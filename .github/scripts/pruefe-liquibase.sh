#!/usr/bin/env bash
# Prüft tech.md Regel 11: bestehende Liquibase-Changesets bleiben unverändert. Neue Dateien sind erlaubt; Master-Changelogs
# (changelog.xml) enthalten keine Changesets und bekommen nur neue <include>/<includeAll>- oder Kommentarzeilen.
# Ausgabe nur Dateipfade (Req 16.7). Exit 0 = geprüft und in Ordnung, 1 = Verstoss, 2 = nicht prüfbar.
set -euo pipefail

basis="${1:-}"
pfad="backend/src/main/resources/db"

if [[ -z "$basis" || "$basis" =~ ^0+$ ]]; then
  basis="HEAD^"
  echo "Keine Basis übergeben (z. B. erster Push); geprüft wird gegen HEAD^."
fi
if ! git rev-parse --verify --quiet "${basis}^{commit}" >/dev/null; then
  echo "Basis ist kein Commit: $basis"
  exit 2
fi

aenderungen="$(git diff --name-status "$basis"...HEAD -- "$pfad")"
fehler=0

set +e
git grep -q -e '<changeSet' HEAD -- ":(glob)$pfad/**/changelog.xml"
treffer=$?
set -e
if [[ $treffer -eq 0 ]]; then
  echo "Changeset direkt im Master-Changelog:"
  git grep -l -e '<changeSet' HEAD -- ":(glob)$pfad/**/changelog.xml" | sed 's/^HEAD://'
  fehler=1
elif [[ $treffer -ne 1 ]]; then
  echo "Master-Changelogs nicht lesbar"
  exit 2
fi

while IFS=$'\t' read -r status datei _; do
  [[ -z "${status:-}" ]] && continue
  case "$status" in
    A) ;;
    M)
      if [[ "$(basename "$datei")" != "changelog.xml" ]]; then
        echo "Bestehendes Changeset geändert: $datei"
        fehler=1
        continue
      fi
      diff="$(git diff -U0 "$basis"...HEAD -- "$datei")"
      if grep -E '^-' <<<"$diff" | grep -vE '^---' >/dev/null; then
        echo "Master-Changelog geändert statt ergänzt: $datei"
        fehler=1
      fi
      if grep -E '^\+' <<<"$diff" | grep -vE '^\+\+\+' | sed 's/^+//' \
          | grep -vE '^[[:space:]]*(<include(All)?[[:space:]][^>]*/>|<!--.*-->)?[[:space:]]*$' >/dev/null; then
        echo "Master-Changelog: nur <include>, <includeAll> oder Kommentare erlaubt: $datei"
        fehler=1
      fi
      ;;
    *)
      echo "Bestehendes Changeset entfernt oder umbenannt ($status): $datei"
      fehler=1
      ;;
  esac
done <<<"$aenderungen"

if [[ $fehler -eq 0 ]]; then
  echo "Liquibase: bestehende Changesets unverändert."
fi
exit $fehler
