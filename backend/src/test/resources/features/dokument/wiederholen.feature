# language: de
@spec-01
Funktionalität: Verarbeitung oder Archivierung wiederholen
  «wiederholen» setzt ein Dokument mit Verarbeitungsfehler auf NEU und reiht VERARBEITEN ein, ein Dokument mit
  Archivierungsfehler auf ARCHIVIERUNG und reiht ARCHIVIEREN ein (Aufgabe 3.4). In jedem anderen Status ändert sich
  nichts. Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-6.13 @req-10.2
  Szenario: Ein Dokument mit Verarbeitungsfehler wird neu verarbeitet
    Angenommen im Mandanten «urinet» liegt das Dokument «W» im Status «FEHLER», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» für das Dokument «W» «wiederholen» wählt
    Dann meldet das Archiv Status 202
    Und steht im Zugriffsprotokoll für «W» 1 Mal «WIEDERHOLEN» von «inhaber-urinet»
    Und trägt die Antwort das ETag der neuen Version von «W»
    Und ist das Dokument «W» im Status «NEU»
    Und ist für das Dokument «W» ein Auftrag «VERARBEITEN» eingereiht
    Und hat das Dokument «W» diese Werte:
      | fehler | null |

  @req-6.13
  Szenario: Ohne If-Match oder mit veralteter Version wiederholt das Archiv nichts
    Angenommen im Mandanten «urinet» liegt das Dokument «W» im Status «FEHLER», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» für das Dokument «W» ohne If-Match «wiederholen» wählt
    Dann meldet das Archiv Status 428 mit Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» für das Dokument «W» mit einer veralteten Version «wiederholen» wählt
    Dann meldet das Archiv Status 412 mit Fehlercode «VERSION_KONFLIKT»
    Und ist das Dokument «W» im Status «FEHLER»
    Und ist für das Dokument «W» kein Auftrag «VERARBEITEN» eingereiht

  @req-6.13
  Szenariogrundriss: In jedem anderen Status antwortet «wiederholen» mit 409
    Angenommen im Mandanten «urinet» liegt das Dokument «W» im Status «<status>», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» für das Dokument «W» «wiederholen» wählt
    Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»
    Und ist das Dokument «W» im Status «<status>»
    Und ist für das Dokument «W» kein Auftrag «VERARBEITEN» eingereiht
    Und ist für das Dokument «W» kein Auftrag «ARCHIVIEREN» eingereiht

    Beispiele:
      | status       |
      | NEU          |
      | VERARBEITUNG |
      | EINGANGSKORB |
      | ARCHIVIERUNG |
      | ARCHIVIERT   |
      | VERWORFEN    |

  @req-2.16
  Szenario: mitarbeiter und treuhaender dürfen nicht wiederholen
    Angenommen im Mandanten «urinet» liegt das Dokument «W» im Status «FEHLER», erfasst von «mitarbeiter-urinet»
    Und das Dokument «W» ist für alle Rollen sichtbar
    Wenn «mitarbeiter-urinet» für das Dokument «W» «wiederholen» wählt
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Wenn «treuhaender-urinet» für das Dokument «W» «wiederholen» wählt
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Und ist das Dokument «W» im Status «FEHLER»
    Und ist für das Dokument «W» kein Auftrag «VERARBEITEN» eingereiht
