# language: de
@spec-04
Funktionalität: Manueller Ingest einer Wissensquelle

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
    Und WireMock liefert gültige Antworten für alle vier Ingest-Schritte
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest-Entwurf wird mit vier KI-Aufrufen «OFFEN»

  @req-5.3 @req-5.8 @req-13.7 @req-13.8
  Szenario: Jeder Ingest-Teilauftrag fragt nach einer ungültigen Antwort einmal nach
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
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
    Und WireMock liefert eine gültige Kernaussage beim Erreichen des Ingest-Budgets
    Wenn «inhaber-urinet» den Ingest für «QUELLE» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der Ingest-Entwurf ist mit einem KI-Aufruf «OFFEN» und unvollständig

  @req-5.3 @req-5.8 @req-13.8
  Szenario: Eine Prompt-Injection im Dokumenttext ändert weder Regeln noch Freigabe
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «QUELLE» der Art «WISSENSQUELLE»
    Und das Dokument «QUELLE» hat Seitentext und eine PDF/A-Fassung
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
    Und WireMock liefert Antworten für zwei Wissensquellen nacheinander
    Wenn «inhaber-urinet» den Ingest für «QUELLE1» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Wenn «inhaber-urinet» den Ingest für «QUELLE2» startet
    Dann antwortet der Ingest-Endpunkt mit Status 202
    Und der zweite Ingest bleibt zunächst «WARTEND»
    Und beide Ingest-Entwürfe werden nacheinander «OFFEN»
