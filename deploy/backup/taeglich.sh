#!/usr/bin/env bash
set -euo pipefail
umask 077

fehler() {
  local code=$1
  shift
  printf 'Fehler: %s\n' "$*" >&2
  exit "$code"
}

[[ $# -eq 0 ]] || fehler 2 "taeglich.sh erwartet keine Argumente."
[[ -n ${ARCHIV_COMPOSE_FILE:-} ]] || fehler 2 "ARCHIV_COMPOSE_FILE fehlt."
[[ -n ${ARCHIV_SICHERUNGSVERZEICHNIS:-} ]] || fehler 2 "ARCHIV_SICHERUNGSVERZEICHNIS fehlt."
[[ -n ${ARCHIV_MANDANTEN:-} ]] || fehler 2 "ARCHIV_MANDANTEN fehlt."
[[ -n ${ARCHIV_AGE_EMPFAENGER_SICHERUNG:-} ]] || fehler 2 "ARCHIV_AGE_EMPFAENGER_SICHERUNG fehlt."
[[ -n ${ARCHIV_AGE_EMPFAENGER_PROBE:-} ]] || fehler 2 "ARCHIV_AGE_EMPFAENGER_PROBE fehlt."

ARCHIV_DOCKER_BIN=${ARCHIV_DOCKER_BIN:-docker}
ARCHIV_AGE_BIN=${ARCHIV_AGE_BIN:-age}
ARCHIV_API_SERVICE=${ARCHIV_API_SERVICE:-api}
ARCHIV_DB_SERVICE=${ARCHIV_DB_SERVICE:-db}
ARCHIV_KEYCLOAK_SERVICE=${ARCHIV_KEYCLOAK_SERVICE:-kc}
ARCHIV_DB_NAME=${ARCHIV_DB_NAME:-archiv}
ARCHIV_KEYCLOAK_DB_NAME=${ARCHIV_KEYCLOAK_DB_NAME:-keycloak}
ARCHIV_AUFBEWAHRUNG_TAGE=${ARCHIV_AUFBEWAHRUNG_TAGE:-3}

[[ -f $ARCHIV_COMPOSE_FILE ]] || fehler 2 "Compose-Datei fehlt."
# age-Schlüssel sind «age1» plus 58 Zeichen aus dem Bech32-Alphabet (ohne 1, b, i, o). Die strenge Prüfung verhindert,
# dass Leerraum, Zeilenumbrüche oder Optionen als Empfänger an age gelangen.
age_schluessel='^age1[02-9ac-hj-np-z]{58}$'
[[ $ARCHIV_AGE_EMPFAENGER_SICHERUNG =~ $age_schluessel ]] \
  || fehler 2 "Sicherungs-Empfänger muss ein öffentlicher age-Schlüssel sein."
[[ $ARCHIV_AGE_EMPFAENGER_PROBE =~ $age_schluessel ]] \
  || fehler 2 "Probe-Empfänger muss ein öffentlicher age-Schlüssel sein."
# Zwei gleiche Empfänger ergäben nur einen Schlüssel: Die Restore-Probe wäre nicht unabhängig von der Sicherung (Req 16.23).
[[ $ARCHIV_AGE_EMPFAENGER_SICHERUNG != "$ARCHIV_AGE_EMPFAENGER_PROBE" ]] \
  || fehler 2 "Sicherungs- und Probe-Empfänger müssen verschiedene Schlüssel sein."
ARCHIV_VOLLEXPORT=${ARCHIV_VOLLEXPORT:-0}
[[ $ARCHIV_VOLLEXPORT == 0 || $ARCHIV_VOLLEXPORT == 1 ]] || fehler 2 "ARCHIV_VOLLEXPORT muss 0 oder 1 sein."
if [[ ! $ARCHIV_AUFBEWAHRUNG_TAGE =~ ^[1-9][0-9]*$ ]] \
  || [[ ${#ARCHIV_AUFBEWAHRUNG_TAGE} -gt 4 ]] \
  || (( ARCHIV_AUFBEWAHRUNG_TAGE > 3650 )); then
  fehler 2 "ARCHIV_AUFBEWAHRUNG_TAGE muss zwischen 1 und 3650 liegen."
fi

command -v "$ARCHIV_DOCKER_BIN" >/dev/null 2>&1 || fehler 2 "Docker ist nicht verfügbar."
command -v "$ARCHIV_AGE_BIN" >/dev/null 2>&1 || fehler 2 "age ist nicht verfügbar."
command -v tar >/dev/null 2>&1 || fehler 2 "tar ist nicht verfügbar."
command -v date >/dev/null 2>&1 || fehler 2 "date ist nicht verfügbar."
if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
  fehler 2 "Kein SHA-256-Werkzeug gefunden."
fi

if [[ -f $ARCHIV_COMPOSE_FILE ]]; then
  compose_ordner=$(cd -- "$(dirname -- "$ARCHIV_COMPOSE_FILE")" && pwd -P) \
    || fehler 2 "Verzeichnis der Compose-Datei ist nicht lesbar."
  compose_datei="$compose_ordner/$(basename -- "$ARCHIV_COMPOSE_FILE")"
fi

mkdir -p -- "$ARCHIV_SICHERUNGSVERZEICHNIS" || fehler 3 "Sicherungsverzeichnis kann nicht angelegt werden."
sicherungswurzel=$(cd -- "$ARCHIV_SICHERUNGSVERZEICHNIS" && pwd -P) \
  || fehler 3 "Sicherungsverzeichnis ist nicht erreichbar."
[[ $sicherungswurzel != / ]] || fehler 2 "Sicherungsverzeichnis darf nicht das Stammverzeichnis sein."
laeufe="$sicherungswurzel/laeufe"
zustand="$sicherungswurzel/.zustand"
zustandslaeufe="$zustand/laeufe"

for verzeichnis in "$laeufe" "$zustand" "$zustandslaeufe"; do
  [[ ! -L $verzeichnis ]] || fehler 2 "Sicherungsverzeichnis enthält einen symbolischen Link."
  mkdir -p -- "$verzeichnis" || fehler 3 "Sicherungsverzeichnis kann nicht angelegt werden."
done
chmod 700 "$laeufe"
chmod 700 "$zustand" "$zustandslaeufe"

# read liest nur die erste Zeile: Ein Zeilenumbruch würde weitere Mandanten stillschweigend auslassen.
[[ $ARCHIV_MANDANTEN != *$'\n'* ]] || fehler 2 "ARCHIV_MANDANTEN darf keinen Zeilenumbruch enthalten."
read -r -a mandanten <<< "$ARCHIV_MANDANTEN"
(( ${#mandanten[@]} > 0 )) || fehler 2 "ARCHIV_MANDANTEN enthält keinen Mandanten."
bereits_gesehen=" "
for mandant in "${mandanten[@]}"; do
  [[ $mandant =~ ^[a-z][a-z0-9-]*$ ]] || fehler 2 "Ungültiger Mandantencode."
  [[ $bereits_gesehen != *" $mandant "* ]] || fehler 2 "Mandant ist mehrfach angegeben."
  bereits_gesehen+="$mandant "
done

zeitstempel=$(date -u +%Y%m%dT%H%M%SZ) || fehler 3 "UTC-Zeit konnte nicht gelesen werden."
laufname="lauf-$zeitstempel-$$"
ziel="$laeufe/$laufname"
zustandsziel="$zustandslaeufe/$laufname"
[[ ! -e $ziel && ! -L $ziel && ! -e $zustandsziel && ! -L $zustandsziel ]] \
  || fehler 3 "Für diesen Zeitpunkt gibt es bereits einen Sicherungslauf."

sperre="$sicherungswurzel/.taeglich.lock"
veraltete_sperre=0
if ! mkdir -- "$sperre" 2>/dev/null; then
  # Nach SIGKILL, OOM-Kill oder Neustart läuft der EXIT-Trap nicht und die Sperre bleibt liegen. Gehört die gespeicherte
  # Prozess-ID keinem lebenden Prozess mehr, wird die Sperre übernommen; sonst bricht der Lauf ab (siehe README).
  sperre_pid=
  if [[ -f $sperre/pid && ! -L $sperre/pid ]]; then
    IFS= read -r sperre_pid < "$sperre/pid" || true
  fi
  if [[ $sperre_pid =~ ^[0-9]+$ ]] && ! kill -0 "$sperre_pid" 2>/dev/null; then
    # Die Umbenennung gelingt nur einem von zwei gleichzeitig startenden Läufen.
    mv -- "$sperre" "$sperre.veraltet.$$" 2>/dev/null \
      || fehler 3 "Ein Sicherungslauf läuft bereits oder die Sperre ist belegt."
    rm -rf -- "$sperre.veraltet.$$"
    mkdir -- "$sperre" 2>/dev/null || fehler 3 "Ein Sicherungslauf läuft bereits oder die Sperre ist belegt."
    printf 'Hinweis: Veraltete Sperre eines abgebrochenen Laufs übernommen.\n' >&2
    veraltete_sperre=1
  else
    fehler 3 "Ein Sicherungslauf läuft bereits oder die Sperre ist belegt."
  fi
fi
sperre_aktiv=1

arbeitsverzeichnis=
keycloak_angehalten=0
realm_container=
realm_container_angelegt=0
realm_export_volume=
realm_export_volume_angelegt=0
lauf_veroeffentlicht=0
registrierung_begonnen=0
zustand_angelegt=0
zustand_aktiv=0

compose() {
  "$ARCHIV_DOCKER_BIN" compose --file "$compose_datei" "$@"
}

api_kommando() {
  # Wegwerf-Container des API-Dienstes (gleiche Secrets und JAVA_OPTS_APPEND), nie «exec» im laufenden Container: Eine
  # zweite JVM sprengt dessen mem_limit (design.md, Aufgabe 1.3). Ohne Traefik-Anmeldung. -T reicht stdin weiter:
  # Aufrufer ohne gewollte Eingabe leiten stdin von /dev/null um, sonst verbraucht der Container ihren stdin.
  # shellcheck disable=SC2016 # JAVA_OPTS_APPEND und "$@" werden im Container-Shellprozess ausgewertet.
  compose run --rm --no-deps -T -l traefik.enable=false "$ARCHIV_API_SERVICE" sh -c \
    'exec java ${JAVA_OPTS_APPEND:-} -jar /deployments/quarkus-run.jar "$@"' archiv "$@"
}

sha256_datei() {
  local ausgabe
  if command -v sha256sum >/dev/null 2>&1; then
    ausgabe=$(sha256sum -- "$1") || return 1
  else
    ausgabe=$(shasum -a 256 -- "$1") || return 1
  fi
  printf '%s\n' "${ausgabe%% *}"
}

verschluesseln() {
  local art=$1 mandant=$2 quelle=$3 dateiname summe
  dateiname=${quelle##*/}
  [[ -f $quelle ]] || fehler 3 "Exportdatei fehlt."
  if ! "$ARCHIV_AGE_BIN" --encrypt \
    --recipient "$ARCHIV_AGE_EMPFAENGER_SICHERUNG" \
    --recipient "$ARCHIV_AGE_EMPFAENGER_PROBE" \
    --output "$verschluesselt/$dateiname.age" "$quelle"; then
    fehler 3 "Exportdatei konnte nicht verschlüsselt werden."
  fi
  [[ -s $verschluesselt/$dateiname.age ]] || fehler 3 "Verschlüsseltes Exportartefakt ist leer."
  summe=$(sha256_datei "$verschluesselt/$dateiname.age") || fehler 3 "SHA-256 konnte nicht berechnet werden."
  printf '%s  %s\n' "$summe" "$dateiname.age" >> "$verschluesselt/SHA256SUMS"
  printf '%s\t%s\t%s\t%s\n' "$art" "$mandant" "$dateiname.age" "$summe" >> "$registrierungen"
  rm -f -- "$quelle"
}

cleanup() {
  local status=$?
  trap - EXIT

  if (( realm_container_angelegt )); then
    if ! "$ARCHIV_DOCKER_BIN" rm -f "$realm_container" >/dev/null; then
      printf 'Fehler: Temporärer Realm-Export-Container konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  if (( realm_export_volume_angelegt )); then
    if ! "$ARCHIV_DOCKER_BIN" volume rm "$realm_export_volume" >/dev/null; then
      printf 'Fehler: Temporäres Realm-Export-Volume konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  if (( keycloak_angehalten )); then
    if ! compose start "$ARCHIV_KEYCLOAK_SERVICE" >/dev/null; then
      printf 'Fehler: Keycloak konnte nach dem Export nicht gestartet werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  if [[ -n $arbeitsverzeichnis && -d $arbeitsverzeichnis ]]; then
    if ! rm -rf -- "$arbeitsverzeichnis"; then
      printf 'Fehler: Arbeitsverzeichnis des Sicherungslaufs konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  if (( zustand_angelegt && ! zustand_aktiv )) && [[ -d $zustandsziel ]]; then
    if ! rm -rf -- "$zustandsziel"; then
      printf 'Fehler: Temporärer Sicherungszustand konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  if (( lauf_veroeffentlicht && ! registrierung_begonnen )) && [[ -d $ziel ]]; then
    if ! rm -rf -- "$ziel"; then
      printf 'Fehler: Unregistrierter Sicherungslauf konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    fi
  fi
  if (( sperre_aktiv )); then
    rm -f -- "$sperre/pid"
    rmdir -- "$sperre" 2>/dev/null || {
      printf 'Fehler: Lauf-Sperre konnte nicht entfernt werden.\n' >&2
      (( status != 0 )) || status=3
    }
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s\n' "$$" > "$sperre/pid" || fehler 3 "Lauf-Sperre konnte nicht protokolliert werden."

if (( veraltete_sperre )); then
  # Der abgebrochene Lauf kann Keycloak angehalten und Klartext im Arbeitsverzeichnis hinterlassen haben.
  compose start "$ARCHIV_KEYCLOAK_SERVICE" >/dev/null \
    || fehler 3 "Keycloak konnte nach einem abgebrochenen Lauf nicht gestartet werden."
  for verwaist in "$sicherungswurzel"/.arbeit.????????; do
    [[ -d $verwaist && ! -L $verwaist && -O $verwaist ]] || continue
    rm -rf -- "$verwaist" || fehler 3 "Verwaistes Arbeitsverzeichnis konnte nicht entfernt werden."
  done
fi

loesche_verwaiste_realm_export_volumes() {
  local volumen containeren
  local volumes
  volumes=$("$ARCHIV_DOCKER_BIN" volume ls --filter name=archiv-realm-export- --format '{{.Name}}') \
    || fehler 3 "Temporäre Realm-Export-Volumes konnten nicht aufgelistet werden."
  while IFS= read -r volumen; do
    [[ $volumen =~ ^archiv-realm-export-[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || continue
    containeren=$("$ARCHIV_DOCKER_BIN" ps -a --filter "volume=$volumen" --format '{{.Names}}') \
      || fehler 3 "Verwendung eines temporären Realm-Export-Volumes konnte nicht geprüft werden."
    [[ -z $containeren ]] \
      || fehler 3 "Temporäres Realm-Export-Volume $volumen ist noch an einen Container gebunden; bitte prüfen und erneut sichern."
    "$ARCHIV_DOCKER_BIN" volume rm "$volumen" >/dev/null \
      || fehler 3 "Verwaistes Realm-Export-Volume $volumen konnte nicht entfernt werden."
    printf 'Hinweis: Verwaistes Realm-Export-Volume entfernt: %s\n' "$volumen" >&2
  done <<< "$volumes"
}
loesche_verwaiste_realm_export_volumes

arbeitsverzeichnis=$(mktemp -d "$sicherungswurzel/.arbeit.XXXXXXXX") \
  || fehler 3 "Arbeitsverzeichnis kann nicht angelegt werden."
plain="$arbeitsverzeichnis/klartext"
verschluesselt="$arbeitsverzeichnis/verschluesselt"
neuer_zustand="$arbeitsverzeichnis/zustand"
registrierungen="$arbeitsverzeichnis/registrierungen.tsv"
mkdir -p -- "$plain/realm" "$verschluesselt" "$neuer_zustand"
: > "$registrierungen"
: > "$verschluesselt/SHA256SUMS"

vorheriger_lauf=
if [[ -e $zustand/aktuell || -L $zustand/aktuell ]]; then
  [[ -f $zustand/aktuell && ! -L $zustand/aktuell ]] || fehler 3 "Sicherungszustand ist ungültig."
  IFS= read -r vorheriger_lauf < "$zustand/aktuell" || fehler 3 "Sicherungszustand ist nicht lesbar."
  [[ $vorheriger_lauf =~ ^lauf-[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || fehler 3 "Sicherungszustand ist ungültig."
  [[ -d $zustandslaeufe/$vorheriger_lauf && ! -L $zustandslaeufe/$vorheriger_lauf ]] \
    || fehler 3 "Inventar des letzten erfolgreichen Laufs fehlt."
fi

pruefe_dienst() {
  local dienst=$1
  local container
  container=$(compose ps --status running --quiet "$dienst") || fehler 3 "Compose-Dienststatus konnte nicht gelesen werden."
  [[ -n $container ]] || fehler 3 "Ein benötigter Compose-Dienst läuft nicht."
}
pruefe_dienst "$ARCHIV_API_SERVICE"
pruefe_dienst "$ARCHIV_DB_SERVICE"
pruefe_dienst "$ARCHIV_KEYCLOAK_SERVICE"

# Jedes Mandantenschema m_* der Datenbank muss in ARCHIV_MANDANTEN stehen (Code mit «-» statt «_»): Sonst steckt der Mandant
# im pg_dump, hätte aber weder Inventar noch Bucket-Export, und der Lauf bliebe grün (Aufgabe 10.6: nicht still überspringen).
schemas=$(compose exec -T "$ARCHIV_DB_SERVICE" psql --no-psqlrc --no-password --username=postgres \
  --dbname="$ARCHIV_DB_NAME" --tuples-only --no-align \
  --command "select nspname from pg_namespace where nspname like 'm\\_%' order by 1") \
  || fehler 3 "Mandantenschemas konnten nicht gelesen werden."
while IFS= read -r schema; do
  [[ -n $schema ]] || continue
  code=${schema#m_}
  code=${code//_/-}
  [[ " ${mandanten[*]} " == *" $code "* ]] \
    || fehler 2 "Mandantenschema $schema fehlt in ARCHIV_MANDANTEN: Der Mandant würde nicht gesichert."
done <<< "$schemas"

loesche_alte_lauefe() {
  local verzeichnis name
  local alte_lauefe="$arbeitsverzeichnis/alte-laeufe"
  local alte_zustaende="$arbeitsverzeichnis/alte-zustaende"
  find "$laeufe" -mindepth 1 -maxdepth 1 -type d \
    -mtime "+$((ARCHIV_AUFBEWAHRUNG_TAGE - 1))" -print0 > "$alte_lauefe" \
    || fehler 3 "Alte Sicherungsläufe konnten nicht geprüft werden."
  while IFS= read -r -d '' verzeichnis; do
    name=${verzeichnis##*/}
    [[ $name =~ ^lauf-[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || continue
    [[ $name == "$laufname" ]] && continue
    rm -rf -- "$verzeichnis" || fehler 3 "Alter Sicherungslauf konnte nicht entfernt werden."
  done < "$alte_lauefe"

  find "$zustandslaeufe" -mindepth 1 -maxdepth 1 -type d \
    -mtime "+$((ARCHIV_AUFBEWAHRUNG_TAGE - 1))" -print0 > "$alte_zustaende" \
    || fehler 3 "Alte Sicherungszustände konnten nicht geprüft werden."
  while IFS= read -r -d '' verzeichnis; do
    name=${verzeichnis##*/}
    [[ $name =~ ^lauf-[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || continue
    [[ $name == "$vorheriger_lauf" ]] && continue
    rm -rf -- "$verzeichnis" || fehler 3 "Alter Sicherungszustand konnte nicht entfernt werden."
  done < "$alte_zustaende"
}

if ! compose exec -T "$ARCHIV_DB_SERVICE" pg_dump --no-password --format=custom \
  --username=postgres --dbname="$ARCHIV_DB_NAME" > "$plain/archiv.dump"; then
  fehler 3 "Archiv-Datenbank konnte nicht exportiert werden."
fi
[[ -s $plain/archiv.dump ]] || fehler 3 "Export der Archiv-Datenbank ist leer."
verschluesseln db - "$plain/archiv.dump"

keycloak_container=$(compose ps --status running --quiet "$ARCHIV_KEYCLOAK_SERVICE") \
  || fehler 3 "Keycloak-Status konnte nicht gelesen werden."
[[ -n $keycloak_container && $keycloak_container != *$'\n'* ]] \
  || fehler 3 "Für den Realm-Export wird genau ein laufender Keycloak-Container benötigt."
realm_container="archiv-realm-export-$zeitstempel-$$"
if "$ARCHIV_DOCKER_BIN" container inspect "$realm_container" >/dev/null 2>&1; then
  fehler 3 "Name des temporären Realm-Export-Containers ist bereits belegt."
fi
keycloak_angehalten=1
compose stop --timeout 60 "$ARCHIV_KEYCLOAK_SERVICE" >/dev/null \
  || fehler 3 "Keycloak konnte für den konsistenten Realm-Export nicht angehalten werden."
if ! compose exec -T "$ARCHIV_DB_SERVICE" pg_dump --no-password --format=custom \
  --username=postgres --dbname="$ARCHIV_KEYCLOAK_DB_NAME" > "$plain/keycloak.dump"; then
  fehler 3 "Keycloak-Datenbank konnte nicht exportiert werden."
fi
[[ -s $plain/keycloak.dump ]] || fehler 3 "Export der Keycloak-Datenbank ist leer."
verschluesseln keycloak - "$plain/keycloak.dump"
# shellcheck disable=SC2016 # Der Befehl wird im Container ausgewertet, nicht im Host-Shellprozess.
# Das Wegwerf-Volume bleibt nach dem Containerlauf für docker cp verfügbar. Das Image legt den Zielordner bereits mit
# restriktiven Rechten für uid 1000 an; so funktioniert der Export trotz read_only ohne Klartext-Bind-Mount.
# Der Export braucht keinen Bootstrap-Admin; Keycloak 26.8 bricht ab, wenn der Name ohne Passwort gesetzt ist.
realm_export_pfad=/opt/keycloak/data/archiv-export
realm_export_volume=$realm_container
if "$ARCHIV_DOCKER_BIN" volume inspect "$realm_export_volume" >/dev/null 2>&1; then
  fehler 3 "Name des temporären Realm-Export-Volumes ist bereits belegt."
fi
"$ARCHIV_DOCKER_BIN" volume create "$realm_export_volume" >/dev/null \
  || fehler 3 "Temporäres Realm-Export-Volume konnte nicht angelegt werden."
realm_export_volume_angelegt=1
realm_container_angelegt=1
realm_export_befehl='set -euo pipefail; umask 077; unset KC_BOOTSTRAP_ADMIN_USERNAME; mkdir -p /opt/keycloak/data/archiv-export; export KC_DB_PASSWORD="$(< /run/secrets/keycloak_db)"; exec /opt/keycloak/bin/kc.sh export --optimized --dir /opt/keycloak/data/archiv-export --realm urinet-archiv --users same_file'
compose run --no-deps -T --name "$realm_container" --volume "$realm_export_volume:$realm_export_pfad" \
  --entrypoint /bin/bash \
  "$ARCHIV_KEYCLOAK_SERVICE" -c "$realm_export_befehl" \
  || fehler 3 "Keycloak-Realm konnte nicht exportiert werden."
"$ARCHIV_DOCKER_BIN" cp "$realm_container:$realm_export_pfad/." "$plain/realm/" \
  || fehler 3 "Keycloak-Realm-Export konnte nicht aus dem Container kopiert werden."
compose start "$ARCHIV_KEYCLOAK_SERVICE" >/dev/null \
  || fehler 3 "Keycloak konnte nach dem Realm-Export nicht gestartet werden."
keycloak_angehalten=0
"$ARCHIV_DOCKER_BIN" rm "$realm_container" >/dev/null \
  || fehler 3 "Temporärer Realm-Export-Container konnte nicht entfernt werden."
realm_container_angelegt=0
[[ -n $(find "$plain/realm" -type f -size +0c -print -quit) ]] || fehler 3 "Keycloak-Realm-Export ist leer."
if ! tar -C "$plain" -cf "$plain/keycloak-realm.tar" realm; then
  fehler 3 "Keycloak-Realm-Export konnte nicht gebündelt werden."
fi
verschluesseln realm - "$plain/keycloak-realm.tar"
rm -rf -- "$plain/realm"

compose cp \
  "$ARCHIV_API_SERVICE:/run/secrets/archiv_manifest_key" "$plain/manifest.key" \
  || fehler 3 "Manifest-Schlüssel konnte nicht aus dem API-Container kopiert werden."
[[ -s $plain/manifest.key ]] || fehler 3 "Manifest-Schlüssel ist leer."
chmod 600 "$plain/manifest.key"
verschluesseln schluessel - "$plain/manifest.key"

# Req 16.23: Wird der Probe-Schlüssel gewechselt, folgt ein neuer Vollexport. Ein Zuwachs der Tage vor dem Wechsel bliebe
# sonst nur für den alten Probe-Schlüssel lesbar, und die Restore-Probe könnte den Bucket nicht vollständig entschlüsseln.
# Gespeichert wird nur die Prüfsumme des öffentlichen Schlüssels, im Zustand des Laufs neben den Inventaren.
printf '%s' "$ARCHIV_AGE_EMPFAENGER_PROBE" > "$arbeitsverzeichnis/probe-empfaenger"
probe_summe=$(sha256_datei "$arbeitsverzeichnis/probe-empfaenger") || fehler 3 "SHA-256 konnte nicht berechnet werden."
rm -f -- "$arbeitsverzeichnis/probe-empfaenger"
printf '%s\n' "$probe_summe" > "$neuer_zustand/probe-empfaenger.sha256"
vollexport=0
vollexport_grund="Erster Export"
if [[ $ARCHIV_VOLLEXPORT == 1 ]]; then
  vollexport=1
  vollexport_grund="Vollexport angefordert (ARCHIV_VOLLEXPORT=1)"
elif [[ -n $vorheriger_lauf ]]; then
  vorherige_summe=
  vorheriger_probe="$zustandslaeufe/$vorheriger_lauf/probe-empfaenger.sha256"
  if [[ -f $vorheriger_probe && ! -L $vorheriger_probe ]]; then
    IFS= read -r vorherige_summe < "$vorheriger_probe" || true
  fi
  if [[ $vorherige_summe != "$probe_summe" ]]; then
    vollexport=1
    vollexport_grund="Probe-Schlüssel gewechselt oder unbekannt (Req 16.23)"
  fi
fi

for mandant in "${mandanten[@]}"; do
  inventar="$plain/inventar-$mandant.jsonl"
  bucket="$plain/bucket-$mandant.bin"
  if ! api_kommando sicherung inventar --mandant "$mandant" > "$inventar" < /dev/null; then
    fehler 3 "Bucket-Inventar konnte nicht erstellt werden."
  fi

  vorheriges_inventar=
  vorheriger_pfad="$zustandslaeufe/$vorheriger_lauf/inventar-$mandant.jsonl"
  if [[ -n $vorheriger_lauf && ( -e $vorheriger_pfad || -L $vorheriger_pfad ) ]]; then
    [[ -f $vorheriger_pfad && ! -L $vorheriger_pfad ]] || fehler 3 "Inventar des Vortags ist ungültig."
    vorheriges_inventar=$vorheriger_pfad
  fi

  if [[ -n $vorheriges_inventar ]] && (( ! vollexport )); then
    if ! cat -- "$vorheriges_inventar" \
      | api_kommando sicherung zuwachs --mandant "$mandant" --inventar-vorher - > "$bucket"; then
      fehler 3 "Bucket-Zuwachs konnte nicht exportiert werden."
    fi
  else
    printf 'Hinweis: %s für Mandant %s; vollständiger Bucket-Export.\n' "$vollexport_grund" "$mandant" >&2
    if ! api_kommando sicherung zuwachs --mandant "$mandant" > "$bucket" < /dev/null; then
      fehler 3 "Vollständiger Bucket-Export konnte nicht erstellt werden."
    fi
  fi
  cp -- "$inventar" "$neuer_zustand/inventar-$mandant.jsonl"
  chmod 600 "$neuer_zustand/inventar-$mandant.jsonl"
  verschluesseln inventar "$mandant" "$inventar"
  verschluesseln bucket "$mandant" "$bucket"
done

mv -- "$verschluesselt" "$ziel" || fehler 3 "Verschlüsselter Sicherungslauf konnte nicht atomar veröffentlicht werden."
lauf_veroeffentlicht=1
mv -- "$neuer_zustand" "$zustandsziel" || fehler 3 "Neuer Sicherungszustand konnte nicht gespeichert werden."
zustand_angelegt=1

# Eigener Dateideskriptor: Kein Aufruf in der Schleife kann die restlichen Zeilen von stdin verbrauchen.
while IFS=$'\t' read -r -u 3 art mandant dateiname summe; do
  [[ -n $art && -n $dateiname && -n $summe ]] || fehler 3 "Interne SHA-256-Liste ist ungültig."
  registrierung_begonnen=1
  if [[ $mandant == - ]]; then
    if ! api_kommando sicherung festhalten --art "$art" --sha256 "$summe" >/dev/null < /dev/null; then
      fehler 3 "SHA-256 konnte nicht im Sicherungsregister festgehalten werden."
    fi
  else
    if ! api_kommando sicherung festhalten --art "$art" --mandant "$mandant" --sha256 "$summe" >/dev/null < /dev/null; then
      fehler 3 "SHA-256 konnte nicht im Sicherungsregister festgehalten werden."
    fi
  fi
done 3< "$registrierungen"

printf 'Vollständig verschlüsselt und registriert.\n' > "$ziel/FERTIG.tmp"
mv -- "$ziel/FERTIG.tmp" "$ziel/FERTIG" || fehler 3 "Sicherungslauf konnte nicht als vollständig markiert werden."
printf '%s\n' "$laufname" > "$arbeitsverzeichnis/aktuell"
mv -f -- "$arbeitsverzeichnis/aktuell" "$zustand/aktuell" || fehler 3 "Sicherungszustand konnte nicht atomar aktualisiert werden."
zustand_aktiv=1
loesche_alte_lauefe
printf 'Sicherungslauf abgeschlossen: %s\n' "$laufname"
