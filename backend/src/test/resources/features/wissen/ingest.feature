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
