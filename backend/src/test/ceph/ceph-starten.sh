#!/bin/sh
# Startet Ceph RGW (ceph-start.sh) als Einzelcontainer auf 127.0.0.1:7480 und wartet, bis die RGW antwortet.
# Aufruf: backend/src/test/ceph/ceph-starten.sh <Zugang-Verzeichnis>
# Fehlt im Verzeichnis der Testzugang (zugang_id, zugang_geheimnis), wird er zufällig erzeugt (Rechte 600). Beenden:
# docker rm -f archiv-ceph-rgw (Name über CEPH_CONTAINER änderbar).
set -eu
ZUGANG=$1
NAME=${CEPH_CONTAINER:-archiv-ceph-rgw}
IMAGE=quay.io/ceph/ceph:v20.2.5-20261028@sha256:4337ac8ac81d40df136e5eac92ca53bb52360a5e039f3d1f7935e729eb5ef42c
HIER=$(cd "$(dirname "$0")" && pwd)

if [ ! -f "$ZUGANG/zugang_id" ] || [ ! -f "$ZUGANG/zugang_geheimnis" ]; then
  mkdir -p "$ZUGANG"
  chmod 700 "$ZUGANG"
  (
    umask 077
    printf 'CEPHTEST%s' "$(openssl rand -hex 6 | tr 'a-f' 'A-F')" > "$ZUGANG/zugang_id"
    openssl rand -hex 20 | tr -d '\n' > "$ZUGANG/zugang_geheimnis"
  )
fi
chmod 600 "$ZUGANG/zugang_id" "$ZUGANG/zugang_geheimnis"

docker create --name "$NAME" -p 127.0.0.1:7480:7480 --entrypoint /bin/sh "$IMAGE" /ceph-start.sh > /dev/null
docker cp "$HIER/ceph-start.sh" "$NAME:/ceph-start.sh"
docker cp "$ZUGANG" "$NAME:/etc/ceph/zugang"
docker start "$NAME" > /dev/null

for _ in $(seq 1 180); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:7480/ || true)" = 200 ]; then
    echo "Ceph RGW bereit auf http://127.0.0.1:7480"
    exit 0
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$NAME")" != true ]; then
    break
  fi
  sleep 1
done
echo "Ceph RGW nicht bereit"
docker logs --tail 80 "$NAME"
exit 1
