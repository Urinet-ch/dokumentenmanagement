# PostgreSQL 17 mit pgvector für die VPS (Aufgabe 1.9, design.md «Betrieb auf der VPS»). Der Deploy-Workflow
# taggt das Image als archiv:db-<version> (Compose-Policy der VPS: Fremd-Images unter dem App-Namen).
# Dieselbe Version wie die Dev Services (quarkus.datasource.devservices.image-name), geprüft von DeployImagesTest.
FROM pgvector/pgvector:0.8.7-pg17-trixie@sha256:01215e09bd4bd582d20230e025b6c971deeeffee49ceef1aae167e839cb62a7e

COPY 10-archiv-rollen.sh /docker-entrypoint-initdb.d/10-archiv-rollen.sh
