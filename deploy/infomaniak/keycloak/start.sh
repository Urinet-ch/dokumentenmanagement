#!/usr/bin/env bash
# Liest die Secret-Dateien (tech.md Regel 7: nie environment/env_file in Compose) und startet Keycloak.
# Der Realm urinet-archiv wird nur importiert, solange es ihn in der Datenbank noch nicht gibt.
set -euo pipefail

if [[ ! "${ARCHIV_BASIS_URL:-}" =~ ^https://[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*(:[0-9]{1,5})?$ ]]; then
  echo "ARCHIV_BASIS_URL fehlt oder ist keine HTTPS-Basisadresse; Deploy-Konfiguration prüfen." >&2
  exit 1
fi
host_port=${ARCHIV_BASIS_URL#https://}
if [[ "$host_port" == *:* ]]; then
  port=${host_port##*:}
  if (( 10#$port < 1 || 10#$port > 65535 )); then
    echo "ARCHIV_BASIS_URL enthält keinen gültigen Port; Deploy-Konfiguration prüfen." >&2
    exit 1
  fi
fi

lesen() {
  local datei="${ARCHIV_SECRETS_DIR:-/run/secrets}/$1"
  if [[ ! -s "$datei" ]]; then
    echo "Secret-Datei $1 fehlt oder ist leer: Deploy-Workflow mit allen Secrets erneut ausführen." >&2
    exit 1
  fi
  cat -- "$datei"
}

# Befehlsersetzung entfernt den abschliessenden Zeilenumbruch
KC_DB_PASSWORD=$(lesen keycloak_db)
KC_BOOTSTRAP_ADMIN_PASSWORD=$(lesen keycloak_admin)
secrets="${ARCHIV_SECRETS_DIR:-/run/secrets}"
if [[ -s "$secrets/google_client_id" && -s "$secrets/google_client_secret" ]]; then
  GOOGLE_CLIENT_ID=$(lesen google_client_id)
  GOOGLE_CLIENT_SECRET=$(lesen google_client_secret)
  # Nur Leerraum (Leerzeichen, Zeilenumbruch, CRLF) zählt als leer.
  if [[ -z "${GOOGLE_CLIENT_ID//[[:space:]]/}" || -z "${GOOGLE_CLIENT_SECRET//[[:space:]]/}" ]]; then
    GOOGLE_CLIENT_ID=
    GOOGLE_CLIENT_SECRET=
  fi
else
  GOOGLE_CLIENT_ID=
  GOOGLE_CLIENT_SECRET=
fi
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET
exec "${KEYCLOAK_BIN:-/opt/keycloak/bin/kc.sh}" start --optimized --import-realm "$@"
