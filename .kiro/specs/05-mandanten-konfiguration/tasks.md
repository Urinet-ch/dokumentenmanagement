# Implementation Plan — 05 Konfiguration je Mandant

Voraussetzungen: Spec 05 auf `main` gemergt, Entscheide aus Aufgabe 17.1, Spec 01 bis einschliesslich Aufgabe 3.5
umgesetzt (Bestätigen und Ändern sind die Prüfpunkte der Regeln). Die Wellen 17 und 18 folgen auf die Nummern von
Spec 04, umgesetzt werden sie nach Gate Phase 1 und spätestens vor Welle 10 (entschieden von Fabian am
11.11.2026). Wellen, Rollen und
Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach `CLAUDE.md`, Branch
`feature/05-<aufgabe>-<stichwort>`. Steht ein Wert noch auf «[offen: …]», wird er nicht geraten; die Aufgabe wartet
auf den Entscheid. Abhängigkeiten zu anderen Specs stehen als `Spec 0n: <Aufgabe>`. Jede Aufgabe liefert für ihre
Anforderungen Cucumber-Szenarien mit `@req-<x.y>` und `@spec-05` unter `features/konfiguration/` (ausser bei
Nachweis-Vermerk) und erfüllt die Definition of Done aus `tech.md`. Führt eine Aufgabe einen Fehlercode ein oder
erweitert sie A11, trägt sie das im selben PR in Spec 01 nach (design.md, «Error Handling»; requirements.md 2.6 und
2.17), sonst werden `FehlercodeSpecTest` bzw. `RechtematrixSpecTest` rot.

## Welle 17 — Modell, Prüfung, Datei
- [ ] 17.1 Entscheide und Folgeänderungen [Fabian]
  - Verfahren der KI-Abnahme ohne Golden-Datensatz (8.9, spätestens vor Welle 10).
  - Folgeänderungen in Spec 01–04 und Steering nach design.md «Folgeänderungen» als eigener PR.
  - _Anforderungen: 8.9_

- [ ] 17.2 Konfigurationsversionen und Grundgerüst [Backend]
  - Modul `konfiguration/`, Changesets `<nnn>-konfiguration-version` und `<nnn>-protokoll-konfiguration`,
    `Konfiguration` und `pruefen()` (Gerüst), Port `MandantEinstellungen`, Cache nach Version, Protokoll
    `KONFIGURATION_AENDERN` neben `REGEL_AENDERN`, `If-Match` mit der Konfigurationsversion (auch für
    `/dokumentarten`), Version am Dokument und in der Dokumentversion, `GET /konfiguration/versionen[/{n}]`,
    Append-only-Test.
  - _Anforderungen: 1.1, 1.2, 1.3, 1.4, 1.5_
  - _Abhängig von: Spec 01: 3.5, 4.7_

- [ ] 17.3 Dokumentarten erweitern und Migration [Backend]
  - Changeset `<nnn>-dokumentart-konfiguration`, Domain und API der neuen Attribute, `belegnummer_eigen` über
    `selbst_ausgestellt`, `eigene_arten` nicht mehr lesen, Muster und Umstufungs-Arten in die Arten von `urinet`
    (Changeset, Version mit Quelle `MIGRATION`), Ablehnung ungültiger Muster, feste Eigenschaften von
    `WISSENSQUELLE` (sobald Spec 04: 13.2 umgesetzt ist, vorher mit Test-Art), reservierte Felder.
  - _Anforderungen: 2.1, 2.2, 2.6, 2.7, 2.8_
  - _Abhängig von: 17.2_

- [ ] 17.4 Fristbezüge `BELEGDATUM` und `FELD` [Backend]
  - `AufbewahrungAb`, `Fristbezug`, Prüfung des Fristfelds, leeres Feld, Neuberechnung bei Änderung des Bezugsdatums.
  - _Anforderungen: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_
  - _Abhängig von: 17.3_

- [ ] 17.5 Klassifikationen [Backend]
  - Changeset `<nnn>-klassifikation`, Domain, REST, Zuordnung zu Arten, Prüfung an Dokumenten, Artwechsel,
    Versionierung, reservierte Parameternamen der öffentlichen Schnittstelle, Kennzeichnung ungültiger Werte im
    Eingangskorb nach einer Konfigurationsänderung.
  - _Anforderungen: 1.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 8.6_
  - _Abhängig von: 17.3; Spec 01: 4.7_

- [ ] 17.6 Regeln und Lückenbericht [Backend]
  - Changeset `<nnn>-regel`, Regeltypen, `RegelPruefung` in Bestätigen, Ändern, Generator und Drive-Import,
    bestehende Verletzungen als Warnung, `Lueckenbericht` mit Recht nach 5.4, Meldung betroffener Dokumente,
    Regeln von `urinet` per Changeset (Version mit Quelle `MIGRATION`).
  - _Anforderungen: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_
  - _Abhängig von: 17.5; Spec 01: 3.4, 3.5, 4.7, 4.8_

- [ ] 17.7 Konfigurationsdatei, Admin-Kommando und Seeds [Backend]
  - Schema-Datei, Export, Prüfen, Vergleichen, Einspielen (ganz oder gar nicht, Basisversion, Rundlauf),
    Admin-Kommando mit `--wer` und `--grund`, `konfiguration.json` als Seed (ersetzt `dokumentarten.json`, auch
    die Test-Seeds), `KonfigurationSeedTest` ersetzt `DokumentartSeedTest` (`StammdatenTabelle` und Spalten der
    Stammdaten-Tabelle in Spec 01 design.md im selben PR), Endpunkte der KI-Texte, `If-Match` für alle Elemente,
    Umfang A11 und Lesen für alle Rollen, Migrationstest mit Schema im Stand vor Spec 05.
  - _Anforderungen: 1.6, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 7.1, 7.2, 7.6, 9.1, 9.2_
  - _Abhängig von: 17.4, 17.6_

## Welle 18 — Wirkung und Oberfläche
- [ ] 18.1 KI aus der Konfiguration [Backend]
  - Prompt und Antwortschema aus Arten, `ki_felder` und Klassifikationen, KI-Texte, Nachbearbeitung über
    Art-Attribute, Belegnummer-Muster aus den Arten mit Zeitgrenze zur Laufzeit, Schalter `ki_freigegeben` und
    Admin-Kommando `ki-freigeben`, Snapshot-Test gegen den Stand von Spec 01, Aufgabe 3.2, Quelltext-Test ohne
    Art-Codes.
  - _Anforderungen: 2.3, 2.4, 2.5, 2.9, 8.1, 8.2, 8.3, 8.4_
  - _Abhängig von: 17.7; Spec 01: 3.2_

- [ ] 18.2 Suche, Generator, Import und Funktionen [Backend]
  - Klassifikationsfilter, Facetten und Volltext, Klassifikationen im Generator und Drive-Import,
    `GET /mandant/funktionen`.
  - _Anforderungen: 8.5, 8.7, 8.8_
  - _Abhängig von: 17.7; Spec 01: 4.5, 4.7, 4.8_

- [ ] 18.3 Verwaltungsseiten [Frontend]
  - Seiten für Dokumentarten (Platzhalter `/verwaltung/dokumentarten`) mit Feld-Editor, Klassifikationen, Regeln,
    Versionen mit Vergleich, Import mit Vorschau.
  - _Anforderungen: 7.3, 7.4_
  - _Abhängig von: 17.7_

- [ ] 18.4 Felder und Klassifikationen je Art im Eingangskorb und in der Detailansicht [Frontend]
  - Darstellung aus Zusatz-Schema und Klassifikationen der Art, Wechsel der Art, Warnungen aus Regeln, ungültige
    Werte nach Konfigurationsänderung.
  - _Anforderungen: 7.5_
  - _Abhängig von: 17.7; Spec 01: 3.6, 4.6_

- [ ] 18.5 Isolation und Abnahme [QA]
  - `MandantenIsolationIT` anlegen oder erweitern, Beispiel-Mandant `probe-verband` durchgängig.
  - _Anforderungen: 9.3, 9.4_
  - _Abhängig von: 18.1, 18.2, 18.3, 18.4_
