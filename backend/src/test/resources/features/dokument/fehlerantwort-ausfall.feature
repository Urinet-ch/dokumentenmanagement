# language: de
@spec-01
Funktionalität: Keine vertraulichen Angaben bei Speicherausfall

  @req-3.2 @req-16.7 @qa-fehlerantwort-ausfall
  Szenario: Speicherausfall beim Upload gibt eine strukturierte Fehlerantwort ohne Dateinamen im Log
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und «inhaber-urinet» hat die Datei «basis.pdf» (pdf) hochgeladen
    Und der Logmitschnitt für eine Fehlerantwort läuft
    Und der Object Storage fällt nach 0 abgelegten Datei aus (NETZ)
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei                       | typ |
      | SYNTHETISCHER_INHALT_7F42.pdf | pdf |
    Dann antwortet der Upload mit Status 503 und Fehlercode «SPEICHER_NICHT_ERREICHBAR»
    Und ist im Mandanten «urinet» nichts gespeichert
    Und das Fehlerantwort-Log enthält weder «SYNTHETISCHER_INHALT_7F42» noch einen Stacktrace
