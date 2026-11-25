# Passkey: Checkliste für die Inbetriebnahme (Aufgabe 24.3, Fabian)

**Status: nicht ausgeführt.** Der Code, die Anleitung in der App (`/hilfe#erste-schritte-passkey`) und die Admin-Option
«Passkeys löschen» sind umgesetzt. Die Wortlaute der App-Anleitung sind aber noch nicht auf einem echten iPhone und Mac
bestätigt; der virtuelle Authenticator ist dafür kein Nachweis. Erst nach den Gerätetests unten darf die Anleitung als
auf diesen Geräten geprüft bezeichnet werden.

Realm- und RP-ID-Änderungen führt der Betreiber aus. Die manuellen Gerätetests und ihre Beurteilung bleiben bei Fabian.
Die Aufgabe 24.3 bleibt offen, bis alle Punkte 1 bis 5 erledigt und die Gerätetests dokumentiert sind. Keine Hostnamen,
Adressen oder Geheimnisse gehören in diese Datei oder ins Repo.

## Reihenfolge
Die Reihenfolge ist verbindlich: zuerst der Realm von Aufgabe 19.3 (Verwaltungsclient, SMTP, Passwort-Richtlinie), dann die
Passkey-Vorlage, `--pruefen`, die Related Origins und zuletzt der Gerätetest. Die Befehle für den Wegwerf-Container (Image
`archiv:kc-current`, `jq`, Anmeldung ohne Passwort im Terminal) stehen vollständig und kopierbar in
[`deploy/infomaniak/keycloak/README.md`](../deploy/infomaniak/keycloak/README.md), Abschnitt «Passkey-Realm nachrüsten
(Aufgabe 24.3)». Die Werte in `<…>` sind Betriebsangaben und kommen nie ins Repo.

## Ablauf
0. **Vorbereitung auf der VPS:** `jq` aus dem Hilfs-Image holen (Prüfsumme `sha256sum -c`, `linux/amd64`) und die drei Skripte
   (`passkey-einrichten-vorlage.sh`, `verwaltung-einrichten-vorlage.sh`, `vorlage-ausfuehren.sh`) aus dem gemergten Stand ablegen,
   dann `chmod -R a+rX "$HOME/archiv-werkzeuge"` (sonst kann der Container bei `umask 077` nichts lesen; README, «Ausführung auf der VPS»).
   **Nicht belegt, beim ersten Lauf prüfen** (je eine Prüfzeile im README): Ausführung auf der VPS selbst, der `docker compose run`-Aufruf
   selbst, Lesbarkeit der Secret-Datei `keycloak_admin` für Benutzer 1000 (`ls -ln`), Erreichbarkeit von `ghcr.io` (`docker pull`),
   `jq` im Container lauffähig. Lokal belegt sind nur die Vorlage (Stub-Test, Echt-Test gegen Keycloak 26.8.2) und `docker run` mit denselben Optionen.
1. **Realm einmalig auf Stufe A aktualisieren.** Der Realm-Import wirkt nur beim ersten Start. Erst `verwaltung-einrichten-vorlage.sh`
   (19.3, SMTP-Angaben als `<smtp-host>`, `<587>`, `<smtp-benutzer>`, `<absender-adresse>`), dann
   `passkey-einrichten-vorlage.sh --pruefen` (Exit 10 = Änderungen nötig), dann ohne `--pruefen`, dann wieder `--pruefen`
   (erwartet Exit 0). Die Vorlage zieht Flow `browser-passkey-otp`, die Richtlinie «WebAuthn Passwordless» und die Kontoverwaltung
   aus dem Realm-Export nach (design.md, «Realm-Änderungen», Punkt 8) und bricht bei unerwartetem Zustand mit einer Meldung ab.
   **Standardaufruf mit `--ereignisse`** (Entscheid Fabian 25.11.2026, Req 10.7: `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` werden
   aktiviert); die Option steht also in allen drei Aufrufen. Sind die Ereignisse des Realms aus oder `enabledEventTypes` leer, bricht die
   Vorlage ab, ohne etwas zu ändern. Probe: Nach der Passkey-Einrichtung in Punkt 4 erscheint `UPDATE_CREDENTIAL` im Ereignisprotokoll
   des Realms (Admin-Konsole, «Ereignisse»).
2. **Relying-Party-ID setzen.** Der Export enthält den Platzhalter `${ARCHIV_PASSKEY_RP_ID}` (`webAuthnPolicyPasswordlessRpId`);
   der Wert ist der Host der App-Basisadresse (`ARCHIV_BASIS_URL`) ohne Schema und Port, abgeleitet wie in
   `deploy/infomaniak/keycloak/start.sh`. Das Hilfsskript `vorlage-ausfuehren.sh` leitet ihn im Wegwerf-Container aus der
   `ARCHIV_BASIS_URL` des Dienstes `kc` ab und die Vorlage prüft ihn (nur Host; eine bereits gesetzte andere ID führt zum Abbruch,
   weil bestehende Passkeys ungültig würden). Er gehört in die Betriebsangaben, nicht ins Repo.
3. **Related Origins prüfen** (`docs/passkey-related-origins.md`): Die App liefert unter `/.well-known/webauthn` die öffentliche
   Keycloak-Origin; HTTP 200, `Content-Type: application/json`, keine Weiterleitung.
4. **Mit iPhone und Mac prüfen:** Auf beiden Geräten den Passkey in der Kontoverwaltung einrichten, damit anmelden, eine
   Step-up-Aktion auslösen (Passkey genügt) und den Passkey wieder entfernen. Danach als Inhaber bei einem synthetischen
   Testbenutzer «Zugang zurücksetzen» mit der Option «Passkeys löschen» ausführen und prüfen, dass der Benutzer nur noch
   mit Passwort und TOTP hereinkommt. Im Ereignisprotokoll des Realms sollte `REMOVE_CREDENTIAL` stehen (nicht belegt, beim
   ersten Lauf beobachten). Die Wortlaute der App-Anleitung erst nach erfolgreicher Prüfung auf beiden Geräten als belegt
   markieren.
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
  Die Vorlage kann das (Option `--ereignisse`, ergänzt nur die beiden Typen), tut es aber nie von selbst.
  **Entschieden am 25.11.2026: Ja.** Der Standardaufruf auf der VPS enthält `--ereignisse` (Ablauf, Punkt 1).
