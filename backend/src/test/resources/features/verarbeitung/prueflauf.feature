# language: de
@spec-01
Funktionalität: Monatlicher Prüflauf (PRUEFLAUF)
  Der Prüflauf vergleicht die Prüfsummen der archivierten Dateien mit der Datenbank, meldet fremde Objektversionen und
  Löschmarken, verlängert die Sperren unbefristeter Dokumente und der Manifeste und schreibt einen Bericht nur mit IDs
  (Aufgabe 5.3). Im Testprofil gilt Stufe A: Sperren höchstens 1 Tag. Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen der Prüflauf ist bereit

  @req-15.5
  Szenario: Unveränderter Bestand mit Tages-Manifest hat keine Manifest-Abweichung
    Angenommen ein eigener Mandant «pl-manifest-a» für den Manifest-Prüflauf
    Und im Testmandanten ist das Dokument «A» am «10.01.2026» um «09:15» archiviert
    Wenn für den Testmandanten das Tages-Manifest bis «10.01.2026» erzeugt wird
    Und der Prüflauf für den Testmandanten läuft
    Dann ist der Prüflauf im Testmandanten abgeschlossen
    Und meldet der Prüflauf im Testmandanten für «A» keine Manifest-Abweichung

  @req-15.5
  Szenario: Abweichende Prüfsumme gegenüber Datenbank und Manifest wird gemeldet
    Angenommen ein eigener Mandant «pl-manifest-b» für den Manifest-Prüflauf
    Und im Testmandanten ist das Dokument «A» am «10.01.2026» um «09:15» archiviert
    Und die gespeicherte Prüfsumme des Testdokuments «A» wird verfälscht
    Wenn für den Testmandanten das Tages-Manifest bis «10.01.2026» erzeugt wird
    Und der Prüflauf für den Testmandanten läuft
    Dann ist der Prüflauf im Testmandanten abgeschlossen
    Und meldet der Prüflauf im Testmandanten für «A» «PRUEFSUMME» und «MANIFEST_PRUEFSUMME»
    Und hat der Inhaber eine Mail zum Test-Prüflauf erhalten, die nur IDs nennt

  @req-15.5
  Szenario: Datei ohne Eintrag im zuständigen Tages-Manifest wird gemeldet
    Angenommen ein eigener Mandant «pl-manifest-c» für den Manifest-Prüflauf
    Und im Testmandanten liegt das Dokument «A» ohne Archivierungszeitpunkt
    Wenn für den Testmandanten das Tages-Manifest bis «10.01.2026» erzeugt wird
    Und der Archivierungszeitpunkt des Testdokuments «A» wird auf «10.01.2026» um «09:15» gesetzt
    Und der Prüflauf für den Testmandanten läuft
    Dann ist der Prüflauf im Testmandanten abgeschlossen
    Und meldet der Prüflauf im Testmandanten für «A» nur «MANIFEST_EINTRAG_FEHLT»

  @req-15.5
  Szenario: Datei nach dem jüngsten Manifest wird noch nicht verglichen
    Angenommen ein eigener Mandant «pl-manifest-d» für den Manifest-Prüflauf
    Und im Testmandanten ist das Dokument «A» am «10.01.2026» um «09:15» archiviert
    Wenn für den Testmandanten das Tages-Manifest bis «09.01.2026» erzeugt wird
    Und der Prüflauf für den Testmandanten läuft
    Dann ist der Prüflauf im Testmandanten abgeschlossen
    Und meldet der Prüflauf im Testmandanten für «A» keine Manifest-Abweichung

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

  @req-15.9
  Szenario: Der Prüflauf verlängert die Sperre eines Manifests im Modus COMPLIANCE, gekappt auf die Höchstfrist
    Angenommen im Mandanten «urinet» liegt ein Manifest im Modus COMPLIANCE, gesperrt für 2 Stunden
    Wenn der Prüflauf für «urinet» läuft
    Dann ist der Prüflauf abgeschlossen
    Und meldet der Prüflauf für das Manifest nichts
    Und ist die Sperre des Manifests im Modus COMPLIANCE verlängert, höchstens bis jetzt plus 1 Tag

  # 15.10 nennt nur archiv/; die Löschmarke unter manifest/ meldet der Prüflauf darüber hinaus, das Szenario belegt
  # dazu die Verlängerung der Manifest-Sperre (15.9; Review PR #97)
  @req-15.9
  Szenario: Eine Löschmarke unter manifest/ steht im Bericht, ohne Dokument
    Angenommen im Mandanten «urinet» liegt ein Manifest im Modus COMPLIANCE, gesperrt für 2 Stunden
    Und das Manifest hat eine Löschmarke
    Wenn der Prüflauf für «urinet» läuft
    Dann ist der Prüflauf abgeschlossen
    Und meldet der Prüflauf für das Manifest nur eine «LOESCHMARKE» ohne Dokument
    Und ist die Sperre des Manifests im Modus COMPLIANCE verlängert, höchstens bis jetzt plus 1 Tag

  @req-15.5
  Szenario: Der Zeitplan startet je Monat höchstens einen Lauf
    Wenn der Prüflauf für «urinet» und den Monat «2999-01» zweimal gestartet wird
    Dann hat nur der erste Start einen Lauf angelegt

  @req-15.5
  Szenario: Der tägliche Takt holt einen verpassten Monatslauf nach und startet keinen zweiten
    Wenn der Takt des Zeitplans am 02.01.2999 um 04:00 auslöst
    Und der Takt des Zeitplans am 03.01.2999 um 04:00 auslöst
    Dann hat nur der erste Start einen Lauf angelegt
