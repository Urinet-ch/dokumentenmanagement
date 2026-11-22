<?php

final class GrundgeruestTest extends WP_UnitTestCase {
	private string $token;

	public function set_up(): void {
		parent::set_up();
		$this->token = 'synthetisch-' . bin2hex( random_bytes( 8 ) );
		update_option(
			Urinet_Archiv_Einstellungen::OPTION,
			array(
				'url'              => 'https://example.invalid',
				'lese_schluessel'  => Urinet_Archiv_Einstellungen::verschluesseln( $this->token ),
				'cache_minuten'    => 10,
				'rolle_mitglieder' => 'subscriber',
				'rolle_vorstand'   => 'editor',
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
		$neu = Urinet_Archiv_Einstellungen::validieren(
			array(
				'url'             => 'https://example.invalid',
				'cache_minuten'   => '10',
				'lese_schluessel' => $this->token,
			)
		);
		update_option( Urinet_Archiv_Einstellungen::OPTION, $neu );
		$werte = Urinet_Archiv_Einstellungen::werte();
		$this->assertSame( $this->token, Urinet_Archiv_Einstellungen::entschluesseln( $werte['lese_schluessel'] ) );
		$this->assertArrayNotHasKey( Urinet_Archiv_Einstellungen::OPTION, get_registered_settings() );
	}

	// phpcs:disable WordPress.WP.AlternativeFunctions.file_system_operations_fopen,WordPress.WP.AlternativeFunctions.file_system_operations_fwrite,WordPress.WP.AlternativeFunctions.file_system_operations_fclose -- Simulierte Eingabeströme im Speicher.
	public function test_json_eingang_ohne_content_length_ist_begrenzt(): void {
		$strom = fopen( 'php://temp', 'w+' );
		$json  = '{"_wpnonce":"test","url":"https://example.invalid","cache_minuten":"10"}';
		fwrite( $strom, $json );
		rewind( $strom );
		$eingabe = Urinet_Archiv_Einstellungen::eingang_lesen( $strom, null, 'application/json' );
		$this->assertIsArray( $eingabe );
		$this->assertSame( '10', $eingabe['cache_minuten'] );
		fclose( $strom );

		$strom = fopen( 'php://temp', 'w+' );
		fwrite( $strom, str_repeat( 'x', Urinet_Archiv_Einstellungen::MAX_EINGANG + 100 ) );
		rewind( $strom );
		$fehler = Urinet_Archiv_Einstellungen::eingang_lesen( $strom, null, 'application/json' );
		$this->assertWPError( $fehler );
		$this->assertSame( 413, $fehler->get_error_data()['status'] );
		$this->assertSame( Urinet_Archiv_Einstellungen::MAX_EINGANG + 1, ftell( $strom ) );
		fclose( $strom );

		$prefix = '{"_wpnonce":"test","cache_minuten":"10","url":"';
		$suffix = '"}';
		$strom  = fopen( 'php://temp', 'w+' );
		fwrite( $strom, $prefix . str_repeat( 'x', Urinet_Archiv_Einstellungen::MAX_EINGANG - strlen( $prefix . $suffix ) ) . $suffix );
		rewind( $strom );
		$this->assertIsArray( Urinet_Archiv_Einstellungen::eingang_lesen( $strom, null, 'application/json' ) );
		fclose( $strom );
	}

	public function test_json_eingang_prueft_laenge_und_festes_schema(): void {
		foreach ( array(
			array( '{"_wpnonce":"test","url":"x","cache_minuten":"10","extra":"x"}', null, 'application/json', 400 ),
			array( '{"_wpnonce":"test","url":"x","cache_minuten":[]}', null, 'application/json', 400 ),
			array( '{}', '8193', 'application/json', 413 ),
			array( '{}', '2', 'application/x-www-form-urlencoded', 415 ),
			array( str_repeat( 'x', 8193 ), '1', 'application/json', 413 ),
		) as $fall ) {
			$strom = fopen( 'php://temp', 'w+' );
			fwrite( $strom, $fall[0] );
			rewind( $strom );
			$fehler = Urinet_Archiv_Einstellungen::eingang_lesen( $strom, $fall[1], $fall[2] );
			$this->assertWPError( $fehler );
			$this->assertSame( $fall[3], $fehler->get_error_data()['status'] );
			fclose( $strom );
		}
	}
	// phpcs:enable WordPress.WP.AlternativeFunctions.file_system_operations_fopen,WordPress.WP.AlternativeFunctions.file_system_operations_fwrite,WordPress.WP.AlternativeFunctions.file_system_operations_fclose

	public function test_erfassungsschluessel_und_geschaeftsstellenrolle(): void {
		$erfassung = 'synthetisch-erfassung-' . bin2hex( random_bytes( 8 ) );
		$neu       = Urinet_Archiv_Einstellungen::validieren(
			array(
				'url'                    => 'https://example.invalid',
				'cache_minuten'          => '10',
				'erfassung_schluessel'   => $erfassung,
				'rolle_geschaeftsstelle' => 'editor',
			)
		);
		$this->assertSame( $erfassung, Urinet_Archiv_Einstellungen::entschluesseln( $neu['erfassung_schluessel'] ) );
		$this->assertStringNotContainsString( $erfassung, $neu['erfassung_schluessel'] );
		$this->assertSame( 'editor', $neu['rolle_geschaeftsstelle'] );
		update_option( Urinet_Archiv_Einstellungen::OPTION, $neu );
		wp_set_current_user( self::factory()->user->create( array( 'role' => 'administrator' ) ) );
		ob_start();
		Urinet_Archiv_Einstellungen::seite();
		$html = ob_get_clean();
		$this->assertStringNotContainsString( $erfassung, $html );
		$this->assertStringNotContainsString( $neu['erfassung_schluessel'], $html );
		$this->assertStringContainsString( 'rolle_geschaeftsstelle', $html );
		$entfernt = Urinet_Archiv_Einstellungen::validieren(
			array(
				'url'                 => 'https://example.invalid',
				'cache_minuten'       => '10',
				'erfassung_entfernen' => '1',
			)
		);
		$this->assertSame( '', $entfernt['erfassung_schluessel'] );
		$this->assertSame( '', $entfernt['rolle_geschaeftsstelle'] );
	}

	public function test_invalidierung_erhaelt_bisherige_einstellungen(): void {
		$alt = Urinet_Archiv_Einstellungen::werte();
		$neu = Urinet_Archiv_Einstellungen::validieren(
			array(
				'url'           => 'http://example.invalid',
				'cache_minuten' => '10',
			)
		);
		$this->assertSame( $alt, $neu );
		$this->assertNotEmpty( get_settings_errors( Urinet_Archiv_Einstellungen::OPTION ) );
	}

	public function test_stufen_und_cache_sind_getrennt(): void {
		$aufrufe = array();
		update_option( Urinet_Archiv_Feed::LESE_OK, time() );
		// phpcs:ignore WordPress.WP.AlternativeFunctions.file_get_contents_file_get_contents -- Lokal erzeugte Testdaten.
		$fixtures = json_decode( file_get_contents( __DIR__ . '/vertrag.json' ), true, 512, JSON_THROW_ON_ERROR );
		$mock     = static function ( $preempt, $args ) use ( &$aufrufe, $fixtures ) {
			$aufrufe[] = $args['headers'];
			return array(
				'response' => array( 'code' => 200 ),
				'body'     => wp_json_encode( $fixtures['dokumente'] + array( 'unbekannt' => 'ignorieren' ) ),
				'headers'  => array(),
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
		$mock    = static function () use ( &$aufrufe ) {
			++$aufrufe;
			return array(
				'response' => array( 'code' => 429 ),
				'body'     => '{"detail":"intern"}',
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$antwort = Urinet_Archiv_Client::lesen( 'suche', array( 'q' => 'test' ) );
		Urinet_Archiv_Client::lesen( 'suche', array( 'q' => 'test' ) );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertWPError( $antwort );
		$this->assertStringNotContainsString( 'intern', $antwort->get_error_message() );
		$this->assertSame( 2, $aufrufe );
	}

	public function test_nicht_erreichbares_archiv_liefert_freundlichen_fehler(): void {
		$mock = static function () {
			return new WP_Error( 'http_request_failed', 'interne technische Angaben' );
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$antwort = Urinet_Archiv_Client::lesen( 'dokumente' );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertWPError( $antwort );
		$this->assertStringContainsString( 'Bitte versuchen Sie es später erneut', $antwort->get_error_message() );
		$this->assertStringNotContainsString( 'interne technische Angaben', $antwort->get_error_message() );
	}

	public function test_ungueltige_antwort_wird_nicht_gecacht(): void {
		$aufrufe = 0;
		$mock    = static function () use ( &$aufrufe ) {
			++$aufrufe;
			return array(
				'response' => array( 'code' => 200 ),
				'body'     => '{"treffer":null,"degradiert":false}',
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$this->assertWPError( Urinet_Archiv_Client::lesen( 'suche' ) );
		$this->assertWPError( Urinet_Archiv_Client::lesen( 'suche' ) );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertSame( 2, $aufrufe );
	}

	public function test_problem_details_verwendet_feste_meldung_statt_detail(): void {
		$mock = static function () {
			return array(
				'response' => array( 'code' => 429 ),
				'body'     => '{"fehlercode":"BUDGET","detail":"intern"}',
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		$antwort = Urinet_Archiv_Client::lesen( 'suche' );
		remove_filter( 'pre_http_request', $mock, 10 );
		$this->assertWPError( $antwort );
		$this->assertStringContainsString( 'diesen Monat ausgelastet', $antwort->get_error_message() );
		$this->assertStringNotContainsString( 'intern', $antwort->get_error_message() );
	}

	public function test_feed_invalidiert_cache_vor_der_ausgabe_geaenderter_dokumente(): void {
		$versionen = array(
			'oeffentlich' => 0,
			'mitglieder'  => 0,
			'vorstand'    => 0,
		);
		update_option( Urinet_Archiv_Feed::CACHE_VERSION, $versionen );
		update_option( Urinet_Archiv_Feed::LESE_CURSOR, 'cursor-alt' );
		update_option( Urinet_Archiv_Feed::LESE_OK, time() - 600 );
		delete_transient( 'urinet_archiv_feed_wartezeit' );
		$cache = 'ua_' . hash( 'sha256', wp_json_encode( array( 'https://example.invalid', hash( 'sha256', $this->token ), 'dokumente', array(), 'oeffentlich', array( 0 ) ) ) );
		set_transient( $cache, array( 'eintraege' => array( array( 'id' => 'veraltet' ) ) ), 10 * MINUTE_IN_SECONDS );
		$mock = static function ( $preempt, $args, $url ) {
			if ( str_ends_with( (string) wp_parse_url( $url, PHP_URL_PATH ), '/aenderungen' ) ) {
				$body = array(
					'eintraege' => array(
						array(
							'dokumentId' => '00000000-0000-4000-8000-000000000001',
							'art'        => 'NICHT_SICHTBAR',
							'stufe'      => 'oeffentlich',
						),
					),
					'cursor'    => 'cursor-neu',
				);
			} else {
				$body = array(
					'eintraege' => array( array( 'id' => 'aktuell' ) ),
					'seite'     => 0,
					'proSeite'  => 20,
					'gesamt'    => 1,
				);
			}
			return array(
				'response' => array( 'code' => 200 ),
				'body'     => wp_json_encode( $body ),
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		try {
			$antwort = Urinet_Archiv_Client::lesen( 'dokumente' );
		} finally {
			remove_filter( 'pre_http_request', $mock, 10 );
		}
		$this->assertSame( 'aktuell', $antwort['eintraege'][0]['id'] );
		$this->assertSame(
			array(
				'oeffentlich' => 1,
				'mitglieder'  => 1,
				'vorstand'    => 1,
			),
			get_option( Urinet_Archiv_Feed::CACHE_VERSION )
		);
	}

	public function test_feed_ausfall_gibt_keinen_veralteten_cache_zurueck(): void {
		update_option(
			Urinet_Archiv_Feed::CACHE_VERSION,
			array(
				'oeffentlich' => 0,
				'mitglieder'  => 0,
				'vorstand'    => 0,
			)
		);
		update_option( Urinet_Archiv_Feed::LESE_CURSOR, 'cursor-alt' );
		update_option( Urinet_Archiv_Feed::LESE_OK, time() - 600 );
		delete_transient( 'urinet_archiv_feed_wartezeit' );
		$cache = 'ua_' . hash( 'sha256', wp_json_encode( array( 'https://example.invalid', hash( 'sha256', $this->token ), 'dokumente', array(), 'oeffentlich', array( 0 ) ) ) );
		set_transient( $cache, array( 'eintraege' => array( array( 'id' => 'veraltet' ) ) ), 10 * MINUTE_IN_SECONDS );
		$mock = static function () {
			return new WP_Error( 'http_request_failed', 'synthetischer Ausfall' );
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		try {
			$antwort = Urinet_Archiv_Client::lesen( 'dokumente' );
		} finally {
			remove_filter( 'pre_http_request', $mock, 10 );
		}
		$this->assertWPError( $antwort );
		$this->assertStringNotContainsString( 'veraltet', wp_json_encode( $antwort ) );
	}

	public function test_erfassungsformular_nutzt_dokumentart_schema_und_sperrt_oeffentlich(): void {
		$werte                           = Urinet_Archiv_Einstellungen::werte();
		$werte['erfassung_schluessel']   = Urinet_Archiv_Einstellungen::verschluesseln( 'synthetisch-erfassung' );
		$werte['rolle_geschaeftsstelle'] = 'editor';
		update_option( Urinet_Archiv_Einstellungen::OPTION, $werte );
		wp_set_current_user( self::factory()->user->create( array( 'role' => 'editor' ) ) );
		$mock = static function ( $preempt, $args, $url ) {
			$daten = str_ends_with( (string) wp_parse_url( $url, PHP_URL_PATH ), '/dokumentarten' )
				? array(
					array(
						'code'          => 'merkblatt',
						'name'          => 'Merkblatt',
						'pflichtfelder' => array( 'ausgabe', 'jahr' ),
						'zusatzSchema'  => array(
							'type'       => 'object',
							'required'   => array( 'ausgabe', 'jahr', 'zugriff' ),
							'properties' => array(
								'ausgabe'         => array(
									'type'  => 'string',
									'title' => 'Ausgabe',
								),
								'jahr'            => array(
									'type'  => 'integer',
									'title' => 'Jahr',
								),
								'zugriff'         => array( 'type' => 'string' ),
								'veroeffentlicht' => array( 'type' => 'boolean' ),
							),
						),
					),
				)
				: array(
					'eintraege' => array(),
					'seite'     => 0,
					'proSeite'  => 50,
					'gesamt'    => 0,
				);
			return array(
				'response' => array( 'code' => 200 ),
				'body'     => wp_json_encode( $daten ),
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Nur der globale Zustand für den direkten Seitentest wird gesichert.
		$vorher = $_GET;
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Direkter Seitentest, keine eingehende Formübermittlung.
		$_GET['page'] = Urinet_Archiv_Erfassung::SEITE;
		try {
			ob_start();
			Urinet_Archiv_Erfassung::seite();
			$html = ob_get_clean();
		} finally {
			$_GET = $vorher;
			remove_filter( 'pre_http_request', $mock, 10 );
		}
		$this->assertStringContainsString( 'urinet_zusatz[merkblatt][ausgabe]', $html );
		$this->assertStringContainsString( 'urinet_zusatz[merkblatt][jahr]', $html );
		$this->assertStringNotContainsString( 'value="oeffentlich"', $html );
		$this->assertStringNotContainsString( 'urinet_zusatz[merkblatt][veroeffentlicht]', $html );
		$this->assertTrue( Urinet_Archiv_Erfassung::hat_zugriff() );
		wp_set_current_user( self::factory()->user->create( array( 'role' => 'administrator' ) ) );
		$this->assertFalse( Urinet_Archiv_Erfassung::hat_zugriff() );
		$werte['erfassung_schluessel'] = '';
		update_option( Urinet_Archiv_Einstellungen::OPTION, $werte );
		wp_set_current_user( self::factory()->user->create( array( 'role' => 'editor' ) ) );
		$this->assertFalse( Urinet_Archiv_Erfassung::hat_zugriff() );
	}

	public function test_erfassungsdaten_normalisiert_das_art_schema_und_verbietet_oeffentlich(): void {
		$werte                         = Urinet_Archiv_Einstellungen::werte();
		$werte['erfassung_schluessel'] = Urinet_Archiv_Einstellungen::verschluesseln( 'synthetisch-erfassung' );
		update_option( Urinet_Archiv_Einstellungen::OPTION, $werte );
		$mock = static function () {
			return array(
				'response' => array( 'code' => 200 ),
				'body'     => wp_json_encode(
					array(
						array(
							'code'          => 'merkblatt',
							'name'          => 'Merkblatt',
							'pflichtfelder' => array( 'ausgabe', 'jahr' ),
							'zusatzSchema'  => array(
								'type'       => 'object',
								'required'   => array( 'ausgabe', 'jahr', 'zugriff' ),
								'properties' => array(
									'ausgabe'         => array( 'type' => 'string' ),
									'jahr'            => array( 'type' => 'integer' ),
									'zugriff'         => array( 'type' => 'string' ),
									'veroeffentlicht' => array( 'type' => 'boolean' ),
								),
							),
						),
					)
				),
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		// phpcs:ignore WordPress.Security.NonceVerification.Missing -- Die private Parsermethode wird direkt im Unit-Test ausgeführt.
		$vorher  = $_POST;
		$eingabe = array(
			'urinet_daten'   => array(
				'art'             => 'merkblatt',
				'titel'           => 'Synthetisches Merkblatt',
				'datum'           => '2026-01-02',
				'vertraulichkeit' => 'NORMAL',
			),
			'urinet_tags'    => 'Bauen, Energie',
			'urinet_zusatz'  => array(
				'merkblatt' => array(
					'ausgabe' => 'Frühjahr',
					'jahr'    => '2026',
				),
			),
			'urinet_zugriff' => 'oeffentlich',
		);
		$method  = new ReflectionMethod( Urinet_Archiv_Erfassung::class, 'form_daten' );
		$method->setAccessible( true );
		try {
			$_POST  = $eingabe;
			$fehler = $method->invoke( null );
			$this->assertWPError( $fehler );
			$_POST['urinet_zugriff'] = 'mitglieder';
			$daten                   = $method->invoke( null );
		} finally {
			$_POST = $vorher;
			remove_filter( 'pre_http_request', $mock, 10 );
		}
		$this->assertSame( array( 'Bauen', 'Energie' ), $daten['tags'] );
		$this->assertSame(
			array(
				'ausgabe' => 'Frühjahr',
				'jahr'    => 2026,
				'zugriff' => 'mitglieder',
			),
			$daten['zusatz']
		);
	}

	public function test_liste_nutzt_oeffentliche_filter_und_seitennavigation(): void {
		update_option(
			Urinet_Archiv_Feed::CACHE_VERSION,
			array(
				'oeffentlich' => time(),
				'mitglieder'  => time(),
				'vorstand'    => time(),
			)
		);
		update_option( Urinet_Archiv_Feed::LESE_OK, time() );
		$aufrufe = array();
		$mock    = static function ( $preempt, $args, $url ) use ( &$aufrufe ) {
			$pfad = (string) wp_parse_url( $url, PHP_URL_PATH );
			if ( str_ends_with( $pfad, '/filter' ) ) {
				$daten = array(
					'reihen'           => array(
						array(
							'code' => 'MERKBLATT',
							'name' => 'Merkblätter',
						),
					),
					'klassifikationen' => array(
						array(
							'code'  => 'fachgebiet',
							'name'  => 'Fachgebiet',
							'werte' => array(
								array(
									'code' => 'bau',
									'name' => 'Bau',
								),
								array(
									'code' => 'energie',
									'name' => 'Energie',
								),
							),
						),
					),
				);
			} else {
				parse_str( (string) wp_parse_url( $url, PHP_URL_QUERY ), $parameter );
				$aufrufe[] = $parameter;
				$seite     = isset( $parameter['seite'] ) ? (int) $parameter['seite'] : 1;
				$daten     = array(
					'eintraege' => array(
						array(
							'id'    => 'dokument-' . ( 1 + ( $seite - 1 ) * 10 ),
							'titel' => 'Synthetisches Merkblatt',
						),
					),
					'seite'     => $seite,
					'proSeite'  => isset( $parameter['pro_seite'] ) ? (int) $parameter['pro_seite'] : 20,
					'gesamt'    => 21,
				);
			}
			return array(
				'response' => array( 'code' => 200 ),
				'body'     => wp_json_encode( $daten ),
				'headers'  => array(),
			);
		};
		add_filter( 'pre_http_request', $mock, 10, 3 );
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Read-only filter parameters for this direct render test.
		$vorher = $_GET;
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Read-only filter parameters for this direct render test.
		$_GET = array(
			'urinet_archiv_filter_fachgebiet' => 'bau',
			'urinet_archiv_jahr'              => '2026',
			'urinet_archiv_pro_seite'         => '10',
			'urinet_archiv_reihe'             => 'MERKBLATT',
			'urinet_archiv_seite'             => '2',
		);
		try {
			$html = Urinet_Archiv_Frontend::liste();
		} finally {
			$_GET = $vorher;
			remove_filter( 'pre_http_request', $mock, 10 );
		}

		$this->assertCount( 1, $aufrufe );
		$this->assertSame(
			array(
				'fachgebiet' => 'bau',
				'jahr'       => '2026',
				'pro_seite'  => '10',
				'reihe'      => 'MERKBLATT',
				'seite'      => '2',
				'sortierung' => 'datum_ab',
			),
			$aufrufe[0]
		);
		$this->assertStringContainsString( 'name="urinet_archiv_filter_fachgebiet"', $html );
		$this->assertStringContainsString( 'value="bau" selected=', $html );
		$this->assertStringContainsString( 'Seite 2 von 3', $html );
		$this->assertStringContainsString( 'rel="prev"', $html );
		$this->assertStringContainsString( 'rel="next"', $html );
		$html = html_entity_decode( $html, ENT_QUOTES );
		$this->assertStringContainsString( 'urinet_archiv_filter_fachgebiet=bau', $html );
		$this->assertStringContainsString( 'urinet_archiv_seite=3', $html );
	}

	public function test_download_slots_laufen_ab_und_loeschen_keine_fremde_lease(): void {
		$client  = str_repeat( 'a', 64 );
		$belegen = new ReflectionMethod( Urinet_Archiv_Client::class, 'download_slot_belegen' );
		$belegen->setAccessible( true );
		$freigeben = new ReflectionMethod( Urinet_Archiv_Client::class, 'download_slot_freigeben' );
		$freigeben->setAccessible( true );
		$erste  = null;
		$zweite = null;
		try {
			$erste  = $belegen->invoke( null, $client );
			$zweite = $belegen->invoke( null, $client );
			$this->assertNotNull( $erste );
			$this->assertNotNull( $zweite );
			$this->assertGreaterThan( time(), $erste['lease']['ablauf'] );
			$this->assertNull( $belegen->invoke( null, $client ) );

			$abgelaufen = array(
				'kennung' => 'abgelaufen',
				'ablauf'  => time() - 1,
			);
			update_option( $erste['name'], $abgelaufen, false );
			$erneuert = $belegen->invoke( null, $client );
			$this->assertSame( $erste['name'], $erneuert['name'] );
			$this->assertNotSame( $abgelaufen['kennung'], $erneuert['lease']['kennung'] );

			$ersetzt = array(
				'kennung' => 'anderer-request',
				'ablauf'  => time() + 1200,
			);
			update_option( $zweite['name'], $ersetzt, false );
			$freigeben->invoke( null, $zweite );
			$this->assertSame( $ersetzt, get_option( $zweite['name'] ) );

			update_option( $zweite['name'], time() - 1201, false );
			$erneuert_alt = $belegen->invoke( null, $client );
			$this->assertSame( $zweite['name'], $erneuert_alt['name'] );
		} finally {
			delete_option( 'ua_download_' . $client . '_1' );
			delete_option( 'ua_download_' . $client . '_2' );
		}
	}

	public function test_download_stream_uebernimmt_content_length_und_gibt_bloecke_aus(): void {
		$stream         = array(
			'inhalt' => '',
			'laenge' => '',
			'start'  => false,
			'typ'    => 'application/octet-stream',
		);
		$header_methode = new ReflectionMethod( Urinet_Archiv_Client::class, 'download_header_callback' );
		$header_methode->setAccessible( true );
		$argumente = array( &$stream );
		$header    = $header_methode->invokeArgs( null, $argumente );
		$header( null, "Content-Type: application/pdf\r\n" );
		$header( null, "Content-Length: 1048580\r\n" );

		$write_methode = new ReflectionMethod( Urinet_Archiv_Client::class, 'download_write_callback' );
		$write_methode->setAccessible( true );
		$argumente = array( &$stream );
		$write     = $write_methode->invokeArgs( null, $argumente );
		ob_start();
		try {
			$this->assertSame( 1048576, $write( null, str_repeat( 'x', 1048576 ) ) );
			$ausgabe = ob_get_contents();
		} finally {
			ob_end_clean();
		}

		$header_methode = new ReflectionMethod( Urinet_Archiv_Client::class, 'download_response_headers' );
		$header_methode->setAccessible( true );
		$header = $header_methode->invoke( null, $stream );
		$this->assertSame( 'application/pdf', $stream['typ'] );
		$this->assertSame( '1048580', $header['Content-Length'] );
		$this->assertSame( 'private, no-store', $header['Cache-Control'] );
		$this->assertTrue( $stream['start'] );
		$this->assertSame( '', $stream['inhalt'] );
		$this->assertSame( 1048576, strlen( $ausgabe ) );
		$this->assertNotSame( (int) $header['Content-Length'], strlen( $ausgabe ) );
	}
}
