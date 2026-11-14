# language: de
@spec-01
Funktionalität: KI-Vorschlag der Metadaten (KI_VORSCHLAG)
  Nach der Verarbeitung ruft der Worker das Sprachmodell einmal mit Belegtext bzw. Seitenbild, Artenliste und Swiss QR
  Code auf, verlangt ein festes JSON-Schema und legt das Dokument mit Vorschlag in den Eingangskorb (Aufgabe 3.2).
  Das Sprachmodell ist ein WireMock (Req 16.7, 16.21), alle Belege, Kennungen und Antworten sind synthetisch (Req 16.15).

  Hintergrund:
    Angenommen die Verarbeitung ist bereit
    Und das Sprachmodell ist bereit

  @req-7.1 @req-7.2 @req-7.8
  Szenario: Ein digitales PDF erhält einen Vorschlag nach festem Schema
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-123»
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-123» und Vertraulichkeit «NORMAL» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und der Aufruf verlangt Modell «google/gemma-4-31B-it» mit Temperatur 0 und einem strikten JSON-Schema
    Und der Aufruf enthält den Belegtext «Muster Buerobedarf AG» und die Dokumentarten des Mandanten
    Und das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»
    Und der Vorschlag enthält Titel, Belegdatum, Partner, Belegnummer, Richtung, Betrag, Währung, Fälligkeit und Tags
    Und jede Sicherheit des Vorschlags liegt zwischen 0 und 1
    Und «ki_verbrauch» enthält 1 Zeile «ERFASSUNG» mit 1200 Tokens ein und 80 Tokens aus

  @req-7.1
  Szenario: Ein Foto geht als Seitenbild an das Sprachmodell
    Angenommen ein Beleg als Foto mit «Quittung Kiosk»
    Und das Sprachmodell schlägt «QUITTUNG» mit Belegnummer «» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und der Aufruf enthält ein Seitenbild als JPEG
    Und das Dokument ist «EINGANGSKORB» mit der Art «QUITTUNG» und der Vertraulichkeit «INTERN»

  @req-7.3 @req-7.8
  Szenario: Nach einer Antwort ausserhalb des Schemas fragt das Archiv einmal nach
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-124»
    Und das Sprachmodell antwortet zuerst ausserhalb des Schemas und danach mit «LIEFERANTENBELEG»
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 2-mal aufgerufen
    Und der zweite Aufruf nennt den Fehlerhinweis «Schemafehler»
    Und das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»
    Und «ki_verbrauch» enthält 2 Zeilen «ERFASSUNG» mit 1200 Tokens ein und 80 Tokens aus

  @req-7.3
  Szenario: Bleibt auch die Nachfrage ausserhalb des Schemas, geht das Dokument ohne Vorschlag in den Eingangskorb
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-125»
    Und das Sprachmodell antwortet immer ausserhalb des Schemas
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 2-mal aufgerufen
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag
    Und der Auftrag «KI_VORSCHLAG» des Belegs ist «FERTIG»

  @req-7.3
  Szenario: Eine Antwort ohne Pflichtfelder des Schemas löst die Nachfrage aus
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-127»
    Und das Sprachmodell antwortet immer mit «{}»
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 2-mal aufgerufen
    Und der zweite Aufruf nennt den Fehlerhinweis «Schemafehler»
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag
    Und der Auftrag «KI_VORSCHLAG» des Belegs ist «FERTIG»

  @req-7.3
  Szenario: Eine Antwort ohne Nachricht gilt wie eine ungültige
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-128»
    Und das Sprachmodell antwortet immer ohne Nachricht
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 2-mal aufgerufen
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag
    Und der Auftrag «KI_VORSCHLAG» des Belegs ist «FERTIG»

  @req-7.3 @req-7.8 @req-5.4
  Szenario: Fällt das Sprachmodell aus, wiederholt der Worker den Auftrag und legt das Dokument danach ohne Vorschlag in den Eingangskorb
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-126»
    Und das Sprachmodell antwortet mit HTTP 500
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und ist der Auftrag «KI_VORSCHLAG» nach 1 Versuch «OFFEN»
    Und das Dokument ist «VERARBEITUNG» ohne Vorschlag
    Wenn der Auftrag «KI_VORSCHLAG» zweimal wieder fällig wird und der Worker ihn bearbeitet
    Dann wurde das Sprachmodell 3-mal aufgerufen
    Und ist der Auftrag «KI_VORSCHLAG» nach 3 Versuchen «FEHLER»
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag
    Und «ki_verbrauch» enthält 3 Zeilen «ERFASSUNG» mit 0 Tokens ein und 0 Tokens aus

  @req-7.1 @req-5.9
  Szenario: Ein Dokument ohne PDF/A im Eingangskorb erhält einen Vorschlag nur aus dem Text
    Angenommen ein Beleg als verschlüsseltes PDF
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-129» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und der Aufruf enthält kein Seitenbild
    Und das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»

  @req-7.1 @req-5.9
  Szenariogrundriss: Wird das Dokument während des Aufrufs <aenderung>, speichert der Worker keinen Vorschlag
    Angenommen ein Beleg als verschlüsseltes PDF
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-130» und Vertraulichkeit «INTERN» erst nach 3 Sekunden vor
    Wenn der Worker die Aufträge bearbeitet und das Dokument während des Aufrufs <aenderung> wird
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag und in der Version des Benutzers
    Und der Auftrag «KI_VORSCHLAG» des Belegs ist «FERTIG»

    Beispiele:
      | aenderung |
      | geändert  |
      | bestätigt |

  @req-7.1 @req-5.9 @req-10.4
  Szenario: Ändert der Benutzer ein Dokument ohne PDF/A zwischen zwei Versuchen, speichert der Worker keinen Vorschlag
    Angenommen ein Beleg als verschlüsseltes PDF
    Und das Sprachmodell antwortet mit HTTP 500
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und ist der Auftrag «KI_VORSCHLAG» nach 1 Versuch «OFFEN»
    Und der Auftrag «KI_VORSCHLAG» trägt die Version des Dokuments nach der Verarbeitung
    Wenn «inhaber-urinet» den Titel des Dokuments auf «Titel des Benutzers» ändert
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-131» und Vertraulichkeit «INTERN» vor
    Und der Auftrag «KI_VORSCHLAG» wieder fällig wird und der Worker ihn bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag und in der Version des Benutzers
    Und das Dokument hat den Titel «Titel des Benutzers»
    Und der Auftrag «KI_VORSCHLAG» des Belegs ist «FERTIG»

  @req-7.1
  Szenario: Ein Auftrag ohne Version in der Nutzlast läuft wie bisher
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-132»
    Und das Sprachmodell antwortet mit HTTP 500
    Wenn der Worker die Aufträge bearbeitet
    Dann ist der Auftrag «KI_VORSCHLAG» nach 1 Versuch «OFFEN»
    Angenommen die Nutzlast des Auftrags «KI_VORSCHLAG» enthält keine Version
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-132» und Vertraulichkeit «INTERN» vor
    Wenn der Auftrag «KI_VORSCHLAG» wieder fällig wird und der Worker ihn bearbeitet
    Dann wurde das Sprachmodell 2-mal aufgerufen
    Und das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»
    Und der Auftrag «KI_VORSCHLAG» des Belegs ist «FERTIG»

  @req-7.3 @req-5.4 @req-5.9
  Szenario: Nach dem letzten Fehlversuch bleibt ein Dokument ohne PDF/A im Eingangskorb unverändert
    Angenommen ein Beleg als verschlüsseltes PDF
    Und das Sprachmodell antwortet mit HTTP 500
    Wenn der Worker die Aufträge bearbeitet
    Dann der Auftrag «KI_VORSCHLAG» trägt die Version des Dokuments nach der Verarbeitung
    Wenn der Auftrag «KI_VORSCHLAG» zweimal wieder fällig wird und der Worker ihn bearbeitet
    Dann wurde das Sprachmodell 3-mal aufgerufen
    Und ist der Auftrag «KI_VORSCHLAG» nach 3 Versuchen «FEHLER»
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag und in der Version nach der Verarbeitung

  @req-7.1 @req-7.4
  Szenario: Ein schon verknüpfter Partner wird vom Abgleich nicht überschrieben
    Angenommen im Mandanten gibt es den Kontakt «Lieferant GmbH» mit der IBAN «CH4431999123000889012»
    Und ein Beleg als gescanntes PDF mit «Lieferant GmbH» und einem Swiss QR Code über CHF 80.00
    Und der Beleg ist schon mit dem Kontakt «Anderer Partner AG» ohne IBAN verknüpft
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «F-79» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»
    Und das Dokument bleibt mit dem Kontakt «Anderer Partner AG» verknüpft, der Abgleich fand den Kontakt mit der IBAN

  @req-7.1 @req-7.4
  Szenario: Ein eindeutiger Kontakt zur IBAN aus dem Swiss QR Code wird als Partner verknüpft
    Angenommen im Mandanten gibt es den Kontakt «Lieferant GmbH» mit der IBAN «CH4431999123000889012»
    Und ein Beleg als gescanntes PDF mit «Lieferant GmbH» und einem Swiss QR Code über CHF 80.00
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «F-78» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»
    Und das Dokument ist mit dem Kontakt verknüpft und heisst dort «Lieferant GmbH»

  @req-7.11
  Szenario: Die Werte aus dem Swiss QR Code gehen vor den Werten der KI
    Angenommen ein Beleg als gescanntes PDF mit «Lieferant GmbH» und einem Swiss QR Code über CHF 250.50
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «F-77» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und der Aufruf enthält die IBAN «CH4431999123000889012» aus dem Swiss QR Code
    Und der Vorschlag hat Betrag 250.50, Währung «CHF», IBAN «CH4431999123000889012» und Partner «Lieferant GmbH»
    Und die Sicherheit von «betrag» ist 1

  @req-7.5
  Szenario: Eine eigene Ausgangsrechnung wird am Belegnummer-Muster erkannt
    Angenommen der Mandant «urinet» hat die UID «CHE-100.200.300»
    Und ein Beleg als digitales PDF mit «UriNet Aschwanden CHE-100.200.300» und «Rechnung 2026-003»
    Und das Sprachmodell schlägt «KORRESPONDENZ» mit Belegnummer «2026-003» und Vertraulichkeit «NORMAL» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann das Dokument ist «EINGANGSKORB» mit der Art «AUSGANGSRECHNUNG» und der Vertraulichkeit «INTERN»
    Und die Sicherheit von «art» ist 1

  @req-7.12
  Szenario: Ein Fremdbeleg mit einer Nummer im Muster des Mandanten bleibt Lieferantenbeleg
    Angenommen der Mandant «urinet» hat die UID «CHE-100.200.300»
    Und ein Beleg als digitales PDF mit «Muster Buerobedarf AG CHE-999.888.777» und «Rechnung 2026-123»
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-123» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»
    Und die Sicherheit von «belegnummer» ist höchstens 0.7
