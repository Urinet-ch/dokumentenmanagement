# language: de
@spec-01
Funktionalität: Archivierung (ARCHIVIEREN) mit Fehlerfall und Wiederholung
  Scheitert die Archivierung, bleibt das Dokument mit seinen bestätigten Metadaten in ARCHIVIERUNG_FEHLER, unter
  eingang/ bleibt alles liegen, und der Inhaber erhält eine Mail nur mit IDs. «wiederholen» startet die Archivierung
  neu, ohne die Metadaten noch einmal zu bestätigen (Aufgabe 3.4). Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-6.4 @req-6.8
  Szenario: Eine abweichende Prüfsumme stoppt die Archivierung, nach der Korrektur archiviert «wiederholen»
    Angenommen im Mandanten «urinet» liegt das Dokument «A» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «A» so bestätigt:
      | art      | QUITTUNG       |
      | titel    | Probe-Quittung |
      | datum    | 2026-03-10     |
      | richtung | AUSGABE        |
      | betrag   | 45.00          |
      | waehrung | CHF            |
    Dann meldet das Archiv Status 202
    Angenommen die gespeicherte Prüfsumme des Originals von «A» ist verfälscht
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «A» im Status «ARCHIVIERUNG_FEHLER»
    Und hat das Dokument «A» diese Werte:
      | art   | QUITTUNG       |
      | titel | Probe-Quittung |
    Und liegt unter archiv/ nichts von «A»
    Und liegen die Dateien von «A» unverändert im Eingang
    Und hat der Inhaber eine Mail zur gescheiterten Archivierung von «A» erhalten, die nur IDs nennt
    Angenommen die gespeicherte Prüfsumme des Originals von «A» ist wieder richtig
    Wenn «inhaber-urinet» für das Dokument «A» «wiederholen» wählt
    Dann meldet das Archiv Status 202
    Und ist das Dokument «A» im Status «ARCHIVIERUNG»
    Und ist für das Dokument «A» ein Auftrag «ARCHIVIEREN» eingereiht
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «A» im Status «ARCHIVIERT»
    Und hat das Dokument «A» diese Werte:
      | art    | QUITTUNG       |
      | titel  | Probe-Quittung |
      | fehler | null           |
    Und liegen alle Dateien von «A» unter archiv/ mit Versions-ID und Sperre bis mindestens heute
    Und ist der Eingang von «A» leer

  @req-3.12
  Szenario: Die Mail zur gescheiterten Archivierung nennt den Dateinamen nicht
    Angenommen im Mandanten «urinet» liegt das Dokument «A» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und das Dokument «A» hat den Dateinamen «Geheimname_Mailprobe-4711.pdf»
    Wenn «inhaber-urinet» das Dokument «A» so bestätigt:
      | art      | QUITTUNG       |
      | titel    | Probe-Quittung |
      | datum    | 2026-03-10     |
      | richtung | AUSGABE        |
      | betrag   | 45.00          |
      | waehrung | CHF            |
    Dann meldet das Archiv Status 202
    Angenommen die gespeicherte Prüfsumme des Originals von «A» ist verfälscht
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «A» im Status «ARCHIVIERUNG_FEHLER»
    Und hat der Inhaber eine Mail zur gescheiterten Archivierung von «A» erhalten, die nur IDs nennt
