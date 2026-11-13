#!/bin/sh
# Ceph RGW als Einzelcontainer für die Ablage-Tests (.github/workflows/ceph.yml, backend/README.md «Ablage-Tests gegen
# Ceph RGW»): 1 MON, 1 OSD mit memstore (flüchtig, im RAM) und radosgw auf Port 7480. Nur für Tests, ohne
# Authentisierung im Cluster. Wird per «docker cp» in den Container gebracht (kein Bind-Mount, Docker Desktop), dazu
# der Testzugang als Dateien zugang_id und zugang_geheimnis im Verzeichnis ZUGANG (Standard /etc/ceph/zugang).
set -e
FSID=7c9e6679-7425-40de-944b-e07fc1f90ae7
ZUGANG=${ZUGANG:-/etc/ceph/zugang}
S3_UID=${S3_UID:-verwaltung}
ZUGANG_ID=$(cat "$ZUGANG/zugang_id")
ZUGANG_GEHEIMNIS=$(cat "$ZUGANG/zugang_geheimnis")
mkdir -p /var/lib/ceph/mon/ceph-a /var/lib/ceph/osd/ceph-0 /var/run/ceph /var/log/ceph
cat > /etc/ceph/ceph.conf <<C
[global]
fsid = $FSID
mon_host = v2:127.0.0.1:3300
auth_cluster_required = none
auth_service_required = none
auth_client_required = none
osd_pool_default_size = 1
osd_pool_default_min_size = 1
mon_allow_pool_size_one = true
mon_warn_on_pool_no_redundancy = false
osd_pool_default_pg_num = 8
osd_pool_default_pgp_num = 8
osd_pool_default_pg_autoscale_mode = off
mon_max_pg_per_osd = 1000
mon_data_avail_warn = 1
ms_mon_client_mode = crc
ms_client_mode = crc
osd_crush_chooseleaf_type = 0
osd_objectstore = memstore
memstore_device_bytes = ${MEMSTORE_BYTES:-1073741824}
log_to_file = false
log_to_stderr = false
[mon.a]
mon_data = /var/lib/ceph/mon/ceph-a
[osd.0]
osd_data = /var/lib/ceph/osd/ceph-0
[client.rgw]
rgw_frontends = beast port=7480
rgw_enable_usage_log = false
log_to_stderr = true
err_to_stderr = true
debug_rgw = 1
C
monmaptool --create --addv a "[v2:127.0.0.1:3300]" --fsid $FSID /tmp/monmap >/dev/null
ceph-mon --mkfs -i a --monmap /tmp/monmap
ceph-mon -i a
OSD=$(ceph osd create)
ceph-osd -i "$OSD" --mkfs >/dev/null 2>&1
ceph-osd -i "$OSD"
until ceph osd stat 2>/dev/null | grep -q '1 up'; do sleep 0.5; done
ceph osd stat
# Benutzer erst, wenn die Pools der RGW anlegbar sind; Ausgabe verworfen (enthält den Zugang)
n=0
until radosgw-admin user info --uid="$S3_UID" >/dev/null 2>&1 \
  || radosgw-admin user create --uid="$S3_UID" --display-name=Verwaltung \
       --access-key="$ZUGANG_ID" --secret-key="$ZUGANG_GEHEIMNIS" >/dev/null 2>&1; do
  n=$((n + 1))
  if [ $n -gt 20 ]; then ceph -s; ceph health detail; exit 1; fi
  sleep 2
done
echo "RGW-BEREIT-VOR-START"
exec radosgw -f -n client.rgw
