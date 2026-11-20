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

  @req-9.4
  Szenario: Der Probeverband erfasst und archiviert eine Sitzungsunterlage
    Angenommen der synthetische Probeverband ist für die Abnahme bereit
    Wenn der Probeverband eine synthetische Sitzungsunterlage erfasst und bestätigt
    Dann ist die Sitzungsunterlage mit Feldfrist archiviert, in Suche und Lückenbericht enthalten und ihre Nummer eindeutig

  @req-9.3
  Szenario: Auch KI-Texte und vollständige Versionsabbilder bleiben in der API des Mandanten
    Angenommen der synthetische Probeverband ist für die Abnahme bereit
    Dann bleiben Konfiguration, KI-Texte und Versionsabbilder des Probeverbands in seiner API
