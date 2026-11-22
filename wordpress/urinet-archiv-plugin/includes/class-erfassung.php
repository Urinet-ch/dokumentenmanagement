<?php

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

final class Urinet_Archiv_Erfassung {
	const SEITE            = 'urinet-archiv-erfassung';
	const MAX_DATEI_BYTES  = 52428800;
	const MAX_META_BYTES   = 16384;
	const META_REFERENZ    = '_urinet_archiv_referenz';
	const META_DOKUMENT_ID = '_urinet_archiv_dokument_id';
	const META_STATUS      = '_urinet_archiv_status';
	const META_VERSION     = '_urinet_archiv_version';
	const META_ENTWURF     = '_urinet_archiv_entwurf';

	public static function post_type(): void {
		register_post_type(
			'urinet_archiv_doc',
			array(
				'public'   => false,
				'show_ui'  => false,
				'label'    => 'Archivdokumente',
				'supports' => array(),
			)
		);
	}

	public static function menue(): void {
		add_menu_page( 'UriNet Archiv', 'Archiv', 'read', self::SEITE, array( self::class, 'seite' ), 'dashicons-archive' );
	}

	public static function skript( string $hook ): void {
		if ( 'toplevel_page_' . self::SEITE === $hook ) {
			wp_enqueue_script( 'urinet-archiv-erfassung', plugins_url( '../assets/erfassung.js', __FILE__ ), array(), '0.1.0', true );
		}
	}

	public static function verarbeiten(): void {
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Nur Routenauswahl; die Aktion prüft danach den Nonce.
		$seite = isset( $_GET['page'] ) ? sanitize_key( wp_unslash( $_GET['page'] ) ) : '';
		// phpcs:ignore WordPress.Security.NonceVerification.Missing -- Der Nonce wird vor jeder Aktion geprüft.
		$methode = isset( $_SERVER['REQUEST_METHOD'] ) ? sanitize_text_field( wp_unslash( $_SERVER['REQUEST_METHOD'] ) ) : '';
		if ( self::SEITE !== $seite || 'POST' !== $methode ) {
			return;
		}
		if ( ! self::hat_zugriff() ) {
			wp_die( esc_html__( 'Sie dürfen die Erfassung nicht verwenden.', 'urinet-archiv' ), '', array( 'response' => 403 ) );
		}
		// phpcs:ignore WordPress.Security.NonceVerification.Missing -- Geprüft nach der Grössenbegrenzung und vor dem Verarbeiten.
		$nonce = isset( $_POST['_wpnonce'] ) && is_string( $_POST['_wpnonce'] ) ? sanitize_text_field( wp_unslash( $_POST['_wpnonce'] ) ) : '';
		if ( ! wp_verify_nonce( $nonce, 'urinet_archiv_erfassung' ) ) {
			self::zurueck( 'sitzung' );
		}
		// phpcs:ignore WordPress.Security.NonceVerification.Missing -- Der Aktionswert wird gegen eine feste Liste geprüft.
		$aktion = isset( $_POST['urinet_aktion'] ) && is_string( $_POST['urinet_aktion'] ) ? sanitize_key( wp_unslash( $_POST['urinet_aktion'] ) ) : '';
		if ( 'hochladen' === $aktion ) {
			self::hochladen();
			return;
		}
		self::dokumentaktion( $aktion );
	}

	public static function seite(): void {
		if ( ! self::hat_zugriff() ) {
			wp_die( esc_html__( 'Die Erfassung ist für diese WordPress-Rolle nicht freigegeben.', 'urinet-archiv' ), '', array( 'response' => 403 ) );
		}
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Nur die Dokument-ID zur Anzeige; Schreibaktionen benötigen einen Nonce.
		$id = isset( $_GET['dokument'] ) && is_string( $_GET['dokument'] ) ? sanitize_text_field( wp_unslash( $_GET['dokument'] ) ) : '';
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Der Nonce wird direkt geprüft, bevor der Stream geöffnet wird.
		$aktion = isset( $_GET['aktion'] ) && is_string( $_GET['aktion'] ) ? sanitize_key( wp_unslash( $_GET['aktion'] ) ) : '';
		if ( 'datei' === $aktion ) {
			// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Der Nonce wird für genau dieses Dokument geprüft.
			$nonce = isset( $_GET['_wpnonce'] ) && is_string( $_GET['_wpnonce'] ) ? sanitize_text_field( wp_unslash( $_GET['_wpnonce'] ) ) : '';
			if ( ! self::uuid( $id ) || ! wp_verify_nonce( $nonce, 'urinet_archiv_datei_' . $id ) ) {
				wp_die( esc_html__( 'Die Datei kann nicht geöffnet werden. Bitte laden Sie die Seite neu.', 'urinet-archiv' ), '', array( 'response' => 403 ) );
			}
			self::datei_ausliefern( $id );
		}
		echo '<div class="wrap"><h1>Archiv – Geschäftsstelle</h1>';
		self::meldung();
		if ( '' !== $id ) {
			self::detail( $id );
		} else {
			self::liste();
			self::uploadformular();
		}
		echo '</div>';
	}

	public static function hat_zugriff(): bool {
		$werte = Urinet_Archiv_Einstellungen::werte();
		if ( '' === $werte['erfassung_schluessel'] || '' === $werte['rolle_geschaeftsstelle'] ) {
			return false;
		}
		return in_array( $werte['rolle_geschaeftsstelle'], wp_get_current_user()->roles, true );
	}

	private static function liste(): void {
		$antwort = Urinet_Archiv_Client::erfassung(
			'GET',
			'dokumente',
			array(
				'seite'   => 0,
				'groesse' => 50,
			)
		);
		if ( is_wp_error( $antwort ) ) {
			self::fehler( $antwort );
			return;
		}
		$eintraege = $antwort['daten']['eintraege'] ?? null;
		if ( ! is_array( $eintraege ) ) {
			self::hinweis( 'Das Archiv hat eine ungültige Antwort geliefert.' );
			return;
		}
		echo '<h2>Dokumente</h2>';
		if ( ! $eintraege ) {
			echo '<p>Es sind keine Dokumente vorhanden.</p>';
			return;
		}
		echo '<table class="widefat striped"><thead><tr><th>Titel</th><th>Reihe</th><th>Jahr</th><th>Status</th><th>Aktion</th></tr></thead><tbody>';
		foreach ( $eintraege as $eintrag ) {
			if ( ! is_array( $eintrag ) || ! isset( $eintrag['id'], $eintrag['status'] ) || ! self::uuid( $eintrag['id'] ) ) {
				continue;
			}
			$url = add_query_arg(
				array(
					'page'     => self::SEITE,
					'dokument' => $eintrag['id'],
				),
				admin_url( 'admin.php' )
			);
			echo '<tr><td>' . esc_html( (string) ( $eintrag['titel'] ?? 'Ohne Titel' ) ) . '</td><td>' . esc_html( (string) ( $eintrag['reihe'] ?? '' ) ) . '</td><td>' . esc_html( (string) ( $eintrag['jahr'] ?? '' ) ) . '</td><td>' . esc_html( (string) $eintrag['status'] ) . '</td><td><a href="' . esc_url( $url ) . '">Ansehen</a></td></tr>';
		}
		echo '</tbody></table>';
	}

	private static function uploadformular(): void {
		$antwort = Urinet_Archiv_Client::erfassung( 'GET', 'dokumentarten' );
		if ( is_wp_error( $antwort ) || ! is_array( $antwort['daten'] ?? null ) ) {
			self::fehler( is_wp_error( $antwort ) ? $antwort : Urinet_Archiv_Client::nicht_verfuegbar() );
			return;
		}
		$arten = $antwort['daten'];
		if ( ! $arten ) {
			self::hinweis( 'Es sind keine aktiven Dokumentarten eingerichtet.' );
			return;
		}
		echo '<h2>Publikation erfassen</h2><form method="post" enctype="multipart/form-data">';
		wp_nonce_field( 'urinet_archiv_erfassung' );
		echo '<input type="hidden" name="urinet_aktion" value="hochladen">';
		echo '<p><label>Datei (höchstens 50 MiB)<br><input type="file" name="urinet_datei" required></label></p>';
		self::art_feld( $arten );
		self::feste_felder();
		self::zugriff_feld();
		self::zusatz_felder( $arten );
		echo '<p><label>Hinweis an die Verarbeitung (optional)<br><input type="text" name="urinet_hinweis" maxlength="1000" class="regular-text"></label></p>';
		submit_button( 'Hochladen' );
		echo '</form><p>Die Datei wird direkt an das Archiv übertragen und nicht in der WordPress-Mediathek abgelegt.</p>';
	}

	private static function feste_felder( array $werte = array() ): void {
		$felder = array(
			'titel' => array( 'Titel', 'text' ),
			'datum' => array( 'Erscheinungsdatum', 'date' ),
		);
		foreach ( $felder as $feld => $definition ) {
			$wert = $werte[ $feld ] ?? '';
			printf(
				'<p><label>%1$s<br><input type="%2$s" name="urinet_daten[%3$s]" value="%4$s" maxlength="200" class="regular-text" %5$s></label></p>',
				esc_html( $definition[0] ),
				esc_attr( $definition[1] ),
				esc_attr( $feld ),
				esc_attr( is_scalar( $wert ) ? (string) $wert : '' ),
				'titel' === $feld ? 'required' : ''
			);
		}
		$vertraulichkeit = $werte['vertraulichkeit'] ?? 'NORMAL';
		echo '<p><label>Vertraulichkeit<br><select name="urinet_daten[vertraulichkeit]">';
		foreach ( array( 'NORMAL', 'INTERN', 'PERSONAL' ) as $wert ) {
			printf( '<option value="%s" %s>%s</option>', esc_attr( $wert ), selected( $vertraulichkeit, $wert, false ), esc_html( $wert ) );
		}
		echo '</select></label></p>';
		$tags = $werte['tags'] ?? array();
		$tags = is_array( $tags ) ? implode( ', ', array_map( 'strval', $tags ) ) : '';
		printf( '<p><label>Themen (durch Komma getrennt)<br><input type="text" name="urinet_tags" value="%s" maxlength="2000" class="regular-text"></label></p>', esc_attr( $tags ) );
	}

	private static function zugriff_feld( array $werte = array() ): void {
		$aktuell = $werte['zusatz']['zugriff'] ?? $werte['zugriff'] ?? 'mitglieder';
		echo '<p><label>Zugriffsstufe<br><select name="urinet_zugriff" required>';
		foreach ( array(
			'mitglieder' => 'Mitglieder',
			'vorstand'   => 'Vorstand',
		) as $code => $text ) {
			printf( '<option value="%s" %s>%s</option>', esc_attr( $code ), selected( $aktuell, $code, false ), esc_html( $text ) );
		}
		echo '</select></label></p>';
	}

	private static function art_feld( array $arten, string $aktuell = '' ): void {
		$codes = array();
		foreach ( $arten as $art ) {
			if ( is_array( $art ) && is_string( $art['code'] ?? null ) && preg_match( '/^[A-Za-z0-9._-]{1,64}$/', $art['code'] ) ) {
				$codes[] = $art['code'];
			}
		}
		if ( ! in_array( $aktuell, $codes, true ) ) {
			$aktuell = $codes[0] ?? '';
		}
		echo '<p><label>Dokumentart<br><select name="urinet_daten[art]" data-urinet-art required>';
		foreach ( $arten as $art ) {
			if ( is_array( $art ) && isset( $art['code'], $art['name'] ) && is_string( $art['code'] ) ) {
				printf( '<option value="%s" %s>%s</option>', esc_attr( $art['code'] ), selected( $aktuell, $art['code'], false ), esc_html( (string) $art['name'] ) );
			}
		}
		echo '</select></label></p>';
	}

	private static function zusatz_felder( array $arten, array $werte = array(), string $aktuell = '' ): void {
		$codes = array();
		foreach ( $arten as $art ) {
			if ( is_array( $art ) && is_string( $art['code'] ?? null ) && preg_match( '/^[A-Za-z0-9._-]{1,64}$/', $art['code'] ) ) {
				$codes[] = $art['code'];
			}
		}
		if ( ! in_array( $aktuell, $codes, true ) ) {
			$aktuell = $codes[0] ?? '';
		}
		foreach ( $arten as $art ) {
			if ( ! is_array( $art ) || ! is_string( $art['code'] ?? null ) || ! preg_match( '/^[A-Za-z0-9._-]{1,64}$/', $art['code'] ) ) {
				continue;
			}
			$eigenschaften = $art['zusatzSchema']['properties'] ?? array();
			if ( ! is_array( $eigenschaften ) ) {
				continue;
			}
			$pflicht   = $art['zusatzSchema']['required'] ?? array();
			$pflicht   = is_array( $pflicht ) ? array_merge( $pflicht, is_array( $art['pflichtfelder'] ?? null ) ? $art['pflichtfelder'] : array() ) : array();
			$aktiv     = $art['code'] === $aktuell;
			$attribute = $aktiv ? '' : 'hidden disabled';
			printf( '<fieldset data-urinet-zusatz-art="%s" %s><legend>%s</legend>', esc_attr( $art['code'] ), esc_attr( $attribute ), esc_html( (string) ( $art['name'] ?? $art['code'] ) ) );
			foreach ( $eigenschaften as $name => $schema ) {
				if ( ! is_string( $name ) || ! preg_match( '/^[A-Za-z0-9._-]{1,64}$/', $name )
					|| in_array( $name, array( 'zugriff', 'veroeffentlicht' ), true ) || ! is_array( $schema )
				) {
					continue;
				}
				self::zusatz_feld( $art['code'], $name, $schema, $werte[ $name ] ?? '', in_array( $name, $pflicht, true ) );
			}
			echo '</fieldset>';
		}
	}

	private static function zusatz_feld( string $art, string $name, array $schema, $wert, bool $pflicht ): void {
		$bezeichnung = is_string( $schema['title'] ?? null ) ? $schema['title'] : $name;
		$typ         = $schema['type'] ?? 'string';
		if ( is_array( $typ ) ) {
			$typ = reset( $typ );
		}
		$name_attribut = 'urinet_zusatz[' . $art . '][' . $name . ']';
		if ( is_array( $schema['enum'] ?? null ) ) {
			printf( '<p><label>%s<br><select name="%s" %s>', esc_html( $bezeichnung ), esc_attr( $name_attribut ), $pflicht ? 'required' : '' );
			echo '<option value="">Bitte auswählen</option>';
			foreach ( $schema['enum'] as $option ) {
				if ( is_scalar( $option ) ) {
					printf( '<option value="%s" %s>%s</option>', esc_attr( (string) $option ), selected( (string) $wert, (string) $option, false ), esc_html( (string) $option ) );
				}
			}
			echo '</select></label></p>';
			return;
		}
		if ( 'boolean' === $typ ) {
			printf( '<p><label><input type="checkbox" name="%s" value="1" %s> %s</label></p>', esc_attr( $name_attribut ), checked( (bool) $wert, true, false ), esc_html( $bezeichnung ) );
			return;
		}
		if ( 'array' === $typ && 'string' === ( $schema['items']['type'] ?? '' ) ) {
			$wert = is_array( $wert ) ? implode( ', ', array_map( 'strval', $wert ) ) : '';
			printf( '<p><label>%s (durch Komma getrennt)<br><input type="text" name="%s" value="%s" maxlength="2000" %s></label></p>', esc_html( $bezeichnung ), esc_attr( $name_attribut ), esc_attr( $wert ), $pflicht ? 'required' : '' );
			return;
		}
		if ( in_array( $typ, array( 'object', 'array' ), true ) ) {
			$wert = is_array( $wert ) ? wp_json_encode( $wert ) : '';
			printf( '<p><label>%s als JSON<br><textarea name="%s" rows="3" maxlength="2000" %s>%s</textarea></label></p>', esc_html( $bezeichnung ), esc_attr( $name_attribut ), $pflicht ? 'required' : '', esc_textarea( (string) $wert ) );
			return;
		}
		$input_typ = 'date' === ( $schema['format'] ?? '' ) ? 'date' : ( in_array( $typ, array( 'integer', 'number' ), true ) ? 'number' : 'text' );
		$schritt   = 'integer' === $typ ? '1' : 'any';
		printf( '<p><label>%s<br><input type="%s" name="%s" value="%s" maxlength="2000" %s %s></label></p>', esc_html( $bezeichnung ), esc_attr( $input_typ ), esc_attr( $name_attribut ), esc_attr( is_scalar( $wert ) ? (string) $wert : '' ), 'number' === $input_typ ? 'step="' . esc_attr( $schritt ) . '"' : '', $pflicht ? 'required' : '' );
	}

	private static function detail( string $id ): void {
		if ( ! self::uuid( $id ) ) {
			self::hinweis( 'Das Dokument wurde nicht gefunden.' );
			return;
		}
		$bearbeiter = self::bearbeiter();
		$gemeldet   = self::bearbeiter_melden( $bearbeiter );
		if ( is_wp_error( $gemeldet ) ) {
			self::fehler( $gemeldet );
			return;
		}
		$antwort = Urinet_Archiv_Client::erfassung( 'GET', 'dokumente/' . $id, array(), null, null, $bearbeiter );
		if ( is_wp_error( $antwort ) ) {
			self::fehler( $antwort );
			return;
		}
		$dokument = $antwort['daten'];
		$etag     = $antwort['etag'];
		if ( '' === $etag || ! isset( $dokument['status'] ) ) {
			self::hinweis( 'Das Archiv hat eine ungültige Antwort geliefert.' );
			return;
		}
		printf( '<p><a href="%s">Zurück zur Dokumentliste</a></p>', esc_url( admin_url( 'admin.php?page=' . self::SEITE ) ) );
		echo '<h2>' . esc_html( (string) ( $dokument['titel'] ?? 'Ohne Titel' ) ) . '</h2>';
		echo '<p>Status: ' . esc_html( (string) $dokument['status'] ) . '</p>';
		echo '<p><a href="' . esc_url(
			wp_nonce_url(
				add_query_arg(
					array(
						'page'     => self::SEITE,
						'aktion'   => 'datei',
						'dokument' => $id,
					),
					admin_url( 'admin.php' )
				),
				'urinet_archiv_datei_' . $id
			)
		) . '">Datei ansehen</a></p>';
		$beitrag = self::beitrag_fuer_dokument( $id );
		$entwurf = $beitrag ? get_post_meta( $beitrag, self::META_ENTWURF, true ) : array();
		$werte   = is_array( $entwurf ) ? $entwurf : array();
		foreach ( array( 'art', 'titel', 'datum', 'vertraulichkeit' ) as $feld ) {
			if ( ! isset( $werte[ $feld ] ) && isset( $dokument[ $feld ] ) ) {
				$werte[ $feld ] = $dokument[ $feld ];
			}
		}
		foreach ( array( 'tags', 'zugriff' ) as $feld ) {
			if ( ! isset( $werte[ $feld ] ) && isset( $dokument[ $feld ] ) ) {
				$werte[ $feld ] = $dokument[ $feld ];
			}
		}
		if ( ! isset( $werte['zusatz'] ) && is_array( $dokument['zusatz'] ?? null ) ) {
			$werte['zusatz'] = $dokument['zusatz'];
		}
		if ( in_array( $dokument['status'], array( 'EINGANGSKORB', 'ARCHIVIERT' ), true ) ) {
			self::pflegeformular( $id, $etag, $dokument, $werte );
		}
		if ( 'ARCHIVIERT' === $dokument['status'] ) {
			self::aktionsformular( $id, $etag, ! empty( $dokument['veroeffentlicht'] ) ? 'zurueckziehen' : 'veroeffentlichen' );
		}
		if ( in_array( $dokument['status'], array( 'FEHLER', 'ARCHIVIERUNG_FEHLER' ), true ) ) {
			self::aktionsformular( $id, $etag, 'wiederholen' );
		}
	}

	private static function pflegeformular( string $id, string $etag, array $dokument, array $werte ): void {
		$aktion = 'EINGANGSKORB' === $dokument['status'] ? 'bestaetigen' : 'aendern';
		echo '<h3>' . ( 'bestaetigen' === $aktion ? 'Angaben prüfen und bestätigen' : 'Metadaten ändern' ) . '</h3><form method="post">';
		wp_nonce_field( 'urinet_archiv_erfassung' );
		printf( '<input type="hidden" name="urinet_aktion" value="%s"><input type="hidden" name="urinet_id" value="%s"><input type="hidden" name="urinet_etag" value="%s">', esc_attr( $aktion ), esc_attr( $id ), esc_attr( $etag ) );
		$arten = Urinet_Archiv_Client::erfassung( 'GET', 'dokumentarten' );
		if ( is_wp_error( $arten ) || ! is_array( $arten['daten'] ?? null ) ) {
			self::fehler( is_wp_error( $arten ) ? $arten : Urinet_Archiv_Client::nicht_verfuegbar() );
			return;
		}
		self::art_feld( $arten['daten'], (string) ( $werte['art'] ?? $dokument['art'] ?? '' ) );
		self::feste_felder( $werte );
		self::zugriff_feld( $werte );
		$zusatz = $werte['zusatz'] ?? array();
		self::zusatz_felder( $arten['daten'], is_array( $zusatz ) ? $zusatz : array(), (string) ( $werte['art'] ?? $dokument['art'] ?? '' ) );
		if ( 'aendern' === $aktion ) {
			echo '<p><label>Grund der Änderung<br><input name="urinet_grund" maxlength="500" class="regular-text"></label></p>';
		}
		submit_button( 'bestaetigen' === $aktion ? 'Bestätigen' : 'Änderungen speichern' );
		echo '</form>';
	}

	private static function aktionsformular( string $id, string $etag, string $aktion ): void {
		$texte = array(
			'veroeffentlichen' => 'Veröffentlichen',
			'zurueckziehen'    => 'Zurückziehen',
			'wiederholen'      => 'Wiederholen',
		);
		echo '<form method="post">';
		wp_nonce_field( 'urinet_archiv_erfassung' );
		printf( '<input type="hidden" name="urinet_aktion" value="%s"><input type="hidden" name="urinet_id" value="%s"><input type="hidden" name="urinet_etag" value="%s">', esc_attr( $aktion ), esc_attr( $id ), esc_attr( $etag ) );
		submit_button( $texte[ $aktion ], 'secondary' );
		echo '</form>';
	}

	private static function hochladen(): void {
		$datei   = self::upload_datei();
		$daten   = self::form_daten();
		$hinweis = self::form_string( 'urinet_hinweis', 1000 );
		if ( is_wp_error( $datei ) || is_wp_error( $daten ) || is_wp_error( $hinweis ) ) {
			self::zurueck( 'eingabe' );
		}
		$beitrag = wp_insert_post(
			array(
				'post_type'   => 'urinet_archiv_doc',
				'post_status' => 'private',
				'post_title'  => 'Archivdokument',
			),
			true
		);
		if ( is_wp_error( $beitrag ) ) {
			self::zurueck( 'fehlgeschlagen' );
		}
		$referenz   = 'wp-' . absint( $beitrag );
		$bearbeiter = self::bearbeiter();
		$gemeldet   = self::bearbeiter_melden( $bearbeiter );
		if ( is_wp_error( $gemeldet ) ) {
			wp_delete_post( $beitrag, true );
			self::zurueck( 'fehlgeschlagen' );
		}
		$antwort = Urinet_Archiv_Client::erfassung(
			'POST',
			'dokumente',
			array(),
			null,
			null,
			$bearbeiter,
			array(
				'pfad'     => $datei['tmp_name'],
				'typ'      => $datei['type'],
				'referenz' => $referenz,
				'hinweis'  => $hinweis,
			)
		);
		if ( is_wp_error( $antwort ) ) {
			wp_delete_post( $beitrag, true );
			self::zurueck( 'fehlgeschlagen' );
		}
		if ( ! is_string( $antwort['daten']['id'] ?? null ) || ! self::uuid( $antwort['daten']['id'] ) ) {
			wp_delete_post( $beitrag, true );
			if ( ! empty( $antwort['daten']['dublette'] ) ) {
				self::zurueck( 'dublette' );
			}
			self::zurueck( 'fehlgeschlagen' );
		}
		update_post_meta( $beitrag, self::META_REFERENZ, $referenz );
		update_post_meta( $beitrag, self::META_DOKUMENT_ID, $antwort['daten']['id'] );
		update_post_meta( $beitrag, self::META_STATUS, sanitize_text_field( (string) ( $antwort['daten']['status'] ?? 'NEU' ) ) );
		update_post_meta( $beitrag, self::META_VERSION, absint( $antwort['daten']['version'] ?? 0 ) );
		update_post_meta( $beitrag, self::META_ENTWURF, $daten );
		self::zurueck( 'hochgeladen', $antwort['daten']['id'] );
	}

	private static function dokumentaktion( string $aktion ): void {
		$id         = self::form_string( 'urinet_id', 36 );
		$etag       = self::form_string( 'urinet_etag', 128 );
		$bearbeiter = self::bearbeiter();
		if ( ! in_array( $aktion, array( 'bestaetigen', 'aendern', 'veroeffentlichen', 'zurueckziehen', 'wiederholen' ), true )
			|| is_wp_error( $id ) || ! self::uuid( $id ) || is_wp_error( $etag ) || '' === $etag
			|| is_wp_error( self::bearbeiter_melden( $bearbeiter ) )
		) {
			self::zurueck( 'eingabe' );
		}
		$daten = null;
		if ( in_array( $aktion, array( 'bestaetigen', 'aendern' ), true ) ) {
			$daten = self::form_daten();
			if ( is_wp_error( $daten ) ) {
				self::zurueck( 'eingabe', $id );
			}
			$beitrag = self::beitrag_fuer_referenz( self::referenz_fuer_id( $id ) );
			if ( $beitrag ) {
				update_post_meta( $beitrag, self::META_ENTWURF, $daten );
			}
			if ( 'aendern' === $aktion ) {
				$grund = self::form_string( 'urinet_grund', 500 );
				if ( is_wp_error( $grund ) ) {
					self::zurueck( 'eingabe', $id );
				}
				if ( '' !== $grund ) {
					$daten['grund'] = $grund;
				}
			}
		}
		$pfad    = 'dokumente/' . $id . ( 'bestaetigen' === $aktion ? '/bestaetigen' : ( 'aendern' === $aktion ? '' : '/' . $aktion ) );
		$methode = 'aendern' === $aktion ? 'PATCH' : 'POST';
		$antwort = Urinet_Archiv_Client::erfassung( $methode, $pfad, array(), $daten, $etag, $bearbeiter );
		if ( is_wp_error( $antwort ) ) {
			if ( 412 === (int) ( $antwort->get_error_data()['status'] ?? 0 ) ) {
				$beitrag = self::beitrag_fuer_id( $id );
				if ( $beitrag ) {
					delete_post_meta( $beitrag, self::META_ENTWURF );
				}
				self::zurueck( 'konflikt', $id );
			}
			self::zurueck( 'fehlgeschlagen', $id );
		}
		$beitrag = self::beitrag_fuer_id( $id );
		if ( $beitrag && isset( $antwort['daten']['status'] ) ) {
			update_post_meta( $beitrag, self::META_STATUS, sanitize_text_field( (string) $antwort['daten']['status'] ) );
			update_post_meta( $beitrag, self::META_VERSION, absint( $antwort['daten']['version'] ?? 0 ) );
		}
		self::zurueck( 'gespeichert', $id );
	}

	private static function datei_ausliefern( string $id ): void {
		if ( ! extension_loaded( 'curl' ) ) {
			wp_die( esc_html__( 'Die Datei kann auf diesem Hosting nicht direkt weitergeleitet werden.', 'urinet-archiv' ), '', array( 'response' => 503 ) );
		}
		$token = Urinet_Archiv_Client::token( 'erfassung' );
		if ( is_wp_error( $token ) ) {
			wp_die( esc_html( $token->get_error_message() ), '', array( 'response' => 503 ) );
		}
		$kennung  = self::bearbeiter();
		$gemeldet = self::bearbeiter_melden( $kennung );
		if ( is_wp_error( $gemeldet ) ) {
			wp_die( esc_html__( 'Die Datei kann im Moment nicht geöffnet werden. Bitte versuchen Sie es später erneut.', 'urinet-archiv' ), '', array( 'response' => 503 ) );
		}
		$werte          = Urinet_Archiv_Einstellungen::werte();
		$url            = $werte['url'] . '/public/v1/erfassung/dokumente/' . rawurlencode( $id ) . '/datei';
		$zustand        = array(
			'aktiv'      => false,
			'status'     => 0,
			'typ'        => '',
			'laenge'     => null,
			'bytes'      => 0,
			'ausgegeben' => false,
			'ungueltig'  => false,
		);
		$curl_hook      = static function ( $handle, array $argumente, string $request_url ) use ( &$zustand, $url ): void {
			if ( $url !== $request_url ) {
				return;
			}
			$zustand['aktiv'] = true;
			// phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_setopt -- Begrenzung des Upstream-Stillstands beim Streamen.
			curl_setopt( $handle, CURLOPT_LOW_SPEED_LIMIT, 1 );
			// phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_setopt -- Begrenzung des Upstream-Stillstands beim Streamen.
			curl_setopt( $handle, CURLOPT_LOW_SPEED_TIME, 30 );
			// phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_setopt -- Blockweises Weiterleiten ohne temporäre Datei.
			curl_setopt(
				$handle,
				CURLOPT_HEADERFUNCTION,
				static function ( $curl, string $zeile ) use ( &$zustand ): int {
					if ( preg_match( '~^HTTP/\S+\s+([0-9]{3})~', $zeile, $treffer ) ) {
						$zustand['status'] = (int) $treffer[1];
					} elseif ( 0 === stripos( $zeile, 'Content-Type:' ) ) {
						$zustand['typ'] = strtolower( trim( explode( ';', trim( substr( $zeile, strlen( 'Content-Type:' ) ) ) )[0] ) );
					} elseif ( 0 === stripos( $zeile, 'Content-Length:' ) ) {
						$wert              = trim( substr( $zeile, strlen( 'Content-Length:' ) ) );
						$zustand['laenge'] = ctype_digit( $wert ) ? (int) $wert : null;
					}
					return strlen( $zeile );
				}
			);
			// phpcs:ignore WordPress.WP.AlternativeFunctions.curl_curl_setopt -- Der Response-Body wird nie im HTTP-Client gepuffert.
			curl_setopt(
				$handle,
				CURLOPT_WRITEFUNCTION,
				static function ( $curl, string $block ) use ( &$zustand ): int {
					if ( 200 !== $zustand['status'] ) {
						return strlen( $block );
					}
					if ( ! in_array( $zustand['typ'], array( 'application/pdf', 'application/octet-stream' ), true )
						|| null === $zustand['laenge'] || $zustand['laenge'] > self::MAX_DATEI_BYTES
					) {
						$zustand['ungueltig'] = true;
						return 0;
					}
					if ( ! $zustand['ausgegeben'] ) {
						while ( ob_get_level() > 0 ) {
							ob_end_clean();
						}
						status_header( 200 );
						header( 'Content-Type: ' . $zustand['typ'], true );
						header( 'Content-Length: ' . $zustand['laenge'], true );
						header( 'Cache-Control: private, no-store', true );
						header( 'Content-Disposition: inline', true );
						header( 'Accept-Ranges: none', true );
						header( 'X-Content-Type-Options: nosniff', true );
						$zustand['ausgegeben'] = true;
					}
					$zustand['bytes'] += strlen( $block );
					if ( $zustand['bytes'] > self::MAX_DATEI_BYTES || $zustand['bytes'] > $zustand['laenge'] ) {
						$zustand['ungueltig'] = true;
						return 0;
					}
					// phpcs:ignore WordPress.Security.EscapeOutput.OutputNotEscaped -- Geprüfte Binärdaten des Archivs werden blockweise weitergeleitet.
					echo $block;
					flush();
					return strlen( $block );
				}
			);
		};
		$curl_transport = static function (): bool {
			return true;
		};
		add_filter( 'use_curl_transport', $curl_transport, PHP_INT_MAX );
		add_action( 'http_api_curl', $curl_hook, 10, 3 );
		$antwort = wp_safe_remote_get(
			$url,
			array(
				'headers'             => array(
					'Authorization'       => 'Bearer ' . $token,
					'X-Archiv-Bearbeiter' => $kennung,
					'Accept'              => 'application/pdf, application/octet-stream',
				),
				'timeout'             => 900,
				'redirection'         => 0,
				'limit_response_size' => 0,
			)
		);
		remove_action( 'http_api_curl', $curl_hook, 10 );
		remove_filter( 'use_curl_transport', $curl_transport, PHP_INT_MAX );
		if ( $zustand['ausgegeben'] ) {
			exit;
		}
		$status = is_wp_error( $antwort ) ? 503 : wp_remote_retrieve_response_code( $antwort );
		if ( is_wp_error( $antwort ) || 200 !== $status || $zustand['ungueltig'] || ! $zustand['aktiv'] ) {
			wp_die( esc_html__( 'Die Datei kann im Moment nicht geöffnet werden. Bitte versuchen Sie es später erneut.', 'urinet-archiv' ), '', array( 'response' => 503 ) );
		}
		wp_die( esc_html__( 'Das Archiv hat keine Datei geliefert. Bitte versuchen Sie es später erneut.', 'urinet-archiv' ), '', array( 'response' => 502 ) );
	}

	private static function upload_datei() {
		// phpcs:ignore WordPress.Security.NonceVerification.Missing -- Nonce wird in verarbeiten() vor dem Aufruf geprüft.
		// phpcs:ignore WordPress.Security.NonceVerification.Missing,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- PHP liefert Dateipfad und Grösse; temporärer Upload, Fehlerstatus und Grenze werden geprüft.
		$datei = $_FILES['urinet_datei'] ?? null;
		if ( ! is_array( $datei ) || UPLOAD_ERR_OK !== ( $datei['error'] ?? null ) || ! isset( $datei['tmp_name'], $datei['size'] )
			|| ! is_string( $datei['tmp_name'] ) || ! is_uploaded_file( $datei['tmp_name'] ) || ! is_readable( $datei['tmp_name'] )
		) {
			return new WP_Error( 'datei_ungueltig', 'Bitte wählen Sie eine gültige Datei aus.' );
		}
		$laenge = filesize( $datei['tmp_name'] );
		if ( false === $laenge || $laenge < 1 || $laenge > self::MAX_DATEI_BYTES || absint( $datei['size'] ) !== $laenge ) {
			return new WP_Error( 'datei_zu_gross', 'Die Datei darf höchstens 50 MiB gross sein.' );
		}
		// phpcs:ignore WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Nur Ziffern werden akzeptiert und verglichen.
		$content_laenge = isset( $_SERVER['CONTENT_LENGTH'] ) ? wp_unslash( $_SERVER['CONTENT_LENGTH'] ) : null;
		if ( null !== $content_laenge && ( ! is_string( $content_laenge ) || ! ctype_digit( $content_laenge )
			|| strlen( $content_laenge ) > 10 || (int) $content_laenge > self::MAX_DATEI_BYTES + 65536
		) ) {
			return new WP_Error( 'datei_zu_gross', 'Die Anfrage ist zu gross oder ungültig.' );
		}
		return array(
			'tmp_name' => $datei['tmp_name'],
			'type'     => isset( $datei['type'] ) && is_string( $datei['type'] ) ? sanitize_mime_type( $datei['type'] ) : 'application/octet-stream',
		);
	}

	private static function form_daten() {
		// phpcs:ignore WordPress.Security.NonceVerification.Missing,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Nonce wird vor dem Aufruf geprüft; jedes Feld wird danach bereinigt.
		$eingabe = isset( $_POST['urinet_daten'] ) && is_array( $_POST['urinet_daten'] ) ? wp_unslash( $_POST['urinet_daten'] ) : array();
		$daten   = array();
		foreach ( array( 'art', 'titel', 'datum', 'vertraulichkeit' ) as $feld ) {
			$wert = $eingabe[ $feld ] ?? '';
			if ( ! is_string( $wert ) || strlen( $wert ) > 200 ) {
				return new WP_Error( 'eingabe_ungueltig', 'Die Metadaten sind ungültig oder zu lang.' );
			}
			if ( '' !== $wert ) {
				$daten[ $feld ] = sanitize_text_field( $wert );
			}
		}
		if ( '' === ( $daten['art'] ?? '' ) || '' === ( $daten['titel'] ?? '' ) ) {
			return new WP_Error( 'eingabe_ungueltig', 'Bitte geben Sie Dokumentart und Titel an.' );
		}
		$tags = self::form_string( 'urinet_tags', 2000 );
		if ( is_wp_error( $tags ) ) {
			return $tags;
		}
		if ( '' !== $tags ) {
			$daten['tags'] = array_values( array_unique( array_filter( array_map( 'sanitize_text_field', array_map( 'trim', explode( ',', $tags ) ) ) ) ) );
			if ( count( $daten['tags'] ) > 100 ) {
				return new WP_Error( 'eingabe_ungueltig', 'Es sind höchstens 100 Themen zulässig.' );
			}
		}
		$zusatz = self::form_zusatz( $daten['art'] );
		if ( is_wp_error( $zusatz ) ) {
			return $zusatz;
		}
		if ( $zusatz ) {
			$daten['zusatz'] = $zusatz;
		}
		$json = wp_json_encode( $daten );
		if ( false === $json || strlen( $json ) > self::MAX_META_BYTES ) {
			return new WP_Error( 'eingabe_ungueltig', 'Die Metadaten sind zu gross.' );
		}
		return $daten;
	}

	private static function form_zusatz( string $art_code ) {
		$antwort = Urinet_Archiv_Client::erfassung( 'GET', 'dokumentarten' );
		if ( is_wp_error( $antwort ) || ! is_array( $antwort['daten'] ?? null ) ) {
			return Urinet_Archiv_Client::nicht_verfuegbar();
		}
		$definition = null;
		foreach ( $antwort['daten'] as $art ) {
			if ( is_array( $art ) && ( $art['code'] ?? null ) === $art_code ) {
				$definition = $art;
				break;
			}
		}
		$eigenschaften = $definition['zusatzSchema']['properties'] ?? null;
		$pflicht       = $definition['zusatzSchema']['required'] ?? array();
		$pflicht       = is_array( $pflicht ) ? array_merge( $pflicht, is_array( $definition['pflichtfelder'] ?? null ) ? $definition['pflichtfelder'] : array() ) : array();
		if ( ! is_array( $eigenschaften ) || ! is_array( $pflicht ) ) {
			return new WP_Error( 'eingabe_ungueltig', 'Die Dokumentart ist ungültig.' );
		}
		// phpcs:ignore WordPress.Security.NonceVerification.Missing,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Nonce ist in verarbeiten() geprüft; Werte werden nach Schema typisiert.
		$eingabe = isset( $_POST['urinet_zusatz'][ $art_code ] ) && is_array( $_POST['urinet_zusatz'][ $art_code ] ) ? wp_unslash( $_POST['urinet_zusatz'][ $art_code ] ) : array();
		$zusatz  = array();
		foreach ( $eingabe as $name => $rohwert ) {
			if ( ! is_string( $name ) || ! isset( $eigenschaften[ $name ] ) || in_array( $name, array( 'zugriff', 'veroeffentlicht' ), true ) || ! is_array( $eigenschaften[ $name ] ) ) {
				return new WP_Error( 'eingabe_ungueltig', 'Die Zusatzfelder sind ungültig.' );
			}
		}
		foreach ( $eigenschaften as $name => $schema ) {
			if ( ! is_string( $name ) || in_array( $name, array( 'zugriff', 'veroeffentlicht' ), true ) || ! is_array( $schema ) ) {
				continue;
			}
			$typ = $schema['type'] ?? 'string';
			if ( is_array( $typ ) ) {
				$typ = reset( $typ );
			}
			$rohwert = $eingabe[ $name ] ?? ( 'boolean' === $typ ? '0' : null );
			if ( null === $rohwert || '' === $rohwert ) {
				if ( in_array( $name, $pflicht, true ) ) {
					return new WP_Error( 'eingabe_ungueltig', 'Bitte füllen Sie alle Pflichtfelder aus.' );
				}
				if ( array_key_exists( 'default', $schema ) ) {
					$zusatz[ $name ] = $schema['default'];
				}
				continue;
			}
			if ( ! is_string( $rohwert ) || strlen( $rohwert ) > 2000 ) {
				return new WP_Error( 'eingabe_ungueltig', 'Die Zusatzfelder sind ungültig oder zu gross.' );
			}
			if ( 'boolean' === $typ ) {
				if ( ! in_array( $rohwert, array( '0', '1' ), true ) ) {
					return new WP_Error( 'eingabe_ungueltig', 'Ein Zusatzfeld ist ungültig.' );
				}
				$wert = '1' === $rohwert;
			} elseif ( 'integer' === $typ ) {
				$wert = filter_var( $rohwert, FILTER_VALIDATE_INT );
				if ( false === $wert ) {
					return new WP_Error( 'eingabe_ungueltig', 'Ein Zusatzfeld ist ungültig.' );
				}
			} elseif ( 'number' === $typ ) {
				if ( ! is_numeric( $rohwert ) ) {
					return new WP_Error( 'eingabe_ungueltig', 'Ein Zusatzfeld ist ungültig.' );
				}
				$wert = (float) $rohwert;
			} elseif ( in_array( $typ, array( 'array', 'object' ), true ) && ! ( 'array' === $typ && 'string' === ( $schema['items']['type'] ?? '' ) ) ) {
				$json_wert = json_decode( $rohwert );
				$wert      = json_decode( $rohwert, true );
				if ( json_last_error() || ( 'object' === $typ && ! ( $json_wert instanceof stdClass ) ) || ( 'array' === $typ && ! is_array( $wert ) ) ) {
					return new WP_Error( 'eingabe_ungueltig', 'Ein Zusatzfeld ist kein gültiges JSON.' );
				}
			} elseif ( 'array' === $typ ) {
				$wert = array_values( array_filter( array_map( 'sanitize_text_field', array_map( 'trim', explode( ',', $rohwert ) ) ) ) );
			} else {
				$wert = sanitize_text_field( $rohwert );
			}
			if ( ! self::wert_begrenzt( $wert, 0 ) || ( isset( $schema['enum'] ) && ! in_array( $wert, $schema['enum'], true ) ) ) {
				return new WP_Error( 'eingabe_ungueltig', 'Ein Zusatzfeld ist ungültig oder zu gross.' );
			}
			$zusatz[ $name ] = $wert;
		}
		if ( count( $zusatz ) > 100 ) {
			return new WP_Error( 'eingabe_ungueltig', 'Es sind höchstens 100 Zusatzfelder zulässig.' );
		}
		// phpcs:ignore WordPress.Security.NonceVerification.Missing -- Nonce wird vor dem Aufruf in verarbeiten() geprüft.
		$zugriff = isset( $_POST['urinet_zugriff'] ) && is_string( $_POST['urinet_zugriff'] ) ? sanitize_key( wp_unslash( $_POST['urinet_zugriff'] ) ) : '';
		if ( ! in_array( $zugriff, array( 'mitglieder', 'vorstand' ), true ) ) {
			return new WP_Error( 'eingabe_ungueltig', 'Die Zugriffsstufe ist ungültig.' );
		}
		$zusatz['zugriff'] = $zugriff;
		return $zusatz;
	}

	private static function wert_begrenzt( $wert, int $tiefe ): bool {
		if ( $tiefe > 4 ) {
			return false;
		}
		if ( is_string( $wert ) ) {
			return strlen( $wert ) <= 2000;
		}
		if ( is_int( $wert ) || is_float( $wert ) || is_bool( $wert ) || null === $wert ) {
			return true;
		}
		if ( ! is_array( $wert ) || count( $wert ) > 100 ) {
			return false;
		}
		foreach ( $wert as $kind ) {
			if ( ! self::wert_begrenzt( $kind, $tiefe + 1 ) ) {
				return false;
			}
		}
		return true;
	}

	private static function form_string( string $feld, int $max_laenge ) {
		// phpcs:ignore WordPress.Security.NonceVerification.Missing,WordPress.Security.ValidatedSanitizedInput.InputNotSanitized -- Nonce wird vor dem Aufruf geprüft; Wert wird direkt ent-slash't, begrenzt und bereinigt.
		$wert = wp_unslash( $_POST[ $feld ] ?? '' );
		if ( ! is_string( $wert ) ) {
			return new WP_Error( 'eingabe_ungueltig', 'Die Anfrage ist ungültig.' );
		}
		if ( strlen( $wert ) > $max_laenge || preg_match( '/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', $wert ) ) {
			return new WP_Error( 'eingabe_ungueltig', 'Die Anfrage ist ungültig oder zu lang.' );
		}
		return sanitize_text_field( $wert );
	}

	private static function bearbeiter(): string {
		$benutzer = wp_get_current_user();
		return 'wp:' . absint( $benutzer->ID );
	}

	private static function bearbeiter_melden( string $kennung ) {
		$benutzer = wp_get_current_user();
		return Urinet_Archiv_Client::erfassung( 'PUT', 'bearbeiter/' . $kennung, array(), array( 'name' => wp_html_excerpt( sanitize_text_field( $benutzer->display_name ), 200, '' ) ) );
	}

	private static function beitrag_fuer_id( string $id ): int {
		$beitraege = get_posts(
			array(
				'post_type'      => 'urinet_archiv_doc',
				'post_status'    => 'private',
				'posts_per_page' => 1,
				'fields'         => 'ids',
				// phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_key -- Höchstens ein eigener Entwurfsbeitrag je Dokument-ID.
				'meta_key'       => self::META_DOKUMENT_ID,
				// phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_value -- Höchstens ein eigener Entwurfsbeitrag je Dokument-ID.
				'meta_value'     => $id,
				'no_found_rows'  => true,
			)
		);
		return $beitraege ? (int) $beitraege[0] : 0;
	}

	private static function beitrag_fuer_referenz( string $referenz ): int {
		if ( '' === $referenz ) {
			return 0;
		}
		$beitraege = get_posts(
			array(
				'post_type'      => 'urinet_archiv_doc',
				'post_status'    => 'private',
				'posts_per_page' => 1,
				'fields'         => 'ids',
				// phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_key -- Höchstens ein eigener Entwurfsbeitrag je Referenz.
				'meta_key'       => self::META_REFERENZ,
				// phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_value -- Höchstens ein eigener Entwurfsbeitrag je Referenz.
				'meta_value'     => $referenz,
				'no_found_rows'  => true,
			)
		);
		return $beitraege ? (int) $beitraege[0] : 0;
	}

	private static function referenz_fuer_id( string $id ): string {
		$beitrag = self::beitrag_fuer_id( $id );
		return $beitrag ? (string) get_post_meta( $beitrag, self::META_REFERENZ, true ) : '';
	}

	private static function uuid( string $id ): bool {
		return (bool) preg_match( '/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i', $id );
	}

	private static function fehler( WP_Error $fehler ): void {
		self::hinweis( $fehler->get_error_message() );
	}

	private static function hinweis( string $text ): void {
		echo '<div class="notice notice-info"><p>' . esc_html( $text ) . '</p></div>';
	}

	private static function meldung(): void {
		// phpcs:ignore WordPress.Security.NonceVerification.Recommended -- Nur feste Meldungscodes, kein Nutzereingabewert.
		$code  = isset( $_GET['meldung'] ) && is_string( $_GET['meldung'] ) ? sanitize_key( wp_unslash( $_GET['meldung'] ) ) : '';
		$texte = array(
			'hochgeladen'    => 'Die Datei wurde an das Archiv übergeben.',
			'dublette'       => 'Das Archiv hat die Datei als bereits vorhanden erkannt. Prüfen Sie den Dokumentstatus in der Liste.',
			'konflikt'       => 'Die Dokumentdaten haben sich geändert. Die aktuellen Archivdaten wurden geladen; bitte prüfen Sie sie vor einem erneuten Speichern.',
			'gespeichert'    => 'Die Aktion wurde an das Archiv übergeben.',
			'fehlgeschlagen' => 'Die Aktion konnte nicht abgeschlossen werden. Bitte prüfen Sie den Status im Archiv.',
			'eingabe'        => 'Die Eingaben sind ungültig oder zu gross. Bitte prüfen Sie sie und versuchen Sie es erneut.',
			'sitzung'        => 'Die Sitzung ist abgelaufen. Bitte laden Sie die Seite neu.',
		);
		if ( isset( $texte[ $code ] ) ) {
			self::hinweis( $texte[ $code ] );
		}
	}

	private static function zurueck( string $meldung, string $id = '' ): void {
		$argumente = array(
			'page'    => self::SEITE,
			'meldung' => $meldung,
		);
		if ( self::uuid( $id ) ) {
			$argumente['dokument'] = $id;
		}
		wp_safe_redirect( add_query_arg( $argumente, admin_url( 'admin.php' ) ) );
		exit;
	}
}
