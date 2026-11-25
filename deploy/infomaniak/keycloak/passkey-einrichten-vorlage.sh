#!/usr/bin/env bash
# Zieht die Passkey-Änderungen aus Aufgabe 24.1 (Quelle: deploy/keycloak/realm-export.json) auf einem bestehenden
# Realm nach; der Realm-Import wirkt nur beim ersten Start. Wiederholbar: Was schon stimmt, bleibt unberührt.
#
#   passkey-einrichten-vorlage.sh [--pruefen] [--ereignisse]
#
#   --pruefen     nur lesen und Abweichungen melden; Exit 0 = nichts zu tun, Exit 10 = Änderungen nötig
#   --ereignisse  zusätzlich die Keycloak-Ereignisse UPDATE_CREDENTIAL und REMOVE_CREDENTIAL aktivieren
#                 (Req 10.7; Entscheid Fabian steht aus, darum nie ohne diese Option)
#
# Umgebung: ARCHIV_PASSKEY_RP_ID (Pflicht, nur Host), KC_REALM (urinet-archiv), KCADM (/opt/keycloak/bin/kcadm.sh).
# kcadm muss für den Realm master angemeldet sein; benötigt wird jq. Exit 1 = Fehler oder unerwarteter Zustand, 2 = Aufruf.
set -euo pipefail

realm=${KC_REALM:-urinet-archiv}
kcadm=${KCADM:-/opt/keycloak/bin/kcadm.sh}
rp_id=${ARCHIV_PASSKEY_RP_ID:-}
modus=anwenden
ereignisse=nein

hilfe() {
  echo "Aufruf: passkey-einrichten-vorlage.sh [--pruefen] [--ereignisse]" >&2
}
for argument in "$@"; do
  case $argument in
    --pruefen) modus=pruefen ;;
    --ereignisse) ereignisse=ja ;;
    -h | --hilfe) hilfe; exit 0 ;;
    *) hilfe; exit 2 ;;
  esac
done

abbruch() {
  echo "Abbruch: $*" >&2
  exit 1
}

command -v jq >/dev/null || abbruch "jq fehlt; siehe deploy/infomaniak/keycloak/README.md (Abschnitt «Passkey-Realm nachrüsten»)."
[[ $realm =~ ^[A-Za-z0-9_-]+$ ]] || abbruch "KC_REALM enthält unzulässige Zeichen."
if [[ -z $rp_id ]]; then
  abbruch "ARCHIV_PASSKEY_RP_ID fehlt (Host der App-Basisadresse, ohne Schema und Port)."
fi
# Nur ein Host: kleingeschriebene Labels, keine IP-Adresse (WebAuthn erlaubt keine), kein Schema, Port oder Pfad.
if (( ${#rp_id} > 253 )) || [[ ! $rp_id =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$ ]] \
    || [[ $rp_id =~ ^[0-9.]+$ ]]; then
  abbruch "ARCHIV_PASSKEY_RP_ID ist kein Host (nur Kleinbuchstaben, Ziffern, Bindestriche und Punkte; kein Schema, Port, Pfad, keine IP-Adresse)."
fi

umask 077
arbeit=$(mktemp -d "${TMPDIR:-/tmp}/urinet-passkey.XXXXXX")
trap 'rm -rf -- "$arbeit"' EXIT

kc() {
  if ! "$kcadm" "$@" 2>"$arbeit/fehler"; then
    echo "Keycloak-Aufruf $1 $2 fehlgeschlagen: $(tail -n 1 "$arbeit/fehler")" >&2
    return 1
  fi
}

abweichungen=0
# aendern <Beschreibung> <Befehl …>: zählt die Abweichung; ändert nur im Modus «anwenden».
aendern() {
  local text=$1
  shift
  abweichungen=$((abweichungen + 1))
  if [[ $modus == pruefen ]]; then
    echo "Abweichung: $text"
    return 0
  fi
  "$@"
  echo "Angepasst: $text"
}

# ---- Soll-Werte (identisch zu deploy/keycloak/realm-export.json) -------------------------------------------------
flow_oben=browser-totp-pflicht
flow_formulare=browser-totp-pflicht-formulare
flow_passkey=browser-passkey-otp
konfig_alias="otp-nur-ohne-passkey"
beschreibung_oben="Anmeldung im Browser: Cookie, Passkey oder Passwort und TOTP (Req 2.1, Spec 06 Req 10)"
beschreibung_formulare="Passkey oder Passwort mit TOTP"
beschreibung_passkey="TOTP ausser nach Anmeldung mit Passkey"

ausfuehrungen() { # <Flow-Alias> <Datei>
  kc get "authentication/flows/$1/executions" -r "$realm" > "$2"
}

ausfuehrung_setzen() { # <Flow-Alias> <Ausführungs-ID> <Anforderung> <Priorität>
  jq -n --arg id "$2" --arg anf "$3" --argjson prio "$4" '{id: $id, requirement: $anf, priority: $prio}' \
    > "$arbeit/ausfuehrung.json"
  kc update "authentication/flows/$1/executions" -r "$realm" -f "$arbeit/ausfuehrung.json" >/dev/null
}

flow_beschreibung_setzen() { # <Flow-ID> <Beschreibung>
  kc get "authentication/flows/$1" -r "$realm" | jq --arg text "$2" '.description = $text' > "$arbeit/flow.json"
  kc update "authentication/flows/$1" -r "$realm" -f "$arbeit/flow.json" >/dev/null
}

# ---- 1. Richtlinie «WebAuthn Passwordless» des Realms ------------------------------------------------------------
schritt_richtlinie() {
  jq -n --arg rp "$rp_id" '{
    webAuthnPolicyPasswordlessRpEntityName: "UriNet Archiv",
    webAuthnPolicyPasswordlessRpId: $rp,
    webAuthnPolicyPasswordlessAttestationConveyancePreference: "none",
    webAuthnPolicyPasswordlessResidentKey: "required",
    webAuthnPolicyPasswordlessUserVerificationRequirement: "required",
    webAuthnPolicyPasswordlessPasskeysEnabled: true,
    webAuthnPolicyPasswordlessMediation: "conditional"
  }' > "$arbeit/richtlinie.json"
  kc get "realms/$realm" > "$arbeit/realm.json"
  # Eine andere, schon gesetzte Relying-Party-ID würde eingerichtete Passkeys entwerten: nie stillschweigend ändern.
  if ! jq -e --arg rp "$rp_id" '(.webAuthnPolicyPasswordlessRpId // "") as $ist | $ist == "" or $ist == $rp' \
      "$arbeit/realm.json" >/dev/null; then
    abbruch "Der Realm hat bereits eine andere Relying-Party-ID; bestehende Passkeys würden ungültig. Manuell prüfen."
  fi
  local fehlend
  fehlend=$(jq -r --slurpfile ist "$arbeit/realm.json" \
    'to_entries | map(select($ist[0][.key] != .value) | .key) | join(", ")' "$arbeit/richtlinie.json")
  if [[ -n $fehlend ]]; then
    aendern "WebAuthn-Passwordless-Richtlinie ($fehlend)" \
      kc update "realms/$realm" -f "$arbeit/richtlinie.json"
  fi
}

# ---- 2. Required Actions für Einrichten und Entfernen in der Kontoverwaltung -------------------------------------
aktion_abgleichen() { # <Alias> <Name> <Priorität>
  local alias=$1 name=$2 prio=$3
  kc get authentication/required-actions -r "$realm" > "$arbeit/aktionen.json"
  if ! jq -e --arg a "$alias" 'any(.[]; .alias == $a)' "$arbeit/aktionen.json" >/dev/null; then
    kc get authentication/unregistered-required-actions -r "$realm" > "$arbeit/unregistriert.json"
    if ! jq -e --arg a "$alias" 'any(.[]; .providerId == $a)' "$arbeit/unregistriert.json" >/dev/null; then
      abbruch "Required Action $alias ist weder registriert noch verfügbar (Keycloak-Version prüfen)."
    fi
    aendern "Required Action $alias registrieren" \
      kc create authentication/register-required-action -r "$realm" -s "providerId=$alias" -s "name=$name"
    [[ $modus == pruefen ]] && return 0
    kc get authentication/required-actions -r "$realm" > "$arbeit/aktionen.json"
  fi
  if ! jq -e --arg a "$alias" --argjson p "$prio" \
      'any(.[]; .alias == $a and .enabled == true and .defaultAction == false and .priority == $p)' \
      "$arbeit/aktionen.json" >/dev/null; then
    jq --arg a "$alias" --argjson p "$prio" \
      '.[] | select(.alias == $a) | .enabled = true | .defaultAction = false | .priority = $p' \
      "$arbeit/aktionen.json" > "$arbeit/aktion.json"
    aendern "Required Action $alias (aktiv, nicht Standard, Priorität $prio)" \
      kc update "authentication/required-actions/$alias" -r "$realm" -f "$arbeit/aktion.json"
  fi
}

schritt_aktionen() {
  aktion_abgleichen webauthn-register-passwordless "Webauthn Register Passwordless" 60
  aktion_abgleichen delete_credential "Delete Credential" 70
}

# ---- 3. Kontoverwaltung: Rollen des Clients account für account-console ------------------------------------------
schritt_kontoverwaltung() {
  local konto konsole
  kc get clients -r "$realm" -q clientId=account --fields id,clientId > "$arbeit/konto.json"
  kc get clients -r "$realm" -q clientId=account-console --fields id,clientId > "$arbeit/konsole.json"
  konto=$(jq -er '[.[] | select(.clientId == "account") | .id] | if length == 1 then .[0] else error("mehrdeutig") end' \
    "$arbeit/konto.json") || abbruch "Der Client account fehlt oder ist nicht eindeutig."
  konsole=$(jq -er '[.[] | select(.clientId == "account-console") | .id] | if length == 1 then .[0] else error("mehrdeutig") end' \
    "$arbeit/konsole.json") || abbruch "Der Client account-console fehlt oder ist nicht eindeutig."
  kc get "clients/$konsole/scope-mappings/clients/$konto" -r "$realm" > "$arbeit/zuordnung.json"
  local rolle
  for rolle in manage-account view-profile; do
    if ! jq -e --arg r "$rolle" 'any(.[]; .name == $r)' "$arbeit/zuordnung.json" >/dev/null; then
      kc get "clients/$konto/roles/$rolle" -r "$realm" | jq '[{id: .id, name: .name}]' > "$arbeit/rolle.json" ||
        abbruch "Die Rolle $rolle des Clients account fehlt."
      aendern "Kontoverwaltung: Rolle $rolle für account-console zulassen" \
        kc create "clients/$konsole/scope-mappings/clients/$konto" -r "$realm" -f "$arbeit/rolle.json"
    fi
  done
}

# ---- 4. Flow: Teilflow browser-passkey-otp im Formular-Flow --------------------------------------------------------
kinder_abgleichen() {
  local flow=$flow_passkey
  ausfuehrungen "$flow" "$arbeit/kinder.json"
  local andere bedingung otp
  andere=$(jq '[.[] | select(.providerId != "conditional-credential" and .providerId != "auth-otp-form")] | length' "$arbeit/kinder.json")
  bedingung=$(jq '[.[] | select(.providerId == "conditional-credential")] | length' "$arbeit/kinder.json")
  otp=$(jq '[.[] | select(.providerId == "auth-otp-form")] | length' "$arbeit/kinder.json")
  (( andere == 0 && bedingung <= 1 && otp <= 1 )) ||
    abbruch "Der Teilflow $flow hat unerwartete Ausführungen; manuell prüfen."
  if (( bedingung == 0 )); then
    aendern "Teilflow $flow: Bedingung «Condition - credential» anlegen" \
      kc create "authentication/flows/$flow/executions/execution" -r "$realm" -s provider=conditional-credential
  fi
  if (( otp == 0 )); then
    aendern "Teilflow $flow: OTP-Formular anlegen" \
      kc create "authentication/flows/$flow/executions/execution" -r "$realm" -s provider=auth-otp-form
  fi
  [[ $modus == pruefen ]] && (( bedingung == 0 || otp == 0 )) && return 0
  (( bedingung == 0 || otp == 0 )) && ausfuehrungen "$flow" "$arbeit/kinder.json"

  local anbieter prio id konfig_id
  for anbieter in conditional-credential:10 auth-otp-form:20; do
    prio=${anbieter#*:}
    anbieter=${anbieter%:*}
    id=$(jq -r --arg p "$anbieter" '.[] | select(.providerId == $p) | .id' "$arbeit/kinder.json")
    if ! jq -e --arg p "$anbieter" --argjson prio "$prio" \
        'any(.[]; .providerId == $p and .requirement == "REQUIRED" and .priority == $prio)' "$arbeit/kinder.json" >/dev/null; then
      aendern "Teilflow $flow: $anbieter verlangt (REQUIRED, Priorität $prio)" \
        ausfuehrung_setzen "$flow" "$id" REQUIRED "$prio"
    fi
  done

  id=$(jq -r '.[] | select(.providerId == "conditional-credential") | .id' "$arbeit/kinder.json")
  konfig_id=$(jq -r '.[] | select(.providerId == "conditional-credential") | .authenticationConfig // ""' "$arbeit/kinder.json")
  jq -n --arg alias "$konfig_alias" \
    '{alias: $alias, config: {credentials: "webauthn-passwordless", included: "false"}}' > "$arbeit/konfig.json"
  if [[ -z $konfig_id ]]; then
    aendern "Teilflow $flow: Konfiguration $konfig_alias anlegen" \
      kc create "authentication/executions/$id/config" -r "$realm" -f "$arbeit/konfig.json"
  else
    kc get "authentication/config/$konfig_id" -r "$realm" > "$arbeit/konfig-ist.json"
    if ! jq -e --arg alias "$konfig_alias" \
        '.alias == $alias and .config.credentials == "webauthn-passwordless" and .config.included == "false"' \
        "$arbeit/konfig-ist.json" >/dev/null; then
      jq --arg id "$konfig_id" '. + {id: $id}' "$arbeit/konfig.json" > "$arbeit/konfig-neu.json"
      aendern "Teilflow $flow: Konfiguration $konfig_alias angleichen" \
        kc update "authentication/config/$konfig_id" -r "$realm" -f "$arbeit/konfig-neu.json"
    fi
  fi
}

schritt_flow() {
  local oben="$arbeit/oben.json" form="$arbeit/formulare.json"
  kc get authentication/flows -r "$realm" > "$arbeit/flows.json"
  jq -e --arg f "$flow_oben" '.browserFlow == $f' "$arbeit/realm.json" >/dev/null ||
    abbruch "Der Realm verwendet browserFlow nicht als $flow_oben; dieser Flow wird nicht verändert, manuell prüfen."
  ausfuehrungen "$flow_oben" "$oben" || abbruch "Der Flow $flow_oben ist nicht lesbar."
  jq -e --arg f "$flow_formulare" \
    'any(.[]; .level == 0 and .displayName == $f and .authenticationFlow == true and .requirement == "ALTERNATIVE")' \
    "$oben" >/dev/null ||
    abbruch "Der Flow $flow_oben enthält $flow_formulare nicht als Alternative; manuell prüfen."
  ausfuehrungen "$flow_formulare" "$form" || abbruch "Der Flow $flow_formulare ist nicht lesbar."

  local pw otp unter gesamt
  pw=$(jq '[.[] | select(.level == 0 and .providerId == "auth-username-password-form")] | length' "$form")
  otp=$(jq '[.[] | select(.level == 0 and .providerId == "auth-otp-form")] | length' "$form")
  unter=$(jq --arg f "$flow_passkey" '[.[] | select(.level == 0 and .authenticationFlow == true and .displayName == $f)] | length' "$form")
  gesamt=$(jq '[.[] | select(.level == 0)] | length' "$form")
  (( pw == 1 && otp <= 1 && unter <= 1 && otp + unter >= 1 && gesamt == pw + otp + unter )) ||
    abbruch "Der Flow $flow_formulare hat nicht die erwartete Form (Passwort-Formular plus OTP oder Teilflow $flow_passkey); manuell prüfen."
  jq -e 'any(.[]; .level == 0 and .providerId == "auth-username-password-form" and .requirement == "REQUIRED")' "$form" >/dev/null ||
    abbruch "Das Passwort-Formular in $flow_formulare ist nicht REQUIRED; manuell prüfen."

  if (( unter == 0 )); then
    aendern "Teilflow $flow_passkey in $flow_formulare anlegen" \
      kc create "authentication/flows/$flow_formulare/executions/flow" -r "$realm" \
      -s "alias=$flow_passkey" -s type=basic-flow -s "description=$beschreibung_passkey"
    if [[ $modus == pruefen ]]; then
      aendern "OTP-Formular direkt in $flow_formulare durch den Teilflow ersetzen" true
      return 0
    fi
    ausfuehrungen "$flow_formulare" "$form"
  fi
  kinder_abgleichen

  local pw_id unter_id otp_id
  pw_id=$(jq -r '.[] | select(.level == 0 and .providerId == "auth-username-password-form") | .id' "$form")
  unter_id=$(jq -r --arg f "$flow_passkey" '.[] | select(.level == 0 and .authenticationFlow == true and .displayName == $f) | .id' "$form")
  if ! jq -e 'any(.[]; .level == 0 and .providerId == "auth-username-password-form" and .priority == 10)' "$form" >/dev/null; then
    aendern "Passwort-Formular: Priorität 10" ausfuehrung_setzen "$flow_formulare" "$pw_id" REQUIRED 10
  fi
  # Der Teilflow wird bedingt erst gesetzt, wenn er vollständig ist; erst danach entfällt das direkte OTP-Formular.
  if ! jq -e --arg f "$flow_passkey" \
      'any(.[]; .level == 0 and .displayName == $f and .requirement == "CONDITIONAL" and .priority == 20)' "$form" >/dev/null; then
    aendern "Teilflow $flow_passkey: CONDITIONAL, Priorität 20" ausfuehrung_setzen "$flow_formulare" "$unter_id" CONDITIONAL 20
  fi
  if (( otp == 1 )); then
    otp_id=$(jq -r '.[] | select(.level == 0 and .providerId == "auth-otp-form") | .id' "$form")
    aendern "OTP-Formular direkt in $flow_formulare entfernen (TOTP verlangt künftig der Teilflow $flow_passkey)" \
      kc delete "authentication/executions/$otp_id" -r "$realm"
  fi

  # Beschreibungen wie im Export (nur Anzeige)
  local oben_id form_id passkey_id
  oben_id=$(jq -r --arg a "$flow_oben" '[.[] | select(.alias == $a) | .id][0] // ""' "$arbeit/flows.json")
  ausfuehrungen "$flow_oben" "$oben"
  ausfuehrungen "$flow_formulare" "$form"
  form_id=$(jq -r --arg f "$flow_formulare" '.[] | select(.level == 0 and .displayName == $f) | .flowId' "$oben")
  passkey_id=$(jq -r --arg f "$flow_passkey" '.[] | select(.level == 0 and .displayName == $f) | .flowId // ""' "$form")
  [[ -n $oben_id && -n $form_id && -n $passkey_id ]] || return 0
  if ! jq -e --arg t "$beschreibung_formulare" --arg f "$flow_formulare" 'any(.[]; .displayName == $f and .description == $t)' "$oben" >/dev/null; then
    aendern "Beschreibung von $flow_formulare" flow_beschreibung_setzen "$form_id" "$beschreibung_formulare"
  fi
  if ! jq -e --arg t "$beschreibung_passkey" --arg f "$flow_passkey" 'any(.[]; .displayName == $f and .description == $t)' "$form" >/dev/null; then
    aendern "Beschreibung von $flow_passkey" flow_beschreibung_setzen "$passkey_id" "$beschreibung_passkey"
  fi
  if ! jq -e --arg t "$beschreibung_oben" --arg a "$flow_oben" 'any(.[]; .alias == $a and .description == $t)' "$arbeit/flows.json" >/dev/null; then
    aendern "Beschreibung von $flow_oben" flow_beschreibung_setzen "$oben_id" "$beschreibung_oben"
  fi
}

# ---- 5. Optional: Ereignisse für Passkey-Einrichtung und -Entfernung (Req 10.7) -------------------------------------
schritt_ereignisse() {
  [[ $ereignisse == ja ]] || return 0
  kc get "realms/$realm" > "$arbeit/realm.json"
  jq -e '.eventsEnabled == true' "$arbeit/realm.json" >/dev/null ||
    abbruch "Die Ereignisse des Realms sind ausgeschaltet; die Option --ereignisse ändert das nicht, manuell prüfen."
  # Bei leerer Liste ist die Bedeutung (alle Typen oder keiner) nicht geprüft; Anfügen könnte die Erfassung einschränken.
  jq -e '(.enabledEventTypes // []) | length > 0' "$arbeit/realm.json" >/dev/null ||
    abbruch "Die Liste enabledEventTypes ist leer oder fehlt; das Anfügen könnte die Erfassung auf zwei Typen einschränken, manuell prüfen."
  jq '{enabledEventTypes: ((.enabledEventTypes // []) + ["UPDATE_CREDENTIAL", "REMOVE_CREDENTIAL"] | unique)}' \
    "$arbeit/realm.json" > "$arbeit/ereignisse.json"
  if ! jq -e '(.enabledEventTypes // []) as $ist | (["UPDATE_CREDENTIAL", "REMOVE_CREDENTIAL"] - $ist) | length == 0' \
      "$arbeit/realm.json" >/dev/null; then
    aendern "Ereignisse UPDATE_CREDENTIAL und REMOVE_CREDENTIAL aktivieren" \
      kc update "realms/$realm" -f "$arbeit/ereignisse.json"
  fi
}

lauf() {
  schritt_richtlinie
  schritt_aktionen
  schritt_kontoverwaltung
  schritt_flow
  schritt_ereignisse
}

if ! "$kcadm" get "realms/$realm" --fields realm >/dev/null 2>"$arbeit/fehler"; then
  abbruch "Der Realm $realm ist nicht lesbar; Anmeldung (kcadm config credentials, Realm master) und KC_REALM prüfen: $(tail -n 1 "$arbeit/fehler")"
fi

# Beim Anwenden zuerst ein Probelauf ohne Änderung: Ein unerwarteter Zustand (Abbruch, Exit 1) im Flow oder bei den
# Ereignissen verändert so gar nichts, statt erst nach den ersten Änderungen aufzufallen.
if [[ $modus == anwenden ]]; then
  modus=pruefen
  lauf > /dev/null
  modus=anwenden
  abweichungen=0
fi
lauf
if [[ $modus == pruefen ]]; then
  if (( abweichungen == 0 )); then
    echo "Passkey-Realm: nichts zu tun."
    exit 0
  fi
  echo "Passkey-Realm: $abweichungen Abweichung(en); Einrichtung ohne --pruefen ausführen."
  exit 10
fi

# Nachprüfung im selben Lauf: Nach der Einrichtung darf keine Abweichung bleiben.
geaendert=$abweichungen
abweichungen=0
modus=pruefen
lauf
if (( abweichungen != 0 )); then
  abbruch "Nach der Einrichtung bleiben $abweichungen Abweichung(en); Ausgabe oben prüfen."
fi
if (( geaendert == 0 )); then
  echo "Passkey-Realm: nichts zu tun."
else
  echo "Passkey-Realm eingerichtet ($geaendert Änderung(en)) und nachgeprüft."
fi
if [[ $ereignisse == nein ]]; then
  echo "Hinweis: Ereignisse für Passkeys (Req 10.7) bleiben unverändert; Option --ereignisse nur nach Entscheid."
fi
