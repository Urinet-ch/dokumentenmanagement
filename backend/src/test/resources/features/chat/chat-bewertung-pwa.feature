# language: de
@spec-02 @browser @attrappe @req-15.11
Funktionalität: Chat-Antworten in der PWA bewerten

  @req-15.1 @req-15.9
  Szenario: Eine Antwort per Tastatur bewerten, wieder öffnen und entfernen
    Angenommen die Chat-Schnittstelle ist eine Attrappe
    Und ein Chatgespräch enthält eine Wissensquelle und einen Analysevorschlag
    Und ein Bildschirm mit 375 Pixel Breite
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich das Gespräch «Wissen zur Probe» öffne
    Dann sind die Sterne und der Datenschutzhinweis sichtbar
    Wenn ich die erste Antwort mit zwei Sternen per Tastatur bewerte
    Und ich den Grund «Antwort falsch» und einen Freitext angebe und die Bewertung speichere
    Dann hat die Bewertung keinen axe-Befund und keinen horizontalen Überlauf
    Wenn ich auf «Neues Gespräch» klicke
    Und ich das Gespräch «Wissen zur Probe» öffne
    Dann ist die Bewertung mit zwei Sternen und ihrem Freitext wieder da
    Wenn ich die Bewertung entferne
    Dann ist die Bewertung entfernt
