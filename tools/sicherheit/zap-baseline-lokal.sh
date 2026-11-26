#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
ausgabe="${1:?Bitte ein leeres Ausgabeverzeichnis angeben.}"
mkdir -p "$ausgabe" "$root/backend/target"
ausgabe="$(cd "$ausgabe" && pwd -P)"
if [ -n "$(ls -A "$ausgabe")" ]; then
    echo "Das Ausgabeverzeichnis muss leer sein." >&2
    exit 1
fi

case "$(docker info --format '{{.Architecture}}')" in
    aarch64|arm64) digest=sha256:0b8847b2c722b16f0964431aabcbb499560a1f17e8698d52326d82db29efac1d ;;
    x86_64|amd64) digest=sha256:c2d63bbb2d907a5c5257914fb20e26e969c92b25f265def7f89f08203f912882 ;;
    *) echo "Nicht unterstützte Docker-Architektur." >&2; exit 1 ;;
esac
zap="ghcr.io/zaproxy/zaproxy@$digest"
docker image inspect "$zap" >/dev/null 2>&1 || docker pull "$zap"
arbeit="$(mktemp -d "$root/backend/target/zap-lokal.XXXXXXXX")"
anker=""
db=""
server=""
scanner=""
aufraeumen() {
    [ -z "$scanner" ] || docker rm -f "$scanner" >/dev/null || echo "Scanner konnte nicht entfernt werden: $scanner" >&2
    [ -z "$server" ] || docker rm -f "$server" >/dev/null || echo "Testserver konnte nicht entfernt werden: $server" >&2
    [ -z "$db" ] || docker rm -fv "$db" >/dev/null || echo "Testdatenbank konnte nicht entfernt werden: $db" >&2
    [ -z "$anker" ] || docker rm -f "$anker" >/dev/null || echo "Netzraum konnte nicht entfernt werden: $anker" >&2
    rm -rf "$arbeit" || echo "Arbeitsverzeichnis konnte nicht entfernt werden: $arbeit" >&2
}
trap aufraeumen EXIT

maven_home="$(mvn --version | sed -n 's/^Maven home: //p')"
[ -d "$maven_home" ] || { echo "Maven-Verzeichnis nicht gefunden." >&2; exit 1; }
mkdir -p "$arbeit/maven/conf/logging"
cp -R "$maven_home/bin" "$maven_home/boot" "$maven_home/lib" "$arbeit/maven/"
printf '<settings/>\n' >"$arbeit/settings.xml"
mkdir "$arbeit/locks"
anker="$(docker run -d --network none eclipse-temurin:25.0.5.1_2-jre-noble sleep 1200)"
db="$(docker run -d --network "container:$anker" \
    -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=zap_baseline \
    pgvector/pgvector:0.8.7-pg17-trixie)"
for _ in $(seq 1 60); do
    if docker exec "$db" pg_isready -h 127.0.0.1 -U postgres -d zap_baseline >/dev/null 2>&1; then break; fi
    sleep 1
done
docker exec "$db" pg_isready -h 127.0.0.1 -U postgres -d zap_baseline >/dev/null
server="$(docker run -d --network "container:$anker" \
    --mount "type=bind,source=$root,target=/workspace" \
    --mount "type=bind,source=$arbeit,target=/scan" \
    --mount "type=bind,source=$HOME/.m2/repository,target=/m2,readonly" \
    --mount "type=bind,source=$arbeit/locks,target=/m2/.locks" \
    --workdir /workspace/backend eclipse-temurin:25.0.5.1_2-jdk-noble \
    /scan/maven/bin/mvn -o -B -ntp -s /scan/settings.xml -gs /scan/settings.xml -Dmaven.repo.local=/m2 \
    -Dquarkus.http.test-port=0 -Dtest=ZapBaselineIT -Darchiv.zap-baseline=true \
    -Darchiv.zap.verzeichnis=/scan -Dquarkus.datasource.jdbc.url=jdbc:postgresql://127.0.0.1:5432/zap_baseline \
    -Dquarkus.datasource.username=postgres -Dquarkus.datasource.password=synthetisch test)"
for _ in $(seq 1 300); do
    [ ! -f "$arbeit/bereit" ] || break
    if [ "$(docker inspect -f '{{.State.Running}}' "$server")" != true ]; then
        docker logs "$server" >&2
        exit 1
    fi
    sleep 1
done
[ -f "$arbeit/bereit" ] || { echo "Testserver wurde nicht rechtzeitig bereit." >&2; docker logs "$server" >&2; exit 1; }
url="$(<"$arbeit/bereit")"
if ! printf '%s\n' "$url" | grep -Eq '^http://127\.0\.0\.1:[0-9]{1,5}/$'; then
    echo "Testserver meldet keine gültige Loopback-Adresse." >&2
    exit 1
fi
gesamt_status=0
for schnittstelle in app webseite; do
    mkdir "$ausgabe/$schnittstelle"
    if [ "$schnittstelle" = app ]; then
        ziel="${url}q/openapi"
    else
        ziel=/zap/public-v1.yaml
    fi
    scanner="$(docker create --pull=never --network "container:$anker" \
        --cap-drop ALL --security-opt no-new-privileges --memory 2g --workdir /zap \
        --mount "type=bind,source=$root/tools/sicherheit/zap-ausnahmen.conf,target=/zap/ausnahmen.conf,readonly" \
        --mount "type=bind,source=$root/docs/schnittstelle/public-v1.yaml,target=/zap/public-v1.yaml,readonly" \
        --entrypoint /bin/sh "$zap" -c 'mkdir -p /zap/wrk; exec /zap/zap-api-scan.py "$@"' sh \
        -S -f openapi -t "$ziel" -O "$url" \
        -c /zap/ausnahmen.conf -J bericht.json -w bericht.md -r bericht.html -T 5 -s -z "-silent")"
    set +e
    docker start -a "$scanner" >"$ausgabe/$schnittstelle/zap.log" 2>&1
    scan_status=$?
    set -e
    docker cp "$scanner:/zap/zap.out" "$ausgabe/$schnittstelle/zap.out" 2>/dev/null \
        || echo "ZAP-Startprotokoll für $schnittstelle nicht verfügbar." >&2
    if [ "$scan_status" -gt 2 ]; then
        echo "ZAP-Scan $schnittstelle fehlgeschlagen; siehe zap.log." >&2
        gesamt_status="$scan_status"
        break
    fi
    for bericht in bericht.json bericht.md bericht.html; do
        docker cp "$scanner:/zap/wrk/$bericht" "$ausgabe/$schnittstelle/$bericht"
        [ -s "$ausgabe/$schnittstelle/$bericht" ] || { echo "ZAP-Bericht $bericht ist leer." >&2; exit 1; }
    done
    docker rm -f "$scanner" >/dev/null
    scanner=""
    if [ "$scan_status" = 1 ]; then
        gesamt_status=1
    elif [ "$scan_status" = 2 ] && [ "$gesamt_status" = 0 ]; then
        gesamt_status=2
    fi
done
touch "$arbeit/abgeschlossen"
server_status="$(docker wait "$server")"
docker logs "$server" >"$ausgabe/testserver.log" 2>&1
if [ "$server_status" != 0 ]; then
    echo "Testserver fehlgeschlagen; siehe testserver.log." >&2
    exit 1
fi
echo "ZAP-Status $gesamt_status. Berichte: $ausgabe"
exit "$gesamt_status"
