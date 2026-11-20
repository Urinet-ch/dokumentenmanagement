#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
script="$repo_root/deploy/backup/restore-uebung-lokal.sh"
compose_file="$repo_root/deploy/backup/test/restore-compose.yml"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT

bin="$temp/bin"
mkdir -p "$bin"
export MOCK_LOG="$temp/commands.log"
export MOCK_BACKUP_TEST="$repo_root/deploy/backup/test/taeglich-test.sh"
export MOCK_TAEGLICH_SCRIPT="$repo_root/deploy/backup/taeglich.sh"
export MOCK_IMAGES="$temp/images"
: > "$MOCK_LOG"

assert() {
  if ! "$@"; then
    printf 'Test fehlgeschlagen: %s\n' "$*" >&2
    exit 1
  fi
}

compose_images() {
  awk '$1 == "image:" { print $2 }' "$compose_file"
}

from_images() {
  awk 'toupper($1) == "FROM" && $2 ~ /[:@]/ { print $2 }' "$@"
}

# Statische Prüfungen der Quellen: keine eigene Digest-Liste im Skript, gleiche Images wie Dev Services und Deploy.
if grep -Eq 'sha256:[0-9a-f]{64}' "$script"; then
  printf 'Das Übungsskript darf keine eigenen Image-Digests führen.\n' >&2
  exit 1
fi
minio_restore=$(compose_images | grep '/minio:')
minio_dev=$(awk '$1 == "image:" && $2 ~ /\/minio:/ { print $2 }' "$repo_root/backend/compose-devservices.yml")
assert test -n "$minio_restore"
assert test "$minio_restore" = "$minio_dev"
db_restore=$(compose_images | grep '^pgvector/')
db_deploy=$(from_images "$repo_root/deploy/infomaniak/db/Dockerfile")
assert test -n "$db_restore"
assert test "$db_restore" = "$db_deploy"
for image in $(compose_images | grep -v '^archiv:'); do
  [[ $image =~ @sha256:[0-9a-f]{64}$ ]] || { printf 'Image ohne Digest: %s\n' "$image" >&2; exit 1; }
done

# Jeder Dienst mit veröffentlichtem Port hängt an einem Netz ohne «internal: true»; alle anderen nur an internen.
netzpruefung=$(awk '
  /^[^ #]/ { bereich = $1; dienst = ""; netz = "" }
  bereich == "services:" && /^  [a-z_]+:$/ { dienst = $1; sub(/:$/, "", dienst); dienste[dienst] = 1 }
  bereich == "services:" && dienst != "" && /^    ports:/ { ports[dienst] = 1 }
  bereich == "services:" && dienst != "" && /^    networks: \[/ {
    liste = $0; sub(/^.*\[/, "", liste); sub(/\].*$/, "", liste); gsub(/ /, "", liste); netze[dienst] = liste
  }
  bereich == "networks:" && /^  [a-z_]+:/ { netz = $1; sub(/:.*$/, "", netz); bekannt[netz] = 1 }
  bereich == "networks:" && netz != "" && /^    internal: true$/ { intern[netz] = 1 }
  END {
    for (d in dienste) {
      if (netze[d] == "") { print "ohne Netz: " d; continue }
      n = split(netze[d], teile, ",")
      offen = 0
      for (i = 1; i <= n; i++) {
        if (!(teile[i] in bekannt)) print "unbekanntes Netz: " d
        if (!(teile[i] in intern)) offen = 1
      }
      if (ports[d] && !offen) print "Port ohne Hostnetz: " d
      if (!ports[d] && offen) print "Hostnetz ohne Port: " d
    }
  }' "$compose_file")
if [[ -n $netzpruefung ]]; then
  printf 'Netze der Restore-Compose-Datei falsch:\n%s\n' "$netzpruefung" >&2
  exit 1
fi
assert grep -Eq '^  intern:$' "$compose_file"
{ compose_images | grep -v '^archiv:'; from_images "$repo_root/deploy/infomaniak/keycloak/Dockerfile" \
  "$repo_root/backend/src/main/docker/Dockerfile.jvm"; } | LC_ALL=C sort -u > "$MOCK_IMAGES"
assert test "$(wc -l < "$MOCK_IMAGES")" -ge 4

cat > "$bin/bash" <<'MOCK'
#!/bin/bash
if [[ ${1:-} == "$MOCK_BACKUP_TEST" ]]; then
  printf 'shelltest %s\n' "$*" >> "$MOCK_LOG"
  exit 0
fi
if [[ ${1:-} == "$MOCK_TAEGLICH_SCRIPT" ]]; then
  printf 'taeglich %s\n' "$*" >> "$MOCK_LOG"
  printf 'ARCHIV_MANDANTEN=%s\n' "$ARCHIV_MANDANTEN" >> "$MOCK_LOG"
  lauf="$ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/lauf-20260101T000000Z-1"
  realm="$ARCHIV_RESTORE_TMP/fake-realm/realm"
  mkdir -p "$lauf"
  for datei in archiv.dump keycloak.dump; do
    printf 'synthetisch:%s\n' "$datei" > "$lauf/$datei.age"
  done
  for mandant in urinet vhf-gsk; do
    for art in inventar bucket; do
      if [[ ${MOCK_FEHLENDER_BUCKET:-} == "$mandant" && $art == bucket ]]; then
        continue
      fi
      endung=jsonl
      [[ $art != bucket ]] || endung=bin
      datei="$art-$mandant.$endung"
      printf 'synthetisch:%s\n' "$datei" > "$lauf/$datei.age"
    done
  done
  mkdir -p "$realm"
  printf 'synthetischer Realm\n' > "$realm/urinet-archiv-realm.json"
  tar -cf "$lauf/keycloak-realm.tar.age" -C "$ARCHIV_RESTORE_TMP/fake-realm" realm
  openssl genpkey -algorithm Ed25519 -out "$ARCHIV_RESTORE_TMP/fake-manifest.key" >/dev/null 2>&1
  cp "$ARCHIV_RESTORE_TMP/fake-manifest.key" "$lauf/manifest.key.age"
  # Format wie taeglich.sh: «<hash>  <datei>.age», ohne «./».
  for artefakt in "$lauf"/*.age; do
    summe=$(openssl dgst -sha256 -r "$artefakt" | awk '{ print $1 }')
    printf '%s  %s\n' "$summe" "${artefakt##*/}" >> "$lauf/SHA256SUMS"
  done
  if [[ ${MOCK_SUMS_LUECKE:-0} == 1 ]]; then
    sed -i.alt '$d' "$lauf/SHA256SUMS" && rm -f "$lauf/SHA256SUMS.alt"
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
  [[ ${3:-} != "${MOCK_FEHLENDES_IMAGE:-}" ]] || exit 1
  grep -Fxq -- "${3:-}" "$MOCK_IMAGES"
  exit
fi
if [[ ${1:-} == image && ${2:-} == inspect ]]; then
  image=${3:-}
  case "$image" in
    archiv:restore-roundtrip|archiv:restore-roundtrip-kc) exit 1 ;;
  esac
  if awk '$1 == "image:" { print $2 }' "$MOCK_COMPOSE_FILE" | grep -Fxq "$image"; then
    exit 0
  fi
  if awk '
    $1 == "FROM" {
      pos = 2
      if ($pos ~ /^--/) pos++
      image = $pos
      if (image != "scratch" && !(image in stages)) print image
      if ($(pos + 1) == "AS") stages[$(pos + 2)] = 1
    }
  ' "$MOCK_API_DOCKERFILE" "$MOCK_KEYCLOAK_DOCKERFILE" | grep -Fxq "$image"; then
    exit 0
  fi
  exit 1
fi
if [[ ${1:-} == inspect ]]; then
  printf 'healthy\n'
  exit 0
fi
if [[ ${1:-} == compose ]]; then
  datei=
  argumente=("$@")
  for ((i = 0; i < ${#argumente[@]}; i++)); do
    [[ ${argumente[i]} != --file ]] || datei=${argumente[i + 1]}
  done
  case " $* " in
    *" config --images "*) awk '$1 == "image:" { print $2 }' "$datei" ;;
    *" ps --quiet db "*) printf 'mock-container\n' ;;
    *" exec -T db psql "*)
      if [[ $* == *"to_regclass('m_vhf_gsk.dokument')"* && ${MOCK_FEHLENDES_SCHEMA:-} == vhf-gsk ]]; then
        exit 0
      fi
      if [[ $* == *"to_regclass('m_urinet.dokument')"* ]]; then
        printf 'm_urinet.dokument\n'
      elif [[ $* == *"to_regclass('m_vhf_gsk.dokument')"* ]]; then
        printf 'm_vhf_gsk.dokument\n'
      else
        exit 1
      fi
      ;;
    # Wie Docker bei Diensten ohne veröffentlichten Port: Exit 0 mit Port 0.
    *" port db 5432 "*) printf '127.0.0.1:25432\n' ;;
    *" port minio 9000 "*) [[ ${MOCK_PORT_NULL:-0} == 1 ]] && printf ':0\n' || printf '127.0.0.1:29000\n' ;;
    *" port kc_restore 8080 "*) printf '127.0.0.1:%s\n' "$ARCHIV_RESTORE_KC_PORT" ;;
    *" port "*) printf ':0\n' ;;
    *" exec -T db timeout "*" bash -c "*)
      dienst=${*: -3:1}
      pfad=${*: -1}
      case $dienst in
        kc|api|kc_restore) ;;
        *) exit 1 ;;
      esac
      printf 'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n'
      if [[ $pfad == */.well-known/openid-configuration ]]; then
        printf '{"issuer":"%s/realms/urinet-archiv"}\n' "$ARCHIV_RESTORE_KC_BASE_URL"
      fi
      ;;
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
if [[ ${1:-} == -o && $# -eq 2 ]]; then
  printf 'synthetic identity\n' > "$2"
elif [[ ${1:-} == -y && $# -eq 2 && -f ${2:-} ]]; then
  printf 'age1synthetic\n'
else
  printf 'flag provided but not defined\n' >&2
  exit 2
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
  printf '{"issuer":"%s/realms/urinet-archiv"}\n' "$ARCHIV_RESTORE_KC_BASE_URL"
fi
exit 0
MOCK

chmod +x "$bin"/*
export PATH="$bin:$PATH"

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
assert grep -q -- '-Darchiv.restore.verzeichnis=' "$MOCK_LOG"
assert grep -q -- '-Darchiv.restore.oidc-client=archiv-restore-' "$MOCK_LOG"
assert grep -q -- '-Darchiv.restore.oidc-benutzer=restore-oidc-' "$MOCK_LOG"
assert grep -q 'create clients ' "$MOCK_LOG"
assert grep -q 'create users ' "$MOCK_LOG"
assert grep -q 'add-roles ' "$MOCK_LOG"
assert grep -q '^ARCHIV_MANDANTEN=urinet vhf-gsk$' "$MOCK_LOG"
assert grep -q 'docker compose --project-name archiv-restore-' "$MOCK_LOG"
assert grep -q 'down --remove-orphans' "$MOCK_LOG"
assert grep -q '^OK      SHA256SUMS des Sicherungspakets prüfen' "$temp/erfolg.stdout"
assert grep -q '^OK      Manipulierte SHA256SUMS zurückweisen' "$temp/erfolg.stdout"
assert grep -q '^OK      Wiederhergestellter Keycloak-Realm erreichbar' "$temp/erfolg.stdout"
assert grep -q 'port kc_restore 8080$' "$MOCK_LOG"
for ziel in 'kc 9000 /health/ready' 'api 8080 /q/health/ready' 'kc_restore 9000 /health/ready' \
  'kc_restore 8080 /realms/urinet-archiv/.well-known/openid-configuration'; do
  assert grep -q "exec -T db timeout 3 bash -c .* _ $ziel\$" "$MOCK_LOG"
done
if grep -Eq ' port (kc|api) ' "$MOCK_LOG"; then
  printf 'Quell-Keycloak und API dürfen keinen Host-Port brauchen.\n' >&2
  exit 1
fi
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

erwarte_abbruch() {
  local erwarteter_status=$1 meldung=$2
  shift 2
  : > "$MOCK_LOG"
  if env "$@" "$script" >"$temp/abbruch.stdout" 2>"$temp/abbruch.stderr"; then
    printf 'Der Übungslauf hätte abbrechen müssen: %s\n' "$*" >&2
    exit 1
  else
    local status=$?
    assert test "$status" -eq "$erwarteter_status"
  fi
  assert grep -Fq -- "$meldung" "$temp/abbruch.stderr"
  if grep -q 'abgeschlossen' "$temp/abbruch.stdout"; then
    printf 'Abgebrochener Übungslauf wurde als abgeschlossen gemeldet.\n' >&2
    exit 1
  fi
}

fehlendes=$(grep '/minio:' "$MOCK_IMAGES")
erwarte_abbruch 2 "docker pull $fehlendes" "MOCK_FEHLENDES_IMAGE=$fehlendes"
erwarte_abbruch 3 'SHA256SUMS nennt nicht genau die verschlüsselten Dateien' MOCK_SUMS_LUECKE=1
erwarte_abbruch 3 'Compose-Port von minio fehlt' MOCK_PORT_NULL=1
erwarte_abbruch 3 'Sicherungspaket für Mandant vhf-gsk ist unvollständig (bucket-vhf-gsk.bin fehlt)' \
  MOCK_FEHLENDER_BUCKET=vhf-gsk
erwarte_abbruch 3 'Wiederhergestelltes Schema für Mandant vhf-gsk fehlt' MOCK_FEHLENDES_SCHEMA=vhf-gsk

# Dieselbe Übung mit «shasum» statt «sha256sum» (macOS ohne coreutils): PATH ohne sha256sum nachbauen.
if command -v shasum >/dev/null 2>&1; then
  ohne_sha256sum="$temp/ohne-sha256sum"
  mkdir -p "$ohne_sha256sum"
  IFS=: read -r -a pfade <<< "${PATH#"$bin:"}"
  for pfad in "${pfade[@]}"; do
    [[ -d $pfad ]] || continue
    for programm in "$pfad"/*; do
      name=${programm##*/}
      [[ $name != sha256sum && -x $programm && ! -e $ohne_sha256sum/$name ]] || continue
      ln -s "$programm" "$ohne_sha256sum/$name"
    done
  done
  : > "$MOCK_LOG"
  if ! PATH="$bin:$ohne_sha256sum" "$script" >"$temp/shasum.stdout" 2>"$temp/shasum.stderr"; then
    cat "$temp/shasum.stderr" >&2
    exit 1
  fi
  assert grep -q '^OK      SHA256SUMS des Sicherungspakets prüfen' "$temp/shasum.stdout"
  assert grep -q '^OK      Manipulierte SHA256SUMS zurückweisen' "$temp/shasum.stdout"
fi
