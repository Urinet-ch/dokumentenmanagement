#!/usr/bin/env bash
# Listet Testquellen für Surefire (-Dsurefire.includesFile bzw. -Dsurefire.excludesFile) nach Gruppen-Tag der Klasse (ci.yml):
#   testklassen-der-gruppe.sh <tag>[,<tag>...]   die Klassen mit einem dieser @Tag
#   testklassen-der-gruppe.sh --alle     alle Klassen mit einer Gruppe (start-N oder szenarien-N)
# Warum nicht nur -Dgroups: JUnit filtert Tags erst nach der Suche; Quarkus baut dabei für jedes Testprofil jeder gefundenen
# Klasse die Anwendung (je ~6 s), auch für Klassen, die der Job gar nicht ausführt (~2 min je Job). Mit der Liste sucht
# Surefire nur die Klassen der Gruppe bzw. alle ausser den Gruppen. Das Tag steht allein in der Zeile und am Zeilenanfang
# (Klassenannotation); eingerückte Tags (Gegenprobe in StartGruppenTest) und Kommentare zählen nicht. Ein Fehler hier fällt
# im Job «testzahl» auf (Klasse in keinem oder mehreren Jobs), nicht erst im Betrieb.
set -euo pipefail
quellen="$(cd "$(dirname "$0")/../../backend/src/test/java" && pwd)"
if [ "${1:-}" = "--alle" ]; then
  muster='^@Tag\("(start|szenarien)-[0-9]+"\)$'
else
  tags="${1:?Gruppe fehlt}"
  muster="^@Tag\\(\"(${tags//,/|})\"\\)\$"
fi
ergebnis=$(cd "$quellen" && grep -rlE --include='*.java' "$muster" . | sed 's|^\./||' | sort)
[ -n "$ergebnis" ] || { echo "Keine Testklasse mit ${1}" >&2; exit 1; }
printf '**/%s\n' $ergebnis
