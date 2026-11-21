# language: de
@spec-04 @browser @attrappe
Funktionalität: Wissensquellen und Analysevorschläge im Chat

  Grundlage:
    Angenommen die Chat-Schnittstelle ist eine Attrappe
    Und synthetische Wissensseiten sind für die Oberfläche verfügbar
    Und ein Chatgespräch enthält eine Wissensquelle und einen Analysevorschlag
    Und ein Bildschirm mit 375 Pixel Breite
    Und Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich das Gespräch «Wissen zur Probe» öffne

  @req-8.8
  Szenario: Eine Wissensquelle öffnet die Wissensseite
    Dann sind Wissensseite und Quelldokument im Chat unterscheidbar
    Wenn ich im Chat die Wissensquelle öffne
    Dann sehe ich die Wissensseite «Synthetische Wissensseite»

  @req-12.4 @req-16.6
  Szenario: Ein zulässiger Analysevorschlag zeigt seinen Status
    Dann gibt es nur für die zulässige Antwort die Aktion «Als Analyse vorschlagen»
    Wenn ich die zulässige Antwort als Analyse vorschlage
    Und ich den Status des Analysevorschlags öffne
    Dann sehe ich den Entscheid und Grund meines Analysevorschlags
