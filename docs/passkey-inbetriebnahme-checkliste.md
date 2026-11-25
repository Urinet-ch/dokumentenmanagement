# Passkey: Checkliste für die Inbetriebnahme (Aufgabe 24.3, Fabian)

**Status: ausgeführt am 25.11.2026 (Fabian), mit einem offenen Punkt.** Realm auf Stufe A eingerichtet (Prüflauf Exit 10,
Einrichten Exit 0 mit 18 Änderungen, Nachprüfung Exit 0, Wiederholung «nichts zu tun»). Auf iPhone und Mac bestätigt: Anmelde-Rückfall mit
Passwort und TOTP, Related Origins, Passkey einrichten, anmelden, Step-up und entfernen, «Zugang zurücksetzen» mit «Passkeys löschen»
sowie TOTP bei Passwortanmeldung trotz Passkey. **Offen:** die Probe im Ereignisprotokoll (`UPDATE_CREDENTIAL`, `REMOVE_CREDENTIAL`);
die Admin-Konsole ist nicht öffentlich, und das Hilfsskript zum Listen der Ereignisse fehlt noch. Die App-Anleitung gilt damit als auf
iPhone und Mac geprüft (25.11.2026); der virtuelle Authenticator war und ist dafür kein Nachweis.

Realm- und RP-ID-Änderungen führt der Betreiber aus. Die manuellen Gerätetests und ihre Beurteilung bleiben bei Fabian.
24.3 ist von Fabian am 25.11.2026 abgenommen; offen bleibt die Ereignisprotokoll-Probe (Hilfsskript folgt separat). Keine Hostnamen,
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
   dann `sudo chmod -R a+rX /opt/archiv-werkzeuge` (nicht unter `$HOME`: als root ist es `/root`, für Benutzer 1000 nicht durchsuchbar) (sonst kann der Container bei `umask 077` nichts lesen; README, «Ausführung auf der VPS»).
   Am 25.11.2026 auf der VPS belegt: Ausführung, `docker compose run`-Aufruf, Lesbarkeit der Secret-Datei für Benutzer 1000,
   Erreichbarkeit von `ghcr.io`, `jq` im Container.
1. **Realm einmalig auf Stufe A aktualisieren.** Der Realm-Import wirkt nur beim ersten Start. Erst `verwaltung-einrichten-vorlage.sh`
   (19.3, SMTP-Angaben als `<smtp-host>`, `<587>`, `<smtp-benutzer>`, `<absender-adresse>`), dann
   `passkey-einrichten-vorlage.sh --pruefen` (Exit 10 = Änderungen nötig), dann ohne `--pruefen`, dann wieder `--pruefen`
   (erwartet Exit 0). Die Vorlage zieht Flow `browser-passkey-otp`, die Richtlinie «WebAuthn Passwordless» und die Kontoverwaltung
   aus dem Realm-Export nach (design.md, «Realm-Änderungen», Punkt 8) und bricht bei unerwartetem Zustand mit einer Meldung ab.
   **Standardaufruf mit `--ereignisse`** (Entscheid Fabian 25.11.2026, Req 10.7: `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` werden
   aktiviert); die Option steht also in allen drei Aufrufen. Sind die Ereignisse des Realms aus oder `enabledEventTypes` leer, bricht die
   Vorlage ab, ohne etwas zu ändern. Probe: Nach der Passkey-Einrichtung in Punkt 4 erscheint `UPDATE_CREDENTIAL` im Ereignisprotokoll
   des Realms. Die Admin-Konsole ist nicht öffentlich; lesen über `kcadm` im Wegwerf-Container (Hilfsskript noch nicht vorhanden, Probe offen).
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
   mit Passwort und TOTP hereinkommt. Im Ereignisprotokoll des Realms sollte `REMOVE_CREDENTIAL` stehen (Probe offen, siehe Punkt 1). Geprüft am 25.11.2026 auf iPhone und Mac (Fabian).
5. **Rückfall prüfen:** Ein Benutzer ohne Passkey meldet sich weiter mit Passwort und TOTP an; auch mit eingerichtetem Passkey
   bleibt TOTP für die Passwortanmeldung verlangt.

| Prüfung | iPhone | Mac |
| --- | --- | --- |
| Passkey einrichten und damit anmelden | ok (Fabian, 25.11.2026) | ok (Fabian, 25.11.2026) |
| Step-up mit Passkey | ok (Fabian, 25.11.2026) | ok (Fabian, 25.11.2026) |
| Passkey entfernen | ok (Fabian, 25.11.2026) | ok (Fabian, 25.11.2026) |
| Passwort- und TOTP-Rückfall | ok (Fabian, 25.11.2026) | ok (Fabian, 25.11.2026) |

Offen: Ereignisprotokoll `UPDATE_CREDENTIAL` / `REMOVE_CREDENTIAL` (Admin-Konsole nicht öffentlich, Werkzeug fehlt noch).

## Domainwechsel
Passkeys sind an die Relying-Party-ID gebunden. Nach einem Wechsel der Domain gelten bestehende Passkeys nicht mehr. Rückfall ist
Passwort plus TOTP; die Benutzer richten die Passkeys für die neue ID neu ein. Die Anleitung in der App nennt das ebenfalls.

## Entscheide für Fabian
- ~~Ein Link zur Kontoverwaltung in der App fehlt; die Anleitung verweist auf die Person, die den Zugang eingerichtet hat.~~
  **Erledigt am 25.11.2026 (Aufgabe 24.4):** Die Kopfzeile der App zeigt jedem angemeldeten Benutzer «Passkey einrichten»; der Link öffnet
  `<Keycloak-Aussteller>/account` in einem neuen Tab, die Adresse stammt aus der OIDC-Konfiguration der App. Die Anleitung nennt den Knopf.
- Keycloak-Ereignisse zum Einrichten und Entfernen eines Passkeys (Requirement 10.7) sind im Realm nicht aktiviert; sie würden
  `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` in `enabledEventTypes` brauchen und einen weiteren Realm-Eingriff auf Stufe A.
  Die Vorlage kann das (Option `--ereignisse`, ergänzt nur die beiden Typen), tut es aber nie von selbst.
  **Entschieden am 25.11.2026: Ja.** Der Standardaufruf auf der VPS enthält `--ereignisse` (Ablauf, Punkt 1).
