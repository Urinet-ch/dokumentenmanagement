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
    Wenn «mitarbeiter-urinet» den Lückenbericht für das Geschäftsjahr «2003» abruft
    Dann antwortet der Lückenbericht mit Status 403 und Fehlercode «NICHT_ERLAUBT»

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
