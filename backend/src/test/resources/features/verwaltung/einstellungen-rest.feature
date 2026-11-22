# language: de
@spec-06
@20-2-rest
Funktionalität: Einstellungen über die REST-Schnittstelle verwalten
  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Ausgangsstände der Einstellungen sind gemerkt

  @req-2.1 @req-2.2 @req-2.11
  Szenario: Nur Berechtigte sehen den Katalog und den eigenen Betriebsstand
    Wenn «inhaber-urinet» den Einstellungskatalog liest
    Dann enthält der REST-Katalog Werte und Metadaten sowie den Betriebsstand
    Wenn «mitarbeiter-urinet» den Einstellungskatalog liest
    Dann antwortet die Einstellungsverwaltung mit Status 403
    Wenn «inhaber-vhf-gsk» den Einstellungskatalog liest
    Dann enthält der REST-Katalog den Branding-Standard des zweiten Mandanten

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
