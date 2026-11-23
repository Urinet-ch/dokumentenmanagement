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
    Angenommen für die Konfigurationsdatei ist eine frische Anmeldung bestätigt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die erweiterten Einstellungen mit Testwerten gesetzt werden
    Und «inhaber-urinet» die geänderte Konfigurationsdatei mit Bestätigung "webseitenChat.aufbewahrungTage" einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die erweiterten Testwerte
    Wenn «inhaber-urinet» die KI-Texte mit der aktuellen Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die erweiterten Testwerte

  @req-2.10 @req-2.14
  Szenario: Ein Schemafehler verdeckt weder eine riskante Einstellung noch eine veraltete Basisversion
    Angenommen für die Konfigurationsdatei ist eine frische Anmeldung bestätigt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Einstellung "aufbewahrungMonate" in der Gruppe "chat" auf den JSON-Wert '0' gesetzt wird
    Und in der Datei das Feld «unbekannt» mit dem Wert «falsch» steht
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 409 und Fehlercode «BESTAETIGUNG_NOETIG»
    Wenn «inhaber-urinet» die geänderte Konfigurationsdatei mit Bestätigung "chat.aufbewahrungMonate" einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Wenn in der Datei die Basisversion veraltet ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 409 und Fehlercode «KONFIGURATION_VERALTET»
    Und ist die Konfigurationsversion unverändert

  @req-2.3 @req-2.4
  Szenariogrundriss: Eine Einstellung ausserhalb der Grenzen wird abgelehnt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Einstellung "<schluessel>" in der Gruppe "<gruppe>" auf den JSON-Wert '<wert>' gesetzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert

    Beispiele:
      | gruppe    | schluessel         | wert                    |
      | branding  | name               | "<b>Archiv</b>"         |
      | branding  | name               | "Arc\u0007hiv"          |
      | branding  | primaerfarbe       | "#fff"                  |
      | kennungen | uid                | "CHE-123"               |
      | kennungen | ibans              | "kein-array"            |
      | chat      | unbekannterSchluessel | 1                    |

  @req-2.4
  Szenario: Eine IBAN-Liste mit mehr als 20 Einträgen wird abgelehnt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die IBAN-Liste mit 21 Einträgen gesetzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert
