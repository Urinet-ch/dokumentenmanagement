#!/usr/bin/env python3
"""Erstellt aus Lastskript-JSON und aufgezeichneten Ressourcenwerten einen Vorprobe-Bericht."""

import argparse
import json
import math
import re
import sys
from decimal import Decimal
from pathlib import Path

EINHEITEN = {
    "B": 1, "kB": 1000, "MB": 1000**2, "GB": 1000**3,
    "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3,
}


def messung(pfad: Path) -> dict:
    daten = json.loads(pfad.read_text(encoding="utf-8"))
    for feld in ("p50_s", "p95_s", "max_s", "grenze_s"):
        wert = daten.get(feld)
        if isinstance(wert, bool) or not isinstance(wert, (int, float)) or not math.isfinite(wert) or wert < 0:
            raise ValueError(f"{pfad}: {feld} fehlt oder ist keine gültige Zeit")
    if not daten["p50_s"] <= daten["p95_s"] <= daten["max_s"]:
        raise ValueError(f"{pfad}: Zeiten sind nicht aufsteigend")
    if not isinstance(daten.get("bestanden"), bool):
        raise ValueError(f"{pfad}: bestanden fehlt")
    if daten["bestanden"] and daten["p95_s"] > daten["grenze_s"]:
        raise ValueError(f"{pfad}: bestanden widerspricht der Grenze")
    return daten


def groesse(text: str) -> Decimal:
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(B|kB|MB|GB|KiB|MiB|GiB)\s*", text)
    if not match:
        raise ValueError(f"Unbekannter Docker-Speicherwert: {text}")
    return Decimal(match[1]) * EINHEITEN[match[2]] / 1024**2


def docker_spitzen(pfad: Path, dienste: list[str]) -> dict[str, tuple[Decimal, Decimal]]:
    spitzen = {}
    for zeile in pfad.read_text(encoding="utf-8").splitlines():
        if not zeile.strip():
            continue
        daten = json.loads(zeile)
        name = daten["Name"]
        if name not in dienste:
            raise ValueError(f"{pfad}: unerwarteter Container {name}")
        speicher = groesse(daten["MemUsage"].split("/", 1)[0])
        cpu_text = daten["CPUPerc"]
        if not re.fullmatch(r"\d+(?:\.\d+)?%", cpu_text):
            raise ValueError(f"{pfad}: ungültiger CPU-Wert für {name}")
        cpu = Decimal(cpu_text[:-1])
        alt = spitzen.get(name, (Decimal(0), Decimal(0)))
        spitzen[name] = (max(alt[0], speicher), max(alt[1], cpu))
    fehlende = set(dienste) - spitzen.keys()
    if fehlende:
        raise ValueError(f"{pfad}: keine Messwerte für {', '.join(sorted(fehlende))}")
    return spitzen


def host_spitzen(pfad: Path) -> tuple[int, int, int]:
    werte = []
    for zeile in pfad.read_text(encoding="utf-8").splitlines():
        teile = zeile.split()
        if teile and teile[0] == "Mem:":
            if len(teile) != 7 or not all(teil.isdecimal() for teil in teile[1:]):
                raise ValueError(f"{pfad}: ungültige «free -m»-Zeile")
            werte.append(tuple(map(int, teile[1:])))
    if not werte:
        raise ValueError(f"{pfad}: keine «free -m»-Messwerte")
    if len({wert[0] for wert in werte}) != 1:
        raise ValueError(f"{pfad}: Host-Gesamtspeicher hat sich geändert")
    return werte[0][0], max(wert[1] for wert in werte), min(wert[5] for wert in werte)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--messung", action="append", required=True, metavar="NAME=DATEI")
    parser.add_argument("--dienst", action="append", required=True, help="vollständiger Containername")
    parser.add_argument("--docker-stats", type=Path, required=True, help="docker stats --format '{{json .}}'")
    parser.add_argument("--host-speicher", type=Path, required=True, help="Ausgabe von free -m")
    parser.add_argument("--ziel", type=Path, required=True)
    args = parser.parse_args()
    try:
        messungen = []
        namen = set()
        for eintrag in args.messung:
            name, trenner, datei = eintrag.partition("=")
            if not trenner or not re.fullmatch(r"[A-Za-z0-9 _.-]+", name) or not datei or name in namen:
                raise ValueError(f"Ungültiger oder mehrfacher Messungsname: {name}")
            namen.add(name)
            messungen.append((name, messung(Path(datei))))
        if len(set(args.dienst)) != len(args.dienst) or any(
                not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) for name in args.dienst):
            raise ValueError("Ungültiger oder mehrfacher Containername")
        spitzen = docker_spitzen(args.docker_stats, args.dienst)
        gesamt, benutzt, verfuegbar = host_spitzen(args.host_speicher)
        zeilen = [
            "# Lastprobe – Messbericht",
            "",
            "Vorprobe mit synthetischen Daten; kein Abnahmenachweis für die VPS.",
            "",
            "## Laufzeiten",
            "",
            "| Test | p50 (s) | p95 (s) | Maximum (s) | Grenze p95 (s) | Ergebnis |",
            "| --- | ---: | ---: | ---: | ---: | --- |",
        ]
        for name, daten in messungen:
            ergebnis = "bestanden" if daten["bestanden"] else "nicht bestanden"
            zeilen.append(f"| {name} | {daten['p50_s']} | {daten['p95_s']} | "
                          f"{daten['max_s']} | {daten['grenze_s']} | {ergebnis} |")
        zeilen.extend(["", "## Spitzen je Container", "", "| Container | Speicher (MiB) | CPU (%) |",
                       "| --- | ---: | ---: |"])
        for name in args.dienst:
            speicher, cpu = spitzen[name]
            zeilen.append(f"| {name} | {speicher:.2f} | {cpu:.2f} |")
        zeilen.extend(["", "## Host-Speicher", "", f"- Gesamt: {gesamt} MiB",
                       f"- Höchste Belegung: {benutzt} MiB", f"- Niedrigste Verfügbarkeit: {verfuegbar} MiB", ""])
        with args.ziel.open("x", encoding="utf-8") as datei:
            datei.write("\n".join(zeilen))
    except (OSError, ValueError, KeyError, TypeError) as fehler:
        print(f"Messbericht nicht erstellt: {fehler}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
