#!/usr/bin/env bash
# Schreibt die .env des Bundles (Aufgabe 5.6; Aufruf aus infomaniak-deploy.yml, «Bundle schnüren»): eine Zeile je
# Variable, Compose interpoliert sie in compose.yml (deploy-app.sh legt die Datei neben compose.yml). Keine Geheimnisse,
# die gehen nur als Dateien unter secrets/. ARCHIV_ALARM_EMPFAENGER_URINET ist leer oder genau eine Adresse ohne
# Leerraum, Anführungszeichen oder $; sonst entstünden weitere Zeilen, oder Compose interpolierte weiter. Dann Exit 1,
# ohne den Wert zu nennen, und die Datei bleibt ungeschrieben. Zusätzlich schreibt es ARCHIV_BASIS_URL aus HOST_ARCHIV (Workflow-env). Test: DeployBenachrichtigungTest.
set -euo pipefail
umask 077

ziel="${1:?Aufruf: schreibe-env.sh <ziel>}"
empfaenger="${ARCHIV_ALARM_EMPFAENGER_URINET:-}"
if [[ -n "$empfaenger" && ! "$empfaenger" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  echo "::error::Variable ARCHIV_ALARM_EMPFAENGER_URINET ist keine einzelne E-Mail-Adresse; kein Deploy."
  exit 1
fi
# ARCHIV_BASIS_URL braucht start.sh von Keycloak (Realm-Import: Redirect-URIs); ohne den Wert startet Keycloak nicht.
host="${HOST_ARCHIV:-}"
if [[ ! "$host" =~ ^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+$ ]]; then
  echo "::error::Variable HOST_ARCHIV fehlt oder ist kein Hostname; kein Deploy."
  exit 1
fi
netz="${ARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE:-}"
if ! python3 -c 'import ipaddress,sys; n=ipaddress.ip_network(sys.argv[1], strict=True); sys.exit(0 if n.is_private and n.prefixlen > 0 else 1)' "$netz" 2>/dev/null; then
  echo "::error::ARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE muss das gültige CIDR-Netz von web-archiv enthalten; kein Deploy."
  exit 1
fi
printf 'ARCHIV_ALARM_EMPFAENGER_URINET=%s\nARCHIV_BASIS_URL=https://%s\nARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE=%s\n' \
  "$empfaenger" "$host" "$netz" > "$ziel"
