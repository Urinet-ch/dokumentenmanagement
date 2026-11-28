# language: de
# Browser-Rauchtest: Jede Rolle kann ihre erlaubten Hauptmenüpunkte öffnen. Die Menüliste entspricht navigation-pwa.feature.
@spec-01 @browser @attrappe
Funktionalität: Hauptnavigation der Rollen

  @req-2.6
  Szenariogrundriss: Jeder sichtbare Menüpunkt führt zu einer geladenen Seite
    Angenommen ich zeichne Browserfehler für die Navigation auf
    Und ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Dann sehe ich im Menü genau «<menü>»
    Wenn ich alle sichtbaren Menüpunkte öffne
    Dann ist jede Seite korrekt geladen und ohne Browserfehler

    Beispiele:
      | rolle            | menü                                                                                                                                              |
      | inhaber          | Übersicht, Eingangskorb, Erfassen, Suche, Chat, Trennblätter, Kontakte, Wissen, Wissensentwürfe, Importe, Jahresexport, Einstellungen, Benutzer, Dokumentarten, Zugriffsprotokoll, Chat-Auswertung, API-Tokens |
      | mitarbeiter      | Übersicht, Eingangskorb, Erfassen, Suche, Chat, Trennblätter, Wissen                                                                                              |
      | treuhaender      | Übersicht, Eingangskorb, Suche, Chat, Wissen, Jahresexport                                                                                          |
      | geschaeftsstelle | Übersicht, Eingangskorb, Erfassen, Suche, Chat, Trennblätter, Kontakte, Wissen, Wissensentwürfe                                                                    |
