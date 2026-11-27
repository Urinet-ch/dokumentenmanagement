#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
ausgabe="${1:?Bitte ein leeres Ausgabeverzeichnis angeben.}"
pruefe_ausgabe() {
    case "$1" in
        "$root"|"$root"/*)
            echo "Das Ausgabeverzeichnis muss ausserhalb des Repositories liegen." >&2
            return 1
            ;;
    esac
}
if [ -d "$ausgabe" ]; then
    ausgabe="$(cd "$ausgabe" && pwd -P)"
else
    ausgabe_eltern="$(dirname "$ausgabe")"
    [ -d "$ausgabe_eltern" ] || {
        echo "Das übergeordnete Ausgabeverzeichnis muss existieren." >&2
        exit 1
    }
    ausgabe="$(cd "$ausgabe_eltern" && pwd -P)/$(basename "$ausgabe")"
fi
pruefe_ausgabe "$ausgabe"
mkdir -p "$ausgabe" "$root/backend/target"
ausgabe="$(cd "$ausgabe" && pwd -P)"
pruefe_ausgabe "$ausgabe"
if [ -n "$(ls -A "$ausgabe")" ]; then
    echo "Das Ausgabeverzeichnis muss leer sein." >&2
    exit 1
fi

case "$(docker info --format '{{.Architecture}}')" in
    aarch64|arm64)
        digest=sha256:0b8847b2c722b16f0964431aabcbb499560a1f17e8698d52326d82db29efac1d
        brotli_native=native-linux-aarch64
        ;;
    x86_64|amd64)
        digest=sha256:c2d63bbb2d907a5c5257914fb20e26e969c92b25f265def7f89f08203f912882
        brotli_native=native-linux-x86_64
        ;;
    *) echo "Nicht unterstützte Docker-Architektur." >&2; exit 1 ;;
esac
zap="ghcr.io/zaproxy/zaproxy@$digest"
temurin_jre="eclipse-temurin@sha256:2ec98aa522c472deadfd2b7c5b39bca680e33ef522a53a72f10789bdf89a6bb5"
temurin_jdk="eclipse-temurin@sha256:c7eef3dfeb81468533ea48c67bddaa8c7ca916454259e4d0d734d12bcff8e125"
pgvector="pgvector/pgvector@sha256:01215e09bd4bd582d20230e025b6c971deeeffee49ceef1aae167e839cb62a7e"
docker image inspect "$zap" >/dev/null 2>&1 || docker pull "$zap"
arbeit="$(mktemp -d "${TMPDIR:-/tmp}/zap-baseline-lokal.XXXXXXXX")"
anker=""
db=""
server=""
scanner=""
arbeitsberichte="$arbeit/berichte"
mkdir "$arbeitsberichte"
redact_ausgaben() {
    [ -d "$arbeit/zugang" ] || return 0
    for zugangsdatei in "$arbeit"/zugang/*; do
        [ -f "$zugangsdatei" ] || continue
        token="$(<"$zugangsdatei")"
        [ -n "$token" ] || continue
        while IFS= read -r -d '' datei; do
            redigiert="$datei.redigiert"
            ZAP_SECRET="$token" perl -0777 -pe 's/\Q$ENV{ZAP_SECRET}\E/[REDACTED]/g' "$datei" >"$redigiert"
            mv "$redigiert" "$datei"
        done < <(find "$arbeitsberichte" -type f -print0)
    done
}
pruefe_ausgaben_ohne_zugangsdaten() {
    for zugangsdatei in "$arbeit"/zugang/*; do
        [ -f "$zugangsdatei" ] || continue
        token="$(<"$zugangsdatei")"
        [ -n "$token" ] || continue
        while IFS= read -r -d '' datei; do
            if grep -aFq -- "$token" "$datei"; then
                echo "Eine ZAP-Ausgabe enthält noch einen synthetischen Zugang." >&2
                return 1
            else
                grep_status=$?
                if [ "$grep_status" -ne 1 ]; then
                    echo "ZAP-Ausgaben konnten nicht auf Zugangsdaten geprüft werden." >&2
                    return 1
                fi
            fi
        done < <(find "$arbeitsberichte" -type f -print0)
    done
}
aufraeumen() {
    redact_ausgaben || echo "Zugangsdaten konnten nicht aus allen ZAP-Ausgaben entfernt werden." >&2
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
brotli_cache="$HOME/.m2/repository/com/aayushatharva/brotli4j/$brotli_native/1.24.0"
if [ ! -s "$brotli_cache/$brotli_native-1.24.0.jar" ] ||
    [ ! -s "$brotli_cache/$brotli_native-1.24.0.pom" ]; then
    if ! mvn -B -ntp -s "$arbeit/settings.xml" -gs "$arbeit/settings.xml" \
        -Dmaven.repo.local="$HOME/.m2/repository" dependency:get \
        -Dartifact="com.aayushatharva.brotli4j:$brotli_native:1.24.0" -Dtransitive=false; then
        echo "Das Linux-Brotli-Artefakt für die Docker-Architektur konnte nicht vorab geladen werden." >&2
        exit 1
    fi
fi
mkdir "$arbeit/locks"
anker="$(docker run -d --network none "$temurin_jre" sleep 2700)"
db="$(docker run -d --network "container:$anker" \
    -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=zap_baseline \
    "$pgvector")"
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
    --workdir /workspace/backend "$temurin_jdk" \
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
scan_status=0
gesamt_status=0
fataler_scan=0
scan_zap() {
    local name="$1" ziel="$2" zugangsdatei="${3:-}" stufe="${4:-}"
    local zap_optionen="-silent" konfiguration="" auth_scope="" auth_probe=""
    mkdir "$arbeitsberichte/$name"
    if [ -n "$zugangsdatei" ]; then
        local token
        token="$(<"$zugangsdatei")"
        [ -n "$token" ] || { echo "Testzugang für $name fehlt." >&2; return 1; }
        if [ -n "$stufe" ]; then
            auth_scope='^http://127\.0\.0\.1:[0-9]+/public/v1/.*$'
            auth_probe='http://127.0.0.1:43123/public/v1/dokumente'
        else
            auth_scope='^http://127\.0\.0\.1:[0-9]+/api/v1/.*$'
            auth_probe='http://127.0.0.1:43123/api/v1/dokumente'
        fi
        if ! printf '%s\n' "$auth_probe" | grep -Eq "$auth_scope"; then
            echo "Der Authentisierungsbereich deckt keine Loopback-API-Route ab." >&2
            return 1
        fi
        if printf '%s\n' \
            'http://zap/JSON/openapi/action/importUrl/?url=http%3A%2F%2F127.0.0.1%3A45657%2Fq%2Fopenapi' \
            'http://zap/JSON/spider/action/scan/?url=http://127.0.0.1:45657/api/v1/dokumente' \
            'https://example.invalid/api/v1/dokumente' | grep -Eq "$auth_scope"; then
            echo "Der Authentisierungsbereich umfasst eine ZAP-Steuerung oder ein fremdes Ziel." >&2
            return 1
        fi
        konfiguration="$arbeit/$name-zap.conf"
        (
            umask 077
            {
                printf 'replacer.full_list(0).description=zap-bearer\n'
                printf 'replacer.full_list(0).enabled=true\n'
                printf 'replacer.full_list(0).matchtype=REQ_HEADER\n'
                printf 'replacer.full_list(0).matchstr=Authorization\n'
                printf 'replacer.full_list(0).regex=false\n'
                printf 'replacer.full_list(0).replacement=Bearer %s\n' "$token"
                printf 'replacer.full_list(0).url=%s\n' "$auth_scope"
                if [ -n "$stufe" ]; then
                    printf 'replacer.full_list(1).description=zap-zugriffsstufe\n'
                    printf 'replacer.full_list(1).enabled=true\n'
                    printf 'replacer.full_list(1).matchtype=REQ_HEADER\n'
                    printf 'replacer.full_list(1).matchstr=X-Archiv-Zugriff\n'
                    printf 'replacer.full_list(1).regex=false\n'
                    printf 'replacer.full_list(1).replacement=%s\n' "$stufe"
                    printf 'replacer.full_list(1).url=%s\n' "$auth_scope"
                fi
            } >"$konfiguration"
        )
        zap_optionen="$zap_optionen -configfile /tmp/zap-auth.conf"
    fi
    scanner="$(docker create -i --pull=never --network "container:$anker" \
        --cap-drop ALL --security-opt no-new-privileges --memory 2g --workdir /zap \
        --env HTTP_PROXY= --env HTTPS_PROXY= --env ALL_PROXY= \
        --env http_proxy= --env https_proxy= --env all_proxy= \
        --mount "type=bind,source=$root/tools/sicherheit/zap-ausnahmen.conf,target=/zap/ausnahmen.conf,readonly" \
        --mount "type=bind,source=$root/docs/schnittstelle/public-v1.yaml,target=/zap/public-v1.yaml,readonly" \
        --entrypoint /bin/sh "$zap" -c 'set -e; umask 077; cat > /tmp/zap-auth.conf; mkdir -p /zap/wrk; exec /zap/zap-api-scan.py "$@"' sh \
        -S -f openapi -t "$ziel" -O "$url" \
        -c /zap/ausnahmen.conf -J bericht.json -w bericht.md -r bericht.html -T 5 -s -z "$zap_optionen")"
    set +e
    docker start -a -i "$scanner" <"${konfiguration:-/dev/null}" >"$arbeitsberichte/$name/zap.log" 2>&1
    scan_status=$?
    set -e
    docker cp "$scanner:/zap/zap.out" "$arbeitsberichte/$name/zap.out" 2>/dev/null \
        || echo "ZAP-Startprotokoll für $name nicht verfügbar." >&2
    if [ "$scan_status" -le 2 ]; then
        for bericht in bericht.json bericht.md bericht.html; do
            docker cp "$scanner:/zap/wrk/$bericht" "$arbeitsberichte/$name/$bericht"
            [ -s "$arbeitsberichte/$name/$bericht" ] || { echo "ZAP-Bericht $bericht ist leer." >&2; exit 1; }
        done
    fi
    docker rm -f "$scanner" >/dev/null
    scanner=""
}

scan_und_sammeln() {
    scan_zap "$@"
    if [ "$scan_status" -gt 2 ]; then
        echo "ZAP-Scan $1 fehlgeschlagen; siehe zap.log." >&2
        gesamt_status="$scan_status"
        fataler_scan=1
    elif [ "$scan_status" = 1 ]; then
        gesamt_status=1
    elif [ "$scan_status" = 2 ] && [ "$gesamt_status" = 0 ]; then
        gesamt_status=2
    fi
}

for _ in 1; do
    scan_und_sammeln app "${url}q/openapi"
    [ "$fataler_scan" = 0 ] || break
    scan_und_sammeln webseite /zap/public-v1.yaml
    [ "$fataler_scan" = 0 ] || break
    scan_und_sammeln app-authentisiert "${url}q/openapi" "$arbeit/zugang/api-token"
    [ "$fataler_scan" = 0 ] || break
    for stufe in oeffentlich mitglieder vorstand; do
        scan_und_sammeln "webseite-$stufe" /zap/public-v1.yaml "$arbeit/zugang/webseite-$stufe" "$stufe"
        [ "$fataler_scan" = 0 ] || break
    done
    [ "$fataler_scan" = 0 ] || break
done
touch "$arbeit/abgeschlossen"
server_status="$(docker wait "$server")"
docker logs "$server" >"$arbeitsberichte/testserver.log" 2>&1
if [ "$server_status" != 0 ]; then
    echo "Testserver fehlgeschlagen; siehe testserver.log." >&2
    exit 1
fi

redact_ausgaben
pruefe_ausgaben_ohne_zugangsdaten
cp -R "$arbeitsberichte/." "$ausgabe/"
echo "ZAP-Status $gesamt_status. Berichte: $ausgabe"
exit "$gesamt_status"
