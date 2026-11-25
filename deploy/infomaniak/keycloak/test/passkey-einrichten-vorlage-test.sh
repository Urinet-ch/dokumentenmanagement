#!/usr/bin/env bash
# Stub-Test der Vorlage passkey-einrichten-vorlage.sh mit einem kcadm-Stub und synthetischen Werten (kein Keycloak nötig).
# Der Echt-Test gegen einen Wegwerf-Keycloak steht in passkey-einrichten-keycloak-test.sh.
set -euo pipefail

repo=$(cd "$(dirname "$0")/../../../.." && pwd)
vorlage="$repo/deploy/infomaniak/keycloak/passkey-einrichten-vorlage.sh"
temp=$(mktemp -d)
trap 'rm -rf -- "$temp"' EXIT
mkdir -p "$temp/bin"

cat > "$temp/bin/kcadm-stub" <<'STUB'
#!/usr/bin/env bash
# Antwortet je nach KC_STUB_ZUSTAND (alt | neu | fremdflow | fremderp | extra) mit festen Daten und protokolliert jeden Aufruf.
set -euo pipefail
printf '%s\n' "$*" >> "$KC_KCADM_AUFRUFPROTOKOLL"
z=${KC_STUB_ZUSTAND:-neu}
[[ $1 == get ]] || exit 0
politik='"webAuthnPolicyPasswordlessRpEntityName":"UriNet Archiv","webAuthnPolicyPasswordlessRpId":"archiv.example.invalid","webAuthnPolicyPasswordlessAttestationConveyancePreference":"none","webAuthnPolicyPasswordlessResidentKey":"required","webAuthnPolicyPasswordlessUserVerificationRequirement":"required","webAuthnPolicyPasswordlessPasskeysEnabled":true,"webAuthnPolicyPasswordlessMediation":"conditional"'
passwort='{"id":"pw","level":0,"providerId":"auth-username-password-form","requirement":"REQUIRED","priority":10,"displayName":"Username Password Form"}'
otp='{"id":"otp","level":0,"providerId":"auth-otp-form","requirement":"REQUIRED","priority":20,"displayName":"OTP Form"}'
unterflow='{"id":"uf","level":0,"authenticationFlow":true,"flowId":"passkey-id","requirement":"CONDITIONAL","priority":20,"displayName":"browser-passkey-otp","description":"TOTP ausser nach Anmeldung mit Passkey"}'
case "$2" in
  realms/urinet-archiv)
    case $z in
      ereignisaus) printf '{"realm":"urinet-archiv","browserFlow":"browser-totp-pflicht","eventsEnabled":false,"enabledEventTypes":["LOGIN_ERROR"],%s}\n' "$politik" ;;
      alt | altextra | altleer) printf '{"realm":"urinet-archiv","browserFlow":"browser-totp-pflicht","webAuthnPolicyPasswordlessRpId":"","webAuthnPolicyPasswordlessRpEntityName":"keycloak","eventsEnabled":true}\n' ;;
      fremdflow) printf '{"realm":"urinet-archiv","browserFlow":"browser",%s}\n' "$politik" ;;
      fremderp) printf '{"realm":"urinet-archiv","browserFlow":"browser-totp-pflicht","webAuthnPolicyPasswordlessRpId":"anders.example.invalid"}\n' ;;
      *) printf '{"realm":"urinet-archiv","browserFlow":"browser-totp-pflicht","eventsEnabled":true,"enabledEventTypes":["LOGIN_ERROR"],%s}\n' "$politik" ;;
    esac ;;
  authentication/required-actions)
    printf '[{"alias":"webauthn-register-passwordless","enabled":true,"defaultAction":false,"priority":%s},{"alias":"delete_credential","enabled":true,"defaultAction":false,"priority":70}]\n' \
      "$([[ $z == alt* ]] && echo 31 || echo 60)" ;;
  clients)
    case "$*" in
      *clientId=account-console*) printf '[{"id":"konsole","clientId":"account-console"}]\n' ;;
      *) printf '[{"id":"konto","clientId":"account"}]\n' ;;
    esac ;;
  clients/konsole/scope-mappings/clients/konto)
    if [[ $z == alt* ]]; then printf '[]\n'; else printf '[{"name":"manage-account"},{"name":"view-profile"}]\n'; fi ;;
  clients/konto/roles/*) printf '{"id":"rolle-id","name":"%s"}\n' "${2##*/}" ;;
  authentication/flows)
    printf '[{"alias":"browser-totp-pflicht","id":"oben-id","description":"Anmeldung im Browser: Cookie, Passkey oder Passwort und TOTP (Req 2.1, Spec 06 Req 10)"}]\n' ;;
  authentication/flows/browser-totp-pflicht/executions)
    printf '[{"id":"c","level":0,"providerId":"auth-cookie","requirement":"ALTERNATIVE","displayName":"Cookie"},{"id":"f","level":0,"authenticationFlow":true,"flowId":"formulare-id","requirement":"ALTERNATIVE","displayName":"browser-totp-pflicht-formulare","description":"Passkey oder Passwort mit TOTP"}]\n' ;;
  authentication/flows/browser-totp-pflicht-formulare/executions)
    case $z in
      alt | altleer) printf '[%s,%s]\n' "$passwort" "$otp" ;;
      leerteilflow) printf '[%s,%s]\n' "$passwort" "${unterflow/CONDITIONAL/DISABLED}" ;;
      teilconditional) printf '[%s,%s,%s]\n' "$passwort" "$otp" "$unterflow" ;;
      extra | altextra) printf '[%s,%s,{"id":"x","level":0,"providerId":"auth-spnego","requirement":"ALTERNATIVE","displayName":"Kerberos"}]\n' "$passwort" "$otp" ;;
      *) printf '[%s,%s,{"id":"b","level":1,"providerId":"conditional-credential","requirement":"REQUIRED","priority":10},{"id":"o","level":1,"providerId":"auth-otp-form","requirement":"REQUIRED","priority":20}]\n' "$passwort" "$unterflow" ;;
    esac ;;
  authentication/flows/browser-passkey-otp/executions)
    [[ $z == leerteilflow ]] && { printf '[]\n'; exit 0; }
    printf '[{"id":"b","level":0,"providerId":"conditional-credential","requirement":"REQUIRED","priority":10,"authenticationConfig":"cfg"},{"id":"o","level":0,"providerId":"auth-otp-form","requirement":"REQUIRED","priority":20}]\n' ;;
  authentication/config/cfg)
    printf '{"id":"cfg","alias":"otp-nur-ohne-passkey","config":{"credentials":"webauthn-passwordless","included":"false"}}\n' ;;
esac
STUB
chmod +x "$temp/bin/kcadm-stub"

lauf() { # <Zustand> <Argumente …>; setzt status und schreibt Ausgabe nach $temp/ausgabe, Aufrufe nach $temp/aufrufe
  local zustand=$1
  shift
  : > "$temp/aufrufe"
  status=0
  KC_STUB_ZUSTAND=$zustand KC_KCADM_AUFRUFPROTOKOLL="$temp/aufrufe" KCADM="$temp/bin/kcadm-stub" \
    ARCHIV_PASSKEY_RP_ID=${RP_ID-archiv.example.invalid} TMPDIR="$temp" \
    bash "$vorlage" "$@" > "$temp/ausgabe" 2>&1 || status=$?
}
erwarte() { # <Exit-Code> <Beschreibung>
  [[ $status == "$1" ]] || { cat "$temp/ausgabe" >&2; echo "Test «$2»: Exit $status statt $1." >&2; exit 1; }
}
aenderungen() { grep -E '^(create|update|delete) ' "$temp/aufrufe" || true; }

# Alter Realm, nur prüfen: Exit 10, Abweichungen genannt, nichts verändert
lauf alt --pruefen
erwarte 10 "alter Realm prüfen"
grep -q '^Abweichung: WebAuthn-Passwordless-Richtlinie' "$temp/ausgabe"
grep -q '^Abweichung: Teilflow browser-passkey-otp in browser-totp-pflicht-formulare anlegen' "$temp/ausgabe"
grep -q '^Abweichung: Kontoverwaltung: Rolle manage-account' "$temp/ausgabe"
grep -q '^Abweichung: Required Action webauthn-register-passwordless' "$temp/ausgabe"
[[ -z $(aenderungen) ]] || { echo "--pruefen hat verändert." >&2; exit 1; }

# Aktueller Stand: nichts zu tun, mit und ohne --pruefen, ohne schreibende Aufrufe
lauf neu --pruefen
erwarte 0 "aktueller Stand prüfen"
grep -q 'nichts zu tun' "$temp/ausgabe"
lauf neu
erwarte 0 "aktueller Stand anwenden"
[[ -z $(aenderungen) ]] || { aenderungen >&2; echo "Wiederholung hat verändert." >&2; exit 1; }
! grep -q 'UPDATE_CREDENTIAL' "$temp/aufrufe"

# Ereignisse nur mit ausdrücklicher Option; die bestehenden Typen bleiben erhalten
lauf neu --pruefen --ereignisse
erwarte 10 "Ereignisse prüfen"
grep -q '^Abweichung: Ereignisse UPDATE_CREDENTIAL und REMOVE_CREDENTIAL' "$temp/ausgabe"

# Unerwartete Zustände: Abbruch mit Meldung, kein Raten, keine Änderung
# Ereignisse: Abbruch bei ausgeschalteten Ereignissen und bei leerer Liste, im Anwendungsmodus ohne jede Änderung
lauf ereignisaus --pruefen --ereignisse
erwarte 1 "Ereignisse ausgeschaltet"
grep -q '^Abbruch: Die Ereignisse des Realms sind ausgeschaltet' "$temp/ausgabe"
lauf altleer --ereignisse
erwarte 1 "leere Ereignisliste trotz anstehender Änderungen"
grep -q '^Abbruch: Die Liste enabledEventTypes ist leer' "$temp/ausgabe"
[[ -z $(aenderungen) ]] || { aenderungen >&2; echo "Abbruch bei --ereignisse hat vorher verändert." >&2; exit 1; }
# Abbruch mitten im Flow (unerwartete Ausführung) nach anstehenden Richtlinien-Änderungen: der Probelauf verhindert jede Änderung
lauf altextra
erwarte 1 "Abbruch im Flow beim Anwenden"
grep -q '^Abbruch: Der Flow browser-totp-pflicht-formulare hat nicht die erwartete Form' "$temp/ausgabe"
[[ -z $(aenderungen) ]] || { aenderungen >&2; echo "Abbruch im Flow hat vorher verändert." >&2; exit 1; }
# Teilzustände nach einem Abbruch mitten im Anlegen: Wiederholung führt weiter, ohne Zweitfaktor-Lücke
lauf leerteilflow --pruefen
erwarte 10 "Teilflow ohne Kinder"
grep -q '^Abweichung: Teilflow browser-passkey-otp: Bedingung' "$temp/ausgabe"
grep -q '^Abweichung: Teilflow browser-passkey-otp: OTP-Formular anlegen' "$temp/ausgabe"
grep -q '^Abweichung: Teilflow browser-passkey-otp: CONDITIONAL' "$temp/ausgabe"
lauf teilconditional --pruefen
erwarte 10 "Teilflow CONDITIONAL, OTP direkt noch da"
grep -q '^Abweichung: OTP-Formular direkt in browser-totp-pflicht-formulare entfernen' "$temp/ausgabe"
! grep -q 'Teilflow browser-passkey-otp: CONDITIONAL' "$temp/ausgabe"
lauf teilconditional
# Der Stub ist zustandslos: die Selbstnachprüfung meldet darum Rest-Abweichungen (Exit 1); geprüft wird, was aufgerufen wurde.
erwarte 1 "Teilflow CONDITIONAL, OTP direkt noch da, anwenden"
grep -q '^delete authentication/executions/otp ' "$temp/aufrufe"
lauf leerteilflow
# Der Stub ist zustandslos: die Selbstnachprüfung meldet darum Rest-Abweichungen (Exit 1); geprüft wird, was aufgerufen wurde.
erwarte 1 "Teilflow ohne Kinder, anwenden"
grep -q '^create authentication/flows/browser-passkey-otp/executions/execution ' "$temp/aufrufe"
! grep -q '^delete ' "$temp/aufrufe" || { echo "Ohne direktes OTP-Formular darf nichts gelöscht werden." >&2; exit 1; }

lauf fremdflow --pruefen
erwarte 1 "fremder browserFlow"
grep -q '^Abbruch: Der Realm verwendet browserFlow nicht als browser-totp-pflicht' "$temp/ausgabe"
lauf fremderp --pruefen
erwarte 1 "andere RP-ID"
grep -q '^Abbruch: Der Realm hat bereits eine andere Relying-Party-ID' "$temp/ausgabe"
lauf extra --pruefen
erwarte 1 "unerwartete Ausführung"
grep -q '^Abbruch: Der Flow browser-totp-pflicht-formulare hat nicht die erwartete Form' "$temp/ausgabe"
lauf fremdflow
[[ -z $(aenderungen) ]] || { echo "Abbruch ohne Änderung erwartet." >&2; exit 1; }

# Eingaben
for falsch in "" "https://archiv.example.invalid" "archiv.example.invalid:8443" "archiv.example.invalid/pfad" "ARCHIV.example.invalid" "10.0.0.1" "-archiv.example.invalid"; do
  RP_ID=$falsch lauf neu --pruefen
  erwarte 1 "RP-ID «${falsch}»"
  grep -q '^Abbruch: ARCHIV_PASSKEY_RP_ID' "$temp/ausgabe"
  [[ ! -s $temp/aufrufe ]] || { echo "Keycloak wurde trotz ungültiger RP-ID aufgerufen." >&2; exit 1; }
done
lauf neu --unbekannt
erwarte 2 "unbekannte Option"
KC_REALM='../master' lauf neu --pruefen
erwarte 1 "Realm-Name"

# Das temporäre Arbeitsverzeichnis der Vorlage wird entfernt
[[ -z $(find "$temp" -maxdepth 1 -name 'urinet-passkey.*') ]] || { echo "Temporäres Verzeichnis nicht entfernt." >&2; exit 1; }
echo "Passkey-Vorlage mit kcadm-Stub geprüft."
