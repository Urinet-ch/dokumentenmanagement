# language: de
@spec-05
Funktionalität: Isolation der Mandantenkonfiguration

  @req-9.3
  Szenario: Konfigurationsdateien enthalten nur die Arten des angemeldeten Mandanten
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die Art «AUSGANGSRECHNUNG»
    Und enthält der Export die Art «PROBE_VHF_BULLETIN» nicht
    Und enthält der Export die Art «WISSENSQUELLE»
    Wenn «inhaber-vhf-gsk» die Konfigurationsdatei exportiert
    Dann enthält der Export die Art «PROBE_VHF_BULLETIN»
    Und enthält der Export die Art «AUSGANGSRECHNUNG» nicht
    Und enthält der Export die Art «WISSENSQUELLE»
