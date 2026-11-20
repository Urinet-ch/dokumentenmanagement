# language: de
@spec-05
Funktionalität: KI-Vorschlag aus der Mandantenkonfiguration
  Hintergrund:
    Angenommen die Verarbeitung ist bereit
    Und das Sprachmodell ist bereit

  @req-2.5 @req-8.1 @req-8.2
  Szenario: Der freigegebene Mandant verwendet seine KI-Texte und Arten
    Angenommen ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-123»
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-123» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 1-mal aufgerufen
    Und der Aufruf verlangt Modell «google/gemma-4-31B-it» mit Temperatur 0 und einem strikten JSON-Schema
    Und der Aufruf verwendet die konfigurierten KI-Texte und Arten des Mandanten
    Und das Dokument ist «EINGANGSKORB» mit der Art «LIEFERANTENBELEG» und der Vertraulichkeit «INTERN»

  @req-8.1 @req-8.2
  Szenario: Geänderte KI-Texte der Konfiguration gelangen in den Aufruf
    Angenommen die KI-Texte des Mandanten tragen die Kennung «KENNUNG-AUS-KONFIG»
    Und ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-125»
    Und das Sprachmodell schlägt «LIEFERANTENBELEG» mit Belegnummer «2026-125» und Vertraulichkeit «INTERN» vor
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf enthält die Kennung «KENNUNG-AUS-KONFIG» der KI-Texte

  @req-8.4
  Szenario: Ohne KI-Freigabe wird kein Vorschlag erzeugt
    Angenommen die KI-Freigabe des Mandanten ist ausgeschaltet
    Und ein Beleg als digitales PDF mit «Muster Buerobedarf AG» und «Rechnung 2026-124»
    Wenn der Worker die Aufträge bearbeitet
    Dann wurde das Sprachmodell 0-mal aufgerufen
    Und das Dokument ist «EINGANGSKORB» ohne Vorschlag
