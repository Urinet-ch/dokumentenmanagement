#!/usr/bin/env bash
# Listet Keycloak-Benutzerereignisse des Realms datensparsam (rein lesend), z. B. die Passkey-Probe aus Aufgabe 24.3
# (Req 10.7): UPDATE_CREDENTIAL und REMOVE_CREDENTIAL. Die Admin-Konsole ist über Traefik nicht öffentlich; dies ist der Ersatz.
#
#   ereignisse-auflisten-vorlage.sh [--typ <TYP>]… [--max <n>] [--seit <Tage>]
#
#   --typ <TYP>    Ereignistyp, mehrfach oder kommagetrennt (Standard UPDATE_CREDENTIAL,REMOVE_CREDENTIAL); nur A-Z, 0-9, Unterstrich
#   --max <n>      höchstens so viele Ereignisse ausgeben (Standard 20, höchstens 200)
#   --seit <Tage>  nur Ereignisse der letzten Tage (Standard 1, höchstens 365)
#
# Ausgabe: Zeit (UTC), Typ, Benutzerkennung (userId, UUID), Client; nie Benutzername, E-Mail, IP-Adresse oder Details (Req 9).
# Exit 0 = mindestens ein Ereignis, 3 = keines gefunden, 1 = Fehler (auch: Ereignisse im Realm aus oder Typ nicht aktiviert), 2 = Aufruf.
# Umgebung: KC_REALM (urinet-archiv), KCADM (/opt/keycloak/bin/kcadm.sh). kcadm muss für den Realm master angemeldet sein
# (vorlage-ausfuehren.sh); benötigt wird jq.
set -euo pipefail

realm=${KC_REALM:-urinet-archiv}
kcadm=${KCADM:-/opt/keycloak/bin/kcadm.sh}
typen=()
hoechstens=20
tage=1

hilfe() {
  echo "Aufruf: ereignisse-auflisten-vorlage.sh [--typ <TYP>]… [--max <n>] [--seit <Tage>]" >&2
}
aufruffehler() {
  echo "Aufruf: $*" >&2
  hilfe
  exit 2
}
zahl() { # <Name> <Wert> <Minimum> <Maximum>
  [[ $2 =~ ^[0-9]{1,4}$ ]] && ((10#$2 >= $3 && 10#$2 <= $4)) || aufruffehler "$1 verlangt eine ganze Zahl von $3 bis $4."
}
typ_aufnehmen() { # <Wert, evtl. kommagetrennt>
  local rest=$1 typ
  [[ -n $rest ]] || aufruffehler "--typ verlangt einen Wert."
  while [[ -n $rest ]]; do
    typ=${rest%%,*}
    if [[ $rest == *,* ]]; then rest=${rest#*,}; else rest=""; fi
    [[ $typ =~ ^[A-Z][A-Z0-9_]{0,63}$ ]] || aufruffehler "Ereignistyp ungültig (nur Grossbuchstaben, Ziffern, Unterstrich)."
    [[ " ${typen[*]-} " == *" $typ "* ]] || typen+=("$typ")
  done
}
while (($# > 0)); do
  case $1 in
    --typ) (($# >= 2)) || aufruffehler "--typ verlangt einen Wert."; typ_aufnehmen "$2"; shift 2 ;;
    --max) (($# >= 2)) || aufruffehler "--max verlangt einen Wert."; zahl --max "$2" 1 200; hoechstens=$((10#$2)); shift 2 ;;
    --seit) (($# >= 2)) || aufruffehler "--seit verlangt einen Wert."; zahl --seit "$2" 1 365; tage=$((10#$2)); shift 2 ;;
    -h | --hilfe) hilfe; exit 0 ;;
    *) aufruffehler "Unbekannte Option." ;;
  esac
done
((${#typen[@]} > 0)) || typ_aufnehmen "UPDATE_CREDENTIAL,REMOVE_CREDENTIAL"
((${#typen[@]} <= 20)) || aufruffehler "Höchstens 20 Ereignistypen."

abbruch() {
  echo "Abbruch: $*" >&2
  exit 1
}

command -v jq >/dev/null || abbruch "jq fehlt; siehe deploy/infomaniak/keycloak/README.md (Abschnitt «Passkey-Realm nachrüsten»)."
[[ $realm =~ ^[A-Za-z0-9_-]+$ ]] || abbruch "KC_REALM enthält unzulässige Zeichen."

umask 077
arbeit=$(mktemp -d "${TMPDIR:-/tmp}/urinet-ereignisse.XXXXXX")
trap 'rm -rf -- "$arbeit"' EXIT

kc() {
  if ! "$kcadm" "$@" 2>"$arbeit/fehler"; then
    echo "Keycloak-Aufruf $1 $2 fehlgeschlagen: $(tail -n 1 "$arbeit/fehler")" >&2
    return 1
  fi
}

# ---- 1. Sind die Ereignisse aktiv und die Typen aktiviert? ---------------------------------------------------------
kc get "realms/$realm" --fields eventsEnabled,enabledEventTypes > "$arbeit/realm.json"
jq -e '.eventsEnabled == true' "$arbeit/realm.json" >/dev/null ||
  abbruch "Die Ereignisse des Realms sind ausgeschaltet (eventsEnabled false); es gibt nichts zu listen. Aktivieren: passkey-einrichten-vorlage.sh --ereignisse (Entscheid Fabian) oder manuell."
fehlend=$(jq -r --argjson typen "$(printf '%s\n' "${typen[@]}" | jq -R . | jq -s -c .)" \
  '(.enabledEventTypes // []) as $aktiv | $typen | map(select(. as $t | $aktiv | index($t) | not)) | join(", ")' "$arbeit/realm.json")
if [[ -n $fehlend ]]; then
  abbruch "Im Realm nicht aktiviert (enabledEventTypes): $fehlend. Mit passkey-einrichten-vorlage.sh --ereignisse nachrüsten; frühere Vorgänge fehlen dann."
fi

# ---- 2. Ereignisse lesen (je Typ eine Abfrage, nur get) ------------------------------------------------------------
jetzt=$(date -u +%s)
von=$((jetzt - tage * 86400))
if ! datum_von=$(date -u -d "@$von" +%F 2>/dev/null); then
  datum_von=$(date -u -r "$von" +%F)
fi
: > "$arbeit/alle.jsonl"
for typ in "${typen[@]}"; do
  kc get events -r "$realm" -q "type=$typ" -q "dateFrom=$datum_von" -q "max=$hoechstens" \
    --fields time,type,userId,clientId > "$arbeit/roh.json"
  # Nur die vier Ausgabefelder behalten; Typ und Zeitfenster hier noch einmal erzwingen.
  jq -c --arg typ "$typ" --argjson von "$((von * 1000))" \
    '.[] | select(.type == $typ and (.time // 0) >= $von) | {time, type, userId, clientId}' "$arbeit/roh.json" >> "$arbeit/alle.jsonl"
done

# ---- 3. Ausgabe: Tabelle (neueste zuerst) und Summen -----------------------------------------------------------------
jq -s --argjson max "$hoechstens" 'sort_by(-.time) | .[:$max]' "$arbeit/alle.jsonl" > "$arbeit/auswahl.json"
anzahl=$(jq 'length' "$arbeit/auswahl.json")
if ((anzahl == 0)); then
  echo "Keine Ereignisse gefunden (Typen: ${typen[*]}; letzte $tage Tag(e))."
  exit 3
fi
# Tabelle nur mit jq (im Keycloak-Image fehlen awk und column); Zellen auf unbedenkliche Zeichen beschränkt.
jq -r 'def zelle: if . == null or . == "" then "-" else (tostring | gsub("[^A-Za-z0-9_.:-]"; "?")) end;
  def fuellen($breite): . + (" " * ($breite - length));
  ([["Zeit (UTC)", "Typ", "Benutzerkennung", "Client"]]
    + map([(.time / 1000 | floor | todate), (.type | zelle), (.userId | zelle), (.clientId | zelle)])) as $zeilen
  | ($zeilen | transpose | map(map(length) | max)) as $breiten
  | $zeilen[] | . as $zeile | [range(0; 4)] | map(. as $i | $zeile[$i] | fuellen($breiten[$i])) | join("  ") | sub(" +$"; "")' "$arbeit/auswahl.json"
for typ in "${typen[@]}"; do
  echo "Summe $typ: $(jq --arg t "$typ" '[.[] | select(.type == $t)] | length' "$arbeit/auswahl.json")"
done
if ((anzahl >= hoechstens)); then
  echo "Hinweis: Höchstzahl $hoechstens erreicht; es kann weitere Ereignisse geben (--max erhöhen oder --seit verkleinern)."
fi
