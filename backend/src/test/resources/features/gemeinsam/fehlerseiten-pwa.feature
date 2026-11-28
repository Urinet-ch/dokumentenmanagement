# language: de
@spec-01 @browser
Funktionalität: Fehleransichten der PWA

  @req-4.4 @qa-fehlerseiten
  Szenario: Der Offline-Hinweis auf der Startseite ist barrierefrei
    Wenn ich die Startseite öffne
    Und das Handy offline ist
    Dann sehe ich die Meldung «Sie sind offline. Daten aus dem Archiv lassen sich erst mit einer Verbindung laden.»
    Und sehe ich den Link «Ohne Verbindung erfassen»
    Und ist die Fehleransicht bei 375 und 1280 Pixeln in hell und dunkel ohne axe-Befund
