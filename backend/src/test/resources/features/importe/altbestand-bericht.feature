# language: de
@spec-03
Funktionalität: Serverseitiger Lückenbericht des Altbestands
  Die Lücken werden mit den Folgenregeln des Mandanten nach Reihe und Geschäftsjahr berechnet.

  @req-4.2
  Szenario: Fehlende Ausgabenummern werden je Reihe und Jahr ausgewiesen
    Wenn der serverseitige Lückenbericht für die Geschäftsjahre «2025» und «2026» berechnet wird
    Dann zeigt der Lückenbericht diese fehlenden Ausgaben:
      | jahr | reihe     | fehlend |
      | 2025 | BULLETIN  | 3       |
      | 2026 | BULLETIN  | 2       |
      | 2025 | MERKBLATT | 2       |
      | 2026 | MERKBLATT | 1, 3    |
