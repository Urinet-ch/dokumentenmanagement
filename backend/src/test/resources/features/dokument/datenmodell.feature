# language: de
@spec-01
Funktionalität: Datenmodell Dokument – Versionen, Zusatzfelder und Indizes (Aufgabe 2.2)
  Die Zusatzfelder ändert «PATCH /api/v1/dokumente/{id}» (A6, If-Match, seit Aufgabe 3.5; vorher ein
  Probe-Endpunkt). Indizes und Trigger prüfen die Szenarien direkt in der Datenbank.

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

  # Nur eigene Arten (Urinet ist Aussteller) landen in «belegnummer_eigen»; die Auswahl trifft die Anwendung (3.x, 11.x).
  Szenario: Eine eigene Belegnummer ist je Art eindeutig
    Angenommen das Dokument «ARCHIV» hat die eigene Belegnummer «2026-001» der Art «AUSGANGSRECHNUNG»
    Wenn das Dokument «KORB» die eigene Belegnummer «2026-001» der Art «AUSGANGSRECHNUNG» erhält
    Dann weist die Datenbank es mit «belegnummer_eigen_pk» ab
    Wenn das Dokument «KORB» die eigene Belegnummer «2026-001» der Art «OFFERTE» erhält
    Dann nimmt die Datenbank es an

  @req-3.11 @req-14.9
  Szenario: Den Dateinamen sieht nur, wer das Dokument sehen darf, in beiden Mandanten
    Angenommen im Mandanten «urinet» liegen diese Dokumente:
      | name      | art      | vertraulichkeit | bestaetigt | erfasser           | person | status       | dateiname                |
      | DN_NORMAL | QUITTUNG | NORMAL          | ja         | inhaber-urinet     |        | ARCHIVIERT   | Quittung_Probe.pdf       |
      | DN_INTERN | QUITTUNG | INTERN          | ja         | inhaber-urinet     |        | ARCHIVIERT   | Lohn_Probe_intern.pdf    |
      | DN_KORB   |          | NORMAL          | nein       | mitarbeiter-urinet |        | EINGANGSKORB | Scan_Probe.pdf           |
    Und im Mandanten «vhf-gsk» liegen diese Dokumente:
      | name      | art | vertraulichkeit | bestaetigt | erfasser                 | person | status       | dateiname          |
      | DN_VHF    |     | NORMAL          | nein       | geschaeftsstelle-vhf-gsk |        | EINGANGSKORB | Bulletin_Probe.pdf |
    Dann sehen die Benutzer den Dateinamen so:
      | benutzer                 | dokument  | status | dateiname             |
      | inhaber-urinet           | DN_NORMAL | 200    | Quittung_Probe.pdf    |
      | mitarbeiter-urinet       | DN_NORMAL | 200    | Quittung_Probe.pdf    |
      | treuhaender-urinet       | DN_INTERN | 200    | Lohn_Probe_intern.pdf |
      | mitarbeiter-urinet       | DN_INTERN | 404    |                       |
      | mitarbeiter-urinet       | DN_KORB   | 200    | Scan_Probe.pdf        |
      | geschaeftsstelle-vhf-gsk | DN_NORMAL | 404    |                       |
      | inhaber-vhf-gsk          | DN_INTERN | 404    |                       |
      | geschaeftsstelle-vhf-gsk | DN_VHF    | 200    | Bulletin_Probe.pdf    |
      | inhaber-urinet           | DN_VHF    | 404    |                       |

  @req-3.11 @req-16.11
  Szenario: Ein Dokument ohne Dateinamen, angelegt wie vom Vorgänger-Release, bleibt lesbar
    Angenommen im Mandanten «urinet» liegen diese Dokumente:
      | name    | art      | vertraulichkeit | bestaetigt | erfasser       | person | status     |
      | DN_ALT  | QUITTUNG | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT |
    Dann sehen die Benutzer den Dateinamen so:
      | benutzer       | dokument | status | dateiname |
      | inhaber-urinet | DN_ALT   | 200    |           |

  @req-16.11
  Szenario: Nach dem Rücksprung schreibt und sucht das Vorgänger-Release mit der neuen Spalte weiter
    Wenn das Vorgänger-Release im Mandanten «urinet» das Dokument «DN_RUECK» mit dem Titel «Synthetischer Rückweg» anlegt
    Dann findet die Suche des Vorgänger-Releases mit «Rückweg» das Dokument «DN_RUECK» ohne Dateinamen

  @req-14.9 @req-3.11
  Szenario: Der Eingangskorb nennt den Dateinamen
    Angenommen im Mandanten «urinet» liegen diese Dokumente:
      | name     | art | vertraulichkeit | bestaetigt | erfasser           | person | status       | dateiname              |
      | DN_SCAN  |     | NORMAL          | nein       | mitarbeiter-urinet |        | EINGANGSKORB | Scan_Rechnung-2026.pdf |
    Wenn «inhaber-urinet» den Eingangskorb mit Seite 0 und Grösse 100 abruft
    Dann nennt der Eingangskorb beim Dokument «DN_SCAN» den Dateinamen "Scan_Rechnung-2026.pdf"
    Und nennt der Eingangskorb beim Dokument «OHNE_ART» keinen Dateinamen
