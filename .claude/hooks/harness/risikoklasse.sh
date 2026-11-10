#!/usr/bin/env bash
# Gibt die Risikoklasse des Branch-Diffs aus (hoch|mittel|niedrig) und begründet sie auf
# stderr. Regeln: skills/_shared/risikoklassen.md. Das Skript entscheidet, nicht das
# Modell – ein Agent soll seine eigene Änderung nicht einstufen.
#   risikoklasse.sh [basis]   (Standard: origin/main)
CLAUDE_PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
source "$(dirname "$0")/_gemeinsam.sh"

basis="${1:-origin/main}"
dateien="$(git -C "$projekt" diff --name-only "$basis...HEAD")" || { echo "Harness: Diff gegen $basis fehlgeschlagen." >&2; echo hoch; exit 1; }

passt() { # passt <datei> <schlüssel>
  local glob
  while IFS= read -r glob; do
    # shellcheck disable=SC2053 # Das Muster soll als Glob wirken.
    [[ -n "$glob" && "$1" == $glob ]] && return 0
  done < <(harness_liste "$2")
  return 1
}

klasse=niedrig
while IFS= read -r datei; do
  [[ -n "$datei" ]] || continue
  if passt "$datei" risiko-hoch; then
    echo "hoch: $datei" >&2; echo hoch; exit 0
  fi
  passt "$datei" risiko-niedrig || { klasse=mittel; echo "mittel: $datei" >&2; }
done <<<"$dateien"
echo "$klasse"
