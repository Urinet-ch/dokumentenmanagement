<?php

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

final class Urinet_Archiv_Frontend {
	public static function registrieren(): void {
		add_shortcode( 'urinet_archiv_liste', array( self::class, 'liste' ) );
		add_shortcode( 'urinet_archiv_suche', array( self::class, 'suche' ) );
		add_shortcode( 'urinet_chatbot', array( self::class, 'chatbot' ) );
		self::rewrite();
		add_action( 'template_redirect', array( self::class, 'route' ) );
		add_action( 'rest_api_init', array( self::class, 'rest' ) );
		add_filter( 'rest_pre_serve_request', array( self::class, 'serve_chat' ), 10, 4 );
		add_action( 'wp_enqueue_scripts', array( self::class, 'skripte' ) );
	}

	public static function aktivieren(): void {
		self::rewrite();
		flush_rewrite_rules();
	}

	public static function bloecke(): void {
		foreach ( array( 'liste', 'suche', 'chatbot' ) as $name ) {
			register_block_type(
				'urinet-archiv/' . $name,
				array(
					'render_callback' => array( self::class, $name ),
				)
			);
		}
	}

	public static function rewrite(): void {
		add_rewrite_rule( '^urinet-archiv/dokument/([^/]+)/?$', 'index.php?urinet_archiv_dokument=$matches[1]', 'top' );
		add_rewrite_rule( '^urinet-archiv-datei/([^/]+)/?$', 'index.php?urinet_archiv_datei=$matches[1]', 'top' );
		add_rewrite_rule( '^urinet-archiv-umleitung/?$', 'index.php?urinet_archiv_umleitung=1', 'top' );
		add_rewrite_tag( '%urinet_archiv_dokument%', '([^&]+)' );
		add_rewrite_tag( '%urinet_archiv_datei%', '([^&]+)' );
		add_rewrite_tag( '%urinet_archiv_umleitung%', '([01])' );
	}

	public static function skripte(): void {
		$inhalt = get_post_field( 'post_content', get_queried_object_id() );
		if ( has_shortcode( $inhalt, 'urinet_chatbot' ) || has_block( 'urinet-archiv/chatbot', get_queried_object_id() ) ) {
			wp_enqueue_script(
				'urinet-archiv-chatbot',
				plugins_url( 'assets/chatbot.js', dirname( __DIR__ ) . '/urinet-archiv.php' ),
				array(),
				'0.1.0',
				true
			);
			wp_localize_script(
				'urinet-archiv-chatbot',
				'urinetArchivChatbot',
				array(
					'url'         => esc_url_raw( rest_url( 'urinet-archiv/v1/chat' ) ),
					'dokumentUrl' => esc_url_raw( home_url( '/urinet-archiv/dokument/' ) ),
				)
			);
		}
	}

	public static function liste( array $atts = array() ): string {
		$filter = Urinet_Archiv_Client::lesen( 'filter' );
		if ( is_wp_error( $filter ) || ! isset( $filter['reihen'], $filter['klassifikationen'] )
			|| ! is_array( $filter['reihen'] ) || ! is_array( $filter['klassifikationen'] )
		) {
			$fehler = is_wp_error( $filter ) ? $filter : Urinet_Archiv_Client::nicht_verfuegbar();
			return '<p class="urinet-archiv-fehler">' . esc_html( $fehler->get_error_message() ) . '</p>';
		}

		$reihen = array();
		foreach ( $filter['reihen'] as $reihe ) {
			if ( is_array( $reihe ) && isset( $reihe['code'], $reihe['name'] )
				&& is_string( $reihe['code'] ) && preg_match( '/^[A-Za-z][A-Za-z0-9_-]{0,63}$/', $reihe['code'] )
				&& is_string( $reihe['name'] )
			) {
				$reihen[ $reihe['code'] ] = $reihe['name'];
			}
		}

		$standard_pro_seite = isset( $atts['pro_seite'] ) && is_scalar( $atts['pro_seite'] )
			? min( 100, max( 1, (int) $atts['pro_seite'] ) )
			: 20;
		$pro_seite_eingabe  = self::listenwert( 'pro_seite', (string) $standard_pro_seite );
		$pro_seite          = in_array( (int) $pro_seite_eingabe, array( 10, 20, 50, 100 ), true )
			? (int) $pro_seite_eingabe
			: $standard_pro_seite;
		$seite_eingabe      = self::listenwert(
			'seite',
			isset( $atts['seite'] ) && is_scalar( $atts['seite'] ) ? (string) $atts['seite'] : '1'
		);
		$seite              = ctype_digit( $seite_eingabe ) ? min( 9999, max( 1, (int) $seite_eingabe ) ) : 1;
		$parameter          = array(
			'pro_seite' => $pro_seite,
			'seite'     => $seite,
		);
		$query_parameter    = array(
			'pro_seite' => 'urinet_archiv_pro_seite',
		);

		$reihe = self::listenwert(
			'reihe',
			isset( $atts['reihe'] ) && is_scalar( $atts['reihe'] ) ? sanitize_text_field( (string) $atts['reihe'] ) : ''
		);
		if ( isset( $reihen[ $reihe ] ) ) {
			$parameter['reihe'] = $reihe;
		}
		$query_parameter['reihe'] = 'urinet_archiv_reihe';

		$jahr = self::listenwert(
			'jahr',
			isset( $atts['jahr'] ) && is_scalar( $atts['jahr'] ) ? sanitize_text_field( (string) $atts['jahr'] ) : ''
		);
		if ( preg_match( '/^\d{4}$/', $jahr ) ) {
			$parameter['jahr'] = $jahr;
		} else {
			$jahr = '';
		}
		$query_parameter['jahr'] = 'urinet_archiv_jahr';

		$ausgabe = self::listenwert(
			'ausgabe',
			isset( $atts['ausgabe'] ) && is_scalar( $atts['ausgabe'] ) ? sanitize_text_field( (string) $atts['ausgabe'] ) : ''
		);
		if ( '' !== $ausgabe && strlen( $ausgabe ) <= 2000 ) {
			$parameter['ausgabe'] = $ausgabe;
		} elseif ( strlen( $ausgabe ) > 2000 ) {
			$ausgabe = '';
		}
		$query_parameter['ausgabe'] = 'urinet_archiv_ausgabe';

		$sortierung = self::listenwert(
			'sortierung',
			isset( $atts['sortierung'] ) && is_scalar( $atts['sortierung'] ) ? sanitize_text_field( (string) $atts['sortierung'] ) : 'datum_ab'
		);
		if ( in_array( $sortierung, array( 'datum_ab', 'datum_auf', 'titel' ), true ) ) {
			$parameter['sortierung'] = $sortierung;
		} else {
			$sortierung = 'datum_ab';
		}
		$query_parameter['sortierung'] = 'urinet_archiv_sortierung';

		$klassifikationen = array();
		foreach ( $filter['klassifikationen'] as $klassifikation ) {
			if ( ! is_array( $klassifikation ) || ! isset( $klassifikation['code'], $klassifikation['name'], $klassifikation['werte'] )
				|| ! is_string( $klassifikation['code'] ) || ! preg_match( '/^[a-z][a-z0-9_]{0,63}$/', $klassifikation['code'] )
				|| ! is_string( $klassifikation['name'] ) || ! is_array( $klassifikation['werte'] )
			) {
				continue;
			}
			$werte = array();
			foreach ( $klassifikation['werte'] as $wert ) {
				if ( is_array( $wert ) && isset( $wert['code'], $wert['name'] )
					&& is_string( $wert['code'] ) && preg_match( '/^[A-Za-z][A-Za-z0-9_-]{0,63}$/', $wert['code'] )
					&& is_string( $wert['name'] )
				) {
					$werte[ $wert['code'] ] = $wert['name'];
				}
			}
			$klassifikationen[ $klassifikation['code'] ] = array(
				'name'  => $klassifikation['name'],
				'werte' => $werte,
			);
		}

		$prefix = wp_unique_id( 'urinet-archiv-liste-' );
		$action = get_permalink();
		if ( ! is_string( $action ) || '' === $action ) {
			$action = home_url( '/' );
		}
		$html  = '<div class="urinet-archiv-liste-bereich"><form class="urinet-archiv-filter" method="get" action="' . esc_url( $action ) . '">';
		$html .= self::listen_auswahl(
			$prefix . 'reihe',
			'urinet_archiv_reihe',
			'Reihe',
			$reihen,
			$reihe
		);
		$html .= '<label for="' . esc_attr( $prefix . 'jahr' ) . '">Jahr</label>'
			. '<input id="' . esc_attr( $prefix . 'jahr' ) . '" name="urinet_archiv_jahr" inputmode="numeric" pattern="[0-9]{4}" maxlength="4" value="' . esc_attr( $jahr ) . '">';
		$html .= '<label for="' . esc_attr( $prefix . 'ausgabe' ) . '">Ausgabe</label>'
			. '<input id="' . esc_attr( $prefix . 'ausgabe' ) . '" name="urinet_archiv_ausgabe" maxlength="2000" value="' . esc_attr( $ausgabe ) . '">';
		foreach ( $klassifikationen as $code => $klassifikation ) {
			$standard = isset( $atts[ $code ] ) && is_scalar( $atts[ $code ] )
				? sanitize_text_field( (string) $atts[ $code ] )
				: '';
			$wert     = self::listenwert( 'filter_' . $code, $standard );
			if ( ! isset( $klassifikation['werte'][ $wert ] ) ) {
				$wert = '';
			} elseif ( '' !== $wert ) {
				$parameter[ $code ] = $wert;
			}
			$query_parameter[ $code ] = 'urinet_archiv_filter_' . $code;
			$html                    .= self::listen_auswahl(
				$prefix . $code,
				'urinet_archiv_filter_' . $code,
				$klassifikation['name'],
				$klassifikation['werte'],
				$wert
			);
		}
		$html .= '<label for="' . esc_attr( $prefix . 'sortierung' ) . '">Sortierung</label><select id="' . esc_attr( $prefix . 'sortierung' ) . '" name="urinet_archiv_sortierung">';
		foreach ( array(
			'datum_ab'  => 'Neueste zuerst',
			'datum_auf' => 'Älteste zuerst',
			'titel'     => 'Titel',
		) as $code => $name ) {
			$html .= '<option value="' . esc_attr( $code ) . '"' . selected( $sortierung, $code, false ) . '>' . esc_html( $name ) . '</option>';
		}
		$html              .= '</select>';
		$html              .= '<label for="' . esc_attr( $prefix . 'pro-seite' ) . '">Einträge pro Seite</label><select id="' . esc_attr( $prefix . 'pro-seite' ) . '" name="urinet_archiv_pro_seite">';
		$pro_seite_optionen = array_unique( array( 10, 20, 50, 100, $standard_pro_seite ) );
		sort( $pro_seite_optionen );
		foreach ( $pro_seite_optionen as $option ) {
			$html .= '<option value="' . esc_attr( (string) $option ) . '"' . selected( $pro_seite, $option, false ) . '>' . esc_html( (string) $option ) . '</option>';
		}
		$html .= '</select><button type="submit">Filtern</button></form>';

		$daten = Urinet_Archiv_Client::lesen( 'dokumente', $parameter );
		if ( is_wp_error( $daten ) ) {
			return '<p class="urinet-archiv-fehler">' . esc_html( $daten->get_error_message() ) . '</p>';
		}
		$html .= '<ul class="urinet-archiv-liste">';
		foreach ( $daten['eintraege'] as $eintrag ) {
			if ( ! is_array( $eintrag ) || ! isset( $eintrag['id'] ) ) {
				continue;
			}
			$id    = sanitize_text_field( (string) $eintrag['id'] );
			$titel = isset( $eintrag['titel'] ) ? (string) $eintrag['titel'] : $id;
			$html .= '<li><a href="' . esc_url( self::dokument_url( $id ) ) . '">' . esc_html( $titel ) . '</a></li>';
		}
		$html .= '</ul>';

		$gesamt    = max( 0, (int) $daten['gesamt'] );
		$pro_seite = max( 1, (int) $daten['proSeite'] );
		$seite     = max( 1, (int) $daten['seite'] );
		$seiten    = (int) ceil( $gesamt / $pro_seite );
		if ( $seiten > 1 ) {
			$navigation = array();
			foreach ( $parameter as $feld => $wert ) {
				if ( isset( $query_parameter[ $feld ] ) ) {
					$navigation[ $query_parameter[ $feld ] ] = $wert;
				}
			}
			$navigation['urinet_archiv_pro_seite'] = $pro_seite;
			$html                                 .= '<nav class="urinet-archiv-seiten" aria-label="Seitennavigation">';
			if ( $seite > 1 ) {
				$html .= '<a rel="prev" href="' . esc_url( add_query_arg( $navigation + array( 'urinet_archiv_seite' => $seite - 1 ), $action ) ) . '">Vorherige Seite</a>';
			}
			$html .= '<span>Seite ' . esc_html( (string) $seite ) . ' von ' . esc_html( (string) $seiten ) . '</span>';
			if ( $seite < $seiten ) {
				$html .= '<a rel="next" href="' . esc_url( add_query_arg( $navigation + array( 'urinet_archiv_seite' => $seite + 1 ), $action ) ) . '">Nächste Seite</a>';
			}
			$html .= '</nav>';
		}
		return $html . '</div>';
	}

	public static function suche(): string {
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Public search query is sanitized immediately and has no state-changing effect.
		$query = isset( $_GET['urinet_archiv_q'] ) && is_scalar( $_GET['urinet_archiv_q'] )
			// phpcs:ignore WordPress.Security.NonceVerification.Recommended,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Public search query is read-only.
			? sanitize_text_field( wp_unslash( $_GET['urinet_archiv_q'] ) )
			: '';
		$html = '<form class="urinet-archiv-suche" method="get"><label for="urinet-archiv-q">Archiv durchsuchen</label>'
			. '<input id="urinet-archiv-q" name="urinet_archiv_q" value="' . esc_attr( $query ) . '"><button type="submit">Suchen</button></form>';
		if ( '' === $query ) {
			return $html;
		}
		$daten = Urinet_Archiv_Client::lesen( 'suche', array( 'q' => $query ) );
		if ( is_wp_error( $daten ) ) {
			return $html . '<p class="urinet-archiv-fehler">' . esc_html( $daten->get_error_message() ) . '</p>';
		}
		$html .= '<ul class="urinet-archiv-suche-treffer">';
		foreach ( $daten['treffer'] as $treffer ) {
			if ( ! is_array( $treffer ) || ! isset( $treffer['id'] ) ) {
				continue;
			}
			$id    = sanitize_text_field( (string) $treffer['id'] );
			$titel = isset( $treffer['titel'] ) ? (string) $treffer['titel'] : $id;
			$html .= '<li><a href="' . esc_url( self::dokument_url( $id ) ) . '">' . esc_html( $titel ) . '</a></li>';
		}
		return $html . '</ul>';
	}

	public static function chatbot(): string {
		return '<section class="urinet-archiv-chatbot" data-urinet-chatbot><label for="urinet-archiv-chat-frage">Frage zum Archiv</label>'
			. '<textarea id="urinet-archiv-chat-frage" data-urinet-chat-frage rows="3"></textarea>'
			. '<button type="button" data-urinet-chat-senden>Fragen</button><div data-urinet-chat-ausgabe role="status" aria-live="polite"></div></section>';
	}

	public static function rest(): void {
		register_rest_route(
			'urinet-archiv/v1',
			'/chat',
			array(
				'methods'             => 'POST',
				'permission_callback' => '__return_true',
				'callback'            => array( self::class, 'chat' ),
			)
		);
	}

	public static function chat( WP_REST_Request $request ) {
		$frage = $request->get_param( 'frage' );
		if ( ! is_string( $frage ) || '' === trim( $frage ) || strlen( $frage ) > 500 ) {
			return new WP_Error( 'anfrage_ungueltig', 'Die Frage ist ungültig.', array( 'status' => 400 ) );
		}
		$antwort = Urinet_Archiv_Client::chat( trim( $frage ) );
		if ( is_wp_error( $antwort ) ) {
			return $antwort;
		}
		$response = new WP_REST_Response( $antwort, 200 );
		$response->header( 'Content-Type', 'text/event-stream; charset=utf-8' );
		$response->header( 'Cache-Control', 'no-store' );
		return $response;
	}

	public static function serve_chat( $served, $result, $request, $server ): bool {
		unset( $server );
		if ( ! $request instanceof WP_REST_Request || '/urinet-archiv/v1/chat' !== $request->get_route()
			|| ! $result instanceof WP_REST_Response ) {
			return $served;
		}
		header( 'Content-Type: text/event-stream; charset=utf-8' );
		header( 'Cache-Control: no-store' );
		echo (string) $result->get_data(); // phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- SSE-Daten des Archivs.
		return true;
	}

	public static function route(): void {
		$id = get_query_var( 'urinet_archiv_dokument' );
		if ( '' !== $id ) {
			$daten = Urinet_Archiv_Client::details( $id );
			if ( ! is_wp_error( $daten ) ) {
				status_header( 200 );
				nocache_headers();
				$titel = isset( $daten['titel'] ) ? (string) $daten['titel'] : $id;
				echo '<main class="urinet-archiv-dokument"><h1>' . esc_html( $titel ) . '</h1>';
				if ( isset( $daten['auszug'] ) ) {
					echo '<p>' . esc_html( (string) $daten['auszug'] ) . '</p>';
				}
				echo '<a href="' . esc_url( home_url( '/urinet-archiv-datei/' . rawurlencode( $id ) ) ) . '">Datei öffnen</a></main>';
				exit;
			}
			status_header( 404 );
			exit;
		}
		if ( get_query_var( 'urinet_archiv_umleitung' ) ) {
			// phpcs:ignore WordPress.Security.NonceVerification.Recommended,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Public legacy URL lookup is read-only and sanitized immediately.
			$alt   = isset( $_GET['url'] ) && is_scalar( $_GET['url'] ) ? esc_url_raw( wp_unslash( $_GET['url'] ) ) : '';
			$daten = Urinet_Archiv_Client::umleitung( $alt );
			if ( ! is_wp_error( $daten ) && isset( $daten['dokumentId'] ) ) {
				wp_redirect( self::dokument_url( (string) $daten['dokumentId'] ), 301 ); // phpcs:ignore WordPress.Security.SafeRedirect.wp_redirect_wp_redirect -- Ziel wird aus der validierten Archivantwort gebildet.
				exit;
			}
			status_header( 404 );
			exit;
		}
		$datei = get_query_var( 'urinet_archiv_datei' );
		if ( '' !== $datei ) {
			Urinet_Archiv_Client::ausliefern( (string) $datei );
			exit;
		}
	}

	private static function dokument_url( string $id ): string {
		return home_url( '/urinet-archiv/dokument/' . rawurlencode( $id ) );
	}

	private static function listenwert( string $feld, string $standard ): string {
		$name = 'urinet_archiv_' . $feld;
		// phpcs:disable WordPress.Security.NonceVerification.Recommended -- Public filter values are read-only and do not change state.
		if ( ! isset( $_GET[ $name ] ) || ! is_scalar( $_GET[ $name ] ) ) {
			return $standard;
		}
		$wert = sanitize_text_field( wp_unslash( (string) $_GET[ $name ] ) );
		// phpcs:enable WordPress.Security.NonceVerification.Recommended
		return $wert;
	}

	private static function listen_auswahl( string $id, string $name, string $label, array $werte, string $ausgewaehlt ): string {
		$html = '<label for="' . esc_attr( $id ) . '">' . esc_html( $label ) . '</label><select id="' . esc_attr( $id ) . '" name="' . esc_attr( $name ) . '"><option value="">Alle</option>';
		foreach ( $werte as $code => $titel ) {
			$html .= '<option value="' . esc_attr( (string) $code ) . '"' . selected( $ausgewaehlt, (string) $code, false ) . '>' . esc_html( (string) $titel ) . '</option>';
		}
		return $html . '</select>';
	}
}
