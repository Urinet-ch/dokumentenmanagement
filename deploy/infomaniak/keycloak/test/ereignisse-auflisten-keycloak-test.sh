#!/usr/bin/env bash
# Echt-Test der Vorlage ereignisse-auflisten-vorlage.sh gegen ein Wegwerf-Keycloak (Aufgabe 24.3, Ereignis-Probe):
# Importiert den Realm-Export VOR Aufgabe 24.1, aktiviert die Passkey-Ereignisse mit passkey-einrichten-vorlage.sh --ereignisse,
# erzeugt Benutzerereignisse und listet sie im Muster der VPS (nicht-root, schreibgeschützt, jq eingehängt).
#  - Immer: LOGIN (Direktzugriff eines synthetischen Benutzers) belegt Filter und Datensparsamkeit.
#  - Mit PASSKEY_PLAYWRIGHT_VERZEICHNIS=<Verzeichnis mit node_modules/playwright>: ein virtueller Authenticator richtet einen
#    Passkey ein; das echte Ereignis UPDATE_CREDENTIAL wird gelistet.
# Ausschliesslich synthetische Werte; eigene Container und ein eigenes Netz, die am Ende entfernt werden.
set -euo pipefail

root=$(cd "$(dirname "$0")/../../../.." && pwd)
# Das Arbeitsverzeichnis wird in Container eingehängt (unter macOS mit Colima gilt das für das Home-Verzeichnis).
basis=${URINET_TEST_TMP:-${TMPDIR:-/tmp}}
mkdir -p -- "$basis"
temp=$(mktemp -d "${basis%/}/urinet-24.3e.XXXXXX")
kennung=$$
netz="urinet-24-3e-netz-$kennung"
server="urinet-24-3e-server-$kennung"
client="urinet-24-3e-client-$kennung"
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
  docker rm -f "urinet-24-3e-jq-$kennung" >/dev/null 2>&1 || true
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
cp "$root"/deploy/infomaniak/keycloak/{passkey-einrichten-vorlage.sh,ereignisse-auflisten-vorlage.sh,vorlage-ausfuehren.sh} "$temp/arbeit/"
chmod -R a+rX "$temp/arbeit"

# jq als statische Datei aus dem Hilfs-Image (das Keycloak-Image hat kein jq)
docker create --name "urinet-24-3e-jq-$kennung" "$jq_image" >/dev/null
docker cp "urinet-24-3e-jq-$kennung:/jq" "$temp/jq"
docker rm -f "urinet-24-3e-jq-$kennung" >/dev/null
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

vorlage() { # <Skript> <Argumente …>
  local skript=$1
  shift
  docker exec -e ARCHIV_PASSKEY_RP_ID="$rp_id" "$client" bash /arbeit/vorlage-ausfuehren.sh "/arbeit/$skript" "$@"
}
auflisten() { vorlage ereignisse-auflisten-vorlage.sh "$@"; }

# 1. Vor der Aktivierung: Der Realm führt die Passkey-Typen noch nicht; die Vorlage sagt das und liest nichts.
vorlage passkey-einrichten-vorlage.sh --ereignisse > "$temp/einrichten.log" 2>&1 || { cat "$temp/einrichten.log" >&2; echo "Einrichtung gescheitert." >&2; exit 1; }
kcadm update realms/urinet-archiv -s 'enabledEventTypes=["LOGIN_ERROR"]' >/dev/null
status=0
auflisten > "$temp/ohne-typ.log" 2>&1 || status=$?
[[ $status == 1 ]] && grep -q '^Abbruch: Im Realm nicht aktiviert (enabledEventTypes): UPDATE_CREDENTIAL, REMOVE_CREDENTIAL' "$temp/ohne-typ.log" ||
  { cat "$temp/ohne-typ.log" >&2; echo "Erwartet Exit 1 für nicht aktivierte Typen, erhalten $status." >&2; exit 1; }
kcadm update realms/urinet-archiv -s eventsEnabled=false >/dev/null
status=0
auflisten --typ LOGIN_ERROR > "$temp/aus.log" 2>&1 || status=$?
[[ $status == 1 ]] && grep -q '^Abbruch: Die Ereignisse des Realms sind ausgeschaltet' "$temp/aus.log"
kcadm update realms/urinet-archiv -s eventsEnabled=true >/dev/null
vorlage passkey-einrichten-vorlage.sh --ereignisse > "$temp/einrichten-2.log" 2>&1
echo "1. Nicht aktivierte Typen und ausgeschaltete Ereignisse: Exit 1 mit Meldung."

# 2. Noch keine Passkey-Ereignisse: Exit 3
status=0
auflisten > "$temp/leer.log" 2>&1 || status=$?
[[ $status == 3 ]] && grep -q '^Keine Ereignisse gefunden' "$temp/leer.log" ||
  { cat "$temp/leer.log" >&2; echo "Erwartet Exit 3, erhalten $status." >&2; exit 1; }
echo "2. Keine Passkey-Ereignisse vorhanden: Exit 3."

# 3. LOGIN durch Direktzugriff eines synthetischen Benutzers (userId gesetzt; Benutzername, IP-Adresse und Details stehen in den Rohdaten)
kcadm update realms/urinet-archiv -s 'enabledEventTypes=["LOGIN","LOGIN_ERROR","UPDATE_CREDENTIAL","REMOVE_CREDENTIAL"]' >/dev/null
base="http://localhost:$port"
admintoken() {
  curl -fsS -d client_id=admin-cli -d grant_type=password -d username=archiv-admin --data-urlencode "password=$(< "$temp/admin-passwort")" \
    "$base/realms/master/protocol/openid-connect/token" | jq -er .access_token
}
token=$(admintoken)
name="ereignis-$kennung"
otp_geheimnis="synthetisch-otp-geheimnis"
PASSWORT='Synthetisch-Passwort-1'
totp() { # sechsstelliger Code zum aktuellen 30-Sekunden-Schritt (wie in passkey-browser-pruefung.mjs)
  python3 -c 'import hmac, hashlib, struct, sys, time
h = hmac.new(sys.argv[1].encode(), struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
o = h[-1] & 15
print("%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000))' "$otp_geheimnis"
}
testclient="ereignis-client"
curl -fsS -o /dev/null -H "authorization: Bearer $token" -H 'content-type: application/json' \
  -d "{\"clientId\":\"$testclient\",\"publicClient\":true,\"directAccessGrantsEnabled\":true,\"standardFlowEnabled\":false}" \
  "$base/admin/realms/urinet-archiv/clients"
jq -n --arg n "$name" --arg g "$otp_geheimnis" --arg p "$PASSWORT" '{ifResourceExists: "FAIL", users: [{username: $n, enabled: true, email: ($n + "@example.invalid"),
  emailVerified: true, firstName: "Test", lastName: "Benutzer", attributes: {mandant: ["urinet"]}, realmRoles: ["default-roles-urinet-archiv"],
  requiredActions: [], credentials: [{type: "password", value: $p, temporary: false},
  {type: "otp", userLabel: "Test", secretData: ({value: $g} | tojson),
   credentialData: ({subType: "totp", digits: 6, counter: 0, period: 30, algorithm: "HmacSHA1"} | tojson)}]}]}' > "$temp/benutzer.json"
curl -fsS -o /dev/null -H "authorization: Bearer $token" -H 'content-type: application/json' -d @"$temp/benutzer.json" \
  "$base/admin/realms/urinet-archiv/partialImport"
benutzer_id=$(curl -fsS -H "authorization: Bearer $token" "$base/admin/realms/urinet-archiv/users?exact=true&username=$name" | jq -er '.[0].id')
curl -sS -d client_id=$testclient -d grant_type=password -d "username=$name" --data-urlencode "password=$PASSWORT" -d "totp=$(totp)" "$base/realms/urinet-archiv/protocol/openid-connect/token" | tee "$temp/grant.json" | jq -e "has(\"access_token\")" >/dev/null || { cat "$temp/grant.json" >&2; docker logs --tail 25 "$server" 2>&1 | grep -E "ERROR|Caused|Exception" | head -8 >&2; echo "Direktzugriff fehlgeschlagen." >&2; exit 1; }
curl -sS -o /dev/null -d client_id=$testclient -d grant_type=password -d "username=$name" --data-urlencode "password=falsch-$PASSWORT" "$base/realms/urinet-archiv/protocol/openid-connect/token" || true
auflisten --typ LOGIN > "$temp/login-error.log" 2>&1 || { cat "$temp/login-error.log" >&2; echo "Auflisten LOGIN gescheitert." >&2; exit 1; }
cat "$temp/login-error.log"
grep -Eq "^20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z +LOGIN +$benutzer_id +$testclient$" "$temp/login-error.log"
grep -q '^Summe LOGIN: 1$' "$temp/login-error.log"
# Datensparsamkeit gegen echte Ereignisse: Die Rohdaten enthalten Benutzername und IP-Adresse; die Ausgabe nie.
curl -fsS -H "authorization: Bearer $token" "$base/admin/realms/urinet-archiv/events?type=LOGIN" > "$temp/roh.json"
jq -e --arg id "$benutzer_id" 'any(.[]; .userId == $id and ((.ipAddress // "") != "") and (.details | tostring | contains("ereignis-")))' "$temp/roh.json" >/dev/null
ip=$(jq -er '.[0].ipAddress' "$temp/roh.json")
for verboten in "$name" example.invalid "$ip" ipAddress details username; do
  ! grep -qF -- "$verboten" "$temp/login-error.log" || { echo "Ausgabe enthält «$verboten»." >&2; exit 1; }
done
echo "3. LOGIN: Zeit, Typ, userId, Client; kein Benutzername, keine E-Mail, keine IP-Adresse, keine Details."

# 4. Filter: Der Standardaufruf (Passkey-Typen) zeigt das LOGIN nicht und meldet Exit 3; Summenzeilen je Typ
status=0
auflisten --typ UPDATE_CREDENTIAL --typ REMOVE_CREDENTIAL > "$temp/filter.log" 2>&1 || status=$?
[[ $status == 3 ]] && ! grep -q "$benutzer_id" "$temp/filter.log"
auflisten --typ LOGIN --typ LOGIN_ERROR --max 1 > "$temp/max.log"
[[ $(grep -Ec '^20[0-9]{2}-' "$temp/max.log") == 1 ]] && grep -q '^Hinweis: Höchstzahl 1 erreicht' "$temp/max.log"
status=0
auflisten --typ login > /dev/null 2>&1 || status=$?
[[ $status == 2 ]]
echo "4. Filter, --max und ungültiger Typ (Exit 2) im Wegwerf-Container."

# 5. Echtes Passkey-Ereignis (nur mit Playwright)
if [[ -n ${PASSKEY_PLAYWRIGHT_VERZEICHNIS:-} ]]; then
  node "$root/deploy/infomaniak/keycloak/test/passkey-browser-pruefung.mjs" "$base" "$PASSKEY_PLAYWRIGHT_VERZEICHNIS" > "$temp/browser.log"
  auflisten > "$temp/passkey.log" 2>&1 || { cat "$temp/passkey.log" >&2; echo "Erwartet Exit 0 nach der Passkey-Einrichtung." >&2; exit 1; }
  cat "$temp/passkey.log"
  grep -Eq '^20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z +UPDATE_CREDENTIAL +[0-9a-f-]{36} +account-console$' "$temp/passkey.log"
  grep -Eq '^Summe UPDATE_CREDENTIAL: [1-9]' "$temp/passkey.log"
  token=$(admintoken)
  curl -fsS -H "authorization: Bearer $token" "$base/admin/realms/urinet-archiv/events?type=UPDATE_CREDENTIAL" > "$temp/roh-passkey.json"
  ip=$(jq -er '.[0].ipAddress' "$temp/roh-passkey.json")
  ! grep -qF -- "$ip" "$temp/passkey.log" || { echo "IP-Adresse in der Ausgabe." >&2; exit 1; }
  ! grep -q 'passkey-' "$temp/passkey.log" || { echo "Benutzername in der Ausgabe." >&2; exit 1; }
  echo "5. Passkey eingerichtet (virtueller Authenticator): UPDATE_CREDENTIAL wird gelistet, Exit 0."
else
  echo "5. Passkey-Ereignis übersprungen (PASSKEY_PLAYWRIGHT_VERZEICHNIS nicht gesetzt)."
fi
token=$(admintoken)
curl -fsS -o /dev/null -X DELETE -H "authorization: Bearer $token" "$base/admin/realms/urinet-archiv/users/$benutzer_id" || true
echo "Ereignis-Vorlage gegen ein Wegwerf-Keycloak geprüft."
