#!/usr/bin/env python3
"""Erfasst Docker- und Host-Speicherwerte für ein eigenes Lastprobe-Projekt."""

import argparse
import json
import re
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

DIENSTE = ("api", "worker", "db", "kc", "s3")
INTERVALL = 5


def aufnehmen(projekt: str) -> tuple[str, str]:
    container = [f"{projekt}-{dienst}-1" for dienst in DIENSTE]
    stats = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{json .}}", *container],
        capture_output=True, text=True, check=True, timeout=INTERVALL,
    )
    zeit = datetime.now(timezone.utc).isoformat()
    zeilen = []
    namen = set()
    for zeile in stats.stdout.splitlines():
        daten = json.loads(zeile)
        name = daten["Name"]
        if name not in container or name in namen:
            raise ValueError("Docker lieferte einen unerwarteten oder mehrfachen Container")
        namen.add(name)
        daten["Zeit"] = zeit
        zeilen.append(json.dumps(daten, ensure_ascii=False))
    if namen != set(container):
        raise ValueError("Docker lieferte nicht alle Container der Lastprobe")
    host = subprocess.run(["free", "-m"], capture_output=True, text=True, check=True, timeout=INTERVALL)
    if not any(zeile.startswith("Mem:") for zeile in host.stdout.splitlines()):
        raise ValueError("free -m lieferte keine Speicherzeile")
    return "\n".join(zeilen) + "\n", f"Zeit: {zeit}\n{host.stdout}\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--projekt", required=True, help="eindeutiger Compose-Projektname")
    parser.add_argument("--docker-stats", type=Path, required=True)
    parser.add_argument("--host-speicher", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"archiv-lastprobe-[0-9a-f]{12}", args.projekt):
        parser.error("--projekt muss archiv-lastprobe- gefolgt von 12 zufälligen Hex-Zeichen sein")
    if args.docker_stats == args.host_speicher:
        parser.error("Die Ausgabedateien müssen verschieden sein")
    stopp = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopp.set())
    signal.signal(signal.SIGINT, lambda *_: stopp.set())
    try:
        with args.docker_stats.open("x", encoding="utf-8") as stats, \
                args.host_speicher.open("x", encoding="utf-8") as host:
            naechste = time.monotonic()
            while not stopp.is_set():
                docker_daten, host_daten = aufnehmen(args.projekt)
                stats.write(docker_daten)
                stats.flush()
                host.write(host_daten)
                host.flush()
                naechste += INTERVALL
                warten = naechste - time.monotonic()
                if warten <= 0:
                    raise ValueError("Die Ressourcenerfassung überschreitet das Fünf-Sekunden-Intervall")
                stopp.wait(warten)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as fehler:
        print(f"Ressourcenerfassung abgebrochen: {fehler}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
