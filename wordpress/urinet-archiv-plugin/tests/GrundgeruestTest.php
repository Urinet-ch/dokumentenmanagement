<?php

final class GrundgeruestTest extends WP_UnitTestCase {
	private string $token;

	public function set_up(): void {
		parent::set_up();
		$this->token = 'synthetisch-' . bin2hex( random_bytes( 8 ) );
		update_option(
			Urinet_Archiv_Einstellungen::OPTION,
			array(
				'url' => 'https://example.invalid',
				'lese_schluessel' => Urinet_Archiv_Einstellungen::verschluesseln( $this->token ),
				'cache_minuten' => 10,
				'rolle_mitglieder' => 'subscriber',
				'rolle_vorstand' => 'editor',
			)
		);
	}

	public function test_verschluesselung_und_keine_ausgabe_des_schluessels(): void {
		$werte = Urinet_Archiv_Einstellungen::werte();
		$this->assertStringNotContainsString( $this->token, $werte['lese_schluessel'] );
		$this->assertSame( $this->token, Urinet_Archiv_Einstellungen::entschluesseln( $werte['lese_schluessel'] ) );
		wp_set_current_user( self::factory()->user->create( array( 'role' => 'administrator' ) ) );
		ob_start();
		Urinet_Archiv_Einstellungen::seite();
		$html = ob_get_clean();
		$this->assertStringNotContainsString( $this->token, $html );
		$this->assertStringNotContainsString( $werte['lese_schluessel'], $html );
	}

	public function test_erste_speicherung_verschluesselt_den_schluessel_einmal(): void {
		delete_option( Urinet_Archiv_Einstellungen::OPTION );
		Urinet_Archiv_Einstellungen::registrieren();
		update_option(
			Urinet_Archiv_Einstellungen::OPTION,
			array( 'url' => 'https://example.invalid', 'cache_minuten' => '10', 'lese_schluessel' => $this->token )
		);
		$werte = Urinet_Archiv_Einstellungen::werte();
		$this->assertSame( $this->token, Urinet_Archiv_Einstellungen::entschluesseln( $werte['lese_schluessel'] ) );
	}

	public function test_invalidierung_erhaelt_bisherige_einstellungen(): void {
		$alt = Urinet_Archiv_Einstellungen::werte();
		$neu = Urinet_Archiv_Einstellungen::validieren( array( 'url' => 'http://example.invalid', 'cache_minuten' => 10 ) );
		$this->assertSame( $alt, $neu );
		$this->assertNotEmpty( get_settings_errors( Urinet_Archiv_Einstellungen::OPTION ) );
	}

	public function test_stufen_und_cache_sind_getrennt(): void {
		$aufrufe = array();
		$fixtures = json_decode( file_get_contents( __DIR__ . '/vertrag.json' ), true, 512, JSON_THROW_ON_ERROR );
		$mock = static function ( $preempt, $args, $url ) use ( &$aufrufe, $fixtures ) {
			$aufrufe[] = $args['headers'];
			return array(
				'response' => array( 'code' => 200 ),
				'body' => wp_json_encode( $fixtures['dokumente'] + array( 'unbekannt' => 'ignorieren' ) ),
				'headers' => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		wp_set_current_user( 0 );
		$this->assertIsArray( Urinet_Archiv_Client::lesen( 'dokumente' ) );
		Urinet_Archiv_Client::lesen( 'dokumente' );
		$mitglied = self::factory()->user->create( array( 'role' => 'subscriber' ) );
		wp_set_current_user( $mitglied );
		$this->assertSame( 'mitglieder', Urinet_Archiv_Einstellungen::stufe() );
		Urinet_Archiv_Client::lesen( 'dokumente' );
		$user = wp_get_current_user();
		$user->add_role( 'editor' );
		$this->assertSame( 'vorstand', Urinet_Archiv_Einstellungen::stufe() );
		Urinet_Archiv_Client::lesen( 'dokumente' );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertCount( 3, $aufrufe );
		$this->assertSame( array( 'oeffentlich', 'mitglieder', 'vorstand' ), array_column( $aufrufe, 'X-Archiv-Zugriff' ) );
		$this->assertSame( 'Bearer ' . $this->token, $aufrufe[0]['Authorization'] );
	}

	public function test_fehlantworten_nicht_gecacht_und_nicht_ausgegeben(): void {
		$aufrufe = 0;
		$mock = static function () use ( &$aufrufe ) {
			++$aufrufe;
			return array( 'response' => array( 'code' => 429 ), 'body' => '{"detail":"intern"}', 'headers' => array() );
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$antwort = Urinet_Archiv_Client::lesen( 'suche', array( 'q' => 'test' ) );
		Urinet_Archiv_Client::lesen( 'suche', array( 'q' => 'test' ) );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertWPError( $antwort );
		$this->assertStringNotContainsString( 'intern', $antwort->get_error_message() );
		$this->assertSame( 2, $aufrufe );
	}

	public function test_ungueltige_antwort_wird_nicht_gecacht(): void {
		$aufrufe = 0;
		$mock = static function () use ( &$aufrufe ) {
			++$aufrufe;
			return array( 'response' => array( 'code' => 200 ), 'body' => '{"treffer":null,"degradiert":false}', 'headers' => array() );
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$this->assertWPError( Urinet_Archiv_Client::lesen( 'suche' ) );
		$this->assertWPError( Urinet_Archiv_Client::lesen( 'suche' ) );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertSame( 2, $aufrufe );
	}

	public function test_problem_details_verwendet_feste_meldung_statt_detail(): void {
		$mock = static function () {
			return array( 'response' => array( 'code' => 429 ), 'body' => '{"fehlercode":"BUDGET","detail":"intern"}', 'headers' => array() );
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$antwort = Urinet_Archiv_Client::lesen( 'suche' );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertWPError( $antwort );
		$this->assertStringContainsString( 'diesen Monat ausgelastet', $antwort->get_error_message() );
		$this->assertStringNotContainsString( 'intern', $antwort->get_error_message() );
	}
}
