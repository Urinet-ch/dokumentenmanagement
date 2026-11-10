# language: de
@spec-01
Funktionalität: Dokumentarten verwalten
  Der Inhaber pflegt die Dokumentarten seines Mandanten selbst: Code, Name, Beschreibung, Zusatzfeld-Gruppen,
  Pflichtfelder, Zusatz-Schema (JSON Schema Draft 2020-12), Vertraulichkeit, Aufbewahrungsregel und gesetzliche
  Pflicht (Req 9.1). Lesen dürfen alle Archiv-Rollen, Anlegen, Ändern und Löschen nur der «inhaber» (A11, Req 2.17).
  Jede Art liegt im Schema ihres Mandanten. Die Probe-Arten tragen das Präfix «PROBE_DA_» und werden nach jedem
  Szenario gelöscht; die Seed-Arten bleiben unverändert.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-9.1
  Szenario: Der Inhaber legt eine Art mit Gruppen, Pflichtfeldern und Zusatzfeldern an
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_MIETE", "name": "Mietvertrag Lager", "beschreibung": "Mietvertrag mit Laufzeit.",
       "gruppen": ["finanz", "vertrag", "frei"], "pflichtfelder": ["betrag", "gueltig_ab", "zusatz.objekt"],
       "zusatzSchema": {"type": "object", "properties": {"objekt": {"type": "string", "maxLength": 100}}},
       "vertraulichkeit": "INTERN", "aufbewahrungJahre": 10, "aufbewahrungAb": "GUELTIG_BIS",
       "gesetzlichePflicht": true}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und hat die Dokumentart in der Antwort diese Werte:
      | code                                  | PROBE_DA_MIETE                     |
      | gruppen                               | [finanz, vertrag, frei]            |
      | pflichtfelder                         | [betrag, gueltig_ab, zusatz.objekt] |
      | zusatzSchema.properties.objekt.type   | string                             |
      | vertraulichkeit                       | INTERN                             |
      | aufbewahrungJahre                     | 10                                 |
      | aufbewahrungAb                        | GUELTIG_BIS                        |
      | gesetzlichePflicht                    | true                               |
      | aktiv                                 | true                               |
    Wenn «mitarbeiter-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_MIETE» mit diesen Werten:
      | name         | Mietvertrag Lager         |
      | beschreibung | Mietvertrag mit Laufzeit. |
      | gruppen      | [finanz, vertrag, frei]   |

  @req-9.1
  Szenario: Ohne Angabe gilt ein leeres Zusatz-Schema, und «unbefristet» ist ausdrücklich
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_NOTIZ", "name": "Notiz", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und hat die Dokumentart in der Antwort diese Werte:
      | gruppen                            | []    |
      | pflichtfelder                      | []    |
      | zusatzSchema.type                  | object |
      | zusatzSchema.additionalProperties  | false |
      | aufbewahrungJahre                  | null  |
      | aufbewahrungAb                     | null  |
      | beschreibung                       | null  |

  @req-9.1
  Szenario: Der Inhaber ändert, deaktiviert und löscht eine Art
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_PLAN", "name": "Plan", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10,
       "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": false}
      """
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_PLAN» ändert:
      """
      {"name": "Bauplan", "gruppen": ["frei"], "pflichtfelder": ["projekt", "zusatz.massstab"],
       "zusatzSchema": {"type": "object", "properties": {"massstab": {"type": "string", "pattern": "^1:[0-9]+$"}}},
       "vertraulichkeit": "INTERN", "aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Und hat die Dokumentart in der Antwort diese Werte:
      | code              | PROBE_DA_PLAN             |
      | name              | Bauplan                   |
      | pflichtfelder     | [projekt, zusatz.massstab] |
      | vertraulichkeit   | INTERN                    |
      | aufbewahrungJahre | 10                        |
      | aufbewahrungAb    | GESCHAEFTSJAHR            |
      | aktiv             | false                     |
    Wenn «treuhaender-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_PLAN» mit diesen Werten:
      | name  | Bauplan |
      | aktiv | false   |
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_PLAN» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 204
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_PLAN» nicht
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_PLAN» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-9.1
  Szenario: Alle Archiv-Rollen sehen die Arten des eigenen Mandanten
    Wenn «geschaeftsstelle-vhf-gsk» die Dokumentarten auflistet
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Und enthält die Liste der Dokumentarten «AUSGANGSRECHNUNG» nicht
    Wenn «mitarbeiter-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «AUSGANGSRECHNUNG» mit diesen Werten:
      | gesetzlichePflicht | true |
      | aktiv              | true |

  @req-9.1
  Szenariogrundriss: Unvollständige oder falsche Angaben ergeben 400
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      <body>
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

    Beispiele:
      | fall                                   | body                                                                                                                                                  |
      | Code vergeben (Seed-Art)               | {"code": "AUSGANGSRECHNUNG", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10, "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": true} |
      | Aufbewahrung nicht angegeben           | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "gesetzlichePflicht": false}                                                         |
      | unbekanntes Feld                       | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false, "mandant": "vhf-gsk"}          |
      | Code klein geschrieben                 | {"code": "probe_da_x", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}                               |
      | unbekannte Gruppe                      | {"code": "PROBE_DA_X", "name": "x", "gruppen": ["lohn"], "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}           |
      | Pflichtfeld ohne seine Gruppe          | {"code": "PROBE_DA_X", "name": "x", "pflichtfelder": ["betrag"], "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}   |
      | Jahre ohne Beginn                      | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10, "gesetzlichePflicht": false}                                 |
      | unbekannte Vertraulichkeit             | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "GEHEIM", "aufbewahrungJahre": null, "gesetzlichePflicht": false}                               |
      | Jahre als Text                         | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": "10", "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": false} |

  @req-9.5
  Szenariogrundriss: Ein ungültiges Zusatz-Schema ergibt 422
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_X", "name": "x", "gruppen": ["frei"], "pflichtfelder": <pflichtfelder>,
       "zusatzSchema": <schema>, "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «ZUSATZ_UNGUELTIG»
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_X» nicht

    Beispiele:
      | fall                                   | pflichtfelder       | schema                                                                                     |
      | kein Objekt-Schema                     | []                  | {"type": "string"}                                                                         |
      | unbekannter Typ                        | []                  | {"type": "object", "properties": {"a": {"type": "zahl"}}}                                  |
      | Pflicht-Zusatzfeld fehlt im Schema     | ["zusatz.objekt"]   | {"type": "object", "properties": {"anderes": {"type": "string"}}}                          |
      | externer Verweis (SSRF)                | []                  | {"type": "object", "properties": {"a": {"$ref": "https://example.org/schema.json"}}}       |
      | eigene Schema-ID                       | []                  | {"type": "object", "$id": "http://127.0.0.1/schema"}                                        |
      | Schema als Text                        | []                  | "{\"type\": \"object\"}"                                                                    |

  @req-9.5
  Szenario: Auch beim Ändern wird das Zusatz-Schema geprüft
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false}
      """
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_X» ändert:
      """
      {"zusatzSchema": {"type": "object", "properties": {"a": {"type": "string", "pattern": "("}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «ZUSATZ_UNGUELTIG»

  @req-9.4
  Szenario: Eine Art mit Dokumenten wird nicht gelöscht, nur deaktiviert (Teilnachweis mit Zähler-Attrappe bis 2.2)
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_ALT", "name": "Alt", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false}
      """
    Und die Dokumentart «PROBE_DA_ALT» hat 1 Dokument
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_ALT» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 409 und Fehlercode «DOKUMENTART_IN_GEBRAUCH»
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_ALT» ändert:
      """
      {"aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Und hat die Dokumentart in der Antwort diese Werte:
      | aktiv | false |

  @req-9.1
  Szenario: Arten eines anderen Mandanten sind unsichtbar und unveränderbar
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_FREMD", "name": "Fremd", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false}
      """
    Wenn «inhaber-vhf-gsk» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_FREMD» nicht
    Wenn «inhaber-vhf-gsk» die Dokumentart «PROBE_DA_FREMD» ändert:
      """
      {"aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» die Dokumentart «PROBE_DA_FREMD» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_FREMD» mit diesen Werten:
      | aktiv | true |

  @req-2.17
  Szenariogrundriss: Ohne Rolle «inhaber» keine Pflege der Dokumentarten, jede Verweigerung wird protokolliert
    Wenn «<benutzer>» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «<benutzer>» die Dokumentart «KORRESPONDENZ» ändert:
      """
      {"aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Dokumentart «KORRESPONDENZ» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |

    Beispiele:
      | benutzer                 | mandant | rolle            |
      | mitarbeiter-urinet       | urinet  | MITARBEITER      |
      | treuhaender-urinet       | urinet  | TREUHAENDER      |
      | geschaeftsstelle-vhf-gsk | vhf-gsk | GESCHAEFTSSTELLE |

  @req-2.17
  Szenariogrundriss: Ein API-Token erreicht die Dokumentarten nicht
    Angenommen für «urinet» gibt es das Token «import» mit Scope «import»
    Wenn das Token «import» <methode> «<pfad>» mit einem leeren JSON-Objekt aufruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»

    Beispiele:
      | methode | pfad                                 |
      | GET     | /api/v1/dokumentarten                |
      | POST    | /api/v1/dokumentarten                |
      | PATCH   | /api/v1/dokumentarten/KORRESPONDENZ  |
      | DELETE  | /api/v1/dokumentarten/KORRESPONDENZ  |
