# language: de
@spec-01
Funktionalität: Dokument bestätigen und archivieren
  Der Inhaber bestätigt ein Dokument im Eingangskorb (A7). Das Archiv prüft Pflichtfelder, Version, Status und
  Belegnummer, berechnet die Aufbewahrung, setzt ARCHIVIERUNG und reiht ARCHIVIEREN ein; der Worker legt Original und
  PDF/A gesperrt unter archiv/ ab und setzt erst danach ARCHIVIERT (Aufgabe 3.4). Nur synthetische Testdaten
  (Req 16.15).

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-8.2 @req-6.3 @req-10.2
  Szenario: Eine bestätigte Quittung wird archiviert und gesperrt
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG        |
      | titel    | Quittung Papier |
      | datum    | 2026-03-10      |
      | richtung | AUSGABE         |
      | betrag   | 1234.50         |
      | waehrung | CHF             |
    Dann meldet das Archiv Status 202
    Und steht im Zugriffsprotokoll für «Q» 1 Mal «BESTAETIGEN» von «inhaber-urinet»
    Und trägt die Antwort das ETag der neuen Version von «Q»
    Und ist das Dokument «Q» im Status «ARCHIVIERUNG»
    Und ist für das Dokument «Q» ein Auftrag «ARCHIVIEREN» eingereiht
    Und hat das Dokument «Q» diese Werte:
      | art             | QUITTUNG        |
      | titel           | Quittung Papier |
      | geschaeftsjahr  | 2026            |
      | aufbewahren_bis | 2036-12-31      |
      | fehler          | null            |
    Und hat das Dokument «Q» die Belegnummer «null» mit Betrag 1234.50 CHF
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «Q» im Status «ARCHIVIERT»
    Und liegen alle Dateien von «Q» unter archiv/ mit Versions-ID und Sperre bis mindestens heute
    Und ist der Eingang von «Q» leer

  @req-2.22
  Szenario: Ohne Angabe gilt die Standard-Vertraulichkeit der Art
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «Q» diese Werte:
      | vertraulichkeit | INTERN |

  @req-2.22
  Szenario: Die bestätigte Vertraulichkeit ersetzt die der Art
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art             | QUITTUNG   |
      | titel           | Quittung   |
      | datum           | 2026-03-10 |
      | richtung        | AUSGABE    |
      | betrag          | 12.00      |
      | waehrung        | CHF        |
      | vertraulichkeit | NORMAL     |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «Q» diese Werte:
      | vertraulichkeit | NORMAL |

  @req-8.3
  Szenario: Fehlende Pflichtfelder verhindern die Bestätigung und werden genannt
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 422 mit Fehlercode «PFLICHTFELD_FEHLT»
    Und nennt die Antwort die fehlenden Felder "richtung, betrag"
    Und ist das Dokument «Q» im Status «EINGANGSKORB»
    Und ist für das Dokument «Q» kein Auftrag «ARCHIVIEREN» eingereiht

  @req-8.8
  Szenario: Ein Dokument, das nicht mehr im Eingangskorb ist, lässt sich nicht bestätigen
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «ARCHIVIERUNG», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»
    Und ist das Dokument «Q» im Status «ARCHIVIERUNG»
    Und hat das Dokument «Q» diese Werte:
      | art   | null |
      | titel | null |
    Und ist für das Dokument «Q» kein Auftrag «ARCHIVIEREN» eingereiht

  @req-8.8
  Szenario: Ohne If-Match oder mit veralteter Version ändert die Bestätigung nichts
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» ohne If-Match bestätigt
    Dann meldet das Archiv Status 428 mit Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» das Dokument «Q» mit einer veralteten Version bestätigt
    Dann meldet das Archiv Status 412 mit Fehlercode «VERSION_KONFLIKT»
    Und ist das Dokument «Q» im Status «EINGANGSKORB»

  @req-8.9
  Szenario: Eine schon vergebene Belegnummer einer eigenen Art verhindert die Bestätigung
    Angenommen im Mandanten «urinet» gibt es den Kontakt «FC_Probe»
    Und im Mandanten «urinet» liegt das Dokument «ALT» im Status «ARCHIVIERT», erfasst von «inhaber-urinet»
    Und das Dokument «ALT» hat die Art «AUSGANGSRECHNUNG», die Belegnummer «2026-003» und den Partner «FC_Probe»
    Und die Belegnummer «2026-003» der Art «AUSGANGSRECHNUNG» gehört dem Dokument «ALT»
    Und im Mandanten «urinet» liegt das Dokument «NEU» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «NEU» so bestätigt:
      | art         | AUSGANGSRECHNUNG |
      | titel       | Rechnung         |
      | datum       | 2026-03-10       |
      | partner     | FC_Probe         |
      | belegnummer | 2026-003         |
      | richtung    | EINNAHME         |
      | betrag      | 500.00           |
      | waehrung    | CHF              |
    Dann meldet das Archiv Status 409 mit Fehlercode «BELEGNUMMER_VERGEBEN»
    Und ist das Dokument «NEU» im Status «EINGANGSKORB»
    Und ist für das Dokument «NEU» kein Auftrag «ARCHIVIEREN» eingereiht

  @req-8.10
  Szenario: Ein Fremdbeleg mit bekannter Belegnummer zeigt eine Warnung und lässt sich trotzdem bestätigen
    Angenommen im Mandanten «urinet» gibt es den Kontakt «Lieferant_Probe»
    Und im Mandanten «urinet» liegt das Dokument «ALT» im Status «ARCHIVIERT», erfasst von «inhaber-urinet»
    Und das Dokument «ALT» hat die Art «LIEFERANTENBELEG», die Belegnummer «F0003» und den Partner «Lieferant_Probe»
    Und im Mandanten «urinet» liegt das Dokument «NEU» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und das Dokument «NEU» hat die Art «LIEFERANTENBELEG», die Belegnummer «F0003» und den Partner «Lieferant_Probe»
    Wenn «inhaber-urinet» den Eingangskorb mit Warnungen abruft
    Dann zeigt der Eingangskorb beim Dokument «NEU» die Warnung «BELEGNUMMER_BEKANNT» mit Verweis auf «ALT»
    Wenn «inhaber-urinet» das Dokument «NEU» mit Warnungen liest
    Dann zeigt das Dokument «NEU» die Warnung «BELEGNUMMER_BEKANNT» mit Verweis auf «ALT»
    Wenn «inhaber-urinet» das Dokument «ALT» mit Warnungen liest
    Dann zeigt das Dokument «ALT» keine Warnung
    Wenn «inhaber-urinet» das Dokument «NEU» so bestätigt:
      | art         | LIEFERANTENBELEG |
      | titel       | Lieferung        |
      | datum       | 2026-03-10       |
      | partner     | Lieferant_Probe  |
      | belegnummer | F0003            |
      | richtung    | AUSGABE          |
      | betrag      | 80.00            |
      | waehrung    | CHF              |
    Dann meldet das Archiv Status 202
    Und ist das Dokument «NEU» im Status «ARCHIVIERUNG»

  @req-5.9
  Szenario: Ohne PDF/A archiviert der Inhaber das Original allein, aber nur ausdrücklich
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» ohne PDF/A im Eingangskorb, erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 422 mit Fehlercode «PFLICHTFELD_FEHLT»
    Und nennt die Antwort die fehlenden Felder "ohnePdfa"
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
      | ohnePdfa | ja         |
    Dann meldet das Archiv Status 202
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «Q» im Status «ARCHIVIERT»
    Und liegt nur das Original von «Q» unter archiv/
    Und ist der Eingang von «Q» leer

  @req-9.8
  Szenario: Bei abgelaufener Frist archiviert das Archiv erst nach dem Entscheid des Inhabers, dann ohne Sperre
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art            | QUITTUNG   |
      | titel          | Altbestand |
      | datum          | 2010-05-01 |
      | richtung       | AUSGABE    |
      | betrag         | 12.00      |
      | waehrung       | CHF        |
      | geschaeftsjahr | 2010       |
    Dann meldet das Archiv Status 409 mit Fehlercode «FRIST_ABGELAUFEN»
    Und ist das Dokument «Q» im Status «EINGANGSKORB»
    Und hat das Dokument «Q» diese Werte:
      | frist_abgelaufen | true |
    Und ist für das Dokument «Q» kein Auftrag «ARCHIVIEREN» eingereiht
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art                       | QUITTUNG   |
      | titel                     | Altbestand |
      | datum                     | 2010-05-01 |
      | richtung                  | AUSGABE    |
      | betrag                    | 12.00      |
      | waehrung                  | CHF        |
      | geschaeftsjahr            | 2010       |
      | fristAbgelaufenAkzeptiert | ja         |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «Q» diese Werte:
      | aufbewahren_bis  | 2020-12-31 |
      | frist_abgelaufen | true       |
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «Q» im Status «ARCHIVIERT»
    Und liegen die Dateien von «Q» unter archiv/ ohne Sperre

  @req-6.3 @req-6.12
  Szenario: Eine Art ohne gesetzliche Pflicht wird ohne Sperre archiviert
    Angenommen im Mandanten «urinet» gibt es den Kontakt «Partner_Probe»
    Und im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «K» so bestätigt:
      | art     | KORRESPONDENZ |
      | titel   | Anfrage Probe |
      | datum   | 2026-03-10    |
      | partner | Partner_Probe |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «K» diese Werte:
      | aufbewahren_bis  | 2036-12-31 |
      | frist_abgelaufen | false      |
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «K» im Status «ARCHIVIERT»
    Und liegen die Dateien von «K» unter archiv/ ohne Sperre
    Und ist der Eingang von «K» leer

  @req-2.13
  Szenario: mitarbeiter und treuhaender dürfen nicht bestätigen
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Und das Dokument «Q» ist für alle Rollen sichtbar
    Wenn «mitarbeiter-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Wenn «treuhaender-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
    Und ist das Dokument «Q» im Status «EINGANGSKORB»
    Und ist für das Dokument «Q» kein Auftrag «ARCHIVIEREN» eingereiht

  @req-3.14
  Szenariogrundriss: Ein Feld «dateiname» in der Bestätigung antwortet mit 400, das Dokument bleibt im Eingangskorb (<fall>)
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und das Dokument «Q» hat den Dateinamen «Quittung_Probe.pdf»
    Wenn «inhaber-urinet» das Dokument «Q» mit dem Körper '{"art": "QUITTUNG", "titel": "Quittung", "datum": "2026-03-10", "richtung": "AUSGABE", "betrag": "12.00", "waehrung": "CHF", "dateiname": <wert>}' bestätigt
    Dann meldet das Archiv Status 400 mit Fehlercode «ANFRAGE_UNGUELTIG»
    Und ist das Dokument «Q» im Status «EINGANGSKORB»
    Und ist für das Dokument «Q» kein Auftrag «ARCHIVIEREN» eingereiht
    Und hat das Dokument «Q» diese Werte:
      | dateiname | Quittung_Probe.pdf |

    Beispiele:
      | fall       | wert         |
      | neuer Name | "anders.pdf" |
      | null       | null         |

  @req-14.9
  Szenario: Die Antwort vom Bestätigen ist das Dokument mit seinem Dateinamen
    Angenommen im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und das Dokument «Q» hat den Dateinamen «Quittung_Probe.pdf»
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann liefert die Bestätigung beim Dokument «Q» diese Angaben:
      | status    | ARCHIVIERUNG       |
      | titel     | Quittung           |
      | dateiname | Quittung_Probe.pdf |
    Und trägt die Antwort das ETag der neuen Version von «Q»

  @req-17.12
  Szenario: Bestätigen mit einem inaktiven Kontakt als neuem Partner lehnt das Archiv ab
    Angenommen im Mandanten «urinet» gibt es den Kontakt «Partner_Ruhend»
    Und der Kontakt «Partner_Ruhend» im Mandanten «urinet» ist inaktiv
    Und im Mandanten «urinet» liegt das Dokument «K» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «K» so bestätigt:
      | art     | KORRESPONDENZ |
      | titel   | Anfrage Probe |
      | datum   | 2026-03-10    |
      | partner | Partner_Ruhend |
    Dann meldet das Archiv Status 400 mit Fehlercode «ANFRAGE_UNGUELTIG»
    Und ist das Dokument «K» im Status «EINGANGSKORB»
