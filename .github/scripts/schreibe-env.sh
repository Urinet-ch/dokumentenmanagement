#!/usr/bin/env bash
# Schreibt die .env des Bundles (Aufgabe 5.6; Aufruf aus infomaniak-deploy.yml, «Bundle schnüren»): eine Zeile je
# Variable, Compose interpoliert sie in compose.yml (deploy-app.sh legt die Datei neben compose.yml). Keine Geheimnisse,
# die gehen nur als Dateien unter secrets/. ARCHIV_ALARM_EMPFAENGER_URINET ist leer oder genau eine Adresse ohne
# Leerraum, Anführungszeichen oder $; sonst entstünden weitere Zeilen, oder Compose interpolierte weiter. Dann Exit 1,
# ohne den Wert zu nennen, und die Datei bleibt ungeschrieben. Test: DeployBenachrichtigungTest.
set -euo pipefail
umask 077

ziel="${1:?Aufruf: schreibe-env.sh <ziel>}"
empfaenger="${ARCHIV_ALARM_EMPFAENGER_URINET:-}"
if [[ -n "$empfaenger" && ! "$empfaenger" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  echo "::error::Variable ARCHIV_ALARM_EMPFAENGER_URINET ist keine einzelne E-Mail-Adresse; kein Deploy."
  exit 1
fi
printf 'ARCHIV_ALARM_EMPFAENGER_URINET=%s\n' "$empfaenger" > "$ziel"
