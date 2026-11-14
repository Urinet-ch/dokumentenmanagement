# Implementation Plan — 02 Abgleich und Chat

Voraussetzung: Gate Phase 1 erfüllt, Spec 02 auf `main` gemergt. Ausnahme: Welle 6 (6.1, 6.3–6.6; 6.6 ergänzt 14.11.2026) wird vor Gate Phase 1 entwickelt (entschieden von Fabian am 13.11.2026, Fokus auf Funktionen für die Benutzer); Gate Phase 1 bleibt Bedingung für den produktiven Betrieb mit echten Belegen (Spec 01, Req 16.17), nicht für die Entwicklung. Welle 7 (7.1–7.4) wird ebenfalls vor Gate Phase 1 entwickelt (entschieden von Fabian am 14.11.2026, Priorität Chat-Funktionen); Gate Phase 1 bleibt Bedingung für den produktiven Betrieb mit echten Belegen (Spec 01, Req 16.17). 9.1 bleibt an Gate Phase 1 gebunden. Vertrag und Entscheide für 7.1–7.4: design.md, «Chat-Agent (`ki/chat`)», «Vertrag Welle 7». Wellen, Rollen und Gates wie in
`docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach `CLAUDE.md`.
Welle 8 (AbaNinja) läuft **nur**, wenn der Konzept-Entscheid E1 = ja; sonst wird sie übersprungen und die
Werkzeuge melden «Zahlstatus unbekannt». Optionale Aufgaben sind mit `*` markiert.
Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` (ausser bei Nachweis-Vermerk)
und erfüllt die Definition of Done aus `tech.md`.

## Welle 6 — E-Mail, Embeddings, Export (parallel)

- [x] 6.1 E-Mail-Eingang [Backend]
  - `integration_mail`, `mail_eingang`; `MailPoller` (Angus Mail, IMAP TLS), Zulassungsliste, Ordner `Archiviert`/`Quarantaene`, Idempotenz über Message-ID
  - Mail ohne Anhang → `.eml` + PDF aus Text (PDFBox) → normale Verarbeitung bis in den Eingangskorb, Dublettenprüfung wie Upload
  - Szenarien `@req-1.x` mit GreenMail
  - _Anforderungen: 1.1–1.6_

- [ ] 6.2 Postfach `archiv@urinet.ch` einrichten [Fabian]
  - Postfach bei Infomaniak anlegen, Ordner erstellen, Zulassungsliste (eigene Adressen) pflegen
  - Stand 13.11.2026: Postfach `archiv@urinet.ch` von Fabian bei Hostpoint angelegt, nicht bei Infomaniak (entschieden von Fabian; Hostpoint ist Schweizer Anbieter, die Mails bleiben in der Schweiz, Spec 01, Req 16.7). IMAP-Server, Benutzer und Passwort sind Konfiguration bzw. Secret, nie im Repo. Offen: Ordner `Archiviert`/`Quarantaene` anlegen, Zulassungsliste, Zugangsdaten im Archiv hinterlegen (nach 6.1)
  - Zugangsdaten nie ins Repo; Ablage gemäss design.md (verschlüsselt in `integration_mail`, Schlüssel aus `/run/secrets/`)
  - _Anforderungen: 1.1, 1.4_

- [x] 6.3 Chunks und Embeddings [Backend]
  - Liquibase-Changeset (`db/mandant`) `chunk` mit `halfvec(<dim aus Spike>)` + HNSW; `Chunker`; `EmbeddingService`; Auftrag `EMBEDDING` nach Archivierung; Nachberechnung `EMBEDDING_ALLE`
  - Spec 03 ergänzt: `chunk.seite_von`/`seite_bis` aus den Seitentrennern über `Seitentext.von` (Spec 04), `null` ohne Trenner oder bei abweichender Seitenzahl (Spec 03, Req 5.7; Aufgabe 11.13)
  - Der Embedding-Port wählt Adapter oder Stub über den Schalter `archiv.ki.embedding-stub` (Standard `${archiv.ki.stub}`, im Profil `lokal` `true`; Spec 01, Aufgabe 1.11)
  - _Anforderungen: 2.1, 2.4_

- [ ] 6.4 Hybridsuche [Backend]
  - `HybridSuche` (Volltext + Vektor + RRF + Reranker), Rückfall auf Volltext mit `degradiert`, Modus `hybrid` in `GET /dokumente`
  - Spec 03 ergänzt: `HybridSuche` gibt je Dokument den besten Chunk (Nummer und Seitenbereich) mit (Spec 03, Req 5.7; Aufgabe 11.13)
  - Der Reranker-Port wählt Adapter oder Stub über den Schalter `archiv.ki.reranker-stub` (Standard `${archiv.ki.stub}`, im Profil `lokal` `true`; Spec 01, Aufgabe 1.11)
  - Vertraulichkeits- und Mandantenszenarien nach der Rechtematrix (Spec 01, 2.6); Lasttest p95 ≤ 2 s
  - Ergänzt 14.11.2026 (entschieden von Fabian, Req 2.7, 2.8): Modus `hybrid` mit höchstens 30 Treffern ohne Gesamtzahl und ohne Blättern, Modus `volltext` unverändert, Rückfall in derselben Form mit `degradiert` (design.md «Hybrid als Standard in der Web-App»); `ki_verbrauch` mit Zweck `SUCHE` je Aufruf von Embedding-Modell und Reranker, kein Limit; Zweck im Kommentar zu `ki_verbrauch` in Spec 01 design.md nachtragen
  - _Anforderungen: 2.2, 2.3, 2.5, 2.6, 2.7, 2.8_

- [ ] 6.6 Hybridsuche als Standard in der Web-App [Frontend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 14.11.2026 («Hybrid wird Standard»); Nummer 6.6 als nächste freie der Welle 6
  - Suchseite `/suche` (Spec 01 design.md, Frontend): jede Suche mit Suchbegriff sendet `modus=hybrid` und zeigt höchstens 30 Treffer ohne Gesamtzahl und ohne Blättern; Umschalter «Volltext» als ausdrücklicher Modus mit dem bisherigen Verhalten (Sortierung, Blättern, Gesamtzahl); ohne Suchbegriff und bei Sortierung nach Datum Volltext; bei `degradiert` Hinweis «nur Volltext» mit Wechsel in den Modus Volltext
  - Browser-Szenarien `@req-2.7` (hybrid, Volltext, Rückfall bei Ausfall des Rerankers)
  - _Anforderungen: 2.7_
  - _Abhängig von: 6.4; Spec 01: 4.10 (Suchseite und Browser-Szenarien der Suche)_

- [x] 6.5 Jahresexport [Backend] [Frontend]
  - Auftrag `EXPORT_JAHR`, ZIP stromweise (nur `ARCHIVIERT`, `pdfa_fehlt` mit Original), `belegliste.csv`, `pruefsummen.sha256`, Manifeste, Dateinamensmuster, Protokoll, E-Mail ohne Inhalte; S3-Lebenszyklus 7 Tage
  - Seite «Export» nur für `inhaber`/`treuhaender`, sonst 403
  - _Anforderungen: 7.1–7.6_

## Welle 7 — Chat-Agent

- [ ] 7.1 Werkzeuge [Backend]
  - `ArchivWerkzeuge` mit `dokumente_suchen`, `dokument_zeigen`, `kennzahl`, `offene_posten`, `fristen`, `vollstaendigkeit`; Mandant/Benutzer aus Kontext; Sichtbarkeitsfilter nach Rechtematrix (Spec 01, 2.6) auch in Summen, Anzahlen und Lücken
  - je Werkzeug Szenarien mit zwei Mandanten und den Rollen `inhaber`, `mitarbeiter`, `treuhaender`
  - Spec 01 ergänzt: Suche ohne Dateinamen (`mitDateiname=false`, Spec 01, Req 3.13) braucht einen Index oder Vorfilter: `ts_filter(volltext_tsv, '{a,b,c}')` nutzt den GIN-Index nicht und durchsucht sonst alle Dokumente des Mandanten (Spec 01, design.md, «Umsetzung 4.10»; Review #104); `dokumente_suchen` ist der erste Konsument dieser Variante
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
  - Teil erledigt (PR #147): Seite gegen Attrappe; Menüpunkt und 4.3 folgen mit 7.4b
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
