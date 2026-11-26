#!/usr/bin/env python3
"""Wertet die CSV der Foto-Messung mit nächstem Rang aus."""

import argparse
import csv
import math
from pathlib import Path


def auswerten(pfad: Path) -> dict:
    with pfad.open(encoding="utf-8", newline="") as datei:
        leser = csv.DictReader(datei)
        if leser.fieldnames != ["lauf", "status", "dauer_s"]:
            raise ValueError("Ungültige CSV-Spalten.")
        zeilen = list(leser)
    if not zeilen:
        raise ValueError("Keine Messläufe.")
    werte = []
    fehler = 0
    for nr, zeile in enumerate(zeilen, 1):
        if zeile["lauf"] != str(nr):
            raise ValueError("Laufnummern müssen lückenlos bei 1 beginnen.")
        if zeile["status"] == "fehler" and zeile["dauer_s"] == "":
            fehler += 1
        elif zeile["status"] == "ok":
            wert = float(zeile["dauer_s"])
            if not math.isfinite(wert) or wert < 0:
                raise ValueError("Ungültige Laufdauer.")
            werte.append(wert)
        else:
            raise ValueError("Ungültiger Status oder Fehler mit Dauer.")
    werte.sort()
    return {
        "laeufe": len(zeilen),
        "fehler": fehler,
        "fehlerquote_prozent": 100 * fehler / len(zeilen),
        "p50_s": werte[math.ceil(0.5 * len(werte)) - 1] if werte else None,
        "p95_s": werte[math.ceil(0.95 * len(werte)) - 1] if werte else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    args = parser.parse_args()
    ergebnis = auswerten(args.csv)
    for name, wert in ergebnis.items():
        print(f"{name}: {wert if wert is not None else 'nicht verfügbar'}")
    if ergebnis["fehler"]:
        print("Unvollständige Messung: Perzentile nur über erfolgreiche Läufe, kein Leistungsnachweis.")


if __name__ == "__main__":
    main()
