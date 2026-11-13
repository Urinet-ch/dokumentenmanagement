# language: de
@spec-02
Funktionalität: Jahresexport für den Treuhänder (Aufgabe 6.5)
  «POST /api/v1/exporte» reiht für Inhaber und Treuhänder den Auftrag EXPORT_JAHR ein; der Worker schreibt das ZIP
  stromweise nach «exporte/<id>.zip» (ohne Sperre, Lebenszyklus 7 Tage): die PDF/A-Fassungen der Belege des
  Geschäftsjahres im Status ARCHIVIERT (bei pdfa_fehlt das Original), «belegliste.csv», «pruefsummen.sha256» und die
  Manifeste des Jahres. «GET /api/v1/exporte/{id}» liefert die vorsignierte Adresse, gültig bis 24 Stunden nach der
  Fertigstellung. Belege, Dateien und Manifeste sind synthetisch und liegen in MinIO.

 Hintergrund:
  Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
  Und im Mandanten «urinet» gibt es keinen offenen Export
  Und im Mandanten «urinet» liegen diese Belege:
   | name | art | datum | jahr | status | partner | belegnummer | richtung | betrag | vertraulichkeit | pdfa_fehlt |
   | B_RECHNUNG | AUSGANGSRECHNUNG | 2025-03-14 | 2025 | ARCHIVIERT | Müller & Söhne AG | 2025-007 | EINNAHME | 1250.50 | NORMAL | nein |
   | B_LOHN | LOHNABRECHNUNG | 2025-06-30 | 2025 | ARCHIVIERT | | | AUSGABE | 4200.00 | PERSONAL | nein |
   | B_ORIGINAL | LIEFERANTENBELEG | 2025-09-01 | 2025 | ARCHIVIERT | =HYPERLINK("x") | F0003 | AUSGABE | 99.90 | INTERN | ja |
   | B_EINGANG | QUITTUNG | 2025-10-01 | 2025 | EINGANGSKORB | Bäckerei Beispiel | | AUSGABE | 12.00 | NORMAL | nein |
   | B_VORJAHR | QUITTUNG | 2024-12-31 | 2024 | ARCHIVIERT | Bäckerei Beispiel | | AUSGABE | 8.50 | NORMAL | nein |
  Und im Mandanten «vhf-gsk» liegen diese Belege:
   | name | art | datum | jahr | status | partner | belegnummer | richtung | betrag | vertraulichkeit | pdfa_fehlt |
   | V_FREMD | PROBE_VHF_BULLETIN | 2025-05-05 | 2025 | ARCHIVIERT | Fremder Verein | | AUSGABE | 1.00 | NORMAL | nein |
  Und im Mandanten «urinet» liegen die Manifeste vom 2025-03-14 und vom 2024-06-01 mit Zeitstempel «sgpki»

  @req-7.1 @req-7.4
  Szenario: Das ZIP enthält genau die archivierten Belege des Geschäftsjahres, Belegliste, Prüfsummen und Manifeste
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Dann antwortet das Archiv auf die Anforderung mit 202 und Status «OFFEN»
    Und für «urinet» ist ein Auftrag «EXPORT_JAHR» eingereiht
    Wenn der Worker die Aufträge abarbeitet
    Dann ist der Export «FERTIG» mit 3 Belegen
    Und das ZIP enthält genau diese Dateien:
      | datei |
      | belege/2025-03-14_AUSGANGSRECHNUNG_2025-007_Mueller-Soehne-AG.pdf |
      | belege/2025-06-30_LOHNABRECHNUNG_{B_LOHN}_ohne-partner.pdf |
      | belege/2025-09-01_LIEFERANTENBELEG_F0003_HYPERLINK-x.pdf |
      | manifeste/2025-03-14.json |
      | manifeste/2025-03-14.sig |
      | manifeste/2025-03-14.sgpki.tsr |
      | manifeste/2025-03-14.sgpki.kette.pem |
      | belegliste.csv |
      | pruefsummen.sha256 |
    Und im ZIP hat «belege/2025-03-14_AUSGANGSRECHNUNG_2025-007_Mueller-Soehne-AG.pdf» den Inhalt «PDF/A B_RECHNUNG»
    Und im ZIP hat «belege/2025-09-01_LIEFERANTENBELEG_F0003_HYPERLINK-x.pdf» den Inhalt «Original B_ORIGINAL»
    Und im ZIP hat «manifeste/2025-03-14.json» den Inhalt «Manifest 2025-03-14 json»
    Und «belegliste.csv» im ZIP ist UTF-8 mit BOM, Semikolon und CRLF und hat diese Zeilen:
      | ID | Art | Datum | Partner | Belegnummer | Richtung | Betrag | Währung | SHA-256 | Dateiname |
      | {B_RECHNUNG} | AUSGANGSRECHNUNG | 2025-03-14 | Müller & Söhne AG | 2025-007 | EINNAHME | 1250.50 | CHF | {sha B_RECHNUNG} | belege/2025-03-14_AUSGANGSRECHNUNG_2025-007_Mueller-Soehne-AG.pdf |
      | {B_LOHN} | LOHNABRECHNUNG | 2025-06-30 | | | AUSGABE | 4200.00 | CHF | {sha B_LOHN} | belege/2025-06-30_LOHNABRECHNUNG_{B_LOHN}_ohne-partner.pdf |
      | {B_ORIGINAL} | LIEFERANTENBELEG | 2025-09-01 | '=HYPERLINK("x") | F0003 | AUSGABE | 99.90 | CHF | {sha B_ORIGINAL} | belege/2025-09-01_LIEFERANTENBELEG_F0003_HYPERLINK-x.pdf |
    Und «pruefsummen.sha256» im ZIP nennt jede andere Datei des ZIP mit ihrer SHA-256

  @req-7.4
  Szenario: Dateinamen im ZIP folgen dem Muster, nur ASCII und ohne Leerzeichen
    Wenn «treuhaender-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Dann folgt jeder Name unter «belege/» im ZIP dem Muster «<datum>_<art>_<belegnummer|id>_<partner>.pdf» nur mit ASCII ohne Leerzeichen

  @req-7.2 @req-7.6
  Szenario: Der fertige Export ist 24 Stunden lang über eine Adresse abrufbar und wird ohne Inhalte per E-Mail gemeldet
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Und «treuhaender-urinet» den Export abruft
    Dann liefert das Archiv eine Adresse, die höchstens bis 24 Stunden nach der Fertigstellung gilt und nicht zwischengespeichert wird
    Und die Adresse liefert das ZIP mit der SHA-256 des Exports
    Und hat der Inhaber eine Mail zum Export erhalten, die nur den Link auf die Seite «Export» und die Export-ID nennt

  @req-7.2
  Szenario: Nach 24 Stunden ist der Download-Link abgelaufen
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Und der Export vor 25 Stunden fertig geworden ist
    Und «inhaber-urinet» den Export abruft
    Dann liefert das Archiv den Export mit Status «ABGELAUFEN» ohne Adresse

  @req-7.3
  Szenario: Anforderung, Abschluss mit Prüfsumme und Download stehen im Zugriffsprotokoll
    Angenommen der Stand des Zugriffsprotokolls für den Export von «urinet» ist gemerkt
    Wenn «treuhaender-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Und «inhaber-urinet» den Export abruft
    Dann enthält das Zugriffsprotokoll von «urinet» für den Export genau diese neuen Einträge:
      | benutzer | rolle | aktion | sha256 |
      | treuhaender-urinet | TREUHAENDER | EXPORTIEREN | |
      | treuhaender-urinet | TREUHAENDER | EXPORTIEREN | ZIP |
      | inhaber-urinet | INHABER | HERUNTERLADEN | ZIP |

  @req-7.5
  Szenariogrundriss: Ohne Rolle inhaber oder treuhaender gibt es weder Export noch Auftrag (<benutzer>)
    Angenommen der Stand des Zugriffsprotokolls für den Export von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Jahresexport 2025 anfordert
    Dann antwortet das Archiv auf die Anforderung mit 403 «NICHT_ERLAUBT»
    Und für «<mandant>» ist kein Auftrag «EXPORT_JAHR» eingereiht
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag VERWEIGERT von «<benutzer>»
    Wenn «<benutzer>» die Liste der Exporte abruft
    Dann antwortet das Archiv auf die Anforderung mit 403 «NICHT_ERLAUBT»
    # erst das Recht, dann die Eingabe: auch eine ungültige ID oder ein falscher Body ergibt 403 mit VERWEIGERT
    Angenommen der Stand des Zugriffsprotokolls für den Export von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Export «keine-uuid» abruft
    Dann antwortet das Archiv auf die Anforderung mit 403 «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag VERWEIGERT von «<benutzer>»
    Angenommen der Stand des Zugriffsprotokolls für den Export von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Jahresexport mit dem Body «{"geschaeftsjahr":"x"}» anfordert
    Dann antwortet das Archiv auf die Anforderung mit 403 «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag VERWEIGERT von «<benutzer>»
    Und für «<mandant>» ist kein Auftrag «EXPORT_JAHR» eingereiht

    Beispiele:
      | benutzer                       | mandant |
      | mitarbeiter-urinet             | urinet  |
      | geschaeftsstelle-vhf-gsk       | vhf-gsk |
      | mitarbeiter-treuhaender-urinet | urinet  |

  @req-7.5
  Szenario: Einen fertigen Export kann nur abrufen, wer exportieren darf, und nur im eigenen Mandanten
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Dann antwortet der Abruf des Exports so:
      | benutzer                 | status | fehlercode    |
      | mitarbeiter-urinet       | 403    | NICHT_ERLAUBT |
      | geschaeftsstelle-vhf-gsk | 403    | NICHT_ERLAUBT |
      | inhaber-vhf-gsk          | 404    | NICHT_GEFUNDEN |
      | treuhaender-urinet       | 200    |               |

  @req-7.1
  Szenario: Je Mandant läuft höchstens ein Export zugleich; ungültige Jahre lehnt das Archiv ab
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und «treuhaender-urinet» den Jahresexport 2024 anfordert
    Dann antwortet das Archiv auf die Anforderung mit 409 «STATUS_KONFLIKT»
    Wenn «inhaber-urinet» den Jahresexport 1899 anfordert
    Dann antwortet das Archiv auf die Anforderung mit 400 «ANFRAGE_UNGUELTIG»
    Wenn «inhaber-urinet» den Jahresexport mit dem Body «{"geschaeftsjahr":"2025"}» anfordert
    Dann antwortet das Archiv auf die Anforderung mit 400 «ANFRAGE_UNGUELTIG»
    Wenn «inhaber-urinet» den Jahresexport mit dem Body «{"geschaeftsjahr":2025.5}» anfordert
    Dann antwortet das Archiv auf die Anforderung mit 400 «ANFRAGE_UNGUELTIG»
    Wenn «treuhaender-urinet» den Export «keine-uuid» abruft
    Dann antwortet das Archiv auf die Anforderung mit 404 «NICHT_GEFUNDEN»

  @req-7.1
  Szenario: Ein vor Beginn des Geschäftsjahres archivierter Beleg bringt das Manifest seines Archivierungstags mit
    Angenommen im Mandanten «urinet» wurde «B_LOHN» am 2024-12-20 archiviert
    Und im Mandanten «urinet» liegt das Manifest vom 2024-12-20 mit Zeitstempel «sgpki»
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Dann ist der Export «FERTIG» mit 3 Belegen
    Und das ZIP enthält diese Dateien:
      | datei |
      | manifeste/2024-12-20.json |
      | manifeste/2024-12-20.sig |
      | manifeste/2024-12-20.sgpki.tsr |
      | manifeste/2025-03-14.json |
    Und das ZIP enthält keine Datei «manifeste/2024-06-01.json»

  @req-7.1
  Szenario: Das ZIP liegt ohne Sperre unter «exporte/», und der Bucket entfernt es nach 7 Tagen
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Dann liegt das ZIP ohne Sperre unter «exporte/<id>.zip» im Bucket von «urinet»
    Und der Bucket von «urinet» entfernt alles unter «exporte/» nach 7 Tagen
