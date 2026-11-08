# Implementation Plan — 03 Zweiter Mandant VHF/GSK

Voraussetzungen: Gate Phase 2, Zuschlag VHF/GSK, Entscheid E4. Wellen und Rollen wie in Spec 01.

## Welle 10 — Mandant, Branding, Erfassung

- [ ] 10.1 Auftragsbearbeitungsvertrag und Domain [Fabian]
  - Vorlage `docs/vertrag/avv-vorlage.md` erstellen, mit VHF/GSK abstimmen und unterzeichnen
  - Domain für das Archiv festlegen (`[Domain VHF/GSK]`), DNS einrichten
  - _Anforderungen: 8.4, 2.2_

- [ ] 10.2 Seed und Mandant anlegen [Backend]
  - `seed/vhf-gsk/dokumentarten.json` mit Zusatz-Schema; keine Finanzarten; Provisionierung ausführen; Keycloak-Client `archiv-vhf-gsk`
  - Test: Finanzwerkzeuge für `vhf-gsk` nicht verfügbar
  - _Anforderungen: 1.1, 1.2, 1.3_

- [ ] 10.3 Branding pro Mandant [Frontend] [Infra]
  - `GET /branding?domain=`, Web-App lädt Name/Logo/Farbe; Keycloak-Theme `vhf-gsk`; Caddy-Eintrag für die Verbands-Domain
  - _Anforderungen: 2.1–2.3_

- [ ] 10.4 Erfassung für die Geschäftsstelle [Backend] [Frontend]
  - Rolle `geschaeftsstelle` (veröffentlichen/zurückziehen), KI-Prompt pro Mandant (Reihe, Ausgabe, Jahr, Fachgebiet), Zugriffsstufe nur manuell (Standard `mitglieder`)
  - Eingangskorb zeigt Felder je Dokumentart
  - _Anforderungen: 3.1–3.4_

- [ ] 10.5 Altbestand übernehmen [Backend] [Fabian]
  - Drive-Import mit Mandant `vhf-gsk` und Pfad-Mustern (Reihe/Jahr), Bericht mit Lücken in der Nummerierung
  - Tabelle `alte_url` und CSV-Import der alten Links
  - _Anforderungen: 4.1–4.3_

## Welle 11 — Öffentliche Schnittstelle und Plugin (parallel)

- [ ] 11.1 Site-Key und Zugriffsstufen [Backend]
  - `SiteKeyAuthMechanism`, Header `X-Archiv-Zugriff`/`X-Archiv-Client`, Filter auf `veroeffentlicht` und `zugriff`, 404 bei zu hoher Stufe, kein CORS
  - _Anforderungen: 5.2, 5.3, 5.5, 5.6_

- [ ] 11.2 Endpunkte `/public/v1` [Backend]
  - Liste, Suche (Hybrid), Details, Datei-Link (5 Min., Protokoll mit gekürzter IP)
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
  - Barrierefreiheit prüfen (axe), Theme-Übernahme
  - _Anforderungen: 7.2, 7.6, 5.4_

## Welle 12 — Mandantenverwaltung und Abnahme

- [ ] 12.1 Monatsbericht pro Mandant [Backend]
  - Auftrag `MANDANT_BERICHT` (Speicher, Dokumente, Tokens nach Zweck, Chat-Fragen), E-Mail an Betreiber
  - _Anforderungen: 8.1_

- [ ] 12.2 Vollexport und Abbau [Backend]
  - `MANDANT_EXPORT` (alle Jahre, Original + PDF/A, Metadaten JSON/CSV, Manifeste); Kommando `mandant abbauen` mit 30-Tage-Frist und Liste gesperrter Objekte
  - _Anforderungen: 8.2, 8.3_

- [ ] 12.3 Isolations- und Sicherheitstests [QA]
  - `MandantenIsolationIT` über alle Wege; OWASP-API-Prüfliste und ZAP-Baseline gegen Staging; Befunde beheben
  - _Anforderungen: 1.4, 9.1, 9.2_

- [ ] 12.4 Einführung und Abnahme [Fabian]
  - Plugin auf der Verbandsseite installieren, Seiten mit Shortcodes/Blöcken gestalten, Geschäftsstelle schulen (30 Min.)
  - **Gate Phase 3:** Archiv online, Geschäftsstelle veröffentlicht selbständig eine neue Ausgabe
  - _Anforderungen: 9.3_
