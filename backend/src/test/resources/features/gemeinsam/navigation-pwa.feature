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
      | inhaber          | Erfassen, Eingangskorb, Suche, Kontakte, Importe, Dokumentarten, Zugriffsprotokoll, API-Tokens, Jahresexport |
      | mitarbeiter      | Erfassen, Eingangskorb, Suche                                              |
      | treuhaender      | Suche, Jahresexport                                                                      |
      | geschaeftsstelle | Erfassen, Eingangskorb, Suche, Kontakte                                    |

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
  Szenario: Ohne Recht auf den Eingangskorb fragt die PWA keinen Zähler ab
    Angenommen der Eingangskorb enthält 3 Dokumente
    Und ich bin als «treuhaender» im Mandanten «urinet» angemeldet
    Dann ruft die PWA den Eingangskorb nicht ab und zeigt keinen Zähler
