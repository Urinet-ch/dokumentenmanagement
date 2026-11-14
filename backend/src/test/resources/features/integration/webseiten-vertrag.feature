# language: de
@spec-03
Funktionalität: Vertrag der Webseiten-Schnittstelle unter /public/v1 (Vorstufe, Aufgabe 11.6)
  Anmeldung, Fehlerformat und Header der Webseiten-Schnittstelle, bevor es Endpunkte mit Daten gibt. Die Szenarien
  laufen gegen die Probe /public/v1/probe, die nur in src/test liegt (weder in der App-OpenAPI noch im Vertrag
  docs/schnittstelle/public-v1.yaml). Bis Aufgabe 11.7 die Webseiten-Schlüssel bringt, melden sich Benutzer der App und
  API-Tokens nach Spec 01, 2.5 an.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-15.3
  # «Abgelaufen» (gueltig_bis nach einer Rotation) prüft Aufgabe 11.7, weil es die Spalte erst dort gibt.
  Szenario: Fehlender, ungültiger, falscher und widerrufener Schlüssel ergeben dieselbe Antwort 401
    Angenommen für «urinet» gibt es ein aktives und ein widerrufenes Token
    Wenn «/public/v1/probe» ohne Anmeldung, mit «ua_kaputt», mit falschem Geheimnis und mit dem widerrufenen Token abgefragt wird
    Dann ist jede Antwort 401 «NICHT_ANGEMELDET» mit «WWW-Authenticate: Bearer»
    Und unterscheiden sich die Antworten nur in «instance»

  @req-15.2
  Szenariogrundriss: Fehler unter /public/v1 sind Problem Details ohne interne Angaben
    Wenn «inhaber-urinet» <methode> «<pfad>» aufruft
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
      | inhaber-urinet | GET     | /public/v1/probe                         | 200    |
      | niemand        | GET     | /public/v1/probe                         | 401    |
      | inhaber-urinet | GET     | /public/v1/gibt-es-nicht                 | 404    |
      | inhaber-urinet | POST    | /public/v1/probe                         | 405    |
      | inhaber-urinet | GET     | /public/v1/probe/fehler/VERTRAULICH-4711 | 500    |
      | inhaber-urinet | GET     | /public/v9/probe                         | 404    |
      | inhaber-urinet | GET     | /public                                  | 404    |
      | inhaber-urinet | GET     | /public/v1;x=1/probe                     | 404    |

  @req-15.4
  Szenario: Auch die Grenze der Anfrage vor der Resource antwortet mit Problem Details und no-store
    Wenn «inhaber-urinet» an «/public/v1/probe/inhalt» 2 MB sendet
    Dann antwortet die Webseiten-Schnittstelle mit Status 413 und Fehlercode «DATEI_ZU_GROSS»
    Und trägt die Antwort «Cache-Control: no-store»

  @req-11.5
  # Mit gesetztem archiv.webseite.v1.sunset prüft WebseitenRouteTest (JUnit) die drei Header auch auf Fehlern, ohne
  # eigenen Quarkus-Start; das Testprofil setzt die Eigenschaft nicht.
  Szenariogrundriss: Ohne Abkündigung fehlen Deprecation, Sunset und Link
    Wenn «inhaber-urinet» GET «<pfad>» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status <status>
    Und fehlen die Header «Deprecation», «Sunset» und «Link»

    Beispiele:
      | pfad                     | status |
      | /public/v1/probe         | 200    |
      | /public/v1/gibt-es-nicht | 404    |

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
