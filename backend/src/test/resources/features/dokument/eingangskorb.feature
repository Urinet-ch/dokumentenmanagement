# language: de
@spec-01
Funktionalität: Eingangskorb – Angaben für die Oberfläche
  Für die Eingangskorb-Oberfläche (Aufgabe 3.6) liefert das Archiv je Dokument den Vorschlag der KI, die Felder unter
  der Schwelle «archiv.ki.schwelle» («bitte prüfen»), den Fehlertext, das Kennzeichen «PDF/A fehlt» und die
  Vorschaubilder der Seiten («GET /api/v1/dokumente/{id}/vorschau»). Nur synthetische Testdaten (Req 16.15).

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-8.1 @req-7.6
  Szenario: Der Eingangskorb zeigt den Vorschlag und markiert unsichere Felder
    Angenommen im Mandanten «urinet» liegt das Dokument «V» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und das Dokument «V» hat den KI-Vorschlag '{"art":"QUITTUNG","titel":"Quittung Test","datum":"2026-03-10","belegnummer":"Q-1","betrag":12.50,"faelligkeit":null}' mit den Sicherheiten '{"art":0.95,"titel":0.8,"datum":0.5,"belegnummer":0.7}'
    Wenn «inhaber-urinet» den Eingangskorb mit Warnungen abruft
    Dann zeigt der Eingangskorb beim Dokument «V» den Vorschlag der Art «QUITTUNG» und «bitte prüfen» bei "belegnummer, betrag, datum"
    Wenn «inhaber-urinet» das Dokument «V» mit Warnungen liest
    Dann zeigt das Dokument «V» den Vorschlag der Art «QUITTUNG» und «bitte prüfen» bei "belegnummer, betrag, datum"

  @req-8.1 @req-10.2
  Szenario: Ausserhalb des Eingangskorbs liefert das Archiv keinen Vorschlag
    Angenommen im Mandanten «urinet» liegt das Dokument «A» im Status «ARCHIVIERUNG», erfasst von «inhaber-urinet»
    Und das Dokument «A» hat den KI-Vorschlag '{"art":"QUITTUNG"}' mit den Sicherheiten '{"art":0.2}'
    Wenn «inhaber-urinet» das Dokument «A» mit Warnungen liest
    Dann zeigt das Dokument «A» weder Vorschlag noch «bitte prüfen»
    Und steht im Zugriffsprotokoll für «A» 1 Mal «ANZEIGEN» von «inhaber-urinet»

  @req-8.1
  Szenario: Eingangskorb und Dokument liefern die gespeicherten Finanz- und Vertragsangaben
    # Die Bestätigung ersetzt finanz und vertrag ganz (3.4); die Oberfläche muss die gespeicherten Werte kennen, etwa bei
    # einem Generator-Dokument ohne Vorschlag, und sendet sie unverändert mit (Review PR #68, M1).
    Angenommen im Mandanten «urinet» liegt das Dokument «G» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und das Dokument «G» hat in dokument_finanz gespeichert:
      | belegnummer | 2026-901        |
      | richtung    | EINNAHME        |
      | betrag      | 1234.5          |
      | waehrung    | CHF             |
      | faelligkeit | 2026-12-31      |
      | qr_referenz | QR-TEST-1       |
      | konto       | 1100-TEST       |
    Und das Dokument «G» hat in dokument_vertrag gespeichert:
      | gueltig_ab       | 2026-01-01 |
      | gueltig_bis      | 2027-12-31 |
      | kuendigungsfrist | 3 Monate   |
      | naechste_frist   | 2027-09-30 |
    Und im Mandanten «urinet» liegt das Dokument «O» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» den Eingangskorb mit Warnungen abruft
    Dann liefert das Archiv beim Dokument «G» diese Angaben:
      | finanz.belegnummer      | 2026-901   |
      | finanz.richtung         | EINNAHME   |
      | finanz.betrag           | 1234.5     |
      | finanz.waehrung         | CHF        |
      | finanz.faelligkeit      | 2026-12-31 |
      | finanz.qrReferenz       | QR-TEST-1  |
      | finanz.konto            | 1100-TEST  |
      | vertrag.gueltigAb       | 2026-01-01 |
      | vertrag.gueltigBis      | 2027-12-31 |
      | vertrag.kuendigungsfrist | 3 Monate  |
      | vertrag.naechsteFrist   | 2027-09-30 |
    Und liefert das Archiv beim Dokument «O» diese Angaben:
      | finanz  | null |
      | vertrag | null |
    Wenn «inhaber-urinet» das Dokument «G» mit Warnungen liest
    Dann liefert das Archiv beim Dokument «G» diese Angaben:
      | finanz.konto            | 1100-TEST  |
      | vertrag.kuendigungsfrist | 3 Monate  |

  @req-8.7
  Szenario: Der Eingangskorb nennt den Fehlertext und das fehlende PDF/A
    Angenommen im Mandanten «urinet» liegt das Dokument «F» im Status «FEHLER», erfasst von «inhaber-urinet»
    Und das Dokument «F» hat den Fehlertext "Verarbeitung fehlgeschlagen (synthetisch)"
    Und im Mandanten «urinet» liegt das Dokument «P» ohne PDF/A im Eingangskorb, erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» den Eingangskorb mit Warnungen abruft
    Dann zeigt der Eingangskorb beim Dokument «F» den Fehlertext "Verarbeitung fehlgeschlagen (synthetisch)", ohne PDF/A nein
    Und zeigt der Eingangskorb beim Dokument «P» den Fehlertext "Keine PDF/A-Fassung möglich", ohne PDF/A ja

  @req-8.1 @req-10.2
  Szenario: Das Vorschaubild einer Seite wird geliefert und protokolliert
    Angenommen im Mandanten «urinet» liegt das Dokument «V» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und für das Dokument «V» liegt ein Vorschaubild der Seite 1
    Wenn «inhaber-urinet» die Vorschau der Seite 1 von «V» abruft
    Dann liefert das Archiv das Vorschaubild von «V» als PNG, nicht im Cache gespeichert
    Und enthält das Zugriffsprotokoll für «V» einen Eintrag «ANZEIGEN» von «inhaber-urinet»

  @req-8.1
  Szenario: Eine Seite ausserhalb des Dokuments gibt es nicht
    Angenommen im Mandanten «urinet» liegt das Dokument «V» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und für das Dokument «V» liegt ein Vorschaubild der Seite 1
    Wenn «inhaber-urinet» die Vorschau der Seite 2 von «V» abruft
    Dann meldet das Archiv Status 404 mit Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-urinet» die Vorschau der Seite 0 von «V» abruft
    Dann meldet das Archiv Status 400 mit Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn «inhaber-urinet» die Vorschau der Seite "abc" von «V» abruft
    Dann meldet das Archiv Status 400 mit Fehlercode «ANFRAGE_UNGUELTIG»

  @req-2.3 @req-1.4
  Szenario: Unsichtbare und fremde Vorschauen gibt es nicht
    Angenommen im Mandanten «urinet» liegt das Dokument «V» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und für das Dokument «V» liegt ein Vorschaubild der Seite 1
    Wenn «mitarbeiter-urinet» die Vorschau der Seite 1 von «V» abruft
    Dann meldet das Archiv Status 404 mit Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» die Vorschau der Seite 1 von «V» abruft
    Dann meldet das Archiv Status 404 mit Fehlercode «NICHT_GEFUNDEN»
