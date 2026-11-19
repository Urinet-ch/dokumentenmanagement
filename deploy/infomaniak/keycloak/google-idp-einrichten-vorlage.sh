#!/usr/bin/env bash
set -euo pipefail

realm=${KC_REALM:-urinet-archiv}
kcadm=${KCADM:-/opt/keycloak/bin/kcadm.sh}
id_datei=${GOOGLE_CLIENT_ID_DATEI:-/run/secrets/google_client_id}
secret_datei=${GOOGLE_CLIENT_SECRET_DATEI:-/run/secrets/google_client_secret}

if [[ ! -s "$id_datei" ]]; then
  echo "Secret-Datei google_client_id fehlt oder ist leer." >&2
  exit 1
fi

if [[ ! -s "$secret_datei" ]]; then
  echo "Secret-Datei google_client_secret fehlt oder ist leer." >&2
  exit 1
fi
command -v jq >/dev/null || {
  echo "jq wird für die sichere JSON-Erzeugung benötigt." >&2
  exit 1
}

umask 077
arbeitsverzeichnis=$(mktemp -d)
trap 'rm -rf -- "$arbeitsverzeichnis"' EXIT

neuen_flow_anlegen() {
  local alias=$1 provider=$2
  "$kcadm" get authentication/flows -r "$realm" > "$arbeitsverzeichnis/flows.json"
  if [[ $(jq --arg alias "$alias" '[ .[] | select(.alias == $alias) ] | length' "$arbeitsverzeichnis/flows.json") -eq 0 ]]; then
    "$kcadm" create authentication/flows -r "$realm" \
      -s "alias=$alias" -s providerId=basic-flow -s topLevel=true -s builtIn=false >/dev/null
  fi
  "$kcadm" get "authentication/flows/$alias/executions" -r "$realm" \
    > "$arbeitsverzeichnis/ausfuehrungen.json"
  if [[ $(jq --arg provider "$provider" '[ .[] | select(.providerId == $provider) ] | length' "$arbeitsverzeichnis/ausfuehrungen.json") -eq 0 ]]; then
    "$kcadm" create "authentication/flows/$alias/executions/execution" -r "$realm" \
      -s "provider=$provider" >/dev/null
    "$kcadm" get "authentication/flows/$alias/executions" -r "$realm" \
      > "$arbeitsverzeichnis/ausfuehrungen.json"
  fi
  jq -e --arg provider "$provider" '
    [ .[] | select(.providerId == $provider) ] as $treffer
    | if ($treffer | length) == 1 then $treffer[0] + {requirement: "REQUIRED"}
      else error("Erwartete Authentifizierungsausführung fehlt oder ist mehrdeutig")
      end
  ' "$arbeitsverzeichnis/ausfuehrungen.json" > "$arbeitsverzeichnis/ausfuehrung.json"
  "$kcadm" update "authentication/flows/$alias/executions" -r "$realm" \
    -f "$arbeitsverzeichnis/ausfuehrung.json" >/dev/null
}

# Zuerst die Selbstverknüpfung sperren (Vorläufig bis zum Entscheid zu Req 2.27), damit ein
# Abbruch in den späteren Schritten sie nicht offen lässt. Der Schritt ist wiederholbar.
account_id=$("$kcadm" get clients -r "$realm" -q clientId=account |
  jq -er '.[0].id')
"$kcadm" get "clients/$account_id/roles/manage-account/composites" -r "$realm" |
  jq '[ .[] | select(.name == "manage-account-links") ]' \
  > "$arbeitsverzeichnis/verknuepfungsrolle.json"
if [[ $(jq 'length' "$arbeitsverzeichnis/verknuepfungsrolle.json") -gt 0 ]]; then
  "$kcadm" delete "clients/$account_id/roles/manage-account/composites" -r "$realm" \
    -f "$arbeitsverzeichnis/verknuepfungsrolle.json" >/dev/null
fi

neuen_flow_anlegen google-nur-verknuepft deny-access-authenticator
neuen_flow_anlegen google-totp-pflicht auth-otp-form

jq -n --rawfile kennung "$id_datei" --rawfile geheimnis "$secret_datei" '{
  alias: "google",
  displayName: "Google",
  providerId: "google",
  enabled: false,
  trustEmail: false,
  storeToken: false,
  firstBrokerLoginFlowAlias: "google-nur-verknuepft",
  postBrokerLoginFlowAlias: "google-totp-pflicht",
  config: {
    clientId: ($kennung | rtrimstr("\n")),
    clientSecret: ($geheimnis | rtrimstr("\n")),
    syncMode: "IMPORT",
    defaultScope: "openid email profile"
  }
}' > "$arbeitsverzeichnis/google.json"
"$kcadm" get identity-provider/instances -r "$realm" > "$arbeitsverzeichnis/anbieter.json"
if [[ $(jq '[ .[] | select(.alias == "google") ] | length' "$arbeitsverzeichnis/anbieter.json") -eq 0 ]]; then
  "$kcadm" create identity-provider/instances -r "$realm" \
    -f "$arbeitsverzeichnis/google.json" >/dev/null
else
  "$kcadm" update identity-provider/instances/google -r "$realm" \
    -f "$arbeitsverzeichnis/google.json" >/dev/null
fi

jq -n '{
  eventsListeners: [],
  eventsEnabled: true,
  enabledEventTypes: [
    "LOGIN_ERROR",
    "IDENTITY_PROVIDER_LOGIN",
    "IDENTITY_PROVIDER_LOGIN_ERROR",
    "IDENTITY_PROVIDER_FIRST_LOGIN_ERROR",
    "FEDERATED_IDENTITY_LINK",
    "REMOVE_FEDERATED_IDENTITY"
  ],
  adminEventsEnabled: true,
  adminEventsDetailsEnabled: false
}' > "$arbeitsverzeichnis/ereignisse.json"
"$kcadm" update "realms/$realm" -f "$arbeitsverzeichnis/ereignisse.json" >/dev/null

echo "Google-IdP vorbereitet und deaktiviert; Anmeldung bleibt bis zur Freigabe gesperrt."
