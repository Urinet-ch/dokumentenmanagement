#!/bin/bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
script="$repo_root/deploy/backup/restore-uebung-lokal.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT

bin="$temp/bin"
mkdir -p "$bin"
export MOCK_LOG="$temp/commands.log"
export MOCK_BACKUP_TEST="$repo_root/deploy/backup/test/taeglich-test.sh"
: > "$MOCK_LOG"

cat > "$bin/bash" <<'MOCK'
#!/bin/bash
if [[ ${1:-} == "$MOCK_BACKUP_TEST" ]]; then
  printf 'shelltest %s\n' "$*" >> "$MOCK_LOG"
  exit 0
fi
exec /bin/bash "$@"
MOCK

cat > "$bin/docker" <<'MOCK'
#!/bin/bash
printf 'docker %s\n' "$*" >> "$MOCK_LOG"
exit 0
MOCK

cat > "$bin/mvn" <<'MOCK'
#!/bin/bash
printf 'mvn cwd=%s %s\n' "$PWD" "$*" >> "$MOCK_LOG"
exit "${MOCK_FAIL_MVN:-0}"
MOCK

for programm in age age-keygen; do
  cat > "$bin/$programm" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
done
chmod +x "$bin"/*
export PATH="$bin:$PATH"

assert() {
  if ! "$@"; then
    printf 'Test fehlgeschlagen: %s\n' "$*" >&2
    exit 1
  fi
}

if ! "$script" >"$temp/erfolg.stdout" 2>"$temp/erfolg.stderr"; then
  cat "$temp/erfolg.stderr" >&2
  exit 1
fi
assert grep -q '^OK      Täglicher Export' "$temp/erfolg.stdout"
assert grep -q '^OK      Restore mit PostgreSQL' "$temp/erfolg.stdout"
assert grep -q '^OK      Übungsdurchlauf abgeschlossen' "$temp/erfolg.stdout"
assert grep -q "shelltest $MOCK_BACKUP_TEST" "$MOCK_LOG"
assert grep -q 'docker info' "$MOCK_LOG"
assert grep -q 'docker image inspect pgvector/pgvector:0.8.7-pg17-trixie' "$MOCK_LOG"
assert grep -q "mvn cwd=$repo_root/backend -Dtest=WiederherstellungTest,SicherungKommandoDbTest,SicherungExportS3Test,SicherungRestoreUebungTest,SicherungWiederherstellungKommandoTest -Dquarkus.http.test-port=0 test" \
  "$MOCK_LOG"

: > "$MOCK_LOG"
if MOCK_FAIL_MVN=1 "$script" >"$temp/fehler.stdout" 2>"$temp/fehler.stderr"; then
  printf 'Der Maven-Fehler hätte den Übungslauf abbrechen müssen.\n' >&2
  exit 1
else
  status=$?
  assert test "$status" -eq 3
fi
assert grep -q '^OK      Täglicher Export' "$temp/fehler.stdout"
assert grep -q '^FEHLER  Restoretests (Exit 1)' "$temp/fehler.stdout"
assert test ! -s "$temp/fehler.stderr"
if grep -q '^OK      Übungsdurchlauf abgeschlossen' "$temp/fehler.stdout"; then
  printf 'Der Übungslauf wurde trotz Testfehler als abgeschlossen gemeldet.\n' >&2
  exit 1
fi
