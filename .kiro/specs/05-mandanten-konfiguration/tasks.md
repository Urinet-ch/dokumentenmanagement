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
- [x] 17.1 Entscheide und Folgeänderungen [Fabian]
  - Verfahren der KI-Abnahme ohne Golden-Datensatz (Req 8.9): Stichprobe mit der Geschäftsstelle (Entscheid Fabian 17.11.2026, umgesetzt in Req 8.9 und design.md «KI»).
  - Folgeänderungen in Spec 01–04 und Steering nach design.md «Folgeänderungen» als eigener PR: umgesetzt mit PR #65.
  - _Anforderungen: 8.9_

- [x] 17.2 Konfigurationsversionen und Grundgerüst [Backend]
  - Modul `konfiguration/`, Changesets `<nnn>-konfiguration-version` und `<nnn>-protokoll-konfiguration`,
    `Konfiguration` und `pruefen()` (Gerüst), Port `MandantEinstellungen`, Cache nach Version, Protokoll
    `KONFIGURATION_AENDERN` neben `REGEL_AENDERN`, `If-Match` mit der Konfigurationsversion (auch für
    `/dokumentarten`), Version am Dokument und in der Dokumentversion, `GET /konfiguration/versionen[/{n}]`,
    Append-only-Test.
    Nachverfolgung (PR #290): Req 1.3 maskiert `uid`/`ibans` im Protokoll (design.md «Protokoll»); Nachweis:
    `ProtokollDetailsTest`, `KonfigurationTest`, Szenarien `@req-1.3` in `nachverfolgung.feature` (auch über
    die Verwaltung), `KonfigurationMigrationTest` (Changeset 037), `DokumentartSeedIdempotenzTest` (Seed-Abbruch).
  - _Anforderungen: 1.1, 1.2, 1.3, 1.4, 1.5_
  - _Abhängig von: Spec 01: 3.5, 4.7_

- [x] 17.3 Dokumentarten erweitern und Migration [Backend]
  - Changeset `<nnn>-dokumentart-konfiguration`, Domain und API der neuen Attribute, `belegnummer_eigen` über
    `selbst_ausgestellt`, `eigene_arten` nicht mehr lesen, Muster und Umstufungs-Arten in die Arten von `urinet`
    (Changeset, Version mit Quelle `MIGRATION`), Ablehnung ungültiger Muster, feste Eigenschaften von
    `WISSENSQUELLE` (sobald Spec 04: 13.2 umgesetzt ist, vorher mit Test-Art), reservierte Felder.
  - Spec 03 ergänzt: reservierte Felder nach Spec 03 design.md «Reservierte Felder» (`zugriff` mit drei Stufen,
    `veroeffentlicht` als JSON-Wahrheitswert) (Spec 03, Req 3.7)
    Nachverfolgung (PR #290): Standard `vorstand` für jede Art, `oeffentlich` abgelehnt; Nachweis: Szenarien
    `@req-2.7` in `nachverfolgung.feature`.
  - _Anforderungen: 2.1, 2.2, 2.6, 2.7, 2.8_
  - _Abhängig von: 17.2_

- [x] 17.4 Fristbezüge `BELEGDATUM` und `FELD` [Backend]
  - `AufbewahrungAb`, `Fristbezug`, Prüfung des Fristfelds, leeres Feld, Neuberechnung bei Änderung des Bezugsdatums.
  - _Anforderungen: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_
  - _Abhängig von: 17.3_

- [x] 17.5 Klassifikationen [Backend]
  - Changeset `<nnn>-klassifikation`, Domain, REST, Zuordnung zu Arten, Prüfung an Dokumenten, Artwechsel,
    Versionierung, reservierte Parameternamen der öffentlichen Schnittstelle, Kennzeichnung ungültiger Werte im
    Eingangskorb nach einer Konfigurationsänderung.
  - Spec 03 ergänzt: reservierte Parameternamen zusätzlich `seit`, `max`, `referenz`, `groesse`, `status`, `url`
    (Spec 03, Req 5.10, 10.3, 10.13, 14.3)
  - _Anforderungen: 1.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 8.6_
  - _Abhängig von: 17.3; Spec 01: 4.7_

- [x] 17.6 Regeln und Lückenbericht [Backend]
  - Übergangsquelle in Spec 02, 7.1c (Port `Lueckenbericht`, Adapter `KonfigurierteLuecken` aus der Konfiguration); 17.6 ersetzt den Adapter hinter demselben Port. Das Werkzeug `vollstaendigkeit` behält Name, Parameter und Antwortform; neu sind Reihen je Geschäftsjahr (`ERWARTET` mit `je` `JAHR`) und die Gruppierung `gruppe` aus den Regeln (design.md «Regeltypen»)
  - Changeset `<nnn>-regel`, Regeltypen, `RegelPruefung` in Bestätigen, Ändern, Generator und Drive-Import,
    bestehende Verletzungen als Warnung, `Lueckenbericht` mit Recht nach 5.4 (im Chat), Regeln von `urinet` per
    Changeset (Version mit Quelle `MIGRATION`) und bei der Provisionierung.
  - Aufgeteilt in der Nachbesserung von PR #304: Die Meldung betroffener Dokumente (5.6) und die REST-Endpunkte
    folgen in 17.8. 5.4 gilt in 17.6 nur im Chat (Werkzeug `vollstaendigkeit`); HTTP 403 für
    `GET /berichte/luecken` folgt in 17.8.
  - _Anforderungen: 5.1, 5.2, 5.3, 5.4, 5.5, 5.7_
  - _Abhängig von: 17.5; Spec 01: 3.4, 3.5, 4.7, 4.8_

- [x] 17.8 Regeln über die REST-Schnittstelle und betroffene Dokumente [Backend]
  - Aus 17.6 abgetrennt (Nachbesserung PR #304): Endpunkte `/regeln` (Anlegen, Ändern, Aktivieren mit neuer
    Konfigurationsversion) mit `betroffeneDokumente` beim Anlegen, Verschärfen oder Aktivieren (5.6) und
    `GET /berichte/luecken` mit HTTP 403 für Rollen ohne uneingeschränkte Sicht (5.4); OpenAPI im selben PR.
  - _Anforderungen: 5.4, 5.6_
  - _Abhängig von: 17.6_

- [x] 17.7 Konfigurationsdatei, Admin-Kommando und Seeds [Backend]
  - Schema-Datei, Export, Prüfen, Vergleichen, Einspielen (ganz oder gar nicht, Basisversion, Rundlauf),
    Admin-Kommando mit `--wer` und `--grund`, `konfiguration.json` als Seed (ersetzt `dokumentarten.json`, auch
    die Test-Seeds), `KonfigurationSeedTest` ersetzt `DokumentartSeedTest` (`StammdatenTabelle` und Spalten der
    Stammdaten-Tabelle in Spec 01 design.md im selben PR), Endpunkte der KI-Texte, `If-Match` für alle Elemente,
    Umfang A11 und Lesen für alle Rollen, Migrationstest mit Schema im Stand vor Spec 05.
  - `DELETE /regeln/{code}` (Req 7.1 «löschen»; in 17.8 offen geblieben, Review PR #317) mit neuer
    Konfigurationsversion, `If-Match` und Recht A11.
  - _Anforderungen: 1.6, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 7.1, 7.2, 7.6, 9.1, 9.2_
  - _Abhängig von: 17.4, 17.6_

## Welle 18 — Wirkung und Oberfläche
- [x] 18.1 KI aus der Konfiguration [Backend]
  - Prompt und Antwortschema aus Arten, `ki_felder` und Klassifikationen, KI-Texte, Nachbearbeitung über
    Art-Attribute, Belegnummer-Muster aus den Arten mit Zeitgrenze zur Laufzeit (inkl. ReDoS-Test mit `(a+)+$` gegen langen
    Eingabetext: gilt als «trifft nicht zu» und landet im Protokoll; 17.3 prüft nur Syntax und Länge), Schalter `ki_freigegeben` und
    Admin-Kommando `ki-freigeben`, Snapshot-Test gegen den Stand von Spec 01, Aufgabe 3.2, Quelltext-Test ohne
    Art-Codes.
  - Offen für Fabian: Probelauf-Betriebsart (design.md, Open Points); Auslegung der Gate-0-Darstellung der
    Prompt-Kennung beim Basisschema und der Reihenfolge der Arten (Spec 01 design.md). Der Quelltext-Test deckt
    ganz `src/main/java` ab, ausser `DokumentErfassen.ART_KORRESPONDENZ` (Mail-Eingang, Spec 02): Ableitung aus der
    Konfiguration braucht ein neues Art-Attribut und ist offen.
  - _Anforderungen: 2.3, 2.4, 2.5, 2.9, 8.1, 8.2, 8.3, 8.4_
  - _Abhängig von: 17.7; Spec 01: 3.2_

- [ ] 18.2 Suche, Generator, Import und Funktionen [Backend]
  - Vermerk (Spec 02, Aufgabe 7.1b, 14.11.2026): `Funktionen.finanzVerfuegbar()` besteht minimal in `dokumentart.application` (mindestens eine aktive Art mit der Gruppe `finanz`) und trägt `kennzahl`/`offene_posten`; 18.2 verschiebt es nach `konfiguration`, ergänzt `GET /mandant/funktionen` und löst die Übergangskonfiguration `archiv.mandant.<code>.kennzahl.*-arten` ab
  - Klassifikationsfilter, Facetten und Volltext, Klassifikationen im Generator und Drive-Import,
    `GET /mandant/funktionen`.
  - _Anforderungen: 8.5, 8.7, 8.8_
  - _Abhängig von: 17.7; Spec 01: 4.5, 4.7, 4.8_

- [x] 18.3 Verwaltungsseiten [Frontend]
  - Seiten für Dokumentarten (Platzhalter `/verwaltung/dokumentarten`) mit Feld-Editor, Klassifikationen, Regeln,
    Versionen mit Vergleich, Import mit Vorschau. Spec 06 ergänzt die Seiten `/verwaltung/einstellungen` und
    `/verwaltung/benutzer` (Aufgaben 20.5 bis 20.7); die Platzhalter hier bleiben.
  - _Anforderungen: 7.3, 7.4_
  - _Abhängig von: 17.7_

- [x] 18.4 Felder und Klassifikationen je Art im Eingangskorb und in der Detailansicht [Frontend]
  - Darstellung aus Zusatz-Schema und Klassifikationen der Art, Wechsel der Art, Warnungen aus Regeln, ungültige
    Werte nach Konfigurationsänderung.
  - _Anforderungen: 7.5_
  - _Abhängig von: 17.7; Spec 01: 3.6, 4.6_

- [ ] 18.5 Isolation und Abnahme [QA]
  - `MandantenIsolationIT` anlegen oder erweitern, Beispiel-Mandant `probe-verband` durchgängig.
  - _Anforderungen: 9.3, 9.4_
  - _Abhängig von: 18.1, 18.2, 18.3, 18.4_
