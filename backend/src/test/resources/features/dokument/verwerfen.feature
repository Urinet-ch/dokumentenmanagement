# language: de
@spec-01
Funktionalität: Dokument verwerfen
  Der Inhaber verwirft ein Dokument im Eingangskorb oder mit Fehler (A10). Das Archiv löscht die Dateien unter
  eingang/, setzt VERWORFEN und protokolliert je Datei Prüfsumme und Grund (Aufgabe 3.4). Was unter archiv/ liegt
  oder gerade dorthin kopiert wird, bleibt unberührt. Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-6.6 @req-8.5 @req-10.2
  Szenario: Ein verworfenes Dokument verliert seine Dateien unter eingang/, das Protokoll hält Prüfsumme und Grund fest
    Angenommen im Mandanten «urinet» liegt das Dokument «D» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Wenn «inhaber-urinet» das Dokument «D» mit dem Grund "Doppelt fotografiert" verwirft
    Dann meldet das Archiv Status 204
    Und ist das Dokument «D» im Status «VERWORFEN»
    Und ist der Eingang von «D» leer
    Und liegt unter archiv/ nichts von «D»
    Und enthält das Zugriffsprotokoll für «D» je Datei «VERWERFEN» mit Prüfsumme und dem Grund "Doppelt fotografiert"

  @req-6.6
  Szenario: Auch ein Dokument mit Verarbeitungsfehler lässt sich verwerfen
    Angenommen im Mandanten «urinet» liegt das Dokument «D» im Status «FEHLER», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «D» mit dem Grund "Unlesbar" verwirft
    Dann meldet das Archiv Status 204
    Und ist das Dokument «D» im Status «VERWORFEN»
    Und ist der Eingang von «D» leer

  @req-6.6
  Szenario: Ohne If-Match oder mit veralteter Version verwirft das Archiv nichts
    Angenommen im Mandanten «urinet» liegt das Dokument «D» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «D» ohne If-Match verwirft
    Dann meldet das Archiv Status 428 mit Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» das Dokument «D» mit einer veralteten Version verwirft
    Dann meldet das Archiv Status 412 mit Fehlercode «VERSION_KONFLIKT»
    Und ist das Dokument «D» im Status «EINGANGSKORB»
    Und liegen die Dateien von «D» unverändert im Eingang

  @req-6.7
  Szenariogrundriss: Während und nach der Archivierung lässt sich nichts verwerfen
    Angenommen im Mandanten «urinet» liegt das Dokument «D» im Status «<status>», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «D» mit dem Grund "Versehen" verwirft
    Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»
    Und ist das Dokument «D» im Status «<status>»
    Und liegen die Dateien von «D» unverändert im Eingang

    Beispiele:
      | status              |
      | ARCHIVIERUNG        |
      | ARCHIVIERUNG_FEHLER |
      | ARCHIVIERT          |

  @req-2.15
  Szenario: mitarbeiter und treuhaender dürfen nicht verwerfen
    Angenommen im Mandanten «urinet» liegt das Dokument «D» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Und das Dokument «D» ist für alle Rollen sichtbar
    Wenn «mitarbeiter-urinet» das Dokument «D» mit dem Grund "Versehen" verwirft
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Wenn «treuhaender-urinet» das Dokument «D» mit dem Grund "Versehen" verwirft
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Und ist das Dokument «D» im Status «EINGANGSKORB»
    Und liegen die Dateien von «D» unverändert im Eingang
