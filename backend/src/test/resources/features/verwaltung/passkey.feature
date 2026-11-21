# language: de
@spec-06 @browser @passkey
Funktionalität: Passkey im Keycloak-Realm
  Der virtuelle Authenticator prüft den Realm; der Backend-Step-up folgt nach 19.10,
  der Admin-Reset nach 24.2 und der Nachweis auf echten Geräten nach 24.3.

  @req-10.1
  Szenario: Passkey freiwillig in der Kontoverwaltung einrichten und entfernen
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Wenn er in der Kontoverwaltung einen Passkey einrichtet
    Dann kann er diesen Passkey in der Kontoverwaltung entfernen

  @req-10.2
  Szenario: Mit Passkey ohne Passwort und TOTP anmelden
    Angenommen ein Testbenutzer hat einen Passkey eingerichtet
    Wenn er sich mit dem Passkey anmeldet
    Dann erhält er ein Access-Token mit auth_time

  @req-10.3
  Szenario: Passwort bleibt auch mit Passkey an TOTP gebunden
    Angenommen ein Testbenutzer hat einen Passkey eingerichtet
    Dann verlangt die Passwortanmeldung dieses Benutzers weiterhin TOTP

  @req-10.3
  Szenario: Passwort ohne Passkey verlangt TOTP
    Angenommen ein Testbenutzer ohne Passkey existiert
    Dann verlangt die Passwortanmeldung dieses Benutzers weiterhin TOTP

  @req-10.4
  Szenario: Passkey erneuert auth_time für den Step-up
    Angenommen ein Testbenutzer hat einen Passkey eingerichtet
    Und er ist mit dem Passkey angemeldet
    Wenn er sich für den Step-up mit dem Passkey erneut anmeldet
    Dann enthält das neue Access-Token eine neuere auth_time

  @req-10.8
  Szenario: Das Archiv liefert die Keycloak-Origin für Related Origins
    Dann liefert das Archiv die konfigurierte Keycloak-Origin unter dem WebAuthn-Pfad

  @teilnachweis-10.11
  Szenario: Virtueller Authenticator gegen Keycloak
    Angenommen ein Testbenutzer hat einen Passkey eingerichtet
    Wenn er sich mit dem Passkey anmeldet
    Dann erhält er ein Access-Token mit auth_time
    Und er kann diesen Passkey in der Kontoverwaltung entfernen
