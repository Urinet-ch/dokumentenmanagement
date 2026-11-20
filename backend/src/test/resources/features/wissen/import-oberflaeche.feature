# language: de
@spec-04 @browser @wissen-import-pwa
Funktionalität: Wissenspakete als Entwurf importieren

  @req-19.28 @req-16.3 @req-16.4
  Szenario: Importbericht und Quellenneuprüfung am Handy
    Angenommen ein synthetischer Wissenspaketimport mit Ergebnis "201" ist verfügbar
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich auf 375 Pixeln ein Wissenspaket hochlade
    Dann sehe ich Importzähler und Seitenbefunde und kann Quellen ohne KI erneut prüfen

  @req-19.28 @req-16.3 @req-16.4
  Szenario: Ein abgewiesenes Paket erzeugt keinen Erfolgsbericht
    Angenommen ein synthetischer Wissenspaketimport mit Ergebnis "422" ist verfügbar
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich auf 375 Pixeln ein Wissenspaket hochlade
    Dann sehe ich den Paketfehler mit Seitenpfad ohne Entwurf und ohne Wiederholung

  @req-16.7 @req-19.28
  Szenariogrundriss: Ohne A17 kein Importzugriff
    Angenommen ein synthetischer Wissenspaketimport mit Ergebnis "201" ist verfügbar
    Und ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Dann ist der Wissenspaketimport weder verlinkt noch über die Route erreichbar

    Beispiele:
      | rolle        |
      | mitarbeiter  |
      | treuhaender  |
