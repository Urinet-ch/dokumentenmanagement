# language: de
@spec-06 @browser @attrappe @20-5-einstellungen
Funktionalität: Einstellungen in der PWA verwalten

  @req-8.1 @req-8.2 @req-8.3 @req-8.4 @req-8.5 @req-8.7
  Szenariogrundriss: Einstellungen prüfen, bestätigen und nach Step-up einmal speichern
    Angenommen die Einstellungen liefern synthetische Daten
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/verwaltung/einstellungen» öffne
    Und ich mich anmelde
    Dann sind die Einstellungen ohne Überlauf und axe-Befund sichtbar
    Wenn ich die Chat-Aufbewahrung prüfe und bestätige
    Dann wird die Änderung nach Step-up genau einmal wiederholt

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |
