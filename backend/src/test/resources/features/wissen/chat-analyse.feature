# language: de
@spec-04 @wissen-analyse
Funktionalität: Analysevorschläge aus dem Chat

  @req-12.4 @req-11.7
  Szenario: Eine eigene Antwort wird ohne KI als geprüfter Entwurf vorgeschlagen
    Angenommen eine eigene Chatantwort hat ein zulässiges Werkzeug mit internem Kontext
    Wenn der Mitarbeiter diese Antwort als Analyse vorschlägt
    Dann ist der Analyseentwurf offen, intern und wegen fehlender Belege gesperrt
    Und der Mitarbeiter sieht nur Status und Entscheid des Vorschlags

  @req-12.4 @req-11.6
  Szenario: Eine Anweisung in der Chatantwort gibt den Analyseentwurf nicht frei
    Angenommen eine eigene Chatantwort mit synthetischer Anweisung hat ein zulässiges Werkzeug mit internem Kontext
    Wenn der Mitarbeiter diese Antwort als Analyse vorschlägt
    Dann bleibt der Analyseentwurf mit der Anweisung offen und gesperrt, ohne Wissensseite und ohne KI-Aufruf

  @req-12.4
  Szenario: Fremde Gespräche und Kennzahlen eignen sich nicht für Analysevorschläge
    Angenommen eine eigene Chatantwort hat ein unzulässiges Werkzeug
    Wenn der Mitarbeiter diese Antwort als Analyse vorschlägt
    Dann wird der Analysevorschlag mit 422 abgelehnt
    Wenn der Inhaber die fremde Antwort als Analyse vorschlägt
    Dann wird der Analysevorschlag mit 404 abgelehnt
