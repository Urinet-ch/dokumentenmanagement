#!/usr/bin/env bash
# Prüft Spec 03, Req 11.3: Der Vertrag der Webseiten-Schnittstelle (docs/schnittstelle/public-v<n>.yaml) ändert sich
# innerhalb einer Hauptversion nur additiv gegenüber der Basis (design.md «Nur additiv»). Verglichen wird der Stand in
# HEAD mit dem Stand der gemeinsamen Vorgängerin von Basis und HEAD (wie pruefe-liquibase.sh); den Vergleich macht
# pruefe-vertrag.rb. Eine Datei, die es auf der Basis nicht gibt, ist die erste Fassung ihrer Hauptversion. Nie
# durchlassen, ohne geprüft zu haben: fehlende oder unbekannte Basis, fehlendes ruby oder unlesbare Datei → Exit 2,
# gelöschte Datei → Exit 1. Exit 0 = geprüft und in Ordnung, 1 = Verstoss, 2 = nicht prüfbar.
set -euo pipefail

basis="${1:-}"
verzeichnis="docs/schnittstelle"
muster='^docs/schnittstelle/public-v[1-9][0-9]*\.yaml$'
vergleich="$(cd "$(dirname "$0")" && pwd)/pruefe-vertrag.rb"

if [[ -z "$basis" || "$basis" =~ ^0+$ ]]; then
  echo "Keine Basis übergeben (z. B. erster Push); ohne Basis wird nicht geprüft."
  exit 2
fi
if ! git rev-parse --verify --quiet "${basis}^{commit}" >/dev/null; then
  echo "Basis ist kein Commit: $basis"
  exit 2
fi
if ! stand="$(git merge-base "$basis" HEAD)"; then
  echo "Basis und HEAD haben keine gemeinsame Vorgängerin: $basis"
  exit 2
fi
if ! command -v ruby >/dev/null; then
  echo "ruby fehlt; der Vertrag kann nicht geprüft werden."
  exit 2
fi

liste() {
  local commit="$1" alle
  if ! alle="$(git ls-tree -r --name-only "$commit" -- "$verzeichnis")"; then
    echo "Dateien unter $verzeichnis nicht lesbar in $commit" >&2
    exit 2
  fi
  grep -E "$muster" <<<"$alle" || true
}

vorher="$(liste "$stand")"
nachher="$(liste HEAD)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fehler=0
geprueft=0

while IFS= read -r datei; do
  [[ -z "$datei" ]] && continue
  geprueft=$((geprueft + 1))
  if ! grep -qxF "$datei" <<<"$nachher"; then
    echo "Vertrag gelöscht: $datei"
    fehler=1
    continue
  fi
  git show "HEAD:$datei" >"$tmp/kopf.yaml"
  if ! grep -qxF "$datei" <<<"$vorher"; then
    ergebnis=0
    ruby "$vergleich" --lesen "$tmp/kopf.yaml" || ergebnis=$?
    [[ $ergebnis -ne 0 ]] && exit 2
    echo "Erste Fassung: $datei"
    continue
  fi
  git show "$stand:$datei" >"$tmp/basis.yaml"
  ergebnis=0
  ruby "$vergleich" "$tmp/basis.yaml" "$tmp/kopf.yaml" || ergebnis=$?
  case $ergebnis in
    0) ;;
    1) echo "Nicht additiv: $datei"; fehler=1 ;;
    *) exit 2 ;;
  esac
done < <(printf '%s\n%s\n' "$vorher" "$nachher" | sort -u)

if [[ $fehler -eq 0 ]]; then
  echo "Vertrag: $geprueft Datei(en) geprüft, nur additiv."
fi
exit $fehler
