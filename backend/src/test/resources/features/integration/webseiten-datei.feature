# language: de
@spec-03 @webseiten-datei
Funktionalität: Dateiauslieferung der Webseiten-Schnittstelle (Aufgabe 11.8)
  Dokumente der Stufe oeffentlich liefern eine vorsignierte Adresse (Szenarien in webseiten-lesen.feature, Req 13.1),
  Dokumente der Stufen mitglieder und vorstand streamt die API in Blöcken (Req 13.2). Gleichzeitige Streams sind
  begrenzt (Test: 4 gesamt, 2 je Schlüssel, Leerlauf 3 Sekunden), das Protokoll steht vor dem ersten Byte, und kein
  Stream hält eine Transaktion. Alle Dokumente und Dateien sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Webseiten-Lesezugang «vorstand» der Webseite «probe-datei» im Mandanten «urinet» hat die höchste Stufe «vorstand»
    Und der Webseiten-Lesezugang «zweiter» der Webseite «probe-datei» im Mandanten «urinet» hat die höchste Stufe «vorstand»
    Und der Webseiten-Lesezugang «dritter» der Webseite «probe-datei» im Mandanten «urinet» hat die höchste Stufe «vorstand»
    Und der Webseiten-Lesezugang «mitglieder» der Webseite «probe-datei» im Mandanten «urinet» hat die höchste Stufe «mitglieder»
    Und der Webseiten-Lesezugang «oeffentlich» der Webseite «probe-datei» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und im Mandanten «urinet» sind diese Reihen angelegt:
      | code        | name            | aktiv |
      | WEBTEST_BUL | Probe-Bulletin  | true  |
    Und im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff     | text                       |
      | b1   | WEBTEST_BUL | oeffentlich | Öffentliches Heft          |
      | b3   | WEBTEST_BUL | mitglieder  | Heft nur für Mitglieder    |
      | v1   | WEBTEST_BUL | vorstand    | Protokoll des Vorstands    |

  @req-13.2
  Szenariogrundriss: Dokumente der Stufen mitglieder und vorstand kommen als Stream, nie als Adresse
    Angenommen das Dokument «<dokument>» hat eine Datei
    Wenn der Webseiten-Lesezugang «<schluessel>» die Datei des Dokuments «<dokument>» mit der Stufe «<stufe>» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und liefert die Antwort die Datei von «<dokument>» als «application/pdf» mit der Länge der Datei

    Beispiele:
      | schluessel | stufe      | dokument |
      | mitglieder | mitglieder | b3       |
      | vorstand   | mitglieder | b3       |
      | vorstand   | vorstand   | b3       |
      | vorstand   | vorstand   | v1       |

  @req-13.2 @req-5.5 @req-9.4
  Szenariogrundriss: Was die Anfrage nicht sehen darf, ist 404, nie ein Teil der Datei, und schreibt kein Protokoll
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff     | veroeffentlicht | vertraulichkeit | bestaetigt | status       | text  |
      | z    | WEBTEST_BUL | <zugriff>   | <veroeffentlicht> | <vertraulichkeit> | <bestaetigt> | <status>  | Datei |
    Und das Dokument «z» hat eine Datei
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «<schluessel>» die Datei des Dokuments «z» mit der Stufe «<stufe>» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und steht im Zugriffsprotokoll von «urinet» kein neuer Eintrag
    Und belegt die API noch 0 Plätze für Streams

    Beispiele:
      | schluessel  | stufe       | zugriff    | veroeffentlicht | vertraulichkeit | bestaetigt | status       |
      | mitglieder  | mitglieder  | vorstand   | true            | NORMAL          | ja         | ARCHIVIERT   |
      | vorstand    | mitglieder  | vorstand   | true            | NORMAL          | ja         | ARCHIVIERT   |
      | oeffentlich | oeffentlich | mitglieder | true            | NORMAL          | ja         | ARCHIVIERT   |
      | oeffentlich | oeffentlich | vorstand   | true            | NORMAL          | ja         | ARCHIVIERT   |
      | vorstand    | oeffentlich | mitglieder | true            | NORMAL          | ja         | ARCHIVIERT   |
      | vorstand    | vorstand    | mitglieder | false           | NORMAL          | ja         | ARCHIVIERT   |
      | vorstand    | vorstand    | mitglieder | true            | INTERN          | ja         | ARCHIVIERT   |
      | vorstand    | vorstand    | mitglieder | true            | NORMAL          | nein       | ARCHIVIERT   |
      | vorstand    | vorstand    | mitglieder | true            | NORMAL          | ja         | EINGANGSKORB |
      | vorstand    | vorstand    | mitglieder | true            | NORMAL          | ja         | VERWORFEN    |
      | vorstand    | vorstand    | -          | true            | NORMAL          | ja         | ARCHIVIERT   |

  @req-9.1
  Szenario: Ein Schlüssel eines anderen Mandanten kann keine Datei streamen
    Angenommen im Mandanten «vhf-gsk» sind diese Reihen angelegt:
      | code        | name                  | aktiv |
      | WEBTEST_VHF | Fremdmandanten-Reihe  | true  |
    Und im Mandanten «vhf-gsk» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff     | text         |
      | x    | WEBTEST_VHF | mitglieder  | Fremdmandant |
    Und das Dokument «x» hat eine Datei
    Wenn der Webseiten-Lesezugang «oeffentlich» die Datei des Dokuments «x» mit der Stufe «oeffentlich» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und belegt die API noch 0 Plätze für Streams

  @req-13.2
  Szenario: Ein Dokument ohne Datei oder ohne Sicht ist 404 und ein unbekanntes ebenso
    Wenn der Webseiten-Lesezugang «vorstand» die Datei des Dokuments «b3» mit der Stufe «vorstand» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «vorstand» ein unbekanntes Dokument mit der Stufe «vorstand» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-13.5
  Szenario: Der Stream trägt Typ, Länge und strenge Header und nennt keinen Dateinamen
    Angenommen das Dokument «b3» hat eine Datei
    Und das Dokument «b3» trägt den Dateinamen «geheimer-dateiname-4711.pdf» und den Partner «Geheimpartner AG»
    Wenn der Webseiten-Lesezugang «mitglieder» die Datei des Dokuments «b3» mit der Stufe «mitglieder» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und liefert die Antwort die Datei von «b3» als «application/pdf» mit der Länge der Datei
    Und trägt die Lese-Antwort den Header «Cache-Control» mit dem Wert «private, no-store»
    Und trägt die Lese-Antwort den Header «X-Content-Type-Options» mit dem Wert «nosniff»
    Und trägt die Lese-Antwort den Header «Content-Disposition» mit dem Wert «inline»
    Und trägt die Lese-Antwort den Header «Accept-Ranges» mit dem Wert «none»
    Und steht in keinem Header der Antwort der Dateiname «geheimer-dateiname»
    Und steht in keinem Header der Antwort der Dateiname «Geheimpartner»

  @req-13.5
  Szenario: Eine Range-Anfrage wird nicht bedient, sondern mit der ganzen Datei beantwortet
    Angenommen das Dokument «b3» hat eine Datei
    Wenn der Webseiten-Lesezugang «mitglieder» die Datei des Dokuments «b3» mit der Stufe «mitglieder» und dem Range-Kopf «bytes=2-5» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und liefert die Antwort die Datei von «b3» als «application/pdf» mit der Länge der Datei
    Und trägt die Lese-Antwort keinen Header «Content-Range»
    Und trägt die Lese-Antwort den Header «Accept-Ranges» mit dem Wert «none»

  @req-13.6 @req-5.4
  Szenario: Jeder Stream wird mit gekürzter IP protokolliert, ein abgelehnter nicht
    Angenommen das Dokument «b3» hat eine Datei
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «vorstand» die Datei des Dokuments «b3» mit der Stufe «mitglieder» und der Adresse «2001:db8:1:2::5» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und steht im Zugriffsprotokoll von «urinet» genau ein neuer Eintrag HERUNTERLADEN für «b3» von der Adresse «2001:db8:1::» ohne weitere Angaben

  @req-13.8
  Szenario: Das Protokoll steht vor dem ersten Byte, und der Stream hält keine Transaktion
    Angenommen das Dokument «b3» hat eine Datei von 32 MB
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Dann liefert der Stream Status 200 und den Medientyp «application/pdf»
    Und steht im Zugriffsprotokoll von «urinet» schon vor dem Ende des Streams ein Eintrag HERUNTERLADEN für «b3»
    Und hält die API während des Streams keine offene Transaktion
    Und belegt die API noch 1 Plätze für Streams

  @req-13.4
  Szenario: Je Schlüssel laufen höchstens 2 Streams, der dritte wird mit 429 abgewiesen und schreibt kein Protokoll
    Angenommen das Dokument «b3» hat eine Datei von 32 MB
    Und der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Und der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Dann lehnt der Stream mit Status 429 ab und nennt Retry-After 5
    Und nennt der abgelehnte Stream den Fehlercode «ZU_VIELE_ANFRAGEN»
    Und steht im Zugriffsprotokoll von «urinet» kein neuer Eintrag
    Und belegt die API noch 2 Plätze für Streams
    Wenn der Webseiten-Lesezugang «mitglieder» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Dann liefert der Stream Status 200 und den Medientyp «application/pdf»

  @req-13.4
  Szenario: Insgesamt laufen höchstens 4 Streams, auch für einen Schlüssel, der selbst noch keinen hat
    Angenommen das Dokument «b3» hat eine Datei von 32 MB
    Und der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Und der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Und der Webseiten-Lesezugang «zweiter» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Und der Webseiten-Lesezugang «zweiter» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Wenn der Webseiten-Lesezugang «dritter» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Dann lehnt der Stream mit Status 429 ab und nennt Retry-After 5
    Und belegt die API noch 4 Plätze für Streams
    Wenn der Client den ersten offenen Stream schliesst
    Dann sind nach höchstens 10 Sekunden nur noch 3 Plätze belegt
    Wenn der Webseiten-Lesezugang «dritter» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Dann liefert der Stream Status 200 und den Medientyp «application/pdf»

  @req-13.7
  Szenario: Bricht der Client ab, gibt die API Platz und Verbindung zum Speicher sofort frei
    Angenommen das Dokument «b3» hat eine Datei von 32 MB
    Und der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Wenn der Client den ersten offenen Stream schliesst
    Dann sind nach höchstens 10 Sekunden alle Plätze für Streams wieder frei

  @req-13.7
  Szenario: Gibt der Client 3 Sekunden lang keinen Block ab, bricht die API den Stream ab und gibt den Platz frei
    Angenommen das Dokument «b3» hat eine Datei von 32 MB
    Und der Webseiten-Lesezugang «vorstand» einen Stream für «b3» mit der Stufe «mitglieder» öffnet und nur den Anfang liest
    Dann belegt die API noch 1 Plätze für Streams
    Und sind nach höchstens 10 Sekunden alle Plätze für Streams wieder frei

  @req-13.7
  Szenario: Eine HEAD-Anfrage hält weder einen Platz noch schreibt sie ein Protokoll
    Angenommen das Dokument «b3» hat eine Datei von 32 MB
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «vorstand» die Datei des Dokuments «b3» mit der Stufe «mitglieder» per HEAD abfragt
    Dann antwortet die Lese-Schnittstelle mit Status 405
    Und steht im Zugriffsprotokoll von «urinet» kein neuer Eintrag
    Und belegt die API noch 0 Plätze für Streams

  @req-13.3
  Szenario: Messfall 50 MB an einen langsamen Client: die Datei kommt vollständig an, ohne dass die API sie im Speicher hält
    Angenommen das Dokument «b3» hat eine Datei von 50 MB
    Wenn der Webseiten-Lesezugang «vorstand» die Datei von «b3» mit der Stufe «mitglieder» langsam liest
    Dann stimmt die Prüfsumme der gelesenen Datei mit der von «b3» überein
    Und ist der Heap der API nach der Hälfte höchstens um 20 MB gewachsen
    Und sind nach höchstens 10 Sekunden alle Plätze für Streams wieder frei
