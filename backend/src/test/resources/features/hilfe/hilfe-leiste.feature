# language: de
# Hilfe-Leiste im App-Rahmen (Spec 07, Aufgabe 22.3). Erfassungsbilder und Chatantworten stammen aus synthetischen Attrappen.
@spec-07 @browser
Funktionalität: Hilfe-Leiste ohne Verlassen der aktuellen Seite

  @req-4.1
  Szenario: Der Knopf «Hilfe» ist abgemeldet auf dem Handy erreichbar
    Angenommen ein Bildschirm mit 375 Pixel Breite
    Wenn ich die Seite «/» öffne
    Dann ist der Knopf «Hilfe» mit seinem zugänglichen Namen sichtbar
    Und zeigt der Knopf das Symbol «hilfe»
    Und ist der Hilfetext verborgen

  @req-4.1
  Szenario: Der Knopf «Hilfe» zeigt am breiten Bildschirm auch seinen Text
    Angenommen ein Bildschirm mit 1280 Pixel Breite
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Dann ist der Knopf «Hilfe» mit seinem zugänglichen Namen sichtbar
    Und zeigt der Knopf das Symbol «hilfe»
    Und ist der Hilfetext sichtbar

  @req-4.2
  Szenario: Eine Aufnahme bleibt beim Öffnen und Schliessen der Hilfe-Leiste erhalten
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Und ich die Hilfe-Leiste öffne
    Dann zeigt die Hilfe-Leiste den Abschnitt «Erfassen»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann sehe ich 1 Seite
    Und bin ich auf der Seite «/erfassen»

  @req-4.2 @attrappe
  Szenario: Der Chatverlauf bleibt beim Öffnen und Schliessen der Hilfe-Leiste erhalten
    Angenommen die Chat-Schnittstelle ist eine Attrappe
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Welche Fristen gelten?» sende
    Dann sehe ich die Antwort «Antwort auf «Welche Fristen gelten?».»
    Wenn ich die Hilfe-Leiste öffne
    Und ich die Hilfe-Leiste über «Schliessen» schliesse
    Dann sehe ich die Antwort «Antwort auf «Welche Fristen gelten?».»
    Und bin ich auf der Seite «/chat»

  @req-4.3
  Szenario: Auf der ganzen Anleitung zeigt die Leiste ohne Seitenkontext ihr Inhaltsverzeichnis
    Wenn ich die Seite «/hilfe» öffne
    Und ich die Hilfe-Leiste öffne
    Dann zeigt die Hilfe-Leiste ihr Inhaltsverzeichnis «Inhalt der Anleitung»
    Und zeigt die Hilfe-Leiste keinen ausgewählten Abschnitt

  @req-4.3
  Szenariogrundriss: Der Kontext der Suche ist für «<rolle>» sichtbar
    Angenommen ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Suche» wähle
    Und ich die Hilfe-Leiste öffne
    Dann zeigt die Hilfe-Leiste den Abschnitt «Suche»

    Beispiele:
      | rolle            |
      | inhaber          |
      | mitarbeiter      |
      | treuhaender      |
      | geschaeftsstelle |

  @req-4.4
  Szenario: Andere Abschnitte wechseln in der Leiste und die ganze Anleitung öffnet den gewählten Anker
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Und ich die Hilfe-Leiste öffne
    Dann zeigt die Hilfe-Leiste den Abschnitt «Erfassen»
    Wenn ich in der Hilfe-Leiste den Abschnitt «Suche» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Suche»
    Und bin ich auf der Seite «/erfassen»
    Wenn ich den Link «Ganze Anleitung öffnen» in der Hilfe-Leiste wähle
    Dann steht die Adresse auf «/hilfe#suche»
    Und ist die Hilfe-Leiste geschlossen

  @req-4.5
  Szenario: Escape schliesst den Dialog, hält den Fokus darin und gibt ihn zurück
    Wenn ich die Seite «/» öffne
    Und ich die Hilfe-Leiste öffne
    Dann hat der Hilfe-Dialog den Namen «Hilfe»
    Und liegt der Fokus auf der Überschrift der Hilfe-Leiste
    Wenn ich in der Hilfe-Leiste weitertabbe
    Dann bleibt der Fokus in der Hilfe-Leiste
    Wenn ich die Hilfe-Leiste mit Escape schliesse
    Dann ist der Knopf «Hilfe» wieder fokussiert
