# Passkey: Checkliste für die Inbetriebnahme (Aufgabe 24.3, Fabian)

**Status: nicht ausgeführt.** Der Code, die Anleitung in der App (`/hilfe#erste-schritte-passkey`) und die Admin-Option
«Passkeys löschen» sind umgesetzt. Die Wortlaute der App-Anleitung sind aber noch nicht auf einem echten iPhone und Mac
bestätigt; der virtuelle Authenticator ist dafür kein Nachweis. Erst nach den Gerätetests unten darf die Anleitung als
auf diesen Geräten geprüft bezeichnet werden.

Realm- und RP-ID-Änderungen führt der Betreiber aus. Die manuellen Gerätetests und ihre Beurteilung bleiben bei Fabian.
Die Aufgabe 24.3 bleibt offen, bis alle Punkte 1 bis 5 erledigt und die Gerätetests dokumentiert sind. Keine Hostnamen,
Adressen oder Geheimnisse gehören in diese Datei oder ins Repo.

## Ablauf
1. **Realm einmalig auf Stufe A aktualisieren.** Der Realm-Import wirkt nur beim ersten Start. Die Änderungen des Passkey-Flows
   (Flow `browser-passkey-otp`, Richtlinie «WebAuthn Passwordless», Kontoverwaltung) stehen im Realm-Export und werden wie in
   design.md, «Realm-Änderungen», Punkt 8, per `kcadm.sh` im Wegwerf-Container nachgezogen (wie bei Aufgabe 19.3).
2. **Relying-Party-ID setzen.** Der Export enthält den Platzhalter `${ARCHIV_PASSKEY_RP_ID}` (`webAuthnPolicyPasswordlessRpId`);
   der Wert ist der Host der App-Basisadresse ohne Port. Er gehört in die Betriebsangaben, nicht ins Repo.
3. **Related Origins prüfen** (`docs/passkey-related-origins.md`): Die App liefert unter `/.well-known/webauthn` die öffentliche
   Keycloak-Origin; HTTP 200, `Content-Type: application/json`, keine Weiterleitung.
4. **Mit iPhone und Mac prüfen:** Auf beiden Geräten den Passkey in der Kontoverwaltung einrichten, damit anmelden, eine
   Step-up-Aktion auslösen (Passkey genügt) und den Passkey wieder entfernen. Danach als Inhaber bei einem synthetischen
   Testbenutzer «Zugang zurücksetzen» mit der Option «Passkeys löschen» ausführen und prüfen, dass der Benutzer nur noch
   mit Passwort und TOTP hereinkommt. Die Wortlaute der App-Anleitung erst nach erfolgreicher Prüfung auf beiden Geräten
   als belegt markieren.
5. **Rückfall prüfen:** Ein Benutzer ohne Passkey meldet sich weiter mit Passwort und TOTP an; auch mit eingerichtetem Passkey
   bleibt TOTP für die Passwortanmeldung verlangt.

| Prüfung | iPhone | Mac |
| --- | --- | --- |
| Passkey einrichten und damit anmelden | offen | offen |
| Step-up mit Passkey | offen | offen |
| Passkey entfernen | offen | offen |
| Passwort- und TOTP-Rückfall | offen | offen |

## Domainwechsel
Passkeys sind an die Relying-Party-ID gebunden. Nach einem Wechsel der Domain gelten bestehende Passkeys nicht mehr. Rückfall ist
Passwort plus TOTP; die Benutzer richten die Passkeys für die neue ID neu ein. Die Anleitung in der App nennt das ebenfalls.

## Entscheide für Fabian
- Ein Link zur Kontoverwaltung in der App fehlt; die Anleitung verweist auf die Person, die den Zugang eingerichtet hat.
- Keycloak-Ereignisse zum Einrichten und Entfernen eines Passkeys (Requirement 10.7) sind im Realm nicht aktiviert; sie würden
  `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` in `enabledEventTypes` brauchen und einen weiteren Realm-Eingriff auf Stufe A.
