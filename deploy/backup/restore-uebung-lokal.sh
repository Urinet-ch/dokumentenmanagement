#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
backend="$repo_root/backend"
compose_file="$repo_root/deploy/backup/test/restore-compose.yml"
mandanten=(urinet vhf-gsk)
temp=$(mktemp -d "${TMPDIR:-/tmp}/archiv-restore.XXXXXX")
chmod 700 "$temp"
suffix=$(printf '%s' "${temp##*.}" | tr '[:upper:]' '[:lower:]')
project="archiv-restore-$$-$suffix"
oidc_client="$project"
api_image="archiv:restore-roundtrip-$project"
keycloak_image="archiv:restore-roundtrip-kc-$project"
api_image_gebaut=0
keycloak_image_gebaut=0
compose_started=0
network_retry=0
export ARCHIV_RESTORE_TMP="$temp"
export COMPOSE_PROJECT_NAME="$project"
export ARCHIV_RESTORE_OIDC_CLIENT="$oidc_client"

bericht() {
  printf '%-7s %s (%ss)\n' "$1" "$2" "$3"
}

fehler() {
  printf 'FEHLER  %s\n' "$*" >&2
  exit 3
}

voraussetzung() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'FEHLER  Voraussetzung fehlt: %s\n' "$1" >&2
    exit 2
  }
}

compose() {
  docker compose --project-name "$project" --file "$compose_file" "$@"
}

pruefsummen_pruefen() {
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$1" && sha256sum --strict --check SHA256SUMS)
  else
    (cd "$1" && shasum -a 256 --strict -c SHA256SUMS)
  fi
}

# Jede verschlüsselte Datei steht genau einmal in SHA256SUMS, und SHA256SUMS nennt keine weiteren Dateien.
pruefsummen_vollstaendig() {
  local verzeichnis=$1
  local erwartet gelistet
  erwartet=$(cd "$verzeichnis" && find . -maxdepth 1 -type f -name '*.age' | sed 's|^\./||' | LC_ALL=C sort)
  gelistet=$(awk '{ sub(/^\*/, "", $2); print $2 }' "$verzeichnis/SHA256SUMS" | LC_ALL=C sort)
  [[ -n $erwartet && $erwartet == "$gelistet" ]]
}

# Die Test-Images kommen aus der Restore-Compose-Datei und den Dockerfiles, die das Skript baut; eine eigene Liste
# mit Digests gibt es hier nicht, damit sie nicht von den Quellen abweichen kann.
erwartete_images() {
  compose config --images | grep -v '^archiv:'
  awk 'toupper($1) == "FROM" && $2 ~ /[:@]/ { print $2 }' \
    "$repo_root/deploy/infomaniak/keycloak/Dockerfile" "$backend/src/main/docker/Dockerfile.jvm"
}

aufräumen() {
  local status=$?
  trap - EXIT
  if (( compose_started )); then
    if ! compose down --remove-orphans >/dev/null 2>&1; then
      printf 'FEHLER  Eigener Restore-Compose-Stack konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  for image in "$api_image" "$keycloak_image"; do
    if [[ $image == "$api_image" && $api_image_gebaut == 0 ]] \
      || [[ $image == "$keycloak_image" && $keycloak_image_gebaut == 0 ]]; then
      continue
    fi
    if ! docker image rm "$image" >/dev/null 2>&1; then
      printf 'FEHLER  Eigenes Restore-Testimage konnte nicht entfernt werden: %s\n' "$image" >&2
      (( status != 0 )) || status=3
    fi
  done
  if ! rm -rf -- "$temp"; then
    printf 'FEHLER  Temporäre Restore-Testdaten konnten nicht entfernt werden.\n' >&2
    (( status != 0 )) || status=3
  fi
  exit "$status"
}
trap aufräumen EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for programm in bash mvn docker age age-keygen openssl curl tar awk grep cmp python3 jq; do
  voraussetzung "$programm"
done
if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
  printf 'FEHLER  Kein SHA-256-Werkzeug gefunden.\n' >&2
  exit 2
fi

if ! docker info >/dev/null 2>&1; then
  printf 'FEHLER  Docker ist nicht verfügbar.\n' >&2
  exit 2
fi

kc_restore_port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
[[ $kc_restore_port =~ ^[0-9]+$ ]] || fehler "Loopback-Port für die OIDC-Prüfung konnte nicht bestimmt werden."
kc_restore_base_url="http://127.0.0.1:$kc_restore_port"
export ARCHIV_RESTORE_KC_PORT="$kc_restore_port"
export ARCHIV_RESTORE_KC_BASE_URL="$kc_restore_base_url"
compose config --quiet || fehler "Restore-Compose-Konfiguration ist ungültig."
images=$(erwartete_images | LC_ALL=C sort -u) || fehler "Test-Images konnten nicht ermittelt werden."
[[ -n $images ]] || fehler "Test-Images konnten nicht ermittelt werden."
while IFS= read -r image; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    printf 'FEHLER  Lokales Test-Image fehlt: %s – vorher «docker pull %s» ausführen.\n' "$image" "$image" >&2
    exit 2
  fi
done <<< "$images"

mkdir -p "$temp/secrets" "$temp/backup" "$temp/entschluesselt" "$temp/entschluesselt-probe"

geheimnis_erzeugen() {
  openssl rand -hex 32 > "$temp/secrets/$1"
}

for geheimnis in postgres_password archiv_db_owner archiv_db_app keycloak_db keycloak_admin keycloak_verwaltung_secret restore_oidc_client_secret; do
  geheimnis_erzeugen "$geheimnis"
done
jq -n --arg client "$oidc_client" --rawfile secret "$temp/secrets/restore_oidc_client_secret" '{
  clientId:$client,
  enabled:true,
  clientAuthenticatorType:"client-secret",
  publicClient:false,
  serviceAccountsEnabled:true,
  directAccessGrantsEnabled:false,
  standardFlowEnabled:false,
  secret:($secret | rtrimstr("\n")),
  protocolMappers:[
    {name:"archiv-api-audience", protocol:"openid-connect", protocolMapper:"oidc-audience-mapper",
      config:{"included.client.audience":"archiv-api", "access.token.claim":"true", "id.token.claim":"false"}},
    {name:"mandant", protocol:"openid-connect", protocolMapper:"oidc-usermodel-attribute-mapper",
      config:{"user.attribute":"mandant", "claim.name":"mandant", "jsonType.label":"String",
        "access.token.claim":"true", "id.token.claim":"true"}}
  ]
}' > "$temp/secrets/restore_oidc_client.json"
jq -e '.clientId and .enabled and .serviceAccountsEnabled and (.secret | length > 0) and (.protocolMappers | length == 2)' \
  "$temp/secrets/restore_oidc_client.json" >/dev/null
printf '{}' > "$temp/secrets/restore_oidc_service_account.json"
printf '%s' archiv-dev > "$temp/secrets/s3_app_zugang_id"
printf '%s' archiv-dev-zugang > "$temp/secrets/s3_app_zugang_geheimnis"
printf '%s' archiv-dev > "$temp/secrets/zugang_id"
printf '%s' archiv-dev-zugang > "$temp/secrets/zugang_geheimnis"
printf '%s' synthetischer-test-token > "$temp/secrets/infomaniak_ai_token"
printf '%s' '${archiv_db_app}' > "$temp/secrets/quarkus.datasource.password"
printf '%s' '${archiv_db_owner}' > "$temp/secrets/quarkus.liquibase.password"
openssl genpkey -algorithm Ed25519 -out "$temp/secrets/archiv_manifest_key" >/dev/null 2>&1
chmod 444 "$temp"/secrets/*

age-keygen -o "$temp/sicherung.agekey" >/dev/null 2>&1
age-keygen -o "$temp/probe.agekey" >/dev/null 2>&1
age-keygen -o "$temp/falsch.agekey" >/dev/null 2>&1
sicherung_empfaenger=$(age-keygen -y "$temp/sicherung.agekey")
probe_empfaenger=$(age-keygen -y "$temp/probe.agekey")

start=$SECONDS
if bash "$repo_root/deploy/backup/test/taeglich-test.sh" >"$temp/taeglich-shelltest.log" 2>&1; then
  bericht OK "Shelltests des täglichen Exports" "$((SECONDS - start))"
else
  status=$?
  bericht FEHLER "Shelltests des täglichen Exports (Exit $status)" "$((SECONDS - start))"
  tail -n 12 "$temp/taeglich-shelltest.log" >&2 || true
  exit 3
fi

baue_maven() {
  local start=$SECONDS
  if (cd "$backend" && mvn -ntp -Dquarkus.http.test-port=0 -DskipTests package) >"$temp/package.log" 2>&1; then
    bericht OK "Quarkus-Artefakt bauen" "$((SECONDS - start))"
  else
    local status=$?
    bericht FEHLER "Quarkus-Artefakt bauen (Exit $status)" "$((SECONDS - start))"
    grep -E 'BUILD FAILURE|\[ERROR\]' "$temp/package.log" | tail -n 12 >&2 || true
    exit 3
  fi
}

baue_image() {
  local name=$1
  shift
  local start=$SECONDS
  if "$@" >"$temp/$name.log" 2>&1; then
    bericht OK "$name" "$((SECONDS - start))"
  else
    local status=$?
    bericht FEHLER "$name (Exit $status)" "$((SECONDS - start))"
    tail -n 12 "$temp/$name.log" >&2 || true
    exit 3
  fi
}

baue_maven
baue_image "API-Testimage bauen" docker build --tag "$api_image" \
  --file "$backend/src/main/docker/Dockerfile.jvm" "$backend"
api_image_gebaut=1
baue_image "Keycloak-Testimage bauen" docker build --tag "$keycloak_image" \
  --file "$repo_root/deploy/infomaniak/keycloak/Dockerfile" "$repo_root/deploy"
keycloak_image_gebaut=1

start_dienste() {
  local ausgabe="$temp/compose-up.log"
  compose_started=1
  if compose up -d "$@" >"$ausgabe" 2>&1; then
    return 0
  fi
  if (( ! network_retry )) && grep -Fq 'all predefined address pools have been fully subnetted' "$ausgabe"; then
    network_retry=1
    compose down --remove-orphans >/dev/null 2>&1 || true
    if compose up -d "$@" >"$ausgabe" 2>&1; then
      bericht OK "Compose-Netzwerk nach Bereinigung des eigenen Stacks erneut gestartet" 0
      return 0
    fi
  fi
  tail -n 12 "$ausgabe" >&2 || true
  fehler "Eigener Restore-Compose-Stack konnte nicht gestartet werden."
}

# Nur PostgreSQL und MinIO veröffentlichen einen Port an 127.0.0.1 (Netz «hostzugang»).
dienstport() {
  local adresse port
  adresse=$(compose port "$1" "$2") || fehler "Compose-Port von $1 konnte nicht gelesen werden."
  port=${adresse##*:}
  [[ $port =~ ^[0-9]+$ ]] && (( port > 0 )) || fehler "Compose-Port von $1 fehlt (Antwort: ${adresse:-leer})."
  printf '%s\n' "$port"
}

warte_url() {
  local dienst=$1 port_container=$2 pfad=$3 beschreibung=$4
  local port url
  port=$(dienstport "$dienst" "$port_container")
  url="http://127.0.0.1:$port$pfad"
  for ((versuch = 0; versuch < 90; versuch++)); do
    if curl --fail --silent --show-error --max-time 2 "$url" >/dev/null 2>&1; then
      bericht OK "$beschreibung" 0
      return 0
    fi
    sleep 2
  done
  fehler "$beschreibung nicht bereit."
}

# Keycloak und die API hängen nur am internen Netz; geprüft wird über den Datenbank-Container (bash, /dev/tcp).
http_intern() {
  local dienst=$1 port=$2 pfad=$3
  compose exec -T db timeout 3 bash -c \
    'exec 3<>"/dev/tcp/$1/$2" && printf "GET %s HTTP/1.0\r\nHost: %s:%s\r\n\r\n" "$3" "$1" "$2" >&3 && cat <&3' \
    _ "$dienst" "$port" "$pfad" 2>/dev/null
}

http_ok() {
  local statuszeile=${1%%$'\n'*}
  [[ $statuszeile =~ ^HTTP/1\.[01]\ 200[[:space:]] ]]
}

warte_intern() {
  local dienst=$1 port=$2 pfad=$3 beschreibung=$4 antwort
  for ((versuch = 0; versuch < 90; versuch++)); do
    if antwort=$(http_intern "$dienst" "$port" "$pfad") && http_ok "$antwort"; then
      bericht OK "$beschreibung" 0
      return 0
    fi
    sleep 2
  done
  compose logs --no-color --tail 40 "$dienst" >&2 || true
  fehler "$beschreibung nicht bereit."
}

warte_keycloak_realm() {
  local antwort
  for ((versuch = 0; versuch < 90; versuch++)); do
    if antwort=$(http_intern kc_restore 8080 /realms/urinet-archiv/.well-known/openid-configuration) \
      && http_ok "$antwort" \
      && grep -Fq "\"issuer\":\"$kc_restore_base_url/realms/urinet-archiv\"" <<< "$antwort"; then
      bericht OK "Wiederhergestellter Keycloak-Realm erreichbar" 0
      return 0
    fi
    sleep 2
  done
  fehler "Wiederhergestellter Keycloak-Realm nicht bereit."
}

oidc_testanmeldung_vorbereiten() {
  local start=$SECONDS
  local client_id service_account service_account_id service_account_name
  keycloak_admin create clients -r urinet-archiv -f /run/secrets/restore_oidc_client >/dev/null \
    || fehler "OIDC-Testclient konnte im Wegwerf-Realm nicht angelegt werden."
  client_id=$(keycloak_admin get clients -r urinet-archiv -q "clientId=$oidc_client" |
    jq -er --arg client "$oidc_client" \
      '[.[] | select(.clientId == $client)] | if length == 1 then .[0].id else error("Client fehlt oder ist mehrdeutig") end') \
    || fehler "ID des OIDC-Testclients fehlt."
  service_account=$(keycloak_admin get "clients/$client_id/service-account-user" -r urinet-archiv) \
    || fehler "Dienstkonto des OIDC-Testclients konnte nicht gelesen werden."
  service_account_id=$(jq -er '.id | select(type == "string" and length > 0)' <<< "$service_account") \
    || fehler "ID des OIDC-Testdienstkontos fehlt."
  service_account_name=$(jq -er '.username | select(type == "string" and length > 0)' <<< "$service_account") \
    || fehler "Benutzername des OIDC-Testdienstkontos fehlt."
  [[ $service_account_name == "service-account-$oidc_client" ]] \
    || fehler "Keycloak hat ein unerwartetes OIDC-Testdienstkonto zurückgegeben."

  jq -n --arg id "$service_account_id" --arg username "$service_account_name" \
    '{id:$id, username:$username, enabled:true, requiredActions:[], attributes:{mandant:["urinet"]}}' \
    > "$temp/restore-oidc-service-account.json"
  chmod 644 "$temp/secrets/restore_oidc_service_account.json"
  cat "$temp/restore-oidc-service-account.json" > "$temp/secrets/restore_oidc_service_account.json"
  chmod 444 "$temp/secrets/restore_oidc_service_account.json"
  keycloak_admin update "users/$service_account_id" -r urinet-archiv \
    -f /run/secrets/restore_oidc_service_account >/dev/null \
    || fehler "Mandantenattribut des OIDC-Testdienstkontos konnte nicht gesetzt werden."
  keycloak_admin add-roles -r urinet-archiv --uusername "$service_account_name" \
    --rolename inhaber >/dev/null \
    || fehler "Rolle des OIDC-Testdienstkontos konnte nicht gesetzt werden."
  bericht OK "OIDC-Testdienstkonto mit Mandant und Rolle vorbereiten" "$((SECONDS - start))"
}

keycloak_admin() {
  compose exec -T kc sh -eu -c '
    admin_password=$(cat /run/secrets/keycloak_admin)
    cli=/opt/keycloak/bin/kcadm.sh
    config=$(mktemp -d /tmp/kcadm-restore.XXXXXX)
    trap '\''rm -rf -- "$config"'\'' EXIT
    config="$config/kcadm.config"
    "$cli" config credentials --server http://kc:8080 --realm master \
      --user archiv-admin --password "$admin_password" --config "$config" >/dev/null
    "$cli" "$@" --config "$config"
  ' sh "$@"
}

test_maven() {
  local phase=$1 stufe=$2 praefix=$3 datenbank=$4
  local start=$SECONDS
  local args=(
    -ntp
    -Dtest=SicherungPaketRoundtripTest
    -Dquarkus.http.test-port=0
    -Darchiv.restore.e2e=true
    "-Darchiv.restore.phase=$phase"
    "-Darchiv.restore.stufe=$stufe"
    "-Darchiv.restore.bucket-praefix=$praefix"
    "-Darchiv.restore.state=$temp/zustand.properties"
    "-Darchiv.restore.verzeichnis=$temp/entschluesselt"
    "-Dsmallrye.config.source.file.locations=$temp/secrets"
    "-Dquarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:$db_port/$datenbank"
    "-Dquarkus.datasource.username=archiv_app"
    "-Dquarkus.liquibase.username=archiv_owner"
    "-Dquarkus.s3.endpoint-override=http://127.0.0.1:$minio_port"
    "-Dquarkus.s3.aws.region=us-east-1"
  )
  if [[ $phase == ziel ]]; then
    args+=(
      "-Darchiv.restore.oidc-url=$kc_restore_base_url/realms/urinet-archiv"
      "-Darchiv.restore.oidc-client=$oidc_client"
      "-Darchiv.restore.oidc-client-secret-datei=$temp/secrets/restore_oidc_client_secret"
    )
  fi
  if (cd "$backend" && mvn "${args[@]}" test) >"$temp/$phase-test.log" 2>&1; then
    bericht OK "Echter Sicherungs-Roundtrip: Phase $phase" "$((SECONDS - start))"
  else
    local status=$?
    bericht FEHLER "Sicherungs-Roundtrip Phase $phase (Exit $status)" "$((SECONDS - start))"
    grep -E 'Tests run:|BUILD FAILURE|\[ERROR\]' "$temp/$phase-test.log" | tail -n 12 >&2 || true
    if [[ $phase == ziel ]]; then
      compose logs --no-color --tail 60 kc_restore >&2 || true
    fi
    exit 3
  fi
}

pruefe_mandantendateien() {
  local mandant datei
  for mandant in "${mandanten[@]}"; do
    for datei in "inventar-$mandant.jsonl" "bucket-$mandant.bin"; do
      [[ -s $temp/entschluesselt/$datei ]] \
        || fehler "Sicherungspaket für Mandant $mandant ist unvollständig ($datei fehlt)."
    done
  done
}

pruefe_schema() {
  local mandant=$1 schema=${1//-/_} relation
  schema="m_$schema"
  relation=$(compose exec -T db psql --no-psqlrc --username=postgres --dbname=archiv_restore \
    --tuples-only --no-align --command "select to_regclass('$schema.dokument')") \
    || fehler "Schema für Mandant $mandant konnte nach der Wiederherstellung nicht geprüft werden."
  [[ $relation == "$schema.dokument" ]] \
    || fehler "Wiederhergestelltes Schema für Mandant $mandant fehlt."
}

start_dienste db minio kc

container=$(compose ps --quiet db)
for ((versuch = 0; versuch < 90; versuch++)); do
  status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")
  [[ $status == healthy ]] && break
  sleep 2
done
[[ $status == healthy ]] || fehler "PostgreSQL nicht bereit."
bericht OK "PostgreSQL bereit" 0
warte_url minio 9000 /minio/health/ready "MinIO bereit"
warte_intern kc 9000 /health/ready "Keycloak bereit"
oidc_testanmeldung_vorbereiten

start_dienste api
warte_intern api 8080 /q/health/ready "Quell-Anwendung bereit"

db_port=$(dienstport db 5432)
minio_port=$(dienstport minio 9000)

test_maven quelle A urinet-archiv-a- archiv

start=$SECONDS
if ARCHIV_COMPOSE_FILE="$compose_file" \
  ARCHIV_SICHERUNGSVERZEICHNIS="$temp/backup" \
  ARCHIV_MANDANTEN="${mandanten[*]}" \
  ARCHIV_AGE_EMPFAENGER_SICHERUNG="$sicherung_empfaenger" \
  ARCHIV_AGE_EMPFAENGER_PROBE="$probe_empfaenger" \
  ARCHIV_VOLLEXPORT=1 \
  bash "$repo_root/deploy/backup/taeglich.sh" >"$temp/taeglich.log" 2>&1; then
  bericht OK "Echter täglicher Export mit zwei Wegwerf-age-Schlüsseln" "$((SECONDS - start))"
else
  status=$?
  bericht FEHLER "Täglicher Sicherungsexport (Exit $status)" "$((SECONDS - start))"
  tail -n 12 "$temp/taeglich.log" >&2 || true
  exit 3
fi

lauf=$(find "$temp/backup/laeufe" -mindepth 1 -maxdepth 1 -type d -name 'lauf-*' -print -quit)
[[ -n $lauf && -s $lauf/FERTIG && -s $lauf/SHA256SUMS ]] || fehler "Vollständiger Sicherungslauf fehlt."
pruefsummen_vollstaendig "$lauf" || fehler "SHA256SUMS nennt nicht genau die verschlüsselten Dateien des Laufs."
if pruefsummen_pruefen "$lauf" >"$temp/checksum.log" 2>&1; then
  bericht OK "SHA256SUMS des Sicherungspakets prüfen" 0
else
  tail -n 8 "$temp/checksum.log" >&2 || true
  fehler "SHA256SUMS-Prüfung fehlgeschlagen."
fi

erste_datei=$(awk 'NR == 1 { print $2; exit }' "$lauf/SHA256SUMS")
[[ -n $erste_datei && -s $lauf/$erste_datei ]] || fehler "Sicherungsartefakt fehlt."
if age --decrypt --identity "$temp/falsch.agekey" --output /dev/null "$lauf/$erste_datei" \
  >/dev/null 2>&1; then
  fehler "Falscher age-Schlüssel wurde nicht zurückgewiesen."
fi
bericht OK "Falschen age-Schlüssel zurückweisen" 0

mkdir "$temp/manipuliert"
cp -- "$lauf"/*.age "$temp/manipuliert/"
# Nur die erste Prüfsumme ersetzen; Trennzeichen und Dateiname bleiben, damit «--strict» die Zeile als gültig
# formatiert annimmt und allein der falsche Hash zur Ablehnung führt.
sed -E "1s/^[0-9a-fA-F]{64}/$(printf '%064d' 0)/" "$lauf/SHA256SUMS" > "$temp/manipuliert/SHA256SUMS"
cmp -s -- "$lauf/SHA256SUMS" "$temp/manipuliert/SHA256SUMS" && fehler "SHA256SUMS konnte nicht manipuliert werden."
if pruefsummen_pruefen "$temp/manipuliert" >/dev/null 2>&1; then
  fehler "Manipulierte SHA256SUMS wurde nicht zurückgewiesen."
fi
bericht OK "Manipulierte SHA256SUMS zurückweisen" 0

for artefakt in "$lauf"/*.age; do
  dateiname=${artefakt##*/}
  age --decrypt --identity "$temp/sicherung.agekey" --output "$temp/entschluesselt/${dateiname%.age}" "$artefakt"
  age --decrypt --identity "$temp/probe.agekey" \
    --output "$temp/entschluesselt-probe/${dateiname%.age}" "$artefakt"
  cmp -- "$temp/entschluesselt/${dateiname%.age}" "$temp/entschluesselt-probe/${dateiname%.age}" \
    || fehler "Sicherungs- und Probe-Schlüssel liefern verschiedene Exportdateien."
done
pruefe_mandantendateien
tar -tf "$temp/entschluesselt/keycloak-realm.tar" >/dev/null \
  || fehler "Wiederhergestelltes Keycloak-Realm-Archiv ist ungültig."
openssl pkey -in "$temp/entschluesselt/manifest.key" -noout >/dev/null 2>&1 \
  || fehler "Wiederhergestellter Manifest-Schlüssel ist ungültig."
bericht OK "Alle Sicherungsartefakte mit beiden Wegwerf-Schlüsseln entschlüsseln" 0

compose exec -T db createdb --username=postgres --owner=archiv_owner archiv_restore \
  || fehler "Leere Ziel-Datenbank konnte nicht angelegt werden."
if compose exec -T db pg_restore --no-password --username=postgres --dbname=archiv_restore \
  --exit-on-error --single-transaction < "$temp/entschluesselt/archiv.dump" \
  >"$temp/archiv-pg-restore.log" 2>&1; then
  bericht OK "Archiv-Datenbankdump in neue Datenbank einspielen" 0
else
  tail -n 12 "$temp/archiv-pg-restore.log" >&2 || true
  fehler "Archiv-Datenbankdump konnte nicht eingespielt werden."
fi
for mandant in "${mandanten[@]}"; do
  pruefe_schema "$mandant"
done

compose exec -T db createdb --username=postgres --owner=keycloak keycloak_restore \
  || fehler "Leere Keycloak-Ziel-Datenbank konnte nicht angelegt werden."
if compose exec -T db pg_restore --no-password --username=postgres --dbname=keycloak_restore \
  --exit-on-error --single-transaction < "$temp/entschluesselt/keycloak.dump" \
  >"$temp/keycloak-pg-restore.log" 2>&1; then
  bericht OK "Keycloak-Datenbankdump in neue Datenbank einspielen" 0
else
  tail -n 12 "$temp/keycloak-pg-restore.log" >&2 || true
  fehler "Keycloak-Datenbankdump konnte nicht eingespielt werden."
fi

start_dienste kc_restore
[[ $(dienstport kc_restore 8080) == "$kc_restore_port" ]] \
  || fehler "Der veröffentlichte Loopback-Port des wiederhergestellten Keycloak stimmt nicht."
warte_intern kc_restore 9000 /health/ready "Wiederhergestelltes Keycloak bereit"
warte_keycloak_realm

test_maven ziel P urinet-archiv-p- archiv_restore

bericht OK "Vollständiger lokaler Sicherungs-Roundtrip abgeschlossen" "$((SECONDS))"
