# Implementation Plan — 01 Archiv-Kern

Aufgaben sind in **Wellen** gruppiert. Innerhalb einer Welle laufen Aufgaben mit verschiedener
Rolle parallel (Crew), eine Welle beginnt erst, wenn die vorherige abgeschlossen und das Gate
erfüllt ist. Rollen: **[Backend]**, **[Frontend]**, **[Infra]**, **[QA]**, **[Fabian]** (manuell, nicht vom Agenten).
Jede Aufgabe: Tests zuerst bzw. mitliefern, `mvn verify` / `ng test` grün, Akzeptanzkriterien im PR nennen.

## Welle 0 — Phase 0 Klären (Gate: ≥ 45/50, Spikes dokumentiert)

- [ ] 0.1 Golden-Datensatz zusammenstellen [Fabian]
  - 50 Belege gemäss Requirement 0.1 auswählen, Personendaten schwärzen (AHV-Nr., IBAN Dritter)
  - `erwartet.json` mit Art, Datum, Partner, Betrag, Belegnummer je Datei erfassen
  - Ablage ausserhalb des Repos (`~/archiv-golden/`), Pfad per Umgebungsvariable `ARCHIV_GOLDEN_DIR`
  - _Anforderungen: 0.1_

- [ ] 0.2 Spike Infomaniak Object Storage [Infra]
  - Bucket mit Object Lock + Versionierung anlegen, `PutObjectRetention` GOVERNANCE pro Objekt testen, Löschen mit/ohne `x-amz-bypass-governance-retention` prüfen
  - prüfen, ob Objekte ohne Retention im selben Bucket löschbar bleiben (Präfix `eingang/`)
  - Ergebnis in `docs/spikes/s3-object-lock.md`; bei negativem Befund Rückfall aus design.md wählen
  - _Anforderungen: 0.4, 6.2, 6.3_

- [ ] 0.3 Spike Infomaniak AI Services [Backend]
  - Das Konto und die Modelle bestehen bereits (Modellliste geprüft 07.11.2026); kein Konto anlegen, keine anderen Anbieter
  - [Fabian] Produkt-ID, API-Token und Basis-URL aus dem Infomaniak-Konto als Secrets bereitstellen (`INFOMANIAK_AI_PRODUCT_ID`, `INFOMANIAK_AI_TOKEN`)
  - Quarkus-Mini-App mit `quarkus-langchain4j-openai`, Base-URL und Produkt-ID aus Konfiguration
  - testen: JSON-Schema-Antwortformat, JSON-Modus, Tool Calling mit Mistral-Small-4, Embeddings-Endpunkt (Dimension notieren), Bild-Eingabe (K1), Reranker-Endpunkt (Form der API notieren)
  - Ergebnis in `docs/spikes/infomaniak-ai.md`
  - _Anforderungen: 0.5_

- [ ] 0.4 Test-Export 21.CRM [Fabian]
  - Kontakte und Verkaufschancen als CSV exportieren, 5 Beispielzeilen anonymisieren
  - als `backend/src/test/resources/csv/21crm-kontakte-beispiel.csv` und `…-verkaufschancen-beispiel.csv` ablegen; Spalten, Trennzeichen, Zeichensatz in `docs/spikes/21crm-export.md`
  - _Anforderungen: 0.6, 13.1_

- [ ] 0.5 Prototyp-Pipeline auf dem Golden-Datensatz [Backend]
  - Kommandozeilen-Klasse `GoldenLauf`: Datei → OCRmyPDF (lokales Docker-Image) → Text → KI-Vorschlag (Prompt gemäss design.md) → Vergleich mit `erwartet.json`
  - Bericht `golden-bericht.md` mit Trefferquote je Feld und Liste der Fehlschläge
  - Prompt iterieren, bis Gate erreicht oder Grenze klar
  - _Anforderungen: 0.2, 0.3_
  - _Abhängig von: 0.1, 0.3_

- [ ] 0.6 Gate-Review und ADRs [Fabian]
  - Spike-Berichte und Golden-Bericht prüfen; Entscheide E2 (Eigenbau), E3 (PWA) bestätigen
  - ADR-001 bis ADR-006 in `docs/adr/` freigeben
  - _Anforderungen: 0.3_

## Welle 1 — Fundament

- [ ] 1.1 Repository-Gerüst und CI [Infra]
  - Struktur gemäss `structure.md`; Quarkus-Projekt `backend` (Java 21, Extensions: rest-jackson, hibernate-orm-panache, jdbc-postgresql, flyway, oidc, smallrye-openapi, smallrye-health, micrometer, scheduler, amazon-s3, langchain4j-openai)
  - Angular-Projekt `frontend` (Standalone, Material, PWA)
  - GitHub Actions: `mvn verify`, `ng test`, `ng build`, Docker-Images bauen
  - _Anforderungen: 16.1_

- [ ] 1.2 Entwicklungsumgebung mit Docker Compose [Infra]
  - `deploy/docker-compose.dev.yml`: PostgreSQL 17 + pgvector, Keycloak, MinIO (Object Lock), Mailpit
  - Keycloak-Realm-Export `urinet-archiv` mit Rollen, Claim-Mapper `mandant`, TOTP-Pflicht, Testbenutzer je Rolle
  - _Anforderungen: 2.1, 2.2, 16.1_

- [ ] 1.3 Mandantenregister und Provisionierung [Backend]
  - Flyway `db/public`: `mandant`, `auftrag`, `api_client`
  - `MandantProvisionierung` (Schema, Flyway `db/mandant`, Bucket mit Object Lock + Versionierung, Seed Dokumentarten); Admin-Kommando `archiv mandant anlegen <code>`
  - Test: zwei Mandanten anlegen, Schemas und Buckets getrennt
  - _Anforderungen: 1.1, 1.5_

- [ ] 1.4 Mandanten-Kontext und Rollen [Backend]
  - `MandantTenantResolver`, `MandantKontext` für Worker, `@RolesAllowed` je Endpunkt
  - Tests: ohne Claim → 403; inaktiver Mandant → 403; fremde Dokument-ID → 404; `treuhaender` schreibt → 403
  - _Anforderungen: 1.2, 1.3, 1.4, 1.6, 2.2, 2.4_

- [ ] 1.5 API-Tokens für Generatoren [Backend]
  - `ApiTokenAuthMechanism`, Argon2id, Kommando `archiv token erstellen <mandant> <name> generator` (gibt Geheimnis einmal aus), Widerruf
  - _Anforderungen: 2.5_

- [ ] 1.6 Querschnitt: Problem Details, Protokoll-Grundlage, ArchUnit [Backend]
  - `ExceptionMapper` → RFC 9457 mit `fehlercode`; Tabelle `zugriff_protokoll` mit Sperr-Trigger; `@Protokolliert`-Interceptor
  - ArchUnit-Regeln aus design.md
  - _Anforderungen: 10.2, 10.3_

## Welle 2 — Dokumente, Ablage, Verarbeitung

- [ ] 2.1 Dokumentarten und Aufbewahrung [Backend]
  - Entität `Dokumentart`, REST `/dokumentarten` (CRUD, Löschen nur ohne Dokumente → sonst inaktiv), JSON-Schema-Validierung für `zusatz`
  - Seed `urinet` mit 11 Arten; `AufbewahrungRechner` (Geschäftsjahr / gültig bis / unbefristet) mit Unit-Tests (Beleg 2026 → 31.12.2036)
  - _Anforderungen: 9.1–9.5_

- [ ] 2.2 Datenmodell Dokument [Backend]
  - Flyway `db/mandant`: `dokument`, `dokument_finanz`, `dokument_vertrag`, `dokument_version`, generierte `volltext_tsv` + Indizes
  - Panache-Entitäten, `VertraulichkeitFilter`
  - Tests: Vertraulichkeit `personal` für `mitarbeiter` unsichtbar, für Inhaber sichtbar
  - _Anforderungen: 2.3, 10.1_

- [ ] 2.3 Ablage-Service S3 [Backend]
  - `AblageService` mit `ablegenEingang`, `archivieren` (Kopie, Prüfsummenvergleich, Retention, Löschen in `eingang/`), `loeschenEingang`, `vorsignierteUrl`
  - Testcontainers MinIO mit Object Lock: archivierte Datei nicht löschbar, Prüfsummen-Abweichung bricht ab
  - _Anforderungen: 6.2, 6.3, 6.4, 6.5_

- [ ] 2.4 Upload-Endpunkt [Backend]
  - `POST /dokumente` multipart, Typprüfung per Tika-Detektor, 50 MB, SHA-256 vor allem anderen, Dublettenprüfung, `zusammenfassen`, Antwort 202 ≤ 2 s
  - Auftrag `VERARBEITEN` einreihen
  - _Anforderungen: 3.1–3.5, 6.1_

- [ ] 2.5 Auftragsschlange [Backend]
  - `AuftragService.einreihen(typ, nutzlast)`, `AuftragPoller` mit `SKIP LOCKED`, Wiederholung mit Backoff, Mandantenkontext pro Auftrag
  - Test: zwei Worker holen denselben Auftrag nie doppelt
  - _Anforderungen: 1.6, 5.4_

- [ ] 2.6 Worker-Image und Verarbeitung [Backend] [Infra]
  - Dockerfile Worker (JVM + ocrmypdf, tesseract-ocr-deu/eng, img2pdf, libheif-examples, poppler-utils, ghostscript)
  - `VerarbeitenHandler`: HEIC→JPEG, Bilder→PDF, OCRmyPDF (`--skip-text` bei Textebene), `pdftotext`, Vorschaubilder; Folgeauftrag `KI_VORSCHLAG`
  - veraPDF-Prüfung im Test; Laufzeitmessung einseitiges Foto ≤ 30 s
  - _Anforderungen: 5.1–5.5_

- [ ] 2.7 Frontend-Grundgerüst und Upload [Frontend]
  - Layout mobil/Desktop im UriNet-Branding, Login per OIDC (PKCE), Navigation mit Eingangskorb-Zähler
  - Seite Upload mit Drag & Drop, Mehrfachauswahl, «als ein Dokument», Fortschritt, Fehlermeldungen
  - API-Client aus OpenAPI generieren
  - _Anforderungen: 3.1, 3.3, 3.5, 8.6_

## Welle 3 — KI-Vorschläge, Eingangskorb, Handy-Erfassung

- [ ] 3.1 Kontakte und Partner-Abgleich [Backend]
  - Flyway `kontakt`, `verkaufschance` mit `pg_trgm`; `Normalisierer`; REST `/kontakte`
  - `PartnerAbgleich` (E-Mail, UID, IBAN, Ähnlichkeit ≥ 0.6, «unsicher» bei Abstand < 0.1) mit Tests
  - _Anforderungen: 7.4_

- [ ] 3.2 Metadaten-Extraktor [Backend]
  - `MetadatenExtraktor` (LangChain4j), Prompt aus Welle 0 übernehmen, Schema-Validierung + eine Nachfrage, Belegnummer-Muster, Sicherheiten, `ki_verbrauch`
  - `KiVorschlagHandler`: Vorschlag speichern, Partner verknüpfen, Status `EINGANGSKORB`; bei KI-Ausfall ohne Vorschlag weiter
  - WireMock-Tests für gültige, ungültige und fehlende Antworten
  - _Anforderungen: 7.1, 7.2, 7.3, 7.5, 7.6, 7.8_

- [ ] 3.3 Lernen aus Korrekturen [Backend]
  - Beim Bestätigen Differenz Vorschlag ↔ Ergebnis als `ki_beispiel` speichern; Auswahl der 5 Beispiele (gleicher Partner, sonst gleiche Art, neueste zuerst)
  - _Anforderungen: 7.7_

- [ ] 3.4 Bestätigen, Verwerfen, Archivieren [Backend]
  - `POST /dokumente/{id}/bestaetigen`: Pflichtfelder, `aufbewahren_bis`, Auftrag `ARCHIVIEREN` (AblageService.archivieren), Status `ARCHIVIERT`
  - `POST /dokumente/{id}/verwerfen`: Dateien löschen, Protokoll, Status `VERWORFEN`
  - _Anforderungen: 6.3, 6.6, 8.2, 8.3, 8.5_

- [ ] 3.5 Metadaten ändern und Versionen [Backend]
  - `PATCH /dokumente/{id}` mit `If-Match`, Grund Pflicht bei `ARCHIVIERT`, Eintrag in `dokument_version`; 412 bei Konflikt
  - _Anforderungen: 10.1, 10.4_

- [ ] 3.6 Eingangskorb-Oberfläche [Frontend]
  - Liste mit Vorschaubild, Vorschlägen, «bitte prüfen»-Markierung, Partnerauswahl mit Suche, Bestätigen in ≤ 2 Tipps, Verwerfen mit Rückfrage
  - Playwright im 375-px-Viewport
  - _Anforderungen: 8.1–8.6_

- [ ] 3.7 Handy-Erfassung (PWA) [Frontend]
  - Kamera mehrseitig, jscanify im Web Worker (Kanten, Zuschnitt, Entzerrung), Vorschau anpassen/verwerfen, JPEG 2'480 px
  - Offline-Warteschlange (IndexedDB), automatischer Upload bei `online`, 3 Fehlversuche → manuell wiederholen
  - Test auf echtem iPhone (Safari) und Android (Chrome) [Fabian]
  - _Anforderungen: 4.1–4.6_

## Welle 4 — CSV-Import, Suche, Generatoren, Altbestand

- [ ] 4.1 CSV-Erkennung und Spaltenzuordnung [Backend]
  - Upload `/importe/crm`, SHA-256, ICU4J-Zeichensatz, Trennzeichen aus Kopfzeile, Grenzen (Name Pflicht, ≤ 10'000 Zeilen)
  - Synonymliste aus `docs/spikes/21crm-export.md`; `import_zuordnung` lesen/speichern
  - Tests mit den Beispieldateien aus 0.4 (UTF-8 mit BOM, Windows-1252, Komma/Semikolon)
  - _Anforderungen: 13.1, 13.2, 13.10_
  - _Abhängig von: 0.4_

- [ ] 4.2 Vorschau und Abgleich [Backend]
  - Auftrag `CSV_VORSCHAU`: Abgleich Kontakt-ID → E-Mail → Name+PLZ, Ergebnis je Zeile in `import_zeile`, Zähler im Lauf
  - Dublettenregel (mehrere Kandidaten oder nur Ähnlichkeit)
  - _Anforderungen: 13.3, 13.4, 13.5_

- [ ] 4.3 Übernahme und Protokoll [Backend]
  - Auftrag `CSV_UEBERNEHMEN` in einer Transaktion; Dubletten-Entscheide; fehlende Kontakte nur `nicht_im_export=true`; Originaldatei nach `importe/`
  - Verkaufschancen-Import (Art `verkaufschance`) mit Kontakt-Zuordnung
  - _Anforderungen: 13.6, 13.7, 13.8, 13.9_

- [ ] 4.4 Import-Oberfläche [Frontend]
  - Upload, Zuordnungsdialog (Spalte → Feld mit Vorschlag), Vorschau mit Zählern und Filter je Ergebnis, Dubletten entscheiden, Übernehmen, Liste der Läufe
  - _Anforderungen: 13.2, 13.3, 13.5, 13.6_

- [ ] 4.5 Volltextsuche [Backend]
  - `GET /dokumente` mit `websearch_to_tsquery`, Filtern, Sortierung, `ts_headline`; Vertraulichkeit und Mandant
  - Lasttest mit 50'000 synthetischen Dokumenten: p95 ≤ 1 s
  - `GET /dokumente/{id}/datei` mit vorsignierter URL (5 Min.) und Protokoll
  - _Anforderungen: 14.1–14.6_

- [ ] 4.6 Suche und Dokumentansicht [Frontend]
  - Suchfeld, Filterleiste (Art, Partner, Zeitraum, Betrag, Tags, Geschäftsjahr), Trefferliste mit Auszug
  - Dokumentansicht: PDF-Vorschau, Metadaten bearbeiten (mit Grund), Versionen, Download
  - _Anforderungen: 14.1, 14.2, 14.4, 10.1_

- [ ] 4.7 Generator-Endpunkt und Python-Client [Backend]
  - `POST /dokumente/generator`: Validierung, direkt archivieren, 409 bei vergebener Belegnummer
  - `tools/archiv_client.py` (requests, Token aus Umgebung `ARCHIV_TOKEN`, Ordner `archiv-ausstehend/` bei Ausfall); Einbau-Beispiel für `rechnung_gen.py` und `urinet_pdf.py` in `tools/README.md`
  - _Anforderungen: 11.1–11.5_

- [ ] 4.8 Drive-Import-Werkzeug [Backend]
  - `tools/drive_import` (Java, Picocli): rekursiv, Trockenlauf, Ordnerpfad als `hinweis`, Überspringen gleicher Prüfsummen, Liste `.gdoc/.gsheet`
  - Pfad-Muster → Hinweise an die KI (Kunde, Art)
  - _Anforderungen: 12.1–12.5_

## Welle 5 — GeBüV, Betrieb, Abnahme Phase 1

- [ ] 5.1 Zugriffsprotokoll vollständig [Backend] [Frontend]
  - `@Protokolliert` an allen Lese-/Schreib-/Download-Endpunkten; REST `/protokoll` (nur Inhaber) mit Filtern; Seite `/verwaltung/protokoll`
  - Test: UPDATE/DELETE auf `zugriff_protokoll` scheitert
  - _Anforderungen: 10.2, 10.3, 10.5_

- [ ] 5.2 Tages-Manifest [Backend]
  - Auftrag `MANIFEST` täglich 02:00 Europe/Zurich, Hash-Kette, Ed25519-Signatur, optional RFC 3161, Ablage mit Object Lock, Tabelle `manifest`
  - Prüfwerkzeug `archiv manifest pruefen <datum>` (Signatur + Kette)
  - _Anforderungen: 15.1–15.4_

- [ ] 5.3 Monatlicher Prüflauf [Backend]
  - Auftrag `PRUEFLAUF`: alle Dateien stromweise hashen, mit DB und Manifest vergleichen, Bericht + E-Mail bei Abweichung
  - _Anforderungen: 15.5_

- [ ] 5.4 Produktivbetrieb aufsetzen [Infra]
  - `deploy/docker-compose.yml` (Caddy, API, Worker, PostgreSQL, Keycloak), Domains `archiv.urinet.ch`/`auth.urinet.ch`, Secrets als Dateien
  - Infomaniak-Server einrichten (Grösse gemäss K5), Bucket `urinet-archiv-urinet` produktiv
  - _Anforderungen: 16.1, 16.7_

- [ ] 5.5 Sicherung und Wiederherstellung [Infra]
  - `pg_dump` + restic nach Swiss Backup (verschlüsselt, Aufbewahrung 30 T / 12 M / 10 J), Systemd-Timer oder Compose-Cron
  - `docs/betrieb/wiederherstellung.md` und erfolgreiche Restore-Probe in Testumgebung
  - _Anforderungen: 16.2, 16.3_

- [ ] 5.6 Überwachung und Benachrichtigung [Backend] [Infra]
  - Health, Micrometer-Kennzahlen (offene Aufträge, Fehler, Eingangskorb), E-Mail an Inhaber bei Fehleraufträgen > 1 h und fehlgeschlagener Sicherung
  - _Anforderungen: 16.4, 16.5_

- [ ] 5.7 Verfahrensbeschreibung «Erfassen und Archivieren» [Fabian]
  - `docs/verfahrensbeschreibung.md` (≤ 2 Seiten): Eingänge, Scan-Regeln (ganze Seite, lesbar, ≥ 300 dpi), Prüfung im Eingangskorb, Sperre, Manifest, Zugriffe, Umgang mit Papier (E5); vom Treuhänder gegenlesen lassen (K3)
  - _Anforderungen: 16.6_

- [ ] 5.8 Übernahme Belege 2026 und Abnahme [Fabian] [QA]
  - CRM-Kontakte per CSV importieren, Drive-Altbestand importieren, Papierbelege 2026 fotografieren, Generatoren umstellen
  - Abnahmetest: Stichprobe 20 Dokumente auffindbar, Prüflauf ohne Abweichung, Mandantentrennung mit Testmandant `test` geprüft
  - **Gate Phase 1:** alle Belege 2026 im Archiv, Prüflauf grün
  - _Anforderungen: 1.4, 13.6, 15.5_
