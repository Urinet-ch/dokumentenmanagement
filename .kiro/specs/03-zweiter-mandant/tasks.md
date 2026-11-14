# Implementation Plan — 03 Zweiter Mandant VHF/GSK

Voraussetzungen: Gate Phase 2, Zuschlag VHF/GSK, Konzept-Entscheid E4, Spec 03 auf `main` gemergt. Ausnahme: Die
mandantenneutralen Vorstufen 11.6 (Vertrag und Versionierung `/public/v1`) und 11.7 (Webseiten-Schlüssel) werden vor
Gate Phase 2 und vor dem Zuschlag entwickelt (entschieden von Fabian am 14.11.2026, «Neutrale Vorstufen jetzt»), ohne
VHF-spezifisches: keine Domain VHF/GSK, kein Seed `vhf-gsk`, keine Dateiauslieferung (11.8), kein Feed (11.9), keine
Erfassung (11.10). Alle übrigen Aufgaben (Welle 10, 11.1–11.5, 11.8–11.13, Welle 12) bleiben an Gate Phase 2,
Zuschlag und E4 gebunden.
Wellen, Rollen und Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach `CLAUDE.md`.
Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` (ausser bei Nachweis-Vermerk)
und erfüllt die Definition of Done aus `tech.md`.

## Welle 10 — Mandant, Branding, Erfassung

- [ ] 10.1 Auftragsbearbeitungsvertrag und Domain [Fabian]
  - Vorlage `docs/vertrag/avv-vorlage.md` erstellen (Unterauftragnehmer nach Requirement 8.4), mit VHF/GSK abstimmen und unterzeichnen
  - Entscheide vor Welle 10 einholen: Domain VHF/GSK, `gesetzliche_pflicht` und Standard-Vertraulichkeit je Art, Rechte A11 bis A14 der Geschäftsstelle
  - Domain für das Archiv festlegen ([offen: Domain VHF/GSK – Entscheid vor Welle 10]), DNS einrichten
  - _Anforderungen: 8.4, 2.2, 1.2, 3.5_

- [ ] 10.2 Seed und Mandant anlegen [Backend]
  - `backend/src/main/resources/seed/vhf-gsk/dokumentarten.json` mit Zusatz-Schema, Standard-Vertraulichkeit und `gesetzliche_pflicht`; keine Finanzarten; Provisionierung nach Spec 01 (Liquibase `db/mandant`, Bucket mit Object Lock, Versionierung und Bucket Policy mit Deny auf `s3:BypassGovernanceRetention`, Zugriff mit dem App-Schlüssel `s3_app_zugang`, nie mit Bypass-Header nach Spec 01, 6.5); Keycloak-Client `archiv-vhf-gsk`
  - Test-Seed `backend/src/test/resources/seed/vhf-gsk` (Dokumentart `PROBE_VHF_BULLETIN` aus Spec 01, Aufgabe 1.4) entfernen, sobald der echte Seed besteht; die Szenarien von 1.4 auf eine echte Art umstellen
  - Szenario: Finanzwerkzeuge für `vhf-gsk` nicht verfügbar
  - Spec 05 ergänzt: Seed `backend/src/main/resources/seed/vhf-gsk/konfiguration.json` statt `dokumentarten.json`, mit Klassifikation `fachgebiet`, Reihen als Dokumentarten, Regeln `EINDEUTIG` und `FOLGE` für Ausgabe-Nr. und Jahr; `KonfigurationSeedTest` prüft den Seed (Spec 05, Req 1.6, 6.7; Aufgabe 17.7)
  - Ergänzt 13.11.2026: Klassifikation `verband` (`vhf`, `gsk`, mehrfach, öffentlich filterbar, ohne KI-Vorschlag) im Seed; `zugriff` und `veroeffentlicht` nach design.md «Reservierte Felder» (drei Stufen)
  - _Anforderungen: 1.1, 1.2, 1.3, 1.5, 1.6, 3.7_

- [ ] 10.3 Branding pro Mandant [Frontend] [Infra]
  - `GET /api/v1/branding?domain=`, Web-App lädt Name/Logo/Farbe; Keycloak-Theme `vhf-gsk` auf `archiv-auth.apps.urinet.ch`; Traefik-Router (Labels in `deploy/infomaniak/compose.yml`) für die Verbands-Domain, ausgerollt nur über den Deploy-Workflow
  - _Anforderungen: 2.1–2.3_

- [ ] 10.4 Erfassung für die Geschäftsstelle [Backend] [Frontend]
  - Rolle `geschaeftsstelle` nach Rechtematrix (Spec 01, 2.6: A1 bis A10 erlaubt, A11 bis A14 bis zum Entscheid 403), veröffentlichen/zurückziehen, KI-Prompt pro Mandant (Reihe, Ausgabe, Jahr, Fachgebiet), Zugriffsstufe nur manuell (Standard `mitglieder`)
  - Eingangskorb zeigt Felder je Dokumentart
  - Spec 05 ergänzt: KI-Prompt und Felder je Art aus der Konfiguration von `vhf-gsk`; die Felder und Klassifikationen je Art im Eingangskorb und in der Detailansicht liefert Spec 05, Aufgabe 18.4 (Spec 05, Req 7.5, 8.1)
  - Ergänzt 13.11.2026 (E-C): Auswahl der Zugriffsstufe mit drei Werten (`oeffentlich`, `mitglieder`, `vorstand`), Standard `mitglieder`
  - _Anforderungen: 3.1–3.5_

- [ ] 10.5 Altbestand übernehmen [Backend] [Fabian]
  - Drive-Import mit API-Token vom Scope `import` des Mandanten `vhf-gsk` und Pfad-Mustern (Reihe/Jahr), Bericht mit Lücken in der Nummerierung
  - Tabelle `alte_url` und CSV-Import der alten Links
  - _Anforderungen: 4.1–4.3_

- [ ] 10.6 Sicherung für Mandant 2 [Infra]
  - Schema `m_vhf_gsk` und Bucket `urinet-archiv-vhf-gsk` in Sicherung, Zweitkopie und `backup-gate.sh` aufnehmen; Restore-Probe stellt beide mit wieder her
  - _Anforderungen: 1.5_

## Welle 11 — Öffentliche Schnittstelle und Plugin (parallel)

- [ ] 11.1 Webseiten-Schlüssel und Zugriffsstufen [Backend]
  - Geändert 13.11.2026 (E-B, E-C; bisher «Site-Key und Zugriffsstufen» mit `SiteKeyAuthMechanism`): Anmeldung der Lese-Schlüssel über `ApiTokenAuthMechanism` (Scopes aus Aufgabe 11.7), `@WebseitenZugang`, Header `X-Archiv-Zugriff` (drei Stufen, Kappung durch den Schlüssel, 403/400 nach 5.9) und `X-Archiv-Client`, Filter auf `veroeffentlicht`, `zugriff = ANY(:stufen)` und Vertraulichkeit `NORMAL`, 404 bei zu hoher Stufe, kein CORS; Domain-Typ `Zugriffsstufe`
  - _Anforderungen: 3.6, 3.7, 5.2, 5.3, 5.5, 5.6, 5.9_
  - _Abhängig von: 11.7_

- [ ] 11.2 Endpunkte `/public/v1` [Backend]
  - Liste, Suche (Hybrid), Details, Datei-Link (5 Min., Protokoll mit gekürzter IP)
  - Spec 01 ergänzt: Suche ohne Dateinamen (`mitDateiname=false`, Spec 01, Req 3.13) braucht einen Index oder Vorfilter: `ts_filter(volltext_tsv, '{a,b,c}')` nutzt den GIN-Index nicht und durchsucht sonst alle Dokumente des Mandanten (Spec 01, design.md, «Umsetzung 4.10»; Review #104); Index oder Vorfilter anlegen, falls Spec 02 (Aufgabe 7.1) ihn noch nicht angelegt hat
  - Spec 05 ergänzt: Liste filtert zusätzlich nach den Klassifikationen mit `oeffentlich_filter` (z. B. `?fachgebiet=bad`), gibt nur diese aus; fester Sicherheitsfilter unverändert davor; reservierte Parameter in Spec 05 design.md (Spec 05, Req 8.6)
  - Ergänzt 13.11.2026: Endpunkte `/public/v1/filter` (nur Reihen und Werte bis zur Stufe) und `/public/v1/umleitung` (alte Links, bis zur Stufe); Antwortfelder nach design.md «Antwortfelder» (nur `dokumentId`, keine Seiten-URLs); Datei-Link nur für Stufe `oeffentlich` (Stream in Aufgabe 11.8); feste Parameter um `seit`, `max`, `referenz`, `groesse`, `status`, `url` ergänzt
  - _Anforderungen: 5.1, 5.4, 5.8, 5.10, 5.11_
  - _Abhängig von: 11.1, 11.6_

- [ ] 11.3 Öffentlicher Chatbot [Backend]
  - `OeffentlicherChatAgent` mit eingeschränkten Werkzeugen, Quellen mit Reihe/Ausgabe/Link, Hinweis auf Geschäftsstelle, Rate-Limit 20/h, `mandant_budget`, Speicherung 90 Tage ohne Personenbezug
  - Ergänzt 13.11.2026: Werkzeuge mit `:stufen` aus der Anfrage (drei Stufen); Quellen mit `dokumentId`, Titel, Reihe, Ausgabe (Link bildet die Webseite); 429 `BUDGET` und `ZU_VIELE_ANFRAGEN` als Problem Details, beide im selben PR in die Fehlercode-Tabelle von Spec 01 design.md eintragen, `ZU_VIELE_ANFRAGEN` falls noch nicht eingetragen (Spec 03 design.md, Error Handling)
  - _Anforderungen: 6.1–6.5_
  - _Abhängig von: 11.1_

- [ ] 11.4 WordPress-Plugin Grundgerüst [Frontend]
  - Einstellungsseite (Lese- und Erfassungs-Schlüssel verschlüsselt; geändert 13.11.2026, bisher «Site-Key»), Client mit Fehlerbehandlung (Problem Details) und Transients, Zugriffsstufe aus Login/Rolle (drei Stufen, Rollen für Mitglieder und Vorstand)
  - Client nur nach `docs/schnittstelle/public-v1.yaml`, unbekannte Felder ignorieren; Mock des Archivs aus dieser Datei
  - wp-env für lokale Entwicklung, PHPUnit
  - _Anforderungen: 7.1, 7.3, 7.4, 7.5, 7.7, 11.1, 11.6_
  - _Abhängig von: 11.6_

- [ ] 11.5 Shortcodes, Blöcke, Download, Chatbot [Frontend]
  - `[urinet_archiv_liste]`, `[urinet_archiv_suche]`, `[urinet_chatbot]` + Gutenberg-Blöcke; Download-Route mit 302; WP-REST-Proxy für den Chat (SSE), `assets/chatbot.js`
  - Browser-Szenarien mit Cucumber.js + Playwright gegen wp-env; Barrierefreiheit prüfen (axe), Theme-Übernahme
  - Spec 05 ergänzt: Filter der Liste nach den Klassifikationen mit `oeffentlich_filter`, nur diese werden angezeigt (Spec 05, Req 8.6)
  - Geändert 13.11.2026 (E-D): Download-Route `oeffentlich` → 302, `mitglieder`/`vorstand` → Stream blockweise durchreichen (cURL-Write-Callback, keine temporäre Datei) mit `Cache-Control: private, no-store`, höchstens 2 gleichzeitige Downloads je Person, Meldung bei 429; Filter aus `/public/v1/filter` (Cache je Stufe); Treffer verlinken mit `#page=<n>` auf die Fundstelle
  - Ergänzt 13.11.2026: Dokumentseite `/urinet-archiv/dokument/{id}`, alle Links auf Dokumente bildet das Plugin; Umleitung alter Links mit 301 über `/public/v1/umleitung`
  - _Anforderungen: 7.2, 7.6, 7.8, 5.4_
  - _Abhängig von: 11.2, 11.4, 11.8, 11.13_

- [ ] 11.6 Vertrag und Versionierung der Webseiten-Schnittstelle [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-B, neutrale Server-API); Nummer 11.6 als nächste freie der Welle 11
  - `docs/schnittstelle/public-v1.yaml` (OpenAPI 3.1) für Lesen und Erfassung; Pfade unter `/public/` über `mp.openapi.scan.exclude.packages` nicht in `frontend/openapi/` und nicht im Angular-Client, Vertrag in eigenem Lauf erzeugen (Weg wählen); `PublicVertragTest` (Code = Datei); CI-Schritt im Build-Job mit vollständiger Historie: nur additive Änderungen gegenüber `origin/main` (Werkzeug wählen); Header `Deprecation`, `Sunset`, `Link` über `archiv.webseite.v<n>.sunset`; Problem Details auch unter `/public/` (`ProblemAnmeldung`), `Cache-Control: no-store`
  - Traefik: eigener Router für `/public/` mit eigener Drosselung je IP (Labels in `deploy/infomaniak/compose.yml`, nur über den Deploy-Workflow)
  - Vorstufe (entschieden von Fabian am 14.11.2026): vor Gate Phase 2 und Zuschlag, nur mandantenneutral; getestet mit dem Mandanten `urinet` bzw. Testmandanten, ohne Domain und Seed von VHF/GSK. Der Vertrag enthält, was es bei der Umsetzung gibt (Problem Details, Header, Versionierung); die Endpunkte kommen additiv mit 11.2 und 11.10 dazu
  - _Anforderungen: 11.1–11.6, 15.2–15.4_

- [ ] 11.7 Webseiten-Schlüssel [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-B); Trennung Lese-/Erfassungs-Schlüssel vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Neues Changeset `db/public` (`api_client`: Scopes `webseite-lesen`, `webseite-erfassung`, Spalten `webseite`, `hoechste_stufe`, `gueltig_bis`, `absender_pruefen`); `ApiTokenScope` erweitern; Identität mit `webseite` und `hoechste-stufe`; `ZugangFilter` und `EndpunktWaechterTest` für `@WebseitenZugang`
  - Kommandos `archiv webseite schluessel erstellen|rotieren|widerrufen` (Wert nur auf stdout); `/api/v1/api-tokens` listet und widerruft, legt nicht an; Absender-Einschränkung: `absender_pruefen` je Schlüssel, Netze je Webseite aus einer Datei der Betriebskonfiguration, geprüft nach der Schlüsselprüfung, fehlender Eintrag → ablehnen; Mandant deaktiviert → Schlüssel gesperrt (403 `MANDANT_FEHLT`)
  - Prüf-Cache für Webseiten-Schlüssel (HMAC des Geheimnisses je Schlüssel-ID, `PT5M`, Zeile in `api_client` bei jeder Anfrage gelesen); Szenario: fremde `ua_…`-Anfragen belegen die `HashSperre`, die Webseite wird trotzdem bedient; Widerruf wirkt trotz Cache sofort
  - Leistung messen: Trefferquote des Prüf-Caches und Wartezeiten der `HashSperre` unter Last mit fremden Anfragen (Open Points in design.md)
  - Spec 01 ergänzt: Scopes in Spec 01, 2.5 und design.md «Umsetzung (Aufgabe 1.5)»; `gueltig_bis` nach Rotation und Prüf-Cache in Spec 01 design.md «Sicherheit» (Spec 01, Req 2.5)
  - Vorstufe (entschieden von Fabian am 14.11.2026): vor Gate Phase 2 und Zuschlag, nur mandantenneutral; Schlüssel und Szenarien mit dem Mandanten `urinet` bzw. Testmandanten, ohne Domain und Seed von VHF/GSK. Das Changeset `db/public` nimmt die nächste freie Nummer (009 ist durch Spec 02, Aufgabe 6.3 belegt)
  - _Anforderungen: 12.1–12.8, 15.3_

- [ ] 11.8 Dateiauslieferung gemischt [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-D)
  - `DateiAuslieferung`: `oeffentlich` → vorsignierte URL (5 Min.), sonst Stream in Blöcken von 64 KiB mit Gegendruck; Plätze gesamt und je Schlüssel, 429 `ZU_VIELE_ANFRAGEN` (Fehlercode im selben PR in Spec 01 design.md eintragen, falls noch nicht eingetragen); Leerlauf- und Gesamt-Zeitlimit; Freigabe bei Abbruch; Protokoll `HERUNTERLADEN` in einer Transaktion, die vor dem ersten Byte endet; keine Dateinamen in Headern; `DateiStreamTest`; Messfall «50 MB an einen langsamen Client»
  - _Anforderungen: 13.1–13.8, 5.4_
  - _Abhängig von: 11.1_

- [ ] 11.9 Änderungs-Feed [Backend]
  - Herkunft: Spec-Erweiterung, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Neues Changeset `db/mandant`: `webseite_aenderung` mit Trigger `AFTER INSERT OR UPDATE OR DELETE` auf `dokument`, `webseite_feed_stand`; Cursor über `pg_current_xact_id()` und `pg_snapshot_xmin`; `/public/v1/aenderungen` (`stufe` = tiefere sichtbare Stufe, nie über dem Schlüssel) und `/public/v1/erfassung/aenderungen` (Referenz nur der eigenen Webseite); Auftrag `FEED_BEREINIGEN` (90 Tage), 410 `CURSOR_ABGELAUFEN` (Fehlercode im selben PR in Spec 01 design.md eintragen); `AenderungFeedTest` mit Stufenwechseln und lang offener Transaktion; Warnung bei Stillstand des Horizonts
  - _Anforderungen: 14.1–14.5_
  - _Abhängig von: 11.7_

- [ ] 11.10 Erfassung über eine Webseite [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-A, Erfassung «beides»)
  - Endpunkte unter `/public/v1/erfassung` (Upload multipart ohne URL-Abruf, Status über ID und Referenz, Liste, Datei ansehen als Stream, Eingangskorb, Dokumentarten, Bearbeiter melden, Bestätigen, Ändern, Veröffentlichen/Zurückziehen, Wiederholen; kein Löschen, kein Verwerfen), delegiert an die Services von Spec 01; Rechte nur nach der Positivliste A1 bis A8 und A10 der Rolle `geschaeftsstelle` (`ErfassungRechteTest`); `veroeffentlicht` in `PATCH`/Bestätigen → 400
  - Idempotenz mit `pg_advisory_xact_lock` auf (Webseite, Referenz) vor der Dublettenprüfung; verworfene Referenz frei; Dublette mit ID nur, wenn sichtbar (Spec 01, 3.4 ergänzt)
  - Neue Changesets `db/mandant`: `webseite_referenz`, `zugriff_protokoll.bearbeiter`, `dokument_version.bearbeiter`, Quelle `WEBSEITE`; Header `X-Archiv-Bearbeiter`; Bearbeiter im Benutzerverzeichnis (Spec 01, 10.10 ergänzt); 409 `REFERENZ_VERGEBEN` (Fehlercode im selben PR in Spec 01 design.md eintragen)
  - _Anforderungen: 10.1–10.14, 3.1_
  - _Abhängig von: 11.7, 11.8_

- [ ] 11.11 Grenzen je Schlüssel [Backend]
  - Herkunft: Spec-Erweiterung, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Token-Bucket je Schlüssel (Startwerte in design.md «Grenzen»), Uploads je Stunde, 429 `ZU_VIELE_ANFRAGEN` mit `Retry-After` (Fehlercode im selben PR in Spec 01 design.md eintragen, falls noch nicht eingetragen); Grenze des Chats je Client-Hash bleibt; Startwerte messen
  - _Anforderungen: 15.1, 6.4_
  - _Abhängig von: 11.7_

- [ ] 11.12 Plugin: Änderungs-Feed und Erfassung [Frontend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026 (E-A); Feed vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - `class-feed.php` (WP-Cron, Transients ab `stufe` aufwärts verwerfen, 410 → alles verwerfen; Hintergrund nur Feed und Statusabruf über die Referenz); `class-erfassung.php` (WP-Admin für die Rolle der Geschäftsstelle, Liste, Formular aus `/erfassung/dokumentarten`, Angaben je Referenz als Post-Meta und Vorbelegung beim Bestätigen, Datei ansehen, Datei nicht in der Mediathek, Referenz und Bearbeiter mit Meldung über `PUT /erfassung/bearbeiter/{kennung}`, `If-Match`, 412 → neu laden, Veröffentlichen erst nach `ARCHIVIERT`, Wiederholen, «Entfernen» = Zurückziehen)
  - Browser-Szenarien mit Cucumber.js + Playwright gegen wp-env
  - _Anforderungen: 7.9, 7.10_
  - _Abhängig von: 11.9, 11.10_

- [ ] 11.13 Fundstelle in Suchtreffern [Backend]
  - Herkunft: Spec-Erweiterung, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR
  - Spec 02 ergänzt: `chunk.seite_von`/`seite_bis` über `Seitentext.von` (Spec 04) mit derselben Plausibilitätsprüfung gegen `dokument.seiten` (erledigt mit Spec 02, Aufgabe 6.3: Changeset `db/mandant` 021 legt die Spalten an, der `Chunker` füllt sie über `gemeinsam/domain/Seitentext`; kein neues Changeset, keine Nachberechnung nötig); `HybridSuche` gibt den besten Chunk mit (Spec 02, Aufgabe 6.4); öffentliche Suche liefert `fundstelle.seite`
  - _Anforderungen: 5.7_
  - _Abhängig von: 11.2; Spec 02: 6.3, 6.4_

## Welle 12 — Mandantenverwaltung und Abnahme

- [ ] 12.1 Monatsbericht pro Mandant [Backend]
  - Auftrag `MANDANT_BERICHT` (Speicher, Dokumente, Tokens nach Zweck, Chat-Fragen), E-Mail an Betreiber
  - _Anforderungen: 8.1_

- [ ] 12.2 Vollexport und Abbau [Backend]
  - `MANDANT_EXPORT` (alle Jahre, Original + Arbeitsfassung + PDF/A, Metadaten JSON/CSV, Manifeste); Kommando `mandant abbauen` mit 30-Tage-Frist, Liste gesperrter Objekte und Ablaufdatum der Sicherungsstände
  - Spec 04 ergänzt den Vollexport um den OKF-Export der Wissensbasis (Spec 04, 17.5; Aufgabe 15.5)
  - Spec 05 ergänzt: Der Vollexport enthält je Dokument die Klassifikationen mit Code und Name und die Konfigurationsversion sowie die aktuelle Konfigurationsdatei und alle Konfigurationsversionen (Spec 05 design.md, «Folgeänderungen»)
  - _Anforderungen: 8.2, 8.3, 8.5_

- [ ] 12.3 Isolations- und Sicherheitstests [QA]
  - `MandantenIsolationIT` (Szenarien `@req-1.4`, `@req-9.1`) über alle Wege; OWASP-API-Prüfliste und ZAP-Baseline gegen Staging, Prüfbericht unter `docs/abnahme/`; Befunde beheben
  - Spec 04 erweitert `MandantenIsolationIT` um die Wissensbasis (Spec 04, 11.5; Aufgabe 16.1)
  - Spec 05 erweitert `MandantenIsolationIT` um Arten, Klassifikationen, Regeln, KI-Texte und Konfigurationsversionen; liegt der Test noch nicht vor, legt ihn Spec 05, Aufgabe 18.5 an (Spec 05, Req 9.3)
  - Ergänzt 13.11.2026: `MandantenIsolationIT` mit Lese- und Erfassungs-Schlüsseln beider Mandanten über Lesen, Erfassung, Stream, Feed und Referenzen; zwei Schlüssel desselben Mandanten mit verschiedenen Stufen (9.4); OWASP-Prüfliste umfasst Erfassung, Stream, Feed, Filter und Umleitung, Stufe `vorstand` und die Erschöpfung der Schlüsselprüfung durch fremde Anfragen
  - _Anforderungen: 1.4, 9.1, 9.2, 9.4_

- [ ] 12.4 Einführung und Abnahme [Fabian]
  - Plugin auf der Verbandsseite installieren, Seiten mit Shortcodes/Blöcken gestalten, Geschäftsstelle schulen (30 Min.)
  - Ergänzt 13.11.2026: Lese- und Erfassungs-Schlüssel per Kommando anlegen und auf dem Webseitenserver hinterlegen; Schulung umfasst die Erfassung im WordPress-Admin
  - **Gate Phase 3:** Archiv online, Geschäftsstelle veröffentlicht selbständig eine neue Ausgabe
  - _Anforderungen: 9.3_
