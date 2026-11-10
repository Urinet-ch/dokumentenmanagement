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
      | inhaber          | Erfassen, Eingangskorb, Suche, Importe, Dokumentarten, Zugriffsprotokoll |
      | mitarbeiter      | Erfassen, Eingangskorb, Suche                                              |
      | treuhaender      | Suche                                                                      |
      | geschaeftsstelle | Erfassen, Eingangskorb, Suche                                              |

  @req-2.6
  Szenario: Ohne Mandant im Token zeigt die PWA kein Menü
    Angenommen Keycloak kennt mich als «inhaber» ohne Mandant
    Wenn ich die Startseite öffne
    Und ich mich anmelde
    Dann sehe ich kein Menü
    Und sehe ich die Meldung «Ihrem Benutzer ist kein Mandant oder keine Rolle im Archiv zugeordnet. Bitte wenden Sie sich an den Inhaber.»

  # Teilnachweis bis Aufgabe 3.6: Die Anzeige der Anzahl belegt der Unit-Test app.spec.ts mit einer Attrappe des
  # Zählers. Den Endpunkt GET /eingangskorb schliesst 3.6 an; bis dahin zeigt die Navigation nichts und meldet keinen Fehler.
  @req-8.6
  Szenario: Ohne Zähler-Endpunkt zeigt die Navigation keinen Zähler und keine Fehlermeldung
    Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Dann zeigt die Navigation keinen Eingangskorb-Zähler und keine Fehlermeldung
