# language: de
@spec-04
Funktionalität: Wissensseitenmodell prüfen

  @req-2.1
  Szenario: Eine Seite verwendet einen gültigen Pfad
    Angenommen eine neue Konzeptseite mit gültigem Pfad
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

  @req-2.2
  Szenario: Eine Seite hat ihre erforderlichen Felder
    Angenommen eine neue Seite mit allen erforderlichen Seitenfeldern
    Wenn das Seitenmodell geprüft wird
    Dann ist die Seite annehmbar

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

  @req-2.7
  Szenario: Ein PERSONAL-Dokument ist keine Wissensquelle
    Angenommen eine Seite mit einer PERSONAL-Dokumentquelle
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «QUELLE_PERSONAL»

  @req-2.8
  Szenario: Fehlende Pflichtabschnitte werden als Hinweis angezeigt
    Angenommen eine Quellen-Seite ohne Pflichtabschnitte
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Hinweis «ABSCHNITT_FEHLT»

  @req-3.5
  Szenario: Ein normaler Markdown-Link wird als Seitenlink gespeichert
    Angenommen Markdown mit einem internen Seitenlink
    Wenn das Seitenmodell geprüft wird
    Dann wird der Seitenlink «quellen/2026-03-handbuch» gespeichert

  @req-3.7
  Szenario: Externe Links, Bilder und HTML werden abgelehnt und sicher dargestellt
    Angenommen Markdown mit externem Link, Bild und HTML
    Wenn das Seitenmodell geprüft wird
    Dann enthält die Prüfung den Befund «LINK_UNGUELTIG»
    Und wird HTML sicher dargestellt
