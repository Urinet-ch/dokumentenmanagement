# language: de
@spec-01
Funktionalität: Überwachung, Alarm an den Inhaber und Kennzahlen
  Steht ein Dokument länger als die Grenze (Standard 1 Stunde) in VERARBEITUNG, FEHLER oder ARCHIVIERUNG_FEHLER,
  erhält der Inhaber des Mandanten genau eine Mail, die nur IDs und Status nennt. Kennzahlen stehen unter /q/metrics,
  das von aussen nicht erreichbar ist (Aufgabe 5.6). Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-16.5 @req-16.7
  Szenario: Ein zu lange hängendes Dokument meldet die Überwachung genau einmal und nur mit IDs
    Angenommen im Mandanten «urinet» steht das Dokument «A» seit 120 Minuten im Status «FEHLER»
    Und im Mandanten «urinet» steht das Dokument «B» seit 10 Minuten im Status «VERARBEITUNG»
    Und im Mandanten «vhf-gsk» steht das Dokument «C» seit 61 Minuten im Status «ARCHIVIERUNG_FEHLER»
    Und im Mandanten «urinet» steht das Dokument «D» seit 120 Minuten im Status «EINGANGSKORB»
    Wenn die Überwachung läuft
    Und die Überwachung läuft
    Dann hat der Inhaber zu «A» Alarm-Mails zu den Status «FEHLER» erhalten, die nur ID und Status nennen
    Und hat der Inhaber zu «C» Alarm-Mails zu den Status «ARCHIVIERUNG_FEHLER» erhalten, die nur ID und Status nennen
    Und hat der Inhaber zu «B» keine Alarm-Mail erhalten
    Und hat der Inhaber zu «D» keine Alarm-Mail erhalten

  @req-16.5
  Szenario: Nach einem Statuswechsel ist der Alarm erledigt, ein neues Hängen meldet die Überwachung wieder
    Angenommen im Mandanten «urinet» steht das Dokument «A» seit 120 Minuten im Status «VERARBEITUNG»
    Wenn die Überwachung läuft
    Angenommen das Dokument «A» wechselt in den Status «FEHLER»
    Wenn die Überwachung läuft
    Dann hat der Inhaber zu «A» Alarm-Mails zu den Status «VERARBEITUNG» erhalten, die nur ID und Status nennen
    Angenommen das Dokument «A» steht seit 90 Minuten in diesem Status
    Wenn die Überwachung läuft
    Und die Überwachung läuft
    Dann hat der Inhaber zu «A» Alarm-Mails zu den Status «VERARBEITUNG,FEHLER» erhalten, die nur ID und Status nennen
    Angenommen das Dokument «A» wechselt in den Status «VERARBEITUNG»
    Und das Dokument «A» steht seit 90 Minuten in diesem Status
    Wenn die Überwachung läuft
    Dann hat der Inhaber zu «A» Alarm-Mails zu den Status «VERARBEITUNG,FEHLER,VERARBEITUNG» erhalten, die nur ID und Status nennen

  @req-16.4
  Szenario: Kennzahlen stehen intern bereit, öffentlich ist unter /q/ nur der schlanke Health-Pfad
    Angenommen im Mandanten «urinet» steht das Dokument «A» seit 5 Minuten im Status «EINGANGSKORB»
    Wenn die Kennzahlen erhoben werden
    Dann nennen die Kennzahlen für «urinet» und «vhf-gsk» Aufträge, Dokumente je Status und den Eingangskorb
    Und zählt der Eingangskorb von «urinet» mindestens 1 Dokument
    Und leitet Traefik von aussen unter «/q/» nur «/q/health/live» weiter
