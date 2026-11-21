Unter «Dokumentarten» pflegen Sie Dokumentarten, Klassifikationen und Regeln Ihres Mandanten. Dokumentarten steuern Metadaten, Aufbewahrung, Vertraulichkeit und KI-Vorschläge. Jede Änderung an der Konfiguration erzeugt eine neue Version. Oben auf der Seite springen Sie zu «Dokumentarten», «Klassifikationen», «Regeln», «Versionen» und «Konfigurationsdatei».

## Dokumentart anlegen oder ändern
1. Wählen Sie die Art in der Liste «Dokumentarten des Mandanten» oder wählen Sie «Neue Dokumentart».
2. Füllen Sie die Angaben aus, zum Beispiel Name, Gruppen, «Pflichtfelder des Dokuments», die Standard-«Vertraulichkeit», «Aufbewahrung in Jahren» oder «Unbefristet», «Aufbewahrung ab» und «Gesetzliche Aufbewahrungspflicht». Den Code legen Sie nur beim Anlegen fest.
3. Geben Sie bei Bedarf einen «Änderungsgrund» ein. Er ist freiwillig und wird protokolliert.
4. Wählen Sie «Änderungen speichern». Bei einer bestehenden Art fragt die App nach, weil Änderungen bestehende Dokumente betreffen können; wählen Sie dann erneut «Änderungen speichern» oder «Abbrechen».

Eine geänderte Aufbewahrung gilt für neue Dokumente und für jede Neuberechnung einer Frist. Bestehende Sperren verlängert das Archiv nur, es verkürzt sie nie. Die alte und die neue Regel stehen im [Zugriffsprotokoll](/verwaltung/protokoll).

> **Achtung:** Prüfen Sie Aufbewahrung und «Gesetzliche Aufbewahrungspflicht» sorgfältig. Das Archiv verhindert auch eine kürzere Aufbewahrung nicht, es protokolliert sie nur.

## Zusatzfelder
Im Teil «Zusatzfelder» legen Sie die Felder fest, die eine Art zusätzlich hat.

1. Wählen Sie «Zusatzfeld hinzufügen».
2. Geben Sie Feldcode, Name und Typ an: «Text», «Ganzzahl», «Dezimalzahl», «Datum», «Ja/Nein», «Auswahl», «Mehrfachauswahl» oder «Liste von Texten». Für eine Auswahl ergänzen Sie die «Werteliste» mit «Wert hinzufügen».
3. Markieren Sie bei Bedarf «Pflicht» und ergänzen Sie eine Beschreibung.

Feldcodes müssen eindeutig sein und mit einem Kleinbuchstaben beginnen. Enthält das Schema einer Art Eigenschaften, die der Feld-Editor nicht darstellen kann, zeigt die App es als «Zusatz-Schema (JSON)»; dann ändern Sie es nur dort.

## Löschen oder deaktivieren
- Wählen Sie «Löschen» und bestätigen Sie «Wirklich löschen?» mit «Löschen».
- Hat die Art noch Dokumente, lehnt das Archiv das Löschen ab. Entfernen Sie dann das Häkchen bei «Aktiv» und speichern Sie. Eine inaktive Art bleibt bei ihren Dokumenten erhalten.

## Klassifikationen
Klassifikationen ordnen Dokumente und stellen Filterwerte bereit.

1. Wählen Sie eine Klassifikation oder «Neue Klassifikation».
2. Legen Sie fest, ob ein Dokument mehrere Werte haben darf, ob die KI Werte vorschlagen darf und ob die Klassifikation als Suchfilter oder öffentlich filterbar dient.
3. Wählen Sie «Änderungen speichern».
4. Die Werte pflegen Sie, sobald die Klassifikation gespeichert ist: Unter «Werte» geben Sie Wertcode und Name ein und wählen «Wert speichern». «Bearbeiten» ändert einen Wert, «Wert löschen» entfernt ihn.

Welche Klassifikationen zu einer Art gehören, legen Sie bei der Dokumentart unter «Klassifikationen dieser Dokumentart» mit «Klassifikation zuordnen» fest, auf Wunsch mit «Pflicht» und «Standardwert».

## Regeln
Regeln prüfen Metadaten und melden Verstösse oder fehlende Werte.

1. Wählen Sie eine Regel oder «Neue Regel».
2. Wählen Sie die Dokumentart (leer gilt für alle), den Regeltyp («Pflicht wenn», «Eindeutig», «Folge» oder «Erwartet»), die Parameter als JSON, die Schwere («Fehler» oder «Warnung») und die Meldung.
3. Wählen Sie «Änderungen speichern». Die App meldet danach, wie viele bestätigte Dokumente gegen die Regel verstossen.

## Versionen vergleichen
Unter «Versionen» steht jede Version der Konfiguration mit Zeitpunkt, Person, Grund und Quelle. Versionen lassen sich nicht ändern.

1. Wählen Sie eine «Ältere Version» und eine «Neuere Version».
2. Wählen Sie «Versionen vergleichen». Die App zeigt je Änderung den Pfad, den Wert vorher und nachher.

## Konfigurationsdatei
Mit einer Konfigurationsdatei sichern Sie die ganze Konfiguration oder übernehmen eine vorbereitete.

1. «Aktuelle Konfiguration exportieren» speichert die Konfiguration als JSON-Datei.
2. Zum Einspielen wählen Sie «Datei auswählen» (JSON, höchstens 1 MiB) und danach «Datei prüfen» und «Mit aktuellem Stand vergleichen». Die Vorschau zeigt Befunde, Änderungen und die Zahl der betroffenen Dokumente.
3. Wählen Sie «Konfiguration einspielen» und danach «Einspielen verbindlich bestätigen».

Das Archiv übernimmt alle Änderungen der Datei oder keine. Beruht die Datei auf einer älteren Version als der aktuellen, wird sie nicht eingespielt; prüfen und vergleichen Sie sie dann erneut.

## Häufige Meldungen
- «Die Konfiguration wurde inzwischen geändert.»: Ihre Eingabe bleibt erhalten. Wählen Sie «Neu laden», prüfen Sie den aktuellen Stand und wiederholen Sie die Änderung.
- «Dieser Eintrag wird noch von Dokumenten verwendet …»: Deaktivieren Sie ihn, statt ihn zu löschen.
- «Dieser Code ist bereits vergeben.»: Wählen Sie einen anderen Code.
- «Der Änderungsgrund enthält Zeichen, die nicht übertragen werden können …»: Ersetzen Sie Zeichen wie den langen Gedankenstrich oder das Euro-Zeichen.
