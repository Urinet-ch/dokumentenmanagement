# Checkliste: Wiederholung des Test-Ingests

Vorbereitung für Aufgabe 19.6 (Spec 04). Den Lauf und den Vergleich führt ausschliesslich
Fabian nach Abschluss von 19.1 bis 19.5 durch. Diese Checkliste ist kein Abnahmenachweis.

## Vorbereitung

- [ ] Abschluss und Bereitstellung von 19.1 bis 19.5 prüfen. 19.4 und 19.5 sind umgesetzt;
      ihre Prüfpunkte 5 und 6 bleiben bis zur manuellen Prüfung offen.
- [ ] Den Befund vom 26.11.2026 für den persönlichen Vergleich bereithalten. Quelldokument,
      Auszüge, Aussagen und Antworten bleiben ausserhalb des Repositorys.
- [ ] Laufumgebung wählen: lokal oder Stufe A. Auf Stufe A ausschliesslich Testdaten oder
      anonymisierte Daten verwenden; andernfalls den Lauf lokal durchführen.
      Das lokale Modell nach ADR-012 ist nur für Entwicklung und Prompt-Vorarbeit
      zugelassen, nicht für Abnahmen oder als Gate-Nachweis. Für eine Abnahme auch
      bei lokal laufendem Archiv keinen Ingest mit dem lokalen Modell verwenden.
- [ ] Für die Prüfung von Fehlerbehandlung und Vertraulichkeit synthetische Daten vorbereiten.
      Keine echten Inhalte in Testdaten, Protokoll, Screenshots oder PR-Kommentare übernehmen.

## Ablauf

1. Fabian ingestiert das Quelldokument des Befunds erneut und prüft den erzeugten Entwurf
   vor einer Bearbeitung anhand der folgenden Punkte gegen die Auswertung vom 26.11.2026.
2. Fehlerbehandlung und Vertraulichkeit zusätzlich mit synthetischen Daten prüfen, soweit
   dafür ein gezielt ausgelöster Fehler oder Vertraulichkeitsvermerk nötig ist.
3. Nur Nummern, Status, Anzahlen und Zeiten protokollieren. Das Ergebnis von 19.6 gehört
   gemäss Aufgabenplan in das vorgesehene Abnahmeprotokoll, ohne Inhalte des Quelldokuments.
4. Bei einem fehlgeschlagenen oder nicht prüfbaren Punkt den Status entsprechend setzen;
   nicht geprüfte Punkte sind kein Erfolg. Fabian entscheidet über die Abnahme.

## Prüfpunkte

| Nr. | Prüfung |
| ---: | --- |
| 1 | Keine Aussage gilt allein aufgrund eines Kopf- oder Fusszeilen-Auszugs als belegt. |
| 2 | Tabellenzeilen bleiben mit ihren zugehörigen Spalten und Bedingungen erhalten; keine Spalten sind verschoben. |
| 3 | Offene Fragen bleiben Fragen; erfundene Antworten erscheinen nicht als Aussagen. |
| 4 | Bedingungen und Einschränkungen von Optionen bleiben erhalten; die Optionentabelle wird zeilenweise wiedergegeben. |
| 5 | **19.4:** Ausgefallene oder überwiegend ausgefallene Abschnitte sind als Befund sichtbar und können nur mit Recht A17 abschnittsweise erneut verarbeitet werden; das Ergebnis der Wiederholung ist sichtbar. |
| 6 | **19.5:** Gruppierung und Vertraulichkeitsvorschlag prüfen; bei synthetischem Vertraulichkeitsvermerk dürfen betroffene Wissensseiten nicht für Webseite oder Mitglieder freigegeben werden. |

## Protokollvorlage

Status: `offen`, `bestanden`, `nicht bestanden` oder `nicht prüfbar`. Zeit und Dauer ohne
Namen, Adressen, Dokument- oder Entwurfs-IDs erfassen; Inhalte bleiben ausserhalb des Repositorys.

| Nr. | Status | Anzahl geprüft | Anzahl Fehler | Zeit / Dauer |
| ---: | --- | ---: | ---: | --- |
| 1 | offen | | | |
| 2 | offen | | | |
| 3 | offen | | | |
| 4 | offen | | | |
| 5 | offen | | | |
| 6 | offen | | | |
