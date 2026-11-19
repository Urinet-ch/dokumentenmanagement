# Implementation Plan — 04 Wissensbasis

Voraussetzungen: Gate Phase 2 (Entscheid 09.11.2026: Spec 04 nach Spec 02), Gate Phase 3 (Spec 04 nach Spec 03,
entschieden von Fabian am 09.11.2026), Spec 04 und ADR-013 auf `main` gemergt. Das Feld `dokument.titel` gibt es in Spec 01 bereits
(Requirement 7.2); seine Präzisierungen im eigenen PR sind keine Voraussetzung. Weil Spec 03 vorher umgesetzt ist, sind
auch die Teile für den Mandanten `vhf-gsk` (Seed-Felder nach 13.2, Freigabe durch die Geschäftsstelle nach 13.5,
Szenarien mit `geschaeftsstelle`) mit Spec 03, Aufgabe 10.2 erfüllt. Wellen, Rollen und Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe)
nach `CLAUDE.md`, Branch `feature/04-<aufgabe>-<stichwort>`. Die Wellen 13–16 folgen auf die Nummern von Spec 03. Steht ein
Wert noch auf «[offen: …]», wird er nicht geraten; die Aufgabe wartet auf den Entscheid (Aufgabe 13.1; bei 15.5 auf die Spec zur Löschfunktion – 14.4 setzt 15.5 bis dahin nur mit der Kennzeichnung «Quelle prüfen» um). Abhängigkeiten zu anderen Specs stehen als `Spec 0n: <Aufgabe>`.
Jede Aufgabe liefert für ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` unter `features/wissen/` (ausser bei
Nachweis-Vermerk) und erfüllt die Definition of Done aus `tech.md`.

## Welle 13 — Grundlagen der Wissensbasis (Voraussetzung: Gate Phase 2 und Gate Phase 3)

- [ ] 13.1 Entscheide und Folgeänderungen [Fabian]
  - Alle zwölf Entscheide vom 09.11.2026 eingetragen (entschieden von Fabian am 09.11.2026; design.md, Open Points)
  - Offen bleibt nur der Umgang mit Seitenversionen gelöschter Dokumente (revDSG), Entscheid in der Spec zur Löschfunktion (Requirement 15.5)
  - ADR-013 per Merge freigeben; die Folgeänderungen nach design.md, Abschnitt «Folgeänderungen in anderen Dokumenten» (Spec 01/02/03, `product.md`, `structure.md`, `tech.md`, `umsetzungsplan.md`, `docs/adr/README.md`, Nachträge zu ADR-006, ADR-009 und ADR-011) nach dem Merge von PR #15 als eigenen PR veranlassen (PR #16 ist gemergt); darin auch die neuen Fehlercodes in die abschliessende Code-Liste von Spec 01
  - _Anforderungen: 2.3, 5.7, 12.1, 13.4, 13.6, 18.1_

- [ ] 13.2 Dokumentart Wissensquelle [Backend]
  - Seed-Teil `seed/_alle/dokumentarten-wissen.json` nach design.md, beim Anlegen jedes Mandanten eingelesen, nur anlegen, wenn der Code fehlt; für `vhf-gsk` mit `zugriff` und `veroeffentlicht` (Spec 03, Aufgabe 10.2); `WissensquelleSeedTest`; `DokumentartSeedTest` aus Spec 01 unverändert
  - Art nicht in der Artenliste des KI-Vorschlags (Test: Prompt aus Spec 01 unverändert); Szenarien: Art im Eingangskorb von Hand gewählt, Upload bis `ARCHIVIERT` ohne Sperre, Datei im Manifest, Titel aus `dokument.titel`, Jahresexport ohne Wissensquelle
  - Satz zu GeBüV in der Verfahrensbeschreibung (Nachweis Dokument, zusammen mit der Folgeänderung zu Spec 01, 16.6)
  - Spec 05 ergänzt: Die Ergänzung für `vhf-gsk` (`zugriff`, `veroeffentlicht`) kommt aus der Konfiguration von `vhf-gsk`, nicht aus dem Seed-Teil; `WISSENSQUELLE` trägt fest `ki_vorschlag = nein` (Spec 05, Req 2.6; Aufgabe 17.3)
  - Teil erledigt (PR #297): Seed-Teil, Art in `seed/vhf-gsk/dokumentarten.json` mit `zugriff` und `veroeffentlicht`, Prüfung der festen Eigenschaften beim Anlegen, Changeset `044-wissensquelle-bestand` für bestehende Mandanten, Archivierung ohne Sperre, Manifest, Jahresexport ohne Wissensquelle, Verfahrensbeschreibung (Req 1.1–1.3, 1.5, 1.6; design.md, «Umsetzung 13.2»). Offen: Req 1.4, nachgewiesen erst mit Ingest, Freigabe und Lint (14.x)
  - _Anforderungen: 1.1–1.6_
  - _Abhängig von: Spec 01: Feld `dokument.titel` (vorhanden, Requirement 7.2)_

- [ ] 13.3 Fachbereich `wissen` und Datenmodell [Backend]
  - Paket `wissen` mit `domain`, `application`, `adapter`; ArchUnit-Regeln wie ADR-011 (kein Zugriff auf `public` ausser `auftrag`)
  - Liquibase-Changesets (`db/mandant`) für alle Tabellen aus design.md; Trigger INSERT-only auf `wissensseite_version` und `wissens_log`, `archiv_app` dort nur `INSERT`/`SELECT`
  - Manifest um Zeilen von `wissensseite_version` und `wissens_log` erweitern; Restore-Probe vergleicht Anzahl Seiten und Versionen
  - Logs nur mit IDs (Test über Log-Ausgabe)
  - Teil erledigt (PR #295): Paket, Changeset `043-wissen-datenmodell`, Rechte und INSERT-only, Manifest (Req 4.1–4.3, 4.6); `public` nur über `auftrag` prüft die allgemeine ArchUnit-Regel `publicNurUeberAuftrag`. Offen: Req 4.7, der Vergleich von Anzahl Seiten und Versionen in der Restore-Probe; er braucht die Restore-Kette mit Datenbank-Restore aus Spec 01, Aufgabe 5.12 (design.md, «Umsetzung 13.3»). 13.9 weisen die Ingest- und Freigabepfade nach (14.x)
  - _Anforderungen: 4.1–4.3, 4.6, 4.7, 13.9_

- [x] 13.4 Seitenmodell, Markdown und Prüfung [Backend]
  - `Wissensseite`, `Quelleneintrag`, `Seitentyp`; `WissensMarkdown` (commonmark-java, Footnotes, ohne HTML); `Seitentext` aus `\f`-getrenntem Volltext (besteht seit Spec 02, Aufgabe 6.3, als `gemeinsam/domain/Seitentext`; wiederverwenden, nicht neu bauen)
  - `WissensPruefung` mit allen Befunden aus design.md, als reine Domain-Logik mit JUnit-Tests; Links aus dem Inhalt nach `wissensseite_link`
  - _Anforderungen: 2.1–2.8, 3.5, 3.7_
  - _Abhängig von: 13.3_

- [x] 13.5 Sichtbarkeit und Rechte [Backend]
  - Hülle (`wissensseite_dokument`, rekursiv mit Zyklen) und Hülle je Version (`wissensseite_version_dokument`), wirksame Vertraulichkeit = max(Hülle, Mindeststufe), `WissenSichtbarkeit` auf Basis des `VertraulichkeitFilter` inkl. Mindeststufe
  - Aktionen A15–A19 in `Berechtigung`, 404 vor 403, A19 für `geschaeftsstelle` 403; Szenarien mit zwei Mandanten und allen vier Rollen; Seite von Hand auf `INTERN` → `mitarbeiter` erhält 404, auch nach Neuberechnung
  - _Anforderungen: 11.1–11.3, 11.5, 11.8, 11.9, 12.1–12.3_
  - _Abhängig von: 13.3_
  - Vermerk (PR #300): Die Neuberechnung bei Änderung eines Dokuments (11.1, 11.3) ist umgesetzt, wird aber erst mit 14.4 (`QuelldokumentBeobachter`) angebunden; der Befund `STUFE_UEBERBLICK` (11.8) liegt in `WissensPruefung` (13.4), die wirksame Stufe nach Annahme in `WissensGraph.wirksameStufeNachAnnahme` (13.5), der Aufruf bei jedem Annehmen kommt mit 14.2. Eine Übersicht erhält bei einer Dokumentänderung die wirksame Stufe (11.9, entschieden von Fabian am 18.11.2026). Der Endpunkt-Nachweis zu 12.2 folgt mit 14.2 am ersten schreibenden A17-Endpunkt (`POST /wissensentwuerfe`); ein Leseendpunkt aus 13.6 kann die schreibende Sperre für `treuhaender` nicht prüfen. Der Endpunkt-Nachweis zu 12.3 folgt mit dem Entwurfszugriff in 14.x.
  - Ergänzung 13.5b (PR #318, Req 11.10; erledigt, Auftrag Copilot-Runde 27 «Mindeststufe Zitate»): Mindeststufen zitierter Quellseiten rekursiv und monoton übernehmen, Zyklen und Tiefengrenze absichern; abhängige Seiten bei Freigabe unter Mandantensperre neu berechnen, historische Versionen unverändert lassen. Nachweis (Req 11.1, 11.8, 11.10): Szenario `@req-11.10` in `sichtbarkeit-rechte.feature`, `WissensGraphTest` (Zyklus, Tiefe 128/129, Nicht-Senken), `WissenHuelleDienstTest`.

- [x] 13.6 Lesen: Index, Seite, Versionen, Log [Backend]
  - REST `GET /wissensseiten…`, `/wissensbasis/log` nach design.md mit ETag; Index nach Typ gruppiert, Log aus Feldern erzeugt und gefiltert; eingehende Links inkl. Quelleneinträgen auf die Wissensquelle einer Quelle-Seite; Versionen und Diff mit Sichtbarkeit je Version (Szenario: frühere `INTERN`-Version bleibt für `mitarbeiter` 404)
  - Zugriffsprotokoll `WISSEN_ANZEIGEN` und `VERWEIGERT`, ohne Inhalte
  - Sichtbarkeit als SQL-Prädikat an den Abfragen von `wissensseite` (Zähler und Paging verraten nichts); ersetzt den Java-Filter aus 13.5 (design.md, «Umsetzung 13.5»), inkl. fail-closed bei Quellen ohne gespeicherte Hülle und bei live erreichbaren Dokumenten ausserhalb der gespeicherten Hülle (Review PR #300, ebc9d0b6, S2)
  - Wissens-Endpunkte nur für OIDC-Benutzer; API-Tokens (auch Webseiten-Schlüssel mit Rolle `geschaeftsstelle`) erhalten A15–A19 nicht über die Rolle (Review PR #300, ebc9d0b6, K4); Nachweis: Szenarien `@req-12.1` «API-Tokens …» und «Webseiten-Schlüssel erreichen die Wissensbasis nicht» (`sichtbarkeit-rechte.feature`) und `EndpunktWaechterTest` (Review PR #311)
  - Szenario `@req-12.2` über einen Endpunkt: `treuhaender` erhält 403, Datenbank und Log bleiben unverändert (Review PR #300, S4) → verschoben auf 14.2 (`POST /wissensentwuerfe`, Vermerk bei 13.5; Review PR #311, M2)
  - _Anforderungen: 3.1–3.4, 3.6, 3.8, 4.4, 4.5, 14.2, 14.4–14.6_
  - _Abhängig von: 13.4, 13.5_

## Welle 14 — Ingest und Freigabe

- [x] 14.1 Ingest-Aufträge [Backend]
  - Port `DokumentEreignisse` nach design.md (Ergänzung zu Spec 01, in derselben Transaktion); Auftrag `WISSEN_INGEST` nach `ARCHIVIERT` und über `POST /dokumente/{id}/wissen-ingest` (Seitenbereich); höchstens ein Ingest je Mandant `IN_ARBEIT`/`OFFEN`, weitere `WARTEND`; Vorprüfung (Art, `PERSONAL`, Volltext, PDF/A und `\f`-Seiten, laufender oder wartender Ingest, Schätzung mit Ein- und Ausgabe, Monatsbudget der Wissensbasis)
  - Teilaufträge `KERNAUSSAGEN`, `PLANUNG`, `SEITE`, `QUELLE` mit je einem Aufruf über `KiPort` und JSON-Schema, `ABSCHLUSS` ohne Aufruf; Auszugsprüfung; Ingest-Kontext nur bis zur Stufe der Wissensquelle, `ueberblick` nur bei `NORMAL`; die Stufe für 5.5 und 11.7 live über `WissensGraph` und die aktuelle Dokumentstufe, nicht aus `wissensseite.vertraulichkeit` (design.md, «Sichtbarkeit und Hülle»); belegter Pfad höherer Stufe → Befund und Hinweis; Kontextstufe je Änderung; Eingrenzung grosser Indizes über die Volltextsuche (Vektor folgt in 15.1)
  - `archiv.wissen.*` (Modell über `archiv.ki.basis-url`, kein eigener URL-Schlüssel); `ki_verbrauch` mit neuen Zwecken; Zeitgrenzen der neuen Auftragstypen
  - Szenarien mit WireMock je Schritt (gültig, ungültig mit Nachfrage je Schritt, Zeitüberschreitung, Budget erreicht, Monatsbudget erschöpft, zwei Wissensquellen nacheinander); Konfigurationstests nach 13.1 und 13.2 (JUnit, Nachweis); Startwerte `budget-tokens` 600'000 (Entscheid Fabian, 18.11.2026) und `monatsbudget-tokens` 1'000'000 (Test über die Konfiguration); Profil `lokal` zusätzlich von Hand mit LM Studio (kein Nachweis); Stufe A nur mit Testdaten
  - Prompt-Injection-PDF: Ergebnis nur als Entwurf mit Befunden
  - Teil erledigt (PR #307, 14.1a): Port `DokumentEreignisse` in der Archivierungstransaktion, `POST /dokumente/{id}/wissen-ingest` mit Seitenbereich (A16, 404 vor 403), Vorprüfung, Schätzung, Monatsbudget (Kalendermonat Zürich), Reihenfolge `WARTEND`/`IN_ARBEIT` (neuer Start `WARTEND`, solange ein Ingest-Entwurf `WARTEND`, `IN_ARBEIT` oder `OFFEN` ist), Changeset `047-wissen-ingest-indizes`, Szenarien `features/wissen/ingest.feature`, `JdbcWissenIngestTest` (design.md, «Zwischenstand 14.1a»). Entscheid Fabian vom 18.11.2026 zur Schätzformel gegen `budget-tokens`: Startwert 600'000 in PR #307 umgesetzt.
  - Teil erledigt (PR #319, 14.1b): Teilaufträge mit `KiPort` und JSON-Schema, WireMock-Szenarien, Auszugs- und Kontextstufenprüfung, Wiederaufnahme alter `HANDLER_FEHLT`-Entwürfe, budgetgeprüfte Aktivierung der ältesten wartenden Ingests, Tests in `JdbcWissenIngestTest` und `WissenIngestStartenTest`; siehe design.md, «Umsetzung 14.1b».
  - Zeitüberschreitung (Review PR #319, S3): Der Adapter liefert `WissensAusfall` (`LangChain4jKiAdapterWissenTimeoutTest`, `start-4`); der Ingest wertet das als Fehlversuch (`WissenIngestVerarbeitenTest`), nach dem dritten Versuch schliesst `WissenIngestHandler.nachLetztemFehlversuch` den Entwurf mit `FEHLER` «nach drei Versuchen» ab; kein Szenario, weil drei echte Zeitüberschreitungen mit Backoff den Szenarienlauf um Minuten verlängern würden
  - _Anforderungen: 5.1–5.13, 11.6, 11.7, 13.1–13.8, 14.3_
  - _Abhängig von: 13.2, 13.4, 13.5_

- [ ] 14.2 Entwurf, Diff und Freigabe [Backend]
  - `DiffDienst` (java-diff-utils, Felddiff), `FreigabeDienst` mit `If-Match`, 412/428, `WissensPruefung` bei jedem Annehmen, Befund 422, Vertraulichkeit nicht senkbar und nur bis `INTERN` erhöhbar (Mindeststufe), kein gesammeltes Annehmen, «neu erzeugen»; Kernaussagen im Ingest-Entwurf
  - Endpunkt-Szenario `@req-12.2`: `treuhaender` erhält am `POST /wissensentwuerfe` HTTP 403; Datenbank und Zugriffsprotokoll bleiben unverändert
  - Transaktion: Seite, Version, Hülle der Version, Quelleneinträge, Links, Hüllen, Log, Zugriffsprotokoll `WISSEN_ANNEHMEN`/`WISSEN_VERWERFEN`, Auftrag `WISSEN_EMBEDDING` (nur einreihen; Handler in 15.1, bis dahin bleibt der Auftrag wartend, der Test prüft nur das Einreihen); Start des nächsten wartenden Ingest
  - manueller Entwurf `POST /wissensentwuerfe`; Felder nach 7.6 nur vom System
  - wirksame Stufe nach Annahme über `WissensGraph.wirksameStufeNachAnnahme` als `Kontext.wirksameVertraulichkeit`, Seitenstufe = Mindeststufe; zuerst `WissenHuelleDienst.sperren()`, dann Seite und Version, dann `versionFreigeben` in derselben Transaktion; Test mit zwei parallelen Freigaben (Review PR #300, S1, S2)
  - Szenario `@req-12.3` über den Entwurfszugriff: eigener Analysevorschlag ohne Fassung und Diff (Review PR #300, S4)
  - Szenario `@req-11.8` Ende zu Ende: Annehmen einer Fassung von `ueberblick`, deren wirksame Stufe über `NORMAL` liegt, ergibt 422 mit dem Befund `STUFE_UEBERBLICK` (Review PR #300, ebc9d0b6, K3); Mindeststufen zitierter Quellseiten fliessen in die wirksame Stufe ein (geklärt durch Req 11.10 / 13.5b, PR #318, Entscheid vom 18.11.2026); der Endpunkt-Test prüft 422 `STUFE_UEBERBLICK` auch mit einer Zitat-Quelle höherer Stufe
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
  - Vermerk: Lint, Chat-Werkzeug und Löschfunktion entstehen erst in 15.4, 15.2 bzw. Spec 01, 9.9; dort sind die
    Ausnahmen für `PERSONAL`, der Prüfhinweis und die Ereignismeldung beim Löschen nachzuweisen.
  - _Anforderungen: 11.3, 15.1–15.5_
  - _Abhängig von: 13.5, 14.1 (Port `DokumentEreignisse`)_

- [x] 14.5 Oberfläche Wissensbasis [Frontend]
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
  - Szenarien mit simulierten Tool-Calls: nur freigegebene Fassungen, `mitarbeiter` erhält keine Seite mit unsichtbarer Hülle; `OeffentlicheWerkzeuge` (Spec 03) ohne Wissenswerkzeuge
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
  - Auftrag `WISSEN_EXPORT`, ZIP mit Frontmatter (SnakeYAML), `index.md`, `log.md`, URN-Verweise, Links im Inhalt auf `.md` umgeschrieben; Download 24 h; Protokoll mit SHA-256; Einstellungen `/wissensbasis/einstellungen` (A19, `geschaeftsstelle` erhält 403)
  - `OkfKonformitaetTest` (JUnit, Nachweis für 17.3, im PR verlinkt); Vollexport nach Spec 03, 8.2 (Aufgabe 12.2) um den OKF-Export ergänzen
  - _Anforderungen: 17.1–17.5, 14.3_
  - _Abhängig von: 13.6_

- [ ] 15.6 Import eines Wissenspakets als Entwurf [Backend]
  - `WissensPaketLeser` und `PaketKopf`/`PaketSeite` in `wissen/domain/paket` (nur im Speicher lesen; Grenzen, Pfadnormalisierung, Symlinks, Zip-Bombe über gezählte Bytes; Frontmatter über den YAML-Ereignisstrom ohne Tags, Anker, Aliase; Konfiguration `archiv.wissen.import.*` und `archiv.wissen.instanz-id`); ArchUnit-Regel `wissenDomainOhneFramework`
  - `WissensImport` und `QuellenAufloeser` in `wissen/application` (Zuordnung per SHA-256 über den Lesezugriff auf `dokument/application`, nur Art `WISSENSQUELLE`, `ARCHIVIERT`, sichtbar; Ergebnis `UNBEKANNT` für unbekannt, andere Art, unsichtbar, fremder Mandant); neue Befunde `QUELLE_UNBEKANNT`, `AUSZUG_FEHLT`, `AUSZUG_NICHT_PRUEFBAR`, `CLOUD_STUFE_UNZULAESSIG` und Ergebnistyp `NICHT_PRUEFBAR` in `WissensPruefung`; Auszugsprüfung mit `Seitentext` wie beim Ingest
  - REST `POST /wissensentwuerfe/import` (multipart, 201/400/403/409/413/415/422) und `POST /wissensentwuerfe/{id}/neu-pruefen` (A17, nur OIDC); Anlass `IMPORT`, Spalten `paket_sha256` (eindeutig) und `paket_angaben`, Changeset; Akteur `extern:<erzeugt_von>`; Zugriffsprotokoll `WISSEN_IMPORT`; Logeintrag `IMPORT` beim Abschluss; «neu erzeugen» für `IMPORT` nicht angeboten
  - Fehlercode `PAKET_UNGUELTIG` (422) in Enum `Fehlercode` und in die Tabelle von Spec 01, design.md (FehlercodeSpecTest); Schlüssel in `tech.md`; OKF-Export (15.5) um `paket.yaml`, `sha256`, `seite`, `auszug`, `erzeugt_von`, `basis_version` ergänzen (Rundlauf-Test: Export einer lokalen Instanz besteht den Import-Leser)
  - Beispielpakete unter `backend/src/test/resources/wissen-pakete/` (gültig, je Befund, je Angriff nach design.md, «Sicherheit und Bedrohungsmodell») und `PaketRegelnGleichheitTest`; Szenarien `@req-19.x` unter `features/wissen/`; Isolationsszenario mit zweitem Mandanten (fremde Prüfsumme); Logs nur mit IDs (Test über Log-Ausgabe)
  - Abgrenzung: kein KI-Aufruf, keine Oberfläche (15.8), kein Prüfwerkzeug (15.7); offener Punkt 19-a (Ingest bei Quellen nur für ein Paket) ist hier nur umzusetzen, wenn Fabian ihn entschieden hat
  - _Anforderungen: 19.1–19.23, 19.26, 14.3, 17.2_
  - _Abhängig von: 14.2 (Entwurf, Diff, Freigabe, manueller Entwurf), 15.5 (OKF-Export, Paketformat)_

- [ ] 15.7 Prüfwerkzeug `wissen_paket` [Backend]
  - `tools/wissen_paket` (Java 25, picocli, Shade-Jar, SpotBugs wie `tools/drive_import`), Kommandos `validate` und `bauen`, Option `--quellen`; bindet den Quelltext von `wissen/domain` und `gemeinsam/domain/Seitentext*` per `build-helper-maven-plugin` ein (design.md, «Prüfwerkzeug»); `OfflineKontext`; Bericht nur mit Befund-Codes und Pfaden (`--json`), Rückgabewert 0/1; reproduzierbares ZIP; kein Netz, keine Zugangsdaten
  - CI: eigener Schritt für `tools/wissen_paket` und Pfadfilter für Änderungen an `wissen/domain`; `README.md` des Werkzeugs
  - `PaketRegelnGleichheitTest` (JUnit, Nachweis für 19.27): dieselben Beispielpakete wie 15.6 ergeben dieselben Befund-Listen; Test, dass das Werkzeug keine Netzverbindung aufbaut und keine Datei ausser dem Ausgabe-ZIP schreibt
  - Abgrenzung: nur das Paketformat und die Domain-Regeln aus 13.4/15.6 werden gebraucht, nicht der Endpunkt; Aufgabe 15.6 und 15.7 dürfen parallel laufen, sobald `WissensPaketLeser` steht (Klasse aus 15.6, zuerst mergen)
  - _Anforderungen: 19.1–19.4, 19.6–19.8, 19.15, 19.25, 19.27_
  - _Abhängig von: 13.4 (`WissensPruefung`), Paketformat aus 15.6 (`WissensPaketLeser`)_

- [ ] 15.8 Oberfläche Import [Frontend]
  - Route `/wissen/entwuerfe/import` (Upload, Prüfbericht mit Zählern und Befunden je Seite, Link zum Entwurf); Entwurfsansicht für Anlass `IMPORT` mit Herkunft, Quellenzuordnung und Auszugsprüfung je Quelle, Hinweis zur Regel für Cloud-Modelle, Aktion «Prüfung wiederholen»; kein gesammeltes Annehmen
  - 375 px, WCAG 2.1 AA (axe), Texte nur in `texte.de-CH.ts`; Szenarien mit Cucumber.js + Playwright; `mitarbeiter` sieht die Route nicht
  - _Anforderungen: 19.28, 16.3, 16.4, 16.7_
  - _Abhängig von: 14.6 (Oberfläche Entwürfe), 15.6 (Endpunkt)_

## Welle 16 — Abnahme Phase 4

- [ ] 16.1 Sicherheits- und Isolationstests [QA]
  - Szenario `@req-18.5` mit zwei Mandanten und allen Rollen über Index, Seiten, Suche, Log, Entwürfe, Chat-Werkzeuge und Export; `MandantenIsolationIT` (Spec 03) um die Wissensbasis erweitern
  - Prompt-Injection-Prüfung aus 14.1 über alle Wege wiederholen, auch über Pakete (Import, 15.6); Befunde beheben
  - Import in die Isolation einbeziehen (`@req-19.29`): zwei Mandanten, alle Rollen und API-Token; Paket mit der SHA-256 eines Dokuments des anderen Mandanten, Zip-Slip, Symlink, Zip-Bombe, YAML mit Tag und Alias, Rohhtml, externer Link und `cloud` mit `INTERN` je abgelehnt bzw. mit Befund (Beispielpakete aus 15.6)
  - _Anforderungen: 11.5, 11.6, 18.5, 19.29_
  - _Abhängig von: 15.1–15.8_

- [ ] 16.2 Prüfliste und Gate [Fabian] [QA]
  - `wissen-pruefliste.yaml` (Wissensquellen, Fussnoten-Stichprobe, Fragen mit erwarteten Seiten und Quelldokumenten) nur unter `ARCHIV_GOLDEN_DIR`; im Repo nur Schema und Test `WissenPrueflisteIT`
  - Lauf manuell auf Stufe B, nie in der CI; Bericht `docs/abnahme/phase4.md` nur mit Nummern und Ergebnissen, Kennzahl «unverändert angenommen»
  - **Gate Phase 4:** Stichprobe 5 Wissensquellen; Belegtreue mindestens 47 von 50 Fussnoten, kein stilles Überschreiben, mindestens 14 von 15 Fragen korrekt mit Seite und Quelldokument beantwortet (18.1–18.4); Isolationstest grün
  - _Anforderungen: 18.1–18.4, 18.6, 18.7_
  - _Abhängig von: 16.1_

## Optionen
Optionale Aufgaben führen die Nummern von Spec 02 (`O.1`–`O.3`) fort. O.5 ist seit dem 18.11.2026 in den Plan übernommen (15.6 bis 15.8).

- [ ]* O.4 Wissensseiten für Verbandsmitglieder VHF/GSK [Backend]
  - öffentliche Schnittstelle und Chatbot (Spec 03) um freigegebene Seiten mit Hülle nur aus veröffentlichten `NORMAL`-Dokumenten erweitern; ändert Spec 03, 6.1
  - Spec 03 ergänzt (Spec 03, 3.7; 13.11.2026): Die Zugriffsstufe einer Seite wäre die höchste Stufe ihrer Hülle (drei Stufen); die Erweiterung wäre additiv im Vertrag `public-v1` (Spec 03, 11.3)
- [ ]* O.5 Import eines OKF-Bundles als Entwurf [Backend] — **übernommen in 15.6 bis 15.8 (Entscheid Fabian 18.11.2026, Requirement 19)**; nicht als Option umsetzen
  - z. B. ein bestehendes LLM-Wiki; Quellen ohne Archivdokument werden als Befund `QUELLE_UNBEKANNT` markiert (Zuordnung über SHA-256, 19.13, 19.14)
- [ ]* O.6 Wissenswerkzeuge im MCP-Server (O.3) [Backend] — **entfällt (folgt aus Spec 02, O.3, Entscheid 18.11.2026)** (nicht umsetzen)
