# language: de
@spec-03 @mandant-bericht
Funktionalität: Monatsbericht pro Mandant (Aufgabe 12.1)
  Der Bericht enthält nur Kennzahlen aus dem jeweiligen Mandantenschema und wird je Mandant und Monat einmal
  gespeichert und versandt.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-8.1
  Szenario: Monatsberichte bleiben getrennt, idempotent und frei von Inhalten
    Angenommen die synthetischen Monatsbericht-Daten für zwei Mandanten sind vorbereitet
    Wenn der Monatsbericht für beide Mandanten zweimal verarbeitet wird
    Dann sind die Berichte getrennt, idempotent gespeichert und die Mails enthalten nur Zahlen
