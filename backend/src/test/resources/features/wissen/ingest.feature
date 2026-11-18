# language: de
@spec-04
Funktionalität: Start eines Ingests einer Wissensquelle
  Der Start prüft Rechte, Status, Art, Seitenbereich, Vorprüfung (5.6) und Budget (13.4, 13.6); der Worker verarbeitet
  anschliessend die vier Ingest-Teilaufträge. Nur synthetische Testdaten (Spec 01, Req 16.15).

  @req-5.2 @req-5.7
  Szenario: Der Inhaber darf nur archivierte Wissensquellen manuell einreihen
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Wenn «inhaber-urinet» den Ingest für «BELEG» startet
    Dann antwortet der Ingest-Endpunkt mit Status 422 und Fehlercode «KEINE_WISSENSQUELLE»

  @req-5.2
  Szenario: Ein Treuhänder ohne Recht A16 kann keinen Ingest starten
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «WISSENSQUELLE»
    Wenn «treuhaender-urinet» den Ingest für «BELEG» startet
    Dann antwortet der Ingest-Endpunkt mit Status 403

  @req-5.3 @req-5.8 @req-13.7
  Szenario: Alle vier Ingest-Teilaufträge erstellen einen Entwurf
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und WireMock liefert gültige Antworten für alle vier Ingest-Schritte
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest-Entwurf wird mit vier KI-Aufrufen «OFFEN»

  @req-5.3 @req-5.8 @req-13.7 @req-13.8
  Szenario: Jeder Ingest-Teilauftrag fragt nach einer ungültigen Antwort einmal nach
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und WireMock verlangt für alle vier Ingest-Schritte einmalig eine Korrektur
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest-Entwurf wird mit acht KI-Aufrufen «OFFEN»

  @req-5.6
  Szenario: Eine Personalunterlage erreicht die KI nicht
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «PERSONAL» der Art «WISSENSQUELLE»
    Und das Dokument «PERSONAL» hat Seitentext und eine PDF/A-Fassung
    Und das Dokument «PERSONAL» hat Vertraulichkeit «PERSONAL»
    Und WireMock ist zurückgesetzt
    Wenn «inhaber-urinet» den Ingest für «PERSONAL» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest wird im Status «FEHLER» ohne KI-Aufruf abgeschlossen

  @req-5.6 @req-13.6
  Szenario: Bei ausgeschöpftem Monatsbudget wird kein Ingest-Aufruf ausgeführt
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
    Und WireMock ist zurückgesetzt
    Und das Monatsbudget der Wissensbasis ist ausgeschöpft
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 429 und Fehlercode «BUDGET»
    Und es erfolgt kein KI-Aufruf

  @req-13.5
  Szenario: Beim Erreichen des Ingest-Budgets bleibt ein unvollständiger Entwurf
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 600000 und das Monatsbudget 100000000 Tokens
    Und WireMock liefert eine gültige Kernaussage beim Erreichen des Ingest-Budgets
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest-Entwurf ist mit einem KI-Aufruf «OFFEN» und unvollständig

  @req-5.3 @req-5.8 @req-13.8
  Szenario: Eine Prompt-Injection im Dokumenttext ändert weder Regeln noch Freigabe
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und das Dokument «QUELLE» enthält eine synthetische Prompt-Injection
    Und WireMock liefert einen nicht belegten Vorschlag auf die Prompt-Injection
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest-Entwurf wird mit vier KI-Aufrufen «OFFEN»
    Und mindestens eine Entwurfsänderung hat einen Befund
    Und die KI erhält die Schutzregel und die Anweisung nur als Dokumenttext

  @req-5.13 @req-13.7
  Szenario: Ein zweiter Ingest wartet auf den Abschluss der ersten Wissensquelle
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE1» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE1» hat Seitentext und eine PDF/A-Fassung
    Und im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE2» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE2» hat Seitentext und eine PDF/A-Fassung
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und WireMock liefert Antworten für zwei Wissensquellen nacheinander
    Wenn «inhaber-urinet» den Ingest für «QUELLE1» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Wenn «inhaber-urinet» den Ingest für «QUELLE2» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der zweite Ingest bleibt zunächst «WARTEND»
    Und der erste Ingest wird «OFFEN» und der zweite bleibt «WARTEND»
  @req-5.2
  Szenario: Ein Mitarbeiter ohne Recht A16 erhält 403 für eine sichtbare und 404 für eine unsichtbare Quelle
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «OFFEN» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Und im Mandanten «urinet» liegt die archivierte Wissensquelle «GEHEIM» mit Vertraulichkeit «INTERN» und 2 Seiten
    Wenn «mitarbeiter-urinet» den Ingest für «OFFEN» startet
    Dann antwortet der Ingest-Endpunkt mit Status 403
    Wenn «mitarbeiter-urinet» den Ingest für «GEHEIM» startet
    Dann antwortet der Ingest-Endpunkt mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-5.2
  Szenario: Ein Dokument eines anderen Mandanten oder ein unbekanntes Dokument ergibt 404
    Angenommen im Mandanten «vhf-gsk» liegt die archivierte Wissensquelle «FREMD» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Wenn «inhaber-urinet» den Ingest für «FREMD» startet
    Dann antwortet der Ingest-Endpunkt mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und hat «FREMD» keinen neuen Ingest-Entwurf
    Wenn «inhaber-urinet» den Ingest für ein unbekanntes Dokument startet
    Dann antwortet der Ingest-Endpunkt mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-5.2
  Szenario: Eine nicht archivierte Wissensquelle lässt sich nicht einreihen
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Und die Wissensquelle «QUELLE» liegt im Status «ARCHIVIERUNG_FEHLER»
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 409 und Fehlercode «STATUS_KONFLIKT»
    Und hat «QUELLE» keinen neuen Ingest-Entwurf

  @req-5.2
  Szenario: Der Status geht dem Seitenbereich vor
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 3 Seiten
    Und die Wissensquelle «QUELLE» liegt im Status «ARCHIVIERUNG_FEHLER»
    Wenn «inhaber-urinet» den Ingest für «QUELLE» mit den Seiten 3 bis 1 startet
    Dann antwortet der Ingest-Endpunkt mit Status 409 und Fehlercode «STATUS_KONFLIKT»

  @req-5.2
  Szenario: Ein umgekehrter Seitenbereich ist ungültig
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 3 Seiten
    Wenn «inhaber-urinet» den Ingest für «QUELLE» mit den Seiten 3 bis 1 startet
    Dann antwortet der Ingest-Endpunkt mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und hat «QUELLE» keinen neuen Ingest-Entwurf

  @req-5.2 @req-5.8
  Szenario: Ein zulässiger Start reiht einen Ingest-Auftrag ein und wird protokolliert
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 3 Seiten
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Wenn «inhaber-urinet» den Ingest für «QUELLE» mit den Seiten 1 bis 2 startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und hat «QUELLE» genau einen Ingest-Entwurf im Status «IN_ARBEIT» mit dem Hinweis der Antwort
    Und ist für «QUELLE» ein Auftrag «WISSEN_INGEST» eingereiht
    Und hält das Zugriffsprotokoll den Ingest-Start von «inhaber-urinet» für «QUELLE» fest
    Und sind Hinweis und Version von «QUELLE» unverändert

  @req-5.2
  Szenario: Die Geschäftsstelle startet den Ingest einer Wissensquelle ihres Mandanten
    Angenommen im Mandanten «vhf-gsk» liegt die archivierte Wissensquelle «MERKBLATT» mit Vertraulichkeit «INTERN» und 2 Seiten
    Und die Wissensbasis von «vhf-gsk» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Wenn «geschaeftsstelle-vhf-gsk» den Ingest für «MERKBLATT» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und hat «MERKBLATT» genau einen Ingest-Entwurf im Status «IN_ARBEIT» mit dem Hinweis der Antwort
    Und ist für «MERKBLATT» ein Auftrag «WISSEN_INGEST» eingereiht
    Und hält das Zugriffsprotokoll den Ingest-Start von «geschaeftsstelle-vhf-gsk» für «MERKBLATT» fest

  @req-5.10
  Szenario: Ein zweiter Start für eine Quelle mit wartendem Ingest wird abgelehnt
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Und für «QUELLE» wartet bereits ein Ingest
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 409 und Fehlercode «STATUS_KONFLIKT»
    Und hat «QUELLE» keinen neuen Ingest-Entwurf

  @req-5.6 @req-1.4
  Szenario: Eine Wissensquelle mit Vertraulichkeit PERSONAL wird ohne KI-Aufruf mit Hinweis abgeschlossen
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «LOHN» mit Vertraulichkeit «PERSONAL» und 2 Seiten
    Wenn «inhaber-urinet» den Ingest für «LOHN» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202, Entwurfsstatus «FEHLER» und dem Hinweis "nicht in die Wissensbasis übernommen – Vertraulichkeit PERSONAL"
    Und hat «LOHN» genau einen Ingest-Entwurf im Status «FEHLER» mit dem Hinweis der Antwort
    Und ist für «LOHN» kein Auftrag «WISSEN_INGEST» eingereiht
    Und sind Hinweis und Version von «LOHN» unverändert

  @req-13.4
  Szenario: Mit den Startwerten ist die Schätzung über dem Budget und es gibt keinen KI-Aufruf
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 300000 und das Monatsbudget 100000000 Tokens
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202, Entwurfsstatus «FEHLER» und einem Hinweis, der mit "Die Unterlage ist für einen Durchgang zu gross (geschätzt " beginnt
    Und hat «QUELLE» genau einen Ingest-Entwurf im Status «FEHLER» mit dem Hinweis der Antwort
    Und ist für «QUELLE» kein Auftrag «WISSEN_INGEST» eingereiht

  @req-13.6
  Szenario: Reicht das Monatsbudget nicht mehr, lehnt das Archiv den manuellen Start mit 429 ab
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «QUELLE» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 1000000 Tokens
    Und die Wissensbasis von «urinet» hat diesen Monat 900000 Tokens verbraucht
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 429 und Fehlercode «BUDGET»
    Und hat «QUELLE» keinen neuen Ingest-Entwurf

  @req-5.1 @req-5.6
  Szenario: Die Archivierung einer Wissensquelle ohne Volltext legt den Entwurf mit Hinweis an, andere Arten keinen
    Angenommen die Archivierung ist bereit
    Und im Mandanten «urinet» liegt das Dokument «W» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Und im Mandanten «urinet» liegt das Dokument «Q» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «W» so bestätigt:
      | art   | WISSENSQUELLE      |
      | titel | Probe-Merkblatt    |
      | datum | 2026-03-10         |
    Dann meldet das Archiv Status 202
    Wenn «inhaber-urinet» das Dokument «Q» so bestätigt:
      | art      | QUITTUNG       |
      | titel    | Probe-Quittung |
      | datum    | 2026-03-10     |
      | richtung | AUSGABE        |
      | betrag   | 45.00          |
      | waehrung | CHF            |
    Dann meldet das Archiv Status 202
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «W» im Status «ARCHIVIERT»
    Und ist das Dokument «Q» im Status «ARCHIVIERT»
    Und hat das Dokument «W» einen Ingest-Entwurf im Status «FEHLER» mit dem Hinweis "nicht in die Wissensbasis übernommen – kein Volltext"
    Und hat das Dokument «Q» keinen Ingest-Entwurf
