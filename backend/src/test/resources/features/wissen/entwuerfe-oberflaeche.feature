# language: de
@spec-04 @browser @wissen-entwuerfe-pwa
Funktionalität: Wissensentwürfe einzeln prüfen und entscheiden

  @req-6.4 @req-6.11 @req-16.3 @req-16.4
  Szenariogrundriss: Diff und Einzelentscheid ohne horizontales Scrollen
    Angenommen synthetische Wissensentwürfe sind für die Oberfläche verfügbar
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich einen Wissensentwurf auf <breite> Pixeln öffne
    Dann sehe ich Diffzeichen, Befunde, Kontext und nicht zugeordnete Kernaussagen
    Und die Entwurfsansicht hat keinen horizontalen Überlauf und keinen axe-Befund
    Wenn ich nur diese Wissensänderung mit Bestätigung annehme
    Dann wurde genau diese Änderung mit If-Match gesendet und der Navigationszähler aktualisiert

    Beispiele:
      | breite |
      | 375    |
      | 1023   |
      | 1024   |
      | 1280   |
