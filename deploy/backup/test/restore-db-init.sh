#!/usr/bin/env bash
set -euo pipefail

owner=$(< /run/secrets/archiv_db_owner)
app=$(< /run/secrets/archiv_db_app)
keycloak=$(< /run/secrets/keycloak_db)

psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname postgres \
  --set=owner="$owner" --set=app="$app" --set=keycloak="$keycloak" <<'SQL'
create role archiv_owner login password :'owner';
create role archiv_app login password :'app';
create role keycloak login password :'keycloak';
create database archiv owner archiv_owner;
create database keycloak owner keycloak;
revoke all on database archiv from public;
revoke all on database keycloak from public;
grant connect on database archiv to archiv_app;
SQL

psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname archiv <<'SQL'
alter schema public owner to archiv_owner;
revoke create on schema public from public;
create extension if not exists vector;
create extension if not exists pg_trgm;
SQL
