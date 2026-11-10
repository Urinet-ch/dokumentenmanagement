# language: de
@spec-01
Funktionalität: Fehlerantworten als Problem Details (design.md «Error Handling»)

  @req-16.7
  Szenario: Ein unerwarteter Fehler gibt 500 ohne Inhalt der Meldung in Antwort und Log
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» einen unerwarteten Fehler mit dem Text «VERTRAULICH-4711» auslöst
    Dann antwortet das Archiv mit 500 und Fehlercode «INTERNER_FEHLER»
    Und weder die Antwort noch das Log enthalten «VERTRAULICH-4711»
    Und das Log nennt nur Fehler-ID, Status und Fehlercode

  # Vorab-Korrektur 4 aus dem Review von PR #35: Die Grenze der Anfrage gab einen leeren 413. Im Testprofil liegt sie
  # bei 1 MB (application.properties); die Grenze für Uploads (Req 3.6) setzt Aufgabe 2.4.
  Szenario: Eine Anfrage über der Grenze gibt 413 als Problem Details
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» an die Probe-Anfrage 2 MB sendet
    Dann antwortet das Archiv mit Status 413 und Fehlercode «DATEI_ZU_GROSS»

  # Vorab-Korrektur 8 aus dem Review von PR #35: 415 des Frameworks ist kein unbekannter Dateityp (Req 3.3).
  Szenario: Ein falscher Content-Type gibt 415 mit eigenem Fehlercode
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» an die Probe-Anfrage JSON mit dem Content-Type «text/plain» sendet
    Dann antwortet das Archiv mit Status 415 und Fehlercode «MEDIENTYP_FALSCH»
