# language: de
# Navigation der PWA nach der Rechtematrix (Aufgabe 2.7). Die Oberfläche blendet nur aus; jede Aktion prüft das
# Backend selbst (sicherheit/rechtematrix in den Java-Szenarien).
@spec-01 @browser
Funktionalität: Navigation der PWA nach Rolle

  @req-2.6
  Szenariogrundriss: Die Navigation zeigt nur Menüpunkte, für die die Rolle ein Recht hat
    Angenommen ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Dann sehe ich im Menü genau «<menü>»

    Beispiele:
      | rolle            | menü                                                                       |
      | inhaber          | Übersicht, Eingangskorb, Erfassen, Suche, Chat, Trennblätter, Kontakte, Wissen, Wissensentwürfe, Importe, Jahresexport, Einstellungen, Benutzer, Dokumentarten, Zugriffsprotokoll, Chat-Auswertung, API-Tokens |
      | mitarbeiter      | Übersicht, Eingangskorb, Erfassen, Suche, Chat, Trennblätter, Wissen                                              |
      | treuhaender      | Übersicht, Eingangskorb, Suche, Chat, Wissen, Jahresexport                                                       |
      | geschaeftsstelle | Übersicht, Eingangskorb, Erfassen, Suche, Chat, Trennblätter, Kontakte, Wissen, Wissensentwürfe                    |

  @req-2.6
  Szenario: Ohne Mandant im Token zeigt die PWA kein Menü
    Angenommen Keycloak kennt mich als «inhaber» ohne Mandant
    Wenn ich die Startseite öffne
    Und ich mich anmelde
    Dann sehe ich kein Menü
    Und sehe ich die Meldung «Ihrem Benutzer ist kein Mandant oder keine Rolle im Archiv zugeordnet. Bitte wenden Sie sich an den Inhaber.»

  # Der Zähler zählt über GET /api/v1/eingangskorb (Feld gesamt), hier mit der Attrappe der API
  # (frontend/e2e/hilfen/archiv-attrappe.mjs); dass das Archiv nur Sichtbares zählt, belegen die Java-Szenarien.
  @req-8.6
  Szenario: Die Navigation zeigt die Anzahl Dokumente im Eingangskorb
    Angenommen der Eingangskorb enthält 3 Dokumente
    Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Dann zeigt die Navigation den Eingangskorb-Zähler «3»

  @req-2.11
  Szenario: Der Treuhänder sieht den Eingangskorb-Zähler lesend (A5, seit 26.11.2026)
    Angenommen der Eingangskorb enthält 3 Dokumente
    Und ich bin als «treuhaender» im Mandanten «urinet» angemeldet
    Dann zeigt die Navigation den Eingangskorb-Zähler «3»

  @req-2.11 @req-2.21 @qa-treuhaender-eingangskorb
  Szenario: Der Treuhänder versteht den leeren, gefilterten Eingangskorb
    Angenommen der Eingangskorb enthält 0 Dokumente
    Wenn ich als «treuhaender» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann erklärt der Eingangskorb dem Treuhänder den leeren, gefilterten Zustand
    Und ist der leere Eingangskorb bei 375 und 1280 px in beiden Farbschemata barrierefrei
