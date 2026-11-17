# language: de
@spec-03 @webseiten-erfassung @webseiten-pflege
Funktionalität: Bestätigen und Pflegen über die Webseiten-Schnittstelle

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Erfassungs-Schlüssel «A» für die Webseite «pflege-a» im Mandanten «urinet» ist angelegt

  @req-10.5
  Szenario: Bestätigen übernimmt Zusatzfelder atomar und reiht die Archivierung ein
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» das Pflegedokument mit «bestaetigen» und ETag «aktuell» bearbeitet:
      """
      {"art":"WEBTEST_ERFASSUNG","titel":"Bestätigte Publikation","vertraulichkeit":"INTERN","zusatz":{"zugriff":"vorstand","jahr":2026}}
      """
    Dann ist die letzte Antwort HTTP 202
    Und ist das Pflegedokument «ARCHIVIERUNG» und trägt eine neue Version

  @req-10.5 @req-10.11
  Szenario: Eine fehlgeschlagene Bestätigung rollt auch die bereits geprüften Zusatzfelder zurück
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» das Pflegedokument mit «bestaetigen» und ETag «aktuell» bearbeitet:
      """
      {"art":"WEBTEST_ERFASSUNG","zusatz":{"zugriff":"vorstand","jahr":2027}}
      """
    Dann ist die letzte Antwort HTTP 422 mit «PFLICHTFELD_FEHLT»
    Und ist das Pflegedokument des Schlüssels «A» nach der Ablehnung unverändert

  @req-10.5
  Szenario: Bestätigen mit Zusatzfeldern ohne Art meldet das Pflichtfeld wie die Web-App
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» das Pflegedokument mit «bestaetigen» und ETag «aktuell» bearbeitet:
      """
      {"zusatz":{"zugriff":"vorstand","jahr":2026}}
      """
    Dann ist die letzte Antwort HTTP 422 mit «PFLICHTFELD_FEHLT»
    Und ist das Pflegedokument des Schlüssels «A» nach der Ablehnung unverändert

  @req-10.5 @req-10.6 @req-10.9
  Szenario: Ein gültiger Metadaten-Patch erhält die Veröffentlichung und sichert den Bearbeiter
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «ARCHIVIERT» vor
    Wenn der Schlüssel «A» am Pflegedokument «veroeffentlichen» mit ETag «aktuell» ausführt
    Dann ist die letzte Antwort HTTP 200
    Wenn der Schlüssel «A» das Pflegedokument mit «PATCH» und ETag «aktuell» bearbeitet:
      """
      {"titel":"Geänderte Publikation","vertraulichkeit":"INTERN","zusatz":{"zugriff":"vorstand","jahr":2027},"grund":"Metadaten korrigiert"}
      """
    Dann ist die letzte Antwort HTTP 200
    Und ist die Veröffentlichung des Pflegedokuments true und der Grund «Metadaten korrigiert» mit Bearbeiter gesichert

  @req-10.5
  Szenariogrundriss: Geschützte Felder und falsche Typen werden nicht zugewiesen
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» das Pflegedokument mit «<aktion>» und ETag «aktuell» bearbeitet:
      """
      <inhalt>
      """
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Beispiele:
      | aktion      | inhalt                                         |
      | PATCH       | {"dateiname":null}                              |
      | bestaetigen | {"dateiname":"neu.pdf"}                         |
      | PATCH       | {"status":"ARCHIVIERT"}                         |
      | bestaetigen | {"mandant":"vhf-gsk"}                           |
      | PATCH       | {"zusatz":{"veroeffentlicht":null}}             |
      | bestaetigen | {"zusatz":{"veroeffentlicht":true}}             |
      | PATCH       | {"titel":42}                                   |

  @req-10.5
  Szenariogrundriss: Versionsprüfung gilt auch für Wiederholen und Veröffentlichen
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «ARCHIVIERT» vor
    Wenn der Schlüssel «A» am Pflegedokument «<aktion>» mit ETag «<etag>» ausführt
    Dann ist die letzte Antwort HTTP <http> mit «<code>»
    Beispiele:
      | aktion           | etag | http | code             |
      | veroeffentlichen | fehlt | 428 | VERSION_FEHLT    |
      | zurueckziehen    | *     | 412 | VERSION_KONFLIKT |
      | wiederholen      | W/"0" | 412 | VERSION_KONFLIKT |
      | bestaetigen      | "999" | 412 | VERSION_KONFLIKT |

  @req-10.5
  Szenario: Pflichtfelder, Zusatzschema und Änderungsgrund werden wie in der Web-App geprüft
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «ARCHIVIERT» vor
    Wenn der Schlüssel «A» das Pflegedokument mit «PATCH» und ETag «aktuell» bearbeitet:
      """
      {"titel":"Neuer Titel"}
      """
    Dann ist die letzte Antwort HTTP 422 mit «PFLICHTFELD_FEHLT»
    Wenn der Schlüssel «A» das Pflegedokument mit «PATCH» und ETag «aktuell» bearbeitet:
      """
      {"zusatz":{"zugriff":"unbekannt"},"grund":"Prüfung"}
      """
    Dann ist die letzte Antwort HTTP 422 mit «ZUSATZ_UNGUELTIG»

  @req-10.6 @req-10.9
  Szenario: Veröffentlichung und Rückzug sichern Fassung und Bearbeiter
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «ARCHIVIERT» vor
    Wenn der Schlüssel «A» am Pflegedokument «veroeffentlichen» mit ETag «aktuell» ausführt
    Dann ist die letzte Antwort HTTP 200
    Und ist die Veröffentlichung des Pflegedokuments true und der Grund «über Webseite veröffentlicht» mit Bearbeiter gesichert
    Wenn der Schlüssel «A» am Pflegedokument «zurueckziehen» mit ETag «aktuell» ausführt
    Dann ist die letzte Antwort HTTP 200
    Und ist die Veröffentlichung des Pflegedokuments false und der Grund «über Webseite zurückgezogen» mit Bearbeiter gesichert

  @req-10.6
  Szenariogrundriss: Veröffentlichung und Rückzug sind vor Archivierung gesperrt
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «<status>» vor
    Wenn der Schlüssel «A» am Pflegedokument «<aktion>» mit ETag «aktuell» ausführt
    Dann ist die letzte Antwort HTTP 409 mit «STATUS_KONFLIKT»
    Beispiele:
      | status              | aktion           |
      | EINGANGSKORB        | veroeffentlichen |
      | FEHLER              | zurueckziehen    |
      | ARCHIVIERUNG_FEHLER | veroeffentlichen |

  @req-10.10
  Szenario: Eine Änderung in der Web-App macht das ETag der Webseite ungültig
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «ARCHIVIERT» vor
    Wenn die Web-App das Pflegedokument des Schlüssels «A» ändert
    Und der Schlüssel «A» das Pflegedokument mit «PATCH» und ETag «gemerkt» bearbeitet:
      """
      {"titel":"Veraltete Änderung","grund":"Konfliktprobe"}
      """
    Dann ist die letzte Antwort HTTP 412 mit «VERSION_KONFLIKT»

  @req-10.7 @req-10.8
  Szenario: Entfernen bietet weder Löschen noch Verwerfen an
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» versucht, das Pflegedokument zu löschen oder zu verwerfen

  @req-10.8
  Szenariogrundriss: Wiederholen verwendet dieselben Fehlerzustände wie die Web-App
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «<status>» vor
    Wenn der Schlüssel «A» am Pflegedokument «wiederholen» mit ETag «aktuell» ausführt
    Dann ist die letzte Antwort HTTP <http>
    Beispiele:
      | status              | http |
      | FEHLER              | 202  |
      | ARCHIVIERUNG_FEHLER | 202  |
      | EINGANGSKORB        | 409  |

  @req-10.9
  Szenariogrundriss: Jede protokollierte Mutation verlangt einen gemeldeten Bearbeiter
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «ARCHIVIERT» vor
    Wenn der Schlüssel «A» am Pflegedokument «<aktion>» ohne gemeldeten Bearbeiter ausführt
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Beispiele:
      | aktion           |
      | PATCH            |
      | bestaetigen      |
      | veroeffentlichen |
      | zurueckziehen    |
      | wiederholen      |

  @req-10.13
  Szenario: Hilfslisten liefern Eingangskorb und aktive Arten mit Formularfeldern
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» die Hilfsliste «eingangskorb» abruft
    Dann enthält der Erfassungs-Eingangskorb das Pflegedokument ohne Dateinamen
    Wenn der Schlüssel «A» die Hilfsliste «dokumentarten» abruft
    Dann enthält die Artenliste die Formularfelder der Pflegeart

  @req-10.4
  Szenario: Die Detailansicht liefert die für eine Bestätigung nötigen Gruppenmetadaten
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Und das Pflegedokument des Schlüssels «A» hat Finanz- und Vertragsmetadaten
    Wenn der Schlüssel «A» das Dokument der Referenz «pflege» abruft
    Dann liefert die Detailansicht die Finanz- und Vertragsmetadaten ohne Dateinamen

  @req-10.14
  Szenario: Eine Prüfdatei verlangt den Bearbeiter und HEAD öffnet keinen Stream
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» die Prüfdatei ohne Bearbeiter abruft
    Dann ist die letzte Antwort HTTP 400 mit «ANFRAGE_UNGUELTIG»
    Wenn der Schlüssel «A» die Prüfdatei mit HEAD abruft
    Dann ist die letzte Antwort HTTP 405

  @req-10.5 @req-15.1
  Szenario: Ein zu grosser JSON-Body der Erfassung wird vor der Resource mit Problem Details abgelehnt
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» dem Pflegedokument 2 MB JSON als Änderung sendet
    Dann ist die letzte Antwort HTTP 413 mit «DATEI_ZU_GROSS»

  @req-10.14 @req-15.1
  Szenario: Die Prüfdatei hat eine engere Grenze, und auch eine abgelehnte Anfrage öffnet keinen Stream
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Wenn der Schlüssel «A» die Prüfdatei des Pflegedokuments abruft
    Dann ist die letzte Antwort HTTP 200
    Wenn der Schlüssel «A» die Prüfdatei des Pflegedokuments abruft
    Dann ist die letzte Antwort HTTP 200
    Wenn der Schlüssel «A» die Prüfdatei des Pflegedokuments abruft
    Dann ist die letzte Antwort HTTP 429 mit «ZU_VIELE_ANFRAGEN», «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «ANFRAGEN»
    Und sind nach höchstens 5 Sekunden alle Plätze für Streams wieder frei

  @req-10.14
  Szenariogrundriss: Zur Prüfung wird das Original ohne Speicheradresse gestreamt
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «<status>» vor
    Wenn der Schlüssel «A» die Prüfdatei des Pflegedokuments abruft
    Dann entspricht der Prüfstream dem Original ohne Adresse oder Dateinamen und ist protokolliert
    Beispiele:
      | status              |
      | NEU                 |
      | VERARBEITUNG        |
      | EINGANGSKORB        |
      | FEHLER              |
      | ARCHIVIERUNG        |
      | ARCHIVIERUNG_FEHLER |
      | ARCHIVIERT          |

  @req-10.14
  Szenario: Verworfene Dateien sind nicht mehr zur Prüfung abrufbar
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «VERWORFEN» vor
    Wenn der Schlüssel «A» die Prüfdatei des Pflegedokuments abruft
    Dann ist die letzte Antwort HTTP 404 mit «NICHT_GEFUNDEN»

  @req-10.3
  Szenario: Ohne die Referenzsperre ersetzt der Primärschlüssel nicht den fachlichen Konflikt
    Dann zeigt eine Gegenprobe ohne Referenzsperre beim Schlüssel «A» den Datenbankkonflikt

  @req-9.4 @req-10.8
  Szenario: Ein Lese-Schlüssel darf keinen Erfassungsweg aus dem automatisch ermittelten Inventar nutzen
    Angenommen für den Schlüssel «A» liegt ein Pflegedokument im Status «EINGANGSKORB» vor
    Und der Lese-Schlüssel «Lesen» für die Webseite «pflege-lesen» im Mandanten «urinet» ist angelegt
    Dann erreicht der Lese-Schlüssel «Lesen» keinen Erfassungs-Endpunkt des Inventars
