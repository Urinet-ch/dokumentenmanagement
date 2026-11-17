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
    Und die Zahl der Dokumente im Mandanten «urinet» wird gemerkt
    Wenn der Schlüssel «A» zwei Uploads derselben Datei «gleichzeitig.pdf» mit Referenz «ref-004» gleichzeitig sendet
    Dann liefern die beiden Uploads genau HTTP 202 und HTTP 200
    Und gehören beide Uploads zur selben Dokument-ID
    Und hat der Mandant «urinet» die Dokumentzahl um 1 erhöht
    Und gibt es in der Referenztabelle für «A» und Referenz «ref-004» genau 1 Eintrag

  @req-10.2 @req-10.3
  Szenario: Zwei gleichzeitige Uploads derselben Referenz mit verschiedenen Dateien ergeben genau ein Dokument
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und die Zahl der Dokumente im Mandanten «urinet» wird gemerkt
    Wenn der Schlüssel «A» zwei Uploads mit Referenz «ref-020» gleichzeitig sendet, einmal die Datei «parallel-1.pdf» und einmal die Datei «parallel-2.pdf»
    Dann liefern die beiden Uploads genau HTTP 202 und HTTP 409
    Und hat der Mandant «urinet» die Dokumentzahl um 1 erhöht
    Und gibt es in der Referenztabelle für «A» und Referenz «ref-020» genau 1 Eintrag

  @req-10.2 @req-10.3
  Szenario: Gleichzeitige Uploads verschiedener Referenzen blockieren sich nicht
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» zwei Uploads der Dateien «parallel-3.pdf» und «parallel-4.pdf» mit den Referenzen «ref-021» und «ref-022» gleichzeitig sendet
    Dann liefern die beiden Uploads genau HTTP 202 und HTTP 202
    Und haben die beiden Uploads verschiedene Dokument-IDs

  @req-10.3
  Szenario: Eine verworfene Referenz gilt als frei und bindet ein neues Dokument
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «verworfen-1.pdf» mit Referenz «ref-023» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Wenn das Dokument der Referenz «ref-023» von «A» wird verworfen
    Und der Schlüssel «A» eine andere Datei «verworfen-2.pdf» mit Referenz «ref-023» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Und gibt es in der Referenztabelle für «A» und Referenz «ref-023» genau 1 Eintrag

  @req-10.3
  Szenario: Eine Dublette auf Seite 2 eines Dokuments bindet die Datei der Webseite und ist wiederholbar
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «seite-1.pdf» mit Referenz «ref-024» hochlädt
    Dann ist die letzte Antwort HTTP 202
    Angenommen das Dokument der Referenz «ref-024» von «A» hat zusätzlich als Seite 2 die Datei «seite-2.pdf»
    Wenn der Schlüssel «A» dieselbe Datei «seite-2.pdf» mit Referenz «ref-025» hochlädt
    Dann antwortet der Upload HTTP 200 mit «dublette = true» und derselben Dokument-ID
    Wenn der Schlüssel «A» dieselbe Datei «seite-2.pdf» mit Referenz «ref-025» erneut hochlädt
    Dann ist die letzte Antwort HTTP 200

  @req-10.3 @req-10.4
  Szenario: Gleiche Referenz in zwei Webseiten ergibt je ein eigenes Dokument, Fehler nennen keine Interna
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «erfassung-b» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «web-a.pdf» mit Referenz «ref-026» hochlädt
    Und der Schlüssel «B» die Datei «web-b.pdf» mit Referenz «ref-026» hochlädt
    Dann sind die Dokument-IDs der Referenz «ref-026» von «A» und «B» verschieden
    Wenn der Schlüssel «A» eine andere Datei «web-c.pdf» mit Referenz «ref-026» hochlädt
    Dann ist die letzte Antwort HTTP 409 mit «REFERENZ_VERGEBEN»
    Und enthält die letzte Antwort keine internen Details

  @req-10.2 @req-10.9 @req-10.11
  Szenario: Zusätzliche Multipart-Felder und nicht gemeldete Bearbeiter werden abgelehnt
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» sendet zusätzlich zur Datei «zusaetzlich.pdf» mit Referenz «ref-005» ein Multipart-Feld «dateiname»
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «A» die Datei «ohne-bearbeiter.pdf» mit Referenz «ref-006» ohne Bearbeiterkennung hochlädt
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»

  @req-10.2 @req-10.9
  Szenario: Unzulässige Multipart-Teile und Bearbeiterkennungen werden abgelehnt und speichern nichts
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und die Zahl der Dokumente im Mandanten «urinet» wird gemerkt
    Wenn der Schlüssel «A» sendet die Datei «teile-1.pdf» mit Referenz «ref-030» und zusätzlich den Teil «datei»
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «A» sendet die Datei «teile-2.pdf» mit Referenz «ref-031» und zusätzlich den Teil «url»
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «A» sendet die Datei «teile-3.pdf» mit Referenz «ref-032» und zusätzlich den Teil «referenz»
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Und hat der Mandant «urinet» die Dokumentzahl um 0 erhöht
    Und gibt es in der Referenztabelle für «A» und Referenz «ref-030» genau 0 Eintrag
    Und gibt es in der Referenztabelle für «A» und Referenz «ref-031» genau 0 Eintrag
    Und gibt es in der Referenztabelle für «A» und Referenz «ref-032» genau 0 Eintrag

  @req-10.9
  Szenario: Ungültige Bearbeiterkennungen werden abgelehnt und speichern nichts
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und die Zahl der Dokumente im Mandanten «urinet» wird gemerkt
    Wenn der Schlüssel «A» die Datei «teile-4.pdf» mit Referenz «ref-033» und der Bearbeiterkennung «wp 1;x» sendet
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «A» die Datei «teile-5.pdf» mit Referenz «ref-034» und der Bearbeiterkennung «aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa» sendet
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Und hat der Mandant «urinet» die Dokumentzahl um 0 erhöht

  @req-10.9
  Szenario: Die Detailansicht verlangt eine Bearbeiterkennung
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «detail-ohne.pdf» mit Referenz «ref-035» hochlädt
    Und der Schlüssel «A» das Dokument der Referenz «ref-035» ohne Bearbeiterkennung abruft
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
    Wenn der Schlüssel «A» die Liste mit dem unzulässigen Filter «dateiname» abruft
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»

  @req-10.13
  Szenario: Die Liste weist die Referenz nur der eigenen Webseite aus
    Angenommen der Erfassungs-Schlüssel «A» für die Webseite «erfassung-a» im Mandanten «urinet» ist angelegt
    Und der Erfassungs-Schlüssel «B» für die Webseite «erfassung-b» im Mandanten «urinet» ist angelegt
    Wenn der Schlüssel «A» die Datei «liste-ref.pdf» mit Referenz «ref-040» hochlädt
    Und der Schlüssel «A» die Liste ohne Filter abruft
    Dann weist die letzte Liste für das Dokument der Referenz «ref-040» von «A» die Referenz aus
    Wenn der Schlüssel «B» die Liste ohne Filter abruft
    Dann weist kein Eintrag der letzten Liste die Referenz «ref-040» aus

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
