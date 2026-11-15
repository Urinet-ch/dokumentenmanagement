# language: de
@spec-03
Funktionalität: Vertrag der Webseiten-Schnittstelle unter /public/v1 (Vorstufe, Aufgabe 11.6)
  Anmeldung, Fehlerformat und Header der Webseiten-Schnittstelle. Die Szenarien laufen gegen die Probe /public/v1/probe,
  die nur in src/test liegt (weder in der App-OpenAPI noch im Vertrag docs/schnittstelle/public-v1.yaml). Nur passende
  Webseiten-Schlüssel erhalten Zugang; App-Benutzer und andere API-Scopes werden abgelehnt.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-15.3
  Szenario: Fehlender, ungültiger, falscher und widerrufener Schlüssel ergeben dieselbe Antwort 401
    Angenommen für «urinet» gibt es einen aktiven und einen widerrufenen Webseiten-Lese-Schlüssel
    Wenn «/public/v1/probe» ohne Anmeldung, mit «ua_kaputt», mit falschem Geheimnis und mit dem widerrufenen Token abgefragt wird
    Dann ist jede Antwort 401 «NICHT_ANGEMELDET» mit «WWW-Authenticate: Bearer»
    Und unterscheiden sich die Antworten nur in «instance»

  @req-15.2
  Szenariogrundriss: Fehler unter /public/v1 sind Problem Details ohne interne Angaben
    Wenn «webseite» <methode> «<pfad>» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status <status> und Fehlercode «<fehlercode>»
    Und enthält die Antwort weder «<eingabe>» noch einen Klassennamen noch «meldung»

    Beispiele:
      | methode | pfad                                     | status | fehlercode        | eingabe          |
      | GET     | /public/v1/gibt-es-nicht-4711            | 404    | NICHT_GEFUNDEN    | gibt-es-nicht    |
      | POST    | /public/v1/probe                         | 405    | ANFRAGE_UNGUELTIG | -                |
      | GET     | /public/v1/probe/zahl?wert=EINGABE-4711  | 400    | ANFRAGE_UNGUELTIG | EINGABE-4711     |
      | GET     | /public/v1/probe/fehler/VERTRAULICH-4711 | 500    | INTERNER_FEHLER   | VERTRAULICH-4711 |

  @req-15.4
  Szenariogrundriss: Jede Antwort unter /public/ trägt Cache-Control: no-store
    Wenn «<benutzer>» <methode> «<pfad>» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status <status>
    Und trägt die Antwort «Cache-Control: no-store»

    Beispiele:
      | benutzer       | methode | pfad                                     | status |
      | webseite       | GET     | /public/v1/probe                         | 200    |
      | niemand        | GET     | /public/v1/probe                         | 401    |
      | webseite       | GET     | /public/v1/gibt-es-nicht                 | 404    |
      | webseite       | POST    | /public/v1/probe                         | 405    |
      | webseite       | GET     | /public/v1/probe/fehler/VERTRAULICH-4711 | 500    |
      | webseite       | GET     | /public/v9/probe                         | 404    |
      | webseite       | GET     | /public                                  | 404    |
      | webseite       | GET     | /public/v1;x=1/probe                     | 404    |

  @req-15.4
  Szenario: Auch die Grenze der Anfrage vor der Resource antwortet mit Problem Details und no-store
    Wenn «webseite» an «/public/v1/probe/inhalt» 2 MB sendet
    Dann antwortet die Webseiten-Schnittstelle mit Status 413 und Fehlercode «DATEI_ZU_GROSS»
    Und trägt die Antwort «Cache-Control: no-store»

  @req-11.5
  # Mit gesetztem archiv.webseite.v1.sunset prüft WebseitenRouteTest (JUnit) die drei Header auch auf Fehlern, ohne
  # eigenen Quarkus-Start; das Testprofil setzt die Eigenschaft nicht.
  Szenariogrundriss: Ohne Abkündigung fehlen Deprecation, Sunset und Link
    Wenn «webseite» GET «<pfad>» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status <status>
    Und fehlen die Header «Deprecation», «Sunset» und «Link»

    Beispiele:
      | pfad                     | status |
      | /public/v1/probe         | 200    |
      | /public/v1/gibt-es-nicht | 404    |

  @req-12.1
  Szenario: Eine OIDC-Anmeldung erreicht die Webseiten-Schnittstelle nicht
    Wenn «inhaber-urinet» GET «/public/v1/probe» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-12.1
  Szenario: Ein Lese-Schlüssel erreicht keinen Erfassungs-Endpunkt
    Wenn «webseite» POST «/public/v1/probe/erfassung» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-12.1
  Szenario: Ein Erfassungs-Schlüssel erreicht einen Erfassungs-Endpunkt
    Wenn «webseite-erfassung» POST «/public/v1/probe/erfassung» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 200

  # Doppelte Schrägstriche und kodierte Zeichen im internen Pfad prüft WebseitenRouteTest mit rohen Pfaden; Matrix-
  # Parameter (;…) prüft dieses Szenario, weil nur die echte App sie vor der Zuordnung entfernt (Review PR #140).
  Szenariogrundriss: Der interne Pfad der Webseiten-Schnittstelle ist von aussen nicht erreichbar
    Wenn «<benutzer>» GET «<pfad>» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

    Beispiele:
      | benutzer       | pfad                          |
      | inhaber-urinet | /api/v1/public/v1/probe       |
      | niemand        | /api/v1/public/v1/probe       |
      | inhaber-urinet | /api/v1/public                |
      | inhaber-urinet | /api/v1/public;x=1/v1/probe   |
      | niemand        | /api/v1/public;x=1/v1/probe   |
      | inhaber-urinet | /api/v1/public/v1;x=1/probe   |
      | inhaber-urinet | /api/v1/public%3Bx=1/v1/probe |
      | inhaber-urinet | /api/v1/public%2Fv1/probe     |
      | inhaber-urinet | /public/v1;x=1/probe          |
      | niemand        | /public;x/v1/probe            |
