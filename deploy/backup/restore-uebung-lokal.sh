#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
backend="$repo_root/backend"
compose_file="$repo_root/deploy/backup/test/restore-compose.yml"
temp=$(mktemp -d)
chmod 700 "$temp"
project="archiv-restore-$$"
compose_started=0
network_retry=0
export ARCHIV_RESTORE_TMP="$temp"
export COMPOSE_PROJECT_NAME="$project"

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
    (cd "$1" && sha256sum --check SHA256SUMS)
  else
    (cd "$1" && shasum -a 256 -c SHA256SUMS)
  fi
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
  if ! rm -rf -- "$temp"; then
    printf 'FEHLER  Temporäre Restore-Testdaten konnten nicht entfernt werden.\n' >&2
    (( status != 0 )) || status=3
  fi
  exit "$status"
}
trap aufräumen EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for programm in bash mvn docker age age-keygen openssl curl tar awk grep cmp; do
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

for image in \
  pgvector/pgvector:0.8.7-pg17-trixie \
  quay.io/keycloak/keycloak:26.8.2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 \
  eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c \
  cgr.dev/chainguard/minio:latest@sha256:59953701f8f8d0de81aab456f8b0b6c0a1804119415559bf7ede79d944108879; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    printf 'FEHLER  Lokales Test-Image fehlt: %s\n' "$image" >&2
    exit 2
  fi
done

mkdir -p "$temp/secrets" "$temp/postgres" "$temp/minio" "$temp/backup" \
  "$temp/entschluesselt" "$temp/entschluesselt-probe"
chmod 777 "$temp/minio"

geheimnis_erzeugen() {
  openssl rand -hex 32 > "$temp/secrets/$1"
}

for geheimnis in postgres_password archiv_db_owner archiv_db_app keycloak_db keycloak_admin; do
  geheimnis_erzeugen "$geheimnis"
done
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
sicherung_empfaenger=$(age-keygen -y -i "$temp/sicherung.agekey")
probe_empfaenger=$(age-keygen -y -i "$temp/probe.agekey")

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
  if (cd "$backend" && mvn -ntp -DskipTests package) >"$temp/package.log" 2>&1; then
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
baue_image "API-Testimage bauen" docker build --tag archiv:restore-roundtrip \
  --file "$backend/src/main/docker/Dockerfile.jvm" "$backend"
baue_image "Keycloak-Testimage bauen" docker build --tag archiv:restore-roundtrip-kc \
  --file "$repo_root/deploy/infomaniak/keycloak/Dockerfile" "$repo_root/deploy"

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

dienstport() {
  local adresse
  adresse=$(compose port "$1" "$2") || fehler "Compose-Port konnte nicht gelesen werden."
  printf '%s\n' "${adresse##*:}"
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

warte_keycloak_realm() {
  local port url
  port=$(dienstport kc_restore 8080)
  url="http://127.0.0.1:$port/realms/urinet-archiv/.well-known/openid-configuration"
  for ((versuch = 0; versuch < 90; versuch++)); do
    if curl --fail --silent --max-time 2 -H 'Host: kc_restore:8080' "$url" \
      | grep -q '"issuer":"http://kc_restore:8080/realms/urinet-archiv"'; then
      bericht OK "Wiederhergestellter Keycloak-Realm erreichbar" 0
      return 0
    fi
    sleep 2
  done
  fehler "Wiederhergestellter Keycloak-Realm nicht bereit."
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
    "-Darchiv.restore.inventar=$temp/entschluesselt/inventar-urinet.jsonl"
    "-Darchiv.restore.bucket=$temp/entschluesselt/bucket-urinet.bin"
    "-Dsmallrye.config.source.file.locations=$temp/secrets"
    "-Dquarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:$db_port/$datenbank"
    "-Dquarkus.datasource.username=archiv_app"
    "-Dquarkus.liquibase.username=archiv_owner"
    "-Dquarkus.s3.endpoint-override=http://127.0.0.1:$minio_port"
    "-Dquarkus.s3.aws.region=us-east-1"
  )
  if (cd "$backend" && mvn "${args[@]}" test) >"$temp/$phase-test.log" 2>&1; then
    bericht OK "Echter Sicherungs-Roundtrip: Phase $phase" "$((SECONDS - start))"
  else
    local status=$?
    bericht FEHLER "Sicherungs-Roundtrip Phase $phase (Exit $status)" "$((SECONDS - start))"
    grep -E 'Tests run:|BUILD FAILURE|\[ERROR\]' "$temp/$phase-test.log" | tail -n 12 >&2 || true
    exit 3
  fi
}

compose config --quiet || fehler "Restore-Compose-Konfiguration ist ungültig."
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
warte_url kc 9000 /health/ready "Keycloak bereit"

start_dienste api
warte_url api 8080 /q/health/ready "Quell-Anwendung bereit"

db_port=$(dienstport db 5432)
minio_port=$(dienstport minio 9000)

test_maven quelle A urinet-archiv-a- archiv

start=$SECONDS
if ARCHIV_COMPOSE_FILE="$compose_file" \
  ARCHIV_SICHERUNGSVERZEICHNIS="$temp/backup" \
  ARCHIV_MANDANTEN=urinet \
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
awk 'NR == 1 { $1 = sprintf("%064d", 0) } { print }' "$lauf/SHA256SUMS" \
  > "$temp/manipuliert/SHA256SUMS"
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
warte_url kc_restore 9000 /health/ready "Wiederhergestelltes Keycloak bereit"
warte_keycloak_realm

test_maven ziel P urinet-archiv-p- archiv_restore

bericht OK "Vollständiger lokaler Sicherungs-Roundtrip abgeschlossen" "$((SECONDS))"
