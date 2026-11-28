# language: de
@spec-09
Funktionalität: Dateinamen-Regeln für Produktivimporte

  @req-9.1 @req-9.2
  Szenario: Die erste passende Regel ergänzt den Importvorschlag mit Sicherheit eins
    Angenommen für den Import gelten geordnete synthetische Dateinamen-Regeln
    Wenn das Archiv den Dateinamen «syn-2026-003_Muster_Büro-AG.pdf» auswertet
    Dann übernimmt es Belegnummer «2026-003» und Partner «Muster Büro AG» aus der ersten passenden Regel

  @req-9.3
  Szenario: Eine Belegnummer ausserhalb des OCR-Volltexts muss geprüft werden
    Angenommen eine Dateinamen-Regel erkennt die synthetische Belegnummer «2026-003»
    Wenn der erkannte Dateiname mit dem OCR-Volltext «Synthetischer Beleg ohne Nummer» abgeglichen wird
    Dann ist die Belegnummer im Vorschlag als «bitte prüfen» markiert

  @req-9.4
  Szenario: Ein ungültiges Dateinamen-Muster macht die Konfiguration ungültig
    Angenommen die Konfiguration enthält ein ungültiges Dateinamen-Muster
    Dann weist die Konfigurationsprüfung das Muster als ungültig zurück

  @req-9.5
  Szenario: Der Generator-Nachlauf meldet belegte Nummern als vorhanden und ordnet neue Dokumente einem Lauf zu
    Angenommen der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Und im Mandanten «urinet» gibt es den CRM-Kontakt «Probe AG» mit der crmId «K-PROBE-4711»
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnung", "datum": "2026-03-10",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-910", "richtung": "EINNAHME", "betrag": "100.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1»
    Angenommen es gibt einen offenen Generator-Quelllauf
    Und der Nachlaufordner enthält die PDF «nachlauf-neu» mit diesen Metadaten:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Neue Probe-Rechnung", "datum": "2026-03-11",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-911", "richtung": "EINNAHME", "betrag": "200.00", "waehrung": "CHF"} }
      """
    Und der Nachlaufordner enthält die PDF «nachlauf-konflikt» mit diesen Metadaten:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Bereits belegte Probe-Rechnung", "datum": "2026-03-12",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-910", "richtung": "EINNAHME", "betrag": "300.00", "waehrung": "CHF"} }
      """
    Wenn der Python-Nachlauf den Ausgabeordner mit dem offenen Quelllauf sendet
    Dann meldet der Nachlauf 1 neue und 1 schon vorhandene Dokumente
    Und bleibt die PDF «nachlauf-konflikt» im Nachlaufordner und nicht in archiv-konflikt
    Und ist die neue Nachlauf-PDF dem offenen Generator-Quelllauf zugeordnet

  @req-9.6
  Szenario: Der Nachlauf-Trockenlauf fragt nur Prüfsummen ab
    Angenommen der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Und das Skript hat ein API-Token vom Scope «import»
    Und der Nachlaufordner enthält die synthetische PDF «nachlauf-trocken»
    Wenn der Python-Nachlauf den Ordner im Trockenlauf prüft
    Dann meldet der Trockenlauf die Datei als «WIRD_GESENDET» ohne Upload
