#!/usr/bin/env python3
"""Wertet drei Stufe-A-Stichproben und die manuell geprüften Abbruchkriterien aus."""

import argparse
import csv
import re
import sys
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from speichermessung_stufe_a import SPALTEN

PHASEN = ("leerlauf", "upload", "ocr")
MIB = Decimal(1024 ** 2)


def speicher_bytes(text: str) -> Decimal:
    treffer = re.fullmatch(r"(\d+(?:\.\d+)?)(B|[kKMGTPE]i?B)", text)
    if not treffer:
        raise ValueError("Ungültiger Speicherwert.")
    wert, einheit = treffer.groups()
    if einheit == "B":
        return Decimal(wert)
    potenz = "KMGTPE".index(einheit[0].upper()) + 1
    return Decimal(wert) * (1024 if "i" in einheit else 1000) ** potenz


def cpu_limit(nano: int, periode: int, quote: int) -> Decimal:
    if nano < 0 or periode < 0 or quote < -1:
        raise ValueError("Ungültiges CPU-Limit.")
    per_nano = Decimal(nano) / Decimal(10 ** 9) if nano else None
    per_quote = Decimal(quote) / Decimal(periode or 100000) if quote > 0 else None
    if per_nano is not None and per_quote is not None and per_nano != per_quote:
        raise ValueError("Widersprüchliche CPU-Limits.")
    if per_nano is None and per_quote is None:
        raise ValueError("CPU-Limit fehlt; Budget nicht prüfbar.")
    return per_nano if per_nano is not None else per_quote


def auswerten(pfade: list[Path], vorpruefung: bool = False) -> dict:
    messwerte = {}
    limits = {}
    for pfad in pfade:
        with pfad.open(encoding="utf-8", newline="") as datei:
            leser = csv.DictReader(datei)
            if leser.fieldnames != SPALTEN:
                raise ValueError("Ungültige CSV-Spalten.")
            zeilen = list(leser)
        if not zeilen:
            raise ValueError("Leere Messung.")
        phase = zeilen[0]["phase"]
        if phase not in PHASEN or phase in messwerte:
            raise ValueError("Phase fehlt oder ist doppelt.")
        spitzen, gesehen, anzahl = {}, set(), None
        for zeile in zeilen:
            if None in zeile or any(wert is None for wert in zeile.values()) or zeile["phase"] != phase:
                raise ValueError("Ungültige Messzeile.")
            lauf, n, dienst = (int(zeile[name]) for name in ("lauf", "anzahl", "dienst"))
            if n < 1 or (anzahl is not None and n != anzahl) or not 1 <= lauf <= n or dienst not in (1, 2, 3):
                raise ValueError("Ungültige Laufnummer, Anzahl oder Dienstnummer.")
            anzahl = n
            if (lauf, dienst) in gesehen:
                raise ValueError("Doppelte Messzeile.")
            gesehen.add((lauf, dienst))
            zeit = datetime.fromisoformat(zeile["zeit_utc"])
            if zeit.utcoffset() is None:
                raise ValueError("Zeitstempel ohne Zeitzone.")
            limit, nano, periode, quote = (int(zeile[name]) for name in
                                           ("mem_limit_bytes", "nano_cpus", "cpu_period", "cpu_quota"))
            if limit <= 0:
                raise ValueError("Speicherlimit fehlt; Budget nicht prüfbar.")
            aktuelle_limits = (limit, cpu_limit(nano, periode, quote))
            if dienst in limits and limits[dienst] != aktuelle_limits:
                raise ValueError("Limits änderten sich während der Messung.")
            limits[dienst] = aktuelle_limits
            verbrauch = speicher_bytes(zeile["verbrauch"])
            spitzen[dienst] = max(spitzen.get(dienst, Decimal(0)), verbrauch)
        if len(gesehen) != anzahl * 3:
            raise ValueError("Messung unvollständig.")
        messwerte[phase] = spitzen
    if vorpruefung and len(messwerte) != 1:
        raise ValueError("Die Vorprüfung benötigt genau eine Phase.")
    if not vorpruefung and set(messwerte) != set(PHASEN):
        raise ValueError("Alle drei Phasen sind erforderlich.")
    return {"messwerte": messwerte, "limits": limits}


def abbruch(ergebnis: dict, oom: str, health: str) -> str:
    limits = ergebnis["limits"].values()
    if (sum(limit for limit, _ in limits) > 1000 * MIB
            or sum(cpu for _, cpu in limits) > Decimal("0.75")
            or oom == "ja" or health == "fehler"):
        return "abbruch"
    if oom == "unbekannt" or health == "unbekannt":
        return "ungeklaert"
    return "kein-abbruch"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, nargs="+")
    parser.add_argument("--vorpruefung", action="store_true", help="Eine Phase vor weiterer Last prüfen")
    parser.add_argument("--oom", required=True, choices=["nein", "ja", "manuell", "unbekannt"])
    parser.add_argument("--health", required=True, choices=["ok", "fehler", "unbekannt"])
    args = parser.parse_args()
    try:
        ergebnis = auswerten(args.csv, args.vorpruefung)
    except (OSError, ValueError, csv.Error) as fehler:
        print(f"Auswertung nicht möglich: {fehler}", file=sys.stderr)
        return 2
    print("| Messpunkt | Dienst | Verbrauch | Limit | Anteil |")
    print("|---|---:|---:|---:|---:|")
    for phase in ergebnis["messwerte"]:
        for dienst in (1, 2, 3):
            wert = ergebnis["messwerte"][phase][dienst]
            limit = ergebnis["limits"][dienst][0]
            print(f"| {phase} | {dienst} | {wert / MIB:.1f} MiB | {Decimal(limit) / MIB:.1f} MiB | {wert * 100 / limit:.1f} % |")
        spitzen = sum(ergebnis["messwerte"][phase].values())
        limit_summe = sum(limit for limit, _ in ergebnis["limits"].values())
        print(f"| {phase} | 1–3 | {spitzen / MIB:.1f} MiB | {Decimal(limit_summe) / MIB:.1f} MiB | {spitzen * 100 / limit_summe:.1f} % |")
    cpus = sum(cpu for _, cpu in ergebnis["limits"].values())
    print(f"\nLimit-Summe: {Decimal(limit_summe) / MIB:.1f} MiB; CPU-Summe: {cpus}.")
    stand = abbruch(ergebnis, args.oom, args.health)
    print(f"Abbruchkriterium: {stand}")
    return {"kein-abbruch": 0, "abbruch": 1, "ungeklaert": 2}[stand]


if __name__ == "__main__":
    sys.exit(main())
