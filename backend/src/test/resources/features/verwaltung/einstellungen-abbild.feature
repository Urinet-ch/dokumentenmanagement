# language: de
@spec-06
@20-1-abbild
Funktionalität: Erweitertes Konfigurationsabbild
  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-1.2
  Szenario: Der Katalog enthält nur mandantenbezogene Einstellungen
    Dann enthält der Einstellungskatalog nur freigegebene mandantenbezogene Schlüssel

  @req-1.4
  Szenario: Fehlende Werte verwenden Startwert oder Katalogstandard
    Dann gilt ohne eigenen Zahlenwert zuerst der bisherige Startwert und danach der Katalogstandard

  @req-1.3
  Szenario: Erweiterte Einstellungen überleben Datei-Rundlauf und spätere Änderungen
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die erweiterten Einstellungen mit Testwerten gesetzt werden
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die erweiterten Testwerte
    Wenn «inhaber-urinet» die KI-Texte mit der aktuellen Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die erweiterten Testwerte

  @req-2.3 @req-2.4
  Szenario: Eine Einstellung ausserhalb der Grenzen wird abgelehnt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Chat-Aufbewahrung auf 0 Monate gesetzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert
