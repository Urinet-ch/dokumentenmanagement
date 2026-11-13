# language: de
@spec-01
Funktionalität: Einsicht ins Zugriffsprotokoll und Benutzerverzeichnis (Aufgabe 5.1)
  Nur der Inhaber sieht das Zugriffsprotokoll seines Mandanten (A13) und kann es nach Zeitraum, Benutzer, Dokument und
  Aktion filtern. Das Benutzerverzeichnis ordnet jede Kennung im Protokoll (Keycloak-sub, token:<id>) Name und Rolle
  zu, zur Zeit des Eintrags. Es ist wie das Protokoll nur Einfügen: Name- oder Rollenwechsel, Austritt und Widerruf
  eines API-Tokens sind neue Zeilen. Frühere Namen und Rollen legen die Szenarien als synthetische Zeilen an.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Und der Stand des Benutzerverzeichnisses von «urinet» ist gemerkt

  @req-2.19 @req-10.7
  Szenariogrundriss: Ohne A13 gibt es keine Einsicht, und die Verweigerung wird protokolliert
    Wenn «<benutzer>» das Zugriffsprotokoll abfragt
    Dann antwortet das Archiv auf die Einsicht mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A13   | NICHT_ERLAUBT |

    Beispiele:
      | benutzer           | rolle       |
      | mitarbeiter-urinet | MITARBEITER |
      | treuhaender-urinet | TREUHAENDER |
      | ohne-rolle-urinet  |             |

  @req-2.19
  Szenariogrundriss: Auch Benutzerverzeichnis und Austritt verlangen A13
    Wenn «mitarbeiter-urinet» <anfrage>
    Dann antwortet das Archiv auf die Einsicht mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer           | rolle       | aktion     | dokument | ip | grund | fehlercode    |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT |          |    | A13   | NICHT_ERLAUBT |

    Beispiele:
      | anfrage                                         |
      | das Benutzerverzeichnis abfragt                 |
      | den Austritt von «treuhaender-urinet» erfasst   |

  @req-2.19
  Szenario: Ein API-Token sieht das Zugriffsprotokoll nicht
    Angenommen «inhaber-urinet» ein Token «Einsichtprobe» mit Scope «import» anlegt
    Wenn das Token «Einsichtprobe» GET «/api/v1/protokoll» mit einem leeren JSON-Objekt aufruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403

  @req-2.19 @req-10.5
  Szenario: Der Inhaber sieht die Einträge seines Mandanten, neueste zuerst, und die Einsicht selbst erzeugt keinen Eintrag
    Angenommen «treuhaender-urinet» das Probe-Dokument «NORMAL» über die IP «203.0.113.7» liest
    Und «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer           | name               | rolle       | aktion   | dokument | ip          |
      | mitarbeiter-urinet | mitarbeiter-urinet | MITARBEITER | ANZEIGEN | NORMAL   |             |
      | treuhaender-urinet | treuhaender-urinet | TREUHAENDER | ANZEIGEN | NORMAL   | 203.0.113.7 |

  @req-10.5
  Szenario: Der Inhaber filtert nach Benutzer und Dokument
    Angenommen «treuhaender-urinet» das Probe-Dokument «NORMAL» über die IP «203.0.113.7» liest
    Und «treuhaender-urinet» das Probe-Dokument «INTERN» liest
    Und «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand für «treuhaender-urinet» und das Probe-Dokument «NORMAL» abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer           | name               | rolle       | aktion   | dokument | ip          |
      | treuhaender-urinet | treuhaender-urinet | TREUHAENDER | ANZEIGEN | NORMAL   | 203.0.113.7 |

  @req-10.5
  Szenario: Der Inhaber filtert nach Aktion
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Und «mitarbeiter-urinet» das Zugriffsprotokoll abfragt
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand mit Aktion «VERWEIGERT» abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer           | name               | rolle       | aktion     | dokument | ip |
      | mitarbeiter-urinet | mitarbeiter-urinet | MITARBEITER | VERWEIGERT |          |    |

  @req-10.5
  Szenario: Ein Zeitraum enthält genau die Einträge ab «von» und vor «bis»
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Und ein zweiter Stand des Zugriffsprotokolls ist gemerkt
    Und «treuhaender-urinet» das Probe-Dokument «NORMAL» liest
    Wenn «inhaber-urinet» das Zugriffsprotokoll zwischen den gemerkten Ständen abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer           | name               | rolle       | aktion   | dokument | ip |
      | mitarbeiter-urinet | mitarbeiter-urinet | MITARBEITER | ANZEIGEN | NORMAL   |    |

  @req-10.5
  Szenario: Die Einsicht blättert seitenweise und nennt die Zahl aller Treffer
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Und «treuhaender-urinet» das Probe-Dokument «NORMAL» liest
    Und «inhaber-urinet» das Probe-Dokument «NORMAL» liest
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand auf Seite 1 mit Grösse 1 abfragt
    Dann enthält die Einsicht auf Seite 1 mit Grösse 1 von 3 Treffern genau diese Einträge:
      | benutzer           | name               | rolle       | aktion   | dokument | ip |
      | treuhaender-urinet | treuhaender-urinet | TREUHAENDER | ANZEIGEN | NORMAL   |    |
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand auf Seite 3 mit Grösse 1 abfragt
    Dann enthält die Einsicht auf Seite 3 mit Grösse 1 von 3 Treffern genau diese Einträge:
      | benutzer | name | rolle | aktion | dokument | ip |

  @req-10.5
  Szenario: Der Inhaber eines anderen Mandanten sieht die Einträge von «urinet» nicht
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Wenn «inhaber-vhf-gsk» das Zugriffsprotokoll seit dem gemerkten Stand für «mitarbeiter-urinet» abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer | name | rolle | aktion | dokument | ip |

  @req-10.5
  Szenariogrundriss: Ungültige Filter ergeben 400
    Wenn «inhaber-urinet» das Zugriffsprotokoll mit dem Parameter «<parameter>» = «<wert>» abfragt
    Dann antwortet das Archiv auf die Einsicht mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

    Beispiele:
      | parameter | wert                    |
      | von       | 01.11.2026              |
      | bis       | gestern                 |
      | dokument  | kein-uuid               |
      | aktion    | LESEN                   |
      | seite     | -1                      |
      | groesse   | 101                     |
      | groesse   | 0                       |
      | von       | +300000-01-01T00:00:00Z |
      | bis       | 0000-12-31T23:59:59Z    |

  @req-10.10
  Szenario: Wechselt der Name oder die Rolle, entsteht eine neue Zeile; frühere Einträge behalten Name und Rolle von damals
    Angenommen im Benutzerverzeichnis von «urinet» steht «treuhaender-urinet» zuletzt als «Früherer Name» mit Rolle «MITARBEITER»
    Und im Zugriffsprotokoll von «urinet» steht danach ein Eintrag ANZEIGEN von «treuhaender-urinet» als «MITARBEITER» zum Probe-Dokument «NORMAL»
    Wenn «treuhaender-urinet» das Probe-Dokument «NORMAL» liest
    Und «treuhaender-urinet» das Probe-Dokument «NORMAL» liest
    Dann hat das Benutzerverzeichnis von «urinet» genau diese neuen Zeilen:
      | benutzer           | name               | rolle       | anlass | beendet | erfasst_von |
      | treuhaender-urinet | treuhaender-urinet | TREUHAENDER | AKTION | nein    |             |
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand für «treuhaender-urinet» abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer           | name               | rolle       | aktion   | dokument | ip |
      | treuhaender-urinet | treuhaender-urinet | TREUHAENDER | ANZEIGEN | NORMAL   |    |
      | treuhaender-urinet | treuhaender-urinet | TREUHAENDER | ANZEIGEN | NORMAL   |    |
      | treuhaender-urinet | Früherer Name      | MITARBEITER | ANZEIGEN | NORMAL   |    |

  @req-10.10
  Szenario: Der Inhaber erfasst einen Austritt; die Zuordnung endet mit einer neuen Zeile, nicht durch Ändern
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Und der Stand des Benutzerverzeichnisses von «urinet» ist gemerkt
    Wenn «inhaber-urinet» den Austritt von «mitarbeiter-urinet» erfasst
    Dann antwortet das Archiv auf die Einsicht mit Status 204
    Und hat das Benutzerverzeichnis von «urinet» genau diese neuen Zeilen:
      | benutzer           | name               | rolle       | anlass   | beendet | erfasst_von    |
      | mitarbeiter-urinet | mitarbeiter-urinet | MITARBEITER | AUSTRITT | ja      | inhaber-urinet |
    Und beginnt die Austritt-Zeile von «mitarbeiter-urinet» in «urinet» mit der Zeile davor
    Wenn «inhaber-urinet» den Austritt von «mitarbeiter-urinet» erfasst
    Dann antwortet das Archiv auf die Einsicht mit Status 409 und Fehlercode «STATUS_KONFLIKT»
    Wenn «inhaber-urinet» das Benutzerverzeichnis abfragt
    Dann liefert das Benutzerverzeichnis für «mitarbeiter-urinet» als letzte Zeile «mitarbeiter-urinet» mit Rolle «MITARBEITER» und Anlass «AUSTRITT»
    Und scheitert im Schema von «urinet» jeder Versuch, das Benutzerverzeichnis zu ändern, zu löschen oder zu leeren

  @req-10.10
  Szenario: Kehrt eine ausgetretene Person zurück, beginnt mit ihrer nächsten Aktion eine neue Zeile
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Und «inhaber-urinet» den Austritt von «mitarbeiter-urinet» erfasst
    Und der Stand des Benutzerverzeichnisses von «urinet» ist gemerkt
    Wenn «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Dann hat das Benutzerverzeichnis von «urinet» genau diese neuen Zeilen:
      | benutzer           | name               | rolle       | anlass | beendet | erfasst_von |
      | mitarbeiter-urinet | mitarbeiter-urinet | MITARBEITER | AKTION | nein    |             |

  @req-10.10
  Szenario: Ein verweigerter Versuch nach dem Austritt öffnet die Zuordnung nicht wieder
    Angenommen «mitarbeiter-urinet» das Probe-Dokument «NORMAL» liest
    Und «inhaber-urinet» den Austritt von «mitarbeiter-urinet» erfasst
    Und der Stand des Benutzerverzeichnisses von «urinet» ist gemerkt
    Wenn «mitarbeiter-urinet» das Zugriffsprotokoll abfragt
    Dann antwortet das Archiv auf die Einsicht mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und hat das Benutzerverzeichnis von «urinet» keine neue Zeile
    Wenn «inhaber-urinet» das Zugriffsprotokoll seit dem gemerkten Stand für «mitarbeiter-urinet» abfragt
    Dann enthält die Einsicht genau diese Einträge:
      | benutzer           | name               | rolle       | aktion     | dokument | ip |
      | mitarbeiter-urinet | mitarbeiter-urinet | MITARBEITER | VERWEIGERT |          |    |

  @req-10.10
  Szenariogrundriss: Eine unbekannte Kennung oder ein API-Token lässt sich nicht als Austritt erfassen
    Angenommen «inhaber-urinet» ein Token «Austrittprobe» mit Scope «import» anlegt
    Und das Token «Austrittprobe» die Tokens auflistet
    Wenn «inhaber-urinet» <anfrage>
    Dann antwortet das Archiv auf die Einsicht mit Status <status> und Fehlercode «<fehlercode>»

    Beispiele:
      | anfrage                                                                   | status | fehlercode      |
      | den Austritt der Kennung «unbekannt-probe» erfasst                        | 404    | NICHT_GEFUNDEN  |
      | den Austritt des Tokens «Austrittprobe» im Mandanten «urinet» erfasst     | 409    | STATUS_KONFLIKT |

  @req-10.10
  Szenario: Ein API-Token steht mit seinem Namen im Verzeichnis, und sein Widerruf beendet die Zuordnung
    Angenommen «inhaber-urinet» ein Token «Widerrufprobe» mit Scope «import» anlegt
    Wenn das Token «Widerrufprobe» die Tokens auflistet
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403
    Wenn «inhaber-urinet» das Token «Widerrufprobe» widerruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 204
    Und hat das Benutzerverzeichnis von «urinet» genau diese neuen Zeilen:
      | benutzer            | name                | rolle     | anlass   | beendet | erfasst_von    |
      | Token Widerrufprobe | Probe Widerrufprobe | API_TOKEN | AKTION   | nein    |                |
      | Token Widerrufprobe | Probe Widerrufprobe | API_TOKEN | WIDERRUF | ja      | inhaber-urinet |

  @req-10.10
  Szenario: Ein Token, das nie zugegriffen hat, erhält beim Widerruf seine Zeile ab der Erstellung
    Angenommen «inhaber-urinet» ein Token «Stillprobe» mit Scope «import» anlegt
    Wenn «inhaber-urinet» das Token «Stillprobe» widerruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 204
    Und hat das Benutzerverzeichnis von «urinet» genau diese neuen Zeilen:
      | benutzer         | name             | rolle     | anlass   | beendet | erfasst_von    |
      | Token Stillprobe | Probe Stillprobe | API_TOKEN | WIDERRUF | ja      | inhaber-urinet |
