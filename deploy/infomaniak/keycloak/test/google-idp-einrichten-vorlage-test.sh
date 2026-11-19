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
if [[ "$1" == get && "$2" == authentication/flows ]]; then
  if [[ ${KC_STUB_VORHANDEN:-} == 1 ]]; then
    printf '[{"alias":"google-nur-verknuepft"},{"alias":"google-totp-pflicht"}]\n'
  else
    printf '[]\n'
  fi
elif [[ "$1" == get && "$2" == identity-provider/instances ]]; then
  if [[ ${KC_STUB_VORHANDEN:-} == 1 ]]; then printf '[{"alias":"google"}]\n'; else printf '[]\n'; fi
elif [[ "$1" == get && "$2" == clients ]]; then
  printf '[{"id":"account-client"}]\n'
elif [[ "$1" == get && "$2" == clients/account-client/roles/manage-account/composites ]]; then
  printf '[{"id":"link-role","name":"manage-account-links"}]\n'
elif [[ "$1" == get && "$2" == authentication/flows/google-nur-verknuepft/executions ]]; then
  if [[ ${KC_STUB_VORHANDEN:-} != 1 && ! -e "$KC_KCADM_AUFRUFPROTOKOLL.deny" ]]; then touch "$KC_KCADM_AUFRUFPROTOKOLL.deny"; printf '[]\n'; exit 0; fi
  printf '[{"id":"deny-execution","providerId":"deny-access-authenticator","requirement":"DISABLED","priority":0}]\n'
elif [[ "$1" == get && "$2" == authentication/flows/google-totp-pflicht/executions ]]; then
  if [[ ${KC_STUB_VORHANDEN:-} != 1 && ! -e "$KC_KCADM_AUFRUFPROTOKOLL.otp" ]]; then touch "$KC_KCADM_AUFRUFPROTOKOLL.otp"; printf '[]\n'; exit 0; fi
  printf '[{"id":"otp-execution","providerId":"auth-otp-form","requirement":"DISABLED","priority":0}]\n'
elif [[ ( "$1" == create && "$2" == identity-provider/instances ) || ( "$1" == update && "$2" == identity-provider/instances/google ) ]]; then
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
# Wiederholungslauf mit vorhandenem Flow und Provider darf nicht scheitern und nichts doppelt anlegen.
KC_STUB_VORHANDEN=1 \
KC_KCADM_AUFRUFPROTOKOLL="$temp/wiederholung.log" \
KCADM="$temp/bin/kcadm-stub" \
GOOGLE_CLIENT_SECRET_DATEI="$temp/secrets/google_client_secret" \
bash "$vorlage" >/dev/null
! grep -q '^create ' "$temp/wiederholung.log"
grep -q '^update identity-provider/instances/google' "$temp/wiederholung.log"
# Die Rollensperre steht vor dem ersten Anlegen.
[[ $(grep -n 'manage-account/composites' "$temp/aufrufe.log" | head -1 | cut -d: -f1) -lt $(grep -n '^create ' "$temp/aufrufe.log" | head -1 | cut -d: -f1) ]]
echo "Google-IdP-Vorlage mit synthetischem Secret geprüft."
