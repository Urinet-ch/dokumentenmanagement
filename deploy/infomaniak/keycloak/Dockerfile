# Keycloak für die VPS (Aufgabe 1.9, design.md «Betrieb auf der VPS»). Der Deploy-Workflow taggt das Image als
# archiv:kc-<version> (Compose-Policy der VPS). Dieselbe Version wie die Dev Services
# (quarkus.keycloak.devservices.image-name), geprüft von DeployImagesTest. Build-Kontext ist deploy/.
FROM quay.io/keycloak/keycloak:26.8.2@sha256:723b1d6e254c0fc96d5cffec860c7e0744d6a3277822b865bb538cf891575e41 AS build
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --cache=local

FROM quay.io/keycloak/keycloak:26.8.2@sha256:723b1d6e254c0fc96d5cffec860c7e0744d6a3277822b865bb538cf891575e41
COPY --from=build /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json
COPY infomaniak/keycloak/start.sh /opt/keycloak/bin/archiv-start.sh
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/archiv-start.sh"]
