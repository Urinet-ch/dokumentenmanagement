# language: de
@spec-02 @browser @attrappe
Funktionalität: Chat-Auswertung in der PWA
  Der Inhaber wertet die Bewertungen des eigenen Mandanten aus und exportiert sie nach frischer Anmeldung.
  Sämtliche Antworten und Bewertungen in diesen Szenarien sind synthetisch.

  @req-15.5 @req-15.6 @req-15.7
  Szenario: Inhaber prüft die Auswertung und lädt den geschwärzten Export herunter
    Angenommen die Chat-Schnittstelle ist eine Attrappe
    Und ein Bildschirm mit 375 Pixel Breite
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat/auswertung» öffne
    Und ich mich anmelde
    Dann sehe ich die Chat-Auswertung mit 3 Bewertungen
    Und die Chat-Auswertung weist auf die Grenzen der Schwärzung und die Prüfung vor Weitergabe hin
    Und ist die Chat-Auswertung ohne horizontalen Überlauf und axe-Befund bei 375 und 1280 Pixeln in hell und dunkel
    Wenn ich den Chat-Auswertungs-Export vorbereite
    Dann ist die Schwärzung im Export standardmässig aktiviert
    Und der Exportdialog weist auf die Grenzen der Schwärzung und die Prüfung vor Weitergabe hin
    Und der Exportdialog hat keinen axe-Befund
    Wenn ich den Chat-Auswertungs-Export bestätige
    Dann wird ein geschwärztes ZIP mit den gewählten Filtern heruntergeladen

  @req-15.6 @req-15.8
  Szenario: Step-up verlangt eine frische Anmeldung ohne gespeicherte Filter
    Angenommen die Chat-Schnittstelle ist eine Attrappe
    Und der Chat-Auswertungs-Export verlangt Step-up
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat/auswertung» öffne
    Und ich mich anmelde
    Und ich den Chat-Auswertungs-Export vorbereite
    Und ich den Chat-Auswertungs-Export bestätige
    Dann wird eine frische Anmeldung für den Export verlangt
    Wenn ich mich für den Chat-Auswertungs-Export erneut anmelde
    Dann hat Keycloak eine frische Anmeldung verlangt und wurden keine Filter im Browser gespeichert
