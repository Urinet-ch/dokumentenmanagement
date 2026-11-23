# language: de
# Die REST-Rechte und Mandantentrennung prüft das Backend; die PWA-Szenarien verwenden synthetische Antworten.
@spec-06 @browser @attrappe
Funktionalität: Benutzer und Rechte in der PWA lesen

  @req-8.1 @req-8.2 @req-8.3 @req-8.7
  Szenariogrundriss: Der Inhaber liest Benutzer, Anträge und Rechte barrierefrei
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Benutzer» wähle
    Dann zeigt die Benutzerverwaltung Liste, Anträge und Rollen ohne Überlauf und axe-Befund
    Wenn ich den synthetischen Benutzer öffne
    Dann zeigt das Benutzerdetail Status, Rechte und Token-Hinweis ohne Überlauf und axe-Befund

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |

  @req-8.1
  Szenariogrundriss: Ohne A21 sind Liste und Detail nicht sichtbar
    Angenommen die Benutzerverwaltung liefert synthetische Daten
    Und Keycloak kennt mich als «<rolle>» im Mandanten «urinet»
    Wenn ich die Seite «<pfad>» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/nicht-gefunden»
    Und fehlt der Menüpunkt Benutzer und es wurde kein Benutzer abgefragt

    Beispiele:
      | rolle            | pfad                        |
      | mitarbeiter      | /verwaltung/benutzer        |
      | mitarbeiter      | /verwaltung/benutzer/probe  |
      | treuhaender      | /verwaltung/benutzer        |
      | geschaeftsstelle | /verwaltung/benutzer/probe  |
