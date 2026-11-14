# language: de
# Suche und Dokumentansicht der PWA (Aufgabe 4.6). Die API beantwortet eine Attrappe mit synthetischen Dokumenten
# (frontend/e2e/hilfen/suche-attrappe.mjs); den Vertrag der echten Suche und des Downloads belegen die Java-Szenarien
# suche/volltextsuche.feature und dokument/download.feature. Darum Teilnachweis (@attrappe). Den Platzhalter für Dokumente
# ohne Titel (Req 14.9, 14.10) weist dokument/dateiname-pwa.feature zusätzlich gegen das echte Archiv im Testmodus nach.
@spec-01 @browser @attrappe
Funktionalität: Suche und Dokumentansicht im Browser

  Hintergrund:
    Angenommen das Archiv liefert synthetische Suchtreffer

  @req-14.1 @req-14.4
  Szenario: Treffer zeigen die Textstelle mit hervorgehobenem Suchbegriff
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    # Sortierung und Seite sind Parameter des Volltexts: Der Umschalter «Volltext» ist dafür vorher ein (Spec 02, 6.6)
    Wenn ich nach «Seedorf» im Volltext suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter  | wert     |
      | modus      | volltext |
      | q          | Seedorf  |
      | sortierung | RELEVANZ |
      | seite      | 0        |
    Und sehe ich genau die Treffer «Rechnung Vereinsfest Seedorf»
    Und hebt der Treffer «Rechnung Vereinsfest Seedorf» genau «Seedorf» hervor
    Und zeigt der Treffer «Rechnung Vereinsfest Seedorf» die Belegnummer «2026-003» und den Betrag «CHF 1'234.50»
    Und steht «Seedorf» weder in der Adresse noch im Browser-Speicher

  @req-14.2
  Szenario: Die Filter werden kombiniert an das Archiv gesendet
    Angenommen ich bin als «treuhaender» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich die Filter setze und suche:
      | filter           | wert             |
      | Dokumentart      | Ausgangsrechnung |
      | Belegdatum von   | 2026-01-01       |
      | Belegdatum bis   | 2026-12-31       |
      | Betrag von (CHF) | 1'000            |
      | Betrag bis (CHF) | 2000,50          |
      | Richtung         | Einnahme         |
      | Status           | Archiviert       |
      | Tags             | verein, fest     |
      | Geschäftsjahr    | 2026             |
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter      | wert             |
      | art            | AUSGANGSRECHNUNG |
      | datumVon       | 2026-01-01       |
      | datumBis       | 2026-12-31       |
      | betragVon      | 1000             |
      | betragBis      | 2000.50          |
      | richtung       | EINNAHME         |
      | status         | ARCHIVIERT       |
      | tag            | verein\|fest     |
      | geschaeftsjahr | 2026             |
      | modus          | volltext         |
    Und sendet die PWA keinen Parameter «q, sortierung, partner, belegnummer»
    Und sehe ich genau die Treffer «Rechnung Vereinsfest Seedorf»

  @req-14.7
  Szenario: Die Belegnummer ist als exakter Filter verfügbar
    Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich die Filter setze und suche:
      | filter      | wert     |
      | Belegnummer | 2026-123 |
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter   | wert     |
      | belegnummer | 2026-123 |
    Und sehe ich genau die Treffer «Rechnung Druckerei»

  @req-14.2
  Szenario: Auf dem Handy ist die Filterleiste eingeklappt und nichts ragt seitlich hinaus
    Angenommen das Browserfenster ist 375 Pixel breit
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Dann ist die Filterleiste eingeklappt
    Und lässt sich die Seite nicht waagrecht scrollen
    Wenn ich nach «Vereinsfest» suche
    Dann sehe ich genau die Treffer «Rechnung Vereinsfest Seedorf, Rechnung Druckerei»
    Und lässt sich die Seite nicht waagrecht scrollen

  @req-14.6
  Szenario: Ein Treffer öffnet die Dokumentansicht mit Vorschau und Download über eine vorsignierte Adresse
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Seedorf» suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert   |
      | modus     | hybrid |
    Wenn ich den Treffer «Rechnung Vereinsfest Seedorf» öffne
    Dann sehe ich die Dokumentansicht von «Rechnung Vereinsfest Seedorf»
    Und zeigt die Dokumentansicht «Dokumentart» mit «Ausgangsrechnung»
    Und zeigt die Dokumentansicht «Belegdatum» mit «14.03.2026»
    Und hat die PWA die PDF/A-Adresse von «Rechnung Vereinsfest Seedorf» als JSON angefragt
    Und zeigt die Vorschau die vorsignierte Adresse als PDF
    Und öffnet «PDF/A herunterladen» die vorsignierte Adresse in einem neuen Tab
    Und lässt sich die Seite nicht waagrecht scrollen
    Und liegt kein Token im Browser-Speicher
    Wenn ich den Link «Zurück zur Suche» wähle
    Dann steht im Suchfeld «Seedorf»
    Und sehe ich genau die Treffer «Rechnung Vereinsfest Seedorf»

  @req-14.9 @req-14.10
  Szenario: Ein Treffer ohne Titel zeigt den Dateinamen als Platzhalter, auch in Verarbeitung und auf dem Handy
    Angenommen das Browserfenster ist 375 Pixel breit
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Kassenzettel» suche
    Dann zeigt der Treffer «Scan_Kassenzettel_Testladen_2026-11-13_Seite_01_bis_12_unbearbeitet.pdf» den Dateinamen als Platzhalter statt eines Titels
    Und lässt sich die Seite nicht waagrecht scrollen
    Wenn ich den Treffer «Scan_Kassenzettel_Testladen_2026-11-13_Seite_01_bis_12_unbearbeitet.pdf» öffne
    Dann sehe ich die Dokumentansicht von «Scan_Kassenzettel_Testladen_2026-11-13_Seite_01_bis_12_unbearbeitet.pdf»
    Und zeigt die Dokumentansicht den Dateinamen «Scan_Kassenzettel_Testladen_2026-11-13_Seite_01_bis_12_unbearbeitet.pdf» als Platzhalter und in der Zeile «Dateiname»
    Und lässt sich die Seite nicht waagrecht scrollen

  @req-14.10
  Szenario: Die Dokumentansicht zeigt einen Dateinamen mit Markup und Schrift von rechts nach links nur als Text und sendet ihn nie als Titel
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Fotobeleg» suche
    Und ich den Treffer «קבלה_<b>Fotobeleg</b>.pdf» öffne
    Dann sehe ich die Dokumentansicht von «קבלה_<b>Fotobeleg</b>.pdf»
    Und zeigt die Dokumentansicht den Dateinamen «קבלה_<b>Fotobeleg</b>.pdf» als Platzhalter und in der Zeile «Dateiname»
    Wenn ich den Knopf «Metadaten bearbeiten» wähle
    Dann ist im Formular das Feld «Titel» leer
    Wenn ich die Tags auf «quittung» ändere
    Und ich ohne Grund speichere
    Dann hat die PWA die Änderung ohne Titel und ohne den Dateinamen «קבלה_<b>Fotobeleg</b>.pdf» gesendet
