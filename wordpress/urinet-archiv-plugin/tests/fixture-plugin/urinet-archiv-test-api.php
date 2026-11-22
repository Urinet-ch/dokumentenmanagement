<?php
/**
 * Plugin Name: UriNet Archiv Test-API
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

if ( ! defined( 'URINET_ARCHIV_KEY' ) ) {
	// phpcs:ignore WordPress.PHP.DiscouragedPHPFunctions.obfuscation_base64_encode -- Schlüssel ist ausschliesslich synthetisch und für wp-env bestimmt.
	define( 'URINET_ARCHIV_KEY', base64_encode( hash( 'sha256', 'urinet-archiv-wp-env-test-only', true ) ) );
}

add_action(
	'plugins_loaded',
	static function (): void {
		$werte                           = Urinet_Archiv_Einstellungen::werte();
		$werte['url']                    = 'https://example.com';
		$werte['lese_schluessel']        = Urinet_Archiv_Einstellungen::verschluesseln( 'synthetisch-lese-schluessel' );
		$werte['erfassung_schluessel']   = Urinet_Archiv_Einstellungen::verschluesseln( 'synthetisch-erfassungsschluessel' );
		$werte['rolle_geschaeftsstelle'] = 'administrator';
		update_option( Urinet_Archiv_Einstellungen::OPTION, $werte, false );
	},
	20
);

add_filter(
	'pre_http_request',
	static function ( $antwort, array $argumente, string $url ) {
		if ( false !== $antwort || 'example.com' !== wp_parse_url( $url, PHP_URL_HOST ) ) {
			return $antwort;
		}

		$pfad = (string) wp_parse_url( $url, PHP_URL_PATH );
		if ( str_ends_with( $pfad, '/erfassung/dokumente' ) ) {
			$daten = array( 'eintraege' => array() );
		} elseif ( str_ends_with( $pfad, '/erfassung/dokumentarten' ) ) {
			$daten = array(
				array(
					'code'          => 'merkblatt',
					'name'          => 'Merkblatt',
					'pflichtfelder' => array( 'ausgabe' ),
					'zusatzSchema'  => array(
						'type'       => 'object',
						'required'   => array( 'ausgabe' ),
						'properties' => array(
							'ausgabe' => array(
								'type'  => 'string',
								'title' => 'Ausgabe',
							),
						),
					),
				),
				array(
					'code'          => 'protokoll',
					'name'          => 'Protokoll',
					'pflichtfelder' => array( 'jahr' ),
					'zusatzSchema'  => array(
						'type'       => 'object',
						'required'   => array( 'jahr' ),
						'properties' => array(
							'jahr' => array(
								'type'  => 'integer',
								'title' => 'Jahr',
							),
						),
					),
				),
			);
		} elseif ( str_ends_with( $pfad, '/aenderungen' ) ) {
			$daten = array(
				'eintraege' => array(),
				'cursor'    => 'synthetischer-cursor',
			);
		} else {
			$daten = array();
		}

		return array(
			'headers'       => array( 'content-type' => 'application/json' ),
			'body'          => wp_json_encode( $daten ),
			'response'      => array(
				'code'    => 200,
				'message' => 'OK',
			),
			'cookies'       => array(),
			'http_response' => null,
		);
	},
	10,
	3
);
