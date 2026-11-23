#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../../../.." && pwd)
# Das Arbeitsverzeichnis wird in den kcadm-Container eingehängt: Es muss für Docker erreichbar sein (unter macOS mit
# Colima gilt das für das Home-Verzeichnis, nicht für /tmp); überschreibbar mit URINET_TEST_TMP.
basis=${URINET_TEST_TMP:-${TMPDIR:-/tmp}}
mkdir -p -- "$basis"
temp=$(mktemp -d "${basis%/}/urinet-19.3.XXXXXX")
server="urinet-19-3-server-$$"
client="urinet-19-3-client-$$"
image=${KEYCLOAK_TEST_IMAGE:-quay.io/keycloak/keycloak:26.8.2}
aufraeumen() {
  local status=$?
  if (( status != 0 )) && [[ -s "$temp/kcadm-fehler" ]]; then
    echo "Synthetischer kcadm-Test: $(<"$temp/kcadm-aufruf")" >&2
    cat "$temp/kcadm-fehler" >&2
  fi
  docker rm -f "$client" "$server" >/dev/null 2>&1 || true
  rm -rf -- "$temp"
}
trap aufraeumen EXIT

docker run -d --name "$server" -e KC_BOOTSTRAP_ADMIN_USERNAME=testadmin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=synthetisch-test "$image" start-dev --http-host=127.0.0.1 >/dev/null
docker run -d --name "$client" --network "container:$server" --user 0:0 \
  -v "$temp:$temp" --entrypoint /bin/bash "$image" -c 'while sleep 60; do :; done' >/dev/null

cat > "$temp/kcadm" <<'EOF'
#!/usr/bin/env bash
printf '%s %s\n' "${1:-}" "${2:-}" > "$TEST_KCADM_AUFRUF"
docker exec -i "$TEST_KCADM_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@" 2>"$TEST_KCADM_FEHLER"
EOF
chmod 700 "$temp/kcadm"
export TEST_KCADM_CONTAINER=$client
export TEST_KCADM_AUFRUF="$temp/kcadm-aufruf" TEST_KCADM_FEHLER="$temp/kcadm-fehler"
bereit=false
for versuch in {1..60}; do
  if "$temp/kcadm" config credentials --server http://127.0.0.1:8080 --realm master \
      --user testadmin --password synthetisch-test >"$temp/anmeldung.log" 2>&1; then
    bereit=true
    break
  fi
  sleep 2
done
if [[ $bereit != true ]]; then
  echo "Der synthetische Keycloak-Testserver wurde nicht bereit." >&2
  exit 1
fi

"$temp/kcadm" create realms -s realm=urinet-archiv -s enabled=true >/dev/null
printf '%s' 'synthetisch-verwaltung' > "$temp/verwaltung"
printf '%s' 'synthetisch-smtp' > "$temp/smtp"
export KCADM="$temp/kcadm" TMPDIR="$temp"
export KEYCLOAK_VERWALTUNG_SECRET_DATEI="$temp/verwaltung" KEYCLOAK_SMTP_PASSWORT_DATEI="$temp/smtp"
export SMTP_HOST=mail.example.invalid SMTP_PORT=587 SMTP_BENUTZER=test SMTP_ABSENDER=test@example.invalid
vorlage="$root/deploy/infomaniak/keycloak/verwaltung-einrichten-vorlage.sh"
bash "$vorlage"
bash "$vorlage"

"$temp/kcadm" get clients -r urinet-archiv -q clientId=archiv-verwaltung > "$temp/clients.json"
client_id=$(jq -er 'if length == 1 then .[0].id else error("Client nicht eindeutig") end' "$temp/clients.json")
"$temp/kcadm" get "clients/$client_id" -r urinet-archiv > "$temp/client.json"
jq -e '.serviceAccountsEnabled and (.publicClient | not) and (.standardFlowEnabled | not)
  and (.implicitFlowEnabled | not) and (.directAccessGrantsEnabled | not)' "$temp/client.json" >/dev/null
"$temp/kcadm" get "clients/$client_id/default-client-scopes" -r urinet-archiv |
  jq -e 'any(.name == "kc-admin-api")' >/dev/null
uid=$("$temp/kcadm" get "clients/$client_id/service-account-user" -r urinet-archiv | jq -er '.id')
"$temp/kcadm" get "users/$uid/role-mappings" -r urinet-archiv > "$temp/rollen.json"
jq -e '[.clientMappings["realm-management"].mappings[].name] | sort ==
  ["manage-users", "query-users", "view-users"]' "$temp/rollen.json" >/dev/null
"$temp/kcadm" get "clients/$client_id/client-secret" -r urinet-archiv |
  jq -e '.value == "synthetisch-verwaltung"' >/dev/null
"$temp/kcadm" get realms/urinet-archiv > "$temp/realm.json"
jq -e '.passwordPolicy == "length(12) and notUsername and notEmail" and
  .smtpServer.host == "mail.example.invalid" and .smtpServer.port == "587" and
  .smtpServer.starttls == "true" and .smtpServer.auth == "true" and
  .adminEventsEnabled and (.adminEventsDetailsEnabled | not)' "$temp/realm.json" >/dev/null
echo "Frischer Keycloak: Einrichtung und Wiederholung erfolgreich geprüft."
