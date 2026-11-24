# language: de
@spec-06
Funktionalität: Bestehende Inhaber vor der Migration dokumentieren

  @req-6.3 @req-6.5 @req-9.4 @inhaber-uebernahme
  Szenario: Ein bestehender Inhaber wird ohne Änderung in Keycloak einmalig dokumentiert und übernommen
    Angenommen ein bestehender Inhaber hat noch keinen dokumentierten Rollenvorgang
    Wenn der Betreiber die bestehende Inhaberrolle prüft und dokumentiert
    Und der Betreiber die Benutzerübernahme ausführt
    Dann ist die Inhaberrolle unverändert und der Abgleich meldet keinen fehlenden Rollenvorgang
    Und sind der Rollenvorgang und genau eine Übernahme ohne persönliche Angaben protokolliert
