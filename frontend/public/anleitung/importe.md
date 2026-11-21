Unter «Importe» übernehmen Sie Kontakte und Verkaufschancen aus 21.CRM als CSV-Datei ins Archiv. Das Archiv zeigt zuerst eine Vorschau und übernimmt erst, wenn Sie es bestätigen. Ein Import löscht nie einen Kontakt.

## Datei hochladen
1. Exportieren Sie in 21.CRM die Kontakte oder die Verkaufschancen als CSV-Datei. Trennzeichen (Semikolon oder Komma) und Zeichensatz (UTF-8 oder Windows-1252) erkennt das Archiv selbst.
2. Öffnen Sie in der Seitenleiste «Importe».
3. Wählen Sie unter «Importart» «Kontakte» oder «Verkaufschancen».
4. Nur bei Kontakten: Setzen Sie das Häkchen bei «Vollexport (alle Kontakte aus 21.CRM)», wenn die Datei alle Kontakte aus 21.CRM enthält (siehe «Vollexport» unten).
5. Wählen Sie «CSV-Datei wählen» und danach «Hochladen».
6. Die App öffnet den neuen Import. Alle Importe finden Sie später unter «Bisherige Importe», mit Datei, Importart, Zahl der Datenzeilen, Zeitpunkt und Status.

> **Hinweis:** Eine Datei darf höchstens 5 MiB gross sein und höchstens 10'000 Datenzeilen haben. Ist sie grösser, exportieren Sie in 21.CRM kleinere Teile, zum Beispiel nach Kategorie.

## Spalten zuordnen
Ist die Kopfzeile neu oder weicht sie von der gespeicherten Zuordnung ab, zeigt der Import «Zuordnung nötig» und «Spalten zuordnen».

1. Wählen Sie für jede Spalte das passende Feld oder «– nicht zuordnen –». Jedes Feld lässt sich höchstens einmal zuordnen; unter jeder Spalte steht ein Beispielwert aus der Datei.
2. Bei Kontakten können Sie «Profil ‹21.CRM› verwenden» wählen. Das Profil ordnet die Spalten des 21.CRM-Exports zu. Entfernen Sie danach ein Feld, gilt das Profil nicht mehr.
3. Bei Kontakten muss «Name» oder «Organisation» zugeordnet sein, bei Verkaufschancen «Titel».
4. Wählen Sie «Zuordnung bestätigen».

Die Zuordnung gilt danach für weitere Dateien derselben Importart. Lädt das Archiv eine Datei mit bekannter Kopfzeile, folgt gleich die Vorschau.

## Vorschau prüfen
Während das Archiv die Zeilen abgleicht, steht dort «Vorschau wird berechnet»; die Vorschau erscheint automatisch. Noch ist dann nichts übernommen.

- Die Zahlen oben zeigen, wie viele Zeilen «neu», «geändert», «unverändert», «mögliche Dublette», «fehlerhaft», «übersprungen» oder «inaktiv» sind. Ein Klick auf eine Zahl zeigt nur diese Zeilen, «Alle» wieder alle.
- Jede Zeile zeigt die Werte in der Datei und, falls vorhanden, den Kontakt im Archiv, den sie trifft («Trifft Kontakt»), oder die möglichen Kontakte.
- Das Archiv sucht den passenden Kontakt zuerst über die Kontakt-ID aus 21.CRM, dann über die E-Mail-Adresse und zuletzt über Name und PLZ.
- «fehlerhaft» heisst zum Beispiel, dass einer Zeile der Name fehlt. Solche Zeilen übernimmt das Archiv nicht.
- «inaktiv» heisst, dass die Zeile nur Kontakte trifft, die im Archiv als inaktiv markiert sind. Ein Import ändert inaktive Kontakte nie und legt für sie keinen neuen Kontakt an.

## Mögliche Dubletten entscheiden
Passt eine Zeile zu mehreren Kontakten oder nur ungefähr, ist sie eine «mögliche Dublette». Das Archiv überschreibt dann nichts von sich aus.

1. Wählen Sie bei Kontakten für jede mögliche Dublette unter «Entscheid» «Als neuen Kontakt anlegen», «Überspringen» oder «Zusammenführen mit» einem der genannten Kontakte.
2. Lassen Sie den Entscheid auf «– noch offen (wird ausgelassen) –», wenn Sie unsicher sind. Diese Zeile übernimmt das Archiv dann nicht.

Doppelte Verkaufschancen in derselben Datei lässt das Archiv aus.

## Import übernehmen
1. Wählen Sie «Übernehmen …».
2. Die Frage «Import übernehmen?» nennt, wie viele Einträge das Archiv neu anlegt und wie viele es aktualisiert, dazu die Folgen Ihrer Entscheide und des Vollexports. Werte aus der Datei gewinnen, leere Zellen überschreiben nichts.
3. Wählen Sie «Jetzt übernehmen» oder «Abbrechen».
4. Die Übernahme läuft im Hintergrund. Danach zeigt der Import «Übernommen» mit der Zahl der angelegten, aktualisierten, ausgelassenen und gekennzeichneten Einträge.

Die übernommenen Kontakte finden Sie unter [Kontakte](/kontakte). Verkaufschancen ordnet das Archiv über die Kontakt-ID aus 21.CRM dem passenden Kontakt zu.

## Vollexport
Nur bei einem Vollexport kennzeichnet das Archiv Kontakte mit Kontakt-ID aus 21.CRM, die in der Datei fehlen oder dort gelöscht sind, als «nicht mehr im CRM-Export». Das geschieht nur, wenn die Spalte «Kontakt-ID (21.CRM)» zugeordnet ist und Werte hat. Gelöscht wird nichts.

> **Achtung:** Wählen Sie «Vollexport» nur für eine Datei mit allen Kontakten. Sonst kennzeichnet das Archiv viele Kontakte zu Unrecht als «nicht mehr im CRM-Export».

## Häufige Meldungen
- «Die Datei ist grösser als 5 MiB.»: Exportieren Sie in 21.CRM kleinere Teile.
- «Die Datei enthält keine Datenzeilen.»: Exportieren Sie die Datei erneut aus 21.CRM. Das Archiv ändert dabei keinen Kontakt.
- «Die Vorschau dauert ungewöhnlich lange …» oder «Die Übernahme dauert ungewöhnlich lange …»: Wählen Sie später «Stand erneut prüfen». Solange der Status nicht «Übernommen» zeigt, ist nichts übernommen; Sie können die Übernahme noch einmal auslösen oder die Datei neu hochladen.
- «Der Import hat sich inzwischen geändert.»: Die Seite zeigt jetzt den aktuellen Stand. Prüfen Sie ihn, bevor Sie weitermachen.
- «Dieser Import wurde abgebrochen.»: Laden Sie die Datei erneut hoch.
