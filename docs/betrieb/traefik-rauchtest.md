# Lokaler Traefik-Rauchtest

Der Rauchtest prüft die Router-Regeln aus `deploy/infomaniak/compose.yml` mit
Traefik `v3.5`. Er ist ein lokaler Laufzeitnachweis, kein VPS- oder Staging-Test.
Er verwendet keine Archiv- oder Keycloak-Images, TLS oder ACME. Die Dienste
werden durch synthetische Python-HTTP-Attrappen in einem Docker-Bridge-Netz ohne
IP-Masquerading ersetzt.
Nur der zufällige Testport von Traefik wird an `127.0.0.1` veröffentlicht.

Voraussetzungen sind Docker Desktop mit laufendem Docker-Daemon und Docker
Compose. Falls nötig, lädt Docker die Images `traefik:v3.5` und
`python:3.13-alpine`. Aufruf im Repository:

```sh
python3 tools/traefik_rauchtest.py
```

Für eine andere Compose-Datei:

```sh
python3 tools/traefik_rauchtest.py --compose /absoluter/pfad/compose.yml
```

Docker Compose wertet die Compose-Datei ohne Interpolation aus. Das Skript
übernimmt Router-Regeln, Middleware-Definitionen und Reihenfolge, setzt den
lokalen HTTP-Einstiegspunkt und ersetzt die Load-Balancer-Ziele durch
synthetische Attrappen. `forceSTSHeader` wird nur in der Testkonfiguration
aktiviert, weil dieser lokale Lauf ohne TLS stattfindet; die Produktionslabels
bleiben unverändert. Rate-Limits werden im Test auf eine Anfrage pro Sekunde
mit Burst eins reduziert, damit pro Router unmittelbar eine echte 429-Antwort
entsteht.

Für jeden Router prüft das Skript, dass eine HTTP-200-Antwort von der richtigen
Attrappe kommt und HSTS trägt, und dass die nachfolgende gedrosselte 429-Antwort
ebenfalls HSTS trägt. Eine separate Gegenprobe vertauscht beim ersten
HSTS-/Drosselungs-Paar die Reihenfolge und verlangt, dass die fehlende HSTS-
Kopfzeile auf der 429-Antwort erkannt wird.

Die dynamische Konfiguration wird mit `docker cp` in den Traefik-Container
kopiert; es gibt keine Bind-Mounts. Das Netz bietet den Containern keine
ausgehende NAT-Verbindung. Beim Beenden
entfernt das Skript ausschliesslich die von diesem Lauf benannten Container
und das eigene Netz. Bei einem Abbruch (z. B. `SIGKILL`) können diese Ressourcen
zurückbleiben; die Namen enthalten eine eindeutige Lauf-ID. Keine allgemeinen
Docker-Aufräumbefehle wie `docker prune` verwenden.

Die Ableitungstests benötigen Docker nicht:

```sh
python3 -m unittest tools/test_traefik_rauchtest.py
```

Ein Vorschlag, nur diese Unit-Tests in der CI aufzurufen, liegt in
[`traefik-rauchtest-ci.diff`](./traefik-rauchtest-ci.diff). Der Laufzeit-Rauchtest
selbst wird nicht in die CI eingebunden.
