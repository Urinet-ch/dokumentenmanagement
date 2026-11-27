# ZAP-Baseline gegen eine laufende Instanz

Diese Anleitung bereitet den manuellen Nachweis zu Spec 03, Aufgabe 12.3 vor.
Nur ein ausdrücklich freigegebenes Ziel verwenden. Der Scan läuft ohne Anmeldung
und ersetzt keine Prüfung geschützter Funktionen. `-S` schaltet den aktiven Scan
ab; beim Import des API-Vertrags können trotzdem HTTP-Anfragen an die
beschriebenen Endpunkte erfolgen. Vorher Auswirkungen und Freigabe prüfen.

## Vorbereitung

Docker und die beiden OpenAPI-Dateien im aktuellen Worktree müssen vorhanden
sein. Ein neues Ausgabeverzeichnis **ausserhalb aller Repositorys** anlegen;
Berichte und Logs können Anfragen und Antworten enthalten:

```sh
umask 077
ausgabe=/absoluter/pfad/ausserhalb/aller-repositorys/zap-ergebnis
mkdir -m 700 "$ausgabe"
```

Die folgenden Architektur-Digests entsprechen
[`zap-baseline-lokal.sh`](../../tools/sicherheit/zap-baseline-lokal.sh):

```sh
case "$(docker info --format '{{.Architecture}}')" in
  aarch64|arm64) digest=sha256:0b8847b2c722b16f0964431aabcbb499560a1f17e8698d52326d82db29efac1d ;;
  x86_64|amd64) digest=sha256:c2d63bbb2d907a5c5257914fb20e26e969c92b25f265def7f89f08203f912882 ;;
  *) echo "Nicht unterstützte Docker-Architektur." >&2; exit 1 ;;
esac
bild="ghcr.io/zaproxy/zaproxy@$digest"
docker pull "$bild"
```

## Passiver Scan

`<ziel-adresse>` durch die freigegebene Zieladresse ersetzen. Die Kommandos
verwenden den aktuellen Worktree und importieren beide Verträge separat. Sie
starten weder den aktiven Scan noch übermitteln sie Zugangsdaten:

```sh
(
set -eu
ziel='https://<ziel-adresse>'
ergebnis=0
for schnittstelle in app webseite; do
  case "$schnittstelle" in
    app) vertrag=frontend/openapi/openapi.yaml ;;
    webseite) vertrag=docs/schnittstelle/public-v1.yaml ;;
  esac
  container=$(docker create --workdir /zap --entrypoint /bin/sh "$bild" \
    -c 'mkdir -p /zap/wrk; exec /zap/zap-api-scan.py "$@"' sh \
    -S -f openapi -t /zap/vertrag.yaml -O "$ziel" \
    -J bericht.json -w bericht.md -r bericht.html -T 5 -s)
  printf 'Temporärer Container: %s\n' "$container"
  docker cp "$vertrag" "$container:/zap/vertrag.yaml"
  status=0
  docker start -a "$container" >"$ausgabe/$schnittstelle.log" 2>&1 || status=$?
  printf '%s: Exit %s\n' "$schnittstelle" "$status"
  if [ "$status" -le 2 ]; then
    for bericht in bericht.json bericht.md bericht.html; do
      docker cp "$container:/zap/wrk/$bericht" "$ausgabe/$schnittstelle-$bericht"
      chmod 600 "$ausgabe/$schnittstelle-$bericht"
    done
  else
    echo "Scan fehlgeschlagen; geschütztes Log prüfen." >&2
  fi
  if [ "$status" -gt 2 ]; then
    ergebnis=3
  elif [ "$status" -eq 1 ] && [ "$ergebnis" -ne 3 ]; then
    ergebnis=1
  elif [ "$status" -eq 2 ] && [ "$ergebnis" -eq 0 ]; then
    ergebnis=2
  fi
  docker rm "$container"
done
exit "$ergebnis"
)
```

Exit-Codes von ZAP: `0` keine Warnung, `1` mindestens ein FAIL, `2` mindestens
ein WARN ohne FAIL, `3` Ausführungsfehler. Ein fehlender Bericht, ein Fehler
beim Kopieren oder ein abgebrochener Lauf ist kein erfolgreicher Nachweis.
Logs und Berichte bleiben geschützt ausserhalb des Repositorys.
Bei einem Abbruch vor `docker rm` den angelegten Container gezielt entfernen;
seine ID wird beim Anlegen ausgegeben.

## Fehlerbilder und Ergebnis

- `No space left on device`: Docker-Plattenbelegung mit `docker system df`
  prüfen. Bei voller Docker-Disk kontrolliert Platz schaffen und erneut
  starten; keine pauschale Bereinigung ausführen.
- `Failed to connect to ZAP after 300 seconds`: Startprotokoll im geschützten
  Ausgabeverzeichnis prüfen; Ursache und Ressourcenverfügbarkeit abklären.
  Der Lauf ist kein Nachweis.
- Ergebnisse nur als Regelnummer, Schweregrad und Anzahl festhalten, ohne
  Zieladresse, IDs, Inhalte oder Zugangsdaten. Beispiel vom 26.11.2026:
  0 FAIL; Warnungen 10035 (HSTS), 10021 und 90004. Die Zahl der Warnungen je
  Regel ist hier nicht geprüft.

Der Lauf ersetzt weder authentisierte Tests noch die fachliche Abnahme.
