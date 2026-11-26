#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || "$1" != "--freigabe" || ( $# == 2 && "$2" != "--leistungsbasis" ) ]]; then
  echo "Einmaliger lokaler Messlauf: bash tools/last/wissenssuche.sh --freigabe [--leistungsbasis]" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
kennung="$(uuidgen | tr '[:upper:]' '[:lower:]')"
name="urinet-wissen-leistung-$kennung"
arbeitsordner="$root/backend/target/$name"
container=""
volume=""
arbeitsordner_erstellt=false

aufraeumen() {
  local ergebnis=$? fehler=0
  trap - EXIT
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null || fehler=1; fi
  if [[ -n "$volume" ]]; then docker volume rm "$volume" >/dev/null || fehler=1; fi
  if [[ "$arbeitsordner_erstellt" == true ]]; then rm -rf "$arbeitsordner" || fehler=1; fi
  if [[ $fehler != 0 ]]; then
    echo "Bereinigung fehlgeschlagen: Container-ID $container, Volume $volume, Ordner $arbeitsordner" >&2
    if [[ $ergebnis == 0 ]]; then ergebnis=1; fi
  fi
  exit "$ergebnis"
}
trap aufraeumen EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p backend/target
mkdir "$arbeitsordner"
arbeitsordner_erstellt=true
docker image inspect pgvector/pgvector:0.8.7-pg17-trixie >/dev/null
if docker volume inspect "$name" >/dev/null 2>&1; then
  echo "Volume bereits vorhanden: $name" >&2
  exit 1
fi
volume="$(docker volume create --label "ch.urinet.wissen-leistung=$kennung" "$name")"
container="$(docker create --pull=never --name "$name" --label "ch.urinet.wissen-leistung=$kennung" \
  --memory=512m --cpus=2 \
  -p 127.0.0.1::5432 --mount "type=volume,source=$volume,target=/var/lib/postgresql/data" \
  -e POSTGRES_DB=wissen_leistung -e POSTGRES_PASSWORD=lokales-testpasswort \
  pgvector/pgvector:0.8.7-pg17-trixie)"
docker start "$container" >/dev/null
# TCP statt Socket: das Image startet zuerst einen Init-Server nur auf dem Socket und danach neu
for i in {1..60}; do
  if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d wissen_leistung >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$container" pg_isready -h 127.0.0.1 -U postgres -d wissen_leistung >/dev/null
port="$(docker port "$container" 5432/tcp | sed 's/.*://')"
testklasse=WissenssucheLeistungIT
testoption=-Darchiv.wissen-leistung=true
if [[ $# == 2 ]]; then
  testklasse=LeistungsbasisIT
  testoption=-Darchiv.leistungsbasis=true
fi
TMPDIR="$arbeitsordner" mvn -B -ntp -f backend/pom.xml test-compile surefire:test \
  -Djava.io.tmpdir="$arbeitsordner" -Dquarkus.http.test-port=0 \
  -Dquarkus.datasource.jdbc.url="jdbc:postgresql://127.0.0.1:$port/wissen_leistung" \
  -Dquarkus.datasource.username=postgres -Dquarkus.datasource.password=lokales-testpasswort \
  "$testoption" "-Dtest=$testklasse"
if [[ $# == 2 ]]; then
  python3 tools/last/leistungsbasis.py backend/target/leistungsbasis-roh.csv
fi
