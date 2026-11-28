#!/usr/bin/env bash
# Prüft konto-einschraenken-vorlage.sh gegen ein Wegwerf-Keycloak (Spec 06, Aufgabe 26.2): Realm mit dem Benutzerprofil
# des Exports vor 26.2 (Vor- und Nachname für Benutzer änderbar), Prüflauf, Einrichten, Wiederholung, Abbruch bei
# unerwartetem Profil. Die Wirkung in der Kontoverwaltung belegt RealmExportKeycloakTest gegen den aktuellen Export.
set -euo pipefail

root=$(cd "$(dirname "$0")/../../../.." && pwd)
# Das Arbeitsverzeichnis wird in den kcadm-Container eingehängt: Es muss für Docker erreichbar sein (unter macOS mit
# Colima gilt das für das Home-Verzeichnis, nicht für /tmp); überschreibbar mit URINET_TEST_TMP.
basis=${URINET_TEST_TMP:-${TMPDIR:-/tmp}}
mkdir -p -- "$basis"
temp=$(mktemp -d "${basis%/}/urinet-26.2.XXXXXX")
server="urinet-26-2-server-$$"
client="urinet-26-2-client-$$"
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
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=testadmin-nur-test "$image" start-dev --http-host=127.0.0.1 >/dev/null
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
      --user testadmin --password testadmin-nur-test >"$temp/anmeldung.log" 2>&1; then
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
# Benutzerprofil wie im Export, aber mit dem Stand vor 26.2: Vorname für admin und user änderbar. Der Nachname ist hier
# strenger als im Export (für niemanden änderbar): Die Vorlage darf ihn nicht erweitern.
jq -r '.components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0]' \
  "$root/deploy/keycloak/realm-export.json" |
  jq '.attributes |= map(if .name == "firstName" then .permissions.edit = ["admin", "user"]
    elif .name == "lastName" then .permissions.edit = [] else . end)' \
  > "$temp/profil-alt.json"
"$temp/kcadm" update users/profile -r urinet-archiv -f "$temp/profil-alt.json" >/dev/null

export KCADM="$temp/kcadm" TMPDIR="$temp"
vorlage="$root/deploy/infomaniak/keycloak/konto-einschraenken-vorlage.sh"
status=0
bash "$vorlage" --pruefen >"$temp/pruefen.log" || status=$?
[[ $status == 10 ]] || { echo "Prüflauf vor dem Einrichten: Exit $status statt 10." >&2; exit 1; }
# Ein neu angelegter Realm hat idp_link aktiv (der importierte Export nicht): beides meldet der Prüflauf
grep -q 'offen: firstName idp_link$' "$temp/pruefen.log"
bash "$vorlage" >/dev/null
bash "$vorlage" --pruefen >/dev/null
bash "$vorlage" | grep -q 'nichts zu tun'
"$temp/kcadm" get users/profile -r urinet-archiv > "$temp/profil.json"
jq -e 'all(.attributes[] | select(.name == "username" or .name == "email" or .name == "firstName"
  or .name == "mandant"); .permissions.edit == ["admin"])' "$temp/profil.json" >/dev/null
# Strengere Rechte bleiben unverändert
jq -e '.attributes[] | select(.name == "lastName") | .permissions.edit == []' "$temp/profil.json" >/dev/null
"$temp/kcadm" get authentication/required-actions -r urinet-archiv |
  jq -e 'all(.[]; .alias != "idp_link" or (.enabled | not))' >/dev/null
# Ansehen bleibt erlaubt: Die Seite «Mein Konto» und die Kontoverwaltung zeigen Name und E-Mail weiter an
jq -e 'all(.attributes[] | select(.name == "firstName" or .name == "lastName"); .permissions.view == ["admin", "user"])' \
  "$temp/profil.json" >/dev/null

# Unerwarteter Zustand: E-Mail für Benutzer änderbar -> Abbruch ohne Änderung
jq '.attributes |= map(if .name == "email" then .permissions.edit = ["admin", "user"] else . end)' "$temp/profil.json" \
  > "$temp/profil-fremd.json"
"$temp/kcadm" update users/profile -r urinet-archiv -f "$temp/profil-fremd.json" >/dev/null
status=0
bash "$vorlage" 2>"$temp/abbruch.log" >/dev/null || status=$?
[[ $status == 1 ]] || { echo "Unerwartetes Profil: Exit $status statt 1." >&2; exit 1; }
grep -q 'weicht vom Export ab' "$temp/abbruch.log"
echo "Wegwerf-Keycloak: Prüflauf, Einrichten, Wiederholung und Abbruch bei fremdem Profil erfolgreich geprüft."
