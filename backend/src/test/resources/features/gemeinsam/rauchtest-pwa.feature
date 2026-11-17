# language: de
@spec-01 @browser @rauchtest
Funktionalität: Rauchtest PWA

  Szenario: Die PWA kommt aus dem API-Artefakt
    Wenn ich die Startseite öffne
    # localhost ist keine registrierte Mandanten-Domain: die PWA zeigt das neutrale Branding «Archiv» (Spec 03, Req 2.2)
    Dann sehe ich den Titel «Archiv»
    Und liefert das Archiv «/manifest.webmanifest» mit Status 200
    Und liefert das Archiv «/ngsw-worker.js» mit Status 200
    Und liefert der Deep Link «/irgendwo/tief/1» die Startseite
