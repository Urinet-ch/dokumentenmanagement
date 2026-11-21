#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../../../.." && pwd)
start="$repo/deploy/infomaniak/keycloak/start.sh"
temp=$(mktemp -d)
aufraeumen() {
  rm -f -- "$temp/secrets/keycloak_db" "$temp/secrets/keycloak_admin" \
    "$temp/secrets/google_client_id" "$temp/secrets/google_client_secret" "$temp/secrets/keycloak_verwaltung_secret" \
    "$temp/bin/keycloak-stub" "$temp/out/id" "$temp/out/secret" "$temp/out/args" "$temp/out/verwaltung"
  rmdir -- "$temp/secrets" "$temp/bin" "$temp/out" "$temp"
}
trap aufraeumen EXIT
mkdir -p "$temp/secrets" "$temp/bin"
printf 'db-testwert\n' > "$temp/secrets/keycloak_db"
printf 'admin-testwert\n' > "$temp/secrets/keycloak_admin"

cat > "$temp/bin/keycloak-stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${GOOGLE_CLIENT_ID:-}" > "$START_TEST_AUSGABE/id"
printf '%s\n' "${GOOGLE_CLIENT_SECRET:-}" > "$START_TEST_AUSGABE/secret"
printf '%s\n' "$*" > "$START_TEST_AUSGABE/args"
printf '%s\n' "${KEYCLOAK_VERWALTUNG_SECRET:-}" > "$START_TEST_AUSGABE/verwaltung"
STUB
chmod +x "$temp/bin/keycloak-stub"
mkdir "$temp/out"

pruefen() {
  local id=$1 secret=$2 erwartet_id=$3 erwartet_secret=$4
  : > "$temp/secrets/google_client_id"
  : > "$temp/secrets/google_client_secret"
  if [[ "$id" != "<leer>" ]]; then
    printf "$id" > "$temp/secrets/google_client_id"
  fi
  if [[ "$secret" != "<leer>" ]]; then
    printf "$secret" > "$temp/secrets/google_client_secret"
  fi
  ARCHIV_SECRETS_DIR="$temp/secrets" \
  ARCHIV_BASIS_URL="https://pwa.example.invalid" \
  KEYCLOAK_BIN="$temp/bin/keycloak-stub" \
  START_TEST_AUSGABE="$temp/out" \
    bash "$start" >/dev/null
  [[ $(<"$temp/out/id") == "$erwartet_id" ]]
  [[ $(<"$temp/out/secret") == "$erwartet_secret" ]]
  [[ $(<"$temp/out/args") == "start --optimized --import-realm" ]]
}

# Das Skript übergibt nur Umgebungsvariablen; «enabled» bleibt im Export fest false (siehe jq unten).
pruefen "<leer>" "synthetisches-secret\n" "" ""
pruefen "synthetische-id\n" "<leer>" "" ""
pruefen "\n" "synthetisches-secret\n" "" ""
pruefen "synthetische-id\n" "\n" "" ""
pruefen "   \n" "synthetisches-secret\n" "" ""
pruefen "synthetische-id\n" " \r\n" "" ""
pruefen "synthetische-id\n" "synthetisches-secret\n" "synthetische-id" "synthetisches-secret"
# Ungültige Basisadresse: gleiche Umgebung wie pruefen() (gültige Secrets, Stub), damit der Abbruch von der URL-Prüfung kommt
# und nicht von fehlenden Secrets; Meldung prüfen.
basis_pruefen() {
  ARCHIV_SECRETS_DIR="$temp/secrets" ARCHIV_BASIS_URL="$1" KEYCLOAK_BIN="$temp/bin/keycloak-stub" \
    START_TEST_AUSGABE="$temp/out" bash "$start" 2>&1 >/dev/null
}
printf 'synthetische-id\n' > "$temp/secrets/google_client_id"
printf 'synthetisches-secret\n' > "$temp/secrets/google_client_secret"
for basis in "" "http://pwa.example.invalid" "https://pwa.example.invalid/path" \
  'https://pwa.example.invalid/*' "https://." "https://pwa.example.invalid:99999" "https://pwa.example.invalid:0"; do
  if meldung=$(basis_pruefen "$basis"); then
    echo "Ungültige Basisadresse wurde angenommen." >&2
    exit 1
  fi
  [[ "$meldung" == *"ARCHIV_BASIS_URL"* ]] || { echo "Abbruch nicht durch die Adressprüfung: $meldung" >&2; exit 1; }
done
# Positivfall mit Port
basis_pruefen "https://pwa.example.invalid:8443" >/dev/null
# Dienstkonto-Secret: ohne Datei zufälliger Wert (nie der Platzhalter), mit Datei dessen Inhalt
pruefen "synthetische-id\n" "synthetisches-secret\n" "synthetische-id" "synthetisches-secret"
zufall=$(<"$temp/out/verwaltung")
[[ ${#zufall} -ge 32 && "$zufall" != *'$'* ]]
pruefen "synthetische-id\n" "synthetisches-secret\n" "synthetische-id" "synthetisches-secret"
[[ $(<"$temp/out/verwaltung") != "$zufall" ]]
printf 'verwaltung-testwert\n' > "$temp/secrets/keycloak_verwaltung_secret"
pruefen "synthetische-id\n" "synthetisches-secret\n" "synthetische-id" "synthetisches-secret"
[[ $(<"$temp/out/verwaltung") == "verwaltung-testwert" ]]
jq -e '.identityProviders[] | select(.alias == "google" and .enabled == false)' \
  "$repo/deploy/keycloak/realm-export.json" >/dev/null
echo "Keycloak-Start übergibt leere Werte für Google bei leerer, fehlender oder nur aus Leerraum bestehender ID oder Secret."
