Unter «Jahresexport» erstellen Sie ein ZIP mit allen archivierten Belegen eines Geschäftsjahres, zum Beispiel für den Abschluss. Das Archiv erstellt den Export im Hintergrund; Sie müssen auf der Seite nicht warten.

## Export anfordern
1. Öffnen Sie in der Seitenleiste «Jahresexport».
2. Geben Sie unter «Geschäftsjahr» das Jahr ein. Vorgeschlagen ist das Vorjahr. Das Geschäftsjahr heisst nach dem Kalenderjahr, in dem es endet.
3. Wählen Sie «Export starten».
4. Während das Archiv den Export erstellt, zeigt die Seite einen Fortschrittsbalken, und der Export steht unter «Exporte» auf «In Arbeit».

Je Mandant läuft höchstens ein Export zugleich. Die Liste «Exporte» zeigt die neuesten Exporte Ihres Mandanten, auch solche, die eine andere Person angefordert hat.

## Export herunterladen
1. Ist der Export fertig, steht er unter «Exporte» auf «Bereit», mit der Zahl der Belege, der Grösse und dem Zeitpunkt, bis zu dem der Download möglich ist.
2. Wählen Sie den Knopf mit dem Jahr, zum Beispiel «Export 2025 herunterladen».
3. Vergleichen Sie bei Bedarf die Prüfsumme der heruntergeladenen Datei mit «SHA-256 des ZIP». Stimmen beide überein, ist die Datei vollständig und unverändert.

Der Download-Link gilt 24 Stunden ab Fertigstellung. Danach steht der Export auf «Abgelaufen»; fordern Sie einen neuen Export an.

Zusätzlich meldet das Archiv einen fertigen Export per E-Mail an die Adresse, die für Meldungen des Archivs in Ihrem Mandanten eingerichtet ist. Die E-Mail enthält nur einen Link auf diese Seite und die Nummer des Exports, keine Belege. Den Stand sehen Sie auch ohne E-Mail auf dieser Seite.

## Inhalt des ZIP
- `belege/`: die PDF/A-Fassung jedes archivierten Belegs des Jahres, sonst das Original. Die Dateinamen folgen dem Muster Datum, Art, Belegnummer und Partner.
- `belegliste.csv`: eine Zeile je Beleg mit ID, Art, Datum, Partner, Belegnummer, Richtung, Betrag, Währung, SHA-256 und Dateiname, zum Öffnen in Excel.
- `pruefsummen.sha256`: die SHA-256 jeder Datei im ZIP.
- `manifeste/`: die Tages-Manifeste des Jahres mit Signatur und Zeitstempel.

Der Export enthält nur Belege, die bereits archiviert sind. Noch nicht bestätigte Dokumente und Wissensquellen gehören nicht dazu.

## Häufige Meldungen
- «Es läuft bereits ein Export. Bitte warten Sie, bis er fertig ist.»: Warten Sie, bis der laufende Export «Bereit» oder «Fehlgeschlagen» zeigt.
- «Fehlgeschlagen»: Beim Export steht, was passiert ist, und oft eine «Fehler-ID». Wählen Sie «Erneut anfordern». Scheitert er wieder, geben Sie die Fehler-ID weiter, wenn Sie Hilfe holen.
- «Das Archiv ist gerade nicht erreichbar …»: Das Archiv startet vielleicht gerade neu. Versuchen Sie es in einer Minute erneut.
- «Der Download-Link ist abgelaufen.»: Fordern Sie einen neuen Export an.
