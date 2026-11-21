# Keycloak für die VPS (Aufgabe 1.9). Die Distribution stammt aus demselben gepinnten Image wie die Dev Services.
# Gebaut und ausgeführt wird sie mit dem für CVE-2026-114938 behobenen Temurin 25.0.5.1+2 (Docker-Tag 25.0.5.1_2).
# Der Deploy-Workflow taggt das Image als archiv:kc-<version> (Compose-Policy der VPS). Build-Kontext: deploy/.
FROM quay.io/keycloak/keycloak:26.8.2-3@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS keycloak

FROM eclipse-temurin:25.0.5.1_2-jdk-noble@sha256:c7eef3dfeb81468533ea48c67bddaa8c7ca916454259e4d0d734d12bcff8e125 AS build
ENV KC_RUN_IN_CONTAINER=true
COPY --from=keycloak --chown=1000:0 /opt/keycloak/ /opt/keycloak/
USER 1000:0
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --cache=local

FROM eclipse-temurin:25.0.5.1_2-jre-noble@sha256:2ec98aa522c472deadfd2b7c5b39bca680e33ef522a53a72f10789bdf89a6bb5
ENV KC_RUN_IN_CONTAINER=true
COPY --from=build --chown=1000:0 /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json
# Login-Theme des Mandanten VHF/GSK (Aufgabe 10.3); gewählt wird es am Client im Realm (Admin-Konsole, Hinweis in design.md)
COPY --chown=1000:0 infomaniak/keycloak/themes/vhf-gsk /opt/keycloak/themes/vhf-gsk
COPY infomaniak/keycloak/start.sh /opt/keycloak/bin/archiv-start.sh
USER 1000:1000
ENTRYPOINT ["/opt/keycloak/bin/archiv-start.sh"]
