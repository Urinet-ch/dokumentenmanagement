# Keycloak für die VPS (Aufgabe 1.9). Die Distribution stammt aus demselben gepinnten Image wie die Dev Services.
# Gebaut und ausgeführt wird sie mit dem für CVE-2026-114938 behobenen Temurin 25.0.5.1+2 (Docker-Tag 25.0.5.1_2).
# Der Deploy-Workflow taggt das Image als archiv:kc-<version> (Compose-Policy der VPS). Build-Kontext: deploy/.
FROM quay.io/keycloak/keycloak:26.8.2-3@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS keycloak

FROM eclipse-temurin:26.0.2.1_1-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
ENV KC_RUN_IN_CONTAINER=true
COPY --from=keycloak --chown=1000:0 /opt/keycloak/ /opt/keycloak/
USER 1000:0
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --cache=local

FROM eclipse-temurin:26.0.2.1_1-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3
ENV KC_RUN_IN_CONTAINER=true
COPY --from=build --chown=1000:0 /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json
# Login-Theme des Mandanten VHF/GSK (Aufgabe 10.3); gewählt wird es am Client im Realm (Admin-Konsole, Hinweis in design.md)
COPY --chown=1000:0 infomaniak/keycloak/themes/vhf-gsk /opt/keycloak/themes/vhf-gsk
COPY --chown=1000:0 infomaniak/keycloak/themes/urinet-archiv /opt/keycloak/themes/urinet-archiv
COPY infomaniak/keycloak/start.sh /opt/keycloak/bin/archiv-start.sh
USER 1000:1000
ENTRYPOINT ["/opt/keycloak/bin/archiv-start.sh"]
