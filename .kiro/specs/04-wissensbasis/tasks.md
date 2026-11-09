# Implementation Plan — 04 Wissensbasis

Voraussetzungen: Gate Phase 2 (Entscheid 09.11.2026: Spec 04 nach Spec 02), Feld titel mit Herkunftsmarkierung in Spec 01
umgesetzt (eigener PR, Entscheid 09.11.2026), Spec 04 und ADR-013 auf `main` gemergt. Die Reihenfolge zu Phase 3 ist nicht
entschieden: [offen: Lage von Spec 04 zu Spec 03 – Entscheid in umsetzungsplan.md]. Alle Teile für den Mandanten `vhf-gsk`
(Seed-Felder nach 13.2, Freigabe durch die Geschäftsstelle nach 13.5, Szenarien mit `geschaeftsstelle`) setzen Spec 03,
Aufgabe 10.2 voraus. Wellen, Rollen und Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe)
nach `CLAUDE.md`, Branch `feature/04-<aufgabe>-<stichwort>`. Die Wellen 13–16 folgen auf die Nummern von Spec 03. Steht ein
Wert noch auf «[offen: …]», wird er nicht geraten; die Aufgabe wartet auf den Entscheid (Aufgabe 13.1). Abhängigkeiten zu anderen Specs stehen als `Spec 0n: <Aufgabe>`.
Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` unter `features/wissen/` (ausser bei
Nachweis-Vermerk) und erfüllt die Definition of Done aus `tech.md`.

## Welle 13 — Grundlagen der Wissensbasis (Voraussetzung: Gate Phase 2)

- [ ] 13.1 Entscheide und Folgeänderungen [Fabian]
  - Entscheid vor Welle 13 einholen: Lage von Spec 04 zu Spec 03 (`umsetzungsplan.md`), weitere Seitentypen für `vhf-gsk`, Recht A19 der Geschäftsstelle
  - Entscheide vor Welle 14 einholen: Tokenbudget je Ingest, Ingest für archivierte Dokumente anderer Arten
  - Entscheid vor Welle 16 einholen: Stichprobengrösse und Schwellen Gate Phase 4
  - ADR-013 per Merge freigeben; die Folgeänderungen nach design.md, Abschnitt «Folgeänderungen in anderen Dokumenten» (Spec 01/02/03, `product.md`, `structure.md`, `tech.md`, `umsetzungsplan.md`, `docs/adr/README.md`, Nachträge zu ADR-006, ADR-009 und ADR-011) nach dem Merge von PR #15 und PR #16 als eigenen PR veranlassen
  - _Anforderungen: 2.3, 5.7, 12.1, 13.4, 18.1_

- [ ] 13.2 Dokumentart Wissensquelle [Backend]
  - Seed-Teil `seed/_alle/dokumentarten-wissen.json` nach design.md, beim Anlegen jedes Mandanten eingelesen, nur anlegen, wenn der Code fehlt; für `vhf-gsk` mit `zugriff` und `veroeffentlicht` (erst nach Spec 03, Aufgabe 10.2); `WissensquelleSeedTest`; `DokumentartSeedTest` aus Spec 01 unverändert
  - Art nicht in der Artenliste des KI-Vorschlags (Test: Prompt aus Spec 01 unverändert); Szenarien: Art im Eingangskorb von Hand gewählt, Upload bis `ARCHIVIERT` ohne Sperre, Datei im Manifest, Titel aus `dokument.titel`, Jahresexport ohne Wissensquelle
  - Satz zu GeBüV in der Verfahrensbeschreibung (Nachweis Dokument, zusammen mit der Folgeänderung zu Spec 01, 16.6)
  - _Anforderungen: 1.1–1.6_
  - _Abhängig von: Spec 01: Feld titel (eigener PR, Entscheid 09.11.2026)_

- [ ] 13.3 Fachbereich `wissen` und Datenmodell [Backend]
  - Paket `wissen` mit `domain`, `application`, `adapter`; ArchUnit-Regeln wie ADR-011 (kein Zugriff auf `public` ausser `auftrag`)
  - Liquibase-Changesets (`db/mandant`) für alle Tabellen aus design.md; Trigger INSERT-only auf `wissensseite_version` und `wissens_log`, `archiv_app` dort nur `INSERT`/`SELECT`
  - Manifest um Zeilen von `wissensseite_version` und `wissens_log` erweitern; Restore-Probe vergleicht Anzahl Seiten und Versionen
  - Logs nur mit IDs (Test über Log-Ausgabe)
  - _Anforderungen: 4.1–4.3, 4.6, 4.7, 13.9_

- [ ] 13.4 Seitenmodell, Markdown und Prüfung [Backend]
  - `Wissensseite`, `Quelleneintrag`, `Seitentyp`; `WissensMarkdown` (commonmark-java, Footnotes, ohne HTML); `Seitentext` aus `\f`-getrenntem Volltext
  - `WissensPruefung` mit allen Befunden aus design.md, als reine Domain-Logik mit JUnit-Tests; Links aus dem Inhalt nach `wissensseite_link`
  - _Anforderungen: 2.1–2.8, 3.5, 3.7_
  - _Abhängig von: 13.3_

- [ ] 13.5 Sichtbarkeit und Rechte [Backend]
  - Hülle (`wissensseite_dokument`, rekursiv mit Zyklen) und Hülle je Version (`wissensseite_version_dokument`), wirksame Vertraulichkeit = max(Hülle, Mindeststufe), `WissenSichtbarkeit` auf Basis des `VertraulichkeitFilter` inkl. Mindeststufe
  - Aktionen A15–A19 in `Berechtigung`, 404 vor 403; Szenarien mit zwei Mandanten und allen vier Rollen; Seite von Hand auf `INTERN` → `mitarbeiter` erhält 404, auch nach Neuberechnung
  - _Anforderungen: 11.1–11.3, 11.5, 11.8, 12.1–12.3_
  - _Abhängig von: 13.3_

- [ ] 13.6 Lesen: Index, Seite, Versionen, Log [Backend]
  - REST `GET /wissensseiten…`, `/wissensbasis/log` nach design.md mit ETag; Index nach Typ gruppiert, Log aus Feldern erzeugt und gefiltert; eingehende Links inkl. Quelleneinträgen auf die Wissensquelle einer Quelle-Seite; Versionen und Diff mit Sichtbarkeit je Version (Szenario: frühere `INTERN`-Version bleibt für `mitarbeiter` 404)
  - Zugriffsprotokoll `WISSEN_ANZEIGEN` und `VERWEIGERT`, ohne Inhalte
  - _Anforderungen: 3.1–3.4, 3.6, 3.8, 4.4, 4.5, 14.2, 14.4–14.6_
  - _Abhängig von: 13.4, 13.5_

## Welle 14 — Ingest und Freigabe

- [ ] 14.1 Ingest-Aufträge [Backend]
  - Port `DokumentEreignisse` nach design.md (Ergänzung zu Spec 01, in derselben Transaktion); Auftrag `WISSEN_INGEST` nach `ARCHIVIERT` und über `POST /dokumente/{id}/wissen-ingest` (Seitenbereich); höchstens ein Ingest je Mandant `IN_ARBEIT`/`OFFEN`, weitere `WARTEND`; Vorprüfung (Art, `PERSONAL`, Volltext, PDF/A und `\f`-Seiten, laufender oder wartender Ingest, Schätzung mit Ein- und Ausgabe, Monatsbudget der Wissensbasis)
  - Teilaufträge `KERNAUSSAGEN`, `PLANUNG`, `SEITE`, `QUELLE` mit je einem Aufruf über `KiPort` und JSON-Schema, `ABSCHLUSS` ohne Aufruf; Auszugsprüfung; Ingest-Kontext nur bis zur Stufe der Wissensquelle, `ueberblick` nur bei `NORMAL`; belegter Pfad höherer Stufe → Befund und Hinweis; Kontextstufe je Änderung; Eingrenzung grosser Indizes über die Volltextsuche (Vektor folgt in 15.1)
  - `archiv.wissen.*` (Modell über `archiv.ki.basis-url`, kein eigener URL-Schlüssel); `ki_verbrauch` mit neuen Zwecken; Zeitgrenzen der neuen Auftragstypen
  - Szenarien mit WireMock je Schritt (gültig, ungültig mit Nachfrage je Schritt, Zeitüberschreitung, Budget erreicht, Monatsbudget erschöpft, zwei Wissensquellen nacheinander); Konfigurationstests nach 13.1 und 13.2 (JUnit, Nachweis); Profil `lokal` zusätzlich von Hand mit LM Studio (kein Nachweis); Stufe A nur mit Testdaten
  - Prompt-Injection-PDF: Ergebnis nur als Entwurf mit Befunden
  - _Anforderungen: 5.1–5.13, 11.6, 11.7, 13.1–13.8, 14.3_
  - _Abhängig von: 13.2, 13.4, 13.5_

- [ ] 14.2 Entwurf, Diff und Freigabe [Backend]
  - `DiffDienst` (java-diff-utils, Felddiff), `FreigabeDienst` mit `If-Match`, 412/428, `WissensPruefung` bei jedem Annehmen, Befund 422, Vertraulichkeit nicht senkbar und nur bis `INTERN` erhöhbar (Mindeststufe), kein gesammeltes Annehmen, «neu erzeugen»; Kernaussagen im Ingest-Entwurf
  - Transaktion: Seite, Version, Hülle der Version, Quelleneinträge, Links, Hüllen, Log, Zugriffsprotokoll `WISSEN_ANNEHMEN`/`WISSEN_VERWERFEN`, Auftrag `WISSEN_EMBEDDING` (nur einreihen; Handler in 15.1, bis dahin bleibt der Auftrag wartend, der Test prüft nur das Einreihen); Start des nächsten wartenden Ingest
  - manueller Entwurf `POST /wissensentwuerfe`; Felder nach 7.6 nur vom System
  - _Anforderungen: 6.1–6.10, 7.6, 14.1_
  - _Abhängig von: 14.1_

- [ ] 14.3 Widerspruchsschutz [Backend]
  - Widerspruchsblöcke erkennen und im Diff markieren, Befund `AUSSAGE_ENTFERNT`, Grund beim Auflösen (sonst 422 `PFLICHTFELD_FEHLT`, Grund gilt für die entfernten Schlüssel), Logeintrag «Widerspruch aufgelöst», Flag `widerspruch_offen`
  - Szenario: neue Quelle widerspricht bestehender Aussage → beide Fussnoten bleiben, nichts überschrieben
  - _Anforderungen: 7.1–7.5_
  - _Abhängig von: 14.2_

- [ ] 14.4 Änderungen an Quelldokumenten [Backend]
  - `QuelldokumentBeobachter` auf `DokumentEreignisse.geaendert` (Metadaten-Versionen, Vertraulichkeit, Art und Status); Neuberechnung nie unter die Mindeststufe; Hinweis «Quelle prüfen» und Bestätigung (A17, Logeintrag `UPDATE`); Dokument wird `PERSONAL` → «Quelle prüfen», vom Lint ausgenommen; aktueller Dokumenttitel in Quelleneinträgen; `GET /dokumente/{id}` liefert verwendende Seiten
  - Szenario: Dokument nach A8 auf `INTERN` → `mitarbeiter` sieht die Seite ab der nächsten Anfrage nicht mehr
  - _Anforderungen: 11.3, 15.1–15.5_
  - _Abhängig von: 13.5, 14.1 (Port `DokumentEreignisse`)_

- [ ] 14.5 Oberfläche Wissensbasis [Frontend]
  - Routen `/wissen`, `/wissen/ueberblick`, `/wissen/:ordner/:name`, `/wissen/log`; Index mit Filtern, Seite mit Quellen-Chips (öffnen `/dokument/:id?seite=n`), Hinweis-Bannern, eingehenden Links und Versionen; Abschnitt «In der Wissensbasis verwendet» und Parameter `seite` in der Dokumentansicht
  - 375 px, WCAG 2.1 AA (axe), Texte nur in `texte.de-CH.ts`; Szenarien mit Cucumber.js + Playwright
  - _Anforderungen: 3.6, 3.8, 15.4, 16.1, 16.2, 16.4, 16.5, 16.7_
  - _Abhängig von: 13.6_

- [ ] 14.6 Oberfläche Entwürfe [Frontend]
  - `/wissen/entwuerfe`, `/wissen/entwuerfe/:id`: Diff nebeneinander bzw. am Handy untereinander mit `−`/`+`, Befunde, Hinweise, Kontextstufe, Widersprüche, entfernte Aussagen, Kernaussagen (nicht zugeordnete markiert); Aktionen je Änderung mit Bestätigung, kein gesammeltes Annehmen; Zähler offener Entwürfe in der Navigation
  - _Anforderungen: 6.4, 6.11, 16.3, 16.4_
  - _Abhängig von: 14.2_

## Welle 15 — Suche, Chat, Lint und Export (parallel)

- [ ] 15.1 Seiten-Embeddings und Suche [Backend] [Frontend]
  - `wissensseite_chunk`, Aufträge `WISSEN_EMBEDDING`/`WISSEN_EMBEDDING_ALLE`, `WissensSuche` (Volltext + Vektor + RRF + Reranker, Rückfall), `GET /wissensseiten?q=`; Abschnitt «Wissen» auf der Suchseite
  - Eingrenzung des Ingest-Index (14.1) auf Hybridsuche umstellen; Profil `lokal` nur Volltext; Lasttest 2'000 Seiten p95 ≤ 1 s
  - _Anforderungen: 9.1–9.6, 13.2_
  - _Abhängig von: 14.2, Spec 02: 6.3, 6.4_

- [ ] 15.2 Chat-Werkzeuge und Quellen [Backend]
  - `wissen_index` und `wissen_seite_zeigen` in `ArchivWerkzeuge` (nur lesend, Mandant/Benutzer aus Kontext, Kürzung nach `werkzeug-max-zeichen` mit `GEKUERZT`), Systemprompt-Ergänzung, SSE-Quellenform `{wissen, dokumente}`, Hinweise in der Antwort
  - Szenarien mit simulierten Tool-Calls: nur freigegebene Fassungen, `mitarbeiter` erhält keine Seite mit unsichtbarer Hülle; `OeffentlicheWerkzeuge` (Spec 03) ohne Wissenswerkzeuge, sobald vorhanden
  - _Anforderungen: 8.1–8.7, 8.9, 11.4_
  - _Abhängig von: 15.1, Spec 02: 7.1–7.3_

- [ ] 15.3 Chat-Oberfläche und Analyse-Vorschlag [Frontend] [Backend]
  - Quellen-Chips für Seiten (öffnen die Seite mit Protokoll), Aktion «Als Analyse vorschlagen» für A18 nur bei zulässigen Gesprächen; `POST /wissensentwuerfe/analyse` ohne KI-Aufruf (422 `ANALYSE_NICHT_MOEGLICH` nach Aufruf von `kennzahl` u. a., Quellen ohne Seite, Kontextstufe aus allen Werkzeugergebnissen des Gesprächs), Status eigener Vorschläge ohne Fassung und Diff; Logeintrag `ANALYSE`
  - _Anforderungen: 6.10, 8.8, 12.3, 12.4, 16.6_
  - _Abhängig von: 15.2, 14.2, Spec 02: 7.4_

- [ ] 15.4 Lint [Backend]
  - Scheduler und manueller Start (A17, Protokoll `WISSEN_LINT`); Prüfungen ohne KI inkl. fehlender Querverweise und Kandidaten «veraltet»; KI-Paare auch verschiedener Vertraulichkeit (ohne `PERSONAL`-Hülle), Änderung nur an der höher eingestuften Seite, mit `lint-paare` und Budget; Vorschläge als Entwurf `LINT`; `wissens_lint`; Logeintrag `LINT` (auch «Budget erschöpft»)
  - _Anforderungen: 10.1–10.6, 14.3_
  - _Abhängig von: 14.2, 14.3_

- [ ] 15.5 OKF-Export [Backend] [Frontend]
  - Auftrag `WISSEN_EXPORT`, ZIP mit Frontmatter (SnakeYAML), `index.md`, `log.md`, URN-Verweise, Links im Inhalt auf `.md` umgeschrieben; Download 24 h; Protokoll mit SHA-256; Einstellungen `/wissensbasis/einstellungen` (A19)
  - `OkfKonformitaetTest` (JUnit, Nachweis für 17.3, im PR verlinkt); Vollexport nach Spec 03, 8.2 um den OKF-Export ergänzen, sobald 12.2 aus Spec 03 vorhanden ist
  - _Anforderungen: 17.1–17.5, 14.3_
  - _Abhängig von: 13.6_

## Welle 16 — Abnahme Phase 4

- [ ] 16.1 Sicherheits- und Isolationstests [QA]
  - Szenario `@req-18.5` mit zwei Mandanten und allen Rollen über Index, Seiten, Suche, Log, Entwürfe, Chat-Werkzeuge und Export; `MandantenIsolationIT` (Spec 03) um die Wissensbasis erweitern, sobald vorhanden
  - Prompt-Injection-Prüfung aus 14.1 über alle Wege wiederholen; Befunde beheben
  - _Anforderungen: 11.5, 11.6, 18.5_
  - _Abhängig von: 15.1–15.5_

- [ ] 16.2 Prüfliste und Gate [Fabian] [QA]
  - `wissen-pruefliste.yaml` (Wissensquellen, Fussnoten-Stichprobe, Fragen mit erwarteten Seiten und Quelldokumenten) nur unter `ARCHIV_GOLDEN_DIR`; im Repo nur Schema und Test `WissenPrueflisteIT`
  - Lauf manuell auf Stufe B, nie in der CI; Bericht `docs/abnahme/phase4.md` nur mit Nummern und Ergebnissen, Kennzahl «unverändert angenommen»
  - **Gate Phase 4:** Belegtreue, kein stilles Überschreiben und Antwortquote nach den Schwellen aus 13.1, Isolationstest grün
  - _Anforderungen: 18.1–18.4, 18.6, 18.7_
  - _Abhängig von: 16.1_

## Optionen
Optionale Aufgaben führen die Nummern von Spec 02 (`O.1`–`O.3`) fort.

- [ ]* O.4 Wissensseiten für Verbandsmitglieder VHF/GSK [Backend]
  - öffentliche Schnittstelle und Chatbot (Spec 03) um freigegebene Seiten mit Hülle nur aus veröffentlichten `NORMAL`-Dokumenten erweitern; ändert Spec 03, 6.1
- [ ]* O.5 Import eines OKF-Bundles als Entwurf [Backend]
  - z. B. ein bestehendes LLM-Wiki; Quellen ohne Archivdokument werden als Befund markiert
- [ ]* O.6 Wissenswerkzeuge im MCP-Server (O.3) [Backend]
