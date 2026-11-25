#!/usr/bin/env bash
# Echt-Test der Vorlage passkey-einrichten-vorlage.sh gegen ein Wegwerf-Keycloak (Aufgabe 24.3, Vorbereitung):
# Importiert den Realm-Export VOR Aufgabe 24.1, zieht die Passkey-Änderungen per Vorlage nach, prüft den Zustand und die
# Wiederholung. Ausschliesslich synthetische Werte; eigene Container und ein eigenes Netz, die am Ende entfernt werden.
# Voraussetzungen: Docker, jq, git-Historie mit dem Commit vor 24.1; das jq-Hilfs-Image wird einmal geladen.
set -euo pipefail

root=$(cd "$(dirname "$0")/../../../.." && pwd)
# Das Arbeitsverzeichnis wird in Container eingehängt (unter macOS mit Colima gilt das für das Home-Verzeichnis).
basis=${URINET_TEST_TMP:-${TMPDIR:-/tmp}}
mkdir -p -- "$basis"
temp=$(mktemp -d "${basis%/}/urinet-24.3.XXXXXX")
kennung=$$
netz="urinet-24-3-netz-$kennung"
server="urinet-24-3-server-$kennung"
client="urinet-24-3-client-$kennung"
server_image=${KEYCLOAK_TEST_IMAGE:-quay.io/keycloak/keycloak:26.8.2}
# Die Vorlage läuft wie auf der VPS im Image der Distribution (Standard: das gleiche Image wie der Server).
client_image=${KEYCLOAK_CLIENT_IMAGE:-$server_image}
jq_image=${JQ_HILFS_IMAGE:-ghcr.io/jqlang/jq:1.7.1@sha256:096b83865ad59b5b02841f103f83f45c51318394331bf1995e187ea3be937432}
vor_24_1=${PASSKEY_STAND_VOR_24_1:-f78cf1c162db554fe7de3fe48440016487157fed^}
# Die Relying-Party-ID «localhost» erlaubt dem Browser-Test (Chromium, http) die Passkey-Einrichtung.
rp_id=localhost
port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
aufraeumen() {
  local status=$?
  docker rm -f "$client" "$server" >/dev/null 2>&1 || true
  docker network rm "$netz" >/dev/null 2>&1 || true
  docker rm -f "urinet-24-3-jq-$kennung" >/dev/null 2>&1 || true
  rm -rf -- "$temp"
  exit "$status"
}
trap aufraeumen EXIT

# Realm aus dem Stand vor 24.1 (Platzhalter löst Keycloak beim Import aus der Umgebung auf)
mkdir -p "$temp/import" "$temp/arbeit"
git -C "$root" show "$vor_24_1:deploy/keycloak/realm-export.json" > "$temp/import/realm-export.json"
jq -e '(.webAuthnPolicyPasswordlessRpId // null) == null and (.requiredActions | map(.alias) | index("webauthn-register-passwordless") | not)' \
  "$temp/import/realm-export.json" >/dev/null || { echo "Der Stand vor 24.1 enthält schon Passkey-Einstellungen." >&2; exit 1; }
chmod -R a+rX "$temp/import"
cp "$root"/deploy/infomaniak/keycloak/{passkey-einrichten-vorlage.sh,verwaltung-einrichten-vorlage.sh,vorlage-ausfuehren.sh} "$temp/arbeit/"
chmod -R a+rX "$temp/arbeit"

# jq als statische Datei aus dem Hilfs-Image (das Keycloak-Image hat kein jq)
docker create --name "urinet-24-3-jq-$kennung" "$jq_image" >/dev/null
docker cp "urinet-24-3-jq-$kennung:/jq" "$temp/jq"
docker rm -f "urinet-24-3-jq-$kennung" >/dev/null
chmod 755 "$temp/jq"

docker network create "$netz" >/dev/null
printf '%s\n' 'synthetisch-test' > "$temp/admin-passwort"
printf '%s' 'synthetisch-verwaltung' > "$temp/verwaltung"
printf '%s' 'synthetisch-smtp' > "$temp/smtp"
chmod 644 "$temp/admin-passwort" "$temp/verwaltung" "$temp/smtp"
docker run -d --name "$server" --network "$netz" --network-alias kc \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=archiv-admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=synthetisch-test \
  -p "127.0.0.1:$port:8080" -e KC_HOSTNAME="http://localhost:$port" -e KC_HTTP_ENABLED=true -e KC_PROXY_HEADERS=xforwarded \
  -e ARCHIV_BASIS_URL="https://$rp_id" -e GOOGLE_CLIENT_ID= -e GOOGLE_CLIENT_SECRET= \
  -v "$temp/import:/opt/keycloak/data/import:ro" \
  "$server_image" start-dev --import-realm >/dev/null

# Wegwerf-Container wie auf der VPS: nicht-root, schreibgeschütztes Dateisystem, begrenzter Speicher, ohne Rechte
docker run -d --name "$client" --network "$netz" --user 1000:1000 --read-only --tmpfs /tmp:size=32m \
  --cap-drop ALL --security-opt no-new-privileges --memory 768m \
  -e KC_OPTS="-Duser.home=/tmp -XX:+UseSerialGC -Xmx96m -XX:TieredStopAtLevel=1" \
  -e ARCHIV_BASIS_URL="https://$rp_id" \
  -v "$temp/jq:/usr/local/bin/jq:ro" -v "$temp/arbeit:/arbeit:ro" -v "$temp/admin-passwort:/run/secrets/keycloak_admin:ro" \
  -v "$temp/verwaltung:/run/secrets/keycloak_verwaltung_secret:ro" -v "$temp/smtp:/run/secrets/keycloak_smtp_passwort:ro" \
  --entrypoint bash "$client_image" -c 'while sleep 60; do :; done' >/dev/null
docker exec "$client" jq --version >/dev/null

# Anmeldung: das Passwort kommt aus der Secret-Datei in die Umgebung des einen kcadm-Prozesses, nicht in Argumente
anmelden() {
  docker exec "$client" bash -c \
    'KC_CLI_PASSWORD=$(< /run/secrets/keycloak_admin) exec /opt/keycloak/bin/kcadm.sh config credentials --server http://kc:8080 --realm master --user archiv-admin' \
    >/dev/null 2>"$temp/anmeldung.log"
}
bereit=false
for _ in {1..60}; do
  if anmelden; then bereit=true; break; fi
  sleep 2
done
if [[ $bereit != true ]]; then
  echo "Der Wegwerf-Keycloak wurde nicht bereit." >&2
  tail -n 3 "$temp/anmeldung.log" >&2 || true
  exit 1
fi
kcadm() { docker exec "$client" /opt/keycloak/bin/kcadm.sh "$@"; }
# Aufruf wie in der README: Hilfsskript meldet kcadm an (Passwort aus der Secret-Datei) und führt die Vorlage aus.
# Die Relying-Party-ID leitet es aus ARCHIV_BASIS_URL ab; RP_ID_TEST überschreibt sie für die Eingabeprüfung.
vorlage() {
  local umgebung=()
  [[ -z ${RP_ID_TEST+x} ]] || umgebung=(-e "ARCHIV_PASSKEY_RP_ID=$RP_ID_TEST")
  docker exec ${umgebung[@]+"${umgebung[@]}"} "$client" bash /arbeit/vorlage-ausfuehren.sh /arbeit/passkey-einrichten-vorlage.sh "$@"
}

# 1. Alter Realm: --pruefen meldet Abweichungen (Exit 10) und ändert nichts
status=0
vorlage --pruefen > "$temp/pruefen-1.log" 2>&1 || status=$?
[[ $status == 10 ]] || { cat "$temp/pruefen-1.log" >&2; echo "Erwartet Exit 10 vor der Einrichtung, erhalten $status." >&2; exit 1; }
grep -q '^Abweichung: ' "$temp/pruefen-1.log"
! grep -q '^Angepasst: ' "$temp/pruefen-1.log"
kcadm get realms/urinet-archiv --fields webAuthnPolicyPasswordlessRpId --format csv --noquotes | grep -qx ''
kcadm get authentication/flows/browser-totp-pflicht-formulare/executions -r urinet-archiv --fields providerId --format csv --noquotes |
  grep -qx 'auth-otp-form'
echo "1. --pruefen vor der Einrichtung: Exit 10, nichts geändert."

# 2. Falsche RP-ID und Aufruffehler
status=0
RP_ID_TEST='https://archiv.example.invalid:8443' vorlage --pruefen > "$temp/rp.log" 2>&1 || status=$?
[[ $status == 1 ]] && grep -q 'kein Host' "$temp/rp.log"
status=0
vorlage --unbekannt > /dev/null 2>&1 || status=$?
[[ $status == 2 ]]

# 3. Einrichtung
vorlage > "$temp/lauf-1.log" 2>&1 || { cat "$temp/lauf-1.log" >&2; echo "Die Einrichtung ist gescheitert." >&2; exit 1; }
grep -q 'eingerichtet' "$temp/lauf-1.log"
echo "2. Einrichtung erfolgreich."

# 4. Zustand wie im aktuellen Export
kcadm get realms/urinet-archiv > "$temp/realm.json"
jq -e --arg rp "$rp_id" '.webAuthnPolicyPasswordlessRpEntityName == "UriNet Archiv" and
  .webAuthnPolicyPasswordlessRpId == $rp and .webAuthnPolicyPasswordlessAttestationConveyancePreference == "none" and
  .webAuthnPolicyPasswordlessResidentKey == "required" and .webAuthnPolicyPasswordlessUserVerificationRequirement == "required" and
  .webAuthnPolicyPasswordlessPasskeysEnabled == true and .webAuthnPolicyPasswordlessMediation == "conditional" and
  .browserFlow == "browser-totp-pflicht"' "$temp/realm.json" >/dev/null
kcadm get authentication/required-actions -r urinet-archiv > "$temp/aktionen.json"
jq -e 'any(.[]; .alias == "webauthn-register-passwordless" and .enabled and (.defaultAction | not) and .priority == 60) and
  any(.[]; .alias == "delete_credential" and .enabled and (.defaultAction | not) and .priority == 70)' "$temp/aktionen.json" >/dev/null
kcadm get authentication/flows/browser-totp-pflicht-formulare/executions -r urinet-archiv > "$temp/formulare.json"
jq -e '[.[] | select(.level == 0)] | map([.displayName, .requirement, .priority]) ==
  [["Username Password Form", "REQUIRED", 10], ["browser-passkey-otp", "CONDITIONAL", 20]]' "$temp/formulare.json" >/dev/null
jq -e '[.[] | select(.level == 1)] | map([.providerId, .requirement, .priority]) ==
  [["conditional-credential", "REQUIRED", 10], ["auth-otp-form", "REQUIRED", 20]]' "$temp/formulare.json" >/dev/null
jq -e 'any(.[]; .providerId == "auth-otp-form" and .level == 0) | not' "$temp/formulare.json" >/dev/null
konfig=$(jq -er '.[] | select(.providerId == "conditional-credential") | .authenticationConfig' "$temp/formulare.json")
kcadm get "authentication/config/$konfig" -r urinet-archiv |
  jq -e '.alias == "otp-nur-ohne-passkey" and .config.credentials == "webauthn-passwordless" and .config.included == "false"' >/dev/null
konsole=$(kcadm get clients -r urinet-archiv -q clientId=account-console --fields id --format csv --noquotes)
konto=$(kcadm get clients -r urinet-archiv -q clientId=account --fields id --format csv --noquotes)
kcadm get "clients/$konsole/scope-mappings/clients/$konto" -r urinet-archiv | jq -e '[.[].name] | sort == ["manage-account", "view-profile"]' >/dev/null
# Ereignisse bleiben ohne --ereignisse unverändert
kcadm get realms/urinet-archiv --fields enabledEventTypes | jq -e '.enabledEventTypes | index("UPDATE_CREDENTIAL") | not' >/dev/null
echo "3. Realm-Zustand stimmt mit dem Export überein."

# 5. Wiederholung ändert nichts
vorlage --pruefen > "$temp/pruefen-2.log" 2>&1 || { cat "$temp/pruefen-2.log" >&2; echo "--pruefen nach der Einrichtung hat Abweichungen." >&2; exit 1; }
vorlage > "$temp/lauf-2.log" 2>&1
! grep -q '^Angepasst: ' "$temp/lauf-2.log"
grep -q 'nichts zu tun' "$temp/lauf-2.log"
echo "4. Wiederholung: --pruefen Exit 0, kein Eingriff."

# 6. Ereignisse nur mit der ausdrücklichen Option
# Ereignisse ausgeschaltet oder leere Typenliste: Abbruch ohne Änderung (die Vorlage rät nicht)
kcadm get realms/urinet-archiv --fields enabledEventTypes > "$temp/typen-vorher.json"
kcadm update realms/urinet-archiv -s eventsEnabled=false >/dev/null
status=0
vorlage --ereignisse > "$temp/ereignisse-aus.log" 2>&1 || status=$?
[[ $status == 1 ]] && grep -q '^Abbruch: Die Ereignisse des Realms sind ausgeschaltet' "$temp/ereignisse-aus.log"
kcadm update realms/urinet-archiv -s eventsEnabled=true >/dev/null
kcadm update realms/urinet-archiv -s 'enabledEventTypes=[]' >/dev/null
# Verhalten von Keycloak 26.8.2 bei leerer Liste: bleibt sie leer oder füllt Keycloak Vorgaben ein? Die Vorlage bricht nur bei leerer Liste ab.
kcadm get realms/urinet-archiv --fields enabledEventTypes | jq -c '"Liste nach Setzen von []: \(.enabledEventTypes | length) Typ(en)"' -r
status=0
vorlage --ereignisse > "$temp/ereignisse-leer.log" 2>&1 || status=$?
if kcadm get realms/urinet-archiv --fields enabledEventTypes | jq -e '(.enabledEventTypes // []) | length == 0' >/dev/null; then
  [[ $status == 1 ]] && grep -q '^Abbruch: Die Liste enabledEventTypes ist leer' "$temp/ereignisse-leer.log"
  echo "5a. Leere Typenliste (bleibt in 26.8.2 leer): Abbruch, nichts geändert."
else
  echo "5a. Keycloak 26.8.2 füllt eine leere Typenliste selbst auf; der Abbruch bei leerer Liste greift dort nicht."
fi
kcadm update realms/urinet-archiv -s "enabledEventTypes=$(jq -c '.enabledEventTypes' "$temp/typen-vorher.json")" >/dev/null

vorlage --pruefen --ereignisse > "$temp/ereignisse-pruefen.log" 2>&1 && { echo "--ereignisse --pruefen hätte Abweichungen melden müssen." >&2; exit 1; } || [[ $? == 10 ]]
vorlage --ereignisse > "$temp/ereignisse.log" 2>&1
kcadm get realms/urinet-archiv --fields enabledEventTypes |
  jq -e '.enabledEventTypes | index("UPDATE_CREDENTIAL") != null and index("REMOVE_CREDENTIAL") != null and index("LOGIN_ERROR") != null' >/dev/null
vorlage --pruefen --ereignisse >/dev/null
echo "5. Ereignisse: nur mit --ereignisse, bestehende Typen bleiben."

# 7. Unerwarteter Zustand: Abbruch statt Raten (anderer browserFlow)
kcadm update realms/urinet-archiv -s browserFlow=browser >/dev/null
status=0
vorlage --pruefen > "$temp/abbruch.log" 2>&1 || status=$?
[[ $status == 1 ]] && grep -q '^Abbruch: ' "$temp/abbruch.log"
kcadm update realms/urinet-archiv -s browserFlow=browser-totp-pflicht >/dev/null
vorlage --pruefen >/dev/null
echo "6. Unerwarteter Zustand: Abbruch mit Meldung."

# 7. Dasselbe Aufrufmuster für die Vorlage 19.3 (Verwaltungsclient, SMTP): Secret-Dateien und SMTP-Angaben als Platzhalter
verwaltung() {
  docker exec -e SMTP_HOST=mail.example.invalid -e SMTP_PORT=587 -e SMTP_BENUTZER=test -e SMTP_ABSENDER=test@example.invalid \
    "$client" bash /arbeit/vorlage-ausfuehren.sh /arbeit/verwaltung-einrichten-vorlage.sh
}
verwaltung > "$temp/verwaltung-1.log" 2>&1 || { cat "$temp/verwaltung-1.log" >&2; echo "Vorlage 19.3 im Wegwerf-Container gescheitert." >&2; exit 1; }
verwaltung > /dev/null 2>&1
kcadm get realms/urinet-archiv | jq -e '.smtpServer.host == "mail.example.invalid" and .passwordPolicy != null' >/dev/null
vorlage --pruefen > /dev/null
echo "7. Vorlage 19.3 im gleichen Aufrufmuster zweimal ausgeführt; Passkey-Zustand unverändert."

# 8. Passkey-Registrierung und Anmeldung im Browser (virtueller Authenticator), wenn Playwright verfügbar ist
if [[ -n ${PASSKEY_PLAYWRIGHT_VERZEICHNIS:-} ]]; then
  node "$root/deploy/infomaniak/keycloak/test/passkey-browser-pruefung.mjs" "http://localhost:$port" "$PASSKEY_PLAYWRIGHT_VERZEICHNIS"
  echo "8. Browser: Passkey eingerichtet und angemeldet, Rückfall auf Passwort und TOTP."
else
  echo "8. Browser-Prüfung übersprungen (PASSKEY_PLAYWRIGHT_VERZEICHNIS nicht gesetzt)."
fi
echo "Passkey-Vorlage gegen einen frisch importierten alten Realm geprüft."
