# Keycloak

## Theme und Benachrichtigung

Der Realm-Export wählt das Login- und E-Mail-Theme `urinet-archiv`; der Client für VHF/GSK kann sein eigenes
Login-Theme weiter wählen. Ein bestehender Realm wird beim Import nicht aktualisiert: Die Umstellung seiner
Theme-Einstellungen und der Versand an ein echtes Postfach sind Betriebsnachweise.
Der Test-Realm der Dev Services verwendet weiterhin das eingebaute Theme, da das Dev-Services-Image keine
Projekt-Themes enthält; `RealmExportKeycloakTest` prüft das ausgelieferte Theme mit einem eigenen Keycloak.

Die Mitteilung an die bisherige Adresse versendet das Archiv, nicht Keycloak (Spec 06, 3.14). Vorgesehener Text
für die noch ausstehende Anbindung:

> **UriNet Archiv: E-Mail-Adresse geändert**
>
> Die E-Mail-Adresse für Ihren Zugang zum UriNet Archiv wurde geändert. Falls Sie diese Änderung nicht erwartet
> haben, wenden Sie sich umgehend an die Verwaltung.

## Verwaltungsclient und SMTP (Aufgabe 19.3)

Der bestehende Realm wird nicht erneut importiert. Die Vorlage
[`verwaltung-einrichten-vorlage.sh`](./verwaltung-einrichten-vorlage.sh) richtet den Verwaltungsclient und seine drei
Rollen, den Scope `kc-admin-api`, SMTP und die Passwort-Richtlinie ein. Sie läuft in einem Wegwerf-Container mit
`jq` und bereits für den Realm authentifiziertem `kcadm.sh` (vollständiger Aufruf für die VPS im Abschnitt
«Passkey-Realm nachrüsten», dort auch zu `jq`). Die Secret-Dateien
`/run/secrets/keycloak_verwaltung_secret` und `/run/secrets/keycloak_smtp_passwort` werden dort eingehängt; die
SMTP-Angaben `SMTP_HOST`, `SMTP_PORT`, `SMTP_BENUTZER` und `SMTP_ABSENDER` kommen vom Betreiber. Die Vorlage verwendet
SMTP mit Authentifizierung und STARTTLS und legt temporäre JSON-Dateien ausschliesslich mit Rechten 600 an. Bestehende
unerwartete direkte Rollen des Dienstkontos führen zum Abbruch, statt sie stillschweigend zu verändern.

Die Vorlage überträgt das SMTP-Passwort in die Realm-Konfiguration; der Secret-Mount allein aktualisiert den Realm
nicht. Bei einem Wechsel des Passworts ist die Vorlage erneut auszuführen. Die übrigen Realm-Änderungen und die
Betriebsnachweise von 19.3 bleiben Aufgabe des Betreibers.

**Vor dem Deploy:** Fabian muss den
[Workflow-Diff](../../../docs/betrieb/keycloak-secrets-workflow.diff) auf `infomaniak-deploy.yml` anwenden und die
beiden GitHub-Secrets hinterlegen. Erst dann liefert das Bundle die beiden Dateien, die Compose voraussetzt. Die
Admin-Adresse und das Dienstkonto-Secret werden gemeinsam aktiviert; Realm und Dienstkonto müssen zuvor eingerichtet
sein. Ohne den Workflow-Diff schlägt ein Deploy mit dem neuen Compose fehl. Die Aufgabe bleibt offen, bis die
Betriebsnachweise erbracht sind.

Der lokale Test verwendet ausschliesslich synthetische Werte und zwei eigene Wegwerf-Container:

```bash
bash deploy/infomaniak/keycloak/test/verwaltung-einrichten-keycloak-test.sh
```

## Google-IdP

Der Realm-Export enthält den deaktivierten Provider `google`, beide Anmeldeflows und die Ereignistypen. `start.sh` kann
`google_client_id` und `google_client_secret` aus `/run/secrets/` lesen und die Werte beim erstmaligen Import als
`GOOGLE_CLIENT_ID` und `GOOGLE_CLIENT_SECRET` bereitstellen, wenn beide nicht leer sind. Fehlt einer, werden beide beim
Realm-Import leer übergeben; der Provider im Export bleibt deaktiviert. Das Produktions-Compose-File bindet die Dateien
noch nicht ein: Der Deploy-Workflow muss sie erst nach dem Entscheid zu OAuth-Client und GitHub-Secrets
(`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`) bereitstellen.

`--import-realm` überspringt einen Realm, der bereits in der Datenbank vorhanden ist. Die Vorlage
[`google-idp-einrichten-vorlage.sh`](./google-idp-einrichten-vorlage.sh) bildet darum die einmalige Nachrüstung ab. Sie
ist für einen Wegwerf-Container mit authentifiziertem `kcadm.sh`, `jq` und Secret-Dateien unter
`/run/secrets/google_client_id` und `/run/secrets/google_client_secret` gedacht. Die Vorlage wurde mit einem
`kcadm.sh`-Stub und synthetischen Testwerten geprüft, aber nicht gegen einen bestehenden Realm oder einen Betriebsserver
ausgeführt. Sie legt den Provider absichtlich deaktiviert an.

Zum Verknüpfen eines Kontos ausschliesslich als Administrator:

```bash
kcadm.sh create "users/$KC_USER_ID/federated-identity/google" -r urinet-archiv \
  -s identityProvider=google -s "userId=$GOOGLE_SUB" -s "userName=$GOOGLE_SUB"
```

Zum Lösen der Verknüpfung und Beenden aller Benutzersitzungen:

```bash
kcadm.sh delete "users/$KC_USER_ID/federated-identity/google" -r urinet-archiv
kcadm.sh create "users/$KC_USER_ID/logout" -r urinet-archiv
```

Die Shell- und Stub-Prüfung läuft mit:

```bash
bash -n deploy/infomaniak/keycloak/google-idp-einrichten-vorlage.sh
bash deploy/infomaniak/keycloak/test/google-idp-einrichten-vorlage-test.sh
bash -n deploy/infomaniak/keycloak/start.sh
bash deploy/infomaniak/keycloak/test/start-test.sh
```
`start-test.sh` belegt nur, dass leere oder nur aus Leerraum bestehende Werte als leere Umgebung ankommen; der Provider bleibt, weil der Export ihn fest mit `enabled: false` führt. Der Test läuft nicht in der CI.

## Passkey-Realm nachrüsten (Aufgabe 24.3)

Der Realm-Import wirkt nur beim ersten Start. Auf einem früher importierten Realm (Stufe A) zieht die Vorlage
[`passkey-einrichten-vorlage.sh`](./passkey-einrichten-vorlage.sh) die Änderungen aus Aufgabe 24.1 per `kcadm.sh` nach; die
Quelle ist [`deploy/keycloak/realm-export.json`](../../keycloak/realm-export.json). Sie ändert, nur wenn etwas abweicht:

- Richtlinie «WebAuthn Passwordless»: Name «UriNet Archiv», Relying-Party-ID aus `ARCHIV_PASSKEY_RP_ID`, Attestation `none`,
  auffindbarer Schlüssel und Benutzerverifikation `required`, `passkeysEnabled`, Mediation `conditional`.
- Flow: In `browser-totp-pflicht-formulare` ersetzt der Teilflow `browser-passkey-otp` (CONDITIONAL, Priorität 20) das direkte
  TOTP-Formular; er enthält `conditional-credential` (Konfiguration `otp-nur-ohne-passkey`: `webauthn-passwordless`,
  `included=false`) und das TOTP-Formular. Ohne Passkey-Anmeldung bleibt TOTP also Pflicht. Der Realm-`browserFlow`
  `browser-totp-pflicht` und alle anderen Flows bleiben unberührt. Zuerst entsteht der vollständige Teilflow, erst danach
  entfällt das direkte TOTP-Formular: Zu keinem Zeitpunkt gibt es eine Anmeldung ohne zweiten Faktor.
- Required Actions `webauthn-register-passwordless` (Priorität 60) und `delete_credential` (70): aktiv, nicht Standard.
- Kontoverwaltung: Der Client `account-console` darf die Rollen `manage-account` und `view-profile` des Clients `account`
  verwenden (sonst fehlt der Bereich «Anmelden»).
- Nur mit `--ereignisse`: Die Ereignistypen `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` kommen zu `enabledEventTypes` hinzu
  (Req 10.7). **Entscheid von Fabian (25.11.2026): Die Ereignisse werden aktiviert; der Standardaufruf auf der VPS enthält
  `--ereignisse`.** Ohne die Option bleiben sie unverändert. Sind die Ereignisse des Realms ausgeschaltet oder ist
  `enabledEventTypes` leer, bricht die Vorlage ab (Meldung, Exit 1), statt zu raten.

Die Vorlage bricht mit einer Meldung ab (Exit 1), statt zu raten, wenn der Zustand nicht zum Export passt: anderer `browserFlow`,
bereits eine andere Relying-Party-ID (bestehende Passkeys würden ungültig), unerwartete Ausführungen im Formular-Flow oder
Teilflow, mehrdeutige Clients. Beim Einrichten (ohne `--pruefen`) läuft zuerst ein Probelauf ohne Änderung; bricht er ab, ist nichts
verändert. Nach dem Einrichten prüft sich die Vorlage im selben Lauf selbst nach.

```text
passkey-einrichten-vorlage.sh [--pruefen] [--ereignisse]
  --pruefen     nur lesen: Exit 0 = nichts zu tun, Exit 10 = Änderungen nötig, Exit 1 = Fehler oder unerwarteter Zustand
  --ereignisse  zusätzlich die Ereignistypen aktivieren (Entscheid 25.11.2026: im Standardaufruf auf der VPS enthalten)
Umgebung: ARCHIV_PASSKEY_RP_ID (Pflicht: nur der Host, ohne Schema, Port und Pfad), KC_REALM (urinet-archiv), KCADM
```

Die Relying-Party-ID ist der Host der App-Basisadresse ohne Port, abgeleitet wie in [`start.sh`](./start.sh). Das Hilfsskript
`vorlage-ausfuehren.sh` leitet sie im Wegwerf-Container aus `ARCHIV_BASIS_URL` ab; der Wert steht nie im Repo.

### Ausführung auf der VPS (Wegwerf-Container)

Nie per `docker exec` im laufenden Keycloak (zweite JVM, `mem_limit`; siehe Haupt-README, «Admin-Kommandos auf der VPS»). Der
Wegwerf-Container nutzt das Image `archiv:kc-current` und die Einstellungen des Dienstes `kc` (Netz `intern`, nicht-root,
schreibgeschützt, `mem_limit`, Secret `keycloak_admin`), startet aber nur `bash` und nicht Keycloak.

**`jq` fehlt im Keycloak-Image.** Geprüft an einem aus `deploy/infomaniak/keycloak/Dockerfile` gebauten Image: Es enthält `bash`,
`awk`, aber weder `jq` noch `curl`. Die Vorlagen (Passkey und 19.3) brauchen `jq`. Einmalig wird eine statische `jq`-Datei aus dem
offiziellen Hilfs-Image `ghcr.io/jqlang/jq` (mehrarchitektur, auf Digest festgelegt) geholt und nur lesend eingehängt; das
Keycloak-Image bleibt unverändert:

```sh
sudo mkdir -p /opt/archiv-werkzeuge/keycloak
sudo docker rm -f urinet-jq-holen 2>/dev/null
sudo docker create --platform linux/amd64 --name urinet-jq-holen \
  ghcr.io/jqlang/jq:1.7.1@sha256:096b83865ad59b5b02841f103f83f45c51318394331bf1995e187ea3be937432
sudo docker cp urinet-jq-holen:/jq /opt/archiv-werkzeuge/jq
sudo docker rm urinet-jq-holen
echo "5942c9b0934e510ee61eb3e30273f1b3fe2590df93933a93d7c58b81d19c8ff5  /opt/archiv-werkzeuge/jq" | sha256sum -c -
```
`sha256sum -c` muss `OK` melden; sonst die Datei löschen und nicht verwenden. Die Prüfsumme gilt für die Datei `jq` der
Plattform `linux/amd64` (die VPS ist x86-64); sie wurde aus genau dem oben mit Digest festgelegten Image entnommen (`docker create`,
`docker cp`, `sha256sum`; ELF x86-64, statisch). Der Digest allein sichert den Inhalt des Images; die Prüfsumme der entnommenen Datei
schliesst zusätzlich eine Verwechslung der Plattform oder eine beschädigte Übertragung aus. Eine Signatur- oder
Provenance-Prüfung des Images gibt es nicht. Wird das Image oder die Plattform gewechselt, die Prüfsumme neu aus dem lokal
gezogenen Image ermitteln und hier zusammen mit dem Digest ändern (der Digest steht auch im Echt-Test). Erreicht die VPS `ghcr.io`
nicht, die Datei auf einem anderen Rechner mit `--platform linux/amd64` auf dieselbe Weise holen, dieselbe Prüfsumme prüfen und per
`scp` übertragen. Die Skripte stammen aus dem gemergten Stand des Repos (Prüfsumme oder Commit notieren) und kommen auf demselben
Weg auf die VPS (`<vps>` ist der Zugang des Betreibers):

```sh
scp deploy/infomaniak/keycloak/{passkey-einrichten-vorlage.sh,verwaltung-einrichten-vorlage.sh,vorlage-ausfuehren.sh} \
  <vps>:/opt/archiv-werkzeuge/keycloak/
```
**Rechte für den Container.** Der Wegwerf-Container läuft als Benutzer 1000:1000 und hängt `jq` und das Verzeichnis `keycloak` nur
lesend ein. Bei einer `umask` von 077 auf der VPS (und beim `scp`) wären beide für ihn unlesbar. Darum auf der VPS nach dem
Ablegen (die Secret-Dateien sind davon **nicht** betroffen und werden nie geöffnet):

```sh
sudo chmod -R a+rX /opt/archiv-werkzeuge
sudo chmod a+rx /opt/archiv-werkzeuge/jq
ls -l "/opt/archiv-werkzeuge/jq" "/opt/archiv-werkzeuge/keycloak"
```
Meldet der Lauf später `Permission denied` oder `jq: not found`, ist fast immer dies die Ursache. Die Werkzeuge liegen in `/opt/archiv-werkzeuge`, nicht im Heimverzeichnis: Als root ist `$HOME` gleich `/root`, und das ist für Benutzer 1000
nicht durchsuchbar. Die Befehle stehen bewusst ohne Shell-Variable für den Pfad (eine eingefügte erste Zeile wurde abgeschnitten).


**Passkey-Vorlage, zuerst nur prüfen** (Exit 10 heisst: Änderungen nötig; `echo $?` zeigt den Exit-Code):

```sh
sudo docker compose --project-directory /srv/apps/archiv -f /srv/apps/archiv/compose.yml \
  run --rm --no-deps -T -l traefik.enable=false \
  -e KC_OPTS="-Duser.home=/tmp -XX:+UseSerialGC -Xmx96m -XX:TieredStopAtLevel=1 -Xss512k" \
  -v "/opt/archiv-werkzeuge/jq:/usr/local/bin/jq:ro" -v "/opt/archiv-werkzeuge/keycloak:/arbeit:ro" \
  --entrypoint bash kc \
  /arbeit/vorlage-ausfuehren.sh /arbeit/passkey-einrichten-vorlage.sh --pruefen
echo "Exit: $?"
```

**Standardaufruf auf der VPS** (Entscheid Fabian 25.11.2026, Req 10.7): Danach dasselbe ohne `--pruefen`, aber mit `--ereignisse`
(einrichten und nachprüfen), das heisst `… /arbeit/passkey-einrichten-vorlage.sh --ereignisse`; anschliessend noch einmal
`--pruefen --ereignisse` (erwartet Exit 0). Auch das erste `--pruefen` darf `--ereignisse` tragen, dann zählen die Ereignisse als
Abweichung mit. Probe: Nach einer Passkey-Einrichtung in der Kontoverwaltung erscheint `UPDATE_CREDENTIAL` im Ereignisprotokoll des
Realms. Die Admin-Konsole ist über Traefik nicht öffentlich; die Ereignisse werden über `kcadm` im Wegwerf-Container gelesen
(Hilfsskript noch nicht vorhanden, Probe offen).

**Beim ersten Lauf auf der VPS (25.11.2026, Fabian) belegt:** Ausführung auf der VPS selbst, der `docker compose run`-Aufruf,
Lesbarkeit der Secret-Datei für Benutzer 1000, Erreichbarkeit von `ghcr.io` und `jq` im Container. Der Lauf mit `--ereignisse`
endete so: Prüflauf Exit 10, Einrichten Exit 0 (18 Änderungen), Nachprüfung Exit 0; ein weiterer Lauf meldet «nichts zu tun».
Lokal belegt waren zuvor die Vorlage selbst (Stub-Test und Echt-Test gegen Keycloak 26.8.2, mit `docker run`).

**Weiter nicht belegt:** die Probe auf `UPDATE_CREDENTIAL` und `REMOVE_CREDENTIAL` im Ereignisprotokoll. Die Admin-Konsole ist nicht
öffentlich, und ein Hilfsskript, das die Ereignisse per `kcadm.sh get events` im Wegwerf-Container listet, fehlt noch.

Ist die Secret-Datei für den Benutzer 1000 nicht lesbar, meldet `vorlage-ausfuehren.sh` «Die Secret-Datei des Keycloak-Admins fehlt
oder ist leer». Die Rechte der Secrets nicht pauschal öffnen, sondern nur so anpassen, wie es der Dienst `kc` braucht.

**Verwaltungsclient und SMTP (Aufgabe 19.3)** laufen im selben Muster; zuerst, weil die Passkey-Vorlage einen funktionierenden
Realm voraussetzt. Die SMTP-Angaben sind Betriebsangaben (hier Platzhalter); `keycloak_smtp_passwort` hängt der Dienst `kc` schon
ein, `keycloak_verwaltung_secret` kommt aus der Secret-Datei des Projektverzeichnisses (Compose löst `./secrets/…` relativ dazu
auf) und muss für den Benutzer 1000 lesbar sein:

```sh
sudo docker compose --project-directory /srv/apps/archiv -f /srv/apps/archiv/compose.yml \
  run --rm --no-deps -T -l traefik.enable=false \
  -e KC_OPTS="-Duser.home=/tmp -XX:+UseSerialGC -Xmx96m -XX:TieredStopAtLevel=1 -Xss512k" \
  -e SMTP_HOST=<smtp-host> -e SMTP_PORT=<587> -e SMTP_BENUTZER=<smtp-benutzer> -e SMTP_ABSENDER=<absender-adresse> \
  -v "/opt/archiv-werkzeuge/jq:/usr/local/bin/jq:ro" -v "/opt/archiv-werkzeuge/keycloak:/arbeit:ro" \
  -v /srv/apps/archiv/secrets/keycloak_verwaltung_secret:/run/secrets/keycloak_verwaltung_secret:ro \
  --entrypoint bash kc \
  /arbeit/vorlage-ausfuehren.sh /arbeit/verwaltung-einrichten-vorlage.sh
```

Was dieses Muster leistet:

- **Anmeldung ohne Passwort im Terminal.** `vorlage-ausfuehren.sh` liest `/run/secrets/keycloak_admin` (Secret des Dienstes `kc`)
  und übergibt den Wert als `KC_CLI_PASSWORD` nur dem einen `kcadm.sh config credentials`-Prozess. Es steht weder im Befehl (Terminal,
  Verlauf, Prozessliste des Hosts) noch in der Compose-Definition noch in `docker inspect`. `kcadm.sh` verlangt ohne Terminal sonst
  `--password` im Argument; die Eingabe über Standardeingabe funktionierte in 26.8.2 ohne Terminal nicht. Anmeldung: Realm
  `master`, Benutzer `archiv-admin`, Server `http://kc:8080` (intern; die Admin-Konsole bleibt für Traefik gesperrt).
- **`-Duser.home=/tmp`:** Das Dateisystem ist schreibgeschützt; die `kcadm`-Konfiguration (mit Token) liegt im tmpfs `/tmp` und
  verschwindet mit dem Container. `$HOME` genügt nicht, `kcadm` nimmt `user.home` der JVM.
- **Speicher:** Der Container erbt `mem_limit: 768m` des Dienstes `kc` zusätzlich zum laufenden Keycloak. `KC_OPTS` (nur `kcadm.sh`
  liest `KC_OPTS`, nicht `JAVA_OPTS`) hält den Heap bei 96 MiB. Jeder `kcadm`-Aufruf startet eine JVM: Lokal dauerten alle Läufe des Echt-Tests (rund ein Dutzend)
  zusammen rund eineinhalb Minuten, auf der VPS (`cpus: 0.6`) länger; nicht gleichzeitig mit einer Speichermessung (Aufgabe 1.10) oder einem Deploy starten.
- **Keine Geheimnisse in der Ausgabe.** Die Vorlagen schreiben nur Meldungen über Flows, Rollen und Einstellungen; temporäre Dateien
  liegen mit `umask 077` in einem `mktemp`-Verzeichnis, das beim Ende entfernt wird.
- **Voraussetzungen:** Der Dienst `kc` läuft und ist gesund (Namensauflösung `kc` im Netz `intern`); `ARCHIV_BASIS_URL` kommt wie für
  `kc` aus der `.env` des Projekts. Das Admin-Passwort in `keycloak_admin` muss noch das des Benutzers `archiv-admin` sein.

Nach dem Lauf: `docker ps -a` zeigt keinen übriggebliebenen Container (`--rm`); bei einem Abbruch mit `docker rm -f <name>` nur den
eigenen Wegwerf-Container entfernen.

Tests (lokal, ausschliesslich synthetische Werte):

```bash
bash -n deploy/infomaniak/keycloak/passkey-einrichten-vorlage.sh
bash deploy/infomaniak/keycloak/test/passkey-einrichten-vorlage-test.sh          # kcadm-Stub, kein Docker
bash deploy/infomaniak/keycloak/test/passkey-einrichten-keycloak-test.sh         # Wegwerf-Keycloak mit dem Realm VOR 24.1
```

Der zweite Test importiert den Realm-Export vor Aufgabe 24.1 (`git show`), führt die Vorlage im Muster oben aus (nicht-root,
schreibgeschützt, `jq` eingehängt, `KC_OPTS`), prüft Richtlinie, Flow, Required Actions und Kontoverwaltung, die Wiederholung
(`--pruefen` Exit 0, kein Eingriff), `--ereignisse` und den Abbruch bei unerwartetem Zustand und führt auch die Vorlage 19.3 aus.
Mit `PASSKEY_PLAYWRIGHT_VERZEICHNIS=<Verzeichnis mit node_modules/playwright>` richtet zusätzlich ein virtueller Authenticator
(Chromium) in der Kontoverwaltung einen Passkey ein und meldet sich damit ohne Passwort und TOTP an. Mit `URINET_TEST_TMP` (unter
macOS mit Colima im Home-Verzeichnis) und `KEYCLOAK_CLIENT_IMAGE` (z. B. ein aus dem Dockerfile gebautes Image) anpassbar. Beide
Tests laufen nicht in der CI.

## Nachbesserung Review (PR #334)

- Die Vorlage ist wiederholbar: Flows, Ausführungen und Provider werden vor dem Anlegen geprüft, ein vorhandener Provider
  wird aktualisiert. Die Rollensperre `manage-account-links` steht am Anfang.
- «Verknüpfung nur durch den Administrator» ist eine vorläufige Voreinstellung bis zum Entscheid zu Req 2.27 (O.8).
- Verknüpfen und Lösen durch den Administrator erscheinen im Admin-Ereignisprotokoll (`adminEventsEnabled`), nicht als
  Benutzerereignisse `FEDERATED_IDENTITY_LINK` und `REMOVE_FEDERATED_IDENTITY`. Fehlversuche des zweiten Faktors stehen
  als `LOGIN_ERROR` im Protokoll.
- Ein dedizierter Cucumber-Runner prüft unbekannte Konten, eine nicht automatisch verknüpfte gleiche E-Mail-Adresse,
  TOTP nach einer bestehenden Verknüpfung sowie Admin-Ereignisse und Sitzungsende nach dem Lösen gegen einen lokalen
  `oidc`-Test-IdP.
- `eventsListeners` ist in Realm-Export und Vorlage gleich (`[]`, Ereignisse nur in der Datenbank); `eventsExpiration` und das
  Realm-Attribut `adminEventsExpiration` stehen in Export und Vorlage auf 400 Tage (34'560'000 s, Spec 06 Req 9).
- `start.sh` setzt fehlende `google_client_id` und `google_client_secret` auf leer; das gilt nur bei deaktiviertem Provider.
  Mit der Freigabe werden die Dateien Pflicht (offen für Fabian, Workflow-Prüfung). Der Start-Stub-Test prüft beide leeren
  Fälle und beide synthetischen Werte; er läuft nicht in der CI (Workflow-Änderung liegt bei Fabian).
- Der Stub-Test der Vorlage läuft nicht in der CI und wird von Hand gestartet.
