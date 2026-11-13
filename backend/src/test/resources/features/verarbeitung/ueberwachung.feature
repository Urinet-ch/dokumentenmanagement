# language: de
@spec-01
Funktionalität: Überwachung, Alarm an den Inhaber und Kennzahlen
  Steht ein Dokument länger als die Grenze (Standard 1 Stunde) in VERARBEITUNG, FEHLER oder ARCHIVIERUNG_FEHLER,
  erhält der Inhaber des Mandanten genau eine Mail, die nur IDs und Status nennt. Kennzahlen stehen unter /q/metrics,
  das von aussen nicht erreichbar ist (Aufgabe 5.6). Fehlt das Tages-Manifest des ersten fälligen Tages, meldet die
  Überwachung es ebenfalls genau einmal, mit Mandant, Datum und Status (Req 15.8, Aufgabe 5.2). Nur synthetische Testdaten (Req 16.15).

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

  @req-16.5 @req-16.7 @req-15.8
  Szenario: Ein Manifest-Auftrag in FEHLER meldet die Überwachung genau einmal, das Nachholen erledigt den Alarm
    Angenommen der Mandant «mu-fehler» ist am «10.01.2026» angelegt
    Und der Zeitstempeldienst «haupt» antwortet mit HTTP 503
    Und der Zeitstempeldienst «zweit» antwortet mit HTTP 503
    Wenn der Manifest-Lauf für «mu-fehler» bis «10.01.2026» bis zum letzten Versuch läuft
    Dann endet der Auftrag im Status «FEHLER» nach 3 Versuchen
    Wenn die Überwachung läuft
    Und die Überwachung läuft
    Dann hat der Inhaber genau eine Mail zum fehlenden Manifest «10.01.2026» von «mu-fehler» erhalten, die nur Mandant, Datum und Status nennt
    Angenommen die Zeitstempeldienste antworten wieder
    Wenn der Manifest-Lauf für «mu-fehler» bis «10.01.2026» läuft
    Und die Überwachung läuft
    Dann ist der Alarm zum Manifest «10.01.2026» von «mu-fehler» erledigt
    Und hat der Inhaber genau eine Mail zum fehlenden Manifest «10.01.2026» von «mu-fehler» erhalten, die nur Mandant, Datum und Status nennt

  @req-16.5 @req-16.7 @req-15.8
  Szenario: Ein neuer Manifest-Auftrag nach einem Fehlschlag hebt den Alarm nicht auf, der nächste Fehlschlag meldet nicht nochmals
    Angenommen der Mandant «mu-neustart» ist am «10.01.2026» angelegt
    Wenn der Manifest-Lauf für «mu-neustart» bis «10.01.2026» läuft
    Angenommen der Zeitstempeldienst «haupt» antwortet mit HTTP 503
    Und der Zeitstempeldienst «zweit» antwortet mit HTTP 503
    Wenn der Manifest-Lauf für «mu-neustart» bis «11.01.2026» bis zum letzten Versuch läuft
    Dann endet der Auftrag im Status «FEHLER» nach 3 Versuchen
    Wenn die Überwachung läuft
    Und für «mu-neustart» wird ein Manifest-Auftrag bis «11.01.2026» eingereiht
    Und die Überwachung läuft
    Dann ist der Alarm zum Manifest «11.01.2026» von «mu-neustart» offen
    Wenn der eingereihte Manifest-Auftrag bis zum letzten Versuch läuft
    Dann endet der Auftrag im Status «FEHLER» nach 3 Versuchen
    Wenn die Überwachung läuft
    Dann hat der Inhaber genau eine Mail zum fehlenden Manifest «11.01.2026» von «mu-neustart» erhalten, die nur Mandant, Datum und Status nennt
    Und ist der Alarm zum Manifest «11.01.2026» von «mu-neustart» offen

  @req-16.5 @req-15.8
  Szenario: Ohne Fortschritt innerhalb der Frist meldet die Überwachung den ersten fälligen Tag ohne Manifest
    Angenommen der Mandant «mu-frist-innen» ist am «10.01.2026» angelegt
    Und für «mu-frist-innen» wurde das Manifest «10.01.2026» vor 5 Stunden festgehalten
    Und der Mandant «mu-frist-aussen» ist am «10.01.2026» angelegt
    Und für «mu-frist-aussen» wurde das Manifest «10.01.2026» vor 7 Stunden festgehalten
    Und der Mandant «mu-luecke» ist am «10.01.2026» angelegt
    Und der Mandant «mu-frisch» ist eben angelegt
    Wenn die Überwachung läuft
    Und die Überwachung läuft
    Dann hat der Inhaber genau eine Mail zum fehlenden Manifest «11.01.2026» von «mu-frist-aussen» erhalten, die nur Mandant, Datum und Status nennt
    Und hat der Inhaber genau eine Mail zum fehlenden Manifest «10.01.2026» von «mu-luecke» erhalten, die nur Mandant, Datum und Status nennt
    Und hat der Inhaber keine Mail zu einem fehlenden Manifest von «mu-frist-innen» erhalten
    Und hat der Inhaber keine Mail zu einem fehlenden Manifest von «mu-frisch» erhalten

  @req-16.5 @req-15.8
  Szenario: Ein Nachholen über mehr als 7 Tage mit Folgeauftrag meldet die Überwachung nicht
    Angenommen der Mandant «mu-nachholen» ist am «10.01.2026» angelegt
    Wenn der erste Manifest-Lauf für «mu-nachholen» bis «20.01.2026» läuft
    Dann hat «mu-nachholen» genau je ein Manifest für die Tage vom «10.01.2026» bis «16.01.2026»
    Und den Tag «20.01.2026» holt für «mu-nachholen» ein Folgeauftrag mit neuen Versuchen
    Wenn die Überwachung läuft
    Und die übrigen Aufträge abgearbeitet sind
    Und die Überwachung läuft
    Dann hat «mu-nachholen» genau je ein Manifest für die Tage vom «10.01.2026» bis «20.01.2026»
    Und hat der Inhaber keine Mail zu einem fehlenden Manifest von «mu-nachholen» erhalten

  @req-16.4
  Szenario: Kennzahlen stehen intern bereit, öffentlich ist unter /q/ nur der schlanke Health-Pfad
    Angenommen im Mandanten «urinet» steht das Dokument «A» seit 5 Minuten im Status «EINGANGSKORB»
    Wenn die Kennzahlen erhoben werden
    Dann nennen die Kennzahlen für «urinet» und «vhf-gsk» Aufträge, Dokumente je Status und den Eingangskorb
    Und zählt der Eingangskorb von «urinet» mindestens 1 Dokument
    Und leitet Traefik von aussen unter «/q/» nur «/q/health/live» weiter
