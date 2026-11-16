#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
backend="$repo_root/backend"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT

bericht() {
  printf '%-7s %s (%ss)\n' "$1" "$2" "$3"
}

voraussetzung() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'FEHLER  Voraussetzung fehlt: %s\n' "$1" >&2
    exit 2
  }
}

for programm in bash mvn docker age age-keygen; do
  voraussetzung "$programm"
done

if ! docker info >/dev/null 2>&1; then
  printf 'FEHLER  Docker ist nicht verfügbar.\n' >&2
  exit 2
fi

for image in \
  pgvector/pgvector:0.8.7-pg17-trixie \
  quay.io/keycloak/keycloak:26.8.2 \
  cgr.dev/chainguard/minio:latest \
  axllent/mailpit:v1.33.1; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    printf 'FEHLER  Lokales Test-Image fehlt: %s\n' "$image" >&2
    exit 2
  fi
done

start=$SECONDS
shell_start=$SECONDS
if bash "$repo_root/deploy/backup/test/taeglich-test.sh" >"$temp/sicherung.log" 2>&1; then
  bericht OK "Täglicher Export (Attrappen und zwei Wegwerf-age-Schlüssel)" "$((SECONDS - shell_start))"
else
  status=$?
  bericht FEHLER "Täglicher Exporttest (Exit $status)" "$((SECONDS - shell_start))"
  exit 3
fi

restore_start=$SECONDS
if (
  cd "$backend"
  mvn -Dtest=WiederherstellungTest,SicherungKommandoDbTest,SicherungExportS3Test,SicherungRestoreUebungTest,SicherungWiederherstellungKommandoTest \
    -Dquarkus.http.test-port=0 test
) >"$temp/wiederherstellung.log" 2>&1; then
  bericht OK "Restore mit PostgreSQL, MinIO und gestarteter Quarkus-Anwendung" "$((SECONDS - restore_start))"
else
  status=$?
  bericht FEHLER "Restoretests (Exit $status)" "$((SECONDS - restore_start))"
  grep -E 'Tests run:|BUILD FAILURE|\[ERROR\]' "$temp/wiederherstellung.log" | tail -n 8 >&2 || true
  exit 3
fi

bericht OK "Übungsdurchlauf abgeschlossen" "$((SECONDS - start))"
