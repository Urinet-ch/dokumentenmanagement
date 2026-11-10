# language: de
@spec-01
Funktionalität: API-Tokens für Skripte
  Generator-Client und Drive-Import melden sich mit einem API-Token `ua_<id>_<geheimnis>` an. Das Token ist an einen
  Mandanten und einen Scope gebunden, wird nur als Argon2id-Hash gespeichert und ist widerrufbar (Req 2.5).
  Anlegen, Anzeigen und Widerrufen ist Aktion A14 und nur dem «inhaber» erlaubt (Req 2.20).
  Bis die fachlichen Endpunkte kommen (2.4, 3.x), stehen Probe-Endpunkte unter src/test für die Scopes:
  «/api/v1/dokumente» (Scope import, A1) und «/api/v1/dokumente/generator» (Scope generator, A1); der
  Query-Parameter «art» prüft, dass die Dokumentart im Schema des Token-Mandanten liegt.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und alle Logeinträge werden mitgeschnitten

  @req-2.5
  Szenario: Ein Token erreicht die Endpunkte seines Scopes
    Angenommen für «urinet» gibt es das Token «import» mit Scope «import»
    Und für «urinet» gibt es das Token «generator» mit Scope «generator»
    Wenn das Token «import» an «/api/v1/dokumente?art=AUSGANGSRECHNUNG» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 202
    Wenn das Token «generator» an «/api/v1/dokumente/generator?art=AUSGANGSRECHNUNG» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 202
    Und kein Logeintrag enthält das Geheimnis eines Tokens

  @req-2.5
  Szenariogrundriss: Ein Token ausserhalb seines Scopes erhält 403 und wird protokolliert
    Angenommen für «urinet» gibt es das Token «<token>» mit Scope «<token>»
    Wenn das Token «<token>» an «<pfad>» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «urinet» hat für das Token «<token>» genau einen Eintrag «VERWEIGERT» ohne Rolle
    Und kein Logeintrag enthält das Geheimnis eines Tokens

    Beispiele:
      | token     | pfad                         | fall                                   |
      | import    | /api/v1/dokumente/generator  | Endpunkt des anderen Scopes            |
      | generator | /api/v1/dokumente            | Endpunkt des anderen Scopes            |
      | import    | /api/v1/probe/erfassen       | Endpunkt ohne Scope (nur für Benutzer) |

  @req-2.5
  Szenario: Ein Token verwaltet keine Tokens
    Angenommen für «urinet» gibt es das Token «import» mit Scope «import»
    Wenn das Token «import» die Tokens auflistet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-2.5
  Szenario: Ein Token ist an seinen Mandanten gebunden
    Angenommen für «vhf-gsk» gibt es das Token «vhf» mit Scope «import»
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Tokens gemerkt
    Wenn das Token «vhf» an «/api/v1/dokumente?art=PROBE_VHF_BULLETIN» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 202
    Wenn das Token «vhf» an «/api/v1/dokumente?art=AUSGANGSRECHNUNG» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und das Zugriffsprotokoll von «urinet» hat seit dem gemerkten Stand keinen Eintrag eines Tokens

  @req-2.5
  Szenario: Das Token eines inaktiven Mandanten erhält 403
    Angenommen der aktive Mandant «probe-token» steht nur im Register
    Und für «probe-token» gibt es das Token «inaktiv» mit Scope «import»
    Und der Mandant «probe-token» wird deaktiviert
    Wenn das Token «inaktiv» an «/api/v1/dokumente» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «MANDANT_FEHLT»

  @req-2.5
  Szenario: Falsche, unbekannte und fehlerhafte Tokens erhalten 401
    Angenommen für «urinet» gibt es das Token «import» mit Scope «import»
    Wenn das Token «import» mit falschem Geheimnis an «/api/v1/dokumente» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 401
    Wenn ein Token mit unbekannter ID an «/api/v1/dokumente» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 401
    Wenn ein Bearer-Token «ua_kaputt» an «/api/v1/dokumente» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 401
    Und kein Logeintrag enthält das Geheimnis eines Tokens

  @req-2.5
  Szenario: Der Inhaber legt ein Token an, sieht es in der Liste und widerruft es
    Wenn «inhaber-urinet» ein Token «generator» mit Scope «generator» anlegt
    Dann enthält die Antwort das Token «generator» genau dieses eine Mal, nicht zwischenspeicherbar
    Wenn das Token «generator» an «/api/v1/dokumente/generator» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 202
    Wenn «inhaber-urinet» die Tokens auflistet
    Dann enthält die Liste das Token «generator» mit aktiv true, ohne Geheimnis und ohne Hash
    Und ist beim Token «generator» die letzte Verwendung eingetragen
    Wenn «inhaber-urinet» das Token «generator» widerruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 204
    Wenn das Token «generator» an «/api/v1/dokumente/generator» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 401
    Wenn «inhaber-urinet» die Tokens auflistet
    Dann enthält die Liste das Token «generator» mit aktiv false, ohne Geheimnis und ohne Hash
    Und kein Logeintrag enthält das Geheimnis eines Tokens

  @req-2.5
  Szenario: Ein Token eines anderen Mandanten ist für den Inhaber nicht sichtbar und nicht widerrufbar
    Angenommen für «vhf-gsk» gibt es das Token «vhf» mit Scope «import»
    Wenn «inhaber-urinet» die Tokens auflistet
    Dann enthält die Liste das Token «vhf» nicht
    Wenn «inhaber-urinet» das Token «vhf» widerruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn das Token «vhf» an «/api/v1/dokumente» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 202

  @req-2.5
  Szenariogrundriss: Ungültige Angaben beim Anlegen werden abgelehnt
    Wenn «inhaber-urinet» ein Token «<name>» mit Scope «<scope>» anlegt
    Dann antwortet das Archiv auf die Token-Anfrage mit Status <status> und Fehlercode «<fehlercode>»

    Beispiele:
      | name  | scope     | status | fehlercode        |
      | probe | wordpress | 400    | ANFRAGE_UNGUELTIG |
      | probe | admin     | 400    | ANFRAGE_UNGUELTIG |

  @req-2.20
  Szenariogrundriss: Ohne Rolle «inhaber» verweigert das Archiv die Token-Verwaltung mit 403
    Angenommen für «<mandant>» gibt es das Token «vorhanden» mit Scope «import»
    Wenn «<benutzer>» ein Token «neu» mit Scope «import» anlegt
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «<benutzer>» die Tokens auflistet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «<benutzer>» das Token «vorhanden» widerruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn das Token «vorhanden» an «/api/v1/dokumente» sendet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 202

    Beispiele:
      | benutzer                 | mandant |
      | mitarbeiter-urinet       | urinet  |
      | treuhaender-urinet       | urinet  |
      | geschaeftsstelle-vhf-gsk | vhf-gsk |
