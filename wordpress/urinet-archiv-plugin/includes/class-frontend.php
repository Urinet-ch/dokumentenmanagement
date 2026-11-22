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
		$parameter = array(
			'pro_seite' => isset( $atts['pro_seite'] ) ? min( 100, max( 1, (int) $atts['pro_seite'] ) ) : 20,
			'seite'     => isset( $atts['seite'] ) ? max( 1, (int) $atts['seite'] ) : ( isset( $_GET['seite'] ) ? max( 1, (int) $_GET['seite'] ) : 1 ), // phpcs:ignore WordPress.Security.NonceVerification.Recommended,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Read-only pagination.
		);
		foreach ( array( 'reihe', 'jahr', 'ausgabe', 'fachgebiet', 'sortierung' ) as $feld ) {
			if ( isset( $atts[ $feld ] ) && is_scalar( $atts[ $feld ] ) ) {
				$parameter[ $feld ] = sanitize_text_field( (string) $atts[ $feld ] );
			}
		}
		$filter      = Urinet_Archiv_Client::lesen( 'filter' );
		$filter_html = '';
		if ( ! is_wp_error( $filter ) && isset( $filter['klassifikationen'] ) && is_array( $filter['klassifikationen'] ) ) {
			$filter_html = '<form class="urinet-archiv-filter" method="get">';
			foreach ( $filter['klassifikationen'] as $klassifikation ) {
				if ( ! is_array( $klassifikation ) || empty( $klassifikation['code'] ) || ! is_array( $klassifikation['werte'] ?? null ) ) {
					continue;
				}
				$code         = sanitize_key( (string) $klassifikation['code'] );
				$filter_html .= '<label>' . esc_html( (string) ( $klassifikation['name'] ?? $code ) ) . '<select name="' . esc_attr( $code ) . '"><option value="">Alle</option>';
				foreach ( $klassifikation['werte'] as $wert ) {
					if ( is_array( $wert ) && isset( $wert['code'], $wert['name'] ) ) {
						$selected     = isset( $_GET[ $code ] ) && (string) wp_unslash( $_GET[ $code ] ) === (string) $wert['code'] ? ' selected' : ''; // phpcs:ignore WordPress.Security.NonceVerification.Recommended,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Read-only filter.
						$filter_html .= '<option value="' . esc_attr( (string) $wert['code'] ) . '"' . $selected . '>' . esc_html( (string) $wert['name'] ) . '</option>';
						if ( '' !== $selected ) {
							$parameter[ $code ] = sanitize_text_field( (string) $wert['code'] );
						}
					}
				}
				$filter_html .= '</select></label>';
			}
			$filter_html .= '<button type="submit">Filtern</button></form>';
		}
		$daten = Urinet_Archiv_Client::lesen( 'dokumente', $parameter );
		if ( is_wp_error( $daten ) ) {
			return '<p class="urinet-archiv-fehler">' . esc_html( $daten->get_error_message() ) . '</p>';
		}
		$html = $filter_html . '<ul class="urinet-archiv-liste">';
		foreach ( $daten['eintraege'] as $eintrag ) {
			if ( ! is_array( $eintrag ) || ! isset( $eintrag['id'] ) ) {
				continue;
			}
			$id    = sanitize_text_field( (string) $eintrag['id'] );
			$titel = isset( $eintrag['titel'] ) ? (string) $eintrag['titel'] : $id;
			$html .= '<li><a href="' . esc_url( self::dokument_url( $id ) ) . '">' . esc_html( $titel ) . '</a></li>';
		}
		$html  .= '</ul>';
		$seiten = (int) ceil( $daten['gesamt'] / max( 1, $daten['proSeite'] ) );
		if ( $seiten > 1 ) {
			$html .= '<nav class="urinet-archiv-seiten" aria-label="Seiten">';
			for ( $seite = 1; $seite <= $seiten; $seite++ ) {
				$html .= '<a href="' . esc_url( add_query_arg( 'seite', $seite ) ) . '">' . esc_html( (string) $seite ) . '</a> ';
			}
			$html .= '</nav>';
		}
		return $html;
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
}
