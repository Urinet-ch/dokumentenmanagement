# language: de
@spec-04 @browser
Funktionalität: Wissensbasis auf schmalen Bildschirmen lesen

  @req-3.8 @req-15.2 @req-15.4 @req-16.1 @req-16.2 @req-16.4 @req-16.5 @req-16.7
  Szenario: Sichtbare Seiten und Quellen am Handy lesen
    Angenommen synthetische Wissensseiten sind für die Oberfläche verfügbar
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Wissensoberfläche auf 375 Pixeln öffne
    Dann sind Index, Seite, Quellen und Log ohne horizontales Scrollen und ohne axe-Befund bedienbar
