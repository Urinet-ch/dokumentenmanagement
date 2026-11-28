#!/usr/bin/env bash
# Zieht die Einschränkung der Kontoverwaltung aus Aufgabe 26.2 (Spec 06, Req 11.5; Quelle: deploy/keycloak/realm-export.json)
# auf einem bestehenden Realm nach; der Realm-Import wirkt nur beim ersten Start. Wiederholbar: Was schon stimmt, bleibt.
#
#   konto-einschraenken-vorlage.sh [--pruefen]
#
#   --pruefen  nur lesen und Abweichungen melden; Exit 0 = nichts zu tun, Exit 10 = Änderungen nötig
#
# Ändert im Benutzerprofil nur die Bearbeitungsrechte von Vor- und Nachname (nur noch «admin») und schaltet die
# Pflichtaktion «idp_link» ab (Google-Konto in der Kontoverwaltung verknüpfen; der Export kennt sie nicht, ein neu
# angelegter Realm hat sie aktiv). Benutzername, E-Mail und Mandant müssen schon nur für «admin» änderbar sein; sonst
# bricht die Vorlage ab, statt mehr zu ändern.
# Umgebung: KC_REALM (urinet-archiv), KCADM (/opt/keycloak/bin/kcadm.sh). kcadm muss für den Realm master angemeldet
# sein; benötigt wird jq. Exit 1 = Fehler oder unerwarteter Zustand, 2 = Aufruf.
set -euo pipefail

realm=${KC_REALM:-urinet-archiv}
kcadm=${KCADM:-/opt/keycloak/bin/kcadm.sh}
modus=anwenden

hilfe() {
  echo "Aufruf: konto-einschraenken-vorlage.sh [--pruefen]" >&2
}
for argument in "$@"; do
  case $argument in
    --pruefen) modus=pruefen ;;
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

umask 077
arbeit=$(mktemp -d "${TMPDIR:-/tmp}/urinet-konto.XXXXXX")
trap 'rm -rf -- "$arbeit"' EXIT

kc() {
  if ! "$kcadm" "$@" 2>"$arbeit/fehler"; then
    echo "Keycloak-Aufruf $1 $2 fehlgeschlagen: $(tail -n 1 "$arbeit/fehler")" >&2
    return 1
  fi
}

kc get users/profile -r "$realm" > "$arbeit/profil.json"
# Erwartet wird das Profil des Exports: diese Attribute je genau einmal, Benutzername, E-Mail und Mandant nur für admin
if ! jq -e '
  . as $p | def attr($n): [$p.attributes[] | select(.name == $n)];
  all("username", "email", "firstName", "lastName", "mandant"; attr(.) | length == 1) and
  all("username", "email", "mandant"; attr(.)[0].permissions.edit == ["admin"])
' "$arbeit/profil.json" >/dev/null; then
  abbruch "Das Benutzerprofil weicht vom Export ab (Benutzername, E-Mail oder Mandant für Benutzer änderbar oder Attribut fehlt); manuell prüfen."
fi

offen=$(jq -r '[.attributes[] | select((.name == "firstName" or .name == "lastName") and .permissions.edit != ["admin"]) | .name] | join(" ")' \
  "$arbeit/profil.json")
kc get authentication/required-actions -r "$realm" > "$arbeit/aktionen.json"
if jq -e 'any(.[]; .alias == "idp_link" and .enabled)' "$arbeit/aktionen.json" >/dev/null; then
  offen="${offen:+$offen }idp_link"
fi
if [[ -z $offen ]]; then
  echo "Kontoverwaltung: nichts zu tun (Benutzername, Name, E-Mail und Mandant nur für admin änderbar, idp_link aus)."
  exit 0
fi
if [[ $modus == pruefen ]]; then
  echo "Kontoverwaltung: noch offen: $offen"
  exit 10
fi

if [[ $offen == *Name* ]]; then
  jq '.attributes |= map(if .name == "firstName" or .name == "lastName" then .permissions.edit = ["admin"] else . end)' \
    "$arbeit/profil.json" > "$arbeit/neu.json"
  kc update users/profile -r "$realm" -f "$arbeit/neu.json" >/dev/null
fi
if [[ $offen == *idp_link* ]]; then
  jq '.[] | select(.alias == "idp_link") | .enabled = false' "$arbeit/aktionen.json" > "$arbeit/idp-link.json"
  kc update authentication/required-actions/idp_link -r "$realm" -f "$arbeit/idp-link.json" >/dev/null
fi

kc get users/profile -r "$realm" > "$arbeit/kontrolle.json"
jq -e 'all(.attributes[] | select(.name == "firstName" or .name == "lastName"); .permissions.edit == ["admin"])' \
  "$arbeit/kontrolle.json" >/dev/null || abbruch "Nachprüfung fehlgeschlagen: Vor- oder Nachname weiterhin für Benutzer änderbar."
kc get authentication/required-actions -r "$realm" > "$arbeit/aktionen.json"
jq -e 'all(.[]; .alias != "idp_link" or (.enabled | not))' "$arbeit/aktionen.json" >/dev/null \
  || abbruch "Nachprüfung fehlgeschlagen: idp_link weiterhin aktiv."
echo "Kontoverwaltung eingeschränkt: Vor- und Nachname nur noch für admin änderbar, idp_link aus."
