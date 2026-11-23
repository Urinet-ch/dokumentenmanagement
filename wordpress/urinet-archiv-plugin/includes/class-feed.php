<?php

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

final class Urinet_Archiv_Feed {
	const CACHE_VERSION    = 'urinet_archiv_cache_version';
	const LESE_CURSOR      = 'urinet_archiv_lese_cursor';
	const LESE_OK          = 'urinet_archiv_lese_feed_ok';
	const ERFASSUNG_CURSOR = 'urinet_archiv_erfassung_cursor';

	public static function registrieren(): void {
		// phpcs:ignore WordPress.WP.CronInterval.CronSchedulesInterval -- Feed muss Änderungen innerhalb der Cache-Frist invalidieren.
		add_filter( 'cron_schedules', array( self::class, 'intervalle' ) );
		add_action( 'init', array( self::class, 'planen' ) );
		add_action( 'urinet_archiv_feed', array( self::class, 'abrufen' ) );
	}

	public static function intervalle( array $intervalle ): array {
		$intervalle['urinet_archiv_5min'] = array(
			'interval' => 5 * MINUTE_IN_SECONDS,
			'display'  => 'Alle fünf Minuten',
		);
		return $intervalle;
	}

	public static function planen(): void {
		if ( ! wp_next_scheduled( 'urinet_archiv_feed' ) ) {
			wp_schedule_event( time() + MINUTE_IN_SECONDS, 'urinet_archiv_5min', 'urinet_archiv_feed' );
		}
	}

	public static function deaktivieren(): void {
		wp_clear_scheduled_hook( 'urinet_archiv_feed' );
	}

	public static function cache_version( string $stufe ): array {
		$versionen = get_option(
			self::CACHE_VERSION,
			array(
				'oeffentlich' => 0,
				'mitglieder'  => 0,
				'vorstand'    => 0,
			)
		);
		return array(
			$versionen[ $stufe ] ?? 0,
		);
	}

	public static function cache_sicher(): bool {
		$letzter_abruf = (int) get_option( self::LESE_OK, 0 );
		if ( $letzter_abruf >= time() - 5 * MINUTE_IN_SECONDS ) {
			return true;
		}
		if ( false !== get_transient( 'urinet_archiv_feed_wartezeit' ) ) {
			return false;
		}
		return ! is_wp_error( self::lese_feed_abrufen() );
	}

	public static function abrufen(): void {
		self::lese_feed_abrufen();
		$werte = Urinet_Archiv_Einstellungen::werte();
		if ( '' !== $werte['erfassung_schluessel'] ) {
			self::erfassungs_feed_abrufen();
		}
	}

	public static function invalidieren( ?string $stufe = null ): void {
		$versionen     = get_option(
			self::CACHE_VERSION,
			array(
				'oeffentlich' => 0,
				'mitglieder'  => 0,
				'vorstand'    => 0,
			)
		);
		$stufen        = array( 'oeffentlich', 'mitglieder', 'vorstand' );
		$start         = null === $stufe ? 0 : array_search( $stufe, $stufen, true );
		$stufen_anzahl = count( $stufen );
		if ( false === $start ) {
			$start = 0;
		}
		for ( $i = $start; $i < $stufen_anzahl; ++$i ) {
			++$versionen[ $stufen[ $i ] ];
		}
		update_option( self::CACHE_VERSION, $versionen, false );
	}

	private static function lese_feed_abrufen() {
		$cursor  = get_option( self::LESE_CURSOR, '' );
		$initial = '' === $cursor;
		if ( $initial ) {
			self::invalidieren();
		}
		for ( $seite = 0; $seite < 5; ++$seite ) {
			$parameter = array( 'max' => 500 );
			if ( '' !== $cursor ) {
				$parameter['seit'] = $cursor;
			}
			$antwort = Urinet_Archiv_Client::anfrage( 'lesen', 'GET', 'aenderungen', $parameter );
			if ( is_wp_error( $antwort ) && 410 === (int) ( $antwort->get_error_data()['status'] ?? 0 ) ) {
				delete_option( self::LESE_CURSOR );
				$cursor = '';
				self::invalidieren();
				$parameter = array( 'max' => 500 );
				$antwort   = Urinet_Archiv_Client::anfrage( 'lesen', 'GET', 'aenderungen', $parameter );
			}
			if ( is_wp_error( $antwort ) || ! self::feed_form_gueltig( $antwort['daten'] ?? null, 'lesen' ) ) {
				self::lese_feed_fehler();
				return Urinet_Archiv_Client::nicht_verfuegbar();
			}
			$daten = $antwort['daten'];
			foreach ( $daten['eintraege'] as $eintrag ) {
				self::invalidieren( $eintrag['stufe'] );
			}
			$cursor = $daten['cursor'];
			update_option( self::LESE_CURSOR, $cursor, false );
			if ( count( $daten['eintraege'] ) < 500 ) {
				update_option( self::LESE_OK, time(), false );
				delete_transient( 'urinet_archiv_feed_wartezeit' );
				return true;
			}
		}
		self::lese_feed_fehler();
		return Urinet_Archiv_Client::nicht_verfuegbar();
	}

	private static function erfassungs_feed_abrufen() {
		$cursor    = get_option( self::ERFASSUNG_CURSOR, '' );
		$parameter = array( 'max' => 500 );
		if ( '' !== $cursor ) {
			$parameter['seit'] = $cursor;
		}
		$antwort = Urinet_Archiv_Client::anfrage( 'erfassung', 'GET', 'erfassung/aenderungen', $parameter );
		if ( is_wp_error( $antwort ) && 410 === (int) ( $antwort->get_error_data()['status'] ?? 0 ) ) {
			delete_option( self::ERFASSUNG_CURSOR );
			$antwort = Urinet_Archiv_Client::anfrage( 'erfassung', 'GET', 'erfassung/aenderungen', array( 'max' => 500 ) );
		}
		if ( is_wp_error( $antwort ) || ! self::feed_form_gueltig( $antwort['daten'] ?? null, 'erfassung' ) ) {
			return Urinet_Archiv_Client::nicht_verfuegbar();
		}
		foreach ( $antwort['daten']['eintraege'] as $eintrag ) {
			self::status_aktualisieren( $eintrag );
		}
		update_option( self::ERFASSUNG_CURSOR, $antwort['daten']['cursor'], false );
		return true;
	}

	private static function feed_form_gueltig( $daten, string $bereich ): bool {
		if ( ! is_array( $daten ) || ! isset( $daten['eintraege'], $daten['cursor'] )
			|| ! is_array( $daten['eintraege'] ) || ! is_string( $daten['cursor'] ) || '' === $daten['cursor']
			|| strlen( $daten['cursor'] ) > 256 || count( $daten['eintraege'] ) > 500
		) {
			return false;
		}
		foreach ( $daten['eintraege'] as $eintrag ) {
			if ( ! is_array( $eintrag ) || ! isset( $eintrag['dokumentId'] )
				|| ! is_string( $eintrag['dokumentId'] )
				|| ! preg_match( '/^[a-f0-9-]{36}$/i', $eintrag['dokumentId'] )
			) {
				return false;
			}
			if ( 'lesen' === $bereich && ( ! isset( $eintrag['art'], $eintrag['stufe'] )
				|| ! in_array( $eintrag['art'], array( 'SICHTBAR', 'GEAENDERT', 'NICHT_SICHTBAR' ), true )
				|| ! in_array( $eintrag['stufe'], array( 'oeffentlich', 'mitglieder', 'vorstand' ), true )
			) ) {
				return false;
			}
			if ( 'erfassung' === $bereich && ( ! array_key_exists( 'referenz', $eintrag )
				|| ! array_key_exists( 'status', $eintrag )
				|| ! array_key_exists( 'version', $eintrag )
			) ) {
				return false;
			}
		}
		return true;
	}

	private static function status_aktualisieren( array $eintrag ): void {
		if ( ! is_string( $eintrag['referenz'] ?? null ) || '' === $eintrag['referenz'] ) {
			return;
		}
		$beitraege = get_posts(
			array(
				'post_type'              => 'urinet_archiv_doc',
				'post_status'            => 'private',
				'posts_per_page'         => 1,
				'fields'                 => 'ids',
				// phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_key -- Eindeutige Referenz, höchstens ein lokaler Eintrag.
				'meta_key'               => '_urinet_archiv_referenz',
				// phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_value -- Eindeutige Referenz, höchstens ein lokaler Eintrag.
				'meta_value'             => $eintrag['referenz'],
				'no_found_rows'          => true,
				'update_post_meta_cache' => false,
				'update_post_term_cache' => false,
			)
		);
		if ( $beitraege ) {
			update_post_meta( $beitraege[0], '_urinet_archiv_status', sanitize_text_field( (string) ( $eintrag['status'] ?? '' ) ) );
			update_post_meta( $beitraege[0], '_urinet_archiv_version', absint( $eintrag['version'] ?? 0 ) );
			update_post_meta( $beitraege[0], '_urinet_archiv_dokument_id', sanitize_text_field( $eintrag['dokumentId'] ) );
		}
	}

	private static function lese_feed_fehler(): void {
		self::invalidieren();
		delete_option( self::LESE_OK );
		set_transient( 'urinet_archiv_feed_wartezeit', 1, MINUTE_IN_SECONDS );
	}
}
