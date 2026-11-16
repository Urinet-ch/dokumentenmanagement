#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
script="$repo_root/deploy/backup/taeglich.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT

mock_bin="$temp/bin"
mkdir -p "$mock_bin"
export MOCK_LOG="$temp/commands.log"
export MOCK_FAIL_ZUWACHS=0
export MOCK_FAIL_FESTHALTEN=0
export MOCK_FAIL_REALM=0
export MOCK_EMPTY_BUCKET=0
: > "$MOCK_LOG"

cat > "$mock_bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "$MOCK_LOG"

if [[ $1 == container && $2 == inspect ]]; then
  exit 1
fi
if [[ $1 == cp ]]; then
  case "$2" in
    */tmp/archiv-export/.)
      mkdir -p "$3"
      printf '{"realm":"synthetic"}\n' > "$3/realm-export.json"
      printf '{"users":[]}\n' > "$3/users-0.json"
      ;;
    *)
      printf 'synthetic-private-sentinel\n' > "$3"
      ;;
  esac
  exit 0
fi
if [[ $1 == rm ]]; then
  exit 0
fi
if [[ $1 != compose ]]; then
  exit 0
fi
shift
while [[ ${1:-} == --file ]]; do shift 2; done
subcommand=$1
shift

case "$subcommand" in
  ps)
    printf 'synthetic-container-id\n'
    ;;
  stop|start)
    ;;
  exec)
    [[ ${1:-} == -T ]] && shift
    service=$1
    shift
    if [[ $service == db && ${1:-} == pg_dump ]]; then
      printf 'synthetic-database-dump\n'
    elif [[ $service == api ]]; then
      [[ ${1:-} == sh && ${2:-} == -c ]] || exit 1
      shift 4
      case ${1:-} in
        sicherung)
          shift
          case ${1:-} in
            inventar)
              if [[ $MOCK_EMPTY_BUCKET != 1 ]]; then
                printf '{"format":"urinet-sicherungsinventar-v1"}\n'
              fi
              ;;
            zuwachs)
              if [[ $MOCK_FAIL_ZUWACHS == 1 ]]; then exit 3; fi
              [[ ${2:-} == --mandant ]] || exit 1
              if [[ $MOCK_EMPTY_BUCKET != 1 ]]; then
                printf 'URINET-BUCKET-EXPORT-V1\nsynthetic-bucket-stream\n'
              fi
              ;;
            festhalten)
              if [[ $MOCK_FAIL_FESTHALTEN == 1 ]]; then exit 3; fi
              ;;
            *)
              exit 1
              ;;
          esac
          ;;
        *)
          exit 1
          ;;
      esac
    fi
    ;;
  cp)
    printf 'synthetic-private-sentinel\n' > "$2"
    ;;
  run)
    while [[ ${1:-} == --* || ${1:-} == -T ]]; do
      case "$1" in
        --name|--entrypoint)
          shift 2
          ;;
        *)
          shift
          ;;
      esac
    done
    service=$1
    shift
    if [[ $service == kc ]]; then
      if [[ $MOCK_FAIL_REALM == 1 ]]; then exit 3; fi
      :
    else
      exit 1
    fi
    ;;
  *)
    exit 1
    ;;
esac
MOCK

cat > "$mock_bin/age" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf 'age %s\n' "$*" >> "$MOCK_LOG"
[[ $1 == --encrypt ]] || exit 1
shift
[[ $1 == --recipient && $2 == age1backuprecipient ]] || exit 1
shift 2
[[ $1 == --recipient && $2 == age1proberecipient ]] || exit 1
shift 2
[[ $1 == --output ]] || exit 1
output=$2
input=$3
[[ -f $input ]] || exit 1
printf 'synthetic ciphertext\n' > "$output"
MOCK

chmod +x "$mock_bin/docker" "$mock_bin/age"

export PATH="$mock_bin:$PATH"
export ARCHIV_COMPOSE_FILE="$temp/compose.yml"
export ARCHIV_SICHERUNGSVERZEICHNIS="$temp/backup"
export ARCHIV_MANDANTEN='urinet vhf-gsk'
export ARCHIV_AGE_EMPFAENGER_SICHERUNG=age1backuprecipient
export ARCHIV_AGE_EMPFAENGER_PROBE=age1proberecipient
export ARCHIV_DOCKER_BIN=docker
export ARCHIV_AGE_BIN=age
printf 'synthetic compose\n' > "$ARCHIV_COMPOSE_FILE"

assert() {
  if ! "$@"; then
    printf 'Test fehlgeschlagen: %s\n' "$*" >&2
    exit 1
  fi
}

if ! "$script" 2> "$temp/success.stderr"; then
  cat "$temp/success.stderr" >&2
  exit 1
fi
run_dir=$(find "$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe" -mindepth 1 -maxdepth 1 -type d -name 'lauf-*' -print -quit)
assert test -n "$run_dir"
assert test -s "$run_dir/SHA256SUMS"
assert test -s "$run_dir/archiv.dump.age"
assert test -s "$run_dir/keycloak.dump.age"
assert test -s "$run_dir/keycloak-realm.tar.age"
assert test -s "$run_dir/manifest.key.age"
assert test -s "$run_dir/bucket-urinet.bin.age"
assert test -s "$run_dir/inventar-urinet.jsonl.age"
assert test -s "$run_dir/bucket-vhf-gsk.bin.age"
assert test -s "$run_dir/inventar-vhf-gsk.jsonl.age"
assert test ! -e "$run_dir/archiv.dump"
assert test -s "$run_dir/FERTIG"
assert test -z "$(find "$run_dir" -type f ! -name '*.age' ! -name 'SHA256SUMS' ! -name 'FERTIG' -print -quit)"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$run_dir" && sha256sum --check SHA256SUMS >/dev/null)
else
  (cd "$run_dir" && shasum -a 256 --check SHA256SUMS >/dev/null)
fi
assert test -s "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/aktuell"
assert test -s "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/laeufe/$(< "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/aktuell")/inventar-urinet.jsonl"
assert grep -q 'age --encrypt --recipient age1backuprecipient --recipient age1proberecipient' "$MOCK_LOG"
if grep -q 'synthetic-private-sentinel' "$MOCK_LOG"; then
  printf 'Geheimer Inhalt ist in den aufgerufenen Argumenten sichtbar.\n' >&2
  exit 1
fi
assert grep -q 'docker compose --file .* exec -T api sh -c' "$MOCK_LOG"
assert grep -q 'sicherung zuwachs --mandant urinet' "$MOCK_LOG"
inventar_zeile=$(grep -n 'sicherung inventar --mandant urinet' "$MOCK_LOG" | head -n 1 | cut -d: -f1)
zuwachs_zeile=$(grep -n 'sicherung zuwachs --mandant urinet' "$MOCK_LOG" | head -n 1 | cut -d: -f1)
assert test "$inventar_zeile" -lt "$zuwachs_zeile"
assert grep -q 'festhalten --art bucket --mandant urinet' "$MOCK_LOG"
assert grep -q 'stop --timeout 60 kc' "$MOCK_LOG"
assert grep -q 'start kc' "$MOCK_LOG"
assert grep -q 'docker rm archiv-realm-export-' "$MOCK_LOG"
assert grep -q 'Hinweis: Erster Export' "$temp/success.stderr"

old_run="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/lauf-20000101T000000Z-1"
unrelated="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/not-a-backup"
mkdir -p "$old_run" "$unrelated"
printf 'old\n' > "$old_run/old.age"
printf 'keep\n' > "$unrelated/keep"
touch -t 200001010000 "$old_run" "$old_run/old.age"
if ! "$script" 2> "$temp/second.stderr"; then
  cat "$temp/second.stderr" >&2
  exit 1
fi
assert test ! -e "$old_run"
assert test -s "$unrelated/keep"
assert grep -q -- '--inventar-vorher -' "$MOCK_LOG"

failed_root="$temp/failed-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$failed_root
export MOCK_FAIL_ZUWACHS=1
if "$script" > /dev/null 2> "$temp/failure.stderr"; then
  printf 'Fehlerfall hätte fehlschlagen müssen.\n' >&2
  exit 1
fi
assert test -z "$(find "$failed_root/laeufe" -mindepth 1 -maxdepth 1 -type d -print -quit)"
assert test ! -e "$failed_root/.zustand/aktuell"
if grep -q 'Keycloak konnte nach dem Realm-Export nicht gestartet werden' "$temp/failure.stderr"; then
  printf 'Keycloak wurde nach einem Exportfehler nicht korrekt gestartet.\n' >&2
  exit 1
fi
assert grep -q 'Vollständiger Bucket-Export konnte nicht erstellt werden' "$temp/failure.stderr"
assert grep -q 'start kc' "$MOCK_LOG"

realm_failed_root="$temp/realm-failed-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$realm_failed_root
export MOCK_FAIL_ZUWACHS=0
export MOCK_FAIL_REALM=1
if "$script" > /dev/null 2> "$temp/realm-failure.stderr"; then
  printf 'Realm-Export-Fehler hätte fehlschlagen müssen.\n' >&2
  exit 1
fi
assert test -z "$(find "$realm_failed_root/laeufe" -mindepth 1 -maxdepth 1 -type d -print -quit)"
assert grep -q 'Keycloak-Realm konnte nicht exportiert werden' "$temp/realm-failure.stderr"
assert grep -q 'docker rm -f archiv-realm-export-' "$MOCK_LOG"
assert grep -q 'start kc' "$MOCK_LOG"

register_failed_root="$temp/register-failed-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$register_failed_root
export MOCK_FAIL_REALM=0
export MOCK_FAIL_FESTHALTEN=1
if "$script" > /dev/null 2> "$temp/register-failure.stderr"; then
  printf 'Register-Fehler hätte fehlschlagen müssen.\n' >&2
  exit 1
fi
register_failed_run=$(find "$register_failed_root/laeufe" -mindepth 1 -maxdepth 1 -type d -name 'lauf-*' -print -quit)
assert test -n "$register_failed_run"
assert test ! -e "$register_failed_run/FERTIG"
assert test ! -e "$register_failed_root/.zustand/aktuell"
assert grep -q 'SHA-256 konnte nicht im Sicherungsregister festgehalten werden' "$temp/register-failure.stderr"

duplicate_root="$temp/duplicate-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$duplicate_root
export ARCHIV_MANDANTEN='urinet urinet'
if "$script" > /dev/null 2> "$temp/duplicate.stderr"; then
  printf 'Doppelte Mandanten hätten abgelehnt werden müssen.\n' >&2
  exit 1
fi
assert grep -q 'Mandant ist mehrfach angegeben' "$temp/duplicate.stderr"
assert test -z "$(find "$duplicate_root/laeufe" -mindepth 1 -maxdepth 1 -type d -print -quit)"

empty_root="$temp/empty-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$empty_root
export ARCHIV_MANDANTEN=urinet
export MOCK_EMPTY_BUCKET=1
export MOCK_FAIL_ZUWACHS=0
export MOCK_FAIL_FESTHALTEN=0
export MOCK_FAIL_REALM=0
if ! "$script" 2> "$temp/empty.stderr"; then
  cat "$temp/empty.stderr" >&2
  exit 1
fi
empty_run=$(find "$empty_root/laeufe" -mindepth 1 -maxdepth 1 -type d -name 'lauf-*' -print -quit)
assert test -n "$empty_run"
assert test -s "$empty_run/bucket-urinet.bin.age"
assert test -s "$empty_run/inventar-urinet.jsonl.age"
assert test -s "$empty_run/FERTIG"

export ARCHIV_SICHERUNGSVERZEICHNIS=/
if "$script" > /dev/null 2> "$temp/root-path.stderr"; then
  printf 'Das Stammverzeichnis hätte als Sicherungsverzeichnis abgelehnt werden müssen.\n' >&2
  exit 1
fi
assert grep -q 'Sicherungsverzeichnis darf nicht das Stammverzeichnis sein' "$temp/root-path.stderr"

printf 'Shell-Tests erfolgreich.\n'
