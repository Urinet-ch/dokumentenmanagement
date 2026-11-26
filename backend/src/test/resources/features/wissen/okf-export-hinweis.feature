# language: de
@spec-04 @browser @attrappe
Funktionalität: Hinweis zu Cloud-Modellen beim OKF-Export

  @req-19.24
  Szenario: Die Einstellungsseite zeigt die Cloud-Modell-Regel vor dem OKF-Export
    Angenommen die Wissenseinstellungen liefern synthetische Daten
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/wissensbasis/einstellungen» öffne
    Und ich mich anmelde
    Dann sind die Wissenseinstellungen ohne Überlauf und axe-Befund sichtbar
    Und sehe ich den Cloud-Modell-Hinweis vor dem OKF-Export
