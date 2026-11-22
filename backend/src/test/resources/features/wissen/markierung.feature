# language: de
@spec-04 @wissensquelle-markierung
Funktionalität: Archivierte Dokumente als Wissensquellen markieren

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-20.2 @req-20.4
  Szenario: Eine Quittung wird markiert und unverändert zum Ingest eingereiht
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Und die Wissensbasis von «urinet» hat das Ingest-Budget 2000000 und das Monatsbudget 100000000 Tokens
    Und der Zustand von «BELEG» mit Dokumentzeile und Dateizeilen ist festgehalten
    Wenn «inhaber-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 202
    Und ist «BELEG» als Wissensquelle markiert
    Und ist für «BELEG» ein Auftrag «WISSEN_INGEST» eingereiht
    Und sind Dokumentzeile und Dateizeilen von «BELEG» unverändert
    Und protokolliert das Archiv «WISSENSQUELLE_MARKIERT» für «BELEG» ohne Titel

  @req-20.3
  Szenario: Ein nicht archiviertes Dokument kann nicht markiert werden
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Und die Wissensquelle «BELEG» liegt im Status «EINGANGSKORB»
    Wenn «inhaber-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 422 und Grund «NICHT_ARCHIVIERT»
    Und ist «BELEG» als Wissensquelle unmarkiert

  @req-20.3
  Szenario: Ein personales Dokument kann nicht markiert werden
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Und das Dokument «BELEG» hat Vertraulichkeit «PERSONAL»
    Wenn «inhaber-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 422 und Grund «PERSONAL»
    Und ist «BELEG» als Wissensquelle unmarkiert

  @req-20.3
  Szenario: Ohne Volltext kann ein Dokument nicht markiert werden
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Und dem Dokument «BELEG» fehlt der Volltext
    Wenn «inhaber-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 422 und Grund «KEIN_VOLLTEXT»
    Und ist «BELEG» als Wissensquelle unmarkiert

  @req-20.3
  Szenario: Ohne PDF/A kann ein Dokument nicht markiert werden
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Und dem Dokument «BELEG» fehlt die PDF/A-Fassung
    Wenn «inhaber-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 422 und Grund «KEINE_PDFA»
    Und ist «BELEG» als Wissensquelle unmarkiert

  @req-20.3
  Szenario: Sichtbare Dokumente verlangen das Recht A16
    Angenommen im Mandanten «urinet» liegt ein archiviertes Dokument «BELEG» der Art «QUITTUNG»
    Wenn «treuhaender-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 403
    Und ist «BELEG» als Wissensquelle unmarkiert

  @req-20.3
  Szenario: Ein Dokument eines anderen Mandanten bleibt unbekannt
    Angenommen im Mandanten «vhf-gsk» liegt ein archiviertes Dokument «BELEG» der Art «PROBE_VHF_BULLETIN»
    Wenn «inhaber-urinet» markiert «BELEG» als Wissensquelle
    Dann antwortet die Markierung mit Status 404
    Und ist «BELEG» als Wissensquelle unmarkiert

  @req-20.5
  Szenario: Eine Markierung wird mit Protokoll beendet
    Angenommen im Mandanten «urinet» liegt die archivierte Wissensquelle «BELEG» mit Vertraulichkeit «NORMAL» und 2 Seiten
    Wenn «inhaber-urinet» entfernt die Wissensquellenmarkierung von «BELEG»
    Dann antwortet die Markierung mit Status 204
    Und ist «BELEG» als Wissensquelle unmarkiert
    Und protokolliert das Archiv «WISSENSQUELLE_ENTFERNT» für «BELEG» ohne Titel
