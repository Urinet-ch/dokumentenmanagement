# language: de
@spec-05
Funktionalität: Regeln je Mandant
  Regeln steuern Pflichtfelder und Lückenberichte, ohne bestehende Dokumente rückwirkend zu ändern.

  @req-5.1
  Szenario: Die vier erlaubten Regeltypen werden typisiert
    Wenn die vier Regeltypen der Konfiguration aufgebaut werden
    Dann sind alle vier Regeltypen typisiert

  @req-5.2
  Szenario: Eine verletzte Regel mit Schwere FEHLER blockiert, eine Warnung nicht
    Wenn eine aktive Pflichtregel mit Schwere «FEHLER» und fehlendem Datum geprüft wird
    Dann wird der Vorgang mit «REGEL_VERLETZT» abgelehnt
    Wenn eine aktive Pflichtregel mit Schwere «WARNUNG» und fehlendem Datum geprüft wird
    Dann enthält die Antwort die Warnung «datum_pflicht»

  @req-5.3
  Szenario: Eine bestehende Verletzung wird bei unveränderten Metadaten zur Warnung
    Wenn eine bereits verletzte Pflichtregel mit unveränderten Metadaten geprüft wird
    Dann enthält die Antwort die Warnung «datum_pflicht»
    Und wird der Vorgang nicht abgelehnt

  @req-5.4
  Szenario: Der REST-Lückenbericht ist nur für Rollen mit uneingeschränkter Sicht verfügbar
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» den Lückenbericht für das Geschäftsjahr «2003» abruft
    Dann antwortet der Lückenbericht mit Status 200
    Wenn «treuhaender-urinet» den Lückenbericht für das Geschäftsjahr «2003» abruft
    Dann antwortet der Lückenbericht mit Status 200
    Wenn «geschaeftsstelle-vhf-gsk» den Lückenbericht für das Geschäftsjahr «2003» abruft
    Dann antwortet der Lückenbericht mit Status 200
    Angenommen der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «mitarbeiter-urinet» den Lückenbericht für das Geschäftsjahr «2003» abruft
    Dann antwortet der Lückenbericht mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer           | rolle       | aktion     | dokument | ip | grund | fehlercode    |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT |          |    | A3    | NICHT_ERLAUBT |

  @req-5.6
  Szenario: Neue und aktivierte Regeln melden bestätigte Verstösse, ohne archivierte Dokumente zu ändern
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Regel Alt 1» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Regel Alt 1» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und das Dokument «Regel Alt 1» hat den Titel «Gilt»
    Und «inhaber-urinet» legt ein Dokument «Regel Alt 2» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Regel Alt 2» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und das Dokument «Regel Alt 2» hat den Titel «Gilt»
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_datum_pflicht","art":"PROBE_DA_REGEL","typ":"PFLICHT_WENN",
       "parameter":{"wenn":{"feld":"titel","gleich":"Gilt"},"dann":["datum"]},
       "schwere":"FEHLER","meldung":"Belegdatum fehlt."}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Und meldet die Regelverwaltung 2 betroffene Dokumente
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_referenz_eindeutig","art":"PROBE_DA_REGEL","typ":"EINDEUTIG",
       "parameter":{"felder":["zusatz.referenz"]},"schwere":"FEHLER",
       "meldung":"Referenz bereits verwendet.","aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Und meldet die Regelverwaltung 2 betroffene Dokumente
    Wenn «inhaber-urinet» aktiviert die Regel «probe_referenz_eindeutig»
    Dann antwortet die Regelverwaltung mit Status 200
    Und meldet die Regelverwaltung 2 betroffene Dokumente
    Und sind im Mandanten «urinet» weiterhin 2 bestätigte Dokumente der Art «PROBE_DA_REGEL» archiviert

  @req-5.6 @req-1.3 @req-7.6
  Szenario: Aktivieren und Deaktivieren einer Regel ergeben je eine neue Konfigurationsversion mit dem neuen Stand
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_referenz_eindeutig","art":"PROBE_DA_REGEL","typ":"EINDEUTIG",
       "parameter":{"felder":["zusatz.referenz"]},"schwere":"FEHLER",
       "meldung":"Referenz bereits verwendet.","aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Angenommen der Konfigurationsstand von «urinet» ist gemerkt
    Wenn «inhaber-urinet» aktiviert die Regel «probe_referenz_eindeutig»
    Dann führt «inhaber-urinet» die Regel «probe_referenz_eindeutig» mit aktiv «true» in genau einer neuen Konfigurationsversion
    Angenommen der Konfigurationsstand von «urinet» ist gemerkt
    Wenn «inhaber-urinet» deaktiviert die Regel «probe_referenz_eindeutig»
    Dann führt «inhaber-urinet» die Regel «probe_referenz_eindeutig» mit aktiv «false» in genau einer neuen Konfigurationsversion

  @req-5.6 @req-7.6
  Szenario: Verschärfen einer aktiven Regel über Parameter oder Schwere meldet die betroffenen Dokumente und übernimmt die übrigen Felder
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Regel Alt 1» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Regel Alt 1» hat den Titel «Gilt»
    Und «inhaber-urinet» legt ein Dokument «Regel Alt 2» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Regel Alt 2» hat den Titel «Gilt»
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_datum_pflicht","art":"PROBE_DA_REGEL","typ":"PFLICHT_WENN",
       "parameter":{"wenn":{"feld":"titel","gleich":"Anders"},"dann":["datum"]},
       "schwere":"WARNUNG","meldung":"Belegdatum fehlt."}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Und meldet die Regelverwaltung 0 betroffene Dokumente
    Angenommen der Konfigurationsstand von «urinet» ist gemerkt
    Wenn «inhaber-urinet» die Regel «probe_datum_pflicht» mit dem aktuellen Stand ändert:
      """
      {"parameter":{"wenn":{"feld":"titel","gleich":"Gilt"},"dann":["datum"]}}
      """
    Dann führt «inhaber-urinet» die Regel «probe_datum_pflicht» in genau einer neuen Konfigurationsversion mit:
      | feld      | wert                                                  |
      | parameter | {"wenn":{"feld":"titel","gleich":"Gilt"},"dann":["datum"]} |
      | schwere   | "WARNUNG"                                             |
      | meldung   | "Belegdatum fehlt."                                   |
      | aktiv     | true                                                  |
    Und meldet die Regelverwaltung 2 betroffene Dokumente
    Angenommen der Konfigurationsstand von «urinet» ist gemerkt
    Wenn «inhaber-urinet» die Regel «probe_datum_pflicht» mit dem aktuellen Stand ändert:
      """
      {"schwere":"FEHLER"}
      """
    Dann führt «inhaber-urinet» die Regel «probe_datum_pflicht» in genau einer neuen Konfigurationsversion mit:
      | feld      | wert                                                  |
      | parameter | {"wenn":{"feld":"titel","gleich":"Gilt"},"dann":["datum"]} |
      | schwere   | "FEHLER"                                              |
      | meldung   | "Belegdatum fehlt."                                   |
      | aktiv     | true                                                  |
    Und meldet die Regelverwaltung 2 betroffene Dokumente
    Und sind im Mandanten «urinet» weiterhin 2 bestätigte Dokumente der Art «PROBE_DA_REGEL» archiviert

  @req-7.2
  Szenario: Die Regeln liest nur, wer das Recht A11 hat
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» die Regeln liest
    Dann enthält die Regelliste die Regel «ausgangsrechnung_folge»
    Wenn «mitarbeiter-urinet» die Regeln liest
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «treuhaender-urinet» die Regeln liest
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «geschaeftsstelle-vhf-gsk» die Regeln liest
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-7.6 @req-7.2 @req-5.5
  Szenario: Anlegen und Ändern von Regeln verlangen Version, Recht A11 und einen eindeutigen Code
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Regel ohne If-Match anlegt:
      """
      {"code":"probe_ohne_version","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» eine Regel mit der Version «1» anlegt:
      """
      {"code":"probe_alte_version","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Wenn «mitarbeiter-urinet» eine Regel mit der Version «1» anlegt:
      """
      {"code":"probe_mitarbeiter","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_doppelt","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_doppelt","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Wenn «inhaber-urinet» die Regel «probe_doppelt» ohne If-Match ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» die Regel «probe_doppelt» mit der Version «1» ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Wenn «mitarbeiter-urinet» die Regel «probe_doppelt» mit der Version «1» ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn «inhaber-urinet» die Regel «probe_unbekannt» mit dem aktuellen Stand ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-7.1 @req-1.3 @req-7.6
  Szenario: Der Inhaber löscht eine Regel in genau einer neuen Konfigurationsversion
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_loeschen","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Wenn «inhaber-urinet» die Regel «probe_loeschen» ohne If-Match löscht
    Dann antwortet die Regelverwaltung mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» die Regel «probe_loeschen» mit der Version «1» löscht
    Dann antwortet die Regelverwaltung mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Wenn «inhaber-vhf-gsk» die Regel «probe_loeschen» mit dem aktuellen Stand löscht
    Dann antwortet die Regelverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Angenommen der Konfigurationsstand von «urinet» ist gemerkt
    Wenn «inhaber-urinet» die Regel «probe_loeschen» mit dem aktuellen Stand löscht
    Dann ist die Regel «probe_loeschen» von «inhaber-urinet» in genau einer neuen Konfigurationsversion gelöscht
    Wenn «inhaber-urinet» die Regel «probe_loeschen» mit dem aktuellen Stand löscht
    Dann antwortet die Regelverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-7.1
  Szenariogrundriss: Regeln löschen darf ausser dem Inhaber keine Rolle, auch nicht <benutzer>
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Regel «<regel>» mit der Version «1» löscht
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |

    Beispiele:
      | benutzer                 | mandant | rolle            | regel                  |
      | mitarbeiter-urinet       | urinet  | MITARBEITER      | ausgangsrechnung_folge |
      | treuhaender-urinet       | urinet  | TREUHAENDER      | ausgangsrechnung_folge |
      | geschaeftsstelle-vhf-gsk | vhf-gsk | GESCHAEFTSSTELLE | probe_ohne_recht       |

  @req-7.1
  Szenariogrundriss: Regeln anlegen und ändern darf ausser dem Inhaber keine Rolle, auch nicht <benutzer>
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» eine Regel mit der Version «1» anlegt:
      """
      {"code":"probe_ohne_recht","art":"AUSGANGSRECHNUNG","typ":"EINDEUTIG",
       "parameter":{"felder":["titel"]},"schwere":"WARNUNG","meldung":"Probe."}
      """
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Regel «<regel>» mit der Version «1» ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A11   | NICHT_ERLAUBT |

    Beispiele:
      | benutzer                 | mandant | rolle            | regel                  |
      | mitarbeiter-urinet       | urinet  | MITARBEITER      | ausgangsrechnung_folge |
      | treuhaender-urinet       | urinet  | TREUHAENDER      | ausgangsrechnung_folge |
      | geschaeftsstelle-vhf-gsk | vhf-gsk | GESCHAEFTSSTELLE | probe_ohne_recht       |

  @req-1.2
  Szenario: Eine Regel des einen Mandanten ist im anderen weder sichtbar noch änderbar
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-vhf-gsk» die Regeln liest
    Dann enthält die Regelliste die Regel «ausgangsrechnung_folge» nicht
    Wenn «inhaber-vhf-gsk» die Regel «ausgangsrechnung_folge» mit dem aktuellen Stand ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Regelverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-urinet» die Regeln liest
    Dann enthält die Regelliste die Regel «ausgangsrechnung_folge»

  @req-5.6
  Szenario: Eine Regel EINDEUTIG über alle Arten meldet nur Dokumente ihrer eigenen Art als betroffen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_FREMD", "name": "Fremdprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Fremd 1» der Art «PROBE_DA_FREMD» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Fremd 1» hat das Zusatzfeld «referenz» mit dem Wert «X-1»
    Und «inhaber-urinet» legt ein Dokument «Fremd 2» der Art «PROBE_DA_FREMD» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Fremd 2» hat das Zusatzfeld «referenz» mit dem Wert «X-1»
    Wenn «inhaber-urinet» eine Regel mit dem aktuellen Stand anlegt:
      """
      {"code":"probe_referenz_ueberall","art":"PROBE_DA_REGEL","typ":"EINDEUTIG",
       "parameter":{"felder":["zusatz.referenz"],"ueberAlleArten":true},"schwere":"FEHLER",
       "meldung":"Referenz bereits verwendet."}
      """
    Dann antwortet die Regelverwaltung mit Status 201
    Und meldet die Regelverwaltung 0 betroffene Dokumente
    Angenommen «inhaber-urinet» legt ein Dokument «Eigen» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Eigen» hat das Zusatzfeld «referenz» mit dem Wert «X-1»
    Wenn «inhaber-urinet» deaktiviert die Regel «probe_referenz_ueberall»
    Und «inhaber-urinet» aktiviert die Regel «probe_referenz_ueberall»
    Dann antwortet die Regelverwaltung mit Status 200
    Und meldet die Regelverwaltung 1 betroffene Dokumente

  @req-5.4
  Szenario: Der Lückenbericht bleibt Rollen mit uneingeschränkter Sicht vorbehalten
    Dann wird «inhaber-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «treuhaender-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «geschaeftsstelle-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «mitarbeiter-urinet» das Werkzeug vollstaendigkeit nicht angeboten
    Wenn «mitarbeiter-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann ist die Antwort der Fehler «Werkzeug nicht verfügbar»

  @req-5.5
  Szenario: Eine Regel darf kein in der Dokumentart fehlendes Feld verwenden
    Wenn eine Regel ein nicht vorhandenes Zusatzfeld referenziert
    Dann wird die Test-Konfiguration mit Befund «REGEL_FELD_UNGUELTIG» abgelehnt

  @req-5.1
  Szenario: Die Seed-Regeln von urinet stehen in der Tabelle und in der aktuellen Konfiguration, vhf-gsk hat keine
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Und «inhaber-vhf-gsk» die Dokumentarten auflistet
    Dann enthält die aktuelle Konfiguration von «urinet» die Regeln «ausgangsrechnung_folge, lohnabrechnung_erwartet, bankbeleg_erwartet»
    Und enthält die aktuelle Konfiguration von «vhf-gsk» keine Regeln

  @req-5.2
  Szenario: Bestätigen mit verletzter Regel FEHLER wird über die REST-Schnittstelle abgelehnt
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» gilt die Regel «probe_referenz_eindeutig» für die Art «PROBE_DA_REGEL» vom Typ «EINDEUTIG» mit Schwere «FEHLER» und den Parametern '{"felder":["zusatz.referenz"]}'
    Und «inhaber-urinet» legt ein Dokument «Vorhanden» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Vorhanden» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und «inhaber-urinet» legt ein Dokument «Neu» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «EINGANGSKORB» an
    Und das Dokument «Neu» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Wenn «inhaber-urinet» bestätigt das Dokument «Neu» mit diesen Angaben:
      """
      {"art":"PROBE_DA_REGEL","titel":"Probe","projekt":"Probe"}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «REGEL_VERLETZT»
    Und nennt die Antwort der Klassifikationsverwaltung die verletzte Regel «probe_referenz_eindeutig» ohne das Dokument «Vorhanden»

  @req-5.2
  Szenario: Bestätigen mit verletzter Regel WARNUNG geht durch und zeigt die Warnung
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» gilt die Regel «probe_referenz_eindeutig» für die Art «PROBE_DA_REGEL» vom Typ «EINDEUTIG» mit Schwere «WARNUNG» und den Parametern '{"felder":["zusatz.referenz"]}'
    Und «inhaber-urinet» legt ein Dokument «Vorhanden» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Vorhanden» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und «inhaber-urinet» legt ein Dokument «Neu» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «EINGANGSKORB» an
    Und das Dokument «Neu» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Wenn «inhaber-urinet» bestätigt das Dokument «Neu» mit diesen Angaben:
      """
      {"art":"PROBE_DA_REGEL","titel":"Probe","projekt":"Probe"}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 202
    Und enthält die Antwort der Klassifikationsverwaltung die Warnung «probe_referenz_eindeutig»

  @req-5.3
  Szenario: Eine Metadatenänderung ohne Regelfeld lässt eine bestehende Verletzung als Warnung durch
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Erstes» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Erstes» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und «inhaber-urinet» legt ein Dokument «Zweites» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Zweites» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und im Mandanten «urinet» gilt die Regel «probe_referenz_eindeutig» für die Art «PROBE_DA_REGEL» vom Typ «EINDEUTIG» mit Schwere «FEHLER» und den Parametern '{"felder":["zusatz.referenz"]}'
    Wenn «inhaber-urinet» ändert den Titel des Dokuments «Zweites» auf «Korrigierter Titel»
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Und enthält die Antwort der Klassifikationsverwaltung die Warnung «probe_referenz_eindeutig»

  @req-5.2
  Szenario: Ein bestätigtes Generator-Dokument vor der Archivierung zählt für EINDEUTIG
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» gilt die Regel «probe_referenz_eindeutig» für die Art «PROBE_DA_REGEL» vom Typ «EINDEUTIG» mit Schwere «FEHLER» und den Parametern '{"felder":["zusatz.referenz"]}'
    Wenn der Generator die PDF «G1» mit diesen Metadaten sendet:
      """
      { "art": "PROBE_DA_REGEL", "titel": "Probe", "zusatz": {"referenz": "G-1"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «G1»
    Wenn der Generator die PDF «G2» mit diesen Metadaten sendet:
      """
      { "art": "PROBE_DA_REGEL", "titel": "Probe", "zusatz": {"referenz": "G-1"} }
      """
    Dann antwortet der Generator mit Status 422 und Fehlercode «REGEL_VERLETZT»
    Und nennt der Generator die verletzte Regel «probe_referenz_eindeutig»
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «G2»
    Angenommen «inhaber-urinet» legt ein Dokument «Neu» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «EINGANGSKORB» an
    Und das Dokument «Neu» hat das Zusatzfeld «referenz» mit dem Wert «G-1»
    Wenn «inhaber-urinet» bestätigt das Dokument «Neu» mit diesen Angaben:
      """
      {"art":"PROBE_DA_REGEL","titel":"Probe","projekt":"Probe"}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «REGEL_VERLETZT»

  @req-5.2
  Szenario: Der Generator nimmt ein Dokument mit verletzter Regel WARNUNG an und nennt die Warnung
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» gilt die Regel «probe_referenz_eindeutig» für die Art «PROBE_DA_REGEL» vom Typ «EINDEUTIG» mit Schwere «WARNUNG» und den Parametern '{"felder":["zusatz.referenz"]}'
    Und «inhaber-urinet» legt ein Dokument «Vorhanden» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Vorhanden» hat das Zusatzfeld «referenz» mit dem Wert «G-1»
    Wenn der Generator die PDF «G1» mit diesen Metadaten sendet:
      """
      { "art": "PROBE_DA_REGEL", "titel": "Probe", "zusatz": {"referenz": "G-1"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «G1»
    Und enthält die Generator-Antwort die Warnung «probe_referenz_eindeutig»

  @req-5.2
  Szenario: Ein gleicher Wert im anderen Mandanten verletzt die Regel EINDEUTIG nicht
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-vhf-gsk» eine Dokumentart anlegt:
      """
      {"code": "PROBE_DA_REGEL", "name": "Regelprobe", "vertraulichkeit": "NORMAL", "aufbewahrungJahre": null,
       "gesetzlichePflicht": false,
       "zusatzSchema": {"type": "object", "properties": {"referenz": {"type": "string", "maxLength": 40}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» gilt die Regel «probe_referenz_eindeutig» für die Art «PROBE_DA_REGEL» vom Typ «EINDEUTIG» mit Schwere «FEHLER» und den Parametern '{"felder":["zusatz.referenz"]}'
    Und «inhaber-vhf-gsk» legt ein Dokument «Fremd» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Fremd» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Und «inhaber-urinet» legt ein Dokument «Neu» der Art «PROBE_DA_REGEL» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «EINGANGSKORB» an
    Und das Dokument «Neu» hat das Zusatzfeld «referenz» mit dem Wert «R-1»
    Wenn «inhaber-urinet» bestätigt das Dokument «Neu» mit diesen Angaben:
      """
      {"art":"PROBE_DA_REGEL","titel":"Probe","projekt":"Probe"}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 202
    Und enthält die Antwort der Klassifikationsverwaltung keine Warnung
