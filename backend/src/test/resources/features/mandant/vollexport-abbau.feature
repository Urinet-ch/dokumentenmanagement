# language: de
@spec-03
Funktionalität: Vollexport und Abbau
  Der Betreiber exportiert die Mandantendaten und kann einen bestätigten Mandanten später kontrolliert abbauen.

  @req-8.2
  Szenario: Metadaten-CSV entschärft Formelzellen
    Wenn eine Vollexport-CSV-Zeile mit einer Formelzelle serialisiert wird
    Dann ist die Formelzelle in der CSV entschärft

  @req-8.2
  Szenario: Das ZIP enthält Original, Arbeitsfassung, PDF/A, Metadaten und Manifest mit stimmenden Prüfsummen
    Wenn ein Vollexport mit einem Dokument und einem Manifest geschrieben wird
    Dann enthält das ZIP Original, Arbeitsfassung, PDF/A, Metadaten und Manifest
    Und die Prüfsummenliste stimmt mit dem Inhalt des ZIP überein

  @req-8.2
  Szenario: Zwei Mandanten erhalten nur ihre eigenen Dateien
    Wenn für zwei Mandanten je ein Vollexport geschrieben wird
    Dann enthält jedes ZIP nur die Dateien des eigenen Mandanten

  @req-8.3
  Szenario: Ein veralteter Vollexport verhindert die Deaktivierung
    Wenn nach dem Vollexport ein weiteres Dokument entstanden ist
    Dann bricht der Abbau ab und der Mandant bleibt aktiv

  @req-8.3
  Szenario: Vor Ablauf der 30 Tage wird nichts gelöscht und gesperrte Objekte werden aufgelistet
    Wenn der Abbau 29 Tage nach der Deaktivierung ausgeführt wird
    Dann meldet der Abbau die Wartefrist und löscht nichts
    Wenn der Abbau nach 30 Tagen mit einem gesperrten Objekt ausgeführt wird
    Dann ist der Abbau unvollständig und listet das gesperrte Objekt auf

  @req-8.3
  Szenario: Der Abbau-Aufruf bleibt ohne Ausführungsflag ein Trockenlauf
    Wenn der Betreiber einen Abbau-Aufruf ohne Ausführungsflag parst
    Dann ist der Abbau-Aufruf ein Trockenlauf
    Und ein widersprüchlicher Trockenlauf wird abgelehnt

  @req-8.5
  Szenario: Der Bericht nennt die Aufbewahrung der Zweitkopie
    Wenn die Sicherungsfrist für einen Mandanten berechnet wird
    Dann nennt der Bericht eine Datenbankfrist und die unbefristete Datei-Aufbewahrung
