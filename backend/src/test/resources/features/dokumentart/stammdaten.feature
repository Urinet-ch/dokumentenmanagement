# language: de
@spec-01
Funktionalität: Stammdaten der Dokumentarten
  Die Werte der 11 Dokumentarten hat Fabian am 10.11.2026 entschieden (Aufgabe 1.7). Massgeblich ist der Seed
  «seed/urinet/dokumentarten.json»; die Stammdaten-Tabelle in design.md spiegelt ihn. Pflege, Zusatzfelder und
  Aufbewahrung der Arten folgen mit Aufgabe 2.1.

  @req-9.2
  Szenario: Der Seed stimmt Feld für Feld mit der Stammdaten-Tabelle überein
    Dann stimmt der Seed von «urinet» Feld für Feld mit der Stammdaten-Tabelle in design.md überein

  @req-9.1
  Szenario: Der Mandant urinet erhält jede Art mit allen Attributen der Stammdaten-Tabelle
    Wenn der Betreiber die Mandanten «urinet» und «probe» anlegt
    Dann stimmen die Dokumentarten im Schema «m_urinet» Feld für Feld mit der Stammdaten-Tabelle in design.md überein
