# Implementation Plan — 02 Abgleich und Chat

Voraussetzung: Gate Phase 1 erfüllt, Spec 02 auf `main` gemergt. Wellen, Rollen und Gates wie in
`docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach `CLAUDE.md`.
Welle 8 (AbaNinja) läuft **nur**, wenn der Konzept-Entscheid E1 = ja; sonst wird sie übersprungen und die
Werkzeuge melden «Zahlstatus unbekannt». Optionale Aufgaben sind mit `*` markiert.
Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` (ausser bei Nachweis-Vermerk)
und erfüllt die Definition of Done aus `tech.md`.

## Welle 6 — E-Mail, Embeddings, Export (parallel)

- [ ] 6.1 E-Mail-Eingang [Backend]
  - `integration_mail`, `mail_eingang`; `MailPoller` (Angus Mail, IMAP TLS), Zulassungsliste, Ordner `Archiviert`/`Quarantaene`, Idempotenz über Message-ID
  - Mail ohne Anhang → `.eml` + PDF aus Text (PDFBox) → normale Verarbeitung bis in den Eingangskorb, Dublettenprüfung wie Upload
  - Szenarien `@req-1.x` mit GreenMail
  - _Anforderungen: 1.1–1.6_

- [ ] 6.2 Postfach `archiv@urinet.ch` einrichten [Fabian]
  - Postfach bei Infomaniak anlegen, Ordner erstellen, Zulassungsliste (eigene Adressen) pflegen
  - Zugangsdaten nie ins Repo; Ablage gemäss design.md (verschlüsselt in `integration_mail`, Schlüssel aus `/run/secrets/`)
  - _Anforderungen: 1.1, 1.4_

- [ ] 6.3 Chunks und Embeddings [Backend]
  - Liquibase-Changeset (`db/mandant`) `chunk` mit `halfvec(<dim aus Spike>)` + HNSW; `Chunker`; `EmbeddingService`; Auftrag `EMBEDDING` nach Archivierung; Nachberechnung `EMBEDDING_ALLE`
  - _Anforderungen: 2.1, 2.4_

- [ ] 6.4 Hybridsuche [Backend]
  - `HybridSuche` (Volltext + Vektor + RRF + Reranker), Rückfall auf Volltext mit `degradiert`, Modus `hybrid` in `GET /dokumente`
  - Vertraulichkeits- und Mandantenszenarien nach der Rechtematrix (Spec 01, 2.6); Lasttest p95 ≤ 2 s
  - _Anforderungen: 2.2, 2.3, 2.5, 2.6_

- [ ] 6.5 Jahresexport [Backend] [Frontend]
  - Auftrag `EXPORT_JAHR`, ZIP stromweise (nur `ARCHIVIERT`, `pdfa_fehlt` mit Original), `belegliste.csv`, `pruefsummen.sha256`, Manifeste, Dateinamensmuster, Protokoll, E-Mail ohne Inhalte; S3-Lebenszyklus 7 Tage
  - Seite «Export» nur für `inhaber`/`treuhaender`, sonst 403
  - _Anforderungen: 7.1–7.6_

## Welle 7 — Chat-Agent

- [ ] 7.1 Werkzeuge [Backend]
  - `ArchivWerkzeuge` mit `dokumente_suchen`, `dokument_zeigen`, `kennzahl`, `offene_posten`, `fristen`, `vollstaendigkeit`; Mandant/Benutzer aus Kontext; Sichtbarkeitsfilter nach Rechtematrix (Spec 01, 2.6) auch in Summen, Anzahlen und Lücken
  - je Werkzeug Szenarien mit zwei Mandanten und den Rollen `inhaber`, `mitarbeiter`, `treuhaender`
  - _Anforderungen: 3.1–3.6, 5.1, 5.2_

- [ ] 7.2 Agent und Systemprompt [Backend]
  - `ArchivAgent` (LangChain4j, Tools, Gesprächsspeicher 10 Nachrichten), Systemprompt gemäss design.md, Betragsformat, Datenbasis, «kein Beleg gefunden»
  - Prompt-Injection-Test mit präpariertem Dokument
  - _Anforderungen: 3.7, 4.2, 5.3_

- [ ] 7.3 Chat-Schnittstelle [Backend]
  - `POST /chat` als SSE (`werkzeug`, `text`, `quellen`, `fertig`, `fehler`), Speicherung `chat_gespraech`/`chat_nachricht`, Löschung nach 12 Monaten, Rate-Limit 100/h
  - _Anforderungen: 3.8, 4.1, 5.4, 5.5_

- [ ] 7.4 Chat-Oberfläche [Frontend]
  - Chat-Seite mit Streaming, Werkzeug-Hinweis, Quellen-Chips (öffnen Dokument), letzte 20 Gespräche; 375 px
  - _Anforderungen: 4.3, 6.1–6.3_

## Welle 8 — AbaNinja (nur bei E1 = ja)

- [ ] 8.1 Entscheid E1 und Zugang [Fabian]
  - Abo «Basic» abschliessen, API-Token mit minimalen Scopes erstellen (Adressen, Dokumente lesen, Webhooks), Konto-UUID notieren; Token nie ins Repo
  - _Anforderungen: 8.1_

- [ ] 8.2 API-Spike und Client [Backend]
  - Endpunkte, Paging, Webhook-Echtheitsprüfung und Rate-Limits aus docs.abaninja.ch in `docs/spikes/abaninja-api.md`
  - `AbaNinjaClient` hinter Driven Port mit Dev-Stub, `integration_abaninja` (AES-GCM, Schlüssel aus `/run/secrets/archiv_data_key`), Verbindungstest, Backoff
  - _Anforderungen: 8.2, 8.3, 8.4_

- [ ] 8.3 Abgleich Kontakte [Backend]
  - `AbaNinjaSync` Adressen → Kontakte (UUID → E-Mail → Name+PLZ), CSV-Felder erhalten, Liste «Kontakte prüfen»
  - _Anforderungen: 9.1–9.3_

- [ ] 8.4 Rechnungen und Lieferantenbelege [Backend]
  - `belegnummer_alias` mit Startwerten; Status/`bezahlt_am` übernehmen (Systemänderung, protokolliert); fehlende PDFs in den Eingangskorb; Lieferantenbelege verknüpfen oder erfassen
  - Test: das Archiv ruft nie schreibende Zahlungs-Endpunkte auf
  - _Anforderungen: 8.5, 10.1–10.3, 11.1, 11.2_

- [ ] 8.5 Webhooks [Backend]
  - `POST /webhooks/abaninja/{mandant}`, Echtheitsprüfung, Idempotenz, 202 ≤ 1 s, Nachholen im Tagesabgleich
  - _Anforderungen: 12.1–12.4_

- [ ] 8.6 Verwaltungsseite AbaNinja [Frontend]
  - Verbindung erfassen/testen, letzter Abgleich, Fehler, Alias-Tabelle, Liste «Kontakte prüfen»
  - _Anforderungen: 8.3, 9.3, 10.1_

## Welle 9 — Abnahme Phase 2

- [ ] 9.1 Chat-Prüfliste [Fabian] [QA]
  - `chat-pruefliste.yaml` mit 7 Beispielfragen + ≥ 13 weiteren, erwartete Antworten und Quellen – nur unter `ARCHIV_GOLDEN_DIR`, nicht im Repo; im Repo nur Schema und Test `ChatPrueflisteIT`
  - Lauf manuell gegen den Produktivbestand, nie in der CI; Bericht in `docs/abnahme/phase2.md` nur mit Fragen-Nr. und Ergebnis, ohne Inhalte
  - **Gate Phase 2:** alle 7 Beispielfragen und ≥ 18 von 20 richtig mit Quelle
  - _Anforderungen: 13.1, 13.2_

## Optionen
Optionale Aufgaben tragen die Nummern `O.<n>` (ohne Welle), damit sie nicht mit den Aufgaben 10.x von Spec 03 kollidieren. Spec 04 belegt die Nummern O.4–O.6.

- [ ]* O.1 Eigene Rechnungen nach AbaNinja importieren (I4) [Backend]
  - Generator-Dokumente der Art Ausgangsrechnung per PDF-Import an AbaNinja senden, AbaNinja-UUID speichern
- [ ]* O.2 Bankbewegungen für «bezahlt am» (I5) [Backend]
- [ ]* O.3 MCP-Server für Claude (C3) [Backend]
  - Werkzeuge aus 7.1 als MCP-Server (nur lesend), Token pro Benutzer, gleiche Schutzregeln
