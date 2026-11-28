# language: de
@spec-06 @browser
Funktionalität: Nicht gefundene Verwaltungsseite

  @req-8.1 @req-8.2 @qa-fehlerseiten
  Szenario: Die Nicht-gefunden-Seite ist barrierefrei
    Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Wenn ich die geschützte Verwaltungsseite ohne Neuladen aufrufe
    Dann bin ich auf der Seite «/nicht-gefunden»
    Und sehe ich die Überschrift «404 – Seite nicht gefunden»
    Und ist die Fehleransicht bei 375 und 1280 Pixeln in hell und dunkel ohne axe-Befund
