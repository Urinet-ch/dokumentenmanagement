# language: de
# Spec 06, Req 11.5 gegen das Keycloak des Test-Realms (Einschränkungen wie im Export, RealmExportTest); denselben Nachweis
# gegen den Realm-Export führt RealmExportKeycloakTest. Eine bestehende Google-Verknüpfung darf der Benutzer selbst lösen
# (Spec 01, 2.27, entschieden von Fabian am 28.11.2026); Keycloak protokolliert das.
@spec-06 @browser @passkey
Funktionalität: Grenzen der Kontoverwaltung

  # Die Pfade sind die aus kontoverwaltungEinstiege() (frontend/src/app/gemeinsam/anmeldung/kontoverwaltung.ts)
  @req-11.4
  Szenario: Die Adressen der Einstiege zeigen in der echten Kontoverwaltung die richtigen Bereiche
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Dann zeigt die Kontoverwaltung unter «account-security/signing-in» die Bereiche «Standardauthentifizierung, Zwei-Faktor-Authentifizierung, Passwortlos»
    Und zeigt die Kontoverwaltung unter «account-security/device-activity» die Bereiche «Geräteaktivität»

  @req-11.5
  Szenario: Die Kontoverwaltung ändert keine Stammdaten und verknüpft kein Google-Konto
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Wenn er ein Token der Kontoverwaltung erhält
    Dann lehnt die Kontoverwaltung Änderungen an Benutzername, Vor- und Nachname, E-Mail und Mandant ab
    Und bleiben seine Angaben in Keycloak unverändert
    Und bietet die Kontoverwaltung keine Verknüpfung mit Google an

  @req-11.5
  Szenario: Eine bestehende Google-Verknüpfung löst der Benutzer selbst, Keycloak protokolliert das
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Und ein Admin hat sein Konto mit Google verknüpft
    Wenn er ein Token der Kontoverwaltung erhält
    Dann kann er die Google-Verknüpfung in der Kontoverwaltung selbst lösen
    Und steht das Lösen im Ereignisprotokoll von Keycloak

  @req-11.5
  Szenario: Ohne Einmalcode verlangt die nächste Anmeldung mit Passwort dessen Einrichtung
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Wenn er ein Token der Kontoverwaltung erhält
    Und er seinen Einmalcode in der Kontoverwaltung entfernt
    Dann verlangt die nächste Anmeldung mit Passwort die Einrichtung des Einmalcodes
