#!/usr/bin/env bash
# Hilfsskript für den Wegwerf-Container (siehe README «Passkey-Realm nachrüsten»): meldet kcadm am Realm master an und führt
# danach eine Vorlage aus. Das Passwort steht nie in einem Argument und nie in der Compose-Definition: Es wird aus der
# Secret-Datei gelesen und nur der Umgebung des einen kcadm-Prozesses übergeben.
#
#   vorlage-ausfuehren.sh <vorlage.sh> [Argumente der Vorlage …]
#
# Umgebung (alle mit Vorgabe): KC_SERVER (http://kc:8080), KC_ADMIN_BENUTZER (archiv-admin),
# KC_ADMIN_PASSWORT_DATEI (/run/secrets/keycloak_admin), KCADM (/opt/keycloak/bin/kcadm.sh). Fehlt ARCHIV_PASSKEY_RP_ID,
# wird sie wie in start.sh aus ARCHIV_BASIS_URL abgeleitet (Host ohne Port).
set -euo pipefail

if [[ $# -lt 1 || ! -f $1 ]]; then
  echo "Aufruf: vorlage-ausfuehren.sh <vorlage.sh> [Argumente …]; die Vorlage muss als Datei vorhanden sein." >&2
  exit 2
fi
vorlage=$1
shift
server=${KC_SERVER:-http://kc:8080}
benutzer=${KC_ADMIN_BENUTZER:-archiv-admin}
datei=${KC_ADMIN_PASSWORT_DATEI:-/run/secrets/keycloak_admin}
kcadm=${KCADM:-/opt/keycloak/bin/kcadm.sh}

if [[ ! -s $datei ]]; then
  echo "Die Secret-Datei des Keycloak-Admins fehlt oder ist leer." >&2
  exit 1
fi
if [[ -z ${ARCHIV_PASSKEY_RP_ID:-} && ${ARCHIV_BASIS_URL:-} == https://* ]]; then
  host_port=${ARCHIV_BASIS_URL#https://}
  ARCHIV_PASSKEY_RP_ID=${host_port%%:*}
  export ARCHIV_PASSKEY_RP_ID
fi

# Befehlsersetzung entfernt den abschliessenden Zeilenumbruch; die Zuweisung gilt nur für diesen einen Aufruf.
if ! KC_CLI_PASSWORD=$(< "$datei") "$kcadm" config credentials --server "$server" --realm master --user "$benutzer" >/dev/null; then
  echo "Die Anmeldung von kcadm am Realm master ist fehlgeschlagen (Server, Benutzer und Secret-Datei prüfen)." >&2
  exit 1
fi
exec bash "$vorlage" "$@"
