#!/usr/bin/env bash
# Lädt die Images der Dev Services (Postgres, Keycloak, MinIO, Mailpit) vor, während Maven kompiliert (ci.yml): Der erste
# Quarkus-Start im Test wartet sonst auf den Pull (~10 s je Job). Die Namen stehen nur in application.properties und
# backend/compose-devservices.yml und werden von dort gelesen, damit nichts auseinanderläuft. Fehler sind unkritisch:
# Fehlt ein Image, zieht es Quarkus beim Start wie bisher.
cd "$(dirname "$0")/../../backend" || exit 0
{
  sed -n 's/^[^#]*devservices\.image-name=//p' src/main/resources/application.properties
  sed -n 's/^[[:space:]]*image:[[:space:]]*//p' compose-devservices.yml
} | tr -d '\r' | sort -u | xargs -r -P 4 -n 1 docker pull --quiet > /dev/null 2>&1
exit 0
