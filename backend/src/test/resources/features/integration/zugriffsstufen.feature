# language: de
@spec-03 @zugriffsstufen
Funktionalität: Zugriffsstufen der Webseiten-Schnittstelle (Aufgabe 11.1)
  Gemeldete Stufe (Header X-Archiv-Zugriff), Kappung durch den Lese-Schlüssel, fester Filter auf veroeffentlicht,
  Zugriffsstufe und Vertraulichkeit sowie 404 bei zu hoher Stufe. Die Szenarien laufen gegen die Probe /public/v1/probe
  (nur in src/test); die echten Endpunkte folgen mit Aufgabe 11.2. Alle Dokumente sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Lese-Schlüssel «vorstand» der Webseite «probe-stufen» im Mandanten «urinet» hat die höchste Stufe «vorstand»
    Und der Lese-Schlüssel «mitglieder» der Webseite «probe-stufen» im Mandanten «urinet» hat die höchste Stufe «mitglieder»
    Und der Lese-Schlüssel «oeffentlich» der Webseite «probe-stufen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und im Mandanten «urinet» liegen diese Stufentest-Dokumente:
      | name | status     | vertraulichkeit | zugriff     | veroeffentlicht |
      | a    | ARCHIVIERT | NORMAL          | oeffentlich | true            |
      | b    | ARCHIVIERT | NORMAL          | mitglieder  | true            |
      | c    | ARCHIVIERT | NORMAL          | vorstand    | true            |

  @req-3.7 @req-5.3
  Szenariogrundriss: Die gemeldete Stufe bestimmt die Stufen des Filters
    Wenn der Schlüssel «vorstand» «/public/v1/probe/dokumente» mit dem Header «X-Archiv-Zugriff» und dem Wert «<stufe>» aufruft
    Dann liefert der feste Filter genau die Dokumente «<dokumente>»

    Beispiele:
      | stufe       | dokumente |
      | oeffentlich | a         |
      | mitglieder  | a,b       |
      | vorstand    | a,b,c     |

  @req-3.7 @req-5.3
  Szenario: Ohne Header gilt die Stufe oeffentlich, auch bei einem Schlüssel mit Stufe vorstand
    Wenn der Schlüssel «vorstand» «/public/v1/probe/stufe» ohne Stufen-Header aufruft
    Dann nennt die Probe die gemeldete Stufe «oeffentlich», die wirksame Stufe «oeffentlich» und die Stufen «oeffentlich»
    Wenn der Schlüssel «vorstand» «/public/v1/probe/dokumente» ohne Stufen-Header aufruft
    Dann liefert der feste Filter genau die Dokumente «a»

  @req-5.3 @req-5.9
  Szenario: Die gemeldete Stufe bis zur Stufe des Schlüssels wird bedient
    Wenn der Schlüssel «mitglieder» «/public/v1/probe/stufe» mit dem Header «X-Archiv-Zugriff» und dem Wert «mitglieder» aufruft
    Dann nennt die Probe die gemeldete Stufe «mitglieder», die wirksame Stufe «mitglieder» und die Stufen «oeffentlich,mitglieder»
    Wenn der Schlüssel «mitglieder» «/public/v1/probe/dokumente» mit dem Header «X-Archiv-Zugriff» und dem Wert «oeffentlich» aufruft
    Dann liefert der feste Filter genau die Dokumente «a»

  @req-5.9
  Szenariogrundriss: Eine gemeldete Stufe über der Stufe des Schlüssels ergibt 403 und nichts
    Wenn der Schlüssel «<schluessel>» «/public/v1/probe/dokumente» mit dem Header «X-Archiv-Zugriff» und dem Wert «<stufe>» aufruft
    Dann liefert das Archiv Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und steht in der Antwort weder «stufentest» noch «vorstand-»

    Beispiele:
      | schluessel  | stufe      |
      | mitglieder  | vorstand   |
      | oeffentlich | mitglieder |
      | oeffentlich | vorstand   |

  @req-5.9
  Szenariogrundriss: Ein unbekannter oder ungültiger Wert ergibt 400 ohne den Wert zu nennen
    Wenn der Schlüssel «vorstand» «/public/v1/probe/stufe» mit dem Header «X-Archiv-Zugriff» und dem Wert «<wert>» aufruft
    Dann liefert das Archiv Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und steht in der Antwort weder «<wert>» noch «meldung»

    Beispiele:
      | wert                   |
      | Mitglieder             |
      | admin-4711             |
      | alle                   |
      | oeffentlich,vorstand   |
      | mitglieder vorstand    |

  @req-5.9
  Szenario: Zwei Header X-Archiv-Zugriff ergeben 400
    Wenn der Schlüssel «vorstand» «/public/v1/probe/stufe» mit zwei Headern «X-Archiv-Zugriff» und den Werten «oeffentlich» und «vorstand» aufruft
    Dann liefert das Archiv Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

  @req-5.9
  Szenario: Ein leerer Header X-Archiv-Zugriff ergibt 400
    Wenn der Schlüssel «vorstand» «/public/v1/probe/stufe» mit dem Header «X-Archiv-Zugriff» und dem Wert «» aufruft
    Dann liefert das Archiv Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

  @req-5.3
  Szenario: Der Client-Hash wird übernommen, ein ungültiger ergibt 400
    Wenn der Schlüssel «oeffentlich» «/public/v1/probe/stufe» mit dem Header «X-Archiv-Client» und dem Wert «0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef» aufruft
    Dann nennt die Probe den Client-Hash «0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef»
    Wenn der Schlüssel «oeffentlich» «/public/v1/probe/stufe» mit dem Header «X-Archiv-Client» und dem Wert «192.0.2.1» aufruft
    Dann liefert das Archiv Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und steht in der Antwort weder «192.0.2.1» noch «meldung»

  @req-3.6 @req-3.7
  Szenario: Der feste Filter liefert nur archivierte, veröffentlichte Dokumente der Vertraulichkeit NORMAL mit gültiger Stufe
    Angenommen im Mandanten «urinet» liegen diese Stufentest-Dokumente:
      | name         | status        | vertraulichkeit | zugriff     | veroeffentlicht |
      | neu          | NEU           | NORMAL          | oeffentlich | true            |
      | eingang      | EINGANGSKORB  | NORMAL          | oeffentlich | true            |
      | verworfen    | VERWORFEN     | NORMAL          | oeffentlich | true            |
      | intern       | ARCHIVIERT    | INTERN          | oeffentlich | true            |
      | personal     | ARCHIVIERT    | PERSONAL        | vorstand    | true            |
      | zurueck      | ARCHIVIERT    | NORMAL          | oeffentlich | false           |
      | text         | ARCHIVIERT    | NORMAL          | oeffentlich | "true"          |
      | ohne-flag    | ARCHIVIERT    | NORMAL          | oeffentlich | -               |
      | ohne-stufe   | ARCHIVIERT    | NORMAL          | -           | true            |
      | fremde-stufe | ARCHIVIERT    | NORMAL          | geheim      | true            |
    Wenn der Schlüssel «vorstand» «/public/v1/probe/dokumente» mit dem Header «X-Archiv-Zugriff» und dem Wert «vorstand» aufruft
    Dann liefert der feste Filter genau die Dokumente «a,b,c»

  @req-5.5
  Szenario: Ein Dokument über der Stufe der Anfrage ist 404 wie ein unbekanntes Dokument
    Wenn der Schlüssel «vorstand» das Dokument «c» mit der Stufe «mitglieder» abruft
    Und der Schlüssel «vorstand» ein unbekanntes Dokument mit der Stufe «mitglieder» abruft
    Dann ist die Antwort für das Dokument gleich der für ein unbekanntes Dokument

  @req-5.5
  Szenario: Ein Dokument bis zur Stufe der Anfrage ist sichtbar
    Wenn der Schlüssel «vorstand» das Dokument «c» mit der Stufe «vorstand» abruft
    Dann enthält die Antwort das Dokument «c» mit dem Titel «stufentest-c»

  @req-5.2 @req-5.3
  Szenario: Der Mandant ergibt sich aus dem Schlüssel, Dokumente anderer Mandanten bleiben unsichtbar
    Angenommen im Mandanten «vhf-gsk» liegen diese Stufentest-Dokumente:
      | name | status     | vertraulichkeit | zugriff     | veroeffentlicht |
      | x    | ARCHIVIERT | NORMAL          | oeffentlich | true            |
    Wenn der Schlüssel «vorstand» «/public/v1/probe/dokumente» mit dem Header «X-Archiv-Zugriff» und dem Wert «vorstand» aufruft
    Dann liefert der feste Filter genau die Dokumente «a,b,c»
    Wenn der Schlüssel «vorstand» das Dokument «x» mit der Stufe «vorstand» abruft
    Dann liefert das Archiv Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-5.6
  Szenariogrundriss: Die Webseiten-Schnittstelle gibt kein CORS frei
    Wenn der Schlüssel «oeffentlich» «/public/v1/probe» mit dem Herkunftskopf «https://webseite.example» und der Methode <methode> aufruft
    Dann trägt die Antwort keinen Header «Access-Control-Allow-Origin»

    Beispiele:
      | methode |
      | GET     |
      | OPTIONS |
