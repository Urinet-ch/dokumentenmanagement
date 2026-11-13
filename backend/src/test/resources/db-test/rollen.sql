-- Nur für MandantEigentuemerTest: DB-Rollen wie in prod (design.md «Secrets und Konfiguration»), Testpasswörter
create role archiv_owner login password 'owner-nur-test';
create role archiv_app login password 'app-nur-test';
grant create on database quarkus to archiv_owner;
alter schema public owner to archiv_owner;
-- pgvector ist nicht «trusted»: wie das Init-Skript der VPS (deploy/infomaniak/db/10-archiv-rollen.sh) als Superuser
create extension if not exists vector with schema public;
