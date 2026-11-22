# language: de
@spec-07 @browser
Funktionalität: Anleitung ohne Netz

  @req-8.1
  Szenario: Die Anleitung wird vorab geladen und ist offline vollständig
    Angenommen die PWA mit der Anleitung ist installiert
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn das Handy offline ist
    Und ich die Hilfe-Leiste öffne
    Dann sind alle sichtbaren Abschnitte der Hilfe-Leiste geladen
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Und ich in der App «/hilfe» aufrufe
    Dann sind alle sichtbaren Abschnitte der Anleitung geladen

  @req-8.3
  Szenario: Abgemeldet zeigt die Offline-Erfassung ihre Hilfe
    Angenommen die PWA mit der Anleitung ist installiert
    Wenn das Handy offline ist
    Und ich die Seite «/erfassen» öffne
    Und ich die Hilfe-Leiste öffne
    Dann zeigt die Hilfe-Leiste den geladenen Abschnitt «Ohne Netz erfassen»
    Wenn ich die Hilfe-Leiste über «Schliessen» schliesse
    Und ich in der App «/hilfe» aufrufe
    Dann sind alle sichtbaren Abschnitte der Anleitung geladen
    Und zeigt die Offline-Anleitung nur die Abschnitte für Abgemeldete

  @req-8.2
  Szenario: Eine neue App-Version und ihre Anleitung werden gemeinsam aktiv
    Angenommen eine PWA mit zwei synthetischen Versionen ist installiert
    Wenn eine neue App-Version mit neuer Anleitung bereitsteht
    Dann zeigt die PWA weiterhin die alte App und die alte Anleitung
    Wenn ich die neue App-Version aktiviere
    Dann zeigt die PWA die neue App und die neue Anleitung
