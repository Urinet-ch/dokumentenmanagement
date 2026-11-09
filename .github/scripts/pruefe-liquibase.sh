#!/usr/bin/env bash
# Prüft tech.md Regel 11: bestehende Liquibase-Changesets bleiben unverändert. Neue Dateien sind erlaubt; in den
# Master-Changelogs (changelog.xml) dürfen nur Zeilen dazukommen. Ausgabe nur Dateipfade (Req 16.7).
set -euo pipefail

basis="${1:?Basis-Commit fehlt, z. B. origin/main}"
pfad="backend/src/main/resources/db"
fehler=0

while IFS=$'\t' read -r status datei rest; do
  [[ -z "${status:-}" ]] && continue
  case "$status" in
    A) ;;
    M)
      if [[ "$(basename "$datei")" == "changelog.xml" ]]; then
        if git diff -U0 "$basis"...HEAD -- "$datei" | grep -E '^-[^-]' >/dev/null; then
          echo "Master-Changelog geändert statt ergänzt: $datei"
          fehler=1
        fi
      else
        echo "Bestehendes Changeset geändert: $datei"
        fehler=1
      fi
      ;;
    *)
      echo "Bestehendes Changeset entfernt oder umbenannt ($status): $datei"
      fehler=1
      ;;
  esac
done < <(git diff --name-status "$basis"...HEAD -- "$pfad")

if [[ $fehler -eq 0 ]]; then
  echo "Liquibase: bestehende Changesets unverändert."
fi
exit $fehler
