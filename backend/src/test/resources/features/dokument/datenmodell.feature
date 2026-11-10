# language: de
@spec-01
Funktionalität: Datenmodell Dokument – Versionen, Zusatzfelder und Indizes (Aufgabe 2.2)
  Bis «PATCH /api/v1/dokumente/{id}» mit Aufgabe 3.5 kommt, ändert der Probe-Endpunkt
  «PATCH /api/v1/probe/dokumente/{id}/zusatz» (A6, If-Match) die Zusatzfelder über den Anwendungsfall
  «DokumentZusatzSpeichern». Indizes und Trigger prüfen die Szenarien direkt in der Datenbank.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Dokumente:
      | name       | art      | vertraulichkeit | bestaetigt | erfasser           | person | status       |
      | ARCHIV     | QUITTUNG | NORMAL          | ja         | inhaber-urinet     |        | ARCHIVIERT   |
      | KORB       | QUITTUNG | NORMAL          | ja         | inhaber-urinet     |        | EINGANGSKORB |
      | OHNE_ART   |          | NORMAL          | nein       | mitarbeiter-urinet |        | EINGANGSKORB |
      | ZWEITES    |          | NORMAL          | nein       | mitarbeiter-urinet |        | EINGANGSKORB |
    Und im Mandanten «vhf-gsk» liegen diese Dokumente:
      | name       | art | vertraulichkeit | bestaetigt | erfasser                 | person | status       |
      | V_KORB     |     | NORMAL          | nein       | geschaeftsstelle-vhf-gsk |        | EINGANGSKORB |

  @req-10.1
  Szenario: Eine Änderung an einem archivierten Dokument verlangt einen Grund und sichert die vorherige Fassung
    Wenn «inhaber-urinet» die Zusatzfelder des Dokuments «ARCHIV» auf "{}" setzt
    Dann antwortet der Dokument-Endpunkt mit Status 422 und Fehlercode «PFLICHTFELD_FEHLT»
    Und hat das Dokument «ARCHIV» 0 Fassungen in dokument_version
    Wenn «inhaber-urinet» die Zusatzfelder des Dokuments «ARCHIV» mit dem Grund "Tippfehler korrigiert" auf "{}" setzt
    Dann antwortet der Dokument-Endpunkt mit Status 200
    Und hat das Dokument «ARCHIV» 1 Fassung in dokument_version
    Und hält die letzte Fassung des Dokuments «ARCHIV» fest:
      | version       | 0                     |
      | geaendert_von | inhaber-urinet        |
      | grund         | Tippfehler korrigiert |
      | zusatz        | null                  |
      | zeit          | gesetzt               |

  @req-10.1
  Szenario: Vor der Archivierung entsteht keine Fassung
    Wenn «inhaber-urinet» die Zusatzfelder des Dokuments «KORB» auf "{}" setzt
    Dann antwortet der Dokument-Endpunkt mit Status 200
    Und hat das Dokument «KORB» 0 Fassungen in dokument_version

  Szenario: Eine veraltete Version antwortet mit 412
    Wenn «inhaber-urinet» die Zusatzfelder des Dokuments «KORB» mit der Version 7 auf "{}" setzt
    Dann antwortet der Dokument-Endpunkt mit Status 412 und Fehlercode «VERSION_KONFLIKT»

  @req-9.5
  Szenario: Zusatzwerte prüft das Schema der Art; ohne Art sind keine Zusatzwerte erlaubt
    Wenn «inhaber-urinet» die Zusatzfelder des Dokuments «KORB» auf "{\"unbekannt\": 1}" setzt
    Dann antwortet der Dokument-Endpunkt mit Status 422 und Fehlercode «ZUSATZ_UNGUELTIG»
    Wenn «inhaber-urinet» die Zusatzfelder des Dokuments «OHNE_ART» auf "{}" setzt
    Dann antwortet der Dokument-Endpunkt mit Status 422 und Fehlercode «ZUSATZ_UNGUELTIG»

  @req-10.3
  Szenariogrundriss: dokument_version erlaubt nur Einfügen: <befehl> verweigert die Datenbank
    Angenommen «inhaber-urinet» hat die Zusatzfelder des Dokuments «ARCHIV» mit einem Grund geändert
    Dann verweigert die Datenbank von «urinet» <befehl> auf dokument_version
    Und hat das Dokument «ARCHIV» 1 Fassung in dokument_version

    Beispiele:
      | befehl   |
      | UPDATE   |
      | DELETE   |
      | TRUNCATE |

  Szenario: Die Dublettenprüfung je Datei zählt verworfene Dokumente nicht und gilt je Mandant
    Angenommen das Dokument «OHNE_ART» hat das Original mit der Prüfsumme «a»
    Wenn das Dokument «ZWEITES» ein Original mit der Prüfsumme «a» erhält
    Dann weist die Datenbank es mit «dokument_datei_dublette_ux» ab
    Wenn das Dokument «V_KORB» ein Original mit der Prüfsumme «a» erhält
    Dann nimmt die Datenbank es an
    Wenn das Dokument «OHNE_ART» verworfen ist
    Und das Dokument «ZWEITES» ein Original mit der Prüfsumme «a» erhält
    Dann nimmt die Datenbank es an

  Szenario: Eine eigene Belegnummer ist je Art eindeutig
    Angenommen das Dokument «ARCHIV» hat die eigene Belegnummer «Q-1» der Art «QUITTUNG»
    Wenn das Dokument «KORB» die eigene Belegnummer «Q-1» der Art «QUITTUNG» erhält
    Dann weist die Datenbank es mit «belegnummer_eigen_pk» ab
    Wenn das Dokument «KORB» die eigene Belegnummer «Q-1» der Art «LIEFERANTENBELEG» erhält
    Dann nimmt die Datenbank es an
