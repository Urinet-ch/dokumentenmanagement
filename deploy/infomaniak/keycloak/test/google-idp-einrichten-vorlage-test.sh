#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../../../.." && pwd)
vorlage="$repo/deploy/infomaniak/keycloak/google-idp-einrichten-vorlage.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
mkdir -p "$temp/secrets" "$temp/bin"
printf '%s\n' 'nur-synthetischer-testwert' > "$temp/secrets/google_client_secret"

cat > "$temp/bin/kcadm-stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$KC_KCADM_AUFRUFPROTOKOLL"
if [[ "$1" == get && "$2" == clients ]]; then
  printf '[{"id":"account-client"}]\n'
elif [[ "$1" == get && "$2" == clients/account-client/roles/manage-account/composites ]]; then
  printf '[{"id":"link-role","name":"manage-account-links"}]\n'
elif [[ "$1" == get && "$2" == authentication/flows/google-nur-verknuepft/executions ]]; then
  printf '[{"id":"deny-execution","providerId":"deny-access-authenticator","requirement":"DISABLED","priority":0}]\n'
elif [[ "$1" == get && "$2" == authentication/flows/google-totp-pflicht/executions ]]; then
  printf '[{"id":"otp-execution","providerId":"auth-otp-form","requirement":"DISABLED","priority":0}]\n'
elif [[ "$1" == create && "$2" == identity-provider/instances ]]; then
  payload=
  while (($#)); do
    if [[ "$1" == -f ]]; then
      payload=$2
      break
    fi
    shift
  done
  jq -e '.enabled == false and .config.clientSecret == "nur-synthetischer-testwert" and .config.syncMode == "IMPORT"' "$payload" >/dev/null
fi
STUB
chmod +x "$temp/bin/kcadm-stub"

KC_KCADM_AUFRUFPROTOKOLL="$temp/aufrufe.log" \
KCADM="$temp/bin/kcadm-stub" \
GOOGLE_CLIENT_SECRET_DATEI="$temp/secrets/google_client_secret" \
bash "$vorlage" >/dev/null

grep -q 'deny-access-authenticator' "$temp/aufrufe.log"
grep -q 'auth-otp-form' "$temp/aufrufe.log"
grep -q 'realms/urinet-archiv' "$temp/aufrufe.log"
grep -q 'clients/account-client/roles/manage-account/composites' "$temp/aufrufe.log"
echo "Google-IdP-Vorlage mit synthetischem Secret geprüft."
