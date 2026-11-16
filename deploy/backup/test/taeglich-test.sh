#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
script="$repo_root/deploy/backup/taeglich.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT

# Echtes age (falls vorhanden) vor dem Voranstellen der Attrappen auflösen.
echtes_age=$(command -v age 2>/dev/null || true)
echtes_age_keygen=$(command -v age-keygen 2>/dev/null || true)

mock_bin="$temp/bin"
mkdir -p "$mock_bin" "$temp/stdin"
export MOCK_LOG="$temp/commands.log"
export MOCK_STDIN_DIR="$temp/stdin"
export MOCK_FAIL_ZUWACHS=0
export MOCK_FAIL_FESTHALTEN=0
export MOCK_FAIL_REALM=0
export MOCK_EMPTY_BUCKET=0
export MOCK_HANG_ZUWACHS=0
export MOCK_STARTED="$temp/gestartet"
export MOCK_RELEASE="$temp/freigabe"
: > "$MOCK_LOG"

# Die Attrappe von docker bildet die echten Werkzeuge nach: «run» und «exec» lesen stdin, wie «docker compose -T»
# es weiterreicht, und der Realm-Export schreibt nur in ein per --volume eingehängtes Verzeichnis (kein docker cp).
cat > "$mock_bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "$MOCK_LOG"

if [[ $1 == container && $2 == inspect ]]; then
  exit 1
fi
if [[ $1 == rm ]]; then
  exit 0
fi
if [[ $1 != compose ]]; then
  exit 1
fi
shift
while [[ ${1:-} == --file ]]; do shift 2; done
subcommand=$1
shift

# Wie compose -T: stdin wird gelesen und gespeichert, damit Tests den Inhalt prüfen können.
lese_stdin() {
  cat > "$(mktemp "$MOCK_STDIN_DIR/$1.XXXXXX")"
}

api_befehl() {
  [[ ${1:-} == sh && ${2:-} == -c ]] || exit 1
  shift 4
  case ${1:-} in
    sicherung)
      shift
      case ${1:-} in
        inventar)
          lese_stdin inventar
          if [[ $MOCK_EMPTY_BUCKET != 1 ]]; then
            printf '{"format":"urinet-sicherungsinventar-v1","mandant":"%s"}\n' "${3:-}"
          fi
          ;;
        zuwachs)
          if [[ $MOCK_HANG_ZUWACHS == 1 ]]; then
            : > "$MOCK_STARTED"
            while [[ ! -e $MOCK_RELEASE ]]; do sleep 0.1; done
          fi
          if [[ $MOCK_FAIL_ZUWACHS == 1 ]]; then exit 3; fi
          [[ ${2:-} == --mandant ]] || exit 1
          if [[ ${4:-} == --inventar-vorher ]]; then
            cat > "$(mktemp "$MOCK_STDIN_DIR/vorher-${3:-}.XXXXXX")"
          else
            lese_stdin zuwachs
          fi
          if [[ $MOCK_EMPTY_BUCKET != 1 ]]; then
            printf 'URINET-BUCKET-EXPORT-V1\nsynthetic-bucket-stream\n'
          fi
          ;;
        festhalten)
          lese_stdin festhalten
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
}

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
    else
      exit 1
    fi
    ;;
  cp)
    printf 'synthetic-private-sentinel\n' > "$2"
    ;;
  run)
    volume=
    while [[ ${1:-} == -* ]]; do
      case "$1" in
        --name|--entrypoint|-l|--label)
          shift 2
          ;;
        --volume)
          volume=$2
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
      [[ $volume == *:/archiv-export ]] || exit 1
      quelle=${volume%%:*}
      [[ -d $quelle ]] || exit 1
      printf '{"realm":"synthetic"}\n' > "$quelle/urinet-archiv-realm.json"
    elif [[ $service == api ]]; then
      api_befehl "$@"
    else
      exit 1
    fi
    ;;
  *)
    exit 1
    ;;
esac
MOCK

# Die Attrappe von age verschlüsselt nicht, prüft aber die Argumente und liest die Eingabedatei.
cat > "$mock_bin/age-mock" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf 'age %s\n' "$*" >> "$MOCK_LOG"
[[ $1 == --encrypt ]] || exit 1
shift
[[ $1 == --recipient && $2 == "$MOCK_EMPFAENGER_SICHERUNG" ]] || exit 1
shift 2
[[ $1 == --recipient && $2 == "$MOCK_EMPFAENGER_PROBE" ]] || exit 1
shift 2
[[ $1 == --output ]] || exit 1
output=$2
input=$3
[[ -f $input ]] || exit 1
printf 'synthetic ciphertext\n' > "$output"
MOCK

chmod +x "$mock_bin/docker" "$mock_bin/age-mock"

# Gültige age-Schlüsselformate (Bech32-Alphabet, 58 Zeichen).
schluessel_a="age1$(printf 'q%.0s' {1..58})"
schluessel_b="age1$(printf 'z%.0s' {1..58})"
schluessel_c="age1$(printf 'p%.0s' {1..58})"
export MOCK_EMPFAENGER_SICHERUNG=$schluessel_a
export MOCK_EMPFAENGER_PROBE=$schluessel_b

export PATH="$mock_bin:$PATH"
export ARCHIV_COMPOSE_FILE="$temp/compose.yml"
export ARCHIV_SICHERUNGSVERZEICHNIS="$temp/backup"
export ARCHIV_MANDANTEN='urinet vhf-gsk'
export ARCHIV_AGE_EMPFAENGER_SICHERUNG=$schluessel_a
export ARCHIV_AGE_EMPFAENGER_PROBE=$schluessel_b
export ARCHIV_DOCKER_BIN=docker
export ARCHIV_AGE_BIN=age-mock
printf 'synthetic compose\n' > "$ARCHIV_COMPOSE_FILE"

assert() {
  if ! "$@"; then
    printf 'Test fehlgeschlagen: %s\n' "$*" >&2
    exit 1
  fi
}

dateirechte() {
  stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"
}

# Erwartet Exit-Code 2 und eine Meldung auf stderr.
erwarte_konfigurationsfehler() {
  local meldung=$1
  shift
  if env "$@" "$script" > /dev/null 2> "$temp/konfiguration.stderr"; then
    printf 'Konfigurationsfehler hätte abgelehnt werden müssen: %s\n' "$meldung" >&2
    exit 1
  else
    local status=$?
    assert test "$status" -eq 2
  fi
  assert grep -q -- "$meldung" "$temp/konfiguration.stderr"
}

# ---- Erfolgsfall -------------------------------------------------------------------------------------------------
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
erster_lauf=$(< "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/aktuell")
assert test -s "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/laeufe/$erster_lauf/inventar-urinet.jsonl"
assert test -s "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/laeufe/$erster_lauf/probe-empfaenger.sha256"
assert grep -q "age --encrypt --recipient $schluessel_a --recipient $schluessel_b" "$MOCK_LOG"

# Kein Klartext ausserhalb der bekannten Inventare: weder in Argumenten noch in Dateien unter der Sicherungswurzel.
if grep -q 'synthetic-private-sentinel' "$MOCK_LOG"; then
  printf 'Geheimer Inhalt ist in den aufgerufenen Argumenten sichtbar.\n' >&2
  exit 1
fi
if grep -rlE 'synthetic-private-sentinel|synthetic-database-dump|synthetic-bucket-stream|"realm":"synthetic"' \
  "$ARCHIV_SICHERUNGSVERZEICHNIS" > "$temp/klartext.treffer"; then
  cat "$temp/klartext.treffer" >&2
  printf 'Klartext liegt unter dem Sicherungsverzeichnis.\n' >&2
  exit 1
fi
assert test -z "$(find "$ARCHIV_SICHERUNGSVERZEICHNIS" -mindepth 1 -maxdepth 1 -name '.arbeit.*' -print -quit)"

# Rechte
assert test "$(dateirechte "$run_dir")" = 700
assert test "$(dateirechte "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand")" = 700
assert test "$(dateirechte "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/laeufe/$erster_lauf/inventar-urinet.jsonl")" = 600
assert test "$(dateirechte "$run_dir/archiv.dump.age")" = 600

# Backend-Kommandos laufen im Wegwerf-Container, nie per exec im laufenden API-Container.
assert grep -q 'docker compose --file .* run --rm --no-deps -T -l traefik.enable=false api sh -c' "$MOCK_LOG"
if grep -q ' exec -T api' "$MOCK_LOG"; then
  printf 'Backend-Kommando lief per exec im laufenden API-Container.\n' >&2
  exit 1
fi
assert grep -q 'sicherung zuwachs --mandant urinet' "$MOCK_LOG"
inventar_zeile=$(grep -n 'sicherung inventar --mandant urinet' "$MOCK_LOG" | head -n 1 | cut -d: -f1)
zuwachs_zeile=$(grep -n 'sicherung zuwachs --mandant urinet' "$MOCK_LOG" | head -n 1 | cut -d: -f1)
assert test "$inventar_zeile" -lt "$zuwachs_zeile"

# Jede .age-Datei wird festgehalten (die Registrierschleife darf nicht nach der ersten Zeile enden).
age_dateien=$(find "$run_dir" -type f -name '*.age' | wc -l | tr -d ' ')
festhalten_aufrufe=$(grep -c 'sicherung festhalten ' "$MOCK_LOG" || true)
assert test "$age_dateien" -eq 8
assert test "$festhalten_aufrufe" -eq "$age_dateien"
assert grep -q 'festhalten --art bucket --mandant urinet' "$MOCK_LOG"
assert grep -q 'festhalten --art db --sha256' "$MOCK_LOG"
while read -r summe _; do
  assert grep -q -- "--sha256 $summe" "$MOCK_LOG"
done < "$run_dir/SHA256SUMS"

# Realm-Export über Bind-Mount, nicht über docker cp.
assert grep -q -- '--volume .*/klartext/realm:/archiv-export' "$MOCK_LOG"
if grep -q '^docker cp ' "$MOCK_LOG"; then
  printf 'Der Realm-Export darf nicht per docker cp aus einem beendeten Container kopiert werden.\n' >&2
  exit 1
fi
assert grep -q 'stop --timeout 60 kc' "$MOCK_LOG"
assert grep -q 'start kc' "$MOCK_LOG"
assert grep -q 'docker rm archiv-realm-export-' "$MOCK_LOG"
assert grep -q 'Hinweis: Erster Export' "$temp/success.stderr"

# ---- Zweiter Lauf: Vorinventar kommt über stdin an, Aufbewahrung -------------------------------------------------
old_run="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/lauf-20000101T000000Z-1"
unrelated="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/not-a-backup"
recent_run="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/lauf-29990101T000000Z-3"
mkdir -p "$old_run" "$unrelated" "$recent_run"
printf 'old\n' > "$old_run/old.age"
printf 'keep\n' > "$unrelated/keep"
printf 'recent\n' > "$recent_run/recent.age"
touch -t 200001010000 "$old_run" "$old_run/old.age" "$unrelated" "$unrelated/keep"
rm -f -- "$MOCK_STDIN_DIR"/*
if ! "$script" 2> "$temp/second.stderr"; then
  cat "$temp/second.stderr" >&2
  exit 1
fi
assert test ! -e "$old_run"
assert test -s "$unrelated/keep"
assert test -s "$recent_run/recent.age"
assert grep -q -- '--inventar-vorher -' "$MOCK_LOG"
if grep -q 'Erster Export' "$temp/second.stderr"; then
  printf 'Der zweite Lauf hätte inkrementell sein müssen.\n' >&2
  exit 1
fi
vorher_datei=$(find "$MOCK_STDIN_DIR" -type f -name 'vorher-urinet.*' -print -quit)
assert test -n "$vorher_datei"
assert cmp -s "$vorher_datei" "$ARCHIV_SICHERUNGSVERZEICHNIS/.zustand/laeufe/$erster_lauf/inventar-urinet.jsonl"
assert grep -q '"mandant":"urinet"' "$vorher_datei"

# ---- Wechsel des Probe-Schlüssels erzwingt einen Vollexport (Req 16.23) ------------------------------------------
export MOCK_EMPFAENGER_PROBE=$schluessel_c
export ARCHIV_AGE_EMPFAENGER_PROBE=$schluessel_c
: > "$MOCK_LOG"
if ! "$script" 2> "$temp/probe-wechsel.stderr"; then
  cat "$temp/probe-wechsel.stderr" >&2
  exit 1
fi
assert grep -q 'Probe-Schlüssel gewechselt' "$temp/probe-wechsel.stderr"
if grep -q -- '--inventar-vorher' "$MOCK_LOG"; then
  printf 'Nach dem Wechsel des Probe-Schlüssels wurde ein Zuwachs statt eines Vollexports erstellt.\n' >&2
  exit 1
fi
# Unveränderter Probe-Schlüssel: wieder inkrementell.
: > "$MOCK_LOG"
assert "$script" 2> "$temp/probe-gleich.stderr"
assert grep -q -- '--inventar-vorher -' "$MOCK_LOG"
# Ausdrücklicher Vollexport.
: > "$MOCK_LOG"
ARCHIV_VOLLEXPORT=1 "$script" 2> "$temp/vollexport.stderr"
assert grep -q 'Vollexport angefordert' "$temp/vollexport.stderr"
if grep -q -- '--inventar-vorher' "$MOCK_LOG"; then
  printf 'ARCHIV_VOLLEXPORT=1 hat den Vollexport nicht erzwungen.\n' >&2
  exit 1
fi
export MOCK_EMPFAENGER_PROBE=$schluessel_b
export ARCHIV_AGE_EMPFAENGER_PROBE=$schluessel_b

# ---- Fehlerfälle -------------------------------------------------------------------------------------------------
failed_root="$temp/failed-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$failed_root
export MOCK_FAIL_ZUWACHS=1
failed_old_run="$failed_root/laeufe/lauf-20000101T000000Z-2"
mkdir -p "$failed_old_run"
printf 'older completed backup\n' > "$failed_old_run/FERTIG"
touch -t 200001010000 "$failed_old_run" "$failed_old_run/FERTIG"
if "$script" > /dev/null 2> "$temp/failure.stderr"; then
  printf 'Fehlerfall hätte fehlschlagen müssen.\n' >&2
  exit 1
fi
assert test -s "$failed_old_run/FERTIG"
assert test -z "$(find "$failed_root/laeufe" -mindepth 1 -maxdepth 1 -type d \
  ! -name 'lauf-20000101T000000Z-2' -print -quit)"
assert test ! -e "$failed_root/.zustand/aktuell"
if grep -q 'Keycloak konnte nach dem Realm-Export nicht gestartet werden' "$temp/failure.stderr"; then
  printf 'Keycloak wurde nach einem Exportfehler nicht korrekt gestartet.\n' >&2
  exit 1
fi
assert grep -q 'Vollständiger Bucket-Export konnte nicht erstellt werden' "$temp/failure.stderr"
assert grep -q 'start kc' "$MOCK_LOG"
assert test -z "$(find "$failed_root" -mindepth 1 -maxdepth 1 -name '.arbeit.*' -print -quit)"
assert test ! -e "$failed_root/.taeglich.lock"

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
export MOCK_FAIL_FESTHALTEN=0

# ---- Konfiguration -----------------------------------------------------------------------------------------------
duplicate_root="$temp/duplicate-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$duplicate_root
export ARCHIV_MANDANTEN='urinet urinet'
if "$script" > /dev/null 2> "$temp/duplicate.stderr"; then
  printf 'Doppelte Mandanten hätten abgelehnt werden müssen.\n' >&2
  exit 1
fi
assert grep -q 'Mandant ist mehrfach angegeben' "$temp/duplicate.stderr"
assert test -z "$(find "$duplicate_root/laeufe" -mindepth 1 -maxdepth 1 -type d -print -quit)"
export ARCHIV_MANDANTEN=urinet

erwarte_konfigurationsfehler 'ARCHIV_MANDANTEN darf keinen Zeilenumbruch' ARCHIV_MANDANTEN=$'urinet\nvhf-gsk'
erwarte_konfigurationsfehler 'ARCHIV_AGE_EMPFAENGER_SICHERUNG fehlt' -u ARCHIV_AGE_EMPFAENGER_SICHERUNG
erwarte_konfigurationsfehler 'ARCHIV_AGE_EMPFAENGER_PROBE fehlt' -u ARCHIV_AGE_EMPFAENGER_PROBE
erwarte_konfigurationsfehler 'müssen verschiedene Schlüssel sein' "ARCHIV_AGE_EMPFAENGER_PROBE=$schluessel_a"
erwarte_konfigurationsfehler 'Sicherungs-Empfänger muss ein öffentlicher age-Schlüssel sein' 'ARCHIV_AGE_EMPFAENGER_SICHERUNG=age1abc'
erwarte_konfigurationsfehler 'Probe-Empfänger muss ein öffentlicher age-Schlüssel sein' "ARCHIV_AGE_EMPFAENGER_PROBE=$schluessel_c --foo"
erwarte_konfigurationsfehler 'Probe-Empfänger muss ein öffentlicher age-Schlüssel sein' "ARCHIV_AGE_EMPFAENGER_PROBE=$schluessel_c"$'\n'
erwarte_konfigurationsfehler 'ARCHIV_VOLLEXPORT muss 0 oder 1 sein' ARCHIV_VOLLEXPORT=2

empty_root="$temp/empty-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$empty_root
export MOCK_EMPTY_BUCKET=1
if ! "$script" 2> "$temp/empty.stderr"; then
  cat "$temp/empty.stderr" >&2
  exit 1
fi
empty_run=$(find "$empty_root/laeufe" -mindepth 1 -maxdepth 1 -type d -name 'lauf-*' -print -quit)
assert test -n "$empty_run"
assert test -s "$empty_run/bucket-urinet.bin.age"
assert test -s "$empty_run/inventar-urinet.jsonl.age"
assert test -s "$empty_run/FERTIG"
export MOCK_EMPTY_BUCKET=0

export ARCHIV_SICHERUNGSVERZEICHNIS=/
if "$script" > /dev/null 2> "$temp/root-path.stderr"; then
  printf 'Das Stammverzeichnis hätte als Sicherungsverzeichnis abgelehnt werden müssen.\n' >&2
  exit 1
fi
assert grep -q 'Sicherungsverzeichnis darf nicht das Stammverzeichnis sein' "$temp/root-path.stderr"

# ---- Sperre ------------------------------------------------------------------------------------------------------
# Belegte Sperre eines lebenden Prozesses: Abbruch mit Exit 3, Sperre bleibt.
lock_root="$temp/lock-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$lock_root
mkdir -p "$lock_root/.taeglich.lock"
printf '%s\n' "$$" > "$lock_root/.taeglich.lock/pid"
if "$script" > /dev/null 2> "$temp/lock-belegt.stderr"; then
  printf 'Belegte Sperre hätte den Lauf verhindern müssen.\n' >&2
  exit 1
fi
assert grep -q 'Sperre ist belegt' "$temp/lock-belegt.stderr"
assert test -d "$lock_root/.taeglich.lock"

# Veraltete Sperre (Prozess existiert nicht mehr): wird übernommen, Klartext-Rest wird entfernt, Keycloak gestartet.
sh -c 'exit 0' &
toter_prozess=$!
wait "$toter_prozess" || true
printf '%s\n' "$toter_prozess" > "$lock_root/.taeglich.lock/pid"
mkdir -p "$lock_root/.arbeit.ABCD1234/klartext"
printf 'synthetic-private-sentinel\n' > "$lock_root/.arbeit.ABCD1234/klartext/manifest.key"
: > "$MOCK_LOG"
if ! "$script" 2> "$temp/lock-veraltet.stderr"; then
  cat "$temp/lock-veraltet.stderr" >&2
  exit 1
fi
assert grep -q 'Veraltete Sperre' "$temp/lock-veraltet.stderr"
assert test ! -e "$lock_root/.arbeit.ABCD1234"
assert test ! -e "$lock_root/.taeglich.lock"
assert test -s "$(find "$lock_root/laeufe" -mindepth 2 -maxdepth 2 -name FERTIG -print -quit)"
start_zeile=$(grep -n 'start kc' "$MOCK_LOG" | head -n 1 | cut -d: -f1)
dump_zeile=$(grep -n 'pg_dump' "$MOCK_LOG" | head -n 1 | cut -d: -f1)
assert test "$start_zeile" -lt "$dump_zeile"

# ---- Abbruch durch SIGTERM ---------------------------------------------------------------------------------------
term_root="$temp/term-backup"
export ARCHIV_SICHERUNGSVERZEICHNIS=$term_root
export MOCK_HANG_ZUWACHS=1
rm -f -- "$MOCK_STARTED" "$MOCK_RELEASE"
: > "$MOCK_LOG"
"$script" > /dev/null 2> "$temp/term.stderr" &
lauf_pid=$!
for _ in $(seq 1 100); do
  [[ -e $MOCK_STARTED ]] && break
  sleep 0.1
done
assert test -e "$MOCK_STARTED"
kill -TERM "$lauf_pid"
: > "$MOCK_RELEASE"
term_status=0
wait "$lauf_pid" || term_status=$?
assert test "$term_status" -eq 143
assert test ! -e "$term_root/.taeglich.lock"
assert test -z "$(find "$term_root" -mindepth 1 -maxdepth 1 -name '.arbeit.*' -print -quit)"
assert test -z "$(find "$term_root/laeufe" -mindepth 1 -maxdepth 1 -type d -print -quit)"
assert grep -q 'start kc' "$MOCK_LOG"
export MOCK_HANG_ZUWACHS=0
rm -f -- "$MOCK_STARTED" "$MOCK_RELEASE"

# ---- Echtes age: beide Identitäten entschlüsseln ------------------------------------------------------------------
if [[ -n $echtes_age && -n $echtes_age_keygen ]]; then
  age_root="$temp/age-backup"
  "$echtes_age_keygen" -o "$temp/sicherung.key" 2> /dev/null
  "$echtes_age_keygen" -o "$temp/probe.key" 2> /dev/null
  echter_a=$("$echtes_age_keygen" -y "$temp/sicherung.key")
  echter_b=$("$echtes_age_keygen" -y "$temp/probe.key")
  export ARCHIV_SICHERUNGSVERZEICHNIS=$age_root
  export ARCHIV_AGE_BIN=$echtes_age
  export ARCHIV_AGE_EMPFAENGER_SICHERUNG=$echter_a
  export ARCHIV_AGE_EMPFAENGER_PROBE=$echter_b
  assert "$script" 2> "$temp/age.stderr"
  age_lauf=$(find "$age_root/laeufe" -mindepth 1 -maxdepth 1 -type d -name 'lauf-*' -print -quit)
  for identitaet in sicherung probe; do
    "$echtes_age" --decrypt --identity "$temp/$identitaet.key" "$age_lauf/archiv.dump.age" > "$temp/entschluesselt"
    assert test "$(< "$temp/entschluesselt")" = synthetic-database-dump
    "$echtes_age" --decrypt --identity "$temp/$identitaet.key" "$age_lauf/manifest.key.age" > "$temp/entschluesselt"
    assert test "$(< "$temp/entschluesselt")" = synthetic-private-sentinel
  done
  # Mit einer fremden Identität lässt sich nichts entschlüsseln.
  "$echtes_age_keygen" -o "$temp/fremd.key" 2> /dev/null
  if "$echtes_age" --decrypt --identity "$temp/fremd.key" "$age_lauf/archiv.dump.age" > /dev/null 2>&1; then
    printf 'Eine fremde Identität darf das Chiffrat nicht entschlüsseln.\n' >&2
    exit 1
  fi
else
  printf 'Hinweis: age oder age-keygen fehlt, der Test mit echtem age wurde übersprungen.\n' >&2
fi

printf 'Shell-Tests erfolgreich.\n'
