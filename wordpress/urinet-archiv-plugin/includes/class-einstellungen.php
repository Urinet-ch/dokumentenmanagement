<?php

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

final class Urinet_Archiv_Einstellungen {
	const OPTION = 'urinet_archiv_einstellungen';

	public static function werte(): array {
		$werte = get_option( self::OPTION, array() );
		return wp_parse_args(
			is_array( $werte ) ? $werte : array(),
			array(
				'url'                    => '',
				'lese_schluessel'        => '',
				'erfassung_schluessel'   => '',
				'cache_minuten'          => 10,
				'rolle_mitglieder'       => '',
				'rolle_vorstand'         => '',
				'rolle_geschaeftsstelle' => '',
			)
		);
	}

	private static function schluessel(): string {
		if ( ! extension_loaded( 'sodium' ) ) {
			throw new RuntimeException( 'Die PHP-Erweiterung sodium fehlt. Bitte lassen Sie sie installieren.' );
		}
		if ( ! defined( 'URINET_ARCHIV_KEY' ) || ! is_string( URINET_ARCHIV_KEY ) ) {
			throw new RuntimeException( 'Der Verschlüsselungsschlüssel fehlt in der WordPress-Konfiguration.' );
		}
		// phpcs:ignore WordPress.PHP.DiscouragedPHPFunctions.obfuscation_base64_decode -- Schlüssel liegt als Base64 vor.
		$key = base64_decode( URINET_ARCHIV_KEY, true );
		if ( false === $key || SODIUM_CRYPTO_SECRETBOX_KEYBYTES !== strlen( $key ) ) {
			throw new RuntimeException( 'Der Verschlüsselungsschlüssel in der WordPress-Konfiguration ist ungültig.' );
		}
		return $key;
	}

	public static function verschluesseln( string $wert ): string {
		$key   = self::schluessel();
		$nonce = random_bytes( SODIUM_CRYPTO_SECRETBOX_NONCEBYTES );
		// phpcs:ignore WordPress.PHP.DiscouragedPHPFunctions.obfuscation_base64_encode -- Binäres Chiffrat für wp_options.
		return base64_encode( $nonce . sodium_crypto_secretbox( $wert, $nonce, $key ) );
	}

	public static function entschluesseln( string $wert ): string {
		if ( '' === $wert ) {
			return '';
		}
		$key = self::schluessel();
		// phpcs:ignore WordPress.PHP.DiscouragedPHPFunctions.obfuscation_base64_decode -- Binäres Chiffrat aus wp_options.
		$bytes = base64_decode( $wert, true );
		if ( false === $bytes || strlen( $bytes ) < SODIUM_CRYPTO_SECRETBOX_NONCEBYTES + SODIUM_CRYPTO_SECRETBOX_MACBYTES ) {
			throw new RuntimeException( 'Der gespeicherte Webseiten-Schlüssel ist ungültig.' );
		}
		$nonce = substr( $bytes, 0, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES );
		$klar  = sodium_crypto_secretbox_open( substr( $bytes, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES ), $nonce, $key );
		if ( false === $klar ) {
			throw new RuntimeException( 'Der Webseiten-Schlüssel kann nicht entschlüsselt werden.' );
		}
		return $klar;
	}

	public static function registrieren(): void {
		add_option( self::OPTION, array(), '', false );
		register_setting( 'urinet_archiv', self::OPTION, array( 'sanitize_callback' => array( self::class, 'validieren' ) ) );
	}

	public static function validieren( $eingabe ): array {
		$alt = self::werte();
		if ( ! is_array( $eingabe ) ) {
			add_settings_error( self::OPTION, 'ungueltig', 'Die Einstellungen sind ungültig. Bitte prüfen Sie Ihre Angaben.' );
			return $alt;
		}
		try {
			foreach ( array( 'url', 'cache_minuten', 'rolle_mitglieder', 'rolle_vorstand', 'rolle_geschaeftsstelle', 'lese_schluessel', 'erfassung_schluessel' ) as $feld ) {
				if ( isset( $eingabe[ $feld ] ) && ! is_string( $eingabe[ $feld ] ) ) {
					throw new InvalidArgumentException( 'Die Einstellungen sind ungültig. Bitte prüfen Sie Ihre Angaben.' );
				}
			}
			$url   = trim( (string) ( $eingabe['url'] ?? '' ) );
			$teile = wp_parse_url( $url );
			if ( strlen( $url ) > 2048 || esc_url_raw( $url, array( 'https' ) ) !== $url || ! is_array( $teile ) || 'https' !== ( $teile['scheme'] ?? '' ) || empty( $teile['host'] ) || isset( $teile['user'] ) || isset( $teile['pass'] ) || isset( $teile['query'] ) || isset( $teile['fragment'] ) ) {
				throw new InvalidArgumentException( 'Bitte geben Sie eine gültige HTTPS-Archiv-URL ohne Zugangsdaten ein.' );
			}
			$minuten = filter_var( $eingabe['cache_minuten'] ?? null, FILTER_VALIDATE_INT );
			if ( false === $minuten || $minuten < 1 || $minuten > 1440 ) {
				throw new InvalidArgumentException( 'Die Cache-Dauer muss zwischen 1 und 1440 Minuten liegen.' );
			}
			$neu                  = $alt;
			$neu['url']           = untrailingslashit( $url );
			$neu['cache_minuten'] = $minuten;
			foreach ( array( 'rolle_mitglieder', 'rolle_vorstand', 'rolle_geschaeftsstelle' ) as $feld ) {
				$rolle = (string) ( $eingabe[ $feld ] ?? '' );
				if ( '' !== $rolle && ! isset( wp_roles()->roles[ $rolle ] ) ) {
					throw new InvalidArgumentException( 'Bitte wählen Sie eine vorhandene WordPress-Rolle.' );
				}
				$neu[ $feld ] = $rolle;
			}
			foreach ( array( 'lese_schluessel', 'erfassung_schluessel' ) as $feld ) {
				$wert = (string) ( $eingabe[ $feld ] ?? '' );
				if ( strlen( $wert ) > 512 || preg_match( '/[\\x00-\\x20\\x7f]/', $wert ) ) {
					throw new InvalidArgumentException( 'Ein Webseiten-Schlüssel ist ungültig.' );
				}
				if ( '' !== $wert ) {
					$neu[ $feld ] = self::verschluesseln( $wert );
				}
			}
			if ( ! empty( $eingabe['erfassung_entfernen'] ) ) {
				$neu['erfassung_schluessel']   = '';
				$neu['rolle_geschaeftsstelle'] = '';
			}
			if ( '' === $neu['erfassung_schluessel'] ) {
				$neu['rolle_geschaeftsstelle'] = '';
			}
			if ( '' === $neu['lese_schluessel'] ) {
				throw new InvalidArgumentException( 'Bitte tragen Sie einen Lese-Schlüssel ein.' );
			}
			return $neu;
		} catch ( InvalidArgumentException | RuntimeException $fehler ) {
			add_settings_error( self::OPTION, 'ungueltig', $fehler->getMessage() );
			return $alt;
		}
	}

	public static function stufe(): string {
		$rollen = wp_get_current_user()->roles;
		$werte  = self::werte();
		if ( '' !== $werte['rolle_vorstand'] && in_array( $werte['rolle_vorstand'], $rollen, true ) ) {
			return 'vorstand';
		}
		if ( '' !== $werte['rolle_mitglieder'] && in_array( $werte['rolle_mitglieder'], $rollen, true ) ) {
			return 'mitglieder';
		}
		return 'oeffentlich';
	}

	public static function menue(): void {
		add_options_page( 'UriNet Archiv', 'UriNet Archiv', 'manage_options', 'urinet-archiv', array( self::class, 'seite' ) );
	}

	public static function seite(): void {
		if ( ! current_user_can( 'manage_options' ) ) {
			wp_die( esc_html__( 'Sie dürfen diese Einstellungen nicht ändern.', 'urinet-archiv' ) );
		}
		$werte = self::werte();
		echo '<div class="wrap"><h1>UriNet Archiv</h1><form action="options.php" method="post">';
		settings_fields( 'urinet_archiv' );
		echo '<table class="form-table" role="presentation"><tbody>';
		foreach ( array(
			'url'           => 'Archiv-URL',
			'cache_minuten' => 'Cache-Dauer in Minuten',
		) as $feld => $label ) {
			printf( '<tr><th scope="row"><label for="%1$s">%2$s</label></th><td><input id="%1$s" name="%3$s[%1$s]" value="%4$s" type="%5$s" class="regular-text"></td></tr>', esc_attr( $feld ), esc_html( $label ), esc_attr( self::OPTION ), esc_attr( (string) $werte[ $feld ] ), 'url' === $feld ? 'url' : 'number' );
		}
		foreach ( array(
			'lese_schluessel'      => 'Lese-Schlüssel',
			'erfassung_schluessel' => 'Erfassungs-Schlüssel (optional)',
		) as $feld => $label ) {
			printf( '<tr><th scope="row"><label for="%1$s">%2$s</label></th><td><input id="%1$s" name="%3$s[%1$s]" type="password" value="" autocomplete="new-password" maxlength="512"><p class="description">Leer lassen, um den bisherigen Schlüssel zu behalten.</p></td></tr>', esc_attr( $feld ), esc_html( $label ), esc_attr( self::OPTION ) );
		}
		echo '<tr><th scope="row">Erfassung entfernen</th><td><label><input type="checkbox" name="' . esc_attr( self::OPTION ) . '[erfassung_entfernen]" value="1"> Erfassungs-Schlüssel entfernen</label></td></tr>';
		$rollenfelder = array(
			'rolle_mitglieder' => 'Rolle Mitglieder',
			'rolle_vorstand'   => 'Rolle Vorstand',
		);
		if ( '' !== $werte['erfassung_schluessel'] ) {
			$rollenfelder['rolle_geschaeftsstelle'] = 'Rolle Geschäftsstelle';
		}
		foreach ( $rollenfelder as $feld => $label ) {
			printf( '<tr><th scope="row"><label for="%1$s">%2$s</label></th><td><select id="%1$s" name="%3$s[%1$s]"><option value="">Keine</option>', esc_attr( $feld ), esc_html( $label ), esc_attr( self::OPTION ) );
			foreach ( wp_roles()->roles as $code => $rolle ) {
				printf( '<option value="%s" %s>%s</option>', esc_attr( $code ), selected( $werte[ $feld ], $code, false ), esc_html( translate_user_role( $rolle['name'] ) ) );
			}
			echo '</select></td></tr>';
		}
		echo '</tbody></table>';
		submit_button();
		echo '</form></div>';
	}
}
