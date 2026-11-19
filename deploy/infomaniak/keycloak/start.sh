#!/usr/bin/env bash
# Liest die Secret-Dateien (tech.md Regel 7: nie environment/env_file in Compose) und startet Keycloak.
# Der Realm urinet-archiv wird nur importiert, solange es ihn in der Datenbank noch nicht gibt.
set -euo pipefail

lesen() {
  local datei="/run/secrets/$1"
  if [[ ! -s "$datei" ]]; then
    echo "Secret-Datei $1 fehlt oder ist leer: Deploy-Workflow mit allen Secrets erneut ausführen." >&2
    exit 1
  fi
  cat -- "$datei"
}

# Befehlsersetzung entfernt den abschliessenden Zeilenumbruch
KC_DB_PASSWORD=$(lesen keycloak_db)
KC_BOOTSTRAP_ADMIN_PASSWORD=$(lesen keycloak_admin)
if [[ -e /run/secrets/google_client_id ]]; then
  GOOGLE_CLIENT_ID=$(lesen google_client_id)
else
  GOOGLE_CLIENT_ID=
fi
if [[ -e /run/secrets/google_client_secret ]]; then
  GOOGLE_CLIENT_SECRET=$(lesen google_client_secret)
else
  GOOGLE_CLIENT_SECRET=
fi
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET
exec /opt/keycloak/bin/kc.sh start --optimized --import-realm "$@"
