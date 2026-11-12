# language: de
@spec-01
Funktionalität: Monatlicher Prüflauf (PRUEFLAUF)
  Der Prüflauf vergleicht die Prüfsummen der archivierten Dateien mit der Datenbank, meldet fremde Objektversionen und
  Löschmarken, verlängert die Sperren unbefristeter Dokumente und der Manifeste und schreibt einen Bericht nur mit IDs
  (Aufgabe 5.3). Im Testprofil gilt Stufe A: Sperren höchstens 1 Tag. Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen der Prüflauf ist bereit

  @req-15.5 @req-15.10
  Szenario: Abweichende Prüfsumme, fremde Version und Löschmarke stehen im Bericht, der Inhaber erhält eine Mail mit IDs
    Angenommen im Mandanten «urinet» ist das unbefristete Dokument «A» archiviert, gesperrt für 2 Stunden
    Und im Mandanten «urinet» ist das unbefristete Dokument «B» archiviert, gesperrt für 2 Stunden
    Und im Mandanten «urinet» ist das unbefristete Dokument «C» archiviert, gesperrt für 2 Stunden
    Und im Mandanten «urinet» ist das unbefristete Dokument «D» archiviert, gesperrt für 2 Stunden
    Und die gespeicherte Prüfsumme von «A» ist verfälscht
    Und unter dem Schlüssel des Originals von «B» liegt eine zweite Version
    Und das Original von «C» hat eine Löschmarke
    Wenn der Prüflauf für «urinet» läuft
    Dann ist der Prüflauf abgeschlossen
    Und meldet der Prüflauf für «A» nur «PRUEFSUMME»
    Und meldet der Prüflauf für «B» nur «FREMDE_VERSION»
    Und meldet der Prüflauf für «C» nur «LOESCHMARKE»
    Und meldet der Prüflauf für «D» nichts
    Und hat der Inhaber eine Mail zum Prüflauf erhalten, die nur IDs nennt

  @req-15.9 @req-6.9
  Szenario: Der Prüflauf verlängert die Sperre unbefristeter Dokumente, befristete bleiben unverändert
    Angenommen im Mandanten «urinet» ist das unbefristete Dokument «U» archiviert, gesperrt für 2 Stunden
    Und im Mandanten «urinet» ist das Dokument «F» bis 31.12.2036 aufzubewahren und archiviert, gesperrt für 2 Stunden
    Wenn der Prüflauf für «urinet» läuft
    Dann ist der Prüflauf abgeschlossen
    Und ist die Sperre des Originals von «U» verlängert, höchstens bis jetzt plus 1 Tag
    Und ist die Sperre des Originals von «F» unverändert

  @req-15.5
  Szenario: Der Zeitplan startet je Monat höchstens einen Lauf
    Wenn der Prüflauf für «urinet» und den Monat «2999-01» zweimal gestartet wird
    Dann hat nur der erste Start einen Lauf angelegt
