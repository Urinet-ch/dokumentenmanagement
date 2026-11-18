#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
script="$repo_root/deploy/backup/restore-uebung-lokal.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT

bin="$temp/bin"
mkdir -p "$bin"
export MOCK_LOG="$temp/commands.log"
export MOCK_BACKUP_TEST="$repo_root/deploy/backup/test/taeglich-test.sh"
export MOCK_TAEGLICH_SCRIPT="$repo_root/deploy/backup/taeglich.sh"
: > "$MOCK_LOG"

cat > "$bin/bash" <<'MOCK'
#!/bin/bash
if [[ ${1:-} == "$MOCK_BACKUP_TEST" ]]; then
  printf 'shelltest %s\n' "$*" >> "$MOCK_LOG"
  exit 0
fi
if [[ ${1:-} == "$MOCK_TAEGLICH_SCRIPT" ]]; then
  printf 'taeglich %s\n' "$*" >> "$MOCK_LOG"
  lauf="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/lauf-20260101T000000Z-1"
  realm="$ARCHIV_RESTORE_TMP/fake-realm/realm"
  mkdir -p "$lauf"
  for datei in archiv.dump keycloak.dump inventar-urinet.jsonl bucket-urinet.bin; do
    printf 'synthetisch:%s\n' "$datei" > "$lauf/$datei.age"
  done
  mkdir -p "$realm"
  printf 'synthetischer Realm\n' > "$realm/urinet-archiv-realm.json"
  tar -cf "$lauf/keycloak-realm.tar.age" -C "$ARCHIV_RESTORE_TMP/fake-realm" realm
  openssl genpkey -algorithm Ed25519 -out "$ARCHIV_RESTORE_TMP/fake-manifest.key" >/dev/null 2>&1
  cp "$ARCHIV_RESTORE_TMP/fake-manifest.key" "$lauf/manifest.key.age"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$lauf" && sha256sum ./*.age > SHA256SUMS)
  else
    (cd "$lauf" && shasum -a 256 ./*.age > SHA256SUMS)
  fi
  printf 'vollständig\n' > "$lauf/FERTIG"
  exit 0
fi
exec /bin/bash "$@"
MOCK

cat > "$bin/docker" <<'MOCK'
#!/bin/bash
printf 'docker %s\n' "$*" >> "$MOCK_LOG"
if [[ ${1:-} == info || ${1:-} == build ]]; then
  exit 0
fi
if [[ ${1:-} == image && ${2:-} == inspect ]]; then
  exit 0
fi
if [[ ${1:-} == inspect ]]; then
  printf 'healthy\n'
  exit 0
fi
if [[ ${1:-} == compose ]]; then
  case " $* " in
    *" ps --quiet db "*) printf 'mock-container\n' ;;
    *" port db 5432 "*) printf '127.0.0.1:25432\n' ;;
    *" port minio 9000 "*) printf '127.0.0.1:29000\n' ;;
    *" port kc 9000 "*) printf '127.0.0.1:29001\n' ;;
    *" port api 8080 "*) printf '127.0.0.1:28080\n' ;;
    *" port kc_restore 9000 "*) printf '127.0.0.1:29002\n' ;;
    *" port kc_restore 8080 "*) printf '127.0.0.1:28081\n' ;;
  esac
  exit 0
fi
exit 0
MOCK

cat > "$bin/mvn" <<'MOCK'
#!/bin/bash
printf 'mvn cwd=%s %s\n' "$PWD" "$*" >> "$MOCK_LOG"
if [[ ${MOCK_FAIL_MVN:-0} == 1 && " $* " == *" -Darchiv.restore.phase=ziel "* ]]; then
  printf '[ERROR] forced test failure\n' >&2
  exit 1
fi
MOCK

cat > "$bin/age-keygen" <<'MOCK'
#!/bin/bash
if [[ ${1:-} == -o ]]; then
  printf 'synthetic identity\n' > "$2"
elif [[ ${1:-} == -y ]]; then
  printf 'age1synthetic\n'
fi
MOCK

cat > "$bin/age" <<'MOCK'
#!/bin/bash
identity=
output=
input=
while (($#)); do
  case "$1" in
    --decrypt) shift ;;
    --identity) identity=$2; shift 2 ;;
    --output) output=$2; shift 2 ;;
    *) input=$1; shift ;;
  esac
done
[[ $identity != *falsch.agekey ]] || exit 1
case ${input##*/} in
  archiv.dump.age) printf 'archiv-dump\n' > "$output" ;;
  keycloak-realm.tar.age|manifest.key.age) cp "$input" "$output" ;;
  *) printf 'synthetisch\n' > "$output" ;;
esac
MOCK

cat > "$bin/curl" <<'MOCK'
#!/bin/bash
if [[ "$*" == *'.well-known/openid-configuration'* ]]; then
  printf '{"issuer":"http://kc_restore:8080/realms/urinet-archiv"}\n'
fi
exit 0
MOCK

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
assert grep -q '^OK      Shelltests des täglichen Exports' "$temp/erfolg.stdout"
assert grep -q '^OK      Echter Sicherungs-Roundtrip: Phase quelle' "$temp/erfolg.stdout"
assert grep -q '^OK      Echter täglicher Export' "$temp/erfolg.stdout"
assert grep -q '^OK      Archiv-Datenbankdump in neue Datenbank einspielen' "$temp/erfolg.stdout"
assert grep -q '^OK      Keycloak-Datenbankdump in neue Datenbank einspielen' "$temp/erfolg.stdout"
assert grep -q '^OK      Echter Sicherungs-Roundtrip: Phase ziel' "$temp/erfolg.stdout"
assert grep -q '^OK      Vollständiger lokaler Sicherungs-Roundtrip abgeschlossen' "$temp/erfolg.stdout"
assert grep -q "shelltest $MOCK_BACKUP_TEST" "$MOCK_LOG"
assert grep -q "taeglich $MOCK_TAEGLICH_SCRIPT" "$MOCK_LOG"
assert grep -q "mvn cwd=$repo_root/backend -ntp -DskipTests package" "$MOCK_LOG"
assert grep -q 'mvn cwd=.*-Darchiv.restore.phase=quelle' "$MOCK_LOG"
assert grep -q 'mvn cwd=.*-Darchiv.restore.phase=ziel' "$MOCK_LOG"
assert grep -q 'docker compose --project-name archiv-restore-' "$MOCK_LOG"
assert grep -q 'down --remove-orphans' "$MOCK_LOG"
if grep -Eq 'down -v|prune' "$script"; then
  printf 'Der Restore-Lauf darf keine Volumes oder Ressourcen global entfernen.\n' >&2
  exit 1
fi

: > "$MOCK_LOG"
if MOCK_FAIL_MVN=1 "$script" >"$temp/fehler.stdout" 2>"$temp/fehler.stderr"; then
  printf 'Der Maven-Fehler hätte den Übungslauf abbrechen müssen.\n' >&2
  exit 1
else
  status=$?
  assert test "$status" -eq 3
fi
assert grep -q '^OK      Echter Sicherungs-Roundtrip: Phase quelle' "$temp/fehler.stdout"
assert grep -q '^FEHLER  Sicherungs-Roundtrip Phase ziel (Exit 1)' "$temp/fehler.stdout"
assert grep -q 'down --remove-orphans' "$MOCK_LOG"
if grep -q '^OK      Vollständiger lokaler Sicherungs-Roundtrip abgeschlossen' "$temp/fehler.stdout"; then
  printf 'Der Übungslauf wurde trotz Testfehler als abgeschlossen gemeldet.\n' >&2
  exit 1
fi
