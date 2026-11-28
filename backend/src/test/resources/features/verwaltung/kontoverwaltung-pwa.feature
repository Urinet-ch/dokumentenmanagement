# language: de
# Spec 06, Req 11.5 gegen das Keycloak des Test-Realms (Einschränkungen wie im Export, RealmExportTest); denselben Nachweis
# gegen den Realm-Export führt RealmExportKeycloakTest. Teilnachweis: Es fehlt «kein Google-Konto lösen»; eine bestehende Verknüpfung kann
# Keycloak 26.8 über die Kontoverwaltung lösen (design.md, «Umsetzung (Aufgabe 26.2 …)»).
@spec-06 @browser @passkey
Funktionalität: Grenzen der Kontoverwaltung

  @teilnachweis-11.5
  Szenario: Die Kontoverwaltung ändert keine Stammdaten und verknüpft kein Google-Konto
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Wenn er ein Token der Kontoverwaltung erhält
    Dann lehnt die Kontoverwaltung Änderungen an Benutzername, Vor- und Nachname, E-Mail und Mandant ab
    Und bleiben seine Angaben in Keycloak unverändert
    Und bietet die Kontoverwaltung keine Verknüpfung mit Google an

  @teilnachweis-11.5
  Szenario: Ohne Einmalcode verlangt die nächste Anmeldung mit Passwort dessen Einrichtung
    Angenommen ein Testbenutzer ist mit Passwort und TOTP angemeldet
    Wenn er ein Token der Kontoverwaltung erhält
    Und er seinen Einmalcode in der Kontoverwaltung entfernt
    Dann verlangt die nächste Anmeldung mit Passwort die Einrichtung des Einmalcodes
