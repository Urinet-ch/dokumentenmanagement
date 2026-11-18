# Requirements Document — 04 Wissensbasis (Phase 4)

## Introduction
Diese Spec erweitert den Archiv-Kern (`01-archiv-kern`) und «Abgleich und Chat» (`02-abgleich-chat`) um eine
Wissensbasis je Mandant. Fachunterlagen, z. B. ein hochgeladenes PDF mit Richtlinien, einem Handbuch oder
einer Weisung, werden als normales Archivdokument der Dokumentart `WISSENSQUELLE` erfasst. Die KI zieht ihr
Wissen einmal heraus und arbeitet es als Entwurf in zusammenhängende, verlinkte Wissensseiten ein. Ein Mensch
mit Recht prüft den Entwurf als Diff und gibt ihn frei. Der Chat aus Spec 02 nutzt danach zuerst die
freigegebenen Seiten und erst bei Bedarf die Quelldokumente.

Vorbild ist das LLM-Wiki nach dem Open Knowledge Format (OKF) v0.2: Seiten mit festen Feldern (analog
Frontmatter), ein Index mit einer Zeile je Seite, ein Log, das nur anfügt, Belege je Aussage über Fussnoten
und die drei Abläufe Ingest, Query und Lint. Abweichend vom Vorbild schreibt die KI nie direkt: Jede
Änderung ist ein Entwurf, den ein Mensch freigibt (ADR-006, ADR-013). Gespeichert wird im Schema des
Mandanten, nicht in Dateien oder Git (ADR-013); OKF dient als Exportformat.

Wissen lässt sich auch ausserhalb des Archivs erarbeiten, z. B. auf dem MacBook mit einem lokalen Modell, und als
Wissenspaket (ZIP im OKF-Format) ins produktive Archiv übertragen (Requirement 19). Das Archiv nimmt das Paket
nur als Entwurf an, prüft es ohne KI und lässt jede Änderung von einem Menschen mit Recht A17 freigeben.

Voraussetzung: Gate Phase 2 und Gate Phase 3 erfüllt (Volltext, Hybridsuche, Chat-Agent und Quellen aus Spec 02). Umgesetzt wird
Spec 04 nach Spec 03 (entschieden von Fabian am 09.11.2026); damit ist der Mandant `vhf-gsk` nach Spec 03, Requirement 1 angelegt. Spec 01 bleibt im Umfang
unverändert; wo diese Spec Verhalten aus Spec 01, 02 oder 03 erweitert, steht das als «Spec 04 ergänzt …».

Grundlage ist der Auftrag von Fabian vom 09.11.2026, nicht das Konzept vom 08.11.2026; die Requirements
tragen deshalb keine Konzept-ID («ohne Konzept-ID», wie Spec 01, Requirement 16). Wo ein Requirement eine
Konzept-Anforderung erweitert, steht der Bezug in der Klammer.

Schreibweise der Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag `@req-<x.y>`, Ausnahmen mit
Vermerk «Nachweis: manuell [Fabian]», «Nachweis: Dokument» bzw. «Nachweis: JUnit»), Problem Details und
`[offen]`-Marker wie in Spec 01. Rollen und Sichtbarkeit richten sich nach der Rechtematrix in Spec 01,
Requirement 2.6, ergänzt um die Zeilen A15 bis A19 (Requirement 12).

## Glossary
- **Wissensbasis** – alle Wissensseiten, der Index und das Log eines Mandanten.
- **Wissensquelle** – Archivdokument der Dokumentart `WISSENSQUELLE`, aus dem Wissen in die Wissensbasis übernommen wird.
- **Wissensseite** (kurz Seite) – eine Einheit der Wissensbasis mit Feldern (analog OKF-Frontmatter) und Inhalt in Markdown; entspricht einem OKF-Concept.
- **Pfad** – stabile ID einer Seite in der Form `<ordner>/<name>` (z. B. `konzepte/spesenregelung`), entspricht der OKF-Concept-ID.
- **Seitentyp** – Art einer Seite (`Synthese`, `Quelle`, `Konzept`, `Akteur`, `Projekt`, `Analyse`).
- **Quelleneintrag** – Beleg einer Seite: Archivdokument mit PDF-Seite (und optional wörtlichem Auszug) oder eine andere Wissensseite, mit stabilem Schlüssel.
- **Fussnote** – Markdown-Fussnote `[^<schluessel>]` hinter einer Aussage; der Schlüssel verweist auf einen Quelleneintrag.
- **Hülle** – alle Archivdokumente, auf denen eine Seite direkt oder über verlinkte Quellseiten beruht; Grundlage der Sichtbarkeit.
- **Index** – Katalog der Wissensbasis: je Seite eine Zeile mit Titel, Pfad und Beschreibung, gruppiert nach Seitentyp.
- **Log** – Journal der Wissensbasis, nur anfügend, neueste Einträge zuerst.
- **Ingest** – Verarbeitung einer Wissensquelle zu einem Entwurf.
- **Lint** – Prüfung der Wissensbasis auf Widersprüche, Lücken und Veraltetes, mit Vorschlägen als Entwurf.
- **Entwurf** – Sammlung vorgeschlagener Änderungen (je Seite eine), die erst nach Freigabe wirksam werden.
- **Freigabe** – Annehmen einer Änderung durch einen Menschen mit Recht A17; erzeugt eine neue Seitenversion.
- **Widerspruchsblock** – markierter Abschnitt `> ⚠️ Widerspruch: …` mit den Quellen beider Aussagen.
- **Wissenspaket** (kurz Paket) – ZIP im OKF-Format mit Wissensseiten und der Datei `paket.yaml`, das ausserhalb des Archivs entsteht und nach Requirement 19 als Entwurf importiert wird.
- **Import** – Einlesen eines Wissenspakets als Entwurf mit Anlass `IMPORT`; kein KI-Aufruf, keine Freigabe.
- **Auszugsprüfung** – Vergleich des wörtlichen Auszugs eines Quelleneintrags mit dem Volltext der angegebenen PDF-Seite der zugeordneten Wissensquelle (Requirement 6.2, 19.15).
- **Prüfwerkzeug** – lokales Offline-Werkzeug `wissen_paket` (`tools/wissen_paket`), das ein Paket vor dem Upload mit denselben Regeln wie der Import prüft und baut (Requirement 19.27).
- **Cloud-Modell** – jedes Sprachmodell, das nicht auf dem eigenen Gerät läuft (z. B. Copilot, Claude); Gegenstück ist ein lokales Modell (z. B. LM Studio auf dem MacBook).
- **Dokumenttitel** – das Feld `dokument.titel` aus Spec 01 (Requirement 7.2). Die Präzisierungen zum Feld (aufgedruckter Titel, sonst KI-Vorschlag mit Herkunft «erzeugt», Anzeige in der Trefferliste) folgen in einem eigenen PR zu Spec 01; diese Spec nutzt nur das bestehende Feld und ändert es nicht.

---

### Requirement 1: Dokumentart Wissensquelle (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich Fachunterlagen wie jedes andere Dokument hochladen,
damit ihr Wissen ohne zweiten Erfassungsweg in die Wissensbasis kommt.

#### Acceptance Criteria
1. Spec 04 ergänzt die Dokumentarten (Spec 01, Requirement 9) in jedem Mandanten um die Art `WISSENSQUELLE` («Wissensquelle», Gruppe `frei`, Pflichtfelder `titel` und `datum`, Standard-Vertraulichkeit `NORMAL`, unbefristet, `gesetzliche_pflicht = nein`, Zusatzfeld `herausgeber` optional); das Archiv SOLL sie über einen eigenen, mandantenunabhängigen Seed-Teil dieser Spec anlegen, ohne den Seed von Spec 01 zu ändern. Spec 04 ergänzt Spec 01, 1.5 und Spec 03, 1.1: Beim Anlegen jedes Mandanten SOLL das Archiv diesen Seed-Teil mit einlesen. Im Mandanten `vhf-gsk` SOLL `WISSENSQUELLE` zusätzlich die Felder nach Spec 03, 1.2 tragen, mindestens die Zugriffsstufe (Pflicht, Standard `mitglieder` nach Spec 03, 3.3) und `veroeffentlicht` (Standard `false`, Spec 03, 3.4). Spec 05, 2.6 ergänzt dieses Kriterium: Die Ergänzung von `WISSENSQUELLE` für `vhf-gsk` kommt aus der Konfiguration von `vhf-gsk`, nicht aus dem mandantenunabhängigen Seed-Teil; der Seed-Teil legt die Art an, wenn der Code fehlt. Code, Gruppe `frei`, mindestens die Pflichtfelder `titel` und `datum`, «unbefristet», `gesetzliche_pflicht = nein` und `ki_vorschlag = nein` sind in jeder Konfiguration fest; Zusatzfelder (auch als Pflicht) und Klassifikationen darf ein Mandant ergänzen (umgesetzt in Spec 05, Aufgabe 17.3). Spec 03, 3.7 ergänzt dieses Kriterium (13.11.2026, entschieden von Fabian, E-C): Die Zugriffsstufe hat die Werte `oeffentlich`, `mitglieder` und `vorstand` (Definition in Spec 03 design.md, «Reservierte Felder»); der Standard `mitglieder` bleibt (umgesetzt in Spec 03, Aufgabe 10.2).
2. Das Archiv SOLL Dokumente der Art `WISSENSQUELLE` auf denselben Wegen erfassen und gleich verarbeiten wie andere Dokumente (Upload, Handy, E-Mail und Drive-Import nach Spec 01 und 02; Dublettenprüfung, OCR, PDF/A, KI-Vorschlag, Eingangskorb, Bestätigung mit Recht A7); bis zur Bestätigung gilt Spec 01, 2.21.
3. Weil `gesetzliche_pflicht = nein`, SOLL das Archiv beim Archivieren einer Wissensquelle keine Object-Lock-Sperre setzen (Spec 01, 6.12); Prüfsummen, Manifest und Unveränderbarkeit der Dateien (Spec 01, 6.5) SOLLEN trotzdem gelten.
4. Die Pflege der Wissensbasis (Ingest, Freigabe, Lint) SOLL Original, Arbeitsfassung, PDF/A, Volltext und Metadaten einer Wissensquelle nie verändern.
5. Der KI-Vorschlag (Spec 01, Requirement 7) SOLL unverändert bleiben und die Art `WISSENSQUELLE` nicht vorschlagen; ein Mensch mit Recht A7 wählt sie im Eingangskorb (entschieden von Fabian am 09.11.2026; ein KI-Vorschlag der Art bleibt Option). So bleiben Prompt und Schwellen aus Gate 0 gültig, und kein Vertrag und keine Police wird über einen KI-Vorschlag ohne Object Lock archiviert. Als `datum` gilt das Erscheinungs- oder Standdatum der Unterlage, als Titel der Dokumenttitel (`dokument.titel`, Spec 01, 7.2). Spec 05, 2.6 ergänzt dieses Kriterium: `WISSENSQUELLE` trägt in jeder Konfiguration fest `ki_vorschlag = nein`; eine Änderung oder Konfigurationsdatei, die davon abweicht, lehnt das Archiv ab (umgesetzt in Spec 05, Aufgabe 17.3).
6. Spec 04 ergänzt Spec 02, 7.1: Dokumente der Art `WISSENSQUELLE` SOLLEN nicht in den Jahresexport für den Treuhänder gelangen. Wissensquellen und Wissensseiten sind keine Buchungsbelege oder Geschäftsbücher nach GeBüV; Spec 04 ergänzt die Verfahrensbeschreibung (Spec 01, 16.6) um diesen Satz. Nachweis: Dokument (Verfahrensbeschreibung); Jahresexport per Szenario.

### Requirement 2: Seitenmodell nach dem LLM-Wiki-Muster (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich das Wissen aus meinen Unterlagen als zusammenhängende, gepflegte Seiten haben statt als lose Textstücke,
damit Antworten auf gesammeltem und geprüftem Wissen beruhen.

#### Acceptance Criteria
1. Das Archiv SOLL je Mandant eine Wissensbasis aus Wissensseiten führen; jede Seite SOLL einen eindeutigen Pfad `<ordner>/<name>` haben, ausser der Seite `ueberblick` (2.3) (Name nur `a-z`, `0-9` und `-`, kebab-case, Umlaute als `ae`, `oe`, `ue`), der sich nach dem Anlegen nicht mehr ändert.
2. Jede Seite SOLL diese Felder haben: Typ, Titel, Beschreibung (ein Satz, höchstens 200 Zeichen, für Index und Suchtreffer), Schlagwörter (klein, kebab-case), Status (`AKTIV` oder `VERALTET`), optional «veraltet ab» (Datum, OKF `stale_after`), Quelleneinträge, Vertraulichkeit, erzeugt von und am (OKF `generated`), freigegeben von und am (OKF `verified`) und Inhalt in Markdown.
3. Das Archiv SOLL diese Seitentypen mit festem Ordner kennen: `Synthese` (höchstens eine Seite `ueberblick` ohne Ordner; fehlt sie, plant der erste Ingest einer Wissensquelle mit Vertraulichkeit `NORMAL` sie als neue Seite; ihre Vertraulichkeit bleibt `NORMAL`, solange alle Dokumente ihrer Hülle `NORMAL` sind, Requirement 11.8 und 11.9), `Quelle` (`quellen/`, je verarbeiteter Wissensquelle genau eine, Name beginnt mit `JJJJ-MM` des Dokumentdatums), `Konzept` (`konzepte/`), `Akteur` (`akteure/`), `Projekt` (`projekte/`) und `Analyse` (`analysen/`). Diese sechs Typen gelten in jedem Mandanten, auch in `vhf-gsk`; weitere Typen gibt es nicht (entschieden von Fabian am 09.11.2026).
4. FALLS ein Name `index` oder `log` lautet, ein Pfad nicht zum Ordner seines Typs passt oder bereits vergeben ist, DANN SOLL das Archiv die Änderung nicht annehmen lassen (Befund nach 6.2).
5. Jeder Quelleneintrag SOLL einen stabilen Schlüssel haben und entweder auf ein archiviertes Dokument desselben Mandanten mit PDF-Seite (Einzelseite oder Bereich der PDF/A-Fassung) und optional einem wörtlichen Auszug von höchstens 300 Zeichen verweisen oder auf eine andere Wissensseite. In einem Entwurf darf die PDF-Seite fehlen (z. B. beim Analyse-Vorschlag, 12.4); eine solche Änderung lässt sich erst annehmen, wenn ein Mensch die Seite ergänzt hat (Befund nach 2.7).
6. Jede inhaltliche Aussage SOLL mit einer Fussnote belegt sein, deren Label der Schlüssel eines Quelleneintrags ist (`Aussage.[^q-…]`); die Reihenfolge der Quelleneinträge SOLL für die Zuordnung keine Rolle spielen. Als inhaltliche Aussage gilt jeder Absatz und jeder Listenpunkt ausserhalb von Überschriften, Widerspruchsblöcken und den Abschnitten «Offene Fragen» und «Berührte Seiten»; enthält einer davon keine Fussnote, SOLL das Archiv den Befund «Aussage ohne Beleg» setzen (6.2).
7. FALLS eine Fussnote keinen Quelleneintrag hat, ein Quelleneintrag auf ein nicht archiviertes, fremdes oder `PERSONAL`-Dokument zeigt, die PDF-Seite fehlt oder die Seitenangabe ausserhalb der Seitenzahl des Dokuments liegt, DANN SOLL das Archiv die Änderung nicht annehmen lassen (Befund nach 6.2). Personaldaten gehören nie in die Wissensbasis (revDSG, ADR-013); das gilt für jeden Anlass, nicht nur für den Ingest (5.6).
8. Seiten vom Typ `Quelle` SOLLEN die Abschnitte «Zusammenfassung», «Kernaussagen» und «Berührte Seiten» haben, die Seite `ueberblick` die Abschnitte «Gesamtbild» und «Offene Fragen»; fehlt ein Abschnitt, SOLL das Archiv dies als Hinweis an der Änderung zeigen.

### Requirement 3: Index, Log und Verlinkung (ohne Konzept-ID)
**User Story:** Als Benutzer möchte ich über einen Katalog und Querverweise durch die Wissensbasis navigieren,
damit ich wie im LLM-Wiki vom Überblick zum Detail finde.

#### Acceptance Criteria
1. Das Archiv SOLL je Mandant einen Index liefern: je Seite eine Zeile mit Titel, Pfad und Beschreibung, gruppiert nach Seitentyp in der Reihenfolge Synthese, Quelle, Konzept, Akteur, Projekt, Analyse; der Index SOLL aus den aktuellen Seiten erzeugt und nie separat gepflegt werden.
2. Der Index SOLL nur Seiten enthalten, die der Benutzer sehen darf (Requirement 11), und Seiten mit Status `VERALTET` als veraltet kennzeichnen.
3. Das Archiv SOLL je Mandant ein Log führen, das nur anfügt: je Eintrag Zeitpunkt, Aktion (`INGEST`, `CREATION`, `UPDATE`, `DEPRECATION`, `LINT`, `ANALYSE`, `IMPORT`; `IMPORT` ergänzt mit Requirement 19.23), Akteur, betroffene Seiten und auslösendes Dokument bzw. Entwurf; der angezeigte Text SOLL aus diesen Feldern erzeugt werden, nie aus Text der KI.
4. WENN ein Benutzer das Log abruft, DANN SOLL das Archiv neueste Einträge zuerst liefern, Seiten, die der Benutzer nicht sehen darf, nicht nennen und Einträge ohne sichtbare Seite weglassen.
5. Seiten SOLLEN einander nur mit normalen Markdown-Links auf den Pfad ab der Wurzel verlinken (`[Titel](/konzepte/x)`); andere Linkformen (z. B. Wikilinks `[[…]]`) SOLL das Archiv als Befund melden.
6. WENN ein Link auf eine Seite zeigt, die es nicht gibt oder die der Benutzer nicht sehen darf, DANN SOLL die App den Link als «Seite nicht vorhanden» ohne Titel darstellen; vorhandene, aber unsichtbare Seiten SOLLEN dabei nicht von fehlenden unterscheidbar sein.
7. FALLS ein Entwurf externe Links (`http:`, `https:`, `mailto:` u. a.), eingebettete Bilder oder HTML enthält, DANN SOLL das Archiv dies als Befund melden; dargestellt wird Markdown immer ohne HTML.
8. Jede Seite SOLL die Liste der sichtbaren Seiten zeigen, die auf sie verlinken. Ein Quelleneintrag auf das Dokument X SOLL dabei als Link auf die Seite vom Typ `Quelle` zu X zählen; so zeigt die Quelle-Seite alle Seiten, die auf ihrer Wissensquelle beruhen.

### Requirement 4: Speicherung und Versionen (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich jede Fassung einer Seite nachvollziehen können,
damit Wissen so prüfbar bleibt wie archivierte Metadaten.

#### Acceptance Criteria
1. Das Archiv SOLL Seiten, Versionen, Quelleneinträge, Entwürfe und Log im Schema des Mandanten speichern (Liquibase-Changelog `db/mandant`), nicht in Dateien oder einem Git-Repository.
2. WENN eine Änderung freigegeben wird, DANN SOLL das Archiv die vollständige neue Fassung (alle Felder, Quelleneinträge und Inhalt) als neue Version mit fortlaufender Nummer, Akteur der Erzeugung, Freigeber, Zeit, Entwurf und gegebenenfalls Grund speichern.
3. FALLS jemand versucht, Seitenversionen oder Logeinträge zu ändern oder zu löschen, DANN SOLL die Datenbank dies verweigern (nur Einfügen erlaubt, wie `dokument_version` nach Spec 01, 10.3).
4. Ein Benutzer mit Recht A15 SOLL die Versionen einer für ihn sichtbaren Seite ansehen und zwei Versionen als Diff vergleichen können, soweit er die jeweilige Version nach 11.2 sehen darf; die Sichtbarkeit SOLL das Archiv je Version aus deren eigener Hülle und Vertraulichkeit bestimmen, nicht aus der aktuellen. Unsichtbare Versionen SOLL es nur mit Nummer ohne Inhalt listen, auf Direktzugriff mit HTTP 404 antworten und einen Diff nur liefern, wenn beide Versionen sichtbar sind.
5. Das Archiv SOLL Seiten nie löschen; nicht mehr gültige Seiten erhalten über eine freigegebene Änderung den Status `VERALTET` und bleiben für Links und Verlauf erhalten.
6. Spec 04 ergänzt Spec 01, 15.1: Das tägliche Manifest SOLL zusätzlich Anzahl und SHA-256 der Zeilen von `wissensseite_version` und `wissens_log` im Fenster enthalten.
7. Die Wissensbasis SOLL ohne zusätzliche Schritte in Sicherung, Zweitkopie und Restore-Probe enthalten sein (Spec 01, 16.2, 16.3, 16.8); die Restore-Probe SOLL Anzahl Seiten und Versionen vergleichen.

### Requirement 5: Ingest einer Wissensquelle (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich, dass die KI eine neue Wissensquelle selbst in die bestehenden Seiten einarbeitet,
damit ich nur noch prüfen und freigeben muss.

#### Acceptance Criteria
1. WENN ein Dokument der Art `WISSENSQUELLE` den Status `ARCHIVIERT` erreicht, DANN SOLL das Archiv einen Auftrag `WISSEN_INGEST` einreihen.
2. Ein Benutzer mit Recht A16 SOLL den Ingest einer archivierten Wissensquelle erneut starten können, wahlweise für einen Bereich von PDF-Seiten.
3. Das Archiv SOLL den Ingest in dieser Reihenfolge ausführen: (a) Text an Seitengrenzen in Abschnitte von höchstens `archiv.wissen.abschnitt-zeichen` (Startwert 30'000 Zeichen) teilen und je Abschnitt die Kernaussagen mit PDF-Seite und wörtlichem Auszug bestimmen; (b) Index und, nur bei einer Wissensquelle mit Vertraulichkeit `NORMAL`, die Seite `ueberblick` lesen und die betroffenen Seiten bestimmen, höchstens `archiv.wissen.max-seiten` (Startwert 15); (c) nur diese Seiten laden und je Seite eine Änderung erstellen; (d) die Seite vom Typ `Quelle` erstellen bzw. aktualisieren.
4. Das Archiv SOLL der KI nie die ganze Wissensbasis übergeben; FALLS der Index länger ist als `archiv.wissen.index-max-zeichen` (Startwert 60'000 Zeichen), DANN SOLL das Archiv ihn vorab über die Suche nach Requirement 9 mit den Kernaussagen auf höchstens 40 Zeilen (und die Seite `ueberblick`, soweit nach 5.3 zulässig) eingrenzen.
5. Das Archiv SOLL der KI beim Ingest nur Seiten zeigen (im Index und im Inhalt), deren Vertraulichkeit nicht höher ist als die der Wissensquelle.
6. FALLS eine Wissensquelle die Vertraulichkeit `PERSONAL` hat, keinen Volltext, keine PDF/A-Fassung (Vermerk `pdfa_fehlt`, Spec 01, 5.9) oder einen Volltext, dessen Zahl der Seitentrenner `\f` nicht der Seitenzahl des Dokuments entspricht, DANN SOLL das Archiv keinen KI-Aufruf ausführen und an der Wissensquelle den Hinweis «nicht in die Wissensbasis übernommen» mit Grund zeigen (bei den beiden letzten Fällen «keine Seitenangaben möglich»).
7. FALLS der Ingest für ein Dokument verlangt wird, das nicht der Art `WISSENSQUELLE` angehört, DANN SOLL das Archiv mit HTTP 422 `KEINE_WISSENSQUELLE` antworten. Der Ingest gilt nur für die Art `WISSENSQUELLE`; eine Aktion «Zur Wissensbasis hinzufügen» für archivierte Dokumente anderer Arten gibt es vorerst nicht (entschieden von Fabian am 09.11.2026).
8. Das Ergebnis eines Ingest SOLL immer ein Entwurf sein; der Ingest SOLL keine freigegebene Seite, keinen Index und kein Log ändern.
9. FALLS die KI für einen Teilauftrag nach den Wiederholungen (3 Versuche, Spec 01) nicht erreichbar ist oder die Zeit überschreitet, oder FALLS die Antwort des Schritts Planung auch nach einer Nachfrage ungültig ist, DANN SOLL das Archiv den Entwurf mit Status `FEHLER` und Hinweis abschliessen; das Dokument SOLL `ARCHIVIERT` und unverändert bleiben (die KI blockiert nie, ADR-006). Ungültige Antworten der übrigen Schritte regelt 13.8.
10. FALLS für ein Dokument bereits ein Ingest läuft oder wartet (5.13), DANN SOLL das Archiv einen weiteren Start mit HTTP 409 `STATUS_KONFLIKT` ablehnen.
11. FALLS eine Wissensquelle eine höhere Vertraulichkeit als `NORMAL` hat, DANN SOLL der Ingest die Seite `ueberblick` weder lesen noch ändern und im Entwurf den Hinweis «Überblick nicht aktualisiert (höhere Vertraulichkeit)» zeigen (11.8).
12. FALLS eine geplante neue Seite einen Pfad trifft, der schon von einer für den Ingest unsichtbaren Seite höherer Vertraulichkeit belegt ist, DANN SOLL das Archiv an der Änderung den Befund nach 2.4 und den Hinweis «Seite auf höherer Stufe vorhanden – von Hand zusammenführen» setzen; der KI SOLL es dabei nichts von dieser Seite zeigen. Den Hinweis sieht nur der Freigeber (A17).
13. Das Archiv SOLL je Mandant höchstens einen Ingest-Entwurf mit Status `IN_ARBEIT` oder `OFFEN` führen. Weitere Ingests SOLLEN als Entwurf `WARTEND` in der Reihenfolge ihrer Auslösung warten und erst starten, wenn der vorherige Entwurf `ERLEDIGT`, `FEHLER` oder `ABGELEHNT` ist; sie planen dann gegen die aktuellen Versionen (wie im LLM-Wiki: Quellen einzeln einarbeiten). Dass ein nicht freigegebener Entwurf weitere Wissensquellen staut, ist akzeptiert (entschieden von Fabian am 09.11.2026).

### Requirement 6: Entwurf und Freigabe (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich jede vorgeschlagene Änderung als Diff sehen und einzeln annehmen, ändern oder verwerfen,
damit nur geprüftes Wissen in die Wissensbasis kommt.

#### Acceptance Criteria
1. Ein Entwurf SOLL je betroffener Seite genau eine Änderung enthalten (neue Seite, Seite ändern oder Seite als veraltet markieren), mit Basisversion, vorgeschlagener Fassung, Diff zur Basisversion, Begründung, Widersprüchen (Requirement 7) und Liste entfernter Aussagen. Ein Ingest-Entwurf SOLL zusätzlich die Kernaussagen mit PDF-Seite, Auszug und Prüfergebnis zeigen und Kernaussagen kennzeichnen, die keiner Änderung zugeordnet sind (wie im LLM-Wiki die Besprechung der Kernaussagen vor dem Einarbeiten).
2. Bevor ein Entwurf zur Freigabe bereitsteht, SOLL das Archiv jede Änderung ohne KI prüfen (Felder, Pfad, reservierte Namen, Fussnoten und Quelleneinträge, Aussagen ohne Fussnote nach 2.6, Seitenangaben, `PERSONAL`-Quellen, wörtliche Auszüge auf der angegebenen PDF-Seite, Links, kein HTML, Grösse höchstens `archiv.wissen.seite-max-zeichen`, Startwert 20'000 Zeichen; bei einer neuen oder geänderten Seite aus einem Ingest mindestens eine Fussnote auf einen Schlüssel der aktuellen Wissensquelle) und Befunde an der Änderung zeigen.
3. FALLS eine Änderung einen Befund hat, DANN SOLL das Archiv sie erst nach Korrektur annehmen und sonst mit HTTP 422 `ENTWURF_BEFUND` antworten. Das Archiv SOLL die Prüfung nach 6.2 bei jedem Annehmen erneut gegen den aktuellen Stand ausführen (z. B. inzwischen vergebener Pfad, Quelldokument inzwischen `PERSONAL`).
4. Ein Benutzer mit Recht A17 SOLL je Änderung wählen können: annehmen, geändert annehmen (Fassung bearbeiten, erneut geprüft nach 6.2) oder verwerfen mit Grund. Jede Annahme SOLL eine einzelne Handlung zu einer Änderung sein, deren Diff dem Freigeber angezeigt wurde; ein gesammeltes Annehmen mehrerer Änderungen SOLL es nicht geben, weil «freigegeben von» (OKF `verified` mit `human:`) sonst eine Prüfung behauptete, die nicht stattfand.
5. WENN eine Änderung angenommen wird, DANN SOLL das Archiv in einer Transaktion die neue Version speichern (4.2), «freigegeben von» auf `human:<benutzer>` setzen, «erzeugt von» auf den KI-Akteur bzw. bei geänderter Fassung auf `human:<benutzer>`, den Index sofort nachführen, einen Logeintrag schreiben und den Auftrag `WISSEN_EMBEDDING` einreihen.
6. FALLS die Seite seit der Basisversion der Änderung eine neue Version erhalten hat, DANN SOLL das Archiv das Annehmen mit HTTP 412 `VERSION_KONFLIKT` ablehnen, die Änderung als `VERALTET` kennzeichnen und «neu erzeugen» gegen die aktuelle Version anbieten.
7. Annehmen und Verwerfen SOLLEN `If-Match` mit dem ETag der Änderung verlangen; ohne `If-Match` SOLL das Archiv mit HTTP 428 `VERSION_FEHLT` antworten (wie Spec 01, 10.4, 10.6).
8. Das Archiv SOLL die Vertraulichkeit einer Änderung nach 11.1 berechnen und anzeigen und kennzeichnen, wenn die Annahme die Vertraulichkeit der Seite erhöht (Hinweis `STUFE_STEIGT`; ändert ein Ingest einer `INTERN`-Quelle eine `NORMAL`-Seite, bleibt diese dauerhaft `INTERN`, akzeptiert, entschieden von Fabian am 09.11.2026); der Freigeber SOLL sie von `NORMAL` auf `INTERN` erhöhen, aber nicht darunter senken können (sonst HTTP 422 `VERTRAULICHKEIT_ZU_TIEF`). `PERSONAL` SOLL nicht wählbar sein (HTTP 422 `VERTRAULICHKEIT_UNGUELTIG`). Die Erhöhung SOLL als Mindeststufe nach 11.1 gespeichert werden und bei späteren Neuberechnungen erhalten bleiben.
9. Ein Benutzer mit Recht A17 SOLL eine Seite von Hand anlegen oder ändern können; das Archiv SOLL dies als Entwurf mit Anlass `MANUELL` speichern und gleich prüfen (6.2); derselbe Benutzer darf ihn freigeben. Spec 04, 19 ergänzt: Ein importiertes Wissenspaket entsteht als Entwurf mit Anlass `IMPORT` und wird wie jeder andere Entwurf nach 6.2 bis 6.8 geprüft und einzeln freigegeben; «neu erzeugen» (6.6) gibt es für Änderungen mit Anlass `IMPORT` nicht, weil das Archiv dort keine KI aufruft (19.11).
10. WENN alle Änderungen eines Entwurfs entschieden sind, DANN SOLL das Archiv den Entwurf als erledigt abschliessen und für einen Ingest einen Logeintrag `INGEST` mit den angenommenen Seiten schreiben, für einen Analyse-Vorschlag (12.4) einen Logeintrag `ANALYSE` mit Vorschlagendem und angenommener Seite.
11. Die App SOLL Benutzern mit Recht A17 die Zahl offener Entwürfe in der Navigation zeigen.

### Requirement 7: Widersprüche und Vorrang (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich, dass widersprüchliches Wissen sichtbar bleibt, bis ich entschieden habe,
damit nie eine Aussage still verschwindet.

#### Acceptance Criteria
1. WENN eine neue Quelle einer belegten Aussage einer bestehenden Seite widerspricht, DANN SOLL die Änderung einen Widerspruchsblock mit den Fussnoten beider Quellen enthalten, statt die bestehende Aussage zu ersetzen.
2. FALLS eine Änderung eine belegte Aussage entfernt (ein Quellenschlüssel der Basisversion kommt in der neuen Fassung nicht mehr vor), ohne sie unter «entfernte Aussagen» mit Begründung aufzuführen, DANN SOLL das Archiv den Befund «Aussage ohne Begründung entfernt» setzen (6.3).
3. FALLS eine Änderung einen bestehenden Widerspruchsblock entfernt, DANN SOLL das Archiv dies im Diff hervorheben und nur durch einen Menschen mit Recht A17 annehmen lassen, der einen Grund angibt; fehlt der Grund, SOLL das Archiv mit HTTP 422 `PFLICHTFELD_FEHLT` antworten. Der Grund SOLL als Begründung für die mit dem Block entfernten Quellenschlüssel gelten (kein Befund nach 7.2), und der Logeintrag SOLL «Widerspruch aufgelöst» nennen.
4. Solange eine Seite einen Widerspruchsblock enthält, SOLL die App sie mit dem Hinweis «Widerspruch offen» zeigen, und das Werkzeug `wissen_seite_zeigen` SOLL den Hinweis mitliefern.
5. Eine neuere Quelle SOLL ältere Aussagen nicht automatisch ungültig machen; das Archiv SOLL das Datum der Quelle (Dokumentdatum) und den Stand der Seite (letzte Freigabe) getrennt anzeigen.
6. Das Archiv SOLL Freigabefelder, Status und Vertraulichkeit einer Seite nur aus Handlungen eines Menschen bzw. aus der Berechnung nach 11.1 setzen; Angaben der KI zu diesen Feldern SOLL es verwerfen.

### Requirement 8: Wissen im Chat (ohne Konzept-ID, erweitert C1, C2)
**User Story:** Als Benutzer möchte ich im Chat Antworten aus der Wissensbasis erhalten, mit Seite und Originaldokument als Beleg,
damit ich die Antwort bis zur Unterlage zurückverfolgen kann.

#### Acceptance Criteria
1. Spec 04 ergänzt Spec 02, 3.1 um die Werkzeuge `wissen_index` und `wissen_seite_zeigen`; die Werkzeugliste SOLL sonst abschliessend bleiben, und beide Werkzeuge SOLLEN nur lesen (Spec 02, 5.3).
2. Das Werkzeug `wissen_index` SOLL Indexzeilen der für den Fragenden sichtbaren Seiten liefern, ohne Suchbegriff höchstens 200 Zeilen, mit Suchbegriff die besten Treffer der Suche nach Requirement 9.
3. Das Werkzeug `wissen_seite_zeigen` SOLL die aktuelle freigegebene Fassung einer sichtbaren Seite liefern, mit Quelleneinträgen (Dokument-ID, Dokumenttitel, PDF-Seite, Link), Stand, Freigabe und den Hinweisen `VERALTET`, `ABGELAUFEN` («veraltet ab» erreicht), `WIDERSPRUCH` und `QUELLE_PRUEFEN`; den Inhalt höchstens bis `archiv.wissen.werkzeug-max-zeichen` (Startwert 12'000 Zeichen), bei Kürzung mit dem Hinweis `GEKUERZT` und trotzdem der vollständigen Quellenliste.
4. Der Agent SOLL bei Fragen nach Wissen zuerst `wissen_index` verwenden, dann die passenden Seiten mit `wissen_seite_zeigen` und bei Bedarf die Quelldokumente mit `dokument_zeigen`; für Summen, Anzahlen und Zeiträume gilt weiterhin Spec 02, 3.2, Wissensseiten sind keine Grundlage für Kennzahlen.
5. Spec 04 ergänzt Spec 02, 4.1: Beruht eine Antwort auf einer Seite, SOLL die Quellenliste die Seite (Titel, Link, Stand) und ihre direkten Quelldokumente (Dokumenttitel mit Link und PDF-Seite, höchstens 5 je Seite, die meistzitierten zuerst) enthalten.
6. Das Archiv SOLL Entwürfe und nicht angenommene Änderungen nie an den Chat geben.
7. WENN eine verwendete Seite einen der Hinweise aus 8.3 trägt, DANN SOLL der Agent ihn in der Antwort nennen.
8. WENN der Benutzer auf eine Seite in der Quellenliste tippt, DANN SOLL die App die Seite öffnen (mit Protokolleintrag nach 14.2).
9. Die öffentliche Schnittstelle und der öffentliche Chatbot (Spec 03, Requirements 5 und 6) SOLLEN keine Wissensseiten liefern; Spec 03, 6.1 bleibt unverändert.

### Requirement 9: Suche nach Wissensseiten (ohne Konzept-ID, erweitert S1, S2)
**User Story:** Als Benutzer möchte ich Wissensseiten wie Dokumente finden,
damit ich Wissen auch ohne Chat nachschlagen kann.

#### Acceptance Criteria
1. Das Archiv SOLL eine Volltextsuche über die aktuellen Fassungen anbieten, mit Titel stärker gewichtet als Beschreibung und Schlagwörter, diese stärker als der Inhalt (deutsche Stammformen wie Spec 01, 14.1).
2. WENN eine Fassung freigegeben wird, DANN SOLL das Archiv ihren Inhalt in Abschnitte teilen und Embeddings speichern wie Spec 02, 2.1, in einer eigenen Tabelle; die Suche SOLL Volltext und Vektor wie Spec 02, 2.2 zusammenführen und bei Ausfall nach Spec 02, 2.3 auf Volltext zurückfallen.
3. Die Suche SOLL die Sichtbarkeit nach Requirement 11 vor der Verdichtung und vor dem Reranker anwenden.
4. Spec 04 ergänzt die Suchseite (Spec 01, Requirement 14): Die App SOLL passende Wissensseiten in einem eigenen Abschnitt über den Dokumenttreffern zeigen, mit Titel, Typ und Beschreibung.
5. Die Suche SOLL bei 2'000 Seiten pro Mandant innert 1 Sekunde antworten (p95, Messpunkt Server; Messbedingungen wie Spec 01, Requirement 3.2).
6. Das Archiv SOLL eine Nachberechnung aller Seiten-Embeddings als Auftrag `WISSEN_EMBEDDING_ALLE` anbieten.

### Requirement 10: Lint der Wissensbasis (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich, dass die Wissensbasis regelmässig auf Widersprüche, Lücken und Veraltetes geprüft wird,
damit sie mit der Zeit nicht verwildert.

#### Acceptance Criteria
1. Das Archiv SOLL je Mandant periodisch einen Auftrag `WISSEN_LINT` einreihen (`archiv.wissen.lint-zeitplan`, Startwert sonntags 04:00 Europe/Zurich); ein Benutzer mit Recht A17 SOLL ihn zusätzlich von Hand starten können.
2. Das Archiv SOLL ohne KI prüfen auf: verwaiste Seiten (ohne eingehende Links nach 3.8, ausser `ueberblick`), Links auf fehlende Seiten, erreichtes «veraltet ab», Seiten mit Hinweis «Quelle prüfen» (Requirement 15), Quelle-Seiten ohne berührte Seiten, fehlende Querverweise (Seiten mit gemeinsamer Quelle oder mindestens zwei gemeinsamen Schlagwörtern ohne Link in eine der beiden Richtungen) und Formfehler nach 2.4, 2.6 und 2.7.
3. Das Archiv SOLL mit KI prüfen auf Widersprüche zwischen Seiten, die einander verlinken, eine Quelle teilen oder mindestens zwei Schlagwörter gemeinsam haben, auf fehlende Querverweise nach 10.2 (als Link-Vorschlag) und auf veraltete Aussagen; Kandidaten für veraltete Aussagen SOLL das Archiv ohne KI bestimmen: Aussagen einer Seite, deren Quelldokument ein älteres Dokumentdatum hat als eine Wissensquelle, deren Quelle-Seite auf dieselbe Seite verlinkt oder mit ihr eine Quelle teilt. Höchstens `archiv.wissen.lint-paare` Seitenpaare je Lauf (Startwert 30), die am längsten nicht geprüften zuerst.
4. Das Archiv SOLL der KI im Lint auch Paare verschiedener Vertraulichkeit vorlegen, nie aber Seiten, deren Hülle ein `PERSONAL`-Dokument enthält. Änderungen aus einem Paar verschiedener Vertraulichkeit SOLL es nur für die Seite mit der höheren Vertraulichkeit vorschlagen (z. B. Widerspruchsblock mit beiden Fussnoten); die Seite der tieferen Stufe SOLL es nur als Lint-Befund (IDs und Codes) markieren.
5. Das Ergebnis SOLL ein Lint-Bericht mit Befunden sein und, wo eine Änderung nötig ist, ein Entwurf mit Anlass `LINT`, der wie in Requirement 6 freigegeben wird; der Lint SOLL keine Seite direkt ändern.
6. WENN ein Lint-Lauf endet, DANN SOLL das Archiv einen Logeintrag `LINT` mit der Zahl der Befunde je Art schreiben.

### Requirement 11: Vertraulichkeit und Mandantentrennung (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich, dass die Wissensbasis nichts zeigt, was der Benutzer im Archiv nicht sehen darf,
damit Wissen aus internen Unterlagen intern bleibt.

#### Acceptance Criteria
1. Die wirksame Vertraulichkeit einer Seite SOLL das Maximum aus der höchsten Vertraulichkeit der Dokumente ihrer Hülle und ihrer Mindeststufe sein (`NORMAL` < `INTERN`; Seiten tragen nie `PERSONAL`, 2.7). Die Mindeststufe SOLL das Maximum aus der Erhöhung von Hand (6.8) und den Kontextstufen aller angenommenen Änderungen der Seite sein (11.7) und bei keiner Neuberechnung sinken. Das Archiv SOLL die wirksame Vertraulichkeit bei jeder Freigabe und bei jeder Änderung der Vertraulichkeit eines Dokuments der Hülle neu berechnen.
2. Ein Benutzer SOLL eine Seite nur sehen, wenn er Recht A15 hat, jedes Dokument ihrer Hülle nach den Zeilen A2 bis A4 der Rechtematrix sehen darf und ein Dokument der Mindeststufe der Seite nach A2 bzw. A3 sehen dürfte; sonst SOLL das Archiv die Seite weder im Index noch in Suchtreffern, Zählern, Log, Links, eingehenden Links, Versionen, Diffs oder im Chat liefern und auf Direktzugriff mit HTTP 404 antworten (Spec 01, 2.3). Für Versionen gilt dieselbe Regel mit Hülle und Mindeststufe der Version (4.4).
3. WENN sich die Sichtbarkeit eines Dokuments der Hülle ändert (z. B. Vertraulichkeit oder `person_benutzer` nach A8), DANN SOLL die Sichtbarkeit der Seite ab der nächsten Anfrage folgen, ohne Freigabe.
4. Die Werkzeuge aus 8.1 SOLLEN Mandant und Benutzer nur aus dem Anfragekontext beziehen und die Sichtbarkeit nach 11.2 anwenden (Spec 02, 5.1, 5.2).
5. Quelleneinträge und Links SOLLEN nur auf Dokumente und Seiten desselben Mandanten zeigen; Spec 04 ergänzt Spec 03, 1.4 und 9.1: Der Isolationstest SOLL die Wissensbasis (Index, Seiten, Suche, Log, Entwürfe, Chat-Werkzeuge, Export) umfassen.
6. Das Archiv SOLL Inhalte von Wissensquellen und Seiten, die an das Sprachmodell gehen, als Daten kennzeichnen; Anweisungen darin SOLLEN nur den Inhalt eines Entwurfs beeinflussen können, nie freigegebene Seiten, Rechte, Felder nach 7.6 oder Werkzeuge.
7. Jede Änderung SOLL eine Kontextstufe tragen: die höchste Vertraulichkeit aller Dokumente und Seiten, die beim Erzeugen in den Kontext gelangt sind (Ingest: Wissensquelle und gezeigte Seiten; Lint: das Seitenpaar; Analyse: alle Dokumente, die Werkzeuge im Gespräch bis zur vorgeschlagenen Nachricht geliefert haben; manuell: `NORMAL`). Die Annahme SOLL die Kontextstufe in die Mindeststufe der Seite übernehmen (11.1), weil sich nicht prüfen lässt, ob die KI Wissen ohne Fussnote übernommen hat.
8. Die Seite `ueberblick` SOLL die wirksame Vertraulichkeit `NORMAL` behalten, solange alle Dokumente ihrer Hülle `NORMAL` sind (11.9); FALLS eine Änderung sie höher einstufen würde, DANN SOLL das Archiv einen Befund setzen (6.2). So bleibt die Synthese für alle Rollen mit A15 lesbar; sie entsteht nur aus `NORMAL`-Wissen (entschieden von Fabian am 09.11.2026; Einschränkung «solange …» entschieden von Fabian am 18.11.2026).
9. WENN ein Dokument der Hülle von `ueberblick` nachträglich höher eingestuft wird, DANN SOLL die Übersicht nach 11.1 die wirksame Stufe erhalten (z. B. `INTERN`) und nach 11.2 nur noch für die Berechtigten sichtbar sein (entschieden von Fabian am 18.11.2026).

### Requirement 12: Rechte der Wissensbasis (ohne Konzept-ID, Ergänzung zur Rechtematrix Spec 01, 2.6)
**User Story:** Als Inhaber möchte ich festlegen, wer Wissen liest, vorschlägt und freigibt,
damit die KI-Pflege unter menschlicher Kontrolle bleibt (ADR-006).

#### Acceptance Criteria
1. Spec 04 ergänzt die Rechtematrix (Spec 01, 2.6) um die folgenden Zeilen; Bedeutung von «erlaubt», «403» und «404» und der Vorrang 404 vor 403 wie dort.

| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |
| --- | --- | --- | --- | --- | --- |
| A15 | Wissensseiten, Index, Versionen und Log lesen (Sichtbarkeit nach 11.2) | erlaubt | erlaubt | erlaubt (nur lesend) | erlaubt |
| A16 | Ingest einer Wissensquelle starten oder wiederholen | erlaubt | 403 | 403 | erlaubt |
| A17 | Entwürfe einsehen, Seiten von Hand ändern, Änderungen annehmen oder verwerfen, Lint starten | erlaubt | 403 | 403 | erlaubt |
| A18 | Chat-Antwort als Analyse vorschlagen, Status eigener Vorschläge sehen | erlaubt | erlaubt | 403 | erlaubt |
| A19 | Wissensbasis verwalten (Einstellungen, OKF-Export) | erlaubt | 403 | 403 | 403 |

¹ Im Mandanten `vhf-gsk` gibt die `geschaeftsstelle` frei (Entscheid 09.11.2026). A19 für die Geschäftsstelle: 403; Einstellungen und OKF-Export bleiben dem Inhaber vorbehalten (entschieden von Fabian am 09.11.2026). Weil Spec 03 im Mandanten `vhf-gsk` nur Benutzer mit Rolle `geschaeftsstelle` anlegt, gelten dort die Startwerte (10.1, 13.4, 13.6); einen OKF-Export erhält VHF/GSK nur über den Vollexport des Betreibers (Spec 03, 8.2; 17.5).

Spec 04, 19.5 ergänzt (ohne neue Zeile, Aktionstext der Tabelle unverändert): A17 umfasst auch das Importieren eines Wissenspakets und die Wiederholung seiner Prüfung; A16 und A19 sind dafür nicht nötig.

2. FALLS ein Benutzer mit Rolle `treuhaender` eine schreibende Aktion der Wissensbasis ausführt, DANN SOLL das Archiv mit HTTP 403 antworten und nichts ändern (Spec 01, 2.4).
3. FALLS ein Benutzer ohne Recht A17 einen Entwurf oder eine Änderung abruft, der bzw. die nicht sein eigener Vorschlag nach A18 ist, DANN SOLL das Archiv mit HTTP 403 antworten. Eigene Vorschläge SOLL es ihm nur mit Status, Entscheid und Grund liefern, ohne Fassung und Diff.
4. WENN ein Benutzer mit Recht A18 eine Chat-Antwort als Analyse vorschlägt, DANN SOLL das Archiv ohne KI-Aufruf einen Entwurf mit Anlass `ANALYSE` anlegen (Seite vom Typ `Analyse`, Inhalt aus der Antwort) und nach Requirement 6 freigeben lassen. Dabei gilt:
   - Quelleneinträge SOLLEN nur aus Dokumentquellen und Seitenquellen der Antwort entstehen, ohne PDF-Seite (2.5), nie aus Datenständen.
   - Der Entwurf SOLL erst annehmbar sein, wenn ein Mensch mit A17 Fussnoten und PDF-Seiten von Hand ergänzt hat (geändert annehmen, 6.4); bis dahin trägt er die Befunde nach 2.6 und 2.7.
   - FALLS im Gespräch bis zu dieser Nachricht ein anderes Werkzeug als `dokumente_suchen`, `dokument_zeigen`, `wissen_index` oder `wissen_seite_zeigen` aufgerufen wurde (z. B. `kennzahl`), DANN SOLL das Archiv mit HTTP 422 `ANALYSE_NICHT_MOEGLICH` antworten und die App die Aktion nicht anbieten; Kennzahlen gehören nicht in Wissensseiten (8.4).
   - Die Kontextstufe (11.7) SOLL aus allen Dokumenten bestimmt werden, die Werkzeuge im Gespräch bis zu dieser Nachricht geliefert haben, nicht nur aus den angezeigten Quellen.

### Requirement 13: KI-Betrieb, Kosten und grosse Dokumente (ohne Konzept-ID, nicht funktional)
**User Story:** Als Betreiber möchte ich, dass die Wissensbasis dieselben KI-Regeln einhält wie das Archiv und die Kosten je Ingest begrenzt sind,
damit Datenschutz und Budget auch bei langen Unterlagen halten.

#### Acceptance Criteria
1. Das Archiv SOLL alle KI-Aufrufe der Wissensbasis (Sprachmodell, Embeddings, Reranker) nur an Infomaniak AI Services richten, mit der Konfiguration `archiv.ki.*` aus Spec 01. Der optionale Schlüssel `archiv.wissen.modell` (Standard `archiv.ki.chat-modell`) SOLL nur das Modell wählen; Aufrufe gehen immer über `archiv.ki.basis-url` mit der Hostprüfung nach Spec 01, 16.20–16.22, einen eigenen URL-Schlüssel der Wissensbasis gibt es nicht. Beim Start SOLL das Archiv prüfen, dass der Modellname nicht leer ist. Nachweis: JUnit (Konfigurationstest). Spec 04, 19.21 stellt klar (entschieden von Fabian am 18.11.2026): Der Import eines Wissenspakets und das Prüfwerkzeug rufen keine KI auf. Das Erarbeiten eines Pakets findet ausserhalb des Archivs statt und fällt nicht unter diesen Absatz, wohl aber unter die Regel zu Cloud-Modellen (19.25); das Archiv selbst richtet auch dann keinen KI-Aufruf an einen anderen Anbieter als Infomaniak.
2. Im Profil `lokal` SOLL das Archiv für Ingest und Lint das lokale Modell nach ADR-012 verwenden dürfen; Embeddings und Reranker bleiben Stubs, die Suche nach Requirement 9 arbeitet dort nur mit Volltext, und Ergebnisse gelten nicht als Nachweis. Nachweis: JUnit (Konfigurationstest Profil `lokal`: Aufrufe über `localhost`, Embedding- und Reranker-Stub, Suche ohne Vektor; wie Spec 01, 16.20). Spec 04, 19.24 ergänzt: Das Ergebnis lässt sich als Wissenspaket exportieren und ins produktive Archiv importieren; wirksam wird es dort erst durch die Freigabe nach Requirement 6.
3. Auf Stufe A SOLLEN nur Test- und anonymisierte Wissensquellen verarbeitet werden (Spec 01, 16.15).
4. Das Archiv SOLL vor dem ersten KI-Aufruf eines Ingest den Verbrauch aus den Zeichenzahlen schätzen; FALLS die Schätzung `archiv.wissen.budget-tokens` übersteigt (Startwert 600'000 Tokens, je Mandant in den Einstellungen änderbar, A19; entschieden von Fabian am 18.11.2026, ersetzt den Startwert 300'000 vom 09.11.2026 wegen des Bedarfs einer kleinen `NORMAL`-Quelle), DANN SOLL es keinen KI-Aufruf ausführen und die Schätzung mit dem Hinweis zeigen, einen Seitenbereich zu wählen (5.2).
5. FALLS ein Ingest oder Lint während der Ausführung sein Budget erreicht (`archiv.wissen.budget-tokens` bzw. `archiv.wissen.lint-budget-tokens`, Startwert 100'000), DANN SOLL das Archiv abbrechen und die bis dahin erstellten Änderungen als Entwurf mit Vermerk «unvollständig» bereitstellen.
6. Das Archiv SOLL jeden KI-Aufruf in `ki_verbrauch` mit den Zwecken `WISSEN_INGEST`, `WISSEN_LINT` bzw. `WISSEN_EMBEDDING` erfassen. Es SOLL ein eigenes Monatsbudget der Wissensbasis je Mandant führen (Summe aus `ki_verbrauch` mit Zweck `WISSEN_*` im Kalendermonat gegen `archiv.wissen.monatsbudget-tokens`, Startwert 1'000'000 Tokens, je Mandant in den Einstellungen änderbar, A19; ohne eigenen Wert gilt der Startwert, einen Wert ≤ 0 lehnt das Archiv mit HTTP 422 ab; entschieden von Fabian am 09.11.2026) und das Budget des öffentlichen Chatbots (Spec 03, 6.4) weder lesen noch belasten. FALLS das Monatsbudget erschöpft ist, DANN SOLL das Archiv keinen KI-Aufruf ausführen: beim manuellen Start HTTP 429 `BUDGET`; beim automatischen Ingest (5.1) ein Entwurf `FEHLER` mit Hinweis «Budget erschöpft», wiederholbar nach 5.2; ein geplanter Lint entfällt mit Logeintrag `LINT` (Befund «Budget erschöpft»).
7. Das Archiv SOLL Ingest und Lint in Teilaufträge mit höchstens einem KI-Aufruf teilen, damit andere Aufträge (z. B. `VERARBEITEN`) auch bei Parallelität 1 dazwischen laufen.
8. Das Archiv SOLL von der KI strukturierte Antworten nach festem JSON-Schema verlangen (wie Spec 01, 7.1). FALLS eine Antwort ungültig ist, DANN SOLL es einmal nachfragen; bleibt sie ungültig, SOLL es: beim Schritt Kernaussagen den Abschnitt ohne Kernaussagen weiterführen, bei einer Seite oder der Quelle-Seite diese Änderung weglassen, jeweils mit Hinweis im Entwurf, und bei der Planung den Entwurf nach 5.9 mit `FEHLER` abschliessen. Eine ungültige Antwort zählt nicht als Fehlversuch nach 5.9.
9. Logs und CI-Ausgaben der Wissensbasis SOLLEN nur IDs enthalten, nie Inhalte von Seiten, Entwürfen oder Wissensquellen.

### Requirement 14: Protokoll und Nachvollziehbarkeit (ohne Konzept-ID, erweitert B2)
**User Story:** Als Inhaber möchte ich wissen, wer welches Wissen wann freigegeben hat,
damit die Wissensbasis prüfbar bleibt.

#### Acceptance Criteria
1. WENN eine Änderung angenommen oder verworfen wird, DANN SOLL das Archiv im Zugriffsprotokoll Benutzer, Zeit, Seite, Entwurf, Basisversion und neue Version bzw. Grund festhalten, synchron in derselben Transaktion (Spec 01, 10.8).
2. Das Archiv SOLL das Anzeigen einer Seite, einer Version und eines Entwurfs protokollieren (Aktion `WISSEN_ANZEIGEN`), Listen, Index und Suche ohne Seitenbezug nicht einzeln.
3. Das Archiv SOLL den manuellen Start von Ingest und Lint, jeden OKF-Export und jeden Import eines Wissenspakets (Aktion `WISSEN_IMPORT`, 19.20) protokollieren.
4. FALLS ein Zugriff nach Requirement 11 oder 12 mit HTTP 403 oder 404 endet, DANN SOLL das Archiv ihn als `VERWEIGERT` mit Fehlercode protokollieren.
5. Zu jeder Version SOLL das Archiv anzeigen können: erzeugender Akteur (KI-Modell oder Mensch), Freigeber, Entwurf mit Anlass und auslösendes Dokument.
6. Das Zugriffsprotokoll SOLL keine Inhalte von Seiten oder Entwürfen enthalten.

### Requirement 15: Änderungen an Quelldokumenten (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich erfahren, wenn sich die Grundlage einer Seite ändert,
damit keine Seite auf einer nicht mehr gültigen Quelle beruht.

#### Acceptance Criteria
1. WENN sich bei einem Dokument der Hülle einer Seite Vertraulichkeit, Art oder Status ändert oder seine Metadaten eine neue Version erhalten, DANN SOLL das Archiv die betroffenen Seiten mit dem Hinweis «Quelle prüfen» kennzeichnen, ohne ihren Inhalt zu ändern.
2. Der Hinweis «Quelle prüfen» SOLL in der App, im Index und im Werkzeug `wissen_seite_zeigen` erscheinen und erst mit der Freigabe einer Änderung der Seite oder durch ausdrückliche Bestätigung eines Benutzers mit Recht A17 (mit Logeintrag `UPDATE`) entfallen.
3. Quelleneinträge SOLLEN den aktuellen Dokumenttitel zeigen, nicht eine Kopie vom Zeitpunkt der Freigabe.
4. Die Dokumentansicht SOLL unter «In der Wissensbasis verwendet» die sichtbaren Seiten nennen, die das Dokument als Quelle haben.
5. WENN die spätere Löschfunktion (Spec 01, 9.9) ein Dokument der Hülle löscht, DANN SOLL das Archiv die betroffenen Seiten mit «Quelle prüfen» kennzeichnen ([offen: Umgang mit Seitenversionen, die auf einem gelöschten Dokument beruhen (revDSG) – Entscheid in der Spec zur Löschfunktion]).

### Requirement 16: Oberfläche der Wissensbasis (ohne Konzept-ID)
**User Story:** Als Benutzer möchte ich die Wissensbasis am Handy und am Mac lesen und als Freigeber Entwürfe bequem prüfen,
damit Pflege und Nutzung ohne Umweg gelingen.

#### Acceptance Criteria
1. Die App SOLL eine Seite «Wissen» mit dem Index (gruppiert nach Typ), Filtern für Typ und Schlagwort und einem Suchfeld bieten.
2. Die Seitenansicht SOLL den Inhalt ohne HTML darstellen, Fussnoten als Quellen mit Dokumenttitel und PDF-Seite (Tipp öffnet das Dokument nach Spec 02, 4.3 auf der ersten angegebenen PDF-Seite, Route `/dokument/:id?seite=n`; Spec 04 ergänzt die Dokumentansicht aus Spec 01 um diesen Parameter), dazu Stand, Freigabe, Status, Hinweise nach 8.3, eingehende Links und Versionen.
3. Die Entwurfsansicht SOLL je Änderung Diff, Befunde, Hinweise, Widersprüche, entfernte Aussagen, Begründung, Kontextstufe und die Aktionen nach 6.4 zeigen, bei einem Ingest-Entwurf zudem die Kernaussagen nach 6.1 und bei einem Import-Entwurf Herkunft und Prüfung der Quellen nach 19.28; entfernte und neue Zeilen SOLLEN nicht nur durch Farbe, sondern auch durch Zeichen (`−`, `+`) unterscheidbar sein.
4. Alle Seiten der Wissensbasis SOLLEN bei 375 px Breite ohne horizontales Scrollen bedienbar sein; der Diff SOLL am Handy untereinander statt nebeneinander erscheinen.
5. Die App SOLL eine Ansicht des Logs bieten (Requirement 3.4).
6. Spec 04 ergänzt die Chat-Seite (Spec 02, Requirement 6): Quellen-Chips SOLLEN Seiten und Dokumente unterscheidbar zeigen, und an jeder Antwort SOLL für Benutzer mit Recht A18 die Aktion «Als Analyse vorschlagen» stehen.
7. Die Oberfläche SOLL nur Deutsch (Schweiz) aus `texte.de-CH.ts` verwenden und WCAG 2.1 AA erfüllen (tech.md).

### Requirement 17: Export im Open Knowledge Format (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich die Wissensbasis als offenes Dateiformat mitnehmen können,
damit mein Wissen nicht im Archiv eingeschlossen ist.

#### Acceptance Criteria
1. WENN ein Benutzer mit Recht A19 einen OKF-Export anfordert, DANN SOLL das Archiv als Auftrag `WISSEN_EXPORT` ein ZIP nach OKF v0.2 erstellen: je aktueller Seite eine Datei `<pfad>.md` mit YAML-Frontmatter (`type`, `title`, `description`, `tags`, `status` als `stable` bzw. `deprecated`, `stale_after`, `generated`, `verified`, `sources` mit `id`, `resource`, `title`, `last_modified`), dazu `index.md` mit `okf_version: "0.2"` und `log.md`.
2. Verweise auf Archivdokumente SOLLEN als `resource: urn:urinet-archiv:<mandant>:dokument:<id>#page=<n>` erscheinen, Verweise auf Seiten als Pfad ab der Wurzel (`/konzepte/x.md`); Links im Inhalt SOLL der Export von `/<pfad>` auf `/<pfad>.md` umschreiben (`/ueberblick` → `/ueberblick.md`), Fussnoten-Labels unverändert lassen. Dokumentdateien SOLL der Export nicht enthalten. Spec 04, 19.24 ergänzt: Damit ein Export als Wissenspaket importierbar ist, SOLL er zusätzlich `paket.yaml` (19.2) und je Dokument-Quelleneintrag `sha256` (SHA-256 der archivierten Originaldatei), `seite` und `auszug` sowie je Seite `erzeugt_von` und `basis_version` (aktuelle Version) schreiben.
3. Jede exportierte Seite SOLL parsbare Frontmatter mit nicht leerem `type` haben, jeder interne Link SOLL auf eine Datei im Bundle zeigen, `index.md` und `log.md` SOLLEN dem OKF-Format folgen. Nachweis: JUnit.
4. WENN der Export fertig ist, DANN SOLL das Archiv einen Download-Link (24 Stunden gültig) anzeigen; der Export SOLL mit Benutzer, Zeit und Prüfsumme des ZIP protokolliert werden.
5. Spec 04 ergänzt Spec 03, 8.2: Der Vollexport eines Mandanten SOLL den OKF-Export der Wissensbasis enthalten.

### Requirement 18: Abnahme Phase 4
#### Acceptance Criteria
1. Das Archiv-Projekt SOLL eine Prüfliste mit einer Stichprobe echter Wissensquellen und Fragen mit erwarteter Antwort, erwarteten Seiten und erwarteten Quelldokumenten verwenden; wie der Golden-Datensatz (Spec 01, 0.1) liegt sie nur unter `ARCHIV_GOLDEN_DIR`, im Repository nur Schema und Test. Die Stichprobe umfasst 5 Wissensquellen, 50 Fussnoten und 15 Fragen (entschieden von Fabian am 09.11.2026).
2. Belegtreue: Von den zufällig gezogenen Fussnoten der vorgeschlagenen Änderungen (vor jeder Bearbeitung) SOLLEN mindestens 47 von 50 die Aussage auf der angegebenen PDF-Seite tragen. Nachweis: manuell [Fabian].
3. In der Stichprobe SOLL keine angenommene Änderung eine belegte Aussage ohne Widerspruchsblock oder Begründung entfernt haben. Nachweis: manuell [Fabian].
4. Antworten: Mindestens 14 von 15 Fragen SOLLEN korrekt und mit Seite und Quelldokument beantwortet werden. Nachweis: manuell [Fabian].
5. Ein automatisierter Test SOLL mit zwei Mandanten und allen Rollen nachweisen, dass Index, Seiten, Suche, Log, Entwürfe, Chat-Werkzeuge und Export keine Seite liefern, deren Hülle ein für den Benutzer unsichtbares Dokument enthält, und keine Daten über Mandantengrenzen.
6. Der Bericht unter `docs/abnahme/phase4.md` SOLL nur Nummern und Ergebnisse enthalten, ohne Inhalte, und als Kennzahl den Anteil unverändert angenommener Änderungen nennen. Nachweis: Dokument.
7. WENN 18.2 bis 18.5 erfüllt sind und der Lauf gegen den Bestand auf Stufe B manuell, nie in der CI, durchgeführt wurde, DANN SOLL das Gate «Phase 4 bestanden» gelten. Nachweis: manuell [Fabian].

### Requirement 19: Lokale Erarbeitung und Import eines Wissenspakets (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich Wissen lokal auf meinem MacBook erarbeiten, mit einem lokalen Modell oder unter klaren Regeln mit einem Cloud-Modell, und als Paket in die Wissensbasis des produktiven Archivs übertragen,
damit ich Seiten ohne Kosten je Aufruf und ohne Weitergabe vertraulicher Unterlagen vorbereiten kann und die Freigabe trotzdem je Änderung durch einen Menschen im Archiv erfolgt.

Entschieden von Fabian am 18.11.2026 (verbindlich): (1) Ein lokales Modell darf beim Erarbeiten mit allen Quellen ausser Personendaten arbeiten; Cloud-Modelle (Copilot, Claude) nur mit Quellen der Stufe `NORMAL` ohne Personendaten, auf Verantwortung des Inhabers; `INTERN` und `PERSONAL` nie an Cloud-Modelle (sinngemäss der Entscheid vom 17.11.2026 zu Spec 02, O.3: keine Weitergabe von Archivinhalten an Cloud-Modelle ausser dem nach `tech.md` zugelassenen Modell). Das Archiv kann ausserhalb nichts erzwingen: Die Regel steht in dieser Spec und im Prüfwerkzeug als Hinweis, und der Import prüft, was prüfbar ist (19.26). (2) Quellen werden per SHA-256 zugeordnet; es gibt keinen Quellen-Export aus dem Archiv (design.md, «Abgelehnte Optionen»). (3) Übertragungsweg ist der Upload eines ZIP in der Web-App plus ein lokales Offline-Prüfwerkzeug; einen Import per API-Token oder Skript gibt es in dieser Runde nicht (design.md, «Abgelehnte Optionen»).

#### Acceptance Criteria
1. Ein Wissenspaket SOLL ein ZIP nach OKF v0.2 sein, aufgebaut wie der Export (17.1): je Seite eine Datei `<pfad>.md` mit YAML-Frontmatter und Markdown-Inhalt, dazu die Pflichtdatei `paket.yaml` im Wurzelverzeichnis. `index.md` und `log.md` SOLL das Archiv ignorieren (Hinweis `DATEI_IGNORIERT`), damit ein Export nach Requirement 17 unverändert importierbar ist; jede andere Datei lässt das Archiv nicht zu (19.6).
2. `paket.yaml` SOLL enthalten: `paket_format` (Wert `1`), `mandant` (Code des Ziel-Mandanten), `erzeugt_von` (Name des Modells oder Werkzeugs, höchstens 100 Zeichen aus `A-Z a-z 0-9 . _ / : + -` und Leerzeichen), `erzeugt_mit` (`lokal` oder `cloud`), `kontext_stufe` (`NORMAL` oder `INTERN`: die höchste Vertraulichkeit aller Unterlagen, die das Modell beim Erarbeiten gesehen hat; `PERSONAL` ist nicht zulässig), `erzeugt_am` und optional `herkunft` mit `instanz` (ID der Archiv-Instanz, aus der das Paket exportiert wurde, `archiv.wissen.instanz-id`). Fehlt ein Pflichtfeld, ist ein Wert ungültig oder nennt `mandant` einen anderen als den Mandanten der Anfrage, SOLL das Archiv das ganze Paket ablehnen (19.7).
3. Das Frontmatter jeder Seite SOLL die Felder von 17.1 (`type`, `title`, `description`, `tags`, `stale_after`) sowie `basis_version` (Ganzzahl, nur bei Änderungen einer bestehenden Seite), optional `erzeugt_von` (Form wie in 19.2; sonst gilt der Wert aus `paket.yaml`) und `sources` tragen. Jeder Eintrag in `sources` SOLL einen Schlüssel `id` (Fussnoten-Label, 2.5) haben (bei Seiten vom Typ `Quelle` genau ein Dokument-Eintrag zusätzlich mit `wissensquelle: true`, 2.3) und entweder die Angaben `sha256` (64 Hexzeichen: SHA-256 der archivierten Originaldatei der Wissensquelle), `seite` (Einzelseite `n` oder Bereich `n-m` der PDF/A-Fassung) und `auszug` (wörtlich, höchstens 300 Zeichen) oder `resource` mit dem Pfad einer Wissensseite (`/konzepte/x.md`, wie 17.2). Eine Archiv-ID (`urn:urinet-archiv:…`) gilt im Paket nicht als Verweis, weil IDs nur innerhalb einer Instanz gelten (entschieden von Fabian am 18.11.2026, Entscheid 2).
4. Felder, die nur ein Mensch im Archiv oder die Berechnung setzt (`verified`, `generated`, `status`, nach 7.6), SOLL das Archiv aus dem Paket verwerfen und mit dem Hinweis `FELD_IGNORIERT` melden; eine neue Seite startet mit Status `AKTIV`. Als erzeugt gilt der Akteur `extern:<erzeugt_von>` (19.20), nie ein Mensch und nie ein KI-Akteur des Archivs.
5. Ein Benutzer mit Recht A17 SOLL ein Paket importieren können; jeder andere Benutzer erhält HTTP 403 (404 vor 403 wie Spec 01, 2.3). Es gibt keine neue Zeile in der Rechtematrix: A17 deckt «Seiten von Hand ändern» (6.9) und das Annehmen ab, und ein Import liefert nur Entwürfe in demselben Rang wie ein manueller Entwurf. A16 (Ingest starten) passt nicht, weil der Import weder eine Wissensquelle als Auslöser noch einen KI-Aufruf hat; A19 ist nicht nötig, weil der Import nichts aus der Wissensbasis herausliest (A19 betrifft Einstellungen und OKF-Export). Der Endpunkt SOLL nur OIDC-Benutzer zulassen; API-Tokens erhalten ihn nicht (wie bei den übrigen Wissens-Endpunkten, tasks.md, Aufgabe 13.6). Das Archiv SOLL den Entwurf mit Anlass `IMPORT` anlegen.
6. Das Archiv SOLL das Paket vor jeder Auswertung strikt begrenzen (Startwerte `archiv.wissen.import.*`, je Mandant nicht änderbar): ZIP höchstens 10 MiB, höchstens 200 Seitendateien (insgesamt höchstens 205 Einträge), entpackt höchstens 20 MiB, Einzeldatei höchstens 128 KiB, höchstens 4 Pfadebenen, Kompressionsverhältnis je Datei höchstens 100:1 und beim Entpacken ein Zählen der gelesenen Bytes statt der Angabe im Kopf des ZIP (Schutz vor Zip-Bomben). Zulässig sind nur Dateien mit der Endung `.md` und `paket.yaml`; Pfade müssen nach Normalisierung relativ und ohne `..`, Laufwerksangabe, Backslash, NUL und Steuerzeichen sein, dürfen nach der Zerlegung nicht aus dem Paketwurzelverzeichnis führen (Zip-Slip) und müssen zu den Namensregeln von 2.1 passen; Symlinks, Verzeichniseinträge mit Inhalt, verschlüsselte Einträge, doppelte Namen (auch nach Normalisierung) und ZIP-Erweiterungen mit mehreren Archivteilen sind unzulässig. Das Archiv SOLL Inhalte nie ausführen und nie auf das Dateisystem entpacken, sondern nur im Speicher lesen.
7. FALLS eine Grenze nach 19.6, das Format nach 19.1 bis 19.3 oder 19.8 verletzt ist, DANN SOLL das Archiv das ganze Paket mit HTTP 422 `PAKET_UNGUELTIG` ablehnen (Problem Details mit Liste von Fehlercodes und Pfaden, ohne Inhalte), nichts speichern und keinen Entwurf anlegen; bei einem ZIP über der Grösse HTTP 413 `DATEI_ZU_GROSS`, bei einer Datei, die kein ZIP ist (Prüfung am Inhalt, nicht am Namen), HTTP 415 `DATEITYP_UNBEKANNT`.
8. Das Frontmatter SOLL das Archiv mit einem YAML-Parser lesen, der nur die sichere Teilmenge zulässt: ein Dokument, Mapping, Liste und Skalare (Text, Zahl, Wahrheitswert, Datum), keine Tags (`!!`, `!`), Anker und Aliase, Merge-Schlüssel und mehrere Dokumente, keine Objekterzeugung, Verschachtelung höchstens 5 Ebenen, Frontmatter höchstens 16 KiB. Schlüssel ausserhalb der Liste von 19.2 und 19.3 SOLL es mit dem Hinweis `FELD_UNBEKANNT` verwerfen. Den Inhalt SOLL es mit demselben Markdown-Parser wie in 2.x lesen: kein HTML, keine externen Links, keine Bilder, keine Wikilinks (Befund `LINK_UNGUELTIG`, 3.7).
9. Jede Seitendatei SOLL genau eine Änderung desselben Entwurfs ergeben, bestimmt aus Pfad und Basisversion: Gibt es den Pfad für den importierenden Benutzer nicht, ist die Aktion `NEU` (`basis_version` darf dann fehlen oder `0` sein); gibt es ihn und ist er sichtbar (11.2), ist die Aktion `AENDERN`; belegt eine für den Benutzer unsichtbare Seite höherer Vertraulichkeit den Pfad, SOLL das Archiv wie bei 5.12 den Befund `PFAD_UNGUELTIG` und den Hinweis `HOEHERE_STUFE_VORHANDEN` setzen und nichts über diese Seite preisgeben. Eine Seite, deren Felder und Inhalt der aktuellen Version entsprechen, SOLL das Archiv als «unverändert» zählen und keine Änderung daraus machen, damit ein Export einer lokalen Instanz nur die wirklich neuen oder geänderten Seiten zeigt. Pakete SOLLEN keine Änderung `VERALTEN` enthalten können.
10. Bei `AENDERN` SOLL die Basisversion der Änderung die Version sein, auf der das Paket beruht: Stammt das Paket aus derselben Instanz (`herkunft.instanz` gleich `archiv.wissen.instanz-id`), gilt `basis_version` aus dem Frontmatter; fehlt sie oder stammt das Paket aus einer anderen Instanz, gilt die aktuelle Version zum Zeitpunkt des Imports mit dem Hinweis `BASIS_UNBEKANNT`. FALLS `basis_version` von der aktuellen Version abweicht, DANN SOLL das Archiv die Änderung mit Status `VERALTET` anlegen wie bei 6.6, mit der Meldung, das Paket gegen den aktuellen Stand neu zu erarbeiten.
11. Das Archiv SOLL ein Paket nie automatisch annehmen: Der Import SOLL keine freigegebene Seite, keinen Index und kein Log ändern (wie 5.8), und es gibt kein gesammeltes Annehmen (6.4); jede Änderung nimmt ein Mensch mit Recht A17 einzeln nach angesehenem Diff an, ändert sie oder verwirft sie. Das Archiv SOLL keine KI-Aufrufe zum Beheben von Befunden anbieten.
12. Das Archiv SOLL jede Änderung des Imports mit der vollen `WissensPruefung` nach 6.2 prüfen (Felder, Pfad, Fussnoten, Aussagen ohne Beleg nach 2.6, Quelleneinträge, `PERSONAL`, Links, kein HTML, Grösse, `STUFE_UEBERBLICK`) und beim Annehmen erneut (6.3). Befunde SOLLEN den Import nicht verhindern, sondern an der Änderung stehen; die Änderung ist erst nach Korrektur annehmbar (HTTP 422 `ENTWURF_BEFUND`).
13. Die Quellen SOLL das Archiv per SHA-256 zuordnen (entschieden von Fabian am 18.11.2026, Entscheid 2): Eine Dokument-Quelle gilt nur, wenn ihre `sha256` der Prüfsumme der archivierten Originaldatei (`dokument_datei`) genau eines Dokuments der Art `WISSENSQUELLE` im Mandanten der Anfrage entspricht, das `ARCHIVIERT` und für den Benutzer sichtbar ist (11.2, Dublettenprüfung nach Spec 01, 3.4). Die Auflösung SOLL nur im Schema des Mandanten der Anfrage laufen; ein Dokument eines anderen Mandanten ist nie auflösbar.
14. FALLS die Zuordnung nach 19.13 scheitert, DANN SOLL das Archiv an der Änderung Befunde setzen, die sperren: `QUELLE_UNBEKANNT` (Prüfsumme gehört zu keinem Dokument der Art `WISSENSQUELLE`, ist archiviert nicht vorhanden oder für den Benutzer unsichtbar; das Archiv unterscheidet diese Fälle nicht, damit es nichts über andere Dokumente preisgibt; die fehlende Quelle ist zuerst über den normalen Weg hochzuladen und als `WISSENSQUELLE` zu bestätigen) und `QUELLE_PERSONAL` (zugeordnetes Dokument hat die Vertraulichkeit `PERSONAL`, nach 2.7). Eine Prüfsumme, die mehr als einem Dokument entspricht, SOLL den Befund `QUELLE_UNGUELTIG` ergeben. Das Archiv SOLL die Prüfsumme beim Annehmen erneut auflösen (6.3), und ein Benutzer mit Recht A17 SOLL die Prüfung eines Import-Entwurfs ohne KI wiederholen können (`neu-pruefen`), z. B. nachdem er die fehlende Quelle hochgeladen hat.
15. Für jeden Quelleneintrag mit Dokument SOLL das Archiv eine Auszugsprüfung ausführen: Der `auszug` muss auf der angegebenen PDF-Seite (bzw. im Bereich) des Volltexts der zugeordneten Wissensquelle vorkommen, normalisiert nach 6.2 (Leerraum, Silbentrennung, Unicode NFKC; Befund `AUSZUG_NICHT_GEFUNDEN`). Das schützt davor, dass ein Sprachmodell Zitate erfindet. Im Paket ist der `auszug` Pflicht (fehlt er: Befund `AUSZUG_FEHLT`), und die Seitenzahl muss innerhalb der Seitenzahl des Dokuments liegen (Befund `QUELLE_UNGUELTIG`). FALLS die Wissensquelle keinen Volltext, keine PDF/A-Fassung oder eine falsche Zahl von `\f`-Seitentrennern hat (5.6), DANN SOLL das Archiv den Befund `AUSZUG_NICHT_PRUEFBAR` setzen.
16. Die Vertraulichkeit SOLL das Archiv aus der Hülle der zugeordneten Quellen berechnen (11.1), nie aus dem Paket, und sie SOLL nie niedriger sein als die der Quelle. Die Kontextstufe der Änderung (11.7) SOLL das Maximum aus der Hülle und `kontext_stufe` aus `paket.yaml` sein, damit das Paket die Stufe nur erhöhen kann; ein Paket, das eine tiefere Stufe angibt als die Hülle, SOLL das Archiv mit dem Hinweis `STUFE_IGNORIERT` übergehen. Mit der Annahme geht die Kontextstufe in die Mindeststufe der Seite ein (11.1); die Annahme einer Seite von `NORMAL` auf `INTERN` zeigt den Hinweis `STUFE_STEIGT` (6.8).
17. FALLS dasselbe Paket (gleiche SHA-256 des ZIP) im Mandanten schon importiert wurde, DANN SOLL das Archiv keinen zweiten Entwurf anlegen, sondern mit HTTP 409 `STATUS_KONFLIKT` und der ID des vorhandenen Entwurfs antworten (Idempotenz, wie Spec 01, 11.8). Je Mandant SOLL es höchstens `archiv.wissen.import.max-offen` Entwürfe mit Anlass `IMPORT` im Status `OFFEN` zulassen (Startwert 5), weitere Importe mit HTTP 409 `STATUS_KONFLIKT`. Import-Entwürfe zählen nicht zur Begrenzung auf einen Ingest-Entwurf je Mandant (5.13).
18. Das Archiv SOLL den Import in einer Transaktion ausführen: Entwurf mit Anlass `IMPORT`, Änderungen mit Diff zur Basisversion, Befunden und Hinweisen, Kontextstufe, Prüfsumme des ZIP, `erzeugt_von`, `erzeugt_mit`, `kontext_stufe` und der Zahl der unveränderten und ignorierten Dateien; schlägt ein Schritt fehl, SOLL nichts gespeichert sein. Der Entwurf SOLL zum Zeitpunkt der Antwort `OFFEN` sein; die Verarbeitung SOLL innerhalb von 60 Sekunden enden (Messpunkt Server), sonst bricht das Archiv ohne Entwurf mit `PAKET_UNGUELTIG` und dem Grund `ZEIT` ab.
19. Der Import SOLL kein Sprachmodell, keine Embeddings und keinen Reranker aufrufen und weder `ki_verbrauch` noch eines der Budgets nach 13.4 bis 13.6 belasten; die Embeddings entstehen wie bei jeder Freigabe erst mit dem Annehmen (6.5, `WISSEN_EMBEDDING`).
20. Das Archiv SOLL den Import im Zugriffsprotokoll festhalten (Aktion `WISSEN_IMPORT`: Benutzer, Zeit, Entwurf, SHA-256 des ZIP, Zahl der Seiten; synchron in derselben Transaktion, Spec 01, 10.8; bei 403/404 `VERWEIGERT` nach 14.4). Jede aus dem Import angenommene Version SOLL als erzeugt den Akteur `extern:<erzeugt_von>` tragen (bei geänderter Fassung `human:<benutzer>`, 6.5) und zu ihr SOLL das Archiv Entwurf, Anlass `IMPORT`, Prüfsumme des ZIP, `erzeugt_mit` und Importierenden anzeigen können (14.5). Zugriffsprotokoll, Logs und CI-Ausgaben SOLLEN nur IDs, Codes und Pfade enthalten, nie Inhalte (13.9, 14.6).
21. Der Import SOLL den Inhalt des Pakets als Daten behandeln (11.6): Anweisungen darin können nur den Inhalt der Änderungen eines Entwurfs bestimmen, nie freigegebene Seiten, Rechte, Felder nach 7.6, Befunde oder Werkzeuge. Das Archiv ruft dafür keine KI auf (19.19), und das Archiv-Chat-Werkzeug SOLL Entwürfe nie liefern (8.6).
22. WENN alle Änderungen eines Import-Entwurfs entschieden sind, DANN SOLL das Archiv den Entwurf abschliessen und einen Logeintrag `IMPORT` mit den angenommenen Seiten schreiben (wie 6.10); `ERLEDIGT`, wenn mindestens eine Änderung angenommen wurde, sonst `ABGELEHNT`.
23. Im Profil `lokal` (ADR-012) SOLL das Erarbeiten mit dem lokalen Modell über Ingest und Lint nach 13.2 dasselbe Paketformat liefern: Der OKF-Export einer lokalen Instanz (Requirement 17, A19) SOLL nach 17.2 als Wissenspaket importierbar sein, mit `erzeugt_mit: lokal`, `kontext_stufe` = höchste wirksame Stufe aller exportierten Seiten und `herkunft.instanz`. Weg A (lokale Archiv-Instanz, Export, Import) und Weg B (beliebige Werkzeuge schreiben OKF-Markdown in einen Ordner, Prüfwerkzeug, ZIP, Import) SOLLEN dasselbe Paketformat und denselben Import verwenden (design.md, «Lokale Erarbeitung und Import»).
24. Ein lokales Modell SOLL beim Erarbeiten Quellen jeder Stufe ausser `PERSONAL` und ausser Personendaten sehen dürfen, wenn es auf dem eigenen Gerät läuft und keine Daten das Gerät verlassen (ADR-012). Der Export einer lokalen Instanz und das Prüfwerkzeug SOLLEN die Regel zu Cloud-Modellen nach 19.25 als Hinweis ausgeben.
25. Für Cloud-Modelle (Copilot, Claude, jedes Modell ausserhalb des Geräts) SOLL gelten (entschieden von Fabian am 18.11.2026, Entscheid 1): Der Inhaber darf ihnen Quellen der Stufe `NORMAL` nur zeigen, wenn sie keine Personendaten enthalten, auf seine Verantwortung; Quellen der Stufe `INTERN` oder `PERSONAL` und alles, was daraus abgeleitet ist, nie. Diese Regel SOLL als Anforderung an den Prozess in `docs/wissen-lokal-erarbeiten.md` und in der Ausgabe des Prüfwerkzeugs stehen; das Archiv kann sie ausserhalb nicht erzwingen. Nachweis: Dokument.
26. Soweit prüfbar, SOLL der Import die Regel nach 19.25 anwenden: Nennt `paket.yaml` `erzeugt_mit: cloud` und `kontext_stufe: INTERN`, SOLL das Archiv das ganze Paket ablehnen (19.7, Grund `CLOUD_STUFE`); hat bei `erzeugt_mit: cloud` die Hülle einer zugeordneten Quelle die Stufe `INTERN`, SOLL der Befund `CLOUD_STUFE_UNZULAESSIG` die Änderung sperren. Die Angaben sind Selbstauskünfte des Erstellers; das Archiv hält `erzeugt_mit` und `kontext_stufe` mit dem Entwurf fest (19.18), damit die Herkunft nachvollziehbar bleibt, und behauptet nicht, die Regel sei damit eingehalten.
27. Das Prüfwerkzeug `wissen_paket` (`tools/wissen_paket`, Java wie `tools/drive_import`) SOLL ein Paket oder einen Ordner mit OKF-Markdown vor dem Upload offline prüfen und bauen: `validate` prüft Format, Grenzen, Frontmatter, Markdown und die Regeln von `WissensPruefung`, soweit sie ohne Archiv prüfbar sind, und `bauen` erzeugt aus einem Ordner das ZIP mit `paket.yaml`. Es SOLL dieselben Regeln wie der Import verwenden (gleiche Regeln, nicht ähnliche; design.md, «Prüfwerkzeug»), kein Netz und keine Zugangsdaten brauchen, nie Inhalte ausführen, Eingaben nur lesen, nur das angegebene Ausgabe-ZIP schreiben (nicht überschreiben ohne `--ueberschreiben`) und den Bericht nur mit Befund-Codes und Pfaden (ohne Inhalte) ausgeben; der Rückgabewert SOLL 0 sein, wenn kein sperrender Befund vorliegt, sonst 1. Es SOLL mit der Option `--quellen <ordner>` die Prüfsummen lokaler Originaldateien ausrechnen, `sha256` im Paket gegen sie abgleichen und, soweit der Volltext lokal bestimmbar ist, Auszüge vorprüfen (Hinweis `AUSZUG_LOKAL_NICHT_GEFUNDEN`, nicht massgeblich). Das Werkzeug ist eine Hilfe, keine Sicherheitsgrenze: Das Archiv prüft jedes Paket beim Import erneut. Nachweis: JUnit (Gleichheitstest der Regeln, 19.29).
28. Die App SOLL Benutzern mit Recht A17 unter `/wissen/entwuerfe/import` einen Upload für das ZIP bieten, nach dem Import den Prüfbericht (Zahl der Seiten `NEU`, `AENDERN`, unverändert, ignoriert, Befunde je Seite mit Code und Pfad) und einen Link zum Entwurf zeigen. Die Entwurfsansicht SOLL bei Anlass `IMPORT` zusätzlich Herkunft (`erzeugt_von`, `erzeugt_mit`, `kontext_stufe`, Prüfsumme des ZIP, Zeit, Importierender) und je Quelle das Ergebnis der Zuordnung und der Auszugsprüfung zeigen, die Regel zu Cloud-Modellen (19.25) als Hinweis tragen und die Aktion «Prüfung wiederholen» bieten. Die Anforderungen aus 16.3, 16.4 und 16.7 gelten unverändert.
29. Abnahme: Die Tests SOLLEN nachweisen: ein gültiges Paket ergibt einen Entwurf `IMPORT` mit richtigen Aktionen `NEU`, `AENDERN` und «unverändert»; Paket zweimal → HTTP 409 mit der ID des Entwurfs; unbekannte `sha256` → `QUELLE_UNBEKANNT`, nach Hochladen der Quelle und «Prüfung wiederholen» ohne Befund; Quelle `PERSONAL` → `QUELLE_PERSONAL`; erfundener Auszug → `AUSZUG_NICHT_GEFUNDEN`; fehlender Auszug → `AUSZUG_FEHLT`; Prüfsumme eines Dokuments eines anderen Mandanten → `QUELLE_UNBEKANNT`; Paket mit tieferer Stufe als die Quelle → Stufe der Quelle; Zip-Slip, Symlink, Zip-Bombe, doppelte Namen, Entpackgrösse, unbekannte Dateitypen, YAML mit Tag, Anker und Alias, Rohhtml und externer Link → abgelehnt bzw. Befund; `mitarbeiter` und `treuhaender` → 403; API-Token → 403; `erzeugt_mit: cloud` mit `kontext_stufe: INTERN` → abgelehnt; ein Export einer Instanz mit Profil `lokal` ist importierbar; das Prüfwerkzeug und der Import kommen auf allen Beispielpaketen zum selben Ergebnis (Gleichheitstest); die Logs enthalten keine Inhalte. Zusätzlich SOLL der Isolationstest (11.5) den Import umfassen.

## Optionen (nicht Teil des Plans, Aufgaben mit `*`)
- Wissensseiten für Verbandsmitglieder von VHF/GSK über die öffentliche Schnittstelle und den Chatbot (ändert Spec 03, 6.1).
- ~~Import eines bestehenden OKF-Bundles (z. B. eines LLM-Wikis) als Entwurf mit Freigabe.~~ **Übernommen in den Plan als Requirement 19** (entschieden von Fabian am 18.11.2026; Aufgaben 15.6 bis 15.8, tasks.md, Option O.5). Zugelassen sind Pakete im OKF-Format mit Quellen über SHA-256 (19.3); Quellen ohne zuordenbares Archivdokument sind Befund `QUELLE_UNBEKANNT` (19.14).
- Werkzeuge der Wissensbasis im MCP-Server (Spec 02, Option C3), nur lesend — entfällt (folgt aus Spec 02, O.3, Entscheid 18.11.2026).
- KI-Vorschlag der Art `WISSENSQUELLE` (Spec 01, Requirement 7), erst nach einem erneuten Lauf des Golden-Datensatzes mit den Schwellen aus Gate 0; ein solcher Vorschlag gälte nie als «sicher».
- Zweite Synthese-Seite für Wissen aus `INTERN`-Quellen (z. B. `ueberblick-intern`), nur bei Bedarf, falls die Synthese nur aus `NORMAL`-Quellen (11.8) nicht genügt (entschieden von Fabian am 09.11.2026).
- Ingest auch für archivierte Dokumente anderer Arten über «Zur Wissensbasis hinzufügen» (vorerst nicht, entschieden von Fabian am 09.11.2026; das Design ist vorbereitet, design.md, Open Points).

## Rückverfolgung Konzept → Spec
| Konzept | Requirement |
| --- | --- |
| ohne Konzept-ID (Auftrag 09.11.2026, Wissensbasis) | 1–7, 10–13, 15–17 |
| ohne Konzept-ID (Option O.5 «Import eines OKF-Bundles als Entwurf», Auftrag 18.11.2026: lokale Erarbeitung und Import) | 19 |
| Erweiterung C1, C2 (Chat, Quellen) | 8 |
| Erweiterung S1, S2 (Suche) | 9 |
| Erweiterung B2 (Protokoll) | 14 |
| Gate Phase 4 | 18 |
