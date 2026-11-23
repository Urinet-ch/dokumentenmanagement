#!/usr/bin/env bash
set -euo pipefail

realm=${KC_REALM:-urinet-archiv}
kcadm=${KCADM:-/opt/keycloak/bin/kcadm.sh}
verwaltung_datei=${KEYCLOAK_VERWALTUNG_SECRET_DATEI:-/run/secrets/keycloak_verwaltung_secret}
smtp_datei=${KEYCLOAK_SMTP_PASSWORT_DATEI:-/run/secrets/keycloak_smtp_passwort}

for datei in "$verwaltung_datei" "$smtp_datei"; do
  if [[ ! -s "$datei" ]]; then
    echo "Eine benötigte Secret-Datei fehlt oder ist leer." >&2
    exit 1
  fi
  if ! jq -en --rawfile wert "$datei" '$wert | test("\\S")' >/dev/null; then
    echo "Eine benötigte Secret-Datei enthält keinen Wert." >&2
    exit 1
  fi
done
for wert in "${SMTP_HOST:-}" "${SMTP_PORT:-}" "${SMTP_BENUTZER:-}" "${SMTP_ABSENDER:-}"; do
  if [[ -z $wert ]]; then
    echo "SMTP_HOST, SMTP_PORT, SMTP_BENUTZER und SMTP_ABSENDER sind erforderlich." >&2
    exit 1
  fi
done
if [[ ! $SMTP_PORT =~ ^[0-9]{1,5}$ ]] || (( 10#$SMTP_PORT < 1 || 10#$SMTP_PORT > 65535 )); then
  echo "Der SMTP-Port muss zwischen 1 und 65535 liegen." >&2
  exit 1
fi

umask 077
arbeitsverzeichnis=$(mktemp -d "${TMPDIR:-/tmp}/urinet-keycloak.XXXXXX")
trap 'rm -rf -- "$arbeitsverzeichnis"' EXIT

kc() {
  if ! "$kcadm" "$@" 2>"$arbeitsverzeichnis/fehler"; then
    echo "Keycloak-Aufruf $1 $2 fehlgeschlagen; Anmeldung und Konfiguration prüfen." >&2
    return 1
  fi
}

# Keine Admin-Ereignisdetails speichern, bevor ein Secret an Keycloak übertragen wird.
jq -n '{adminEventsEnabled: true, adminEventsDetailsEnabled: false}' > "$arbeitsverzeichnis/ereignisse.json"
kc update "realms/$realm" -f "$arbeitsverzeichnis/ereignisse.json" >/dev/null

kc get clients -r "$realm" -q clientId=archiv-verwaltung > "$arbeitsverzeichnis/clients.json"
anzahl=$(jq '[.[] | select(.clientId == "archiv-verwaltung")] | length' "$arbeitsverzeichnis/clients.json")
if (( anzahl > 1 )); then
  echo "Der Verwaltungsclient ist nicht eindeutig." >&2
  exit 1
fi
jq -n '{
  clientId: "archiv-verwaltung", name: "UriNet Archiv (Verwaltung)",
  enabled: true, protocol: "openid-connect", publicClient: false, bearerOnly: false,
  standardFlowEnabled: false, implicitFlowEnabled: false, directAccessGrantsEnabled: false,
  serviceAccountsEnabled: true
}' > "$arbeitsverzeichnis/client.json"
if (( anzahl == 0 )); then
  kc create clients -r "$realm" -f "$arbeitsverzeichnis/client.json" >/dev/null
  kc get clients -r "$realm" -q clientId=archiv-verwaltung > "$arbeitsverzeichnis/clients.json"
fi
client_id=$(jq -er '[.[] | select(.clientId == "archiv-verwaltung") | .id] | if length == 1 then .[0] else error("Client fehlt") end' "$arbeitsverzeichnis/clients.json")
kc update "clients/$client_id" -r "$realm" -f "$arbeitsverzeichnis/client.json" >/dev/null

kc get client-scopes -r "$realm" > "$arbeitsverzeichnis/scopes.json"
scope_id=$(jq -er '[.[] | select(.name == "kc-admin-api") | .id] | if length == 1 then .[0] else error("Scope kc-admin-api fehlt") end' "$arbeitsverzeichnis/scopes.json")
kc update "clients/$client_id/default-client-scopes/$scope_id" -r "$realm" >/dev/null

dienstkonto_id=$(kc get "clients/$client_id/service-account-user" -r "$realm" | jq -er '.id')
kc get "users/$dienstkonto_id/role-mappings" -r "$realm" > "$arbeitsverzeichnis/rollen.json"
if ! jq -e --arg realm "$realm" '
  all(.realmMappings[]?; .name == ("default-roles-" + $realm)) and
  all((.clientMappings // {}) | to_entries[];
    .key == "realm-management" and
    all(.value.mappings[]; .name == "query-users" or .name == "view-users" or .name == "manage-users"))
' "$arbeitsverzeichnis/rollen.json" >/dev/null; then
  echo "Das Dienstkonto hat unerwartete direkte Rollen; vor der Einrichtung manuell prüfen." >&2
  exit 1
fi
for rolle in query-users view-users manage-users; do
  if ! jq -e --arg rolle "$rolle" '.clientMappings["realm-management"].mappings // [] | any(.name == $rolle)' \
      "$arbeitsverzeichnis/rollen.json" >/dev/null; then
    kc add-roles -r "$realm" --uid "$dienstkonto_id" --cclientid realm-management --rolename "$rolle" >/dev/null
  fi
done

jq -n --rawfile geheimnis "$verwaltung_datei" \
  '{secret: ($geheimnis | rtrimstr("\n"))}' > "$arbeitsverzeichnis/client-secret.json"
kc update "clients/$client_id" -r "$realm" -f "$arbeitsverzeichnis/client-secret.json" >/dev/null

jq -n --arg host "$SMTP_HOST" --arg port "$SMTP_PORT" --arg benutzer "$SMTP_BENUTZER" \
  --arg absender "$SMTP_ABSENDER" --rawfile passwort "$smtp_datei" '{
  passwordPolicy: "length(12) and notUsername and notEmail",
  smtpServer: {
    host: $host, port: $port, user: $benutzer, from: $absender,
    fromDisplayName: "UriNet Archiv", auth: "true", starttls: "true", ssl: "false",
    password: ($passwort | rtrimstr("\n"))
  }
}' > "$arbeitsverzeichnis/realm.json"
kc update "realms/$realm" -f "$arbeitsverzeichnis/realm.json" >/dev/null
echo "Verwaltungsclient, Dienstkonto, SMTP und Passwort-Richtlinie eingerichtet."
