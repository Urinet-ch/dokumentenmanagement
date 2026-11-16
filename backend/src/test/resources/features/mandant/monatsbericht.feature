# language: de
@spec-03 @mandant-bericht
Funktionalität: Monatsbericht pro Mandant (Aufgabe 12.1)
  Der Bericht enthält nur Kennzahlen aus dem jeweiligen Mandantenschema und wird je Mandant und Monat einmal
  gespeichert und versandt.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-8.1
  Szenario: Die Berichte zweier Mandanten bleiben getrennt
    Angenommen die synthetischen Monatsbericht-Daten für zwei Mandanten sind vorbereitet
    Wenn der Monatsbericht für beide Mandanten zweimal verarbeitet wird
    Dann enthält der Bericht je Mandant nur dessen eigene Dokumente, Speicherwerte und Kennzahlen

  @req-8.1
  Szenario: Die Monatsgrenze trennt die Zählung
    Angenommen die synthetischen Monatsbericht-Daten für zwei Mandanten sind vorbereitet
    Wenn der Monatsbericht für beide Mandanten zweimal verarbeitet wird
    Dann zählen KI-Tokens und Chat-Fragen nur Einträge innerhalb des Monats

  @req-8.1
  Szenario: Doppelte Verarbeitung speichert und versendet je Mandant nur einmal
    Angenommen die synthetischen Monatsbericht-Daten für zwei Mandanten sind vorbereitet
    Wenn der Monatsbericht für beide Mandanten zweimal verarbeitet wird
    Dann ist je Mandant genau ein Bericht gespeichert und genau eine Mail an dessen Empfänger versandt

  @req-8.1
  Szenario: Weder Bericht noch Mail enthalten Inhalte
    Angenommen die synthetischen Monatsbericht-Daten für zwei Mandanten sind vorbereitet
    Wenn der Monatsbericht für beide Mandanten zweimal verarbeitet wird
    Dann enthalten gespeicherter Bericht und Mail nur Zahlen und keine Inhalte

  @req-8.1
  Szenario: Die Planung überspringt deaktivierte Mandanten
    Angenommen der Mandant «vhf-gsk» ist deaktiviert
    Wenn die Monatsberichte des Vormonats geplant werden
    Dann ist nur für den aktiven Mandanten «urinet» ein Berichtsauftrag eingereiht
