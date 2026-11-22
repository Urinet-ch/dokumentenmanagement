<?php

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

final class Urinet_Archiv_Client {
	public static function lesen( string $pfad, array $parameter = array() ) {
		if ( ! in_array( $pfad, array( 'dokumente', 'suche', 'filter' ), true ) || count( $parameter ) > 32 ) {
			return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
		}
		$werte = Urinet_Archiv_Einstellungen::werte();
		try {
			$token = Urinet_Archiv_Einstellungen::entschluesseln( $werte['lese_schluessel'] );
		} catch ( RuntimeException $fehler ) {
			return new WP_Error( 'konfiguration', 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.' );
		}
		if ( '' === $token || '' === $werte['url'] ) {
			return new WP_Error( 'konfiguration', 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.' );
		}
		foreach ( $parameter as $name => $wert ) {
			if ( ! is_string( $name ) || ! preg_match( '/^[a-z][a-z0-9_]{0,63}$/', $name ) || ! is_scalar( $wert ) || strlen( (string) $wert ) > 2000 ) {
				return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
			}
		}
		ksort( $parameter );
		$stufe = Urinet_Archiv_Einstellungen::stufe();
		$cache = 'ua_' . hash( 'sha256', wp_json_encode( array( $werte['url'], hash( 'sha256', $token ), $pfad, $parameter, $stufe ) ) );
		$treffer = get_transient( $cache );
		if ( false !== $treffer ) {
			return $treffer;
		}
		$url = add_query_arg( $parameter, $werte['url'] . '/public/v1/' . $pfad );
		$antwort = wp_safe_remote_get(
			$url,
			array(
				'headers'             => array( 'Authorization' => 'Bearer ' . $token, 'X-Archiv-Zugriff' => $stufe, 'Accept' => 'application/json' ),
				'timeout'             => 15,
				'redirection'         => 0,
				'limit_response_size' => 1048576,
			)
		);
		if ( is_wp_error( $antwort ) || 200 !== wp_remote_retrieve_response_code( $antwort ) ) {
			$status = is_wp_error( $antwort ) ? 0 : wp_remote_retrieve_response_code( $antwort );
			$text = 429 === $status ? 'Gerade sind viele Anfragen aktiv. Bitte versuchen Sie es später erneut.' : 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.';
			if ( 429 === $status ) {
				$problem = json_decode( wp_remote_retrieve_body( $antwort ), true );
				if ( is_array( $problem ) && 'BUDGET' === ( $problem['fehlercode'] ?? '' ) ) {
					$text = 'Der Chatbot ist diesen Monat ausgelastet – die Suche funktioniert weiterhin.';
				}
			}
			return new WP_Error( 'archiv_nicht_verfuegbar', $text );
		}
		$daten = json_decode( wp_remote_retrieve_body( $antwort ), true );
		$pflicht = array( 'dokumente' => array( 'eintraege', 'seite', 'proSeite', 'gesamt' ), 'suche' => array( 'treffer', 'degradiert' ), 'filter' => array( 'reihen' ) );
		if ( ! is_array( $daten ) || array_diff( $pflicht[ $pfad ], array_keys( $daten ) )
			|| ( 'dokumente' === $pfad && ( ! is_array( $daten['eintraege'] ) || ! is_int( $daten['seite'] ) || ! is_int( $daten['proSeite'] ) || ! is_int( $daten['gesamt'] ) ) )
			|| ( 'suche' === $pfad && ( ! is_array( $daten['treffer'] ) || ! is_bool( $daten['degradiert'] ) ) )
			|| ( 'filter' === $pfad && ! is_array( $daten['reihen'] ) )
		) {
			return new WP_Error( 'archiv_antwort', 'Das Archiv hat eine ungültige Antwort geliefert. Bitte versuchen Sie es später erneut.' );
		}
		set_transient( $cache, $daten, (int) $werte['cache_minuten'] * MINUTE_IN_SECONDS );
		return $daten;
	}
}
