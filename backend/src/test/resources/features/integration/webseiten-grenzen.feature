# language: de
@spec-03 @webseiten-grenzen
Funktionalität: Grenzen je Schlüssel der Webseiten-Schnittstelle (Aufgabe 11.11)
  Jeder Webseiten-Schlüssel hat Token-Buckets (Lesen, strengere Suche, Erfassung, Uploads je Stunde); gezählt wird je
  Schlüssel, nie je Header-Wert. Bei Überschreitung antwortet das Archiv 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After».
  Die Szenarien laufen mit kleinen Testwerten (application.properties, Profil test) gegen die Probe /public/v1/probe und
  die Suche; es gibt nur synthetische Daten, Schlüssel entstehen zur Laufzeit.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-15.1
  Szenario: Schlüssel A wird begrenzt, Schlüssel B nicht, und wechselnde Header umgehen die Grenze nicht
    Angenommen es gibt den Lese-Schlüssel «A» und den Lese-Schlüssel «B» für Grenzen
    Wenn der Schlüssel «A» «/public/v1/probe» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird
    Dann wurde die erste Anfrage zwischen der 41. und der 44. abgelehnt
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «ANFRAGEN»
    Und die Ablehnung nennt weder den Schlüssel noch einen Header-Wert
    Wenn der Schlüssel «A» «/public/v1/probe» mit einem anderen Client und der Stufe «oeffentlich» aufgerufen wird
    Dann antwortet die Grenze mit Status 429
    Wenn der Schlüssel «A» «/public/v1/probe» mit einem ungültigen Client aufgerufen wird
    Dann antwortet die Grenze mit Status 429
    Wenn der Schlüssel «B» «/public/v1/probe» ohne Header aufgerufen wird
    Dann antwortet die Grenze mit Status 200
    Und die Kennzahl der abgelehnten Anfragen trägt nur den Grund und zählt für «ANFRAGEN» mindestens 1

  @req-15.1
  Szenario: Die Suche hat eine strengere Grenze als die übrigen Lese-Endpunkte
    Angenommen es gibt den Lese-Schlüssel «A» für Grenzen
    Wenn der Schlüssel «A» «/public/v1/suche?q=zitrone» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird
    Dann wurde die erste Anfrage zwischen der 7. und der 8. abgelehnt
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «SUCHE»
    Wenn der Schlüssel «A» «/public/v1/filter» ohne Header aufgerufen wird
    Dann antwortet die Grenze mit Status 200
    Und die Kennzahl der abgelehnten Anfragen trägt nur den Grund und zählt für «SUCHE» mindestens 1

  @req-15.1
  Szenario: Eine als teuer markierte Probe zählt wie die Suche
    Angenommen es gibt den Lese-Schlüssel «A» für Grenzen
    Wenn der Schlüssel «A» «/public/v1/probe/teuer» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird
    Dann wurde die erste Anfrage zwischen der 7. und der 8. abgelehnt
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «SUCHE»

  @req-15.1
  Szenario: Uploads je Stunde sind je Erfassungs-Schlüssel begrenzt
    Angenommen es gibt den Erfassungs-Schlüssel «E» und den Erfassungs-Schlüssel «F» für Grenzen
    Wenn der Schlüssel «E» «/public/v1/probe/erfassung/upload» mit wechselnden Clients und Stufen hochlädt, bis eine Anfrage abgelehnt wird
    Dann wurde die erste Anfrage zwischen der 4. und der 4. abgelehnt
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «UPLOADS»
    Wenn der Schlüssel «E» «/public/v1/probe/erfassung» ohne Header hochgeladen wird
    Dann antwortet die Grenze mit Status 200
    Wenn der Schlüssel «F» «/public/v1/probe/erfassung/upload» ohne Header hochgeladen wird
    Dann antwortet die Grenze mit Status 200
    Und die Kennzahl der abgelehnten Anfragen trägt nur den Grund und zählt für «UPLOADS» mindestens 1

  @req-6.4 @req-15.1
  Szenario: Der Chat zählt im Bucket seines Schlüssels mit, behält aber seine eigenen Grenzen
    Angenommen es gibt den Lese-Schlüssel «A» für Grenzen
    Wenn der Schlüssel «A» «/public/v1/probe» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird
    Und der Schlüssel «A» eine Chat-Frage stellt
    Dann antwortet die Grenze mit Status 429
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «ANFRAGEN»

  @req-15.1
  Szenario: Interne Endpunkte unterliegen den Grenzen der Webseiten-Schnittstelle nicht
    Wenn «inhaber-urinet» 60 Mal «/probe/dokumentarten/gibt-es-nicht» abruft
    Dann ist keine der Antworten 429
