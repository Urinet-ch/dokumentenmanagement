# language: de
@spec-03
Funktionalität: Webseiten-Schlüssel (Aufgabe 11.7)
  Provisionierung, Rotation, Absenderprüfung und Prüf-Cache der Webseiten-Schlüssel. Die Szenarien laufen gegen die
  Probe /public/v1/probe (nur in src/test); den Vertrag der Schnittstelle prüft webseiten-vertrag.feature (Aufgabe 11.6).

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-12.1
  Szenario: Eine OIDC-Anmeldung erreicht die Webseiten-Schnittstelle nicht
    Wenn «inhaber-urinet» GET «/public/v1/probe» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-12.1 @req-9.4
  Szenario: Ein Lese-Schlüssel erreicht keinen Erfassungs-Endpunkt
    Wenn «webseite» POST «/public/v1/probe/erfassung» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-12.1
  Szenario: Ein Erfassungs-Schlüssel erreicht einen Erfassungs-Endpunkt
    Wenn «webseite-erfassung» POST «/public/v1/probe/erfassung» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 200

  @req-12.2
  Szenario: Ein Webseiten-Schlüssel ist an Webseite und höchste Zugriffsstufe gebunden
    Angenommen es gibt einen Webseiten-Lese-Schlüssel für «probe» mit höchster Stufe «mitglieder»
    Wenn «inhaber-urinet» die API-Tokens auflistet
    Dann enthält die Liste den Webseiten-Schlüssel «probe» mit höchster Stufe «mitglieder»

  @req-12.3 @req-12.7
  Szenario: Das Kommando gibt einen Webseiten-Schlüssel nur einmal aus und die API verwaltet ihn
    Wenn das Webseiten-Schlüssel-Kommando einen Lese-Schlüssel für «probe» erstellt
    Dann steht das Geheimnis nur einmal auf stdout und weder in stderr noch im Log
    Und steht in der Datenbank nur der Hash des Webseiten-Schlüssels
    Wenn «inhaber-urinet» die API-Tokens auflistet
    Dann enthält die Liste den Webseiten-Schlüssel «probe» mit höchster Stufe «oeffentlich»
    Wenn «inhaber-urinet» über die API einen Webseiten-Schlüssel anlegen will
    Dann lehnt die API das mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» den Webseiten-Schlüssel widerruft
    Dann antwortet die Token-Verwaltung mit Status 204

  @req-12.4
  Szenario: Bei einer Rotation bleiben beide Schlüssel während der Überlappung gültig
    Angenommen es gibt einen Webseiten-Lese-Schlüssel für «probe» mit höchster Stufe «oeffentlich»
    Wenn der Webseiten-Schlüssel mit einer Überlappung von 30 Sekunden rotiert wird
    Dann erreicht der alte und der neue Webseiten-Schlüssel die Probe

  @req-12.4 @req-9.4
  Szenario: Ohne Überlappung wird der alte Schlüssel sofort ungültig
    Angenommen es gibt einen Webseiten-Lese-Schlüssel für «probe» mit höchster Stufe «oeffentlich»
    Wenn der Webseiten-Schlüssel ohne Überlappung rotiert wird
    Dann erhält der alte Webseiten-Schlüssel Status 401 und der neue erreicht die Probe

  @req-12.6
  Szenario: Ein Webseiten-Schlüssel mit Absenderprüfung akzeptiert nur konfigurierte Netze
    Angenommen es gibt einen Webseiten-Lese-Schlüssel mit Absenderprüfung für «probe»
    Und die Betriebskonfiguration erlaubt für «probe» das Netz «198.51.100.0/24»
    Wenn die Webseite mit Absender «198.51.100.42» die Probe aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 200
    Wenn die Webseite mit Absender «203.0.113.42» die Probe aufruft
    Dann ist die Antwort 401 «NICHT_ANGEMELDET» mit «WWW-Authenticate: Bearer»

  @req-12.6
  Szenario: Ein Webseiten-Schlüssel mit Absenderprüfung wird ohne Konfiguration abgelehnt
    Angenommen es gibt einen Webseiten-Lese-Schlüssel mit Absenderprüfung für «probe»
    Und die Betriebskonfiguration enthält keine Absendernetze
    Wenn die Webseite mit Absender «198.51.100.42» die Probe aufruft
    Dann ist die Antwort 401 «NICHT_ANGEMELDET» mit «WWW-Authenticate: Bearer»

  @req-12.8
  Szenario: Ein warmer Schlüssel bleibt erreichbar, wenn die HashSperre ausgelastet ist
    Angenommen es gibt einen aktiven Webseiten-Lese-Schlüssel
    Wenn die Webseite den Schlüssel erstmals an der Probe verwendet
    Dann antwortet die Webseiten-Schnittstelle mit Status 200
    Wenn alle Plätze der HashSperre belegt sind
    Und ein ungültiger Webseiten-Schlüssel die Probe aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 503 und Fehlercode «UEBERLASTET»
    Wenn die Webseite den warmen Schlüssel erneut an der Probe verwendet
    Dann antwortet die Webseiten-Schnittstelle mit Status 200

  @req-9.2 @req-12.8
  Szenario: Ungültige Schlüssel einer Quelle blockieren kalte Schlüssel anderer Quellen nicht
    Angenommen die Schlüsselprüfung lässt je Quelle 10 Prüfungen in Folge zu und füllt sich kaum nach
    Und es gibt einen kalten Webseiten-Lese-Schlüssel
    Und es gibt einen Erfassungs-Schlüssel für «probe»
    Wenn 10 ungültige Schlüssel von Quelle «192.0.2.21» die Probe aufrufen
    Dann werden alle mit Status 401 abgelehnt
    Wenn ein weiterer ungültiger Schlüssel von Quelle «192.0.2.21» die Probe aufruft
    Dann wird die Schlüsselprüfung mit Status 429 und «Retry-After» abgelehnt
    Wenn der kalte Webseiten-Schlüssel mit Quelle «198.51.100.21» die Probe aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 200
    Wenn der Erfassungs-Schlüssel mit Quelle «203.0.113.21» die Probe aufruft
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

  @req-12.8
  Szenario: Ein warmer Schlüssel eines deaktivierten Mandanten erhält 403
    Angenommen der aktive Mandant «probe-webseite» steht nur im Register
    Und es gibt einen Webseiten-Lese-Schlüssel für den Mandanten «probe-webseite»
    Wenn die Webseite den Schlüssel erstmals an der Probe verwendet
    Dann antwortet die Webseiten-Schnittstelle mit Status 200
    Wenn der Mandant «probe-webseite» wird deaktiviert
    Und die Webseite den warmen Schlüssel erneut an der Probe verwendet
    Dann antwortet die Webseiten-Schnittstelle mit Status 403 und Fehlercode «MANDANT_FEHLT»
