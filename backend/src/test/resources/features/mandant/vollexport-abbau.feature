# language: de
@spec-03
Funktionalität: Vollexport und Abbau
  Der Betreiber exportiert die Mandantendaten und kann einen bestätigten Mandanten später kontrolliert abbauen.

  @req-8.2
  Szenario: Metadaten-CSV entschärft Formelzellen
    Wenn eine Vollexport-CSV-Zeile mit einer Formelzelle serialisiert wird
    Dann ist die Formelzelle in der CSV entschärft

  @req-8.3
  Szenario: Der Abbau-Aufruf bleibt ohne Ausführungsflag ein Trockenlauf
    Wenn der Betreiber einen Abbau-Aufruf ohne Ausführungsflag parst
    Dann ist der Abbau-Aufruf ein Trockenlauf
    Und ein widersprüchlicher Trockenlauf wird abgelehnt

  @req-8.5
  Szenario: Der Bericht nennt die Aufbewahrung der Zweitkopie
    Wenn die Sicherungsfrist für einen Mandanten berechnet wird
    Dann nennt der Bericht eine Datenbankfrist und die unbefristete Datei-Aufbewahrung
