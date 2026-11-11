# Implementation Plan — 03 Zweiter Mandant VHF/GSK

Voraussetzungen: Gate Phase 2, Zuschlag VHF/GSK, Konzept-Entscheid E4, Spec 03 auf `main` gemergt.
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
  - _Anforderungen: 1.1, 1.2, 1.3, 1.5_

- [ ] 10.3 Branding pro Mandant [Frontend] [Infra]
  - `GET /api/v1/branding?domain=`, Web-App lädt Name/Logo/Farbe; Keycloak-Theme `vhf-gsk` auf `archiv-auth.apps.urinet.ch`; Traefik-Router (Labels in `deploy/infomaniak/compose.yml`) für die Verbands-Domain, ausgerollt nur über den Deploy-Workflow
  - _Anforderungen: 2.1–2.3_

- [ ] 10.4 Erfassung für die Geschäftsstelle [Backend] [Frontend]
  - Rolle `geschaeftsstelle` nach Rechtematrix (Spec 01, 2.6: A1 bis A10 erlaubt, A11 bis A14 bis zum Entscheid 403), veröffentlichen/zurückziehen, KI-Prompt pro Mandant (Reihe, Ausgabe, Jahr, Fachgebiet), Zugriffsstufe nur manuell (Standard `mitglieder`)
  - Eingangskorb zeigt Felder je Dokumentart
  - Spec 05 ergänzt: KI-Prompt und Felder je Art aus der Konfiguration von `vhf-gsk`; die Felder und Klassifikationen je Art im Eingangskorb und in der Detailansicht liefert Spec 05, Aufgabe 18.4 (Spec 05, Req 7.5, 8.1)
  - _Anforderungen: 3.1–3.5_

- [ ] 10.5 Altbestand übernehmen [Backend] [Fabian]
  - Drive-Import mit API-Token vom Scope `import` des Mandanten `vhf-gsk` und Pfad-Mustern (Reihe/Jahr), Bericht mit Lücken in der Nummerierung
  - Tabelle `alte_url` und CSV-Import der alten Links
  - _Anforderungen: 4.1–4.3_

- [ ] 10.6 Sicherung für Mandant 2 [Infra]
  - Schema `m_vhf_gsk` und Bucket `urinet-archiv-vhf-gsk` in Sicherung, Zweitkopie und `backup-gate.sh` aufnehmen; Restore-Probe stellt beide mit wieder her
  - _Anforderungen: 1.5_

## Welle 11 — Öffentliche Schnittstelle und Plugin (parallel)

- [ ] 11.1 Site-Key und Zugriffsstufen [Backend]
  - `SiteKeyAuthMechanism`, Header `X-Archiv-Zugriff`/`X-Archiv-Client`, Filter auf `veroeffentlicht`, `zugriff` und Vertraulichkeit `NORMAL`, 404 bei zu hoher Stufe, kein CORS
  - _Anforderungen: 3.6, 5.2, 5.3, 5.5, 5.6_

- [ ] 11.2 Endpunkte `/public/v1` [Backend]
  - Liste, Suche (Hybrid), Details, Datei-Link (5 Min., Protokoll mit gekürzter IP)
  - Spec 05 ergänzt: Liste filtert zusätzlich nach den Klassifikationen mit `oeffentlich_filter` (z. B. `?fachgebiet=bad`), gibt nur diese aus; fester Sicherheitsfilter unverändert davor; reservierte Parameter in Spec 05 design.md (Spec 05, Req 8.6)
  - _Anforderungen: 5.1, 5.4_

- [ ] 11.3 Öffentlicher Chatbot [Backend]
  - `OeffentlicherChatAgent` mit eingeschränkten Werkzeugen, Quellen mit Reihe/Ausgabe/Link, Hinweis auf Geschäftsstelle, Rate-Limit 20/h, `mandant_budget`, Speicherung 90 Tage ohne Personenbezug
  - _Anforderungen: 6.1–6.5_

- [ ] 11.4 WordPress-Plugin Grundgerüst [Frontend]
  - Einstellungsseite (Site-Key verschlüsselt), Client mit Fehlerbehandlung und Transients, Zugriffsstufe aus Login/Rolle
  - wp-env für lokale Entwicklung, PHPUnit
  - _Anforderungen: 7.1, 7.3, 7.4, 7.5, 7.7_

- [ ] 11.5 Shortcodes, Blöcke, Download, Chatbot [Frontend]
  - `[urinet_archiv_liste]`, `[urinet_archiv_suche]`, `[urinet_chatbot]` + Gutenberg-Blöcke; Download-Route mit 302; WP-REST-Proxy für den Chat (SSE), `assets/chatbot.js`
  - Browser-Szenarien mit Cucumber.js + Playwright gegen wp-env; Barrierefreiheit prüfen (axe), Theme-Übernahme
  - Spec 05 ergänzt: Filter der Liste nach den Klassifikationen mit `oeffentlich_filter`, nur diese werden angezeigt (Spec 05, Req 8.6)
  - _Anforderungen: 7.2, 7.6, 5.4_

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
  - _Anforderungen: 1.4, 9.1, 9.2_

- [ ] 12.4 Einführung und Abnahme [Fabian]
  - Plugin auf der Verbandsseite installieren, Seiten mit Shortcodes/Blöcken gestalten, Geschäftsstelle schulen (30 Min.)
  - **Gate Phase 3:** Archiv online, Geschäftsstelle veröffentlicht selbständig eine neue Ausgabe
  - _Anforderungen: 9.3_
