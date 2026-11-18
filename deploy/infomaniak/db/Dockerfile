# PostgreSQL 17 mit pgvector für die VPS (Aufgabe 1.9, design.md «Betrieb auf der VPS»). Der Deploy-Workflow
# taggt das Image als archiv:db-<version> (Compose-Policy der VPS: Fremd-Images unter dem App-Namen).
# Dieselbe Version wie die Dev Services (quarkus.datasource.devservices.image-name), geprüft von DeployImagesTest.
FROM pgvector/pgvector:0.8.7-pg17-trixie@sha256:89947206f52eac3a9df9b663df7e76987cbdc623be6576b3a524953d568700d7

COPY 10-archiv-rollen.sh /docker-entrypoint-initdb.d/10-archiv-rollen.sh
