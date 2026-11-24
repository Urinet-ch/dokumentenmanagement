# language: de
@spec-06
@20-2-rest
Funktionalität: Einstellungen über die REST-Schnittstelle verwalten
  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Ausgangsstände der Einstellungen sind gemerkt

  @req-1.8 @req-2.1 @req-2.2 @req-2.11
  Szenario: Nur Berechtigte sehen den Katalog und den eigenen Betriebsstand
    Wenn «inhaber-urinet» den Einstellungskatalog liest
    Dann enthält der REST-Katalog Werte und Metadaten sowie den Betriebsstand
    Wenn «mitarbeiter-urinet» den Einstellungskatalog liest
    Dann antwortet die Einstellungsverwaltung mit Status 403
    Wenn «inhaber-vhf-gsk» den Einstellungskatalog liest
    Dann enthält der REST-Katalog den Branding-Standard des zweiten Mandanten

  @req-2.1 @req-2.2 @req-2.11 @req-2.13
  Szenario: Beide Wissensschnittstellen verwenden dieselbe Konfigurationsversion
    Angenommen ein eigenes Wissensbudget ist über die bisherige Schnittstelle gesetzt
    Wenn «inhaber-urinet» den Einstellungskatalog liest
    Dann zeigt der Katalog das eigene Wissensbudget und den unveränderten Standard
    Wenn das Wissensbudget über die neue Schnittstelle geändert wird
    Dann ist eine neue APP-Version mit Änderungsprotokoll entstanden
    Und zeigt die bisherige Wissensschnittstelle den geänderten Wert
    Und ist der zweite Einstellungsstand unverändert

  @req-2.3 @req-2.6 @req-2.8 @req-2.14
  Szenario: Rechte, Version, Risiko und Gültigkeit werden in dieser Reihenfolge geprüft
    Wenn die riskante ungültige REST-Änderung ohne Version gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 428 und ETag des Ausgangsstands
    Wenn die riskante ungültige REST-Änderung mit veralteter Version gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 412 und ETag des Ausgangsstands
    Wenn die riskante ungültige REST-Änderung mit aktueller Version gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 409 und ETag des Ausgangsstands
    Und die REST-Antwort nennt die Auswirkung und «BESTAETIGUNG_NOETIG»
    Und sind Version und Änderungsprotokoll unverändert

  @req-2.6 @req-2.7
  Szenario: Eine riskante Änderung braucht Bestätigung und frische Anmeldung
    Angenommen für die Konfigurationsdatei ist eine frische Anmeldung bestätigt
    Wenn die Änderung des Geschäftsjahresendes als REST-Vorschau gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 200
    Und enthält die REST-Vorschau die Auswirkung zum Geschäftsjahresende
    Und sind Version und Änderungsprotokoll unverändert
    Wenn die Änderung des Geschäftsjahresendes ohne Bestätigung gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 409 und ETag des Ausgangsstands
    Und die REST-Antwort nennt die Auswirkung und «BESTAETIGUNG_NOETIG»
    Wenn die Änderung des Geschäftsjahresendes mit Bestätigung gespeichert wird
    Dann ist eine neue APP-Version mit Änderungsprotokoll entstanden
    Wenn die ursprüngliche Einstellungsversion über REST wiederhergestellt wird
    Dann entsprechen die Einstellungen wieder dem Ausgangsstand

  @req-2.3
  Szenario: Ein ungültiger Feldtyp meldet Befunde und ändert nichts
    Wenn ein ungültiger Archivname über REST gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 422 und ETag des Ausgangsstands
    Und enthält die REST-Antwort einen Befund zum Archivnamen
    Und sind Version und Änderungsprotokoll unverändert

  @req-2.4
  Szenario: Eine übergrosse Chunked-Anfrage wird schon beim Empfang abgewiesen
    Wenn eine übergrosse REST-Anfrage ohne Inhaltslänge gesendet wird
    Dann wird die REST-Anfrage mit Status 413 abgewiesen
    Und sind Version und Änderungsprotokoll unverändert

  @req-2.9
  Szenario: Das Änderungsprotokoll nennt bei Kennungen keine Werte
    Wenn eine UID über REST geändert wird
    Dann enthält das Änderungsprotokoll nur den Pfad und nicht die UID-Werte

  @req-2.8
  Szenario: Die Vorschau verlangt eine vorhandene und aktuelle Versionsmarke
    Wenn «inhaber-urinet» eine Vorschau der Branding-Änderung ohne Versionsmarke sendet
    Dann antwortet die Einstellungsverwaltung mit Status 428 und ETag des Ausgangsstands
    Wenn «inhaber-urinet» eine Vorschau der Branding-Änderung mit veralteter Versionsmarke sendet
    Dann antwortet die Einstellungsverwaltung mit Status 412 und ETag des Ausgangsstands
    Und sind Version und Änderungsprotokoll unverändert

  @req-2.8
  Szenario: Die Rücksetzung verlangt eine vorhandene und aktuelle Versionsmarke
    Wenn «inhaber-urinet» eine Rücksetzung ohne Versionsmarke sendet
    Dann antwortet die Einstellungsverwaltung mit Status 428 und ETag des Ausgangsstands
    Wenn «inhaber-urinet» eine Rücksetzung mit veralteter Versionsmarke sendet
    Dann antwortet die Einstellungsverwaltung mit Status 412 und ETag des Ausgangsstands
    Und sind Version und Änderungsprotokoll unverändert

  @req-2.3 @req-2.5
  Szenario: Die Rücksetzung lehnt eine unbekannte Version und ein unbekanntes Feld ab
    Wenn «inhaber-urinet» eine unbekannte Einstellungsversion wiederherstellt
    Dann antwortet die Einstellungsverwaltung mit Status 404
    Und ist der Fehlercode der Einstellungsverwaltung «NICHT_GEFUNDEN»
    Wenn «inhaber-urinet» ein unbekanntes Feld beim Zurücksetzen sendet
    Dann antwortet die Einstellungsverwaltung mit Status 422
    Und ist der Fehlercode der Einstellungsverwaltung «KONFIGURATION_UNGUELTIG»
    Und sind Version und Änderungsprotokoll unverändert

  @req-2.9 @grenze-einstellungen-zwei
  Szenario: Die Einstellungsrate zählt echte REST-Änderungen
    Angenommen die Einstellungsrate ist auf zwei Änderungen begrenzt
    Wenn «inhaber-urinet» den Archivnamen per REST auf "Rate Test Eins" setzt
    Dann antwortet die Einstellungsverwaltung mit Status 200
    Wenn «inhaber-urinet» den Archivnamen per REST auf "Rate Test Zwei" setzt
    Dann antwortet die Einstellungsverwaltung mit Status 200
    Wenn «inhaber-urinet» den Archivnamen per REST auf "Rate Test Drei" setzt
    Dann antwortet die Einstellungsverwaltung mit Status 429
    Und ist der Fehlercode der Einstellungsverwaltung «ZU_VIELE_ANFRAGEN»
    Und bleibt die Versionsmarke der Einstellungsverwaltung unverändert
    Und sind nach zwei angenommenen Änderungen genau zwei Protokolleinträge entstanden

  @req-2.5 @req-2.7 @req-2.9 @req-2.11
  Szenario: Vorschau, Änderung und Rücksetzung bleiben auf den eigenen Mandanten beschränkt
    Wenn die REST-Änderung des Archivnamens als Vorschau gesendet wird
    Dann antwortet die Einstellungsverwaltung mit Status 200
    Und sind Version und Änderungsprotokoll unverändert
    Wenn die REST-Änderung des Archivnamens gespeichert wird
    Dann ist eine neue APP-Version mit Änderungsprotokoll entstanden
    Und ist der zweite Einstellungsstand unverändert
    Wenn die REST-Änderung des Archivnamens gespeichert wird
    Dann entsteht keine weitere Konfigurationsversion
    Wenn der Archivname über REST auf null gesetzt wird
    Dann gilt für den Archivnamen der Standard
    Wenn die REST-Änderung des Archivnamens gespeichert wird
    Und die Gruppe Branding über REST zurückgesetzt wird
    Dann gilt für den Archivnamen der Standard
    Wenn die ursprüngliche Einstellungsversion über REST wiederhergestellt wird
    Dann entsprechen die Einstellungen wieder dem Ausgangsstand
    Und ist der zweite Einstellungsstand unverändert

  @req-1.4 @req-2.12 @20-4-verbraucher
  Szenario: Öffentliches Branding folgt der Version und dem Standard
    Angenommen das öffentliche Branding beider Mandanten ist gemerkt
    Wenn «inhaber-urinet» den Einstellungskatalog liest
    Dann nennt der Katalog die Wirkzeit für Branding und Aufbewahrung
    Wenn die REST-Änderung des Archivnamens gespeichert wird
    Dann zeigt das öffentliche Branding unmittelbar den neuen Namen
    Und bleibt das öffentliche Branding des anderen Mandanten unverändert
    Wenn die Gruppe Branding über REST zurückgesetzt wird
    Dann zeigt das öffentliche Branding den bisherigen Standard
    Wenn die ursprüngliche Einstellungsversion über REST wiederhergestellt wird
    Dann entspricht das öffentliche Branding wieder dem Ausgangsstand
