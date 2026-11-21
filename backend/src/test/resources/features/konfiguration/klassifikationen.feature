# language: de
@spec-05
Funktionalität: Klassifikationen je Mandant
  Klassifikationen und ihre Zuordnungen bilden die Ordnung eines Mandanten ab und bleiben mit Dokumenten
  nachvollziehbar.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-3.1 @req-3.2 @req-3.4
  Szenario: Eine Klassifikation anlegen, unveränderliche Codes prüfen und einer Art zuordnen
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":true,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Und enthält die Antwort den Wert «bau» der Klassifikation
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Doppelt","werte":[],"mehrfach":false,
       "kiVorschlag":false,"suchfilter":false,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fach-gebiet","name":"Ungültiger Code","werte":[],"mehrfach":false,
       "kiVorschlag":false,"suchfilter":false,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Wenn «inhaber-urinet» versucht, den Code der Klassifikation «fachgebiet» auf «gebiet» zu ändern
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Und enthält die Antwort den Standardwert «bau»

  @req-3.3 @req-1.7
  Szenario: Inaktive Werte bleiben an Dokumenten sichtbar und ungültige Eingangskorbwerte werden markiert
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Klassifikation-alt» der Art «PROJEKTDOKUMENT» mit der Klassifikation «fachgebiet» und dem Wert «bau» im Status «ARCHIVIERT» an
    Und «inhaber-urinet» legt ein Dokument «Klassifikation-neu» der Art «PROJEKTDOKUMENT» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und «inhaber-urinet» legt ein Dokument «Eingangskorb-klasse» der Art «PROJEKTDOKUMENT» mit der Klassifikation «fachgebiet» und dem Wert «bau» im Status «EINGANGSKORB» an
    Wenn «inhaber-urinet» versucht, den Wert «bau» der Klassifikation «fachgebiet» zu löschen
    Dann antwortet die Klassifikationsverwaltung mit Status 409 und Fehlercode «KONFIGURATION_IN_GEBRAUCH»
    Wenn «inhaber-urinet» versucht, die Klassifikation «fachgebiet» zu löschen
    Dann antwortet die Klassifikationsverwaltung mit Status 409 und Fehlercode «KONFIGURATION_IN_GEBRAUCH»
    Wenn «inhaber-urinet» entfernt den Standardwert «bau» der Klassifikation «fachgebiet» von der Dokumentart «PROJEKTDOKUMENT»
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Wenn «inhaber-urinet» deaktiviert den Wert «bau» der Klassifikation «fachgebiet»
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Wenn «inhaber-urinet» liest das Dokument «Klassifikation-alt»
    Dann enthält das Dokument «Klassifikation-alt» die Klassifikation «fachgebiet» mit dem Wert «bau»
    Und ist keine Klassifikation des Dokuments «Klassifikation-alt» ungültig
    Wenn «inhaber-urinet» liest das Dokument «Eingangskorb-klasse»
    Dann ist die Klassifikation «fachgebiet» des Dokuments «Eingangskorb-klasse» ungültig
    Wenn «inhaber-urinet» liest den Eingangskorb
    Dann ist im Eingangskorb die Klassifikation «fachgebiet» des Dokuments «Eingangskorb-klasse» ungültig
    Wenn «inhaber-urinet» ändert die Klassifikationen des Dokuments «Klassifikation-alt» auf:
      """
      {"fachgebiet":["bau"]}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Wenn «inhaber-urinet» ändert die Klassifikationen des Dokuments «Klassifikation-neu» auf:
      """
      {"fachgebiet":["bau"]}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «KLASSIFIKATION_UNGUELTIG»
    Wenn «inhaber-urinet» entfernt die Zuordnung «fachgebiet» von der Dokumentart «PROJEKTDOKUMENT»
    Dann antwortet die Klassifikationsverwaltung mit Status 204
    Wenn «inhaber-urinet» liest das Dokument «Eingangskorb-klasse»
    Dann ist die Klassifikation «fachgebiet» des Dokuments «Eingangskorb-klasse» ungültig
    Wenn «inhaber-urinet» liest den Eingangskorb
    Dann ist im Eingangskorb die Klassifikation «fachgebiet» des Dokuments «Eingangskorb-klasse» ungültig

  @req-1.7
  Szenario: Eine fehlende Pflicht-Klassifikation gilt im Eingangskorb nicht als ungültiger Wert
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» als Pflicht ohne Standardwert zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Eingangskorb-pflicht» der Art «PROJEKTDOKUMENT» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «EINGANGSKORB» an
    Wenn «inhaber-urinet» liest das Dokument «Eingangskorb-pflicht»
    Dann ist keine Klassifikation des Dokuments «Eingangskorb-pflicht» ungültig
    Wenn «inhaber-urinet» liest den Eingangskorb
    Dann ist im Eingangskorb keine Klassifikation des Dokuments «Eingangskorb-pflicht» ungültig

  @req-3.5
  Szenario: Eine Klassifikation wird beim Bestätigen geprüft
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Bestätigung-klasse» der Art «PROJEKTDOKUMENT» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «EINGANGSKORB» an
    Wenn «inhaber-urinet» bestätigt das Dokument «Bestätigung-klasse» mit diesen Angaben:
      """
      {"art":"PROJEKTDOKUMENT","titel":"Probe","projekt":"Probe","klassifikationen":{"fachgebiet":["bau"]}}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 202
    Wenn «inhaber-urinet» liest das Dokument «Bestätigung-klasse»
    Dann enthält das Dokument «Bestätigung-klasse» die Klassifikation «fachgebiet» mit dem Wert «bau»

  @req-3.5
  Szenario: Der Generator lehnt nicht konfigurierte Klassifikationswerte ab
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Wenn der Generator die PDF «klassifikation.pdf» mit diesen Metadaten sendet:
      """
      {"art":"PROJEKTDOKUMENT","titel":"Probe","projekt":"Probe","klassifikationen":{"fachgebiet":["unbekannt"]}}
      """
    Dann antwortet der Generator mit Status 422 und Fehlercode «KLASSIFIKATION_UNGUELTIG»
    Und nennt der Generator die ungültige Klassifikation «fachgebiet»

  @req-3.1
  Szenario: Nur der inhaber pflegt Klassifikationen
    Wenn «mitarbeiter-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[],"mehrfach":false,"kiVorschlag":false,
       "suchfilter":false,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 403

  @req-3.5
  Szenario: Ein unverändert behaltener inaktiver Wert bleibt bei mehrfachen Klassifikationen erlaubt
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":true,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «elektro» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Mehrfach-alt» der Art «PROJEKTDOKUMENT» mit der Klassifikation «fachgebiet» und dem Wert «bau» im Status «ARCHIVIERT» an
    Wenn «inhaber-urinet» deaktiviert den Wert «bau» der Klassifikation «fachgebiet»
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Wenn «inhaber-urinet» ändert die Klassifikationen des Dokuments «Mehrfach-alt» auf:
      """
      {"fachgebiet":["bau","elektro"]}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 200

  @req-3.6
  Szenario: Ein Artwechsel über die Schnittstelle entfernt nicht zugeordnete Werte, belegt vor und hält sie in der Fassung fest
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"zielgruppe","name":"Zielgruppe","werte":[{"code":"alle","name":"Alle"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «LIEFERANTENBELEG» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «zielgruppe» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «alle» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Artwechsel-klasse» der Art «LIEFERANTENBELEG» mit der Klassifikation «fachgebiet» und dem Wert «bau» im Status «ARCHIVIERT» an
    Wenn «inhaber-urinet» wechselt die Art des Dokuments «Artwechsel-klasse» auf «PROJEKTDOKUMENT»
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Und enthält das Dokument «Artwechsel-klasse» die Klassifikation «zielgruppe» mit dem Wert «alle»
    Und enthält das Dokument «Artwechsel-klasse» keine Klassifikation «fachgebiet»
    Und bleibt die letzte Fassung des Dokuments «Artwechsel-klasse» bei der Klassifikation «fachgebiet» auf dem Wert «bau»

  @req-3.6
  Szenario: Ein Artwechsel entfernt nicht zugeordnete Werte und belegt nur leere Klassifikationen
    Wenn eine Test-Konfiguration beim Artwechsel Klassifikationen vorbelegt
    Dann beim Artwechsel bleiben nur zugeordnete Werte erhalten und leere Klassifikationen werden vorbelegt

  @req-3.7 @req-3.8
  Szenario: Eine Klassifikationsänderung wird versioniert und lässt freie Tags bestehen
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Version-klasse» der Art «PROJEKTDOKUMENT» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Und das Dokument «Version-klasse» hat den freien Tag «freier-tag»
    Wenn «inhaber-urinet» ändert die Klassifikationen des Dokuments «Version-klasse» auf:
      """
      {"fachgebiet":["bau"]}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Wenn «inhaber-urinet» ändert die Klassifikationen des Dokuments «Version-klasse» auf:
      """
      {"fachgebiet":["elektro"]}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Und bleibt die letzte Fassung des Dokuments «Version-klasse» bei der Klassifikation «fachgebiet» auf dem Wert «bau»
    Und hat das Dokument «Version-klasse» weiterhin den freien Tag «freier-tag»

  @req-8.6
  Szenariogrundriss: Öffentliche Klassifikationen verwenden keine reservierten Parameternamen
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"<code>","name":"Probe","werte":[],"mehrfach":false,"kiVorschlag":false,"suchfilter":false,"oeffentlichFilter":true}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

    Beispiele:
      | code |
      | seit |
      | max |
      | referenz |
      | groesse |
      | status |
      | url |
      | dateiname |

  @req-3.4
  Szenario: Eine Zuordnung zu einer unbekannten Dokumentart gibt es nicht
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «GIBT_ES_NICHT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-1.3
  Szenario: Umbenennungen und ein neuer Standardwert stehen mit altem und neuem Wert im Zugriffsprotokoll
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» benennt die Klassifikation «fachgebiet» in «Themengebiet» um
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Und steht im letzten Konfigurationseintrag von «urinet» die Änderung «klassifikationen.fachgebiet.name» von «Fachgebiet» auf «Themengebiet»
    Wenn «inhaber-urinet» benennt den Wert «bau» der Klassifikation «fachgebiet» in «Hochbau» um
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Und steht im letzten Konfigurationseintrag von «urinet» die Änderung «klassifikationen.fachgebiet.werte.bau.name» von «Bau» auf «Hochbau»
    Wenn «inhaber-urinet» setzt den Standardwert der Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» auf «elektro»
    Dann antwortet die Klassifikationsverwaltung mit Status 200
    Und steht im letzten Konfigurationseintrag von «urinet» die Änderung «arten.PROJEKTDOKUMENT.klassifikationen.fachgebiet.standardWert» von «bau» auf «elektro»

  @req-1.2 @req-9.3
  Szenario: Klassifikationen, Zuordnungen und Dokumentwerte bleiben im eigenen Mandanten
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen «inhaber-urinet» legt ein Dokument «Trennung-urinet» der Art «PROJEKTDOKUMENT» mit der Klassifikation «fachgebiet» und dem Wert «bau» im Status «ARCHIVIERT» an
    Und «inhaber-vhf-gsk» legt ein Dokument «Trennung-vhf» der Art «PROBE_VHF_BULLETIN» mit der Klassifikation «ohne» und dem Wert «ohne» im Status «ARCHIVIERT» an
    Wenn «inhaber-vhf-gsk» liest die Klassifikationen
    Dann fehlt die Klassifikation «fachgebiet» in der Liste
    Wenn «inhaber-vhf-gsk» deaktiviert den Wert «bau» der Klassifikation «fachgebiet»
    Dann antwortet die Klassifikationsverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» versucht, die Klassifikation «fachgebiet» zu löschen
    Dann antwortet die Klassifikationsverwaltung mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» liest das Dokument «Trennung-urinet»
    Dann antwortet die Klassifikationsverwaltung mit Status 404
    Wenn «inhaber-vhf-gsk» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet VHF","werte":[{"code":"statik","name":"Statik"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-vhf-gsk» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROBE_VHF_BULLETIN» mit dem Standardwert «statik» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-vhf-gsk» ändert die Klassifikationen des Dokuments «Trennung-vhf» auf:
      """
      {"fachgebiet":["bau"]}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 422 und Fehlercode «KLASSIFIKATION_UNGUELTIG»
    Wenn «inhaber-vhf-gsk» entfernt die Zuordnung «fachgebiet» von der Dokumentart «PROBE_VHF_BULLETIN»
    Dann antwortet die Klassifikationsverwaltung mit Status 204
    Wenn «inhaber-vhf-gsk» versucht, die Klassifikation «fachgebiet» zu löschen
    Dann antwortet die Klassifikationsverwaltung mit Status 204
    Wenn «inhaber-urinet» versucht, die Klassifikation «fachgebiet» zu löschen
    Dann antwortet die Klassifikationsverwaltung mit Status 409 und Fehlercode «KONFIGURATION_IN_GEBRAUCH»
    Wenn «inhaber-urinet» liest das Dokument «Trennung-urinet»
    Dann enthält das Dokument «Trennung-urinet» die Klassifikation «fachgebiet» mit dem Wert «bau»
