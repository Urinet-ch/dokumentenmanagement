# language: de
# Einführungsvermerk im Browser (Spec 07, Aufgabe 23.1, Req 1.3–1.5): Die Schritte (frontend/e2e/schritte/
# einfuehrung-vermerk.mjs) laden das Modul frontend/src/app/hilfe/einfuehrung-vermerk-speicher.ts in Chromium auf den
# Ursprung der App und prüfen den localStorage dort; Benutzer-IDs sind zufällige UUIDs. Abschliessen und Überspringen über
# die Karte der Startseite belegen die Szenarien der Aufgaben 23.2 und 23.3 (Req 3.3, 3.4). Arbeitsspeicher als Rückfall,
# Abmeldung und Benutzerwechsel im Dienst EinfuehrungVermerk sowie die Begrenzung auf 20 Vermerke (Req 1.6) belegt Vitest
# (frontend/src/app/hilfe/einfuehrung-vermerk.spec.ts).
@spec-07 @browser
Funktionalität: Einführungsvermerk im Browser

  Hintergrund:
    Angenommen der Vermerk der Einführung ist auf der Startseite geladen

  @req-1.3
  Szenariogrundriss: Der Vermerk enthält nur Kennung, Version, Art und Datum (<art>)
    Wenn der Vermerk «<art>» für «Anna» im Mandanten «urinet» abgelegt wird
    Dann liegt unter «archiv.einfuehrung» genau ein Vermerk mit der Art «<art>», Version 1 und dem heutigen Datum
    Und ist seine Kennung der SHA-256 von Mandant und Benutzer-ID von «Anna» im Mandanten «urinet»
    Und enthält der Browser-Speicher weder die Benutzer-ID von «Anna» noch den Mandanten «urinet»
    Und hat die Seite dabei keine Anfrage gesendet

    Beispiele:
      | art           |
      | ABGESCHLOSSEN |
      | UEBERSPRUNGEN |

  @req-1.4
  Szenario: Vermerke sind je Mandant und Benutzer getrennt, die anderer bleiben unverändert
    Angenommen der Vermerk «ABGESCHLOSSEN» für «Anna» im Mandanten «urinet» ist abgelegt
    Dann hat «Anna» im Mandanten «urinet» einen Vermerk
    Und hat «Beat» im Mandanten «urinet» keinen Vermerk
    Und hat «Anna» im Mandanten «vhf-gsk» keinen Vermerk
    Wenn der Vermerk «UEBERSPRUNGEN» für «Beat» im Mandanten «urinet» abgelegt wird
    Und der Vermerk «UEBERSPRUNGEN» für «Anna» im Mandanten «vhf-gsk» abgelegt wird
    Dann ist der Vermerk von «Anna» im Mandanten «urinet» unverändert
    Und liegen unter «archiv.einfuehrung» 3 Vermerke

  @req-1.5
  Szenario: Ein beschädigter Eintrag gilt als kein Vermerk und wird beim nächsten Schreiben ersetzt
    Angenommen unter «archiv.einfuehrung» steht «{kein JSON»
    Dann hat «Anna» im Mandanten «urinet» keinen Vermerk
    Wenn der Vermerk «ABGESCHLOSSEN» für «Anna» im Mandanten «urinet» abgelegt wird
    Dann hat «Anna» im Mandanten «urinet» einen Vermerk
    Und liegen unter «archiv.einfuehrung» 1 Vermerk

  @req-1.5
  Szenario: Gesperrter Speicher gilt als kein Vermerk und führt zu keinem Fehler
    Angenommen der Browser sperrt den localStorage
    Dann hat «Anna» im Mandanten «urinet» keinen Vermerk
    Wenn der Vermerk «ABGESCHLOSSEN» für «Anna» im Mandanten «urinet» abgelegt wird
    Dann bleibt der Vermerk ohne Fehler ungespeichert
