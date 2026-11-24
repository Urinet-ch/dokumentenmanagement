# Passkey: Checkliste für die Inbetriebnahme (Aufgabe 24.3, Fabian)

Diese Schritte kann nur der Betreiber ausführen. Der Code, die Anleitung in der App (`/hilfe#erste-schritte-passkey`) und
die Admin-Option «Passkeys löschen» sind mit Aufgabe 24.2 fertig. Die Aufgabe 24.3 bleibt offen, bis die Punkte 1 bis 5
erledigt sind. Keine Hostnamen, Adressen oder Geheimnisse gehören in diese Datei oder ins Repo.

## Ablauf
1. **Realm einmalig auf Stufe A aktualisieren.** Der Realm-Import wirkt nur beim ersten Start. Die Änderungen des Passkey-Flows
   (Flow `browser-passkey-otp`, Richtlinie «WebAuthn Passwordless», Kontoverwaltung) stehen im Realm-Export und werden wie in
   design.md, «Realm-Änderungen», Punkt 8, per `kcadm.sh` im Wegwerf-Container nachgezogen (wie bei Aufgabe 19.3).
2. **Relying-Party-ID setzen.** Der Export enthält den Platzhalter `${ARCHIV_PASSKEY_RP_ID}` (`webAuthnPolicyPasswordlessRpId`);
   der Wert ist der Host der App-Basisadresse ohne Port. Er gehört in die Betriebsangaben, nicht ins Repo.
3. **Related Origins prüfen** (`docs/passkey-related-origins.md`): Die App liefert unter `/.well-known/webauthn` die öffentliche
   Keycloak-Origin; HTTP 200, `Content-Type: application/json`, keine Weiterleitung.
4. **Mit iPhone und Mac prüfen:** Passkey in der Kontoverwaltung einrichten, mit dem Passkey anmelden, eine Step-up-Aktion
   auslösen (Passkey genügt), Passkey wieder entfernen. Danach als Inhaber bei einem Testbenutzer «Zugang zurücksetzen» mit der
   Option «Passkeys löschen» ausführen und prüfen, dass der Benutzer nur noch mit Passwort und TOTP hereinkommt.
5. **Rückfall prüfen:** Ein Benutzer ohne Passkey meldet sich weiter mit Passwort und TOTP an; auch mit eingerichtetem Passkey
   bleibt TOTP für die Passwortanmeldung verlangt.

## Domainwechsel
Passkeys sind an die Relying-Party-ID gebunden. Nach einem Wechsel der Domain gelten bestehende Passkeys nicht mehr. Rückfall ist
Passwort plus TOTP; die Benutzer richten die Passkeys für die neue ID neu ein. Die Anleitung in der App nennt das ebenfalls.

## Entscheide für Fabian
- Ein Link zur Kontoverwaltung in der App fehlt; die Anleitung verweist auf die Person, die den Zugang eingerichtet hat.
- Keycloak-Ereignisse zum Einrichten und Entfernen eines Passkeys (Requirement 10.7) sind im Realm nicht aktiviert; sie würden
  `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` in `enabledEventTypes` brauchen und einen weiteren Realm-Eingriff auf Stufe A.
