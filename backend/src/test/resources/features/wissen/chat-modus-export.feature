# language: de
@spec-04
Funktionalität: Der Bewertungsexport enthält den Gesprächsmodus
  @req-21.4
  Szenario: Beide Gesprächsmodi werden im Export ausgewiesen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 4 Sternen im Modus «ARCHIV»
    Und im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 3 Sternen im Modus «WISSEN»
    Wenn «inhaber-urinet» exportiert die Chat-Auswertung für die letzten 30 Tage
    Dann ist der Chat-Auswertungs-Export mit Status 200 beantwortet
    Und enthält der Chat-Auswertungs-Export die Modi ARCHIV und WISSEN
    Und umfasst der Wissensverbrauchsindex in «urinet» den Wissenschat
    Und umfasst der Wissensverbrauchsindex in «vhf-gsk» den Wissenschat
