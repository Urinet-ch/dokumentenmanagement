#!/usr/bin/env python3
"""Wertet serverseitige Auftragszeitstempel bei deaktivierter KI-Wartezeit aus."""

import argparse
import csv
import math
from datetime import datetime
from pathlib import Path

from suche import perzentil


def auswerten(zeilen):
    if len(zeilen) != 50:
        raise ValueError("Genau 50 Messaufträge ohne Aufwärmen erforderlich.")
    zeiten = []
    ids = set()
    for z in zeilen:
        if z["status"] != "FERTIG" or z["versuche"] != "0" or z["ki_stub"] != "true" or z["auftraege"] != "2":
            raise ValueError("Kein erfolgreicher Erstversuch ohne KI-Wartezeit.")
        if z["id"] in ids:
            raise ValueError("Auftrag doppelt.")
        ids.add(z["id"])
        if not all(isinstance(z.get(feld), str) for feld in ("gestartet", "beendet")):
            raise ValueError("Auftragszeitstempel fehlen.")
        start, ende = datetime.fromisoformat(z["gestartet"]), datetime.fromisoformat(z["beendet"])
        if start.tzinfo is None or ende.tzinfo is None:
            raise ValueError("UTC-Zeitstempel mit Zeitzone erforderlich.")
        dauer = (ende - start).total_seconds()
        if not math.isfinite(dauer) or dauer < 0:
            raise ValueError("Ungültige Auftragsdauer.")
        zeiten.append(dauer)
    return {"anzahl": 50, "p50_s": perzentil(zeiten, 50),
            "p95_s": perzentil(zeiten, 95), "bestanden": perzentil(zeiten, 95) <= 30}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    args = parser.parse_args()
    try:
        with args.csv.open(newline="") as datei:
            bericht = auswerten(list(csv.DictReader(datei)))
    except (OSError, ValueError, KeyError):
        parser.exit(2, "Auftragsauswertung ungültig; Erstversuche, KI-Modus und Zeitstempel prüfen.\n")
    for name, wert in bericht.items():
        print(f"{name}: {wert}")
    return 0 if bericht["bestanden"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
