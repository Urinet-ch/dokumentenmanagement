#!/usr/bin/env python3
"""Erfasst eine Phase der Stufe-A-Speichermessung ohne Containerkennungen in der Ausgabe."""

import argparse
import csv
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

SPALTEN = ["phase", "lauf", "anzahl", "zeit_utc", "dienst", "verbrauch",
           "mem_limit_bytes", "nano_cpus", "cpu_period", "cpu_quota"]
SPEICHER = re.compile(r"^(\d+(?:\.\d+)?(?:B|[kKMGTPE]i?B))\s*/\s*\d+(?:\.\d+)?(?:B|[kKMGTPE]i?B)$")


def docker(*argumente: str) -> str:
    try:
        ergebnis = subprocess.run(["docker", *argumente], text=True, capture_output=True, check=False, timeout=60)
    except subprocess.TimeoutExpired as fehler:
        raise RuntimeError(f"docker {argumente[0]} nach 60 Sekunden abgebrochen.") from fehler
    if ergebnis.returncode:
        raise RuntimeError(f"docker {argumente[0]} fehlgeschlagen: {ergebnis.stderr.strip()}")
    return ergebnis.stdout.strip()


def messen(phase: str, pfad: Path, anzahl: int, intervall: float, container: list[str]) -> None:
    if anzahl < 1 or not 0 <= intervall <= 3600:
        raise ValueError("Anzahl muss positiv und Intervall zwischen 0 und 3600 Sekunden sein.")
    if len(set(container)) != 3 or any(not re.fullmatch(r"[a-f0-9]{12,64}", wert) for wert in container):
        raise ValueError("Drei verschiedene Docker-Container-IDs (12 bis 64 Hexzeichen) erforderlich.")

    fd = os.open(pfad, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="") as datei:
        os.fchmod(datei.fileno(), 0o600)
        csv_schreiber = csv.DictWriter(datei, fieldnames=SPALTEN)
        csv_schreiber.writeheader()
        for lauf in range(1, anzahl + 1):
            for dienst, kennung in enumerate(container, 1):
                inspektion = docker("inspect", "--format",
                                    '{{index .Config.Labels "com.docker.compose.project"}} '
                                    '{{index .Config.Labels "com.docker.compose.service"}} '
                                    "{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}} "
                                    "{{.HostConfig.CpuPeriod}} {{.HostConfig.CpuQuota}}", kennung).split()
                if inspektion[:2] != ["archiv", ("api", "kc", "db")[dienst - 1]]:
                    raise ValueError(f"Container {dienst} ist nicht der erwartete Archiv-Dienst.")
                limits = inspektion[2:]
                if len(limits) != 4 or any(not re.fullmatch(r"-?\d+", wert) for wert in limits):
                    raise ValueError(f"Ungültige Limit-Antwort für Dienst {dienst}.")
                stats = docker("stats", "--no-stream", "--format", "{{.MemUsage}}", kennung)
                treffer = SPEICHER.fullmatch(stats)
                if not treffer:
                    raise ValueError(f"Ungültige Speicher-Antwort für Dienst {dienst}.")
                csv_schreiber.writerow(dict(zip(SPALTEN, [
                    phase, lauf, anzahl, datetime.now(timezone.utc).isoformat(), dienst,
                    treffer.group(1), *limits,
                ])))
                datei.flush()
            if lauf < anzahl:
                time.sleep(intervall)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["leerlauf", "upload", "ocr"])
    parser.add_argument("ausgabe", type=Path)
    parser.add_argument("--anzahl", type=int, required=True)
    parser.add_argument("--intervall", type=float, required=True, help="Sekunden zwischen Messrunden")
    parser.add_argument("--container", nargs=3, required=True, metavar=("API", "KC", "DB"))
    args = parser.parse_args()
    try:
        messen(args.phase, args.ausgabe, args.anzahl, args.intervall, args.container)
    except (OSError, ValueError, RuntimeError) as fehler:
        print(f"Messung abgebrochen: {fehler}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
