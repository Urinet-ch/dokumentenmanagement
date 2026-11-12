# language: de
@spec-01
Funktionalität: Metadaten ändern und Versionen
  Inhaber und Geschäftsstelle ändern die Metadaten eines Dokuments (A6, Vertraulichkeit und betroffene Person nur
  mit A8) über «PATCH /api/v1/dokumente/{id}» mit If-Match. Bei einem archivierten Dokument ist ein Grund Pflicht,
  die vorherige Fassung geht nach dokument_version, und eine neue Frist verlängert Frist und Sperre, verkürzt sie
  aber nie (Aufgabe 3.5). Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-10.1
  Szenario: Im Eingangskorb ändert der Inhaber Metadaten ohne Grund und ohne Fassung
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Wenn «inhaber-urinet» das Dokument «K» so ändert:
      | art             | QUITTUNG       |
      | titel           | Quittung Probe |
      | tags            | Werkzeug, Bar  |
      | vertraulichkeit | NORMAL         |
      | betrag          | 12.50          |
    Dann meldet das Archiv Status 200
    Und trägt die Antwort das ETag der neuen Version von «K»
    Und ist das Dokument «K» im Status «EINGANGSKORB»
    Und hat das Dokument «K» diese Werte:
      | art             | QUITTUNG       |
      | titel           | Quittung Probe |
      | vertraulichkeit | NORMAL         |
    Und hat das Dokument «K» 0 Fassungen mit Grund
    Und steht im Zugriffsprotokoll für «K» 1 Mal «AENDERN» von «inhaber-urinet»

  @req-10.1
  Szenario: Ein archiviertes Dokument ändert sich nur mit Grund und sichert die vorherige Fassung
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Und der Worker die Archivierung ausführt
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | titel | Quittung Werkstatt |
    Dann meldet das Archiv Status 422 mit Fehlercode «PFLICHTFELD_FEHLT»
    Und nennt die Antwort die fehlenden Felder "grund"
    Und hat das Dokument «Q» 0 Fassungen mit Grund
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | titel | Quittung Werkstatt  |
      | grund | Titel präzisiert    |
    Dann meldet das Archiv Status 200
    Und trägt die Antwort das ETag der neuen Version von «Q»
    Und ist das Dokument «Q» im Status «ARCHIVIERT»
    Und hat das Dokument «Q» diese Werte:
      | titel | Quittung Werkstatt |
    Und hat das Dokument «Q» 1 Fassung mit Grund
    Und hält die letzte Fassung von «Q» den Titel "Quittung" mit dem Grund "Titel präzisiert" von «inhaber-urinet» fest

  @req-10.1
  Szenario: Archiviert darf kein Pflichtfeld der Art und nicht das Geschäftsjahr neu fehlen
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Und der Worker die Archivierung ausführt
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | betrag |           |
      | grund  | Versehen  |
    Dann meldet das Archiv Status 422 mit Fehlercode «PFLICHTFELD_FEHLT»
    Und nennt die Antwort die fehlenden Felder "betrag"
    Und hat das Dokument «Q» die Belegnummer «null» mit Betrag 12.00 CHF
    Und hat das Dokument «Q» 0 Fassungen mit Grund
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | geschaeftsjahr |          |
      | grund          | Versehen |
    Dann meldet das Archiv Status 400 mit Fehlercode «ANFRAGE_UNGUELTIG»
    Und hat das Dokument «Q» diese Werte:
      | geschaeftsjahr | 2026 |
    Und hat das Dokument «Q» 0 Fassungen mit Grund

  @req-9.7
  Szenario: Ein späteres Belegdatum verlängert Frist und Sperre
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Und der Worker die Archivierung ausführt
    Und die Sperren der Dateien von «Q» sind gemerkt
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | datum          | 2027-02-01          |
      | geschaeftsjahr | 2027                |
      | grund          | Datum falsch erfasst |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «Q» diese Werte:
      | aufbewahren_bis  | 2037-12-31 |
      | frist_abgelaufen | false      |
    Und sind die Sperren der Dateien von «Q» verlängert
    Und hält das Zugriffsprotokoll für «Q» kein «SPERRE_KONFLIKT» fest

  @req-9.7
  Szenario: Ein früheres Belegdatum verkürzt bei Sperre nichts und wird als Konflikt protokolliert
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Und der Worker die Archivierung ausführt
    Und die Sperren der Dateien von «Q» sind gemerkt
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | datum          | 2025-03-10           |
      | geschaeftsjahr | 2025                 |
      | grund          | Datum falsch erfasst |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «Q» diese Werte:
      | datum           | 2025-03-10 |
      | aufbewahren_bis | 2036-12-31 |
    Und sind die Sperren der Dateien von «Q» unverändert
    Und hält das Zugriffsprotokoll für «Q» «SPERRE_KONFLIKT» von «inhaber-urinet» mit der Frist bisher «2036-12-31» und neu «2035-12-31» fest

  @req-9.7
  Szenario: Eine Art ohne gesetzliche Pflicht und ohne Sperre übernimmt eine frühere Frist ohne Konflikt
    Angenommen im Mandanten «urinet» gibt es den Kontakt «Partner_Probe»
    Und im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «K» so bestätigt:
      | art     | KORRESPONDENZ |
      | titel   | Anfrage Probe |
      | datum   | 2026-03-10    |
      | partner | Partner_Probe |
    Und der Worker die Archivierung ausführt
    Wenn «inhaber-urinet» das Dokument «K» so ändert:
      | datum          | 2025-03-10           |
      | geschaeftsjahr | 2025                 |
      | grund          | Datum falsch erfasst |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «K» diese Werte:
      | datum           | 2025-03-10 |
      | aufbewahren_bis | 2035-12-31 |
    Und liegen die Dateien von «K» unter archiv/ ohne Sperre
    Und hält das Zugriffsprotokoll für «K» kein «SPERRE_KONFLIKT» fest

  @req-9.7
  Szenario: Ein Wechsel auf eine Art ohne Pflicht verkürzt bei Sperre nichts und wird als Konflikt protokolliert
    Angenommen im Mandanten «urinet» gibt es den Kontakt «Partner_Probe»
    Und im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Und der Worker die Archivierung ausführt
    Und die Sperren der Dateien von «Q» sind gemerkt
    Wenn «inhaber-urinet» das Dokument «Q» so ändert:
      | art            | KORRESPONDENZ        |
      | partner        | Partner_Probe        |
      | datum          | 2025-03-10           |
      | geschaeftsjahr | 2025                 |
      | grund          | Art falsch erfasst   |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «Q» diese Werte:
      | art             | KORRESPONDENZ |
      | aufbewahren_bis | 2036-12-31    |
    Und sind die Sperren der Dateien von «Q» unverändert
    Und hält das Zugriffsprotokoll für «Q» «SPERRE_KONFLIKT» von «inhaber-urinet» mit der Frist bisher «2036-12-31» und neu «2035-12-31» fest

  @req-2.12 @req-10.7
  Szenario: mitarbeiter und treuhaender dürfen nicht ändern
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Und das Dokument «K» ist für alle Rollen sichtbar
    Wenn «mitarbeiter-urinet» das Dokument «K» so ändert:
      | titel | Neu |
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Wenn «treuhaender-urinet» das Dokument «K» so ändert:
      | titel | Neu |
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Und hat das Dokument «K» diese Werte:
      | titel | null |
    Und steht im Zugriffsprotokoll für «K» 0 Mal «AENDERN» von «mitarbeiter-urinet»
    Und steht im Zugriffsprotokoll für «K» 1 Mal «VERWEIGERT» von «mitarbeiter-urinet»
    Und steht im Zugriffsprotokoll für «K» 1 Mal «VERWEIGERT» von «treuhaender-urinet»

  @req-2.14 @req-2.23
  Szenario: Vertraulichkeit und betroffene Person ändert nur, wer A8 hat
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Und das Dokument «K» ist für alle Rollen sichtbar
    Wenn «mitarbeiter-urinet» das Dokument «K» so ändert:
      | vertraulichkeit | PERSONAL |
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Und hat das Dokument «K» diese Werte:
      | vertraulichkeit | NORMAL |
    Wenn «inhaber-urinet» das Dokument «K» so ändert:
      | vertraulichkeit | PERSONAL       |
      | personBenutzer  | ma-probe       |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «K» diese Werte:
      | vertraulichkeit | PERSONAL |
      | person_benutzer | ma-probe |

  @req-2.3 @req-2.6 @req-10.7
  Szenario: Ein für den Mitarbeiter unsichtbares Dokument ist nicht auffindbar (404 vor 403)
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «mitarbeiter-urinet» das Dokument «K» so ändert:
      | titel | Neu |
    Dann meldet das Archiv Status 404 mit Fehlercode «NICHT_GEFUNDEN»
    Und hat das Dokument «K» diese Werte:
      | titel | null |
    Und steht im Zugriffsprotokoll für «K» 1 Mal «VERWEIGERT» von «mitarbeiter-urinet»

  @req-2.6 @req-2.23
  Szenario: Die Geschäftsstelle ändert Metadaten und Vertraulichkeit im eigenen Mandanten
    Angenommen im Mandanten «vhf-gsk» liegt das Dokument «B» im Status «EINGANGSKORB», erfasst von «geschaeftsstelle-vhf-gsk»
    Wenn «geschaeftsstelle-vhf-gsk» das Dokument «B» so ändert:
      | titel           | Bulletin Probe |
      | tags            |                |
      | vertraulichkeit | INTERN         |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «B» diese Werte:
      | titel           | Bulletin Probe |
      | vertraulichkeit | INTERN         |
    Und steht im Zugriffsprotokoll für «B» 1 Mal «AENDERN» von «geschaeftsstelle-vhf-gsk»

  Szenario: Ein Dokument eines anderen Mandanten ist nicht auffindbar
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «geschaeftsstelle-vhf-gsk» das Dokument «K» so ändert:
      | titel | Neu |
    Dann meldet das Archiv Status 404 mit Fehlercode «NICHT_GEFUNDEN»
    Und hat das Dokument «K» diese Werte:
      | titel | null |

  @req-10.4 @req-10.6
  Szenario: Ohne If-Match oder mit veralteter Version ändert sich nichts
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «K» ohne If-Match ändert
    Dann meldet das Archiv Status 428 mit Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» das Dokument «K» mit einer veralteten Version ändert
    Dann meldet das Archiv Status 412 mit Fehlercode «VERSION_KONFLIKT»
    Und hat das Dokument «K» diese Werte:
      | titel | null |

  @req-10.4 @req-10.6
  Szenario: Auch ein ungültiger Körper scheitert zuerst an If-Match und am Status
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «K» ohne If-Match mit dem Körper '{}' ändert
    Dann meldet das Archiv Status 428 mit Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» das Dokument «K» ohne If-Match mit dem Körper '{"titel": ' ändert
    Dann meldet das Archiv Status 428 mit Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» das Dokument «K» mit einer veralteten Version und dem Körper '{"titel": "Neu", "status": "ARCHIVIERT"}' ändert
    Dann meldet das Archiv Status 412 mit Fehlercode «VERSION_KONFLIKT»
    Und hat das Dokument «K» diese Werte:
      | titel | null |

  Szenario: Während der Archivierung lässt sich nichts ändern
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «ARCHIVIERUNG», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «K» so ändert:
      | titel | Neu |
    Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»
    Wenn «inhaber-urinet» das Dokument «K» mit dem Körper '{}' ändert
    Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»

  Szenariogrundriss: Eine ungültige Änderung antwortet mit 400: <fall>
    Angenommen im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «K» mit dem Körper '<koerper>' ändert
    Dann meldet das Archiv Status 400 mit Fehlercode «ANFRAGE_UNGUELTIG»
    Und hat das Dokument «K» diese Werte:
      | titel | null |

    Beispiele:
      | fall                       | koerper                                    |
      | leer                       | {}                                         |
      | unbekanntes Feld           | {"titel": "Neu", "status": "ARCHIVIERT"}   |
      | Finanz ohne Art            | {"titel": "Neu", "betrag": 10}             |
      | Vertraulichkeit leer       | {"titel": "Neu", "vertraulichkeit": null}  |
      | Art unbekannt              | {"titel": "Neu", "art": "GIBT_ES_NICHT"}   |
      | Zusatz kein Objekt         | {"titel": "Neu", "zusatz": [1]}            |
