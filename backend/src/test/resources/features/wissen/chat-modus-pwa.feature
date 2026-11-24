# language: de
@spec-04 @browser @attrappe
Funktionalität: Den Modus eines Chat-Gesprächs wählen
  Grundlage:
    Angenommen die Chat-Schnittstelle ist eine Attrappe

  @req-21.4
  Szenariogrundriss: Wissenschat starten und den Modus erkennen
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich wähle den Wissenschat
    Dann ist der Chat in beiden Farbschemata ohne axe-Befund und Überlauf sichtbar
    Wenn ich die Frage «Welche Regel gilt?» sende
    Dann sehe ich die Antwort «Antwort auf «Welche Regel gilt?».»
    Und ist der Wissensmodus im Gespräch und in der Liste festgehalten
    Und ist der Chat in beiden Farbschemata ohne axe-Befund und Überlauf sichtbar

    Beispiele:
      | breite |
      | 375    |
      | 1280   |
