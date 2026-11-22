# language: de
@spec-07 @browser @attrappe
Funktionalität: Hilfe an Stellen mit Rückfragen

  @req-4.6
  Szenario: Die Warteschlangenhilfe erhält eine angefangene Aufnahme
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Und ich den Kontextlink bei «Warteschlange» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Ohne Netz erfassen»
    Und bin ich auf der Seite «/erfassen»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann sehe ich 1 Seite
    Und ist der Kontextlink bei «Warteschlange» wieder fokussiert

  @req-4.6
  Szenario: Ein fehlerhafter Datei-Upload behält seine Auswahl
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Und ein synthetischer Datei-Upload fehlschlägt
    Und ich den Kontextlink bei «Uploadfehler» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Ohne Netz erfassen»
    Und bin ich auf der Seite «/erfassen»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann bleibt die synthetische Datei ausgewählt
    Und ist der Kontextlink bei «Uploadfehler» wieder fokussiert

  @req-4.6
  Szenario: Ohne Netz bietet die Anmeldekarte Hilfe an
    Angenommen der Browser meldet keine Verbindung
    Wenn ich die Seite «/» öffne
    Und ich den Kontextlink bei «Anmeldekarte» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Ohne Netz erfassen»
    Und bin ich auf der Seite «/»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann ist der Kontextlink bei «Anmeldekarte» wieder fokussiert

  @req-4.6
  Szenario: Ohne Rolle führt die Meldung zur passenden Hilfe
    Angenommen ich bin als «ohne-rolle» im Mandanten «urinet» angemeldet
    Wenn ich den Kontextlink bei «Zugang» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Wenn etwas nicht klappt»
    Und bin ich auf der Seite «/»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann ist der Kontextlink bei «Zugang» wieder fokussiert

  @req-4.6
  Szenario: Ohne Mandant führt die Meldung zur passenden Hilfe
    Angenommen Keycloak kennt mich als «inhaber» ohne Mandant
    Wenn ich die Seite «/» öffne
    Und ich mich anmelde
    Und ich den Kontextlink bei «Zugang» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Wenn etwas nicht klappt»
    Und bin ich auf der Seite «/»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann ist der Kontextlink bei «Zugang» wieder fokussiert
