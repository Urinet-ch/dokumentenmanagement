#!/usr/bin/env bash
# Selbsttest für pruefe-vertrag.sh in einem Wegwerf-Repository: jeder Fall aus design.md «Nur additiv» rot und grün,
# und die Prüfung darf nie durchlassen, ohne geprüft zu haben.
set -euo pipefail

skript="$(cd "$(dirname "$0")" && pwd)/pruefe-vertrag.sh"
repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q -b main
git config user.email test@example.invalid
git config user.name Selbsttest
v=docs/schnittstelle
datei="$v/public-v1.yaml"
mkdir -p "$v"
echo "Wegwerf" > README.md
git add -A && git commit -q -m leer
leer="$(git rev-parse HEAD)"

cat > "$datei" <<'EOF'
openapi: 3.1.0
info:
  title: Selbsttest
  version: "1"
paths:
  /public/v1/dokumente:
    get:
      parameters:
        - name: reihe
          in: query
          schema:
            type: string
      responses:
        "200":
          description: Liste
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Liste"
        "401":
          $ref: "#/components/responses/NichtAngemeldet"
components:
  schemas:
    Liste:
      type: object
      required: [eintraege]
      properties:
        eintraege:
          type: array
          items:
            $ref: "#/components/schemas/Eintrag"
    Eintrag:
      type: object
      properties:
        id:
          type: string
        stufe:
          type: string
          enum: [oeffentlich, mitglieder]
        art:
          type: string
          x-erweiterbar: true
          enum: [NEU, SICHTBAR]
  responses:
    NichtAngemeldet:
      description: nicht angemeldet
EOF
git add -A && git commit -q -m basis
basis="$(git rev-parse HEAD)"
fehler=0

erwarte() {
  local soll="$1" fall="$2"
  shift 2
  local ist=0 ausgabe
  ausgabe="$("$skript" "$@" 2>&1)" || ist=$?
  if [[ "$ist" -eq "$soll" && ( -z "${MELDUNG:-}" || "$ausgabe" == *"$MELDUNG"* ) ]]; then
    echo "ok   $fall (Exit $ist)"
  else
    echo "FEHL $fall (Exit $ist, erwartet $soll${MELDUNG:+ mit «${MELDUNG}»})"
    echo "$ausgabe" | sed 's/^/     /'
    fehler=1
  fi
}

# ersetzt im Vertrag (Ruby, damit GNU- und BSD-sed keine Rolle spielen) und committet den Fall auf der Basis
aendern() {
  git reset -q --hard "$basis"
  ruby -e 'p = ARGV[0]; s = File.read(p); a = ARGV[1].gsub("\\n", "\n"); b = ARGV[2].gsub("\\n", "\n")
           abort("fehlt: #{a}") unless s.include?(a); File.write(p, s.sub(a, b))' "$datei" "$1" "$2"
  git add -A && git commit -q -m fall
}

erwarte 2 "ungültige Basis" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef
erwarte 2 "Null-SHA" 0000000000000000000000000000000000000000
erwarte 2 "leere Basis" ""
erwarte 0 "unverändert" "$basis"
MELDUNG="Erste Fassung" erwarte 0 "Datei fehlt auf der Basis (erste Fassung)" "$leer"

git reset -q --hard "$basis"
git rm -q "$datei" && git commit -q -m geloescht
MELDUNG="Vertrag gelöscht" erwarte 1 "Datei gelöscht" "$basis"
git reset -q --hard "$leer"
mkdir -p "$v"
printf 'openapi: 3.1.0\ninfo: [kaputt\n' > "$datei"
git add -A && git commit -q -m kaputt
erwarte 2 "erste Fassung kein YAML" "$leer"

# additiv: neuer Pfad, neuer optionaler Parameter, neues Antwortfeld, neuer Wert bei x-erweiterbar, neuer Statuscode
aendern '  responses:\n    NichtAngemeldet:' '  responses:\n    NichtGefunden:\n      description: fehlt\n    NichtAngemeldet:'
erwarte 0 "neue Komponente" "$basis"
aendern '      responses:\n        "200":' '        - name: jahr\n          in: query\n          schema:\n            type: integer\n      responses:\n        "200":'
erwarte 0 "neuer optionaler Parameter" "$basis"
aendern '        id:\n          type: string' '        id:\n          type: string\n        titel:\n          type: string'
erwarte 0 "neues Antwortfeld" "$basis"
aendern 'enum: [NEU, SICHTBAR]' 'enum: [NEU, SICHTBAR, VERBORGEN]'
erwarte 0 "neuer Wert bei x-erweiterbar: true" "$basis"
aendern '        "401":' '        "429":\n          description: zu viele\n        "401":'
erwarte 0 "neuer Statuscode" "$basis"
aendern 'paths:' 'paths:\n  /public/v1/filter:\n    get:\n      responses:\n        "200":\n          description: Filter'
erwarte 0 "neuer Pfad" "$basis"
aendern '      required: [eintraege]' '      required: [eintraege, seite]\n'
erwarte 0 "neues Pflichtfeld nur in einer Antwort" "$basis"

# nicht additiv
aendern '  /public/v1/dokumente:' '  /public/v1/eintraege:'
MELDUNG="Pfad entfernt oder umbenannt" erwarte 1 "Pfad umbenannt" "$basis"
aendern '    get:\n      parameters:' '    post:\n      parameters:'
MELDUNG="Operation entfernt" erwarte 1 "Operation entfernt" "$basis"
aendern '        id:\n          type: string\n' ''
MELDUNG="Feld entfernt oder umbenannt" erwarte 1 "Feld entfernt" "$basis"
aendern '        id:\n' '        kennung:\n'
MELDUNG="Feld entfernt oder umbenannt" erwarte 1 "Feld umbenannt" "$basis"
aendern '        - name: reihe' '        - name: serie'
MELDUNG="Parameter entfernt oder umbenannt" erwarte 1 "Parameter umbenannt" "$basis"
aendern '        id:\n          type: string' '        id:\n          type: integer'
MELDUNG="Typ geändert" erwarte 1 "Typ geändert" "$basis"
aendern '          in: query\n          schema:\n            type: string' '          in: query\n          required: true\n          schema:\n            type: string'
MELDUNG="Parameter wird Pflicht" erwarte 1 "optionaler Parameter wird Pflicht" "$basis"
aendern '      responses:\n        "200":' '        - name: jahr\n          in: query\n          required: true\n          schema:\n            type: integer\n      responses:\n        "200":'
MELDUNG="neuer Pflichtparameter" erwarte 1 "neuer Pflichtparameter" "$basis"
aendern '        "401":\n          $ref: "#/components/responses/NichtAngemeldet"\n' ''
MELDUNG="Statuscode entfernt" erwarte 1 "Statuscode entfernt" "$basis"
aendern 'enum: [oeffentlich, mitglieder]' 'enum: [oeffentlich, mitglieder, vorstand]'
MELDUNG="ohne x-erweiterbar" erwarte 1 "neuer Wert ohne x-erweiterbar" "$basis"
aendern 'enum: [oeffentlich, mitglieder]' 'x-erweiterbar: true\n          enum: [oeffentlich, mitglieder, vorstand]'
MELDUNG="nachträglich erweiterbar" erwarte 1 "x-erweiterbar zusammen mit neuem Wert nachgetragen" "$basis"
aendern 'enum: [NEU, SICHTBAR]' 'enum: [NEU]'
MELDUNG="Wert SICHTBAR entfernt" erwarte 1 "Wert entfernt" "$basis"
aendern '      required: [eintraege]\n' ''
MELDUNG="nicht mehr Pflicht" erwarte 1 "Antwortfeld nicht mehr Pflicht" "$basis"
aendern '    NichtAngemeldet:\n      description: nicht angemeldet\n' '    Abgelehnt:\n      description: nicht angemeldet\n'
MELDUNG="Komponente entfernt oder umbenannt" erwarte 1 "Komponente umbenannt" "$basis"
aendern 'info:' 'info: [kaputt'
erwarte 2 "kein YAML" "$basis"

git reset -q --hard "$basis"
git checkout -q --orphan fremd
git rm -q -r --cached . && git commit -q --allow-empty -m fremd
erwarte 2 "Basis ohne gemeinsame Vorgängerin" "$basis"
git checkout -q -f main

exit $fehler
