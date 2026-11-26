#!/usr/bin/env bash
# Stub-Test der Vorlage ereignisse-auflisten-vorlage.sh mit einem kcadm-Stub und synthetischen Werten (kein Keycloak nötig).
# Der Echt-Test gegen einen Wegwerf-Keycloak steht in ereignisse-auflisten-keycloak-test.sh.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../../../.." && pwd)
vorlage="$repo/deploy/infomaniak/keycloak/ereignisse-auflisten-vorlage.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
mkdir -p "$temp/bin"

cat > "$temp/bin/kcadm-stub" <<'STUB'
#!/usr/bin/env bash
# Antwortet je nach KC_STUB_ZUSTAND (aktiv | leer | aus | ohnetyp | fehler) mit festen, synthetischen Daten; protokolliert jeden Aufruf.
# Der Stub filtert absichtlich NICHT nach Typ und Zeit: Die Vorlage muss selbst begrenzen und darf nur vier Felder ausgeben.
set -euo pipefail
printf '%s\n' "$*" >> "$KC_KCADM_AUFRUFPROTOKOLL"
z=${KC_STUB_ZUSTAND:-aktiv}
[[ $1 == get ]] || exit 0
[[ $z != fehler ]] || { echo "Boom: synthetischer Fehler" >&2; exit 1; }
case "$2" in
  realms/urinet-archiv)
    case $z in
      aus) echo '{"eventsEnabled":false,"enabledEventTypes":["UPDATE_CREDENTIAL","REMOVE_CREDENTIAL"]}' ;;
      ohnetyp) echo '{"eventsEnabled":true,"enabledEventTypes":["UPDATE_CREDENTIAL","LOGIN_ERROR"]}' ;;
      *) echo '{"eventsEnabled":true,"enabledEventTypes":["UPDATE_CREDENTIAL","REMOVE_CREDENTIAL","LOGIN"]}' ;;
    esac ;;
  events)
    [[ $z != leer ]] || { echo '[]'; exit 0; }
    jetzt=$(( $(date +%s) * 1000 ))
    # Felder, die nie ausgegeben werden dürfen, stehen absichtlich in den Daten.
    sensibel='"ipAddress":"203.0.113.77","details":{"username":"anna.muster","email":"anna@example.invalid","token":"geheim-token"}'
    printf '[{"time":%s,"type":"UPDATE_CREDENTIAL","realmId":"r","clientId":"account-console","userId":"11111111-1111-1111-1111-111111111111",%s},' "$((jetzt - 3600000))" "$sensibel"
    printf '{"time":%s,"type":"REMOVE_CREDENTIAL","clientId":"account-console","userId":"22222222-2222-2222-2222-222222222222",%s},' "$((jetzt - 7200000))" "$sensibel"
    printf '{"time":%s,"type":"LOGIN","clientId":"archiv","userId":"33333333-3333-3333-3333-333333333333",%s},' "$((jetzt - 60000))" "$sensibel"
    printf '{"time":%s,"type":"UPDATE_CREDENTIAL","clientId":"account-console","userId":"44444444-4444-4444-4444-444444444444",%s},' "$((jetzt - 5 * 86400000))" "$sensibel"
    printf '{"time":%s,"type":"UPDATE_CREDENTIAL","userId":"55555555-5555-5555-5555-555555555555"}]\n' "$((jetzt - 1000))" ;;
esac
STUB
chmod +x "$temp/bin/kcadm-stub"

lauf() { # <Zustand> <Argumente …>; setzt status, schreibt Ausgabe nach $temp/ausgabe, Aufrufe nach $temp/aufrufe
  local zustand=$1
  shift
  : > "$temp/aufrufe"
  status=0
  KC_STUB_ZUSTAND=$zustand KC_KCADM_AUFRUFPROTOKOLL="$temp/aufrufe" KCADM="$temp/bin/kcadm-stub" TMPDIR="$temp" \
    bash "$vorlage" "$@" > "$temp/ausgabe" 2>&1 || status=$?
}
erwarte() { # <Exit-Code> <Beschreibung>
  [[ $status == "$1" ]] || { cat "$temp/ausgabe" >&2; echo "Test «$2»: Exit $status statt $1." >&2; exit 1; }
}
schreibend() { grep -Ev '^get ' "$temp/aufrufe" || true; }

# Standard: beide Passkey-Typen, neueste zuerst, Zeitfenster 1 Tag (der 5 Tage alte Eintrag fällt weg), LOGIN wird nicht gezeigt
lauf aktiv
erwarte 0 "Standard"
grep -q '^Zeit (UTC) *Typ *Benutzerkennung *Client' "$temp/ausgabe"
grep -Eq '^20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z +UPDATE_CREDENTIAL +11111111-1111-1111-1111-111111111111 +account-console$' "$temp/ausgabe"
grep -Eq 'REMOVE_CREDENTIAL +22222222-2222-2222-2222-222222222222' "$temp/ausgabe"
grep -Eq 'UPDATE_CREDENTIAL +55555555-5555-5555-5555-555555555555 +-$' "$temp/ausgabe"
! grep -q '33333333' "$temp/ausgabe" || { echo "LOGIN darf nicht erscheinen." >&2; exit 1; }
! grep -q '44444444' "$temp/ausgabe" || { echo "Ereignis ausserhalb des Zeitfensters erschienen." >&2; exit 1; }
grep -q '^Summe UPDATE_CREDENTIAL: 2$' "$temp/ausgabe"
grep -q '^Summe REMOVE_CREDENTIAL: 1$' "$temp/ausgabe"
[[ $(grep -n 'UPDATE_CREDENTIAL  *55555555' "$temp/ausgabe" | cut -d: -f1) -lt $(grep -n '11111111' "$temp/ausgabe" | cut -d: -f1) ]] ||
  { echo "Neueste Ereignisse müssen oben stehen." >&2; exit 1; }
# Datensparsamkeit: kein Benutzername, keine E-Mail, keine IP-Adresse, keine Details
for verboten in anna muster example.invalid 203.0.113 geheim-token ipAddress details username; do
  ! grep -qi -- "$verboten" "$temp/ausgabe" || { cat "$temp/ausgabe" >&2; echo "Ausgabe enthält «$verboten»." >&2; exit 1; }
done
# Abfragen: je Typ eine, nur lesend, mit Realm, Typ, Datum und Höchstzahl
grep -q '^get events -r urinet-archiv -q type=UPDATE_CREDENTIAL -q dateFrom=20[0-9-]* -q max=20 ' "$temp/aufrufe"
grep -q '^get events -r urinet-archiv -q type=REMOVE_CREDENTIAL ' "$temp/aufrufe"
[[ -z $(schreibend) ]] || { schreibend >&2; echo "Die Vorlage hat nicht nur gelesen." >&2; exit 1; }

# Eigene Typen (mehrfach und kommagetrennt), --max, --seit
lauf aktiv --typ LOGIN --typ UPDATE_CREDENTIAL,LOGIN --max 5 --seit 7
erwarte 0 "eigene Typen"
grep -q '33333333' "$temp/ausgabe"
grep -q '44444444' "$temp/ausgabe"
grep -q '^Summe LOGIN: 1$' "$temp/ausgabe"
! grep -q '^Summe REMOVE_CREDENTIAL' "$temp/ausgabe"
grep -q -- '-q type=LOGIN .* -q max=5 ' "$temp/aufrufe"
[[ $(grep -c '^get events ' "$temp/aufrufe") == 2 ]] || { echo "LOGIN doppelt abgefragt." >&2; exit 1; }
lauf aktiv --max 2
erwarte 0 "--max begrenzt"
[[ $(grep -Ec '^20[0-9]{2}-' "$temp/ausgabe") == 2 ]]
grep -q '^Hinweis: Höchstzahl 2 erreicht' "$temp/ausgabe"

# Nichts gefunden: Exit 3
lauf leer
erwarte 3 "keine Ereignisse"
grep -q '^Keine Ereignisse gefunden' "$temp/ausgabe"
lauf aktiv --typ LOGIN_ERROR
erwarte 1 "Typ nicht aktiviert"
grep -q '^Abbruch: Im Realm nicht aktiviert (enabledEventTypes): LOGIN_ERROR' "$temp/ausgabe"
[[ -z $(grep '^get events' "$temp/aufrufe" || true) ]] || { echo "Ohne aktivierten Typ darf nicht abgefragt werden." >&2; exit 1; }

# Fehler: Ereignisse aus, Typ nicht aktiviert, kcadm-Fehler
lauf aus
erwarte 1 "Ereignisse aus"
grep -q '^Abbruch: Die Ereignisse des Realms sind ausgeschaltet' "$temp/ausgabe"
lauf ohnetyp
erwarte 1 "Typ fehlt in enabledEventTypes"
grep -q '^Abbruch: Im Realm nicht aktiviert (enabledEventTypes): REMOVE_CREDENTIAL' "$temp/ausgabe"
lauf fehler
erwarte 1 "kcadm-Fehler"
grep -q 'Keycloak-Aufruf get realms/urinet-archiv fehlgeschlagen: Boom' "$temp/ausgabe"

# Ungültige Parameter: Exit 2, Keycloak wird nicht aufgerufen
for falsch in "--typ login" "--typ UPDATE-CREDENTIAL" "--typ" "--typ A;B" '--typ $(id)' "--typ ,"  "--max 0" "--max 201" "--max abc" "--max" \
    "--max -1" "--seit 0" "--seit 366" "--seit 1.5" "--unbekannt" "UPDATE_CREDENTIAL"; do
  # shellcheck disable=SC2086 # Wörter absichtlich getrennt
  lauf aktiv $falsch
  erwarte 2 "ungültig: $falsch"
  [[ ! -s $temp/aufrufe ]] || { echo "Keycloak wurde trotz ungültigem Aufruf «$falsch» aufgerufen." >&2; exit 1; }
done
KC_REALM='../master' lauf aktiv
erwarte 1 "Realm-Name"
[[ ! -s $temp/aufrufe ]]

# Das temporäre Arbeitsverzeichnis wird entfernt
[[ -z $(find "$temp" -maxdepth 1 -name 'urinet-ereignisse.*') ]] || { echo "Temporäres Verzeichnis nicht entfernt." >&2; exit 1; }
echo "Ereignis-Vorlage mit kcadm-Stub geprüft."
