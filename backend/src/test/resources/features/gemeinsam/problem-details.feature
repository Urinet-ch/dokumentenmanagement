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
