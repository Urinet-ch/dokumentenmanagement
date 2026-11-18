# language: de
@spec-04
Funktionalität: Wissensseitenmodell prüfen

  @req-2.1
  Szenario: Eine Seite verwendet einen gültigen Pfad
    Angenommen eine neue Konzeptseite mit gültigem Pfad
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

  @req-2.1
  Szenario: Ein Pfad ausserhalb des Typordners wird abgelehnt
    Angenommen eine neue Konzeptseite im Ordner «akteure»
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «PFAD_UNGUELTIG»

  @req-2.2
  Szenario: Eine Seite hat ihre erforderlichen Felder
    Angenommen eine neue Seite mit allen erforderlichen Seitenfeldern
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

  @req-2.2
  Szenario: Eine Seite ohne Beschreibung ist nicht annehmbar
    Angenommen eine neue Seite ohne Beschreibung
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «FELD_FEHLT»

  @req-2.3
  Szenario: Die Wissensquellen-Seite verwendet ihren Typordner und Dokumentmonat
    Angenommen eine Wissensquellen-Seite mit passendem Monatsordner
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

  @req-2.4
  Szenario: Ein reservierter Pfadname wird abgelehnt
    Angenommen eine neue Seite mit einem reservierten Namen
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «PFAD_UNGUELTIG»

  @req-2.5
  Szenario: Ein Quelleneintrag verweist auf einen PDF-Seitenbereich
    Angenommen eine Seite mit einer PDF-Quelle auf Seite 2 von 2
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

  @req-2.6
  Szenario: Eine Aussage ohne Fussnote ist nicht annehmbar
    Angenommen eine Seite mit einem unbelegten Absatz
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «AUSSAGE_OHNE_BELEG»

  @req-2.6
  Szenario: Ein Widerspruchsblock ohne die Quellen beider Aussagen ist nicht annehmbar
    Angenommen eine Seite mit einem Widerspruchsblock ohne Fussnoten
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «AUSSAGE_OHNE_BELEG»

  @req-2.6
  Szenario: Ein Widerspruchsblock mit den Quellen beider Aussagen ist annehmbar
    Angenommen eine Seite mit einem Widerspruchsblock mit Fussnoten auf zwei Quellen
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

  @req-2.7
  Szenario: Ein PERSONAL-Dokument ist keine Wissensquelle
    Angenommen eine Seite mit einer PERSONAL-Dokumentquelle
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «QUELLE_PERSONAL»

  @req-2.6
  Szenario: Pflichtabschnitte unter «Berührte Seiten» brauchen trotzdem Fussnoten
    Angenommen eine Quellen-Seite mit unbelegten Pflichtabschnitten unter «Berührte Seiten»
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «AUSSAGE_OHNE_BELEG»

  @req-2.7
  Szenario: Eine PERSONAL-Wissensquelle ist auch ohne Fussnote auf sie nicht annehmbar
    Angenommen eine Quelle-Seite zu einer PERSONAL-Wissensquelle mit Fussnoten auf ein anderes Dokument
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «QUELLE_PERSONAL»

  @req-2.7
  Szenario: Eine Fussnote ohne Quelleneintrag ist nicht annehmbar
    Angenommen eine Seite mit einer Fussnote ohne Quelleneintrag
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «FUSSNOTE_OHNE_QUELLE»

  @req-2.8
  Szenario: Fehlende Pflichtabschnitte werden als Hinweis angezeigt
    Angenommen eine Quellen-Seite ohne Pflichtabschnitte
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Hinweis «ABSCHNITT_FEHLT»

  @req-3.5
  Szenario: Ein normaler Markdown-Link wird als Seitenlink extrahiert
    Angenommen Markdown mit einem internen Seitenlink
    Wenn das Seitenmodell geprüft wird
    Dann wird der Seitenlink «quellen/2026-03-handbuch» extrahiert

  @req-3.7
  Szenario: Externe Links, Bilder und HTML werden abgelehnt und sicher dargestellt
    Angenommen Markdown mit externem Link, Bild und HTML
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «LINK_UNGUELTIG»
    Und wird HTML sicher dargestellt

  @req-3.5 @req-3.7
  Szenario: Link-Referenzdefinitionen und Referenzlinks werden gemeldet
    Angenommen Markdown mit einem Referenzlink und einer Link-Referenzdefinition auf eine externe Adresse
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «LINK_UNGUELTIG»
