# language: de
@spec-03 @webseiten-erfassung
Funktionalität: Erfassung über einen Webseiten-Schlüssel
  Die Erfassungs-Schnittstelle verwendet dieselbe Dokumentlogik wie die Web-App. Alle Schlüssel und Dokumente sind
  synthetisch und werden nach jedem Szenario bereinigt.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-3.1 @req-10.1 @req-10.4 @req-10.9 @req-10.11 @req-10.12
  Szenario: Upload, Referenzstatus und Detailansicht enthalten keinen Dateinamen
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «interne-datei.pdf» mit Referenz «ref-001» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Und die Upload-Antwort enthält eine ID, den Status und ETag
    Und die letzte Antwort enthält keinen Dateinamen
    Wenn der Schlüssel «A» den Status der Referenz «ref-001» abruft
    Dann ist die letzte Antwort HTTP 200
    Und der Status enthält die ID des letzten Uploads und die Referenz «ref-001»
    Wenn der Schlüssel «A» das Dokument der Referenz «ref-001» abruft
    Dann ist die letzte Antwort HTTP 200
    Und die Detailansicht kennzeichnet Quelle «WEBSEITE»
    Und die letzte Antwort enthält keinen Dateinamen
    Und das Protokoll enthält den Bearbeiter des Uploads

  @req-10.2 @req-10.3
  Szenario: Eine Referenz ist idempotent, unveränderlich belegt und Dokumentduplikate werden erkannt
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «gleiche-datei.pdf» mit Referenz «ref-002» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn der Schlüssel «A» dieselbe Datei «gleiche-datei.pdf» mit Referenz «ref-002» erneut hochlädt
    Dann antwortet der wiederholte Upload HTTP 200 mit derselben Dokument-ID
    Wenn der Schlüssel «A» dieselbe Datei «gleiche-datei.pdf» mit Referenz «ref-003» hochlädt
    Dann antwortet der Upload HTTP 200 mit «dublette = true» und derselben Dokument-ID
    Und der Erfassungs-Schlüssel «B» für dieselbe Webseite wie «A» ist angelegt
    Wenn der Schlüssel «B» eine andere Datei «andere-datei.pdf» mit Referenz «ref-002» hochlädt
    Dann ist die letzte Antwort HTTP 409 mit «REFERENZ_VERGEBEN»

  @req-10.2
  Szenario: Zwei gleichzeitige Uploads derselben Referenz liefern dasselbe Dokument
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» zwei Uploads derselben Datei «gleichzeitig.pdf» mit Referenz «ref-004» gleichzeitig sendet
    Dann liefern beide Uploads HTTP 200 oder 202 und dieselbe Dokument-ID

  @req-10.2 @req-10.9 @req-10.11
  Szenario: Zusätzliche Multipart-Felder und nicht gemeldete Bearbeiter werden abgelehnt
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» sendet zusätzlich zur Datei «zusaetzlich.pdf» mit Referenz «ref-005» ein Multipart-Feld «dateiname»
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «A» die Datei «ohne-bearbeiter.pdf» mit Referenz «ref-006» ohne Bearbeiterkennung hochlädt
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»

  @req-10.12
  Szenario: Ein geänderter Anzeigename wird dem Bearbeiterverzeichnis append-only hinzugefügt
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» den Anzeigenamen des Bearbeiters «wp:1» auf «Neue Testperson» ändert
    Dann antwortet die Bearbeitermeldung mit HTTP 204
    Und das Bearbeiterverzeichnis enthält «Neue Testperson» für «A»

  @req-10.13
  Szenario: Die Liste filtert nach Status, Reihe, Jahr, Veröffentlichungsstatus und Suchbegriff
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und im Mandanten «urinet» liegt ein synthetisches Dokument mit allen Listenfiltern
    Wenn der Schlüssel «A» die Liste mit allen Listenfiltern abruft
    Dann enthält die Liste das synthetische Dokument ohne Dateinamen
    Wenn der Schlüssel «A» die Liste mit «veroeffentlicht = false» abruft
    Dann ist die Liste leer

  @req-1.4 @req-9.1 @req-9.4 @req-12.1
  Szenario: Referenzen und Dokumente sind je Webseite und Mandant getrennt, Leseschlüssel dürfen nicht erfassen
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «erfassung-b» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «C» für die Webseite «erfassung-c» im Mandanten «vhf-gsk» ist angelegt
    Und der Lese-Schlüssel «L» für die Webseite «lesen-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «mandantendatei.pdf» mit Referenz «ref-007» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn der Schlüssel «B» den Status der Referenz «ref-007» abruft
    Dann ist die letzte Antwort HTTP 404 mit «NICHT_GEFUNDEN»
    Wenn der Schlüssel «C» das Dokument der Referenz «ref-007» abruft
    Dann ist die letzte Antwort HTTP 404 mit «NICHT_GEFUNDEN»
    Wenn der Schlüssel «L» die Datei «leseschluessel.pdf» mit Referenz «ref-008» hochlädt
    Dann ist die letzte Antwort HTTP 403 mit «NICHT_ERLAUBT»
    Wenn der Schlüssel «A» den lesenden Endpunkt «/public/v1/filter» aufruft
    Dann ist die letzte Antwort HTTP 403 mit «NICHT_ERLAUBT»
