#!/usr/bin/env bash
# Legt beim ersten Start (leeres Volume) die Rollen und Datenbanken des Archivs an (Aufgabe 1.9).
# Der Container sieht keine Passwörter der Rollen, nur ihre SCRAM-Verifier (der Deploy-Workflow berechnet sie aus den
# GitHub-Secrets). Läuft nur bei leerem Datenverzeichnis; ein späterer Passwortwechsel geht per ALTER ROLE
# (design.md «Betrieb auf der VPS»).
set -euo pipefail

# shellcheck disable=SC2016 # Regex, keine Variablen
VERIFIER='^SCRAM-SHA-256\$4096:[A-Za-z0-9+/]+={0,2}\$[A-Za-z0-9+/]+={0,2}:[A-Za-z0-9+/]+={0,2}$'
for name in archiv_db_owner_scram archiv_db_app_scram keycloak_db_scram; do
  datei="/run/secrets/$name"
  if [[ ! -s "$datei" ]] || ! grep -Eq "$VERIFIER" "$datei" || [[ $(grep -c '' "$datei") -ne 1 ]]; then
    echo "Secret-Datei $name fehlt oder ist kein SCRAM-SHA-256-Verifier: Deploy-Workflow erneut ausführen." >&2
    exit 1
  fi
done

# Der Superuser postgres meldet sich nur über den lokalen Socket im Container an, nie über TCP (Nachbesserung PR #42):
# Die Regel steht vor der allgemeinen «host all all all …», die das Image beim Init anhängt.
hba="$PGDATA/pg_hba.conf"
if ! grep -Eq '^host[[:space:]]+all[[:space:]]+all[[:space:]]+all[[:space:]]' "$hba"; then
  echo "pg_hba.conf ohne Regel «host all all all»: Image von PostgreSQL prüfen (Aufgabe 1.9)." >&2
  exit 1
fi
sed -i '0,/^host[[:space:]]\+all[[:space:]]\+all[[:space:]]\+all[[:space:]]/s//host all postgres all reject\n&/' "$hba"

# Werte nur über psql-Variablen (\set mit Backticks), nie auf der Kommandozeile
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\set owner `cat /run/secrets/archiv_db_owner_scram`
\set app `cat /run/secrets/archiv_db_app_scram`
\set kc `cat /run/secrets/keycloak_db_scram`
create role archiv_owner login password :'owner';
create role archiv_app login password :'app';
create role keycloak login password :'kc';
create database archiv owner archiv_owner;
create database keycloak owner keycloak;
revoke all on database archiv from public;
revoke all on database keycloak from public;
revoke connect on database postgres from public;
grant connect on database archiv to archiv_app;
SQL

# Schema public gehört archiv_owner (Liquibase), die Rechte von archiv_app vergibt die App nach der Migration.
# pgvector ist keine vertrauenswürdige Erweiterung und braucht den Superuser, darum schon hier.
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname archiv <<'SQL'
alter schema public owner to archiv_owner;
revoke create on schema public from public;
create extension if not exists vector;
create extension if not exists pg_trgm;
SQL
