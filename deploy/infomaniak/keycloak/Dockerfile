# Keycloak für die VPS (Aufgabe 1.9, design.md «Betrieb auf der VPS»). Der Deploy-Workflow taggt das Image als
# archiv:kc-<version> (Compose-Policy der VPS). Dieselbe Version wie die Dev Services
# (quarkus.keycloak.devservices.image-name), geprüft von DeployImagesTest. Build-Kontext ist deploy/.
FROM quay.io/keycloak/keycloak:26.8.2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 AS build
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --cache=local

FROM quay.io/keycloak/keycloak:26.8.2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0
COPY --from=build /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json
# Login-Theme des Mandanten VHF/GSK (Aufgabe 10.3); gewählt wird es am Client im Realm (Admin-Konsole, Hinweis in design.md)
COPY --chown=1000:0 infomaniak/keycloak/themes/vhf-gsk /opt/keycloak/themes/vhf-gsk
COPY --chown=1000:0 infomaniak/keycloak/themes/urinet-archiv /opt/keycloak/themes/urinet-archiv
COPY infomaniak/keycloak/start.sh /opt/keycloak/bin/archiv-start.sh
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/archiv-start.sh"]
