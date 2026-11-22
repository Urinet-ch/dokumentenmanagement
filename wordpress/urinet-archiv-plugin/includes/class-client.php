<?php

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

final class Urinet_Archiv_Client {
	private const DOWNLOAD_SLOT_TTL = 1200;

	public static function chat( string $frage ) {
		$token = self::token( 'lesen' );
		if ( is_wp_error( $token ) ) {
			return $token;
		}
		$werte   = Urinet_Archiv_Einstellungen::werte();
		$antwort = wp_safe_remote_post(
			$werte['url'] . '/public/v1/chat',
			array(
				'headers' => array(
					'Authorization'    => 'Bearer ' . $token,
					'X-Archiv-Zugriff' => Urinet_Archiv_Einstellungen::stufe(),
					'Content-Type'     => 'application/json',
					'Accept'           => 'text/event-stream',
					'X-Archiv-Client'  => self::client_hash(),
				),
				'timeout' => 30,
				'body'    => wp_json_encode( array( 'frage' => $frage ) ),
			)
		);
		if ( is_wp_error( $antwort ) || 200 !== wp_remote_retrieve_response_code( $antwort ) ) {
			return self::nicht_verfuegbar();
		}
		return wp_remote_retrieve_body( $antwort );
	}

	public static function details( string $id ) {
		$antwort = self::anfrage( 'lesen', 'GET', 'dokumente/' . rawurlencode( $id ) );
		return is_wp_error( $antwort ) ? $antwort : $antwort['daten'];
	}

	public static function umleitung( string $url ) {
		$antwort = self::anfrage( 'lesen', 'GET', 'umleitung', array( 'url' => $url ) );
		return is_wp_error( $antwort ) ? $antwort : $antwort['daten'];
	}

	public static function ausliefern( string $id ): void {
		$token = self::token( 'lesen' );
		if ( is_wp_error( $token ) ) {
			status_header( 404 );
			return;
		}
		$lock = self::download_slot_belegen( self::client_hash() );
		if ( null === $lock ) {
			status_header( 429 );
			header( 'Retry-After: 60' );
			echo 'Zu viele Downloads gleichzeitig. Bitte versuchen Sie es später erneut.'; // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- Feste Fehlermeldung.
			return;
		}
		$werte = Urinet_Archiv_Einstellungen::werte();
		$curl  = curl_init( $werte['url'] . '/public/v1/dokumente/' . rawurlencode( $id ) . '/datei' ); // phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_init -- Streaming ohne temporäre Datei ist für diesen Download erforderlich.
		if ( false === $curl ) {
			self::download_slot_freigeben( $lock );
			status_header( 404 );
			return;
		}
		$stream = array(
			'inhalt' => '',
			'laenge' => '',
			'start'  => false,
			'typ'    => 'application/octet-stream',
		);
		curl_setopt_array( // phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_setopt_array -- Streaming ohne temporäre Datei ist für diesen Download erforderlich.
			$curl,
			array(
				CURLOPT_HTTPHEADER     => array( 'Authorization: Bearer ' . $token, 'X-Archiv-Zugriff: ' . Urinet_Archiv_Einstellungen::stufe() ),
				CURLOPT_FOLLOWLOCATION => false,
				CURLOPT_TIMEOUT        => 900,
				CURLOPT_HEADERFUNCTION => self::download_header_callback( $stream ),
				CURLOPT_WRITEFUNCTION  => self::download_write_callback( $stream ),
			)
		);
		$ok     = curl_exec( $curl ); // phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_exec -- Streaming ohne temporäre Datei ist für diesen Download erforderlich.
		$status = (int) curl_getinfo( $curl, CURLINFO_RESPONSE_CODE ); // phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_getinfo -- Streaming ohne temporäre Datei ist für diesen Download erforderlich.
		$error  = curl_error( $curl ); // phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_error -- Streaming ohne temporäre Datei ist für diesen Download erforderlich.
		if ( false === $ok || '' !== $error ) {
			self::download_slot_freigeben( $lock );
			if ( ! $stream['start'] ) {
				status_header( 404 );
			}
			return;
		}
		if ( 429 === $status ) {
			status_header( 429 );
			header( 'Retry-After: 60' );
			header( 'Content-Type: text/plain; charset=utf-8' );
			echo 'Zu viele Anfragen. Bitte versuchen Sie es später erneut.'; // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- Feste Fehlermeldung.
			self::download_slot_freigeben( $lock );
			return;
		}
		if ( 200 === $status ) {
			$json = json_decode( $stream['inhalt'], true );
			if ( is_array( $json ) && isset( $json['url'] ) && is_string( $json['url'] ) && '' !== esc_url_raw( $json['url'] ) ) {
				wp_redirect( esc_url_raw( $json['url'] ), 302 ); // phpcs:ignore WordPress.Security.SafeRedirect.wp_redirect_wp_redirect -- Die API liefert eine vorsignierte Object-Storage-URL.
				self::download_slot_freigeben( $lock );
				return;
			}
		}
		if ( in_array( $status, array( 301, 302 ), true ) ) {
			self::download_slot_freigeben( $lock );
			status_header( 404 );
			return;
		}
		if ( ! $stream['start'] ) {
			status_header( $status );
			self::download_sende_header( $stream );
			echo $stream['inhalt']; // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- Binärdaten des Archivs.
		}
		self::download_slot_freigeben( $lock );
	}

	private static function download_slot_belegen( string $client ): ?array {
		global $wpdb;

		$jetzt = time();
		foreach ( array( 1, 2 ) as $nummer ) {
			$name  = 'ua_download_' . $client . '_' . $nummer;
			$lease = array(
				'kennung' => wp_generate_uuid4(),
				'ablauf'  => $jetzt + self::DOWNLOAD_SLOT_TTL,
			);
			if ( add_option( $name, $lease, '', 'no' ) ) {
				return array(
					'name'  => $name,
					'lease' => $lease,
				);
			}

			$vorhanden = get_option( $name );
			$ablauf    = is_array( $vorhanden ) && isset( $vorhanden['ablauf'] )
				? (int) $vorhanden['ablauf']
				: ( is_numeric( $vorhanden ) ? (int) $vorhanden + self::DOWNLOAD_SLOT_TTL : PHP_INT_MAX );
			if ( $ablauf > $jetzt ) {
				continue;
			}
			// phpcs:ignore WordPress.DB.DirectDatabaseQuery.DirectQuery -- Compare-and-delete keeps another request's replacement lease intact.
			$geloescht = $wpdb->delete(
				$wpdb->options,
				array(
					'option_name'  => $name,
					'option_value' => maybe_serialize( $vorhanden ),
				),
				array( '%s', '%s' )
			);
			if ( 1 !== $geloescht ) {
				continue;
			}
			wp_cache_delete( $name, 'options' );
			if ( add_option( $name, $lease, '', 'no' ) ) {
				return array(
					'name'  => $name,
					'lease' => $lease,
				);
			}
		}
		return null;
	}

	private static function download_slot_freigeben( array $slot ): void {
		global $wpdb;

		// phpcs:ignore WordPress.DB.DirectDatabaseQuery.DirectQuery -- Release only the lease owned by this request.
		$geloescht = $wpdb->delete(
			$wpdb->options,
			array(
				'option_name'  => $slot['name'],
				'option_value' => maybe_serialize( $slot['lease'] ),
			),
			array( '%s', '%s' )
		);
		if ( 1 === $geloescht ) {
			wp_cache_delete( $slot['name'], 'options' );
		}
	}

	private static function download_header_callback( array &$stream ): Closure {
		return static function ( $handle, string $header ) use ( &$stream ): int {
			if ( preg_match( '/^Content-Type:\s*(.+)$/i', $header, $treffer ) ) {
				$stream['typ'] = trim( $treffer[1] );
			}
			if ( preg_match( '/^Content-Length:\s*(\d+)$/i', $header, $treffer ) ) {
				$stream['laenge'] = $treffer[1];
			}
			return strlen( $header );
		};
	}

	private static function download_write_callback( array &$stream ): Closure {
		return static function ( $handle, string $chunk ) use ( &$stream ): int {
			if ( ! $stream['start'] ) {
				$stream['inhalt'] .= $chunk;
				if ( strlen( $stream['inhalt'] ) < 1048576 ) {
					return strlen( $chunk );
				}
				status_header( 200 );
				self::download_sende_header( $stream );
				echo $stream['inhalt']; // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- Binärdaten des Archivs.
				$stream['inhalt'] = '';
				$stream['start']  = true;
			} else {
				echo $chunk; // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- Binärdaten des Archivs.
			}
			return strlen( $chunk );
		};
	}

	private static function download_response_headers( array $stream ): array {
		$header = array(
			'Cache-Control' => 'private, no-store',
			'Content-Type'  => $stream['typ'],
		);
		if ( '' !== $stream['laenge'] ) {
			$header['Content-Length'] = $stream['laenge'];
		}
		return $header;
	}

	private static function download_sende_header( array $stream ): void {
		foreach ( self::download_response_headers( $stream ) as $name => $wert ) {
			header( $name . ': ' . $wert );
		}
	}

	private static function client_hash(): string {
		$benutzer = wp_get_current_user();
		// The cookie is only hashed into an opaque client identifier and never used as a value.
		$kennung = isset( $_SERVER['REMOTE_ADDR'] ) ? (string) wp_unslash( $_SERVER['REMOTE_ADDR'] ) : 'anonym'; // phpcs:ignore WordPress.Security.ValidatedSanitizedInput.InputNotSanitized
		return hash_hmac( 'sha256', $kennung, wp_salt( 'auth' ) . gmdate( 'Y-m-d' ) );
	}

	public static function lesen( string $pfad, array $parameter = array() ) {
		if ( ! in_array( $pfad, array( 'dokumente', 'suche', 'filter' ), true ) || count( $parameter ) > 32 ) {
			return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
		}
		foreach ( $parameter as $name => $wert ) {
			if ( ! is_string( $name ) || ! preg_match( '/^[a-z][a-z0-9_]{0,63}$/', $name ) || ! is_scalar( $wert ) || strlen( (string) $wert ) > 2000 ) {
				return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
			}
		}
		ksort( $parameter );
		$werte   = Urinet_Archiv_Einstellungen::werte();
		$stufe   = Urinet_Archiv_Einstellungen::stufe();
		$version = Urinet_Archiv_Feed::cache_version( $stufe );
		$token   = self::token( 'lesen' );
		if ( is_wp_error( $token ) ) {
			return $token;
		}
		$cache   = 'ua_' . hash( 'sha256', wp_json_encode( array( $werte['url'], hash( 'sha256', $token ), $pfad, $parameter, $stufe, $version ) ) );
		$treffer = get_transient( $cache );
		if ( false !== $treffer ) {
			if ( ! Urinet_Archiv_Feed::cache_sicher() ) {
				return self::nicht_verfuegbar();
			}
			$aktuelle_version = Urinet_Archiv_Feed::cache_version( $stufe );
			if ( $version !== $aktuelle_version ) {
				$version = $aktuelle_version;
				$cache   = 'ua_' . hash( 'sha256', wp_json_encode( array( $werte['url'], hash( 'sha256', $token ), $pfad, $parameter, $stufe, $version ) ) );
				$treffer = get_transient( $cache );
			}
			if ( false !== $treffer ) {
				return $treffer;
			}
		}

		$antwort = self::anfrage( 'lesen', 'GET', $pfad, $parameter );
		if ( is_wp_error( $antwort ) ) {
			return $antwort;
		}
		$daten   = $antwort['daten'];
		$pflicht = array(
			'dokumente' => array( 'eintraege', 'seite', 'proSeite', 'gesamt' ),
			'suche'     => array( 'treffer', 'degradiert' ),
			'filter'    => array( 'reihen', 'klassifikationen' ),
		);
		if ( array_diff( $pflicht[ $pfad ], array_keys( $daten ) )
			|| ( 'dokumente' === $pfad && ( ! is_array( $daten['eintraege'] ) || ! is_int( $daten['seite'] ) || ! is_int( $daten['proSeite'] ) || ! is_int( $daten['gesamt'] ) ) )
			|| ( 'suche' === $pfad && ( ! is_array( $daten['treffer'] ) || ! is_bool( $daten['degradiert'] ) ) )
			|| ( 'filter' === $pfad && ( ! is_array( $daten['reihen'] ) || ! is_array( $daten['klassifikationen'] ) ) )
		) {
			return new WP_Error( 'archiv_antwort', 'Das Archiv hat eine ungültige Antwort geliefert. Bitte versuchen Sie es später erneut.' );
		}
		set_transient( $cache, $daten, (int) $werte['cache_minuten'] * MINUTE_IN_SECONDS );
		return $daten;
	}

	public static function erfassung( string $methode, string $pfad, array $parameter = array(), ?array $daten = null, ?string $etag = null, ?string $bearbeiter = null, ?array $datei = null ) {
		if ( ! self::erfassungspfad_gueltig( $pfad ) || count( $parameter ) > 32 ) {
			return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
		}
		return self::anfrage( 'erfassung', $methode, 'erfassung/' . $pfad, $parameter, $daten, $etag, $bearbeiter, $datei );
	}

	public static function anfrage( string $bereich, string $methode, string $pfad, array $parameter = array(), ?array $daten = null, ?string $etag = null, ?string $bearbeiter = null, ?array $datei = null ) {
		if ( ! in_array( $bereich, array( 'lesen', 'erfassung' ), true )
			|| ! in_array( $methode, array( 'GET', 'POST', 'PATCH', 'PUT' ), true )
			|| ! preg_match( '~^[a-z0-9/_-]{1,160}$~', $pfad )
			|| str_contains( $pfad, '..' )
			|| count( $parameter ) > 32
			|| ( null !== $bearbeiter && ! preg_match( '~^[A-Za-z0-9._:@-]{1,64}$~', $bearbeiter ) )
			|| ( null !== $datei && ! extension_loaded( 'curl' ) )
		) {
			return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
		}
		foreach ( $parameter as $name => $wert ) {
			if ( ! is_string( $name ) || ! preg_match( '/^[a-z][a-z0-9_]{0,63}$/', $name ) || ! is_scalar( $wert ) || strlen( (string) $wert ) > 2000 ) {
				return new WP_Error( 'anfrage_ungueltig', 'Die Anfrage ist ungültig.' );
			}
		}
		$token = self::token( $bereich );
		if ( is_wp_error( $token ) ) {
			return $token;
		}
		$werte = Urinet_Archiv_Einstellungen::werte();
		$kopf  = array(
			'Authorization'   => 'Bearer ' . $token,
			'Accept'          => 'application/json',
			'X-Archiv-Client' => self::client_hash(),
		);
		if ( 'lesen' === $bereich ) {
			$kopf['X-Archiv-Zugriff'] = Urinet_Archiv_Einstellungen::stufe();
		}
		if ( null !== $etag && '' !== $etag ) {
			$kopf['If-Match'] = $etag;
		}
		if ( null !== $bearbeiter ) {
			$kopf['X-Archiv-Bearbeiter'] = $bearbeiter;
		}
		$inhalt = null === $datei ? ( null === $daten ? null : wp_json_encode( $daten ) ) : self::multipart( $datei );
		if ( is_wp_error( $inhalt ) ) {
			return $inhalt;
		}
		if ( null !== $daten && null === $datei ) {
			$kopf['Content-Type'] = 'application/json; charset=utf-8';
		}
		$url     = $werte['url'] . '/public/v1/' . $pfad;
		$antwort = wp_safe_remote_request(
			add_query_arg( $parameter, $url ),
			array(
				'method'              => $methode,
				'headers'             => $kopf,
				'timeout'             => 30,
				'redirection'         => 0,
				'limit_response_size' => 1048576,
				'body'                => $inhalt,
			)
		);
		if ( is_wp_error( $antwort ) ) {
			return self::nicht_verfuegbar();
		}
		$status = wp_remote_retrieve_response_code( $antwort );
		$body   = wp_remote_retrieve_body( $antwort );
		if ( ! in_array( $status, array( 200, 202, 204 ), true ) ) {
			return new WP_Error( 'archiv_nicht_verfuegbar', self::fehlermeldung( $status, $body ), array( 'status' => $status ) );
		}
		$decoded = array();
		if ( 204 !== $status ) {
			$decoded = json_decode( $body, true );
			if ( ! is_array( $decoded ) ) {
				return new WP_Error( 'archiv_antwort', 'Das Archiv hat eine ungültige Antwort geliefert. Bitte versuchen Sie es später erneut.' );
			}
		}
		return array(
			'daten'  => $decoded,
			'etag'   => (string) wp_remote_retrieve_header( $antwort, 'etag' ),
			'status' => $status,
		);
	}

	public static function token( string $bereich ) {
		$werte = Urinet_Archiv_Einstellungen::werte();
		$feld  = 'erfassung' === $bereich ? 'erfassung_schluessel' : 'lese_schluessel';
		try {
			$token = Urinet_Archiv_Einstellungen::entschluesseln( $werte[ $feld ] );
		} catch ( RuntimeException $fehler ) {
			return new WP_Error( 'konfiguration', 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.' );
		}
		if ( '' === $token || '' === $werte['url'] ) {
			return new WP_Error( 'konfiguration', 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.' );
		}
		return $token;
	}

	public static function nicht_verfuegbar(): WP_Error {
		return new WP_Error( 'archiv_nicht_verfuegbar', 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.' );
	}

	private static function multipart( array $datei ) {
		if ( ! isset( $datei['pfad'], $datei['referenz'] ) || ! is_string( $datei['pfad'] ) || ! is_string( $datei['referenz'] )
			|| ! is_file( $datei['pfad'] ) || ! is_readable( $datei['pfad'] )
		) {
			return new WP_Error( 'datei_ungueltig', 'Die Datei konnte nicht gelesen werden.' );
		}
		// cURL streams the PHP upload temporary file; the archive never receives a user-controlled filename.
		return array(
			'datei'    => new CURLFile( $datei['pfad'], (string) ( $datei['typ'] ?? 'application/octet-stream' ), 'upload' ),
			'referenz' => $datei['referenz'],
			'hinweis'  => (string) ( $datei['hinweis'] ?? '' ),
		);
	}

	private static function fehlermeldung( int $status, string $body ): string {
		if ( 429 === $status ) {
			$problem = json_decode( $body, true );
			if ( is_array( $problem ) && 'BUDGET' === ( $problem['fehlercode'] ?? '' ) ) {
				return 'Der Chatbot ist diesen Monat ausgelastet – die Suche funktioniert weiterhin.';
			}
			return 'Gerade sind viele Anfragen aktiv. Bitte versuchen Sie es später erneut.';
		}
		return 'Das Archiv ist momentan nicht verfügbar. Bitte versuchen Sie es später erneut.';
	}

	private static function erfassungspfad_gueltig( string $pfad ): bool {
		return in_array( $pfad, array( 'aenderungen', 'dokumente', 'dokumentarten', 'eingangskorb' ), true )
			|| preg_match( '~^dokumente/[a-f0-9-]{36}(?:/(?:bestaetigen|datei|veroeffentlichen|zurueckziehen|wiederholen))?$~', $pfad )
			|| preg_match( '~^bearbeiter/[A-Za-z0-9._:@-]{1,64}$~', $pfad );
	}
}
