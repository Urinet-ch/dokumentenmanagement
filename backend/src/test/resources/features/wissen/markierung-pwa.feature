# language: de
@spec-04 @browser
Funktionalität: Wissensquellen in der Oberfläche markieren und verwalten

  @req-20.10
  Szenario: Eine Markierung erscheint beim Dokument und in der Wissensquellenliste
    Angenommen eine synthetische Wissensquelle für die Markierung ist verfügbar
    Angenommen ich öffne die Seite «/dokument/0b6f1d3e-0000-4000-8000-000000000001» vor der Anmeldung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Wissensquelle markiere
    Dann ist das Dokument als Wissensquelle gekennzeichnet
    Wenn ich im Menü «Wissen» wähle
    Dann zeigt die Wissensquellenliste Titel, Art, Markierungsdatum und Ingest-Status
    Wenn ich die Markierung in der Wissensquellenliste entferne
    Dann zeigt die Wissensquellenliste den Leerzustand nach der Entfernung
