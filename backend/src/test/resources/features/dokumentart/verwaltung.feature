# language: de
@spec-01
Funktionalität: Dokumentarten verwalten
  Der Inhaber pflegt die Dokumentarten seines Mandanten selbst: Code, Name, Beschreibung, Zusatzfeld-Gruppen,
  Pflichtfelder, Zusatz-Schema (JSON Schema Draft 2020-12), Vertraulichkeit, Aufbewahrungsregel und gesetzliche
  Pflicht (Req 9.1). Lesen dürfen alle Archiv-Rollen, Anlegen, Ändern und Löschen nur der «inhaber» (A11, Req 2.17).
  Jede Art liegt im Schema ihres Mandanten. Die Probe-Arten tragen das Präfix «PROBE_DA_» und werden nach jedem
  Szenario gelöscht; eine geänderte Seed-Art wird nach dem Szenario wieder aktiv (nur «aktiv» wird an ihr geändert).

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

  @req-9.1 @req-9.3
  Szenario: Ohne Aufbewahrungsregel gilt der Standard von 10 Jahren ab Geschäftsjahr
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_STANDARD", "name": "Standard", "vertraulichkeit": "NORMAL", "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und hat die Dokumentart in der Antwort diese Werte:
      | aufbewahrungJahre | 10             |
      | aufbewahrungAb    | GESCHAEFTSJAHR |
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_JAHRE", "name": "Nur Jahre", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 6,
       "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und hat die Dokumentart in der Antwort diese Werte:
      | aufbewahrungJahre | 6              |
      | aufbewahrungAb    | GESCHAEFTSJAHR |

  @req-9.1
  Szenario: Einen Code gibt es im Mandanten nur einmal
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "AUSGANGSRECHNUNG", "name": "x", "vertraulichkeit": "NORMAL", "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 409 und Fehlercode «DOKUMENTART_EXISTIERT»
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «AUSGANGSRECHNUNG» mit diesen Werten:
      | name               | Ausgangsrechnung |
      | gesetzlichePflicht | true             |

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
      | Pflicht nicht angegeben                | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL"}                                                                                      |
      | doppelter Schlüssel                    | {"code": "PROBE_DA_X", "code": "PROBE_DA_Y", "name": "x", "vertraulichkeit": "NORMAL", "gesetzlichePflicht": false}                                  |
      | Zeichen nach dem Objekt                | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "gesetzlichePflicht": false} {}                                                     |
      | unbekanntes Feld                       | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false, "mandant": "vhf-gsk"}          |
      | Code klein geschrieben                 | {"code": "probe_da_x", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}                               |
      | unbekannte Gruppe                      | {"code": "PROBE_DA_X", "name": "x", "gruppen": ["lohn"], "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}           |
      | Pflichtfeld ohne seine Gruppe          | {"code": "PROBE_DA_X", "name": "x", "pflichtfelder": ["betrag"], "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "gesetzlichePflicht": false}   |
      | Beginn ohne Jahre (unbefristet)        | {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null, "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": false} |
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
      | Verweis auf sich selbst                | []                  | {"type": "object", "$ref": "#"}                                                            |
      | Kreis über $defs                       | []                  | {"type": "object", "$ref": "#/$defs/a", "$defs": {"a": {"$ref": "#/$defs/a"}}}             |
      | Kreis über not                         | []                  | {"type": "object", "not": {"$ref": "#"}}                                                   |

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
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_X", "name": "x", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |
    Wenn «<inhaber>» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_X» nicht
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Dokumentart «<art>» ändert:
      """
      {"name": "Geändert", "aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |
    Wenn «<inhaber>» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «<art>» mit diesen Werten:
      | name  | <name> |
      | aktiv | true   |
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Dokumentart «<art>» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |
    Wenn «<inhaber>» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «<art>» mit diesen Werten:
      | name  | <name> |
      | aktiv | true   |

    Beispiele:
      | benutzer                 | mandant | rolle            | inhaber         | art                | name          |
      | mitarbeiter-urinet       | urinet  | MITARBEITER      | inhaber-urinet  | KORRESPONDENZ      | Korrespondenz |
      | treuhaender-urinet       | urinet  | TREUHAENDER      | inhaber-urinet  | KORRESPONDENZ      | Korrespondenz |
      | geschaeftsstelle-vhf-gsk | vhf-gsk | GESCHAEFTSSTELLE | inhaber-vhf-gsk | PROBE_VHF_BULLETIN | Probe-Bulletin |

  @req-9.4
  Szenario: Auch eine Art aus dem Seed lässt sich nur deaktivieren, solange sie Dokumente hat
    Angenommen die Dokumentart «KORRESPONDENZ» hat 1 Dokument
    Wenn «inhaber-urinet» die Dokumentart «KORRESPONDENZ» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 409 und Fehlercode «DOKUMENTART_IN_GEBRAUCH»
    Wenn «inhaber-urinet» die Dokumentart «KORRESPONDENZ» ändert:
      """
      {"aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Wenn «mitarbeiter-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «KORRESPONDENZ» mit diesen Werten:
      | name  | Korrespondenz |
      | aktiv | false         |

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

  # Entschieden von Fabian am 10.11.2026: Die Regel einer Art darf sich auch ändern, wenn sie schon Dokumente hat; sie
  # wirkt auf neue Dokumente und Neuberechnungen. Die Wirkung auf bestehende Dokumente und Sperren folgt mit 2.2/2.3.
  @req-9.10
  Szenario: Die Regel einer Art mit Dokumenten lässt sich ändern, jede Regeländerung kommt mit alt und neu ins Protokoll (Teilnachweis bis 2.2/2.3)
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regel", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10,
       "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": true}
      """
    Und die Dokumentart «PROBE_DA_REGEL» hat 1 Dokument
    Und der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_REGEL» ändert:
      """
      {"aufbewahrungJahre": 6, "gesetzlichePflicht": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Und hat die Dokumentart in der Antwort diese Werte:
      | aufbewahrungJahre  | 6              |
      | aufbewahrungAb     | GESCHAEFTSJAHR |
      | gesetzlichePflicht | false          |
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer       | rolle   | aktion        | dokument | ip | grund | fehlercode |
      | inhaber-urinet | INHABER | REGEL_AENDERN |          |    |       |            |
    Und der neue Eintrag im Zugriffsprotokoll von «urinet» hält für die Dokumentart «PROBE_DA_REGEL» diese Regeln fest:
      | regel | aufbewahrungJahre | aufbewahrungAb | gesetzlichePflicht |
      | alt   | 10                | GESCHAEFTSJAHR | true               |
      | neu   | 6                 | GESCHAEFTSJAHR | false              |
    Angenommen der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_REGEL» ändert:
      """
      {"aufbewahrungJahre": null}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Und der neue Eintrag im Zugriffsprotokoll von «urinet» hält für die Dokumentart «PROBE_DA_REGEL» diese Regeln fest:
      | regel | aufbewahrungJahre | aufbewahrungAb | gesetzlichePflicht |
      | alt   | 6                 | GESCHAEFTSJAHR | false              |
      | neu   |                   |                | false              |

  @req-9.10
  Szenario: Ohne Änderung der Regel gibt es keinen Protokolleintrag (Gegenprobe)
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regel", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10,
       "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": true}
      """
    Und der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_REGEL» ändert:
      """
      {"name": "Regel neu", "beschreibung": "Nur der Text ändert.", "vertraulichkeit": "INTERN",
       "aufbewahrungJahre": 10, "gesetzlichePflicht": true, "aktiv": false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Und das Zugriffsprotokoll von «urinet» hat keinen neuen Eintrag

  @req-9.10
  Szenario: Lässt sich die Regeländerung nicht protokollieren, bleibt die Regel unverändert
    Angenommen «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regel", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10,
       "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": false}
      """
    Und im Schema von «urinet» scheitert jeder neue Eintrag ins Zugriffsprotokoll beim Festschreiben
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_REGEL» ändert:
      """
      {"aufbewahrungJahre": 5}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 500 und Fehlercode «INTERNER_FEHLER»
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «PROBE_DA_REGEL» mit diesen Werten:
      | aufbewahrungJahre | 10 |
