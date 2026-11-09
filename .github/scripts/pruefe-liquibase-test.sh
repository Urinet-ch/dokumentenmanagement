#!/usr/bin/env bash
# Selbsttest für pruefe-liquibase.sh in einem Wegwerf-Repository: die Prüfung darf nie durchlassen, ohne geprüft zu haben.
set -euo pipefail

skript="$(cd "$(dirname "$0")" && pwd)/pruefe-liquibase.sh"
repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q -b main
git config user.email test@example.invalid
git config user.name Selbsttest
db=backend/src/main/resources/db/mandant
mkdir -p "$db"
printf '<databaseChangeLog>\n    <include file="001-a.xml" relativeToChangelogFile="true"/>\n</databaseChangeLog>\n' > "$db/changelog.xml"
printf '<databaseChangeLog>\n    <changeSet id="001" author="t"/>\n</databaseChangeLog>\n' > "$db/001-a.xml"
git add -A && git commit -q -m basis
basis="$(git rev-parse HEAD)"
fehler=0

erwarte() {
  local soll="$1" fall="$2"
  shift 2
  local ist=0
  "$skript" "$@" >/dev/null 2>&1 || ist=$?
  if [[ "$ist" -eq "$soll" ]]; then
    echo "ok   $fall (Exit $ist)"
  else
    echo "FEHL $fall (Exit $ist, erwartet $soll)"
    fehler=1
  fi
}

aendern() {
  git reset -q --hard "$basis"
  "$@"
  git add -A && git commit -q -m fall
}

erwarte 2 "ungültige Basis" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef
erwarte 2 "Null-SHA ohne Vorgänger" 0000000000000000000000000000000000000000
erwarte 2 "leere Basis ohne Vorgänger" ""

aendern sh -c "printf '<databaseChangeLog/>\n' > $db/002-b.xml && sed -i.bak 's#</databaseChangeLog>#    <include file=\"002-b.xml\" relativeToChangelogFile=\"true\"/>\n    <!-- Kommentar -->\n</databaseChangeLog>#' $db/changelog.xml && rm $db/changelog.xml.bak"
erwarte 0 "neues Changeset mit <include>" "$basis"
erwarte 0 "Null-SHA prüft gegen HEAD^" 0000000000000000000000000000000000000000

aendern sh -c "sed -i.bak 's/001/001b/' $db/001-a.xml && rm $db/001-a.xml.bak"
erwarte 1 "bestehendes Changeset geändert" "$basis"

aendern git rm -q "$db/001-a.xml"
erwarte 1 "bestehendes Changeset gelöscht" "$basis"

aendern sh -c "sed -i.bak 's#</databaseChangeLog>#    <changeSet id=\"002\" author=\"t\"/>\n</databaseChangeLog>#' $db/changelog.xml && rm $db/changelog.xml.bak"
erwarte 1 "Changeset im Master-Changelog" "$basis"

aendern sh -c "sed -i.bak 's#001-a.xml#001-x.xml#' $db/changelog.xml && rm $db/changelog.xml.bak"
erwarte 1 "Master-Changelog geändert statt ergänzt" "$basis"

exit $fehler
