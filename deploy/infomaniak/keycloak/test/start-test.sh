#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../../../.." && pwd)
start="$repo/deploy/infomaniak/keycloak/start.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
mkdir -p "$temp/secrets" "$temp/bin"
printf 'db-testwert\n' > "$temp/secrets/keycloak_db"
printf 'admin-testwert\n' > "$temp/secrets/keycloak_admin"

cat > "$temp/bin/keycloak-stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${GOOGLE_CLIENT_ID:-}" > "$START_TEST_AUSGABE/id"
printf '%s\n' "${GOOGLE_CLIENT_SECRET:-}" > "$START_TEST_AUSGABE/secret"
printf '%s\n' "$*" > "$START_TEST_AUSGABE/args"
STUB
chmod +x "$temp/bin/keycloak-stub"
mkdir "$temp/out"

pruefen() {
  local id=$1 secret=$2 erwartet_id=$3 erwartet_secret=$4
  : > "$temp/secrets/google_client_secret"
  if [[ "$secret" != "<leer>" ]]; then
    printf '%s\n' "$secret" > "$temp/secrets/google_client_secret"
  fi
  ARCHIV_SECRETS_DIR="$temp/secrets" \
  GOOGLE_CLIENT_ID="$id" \
  KEYCLOAK_BIN="$temp/bin/keycloak-stub" \
  START_TEST_AUSGABE="$temp/out" \
    bash "$start" >/dev/null
  [[ $(<"$temp/out/id") == "$erwartet_id" ]]
  [[ $(<"$temp/out/secret") == "$erwartet_secret" ]]
  [[ $(<"$temp/out/args") == "start --optimized --import-realm" ]]
}

pruefen "" "synthetisches-secret" "" ""
pruefen "synthetische-id" "<leer>" "" ""
pruefen "synthetische-id" "synthetisches-secret" "synthetische-id" "synthetisches-secret"
jq -e '.identityProviders[] | select(.alias == "google" and .enabled == false)' \
  "$repo/deploy/keycloak/realm-export.json" >/dev/null
echo "Keycloak-Start aktiviert Google nur mit nichtleerer ID und Secret."
