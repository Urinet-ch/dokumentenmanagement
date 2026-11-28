#!/usr/bin/env python3
"""Wertet Differenzen interner HTTP-Serverhistogramme aus; keine Netzwerkverbindung."""

import argparse
import math
import re
from pathlib import Path

MUSTER = re.compile(r'^(http_server_requests_seconds_(?:bucket|count|sum))\{([^}]+)\}\s+([0-9.eE+-]+)$')


def lesen(text, methode, status):
    werte = {}
    for zeile in text.splitlines():
        match = MUSTER.fullmatch(zeile)
        if not match:
            continue
        labels = dict(re.findall(r'(\w+)="([^"]*)"', match[2]))
        if labels.get("uri") != "/api/v1/dokumente" or labels.get("method") != methode:
            continue
        # Jede Antwort ausser dem erwarteten Erfolg ist ein Messfehler.
        schluessel = (match[1].rsplit("_", 1)[1], labels.get("le", ""), labels.get("status", ""))
        wert = float(match[3])
        if not math.isfinite(wert) or wert < 0 or schluessel in werte:
            raise ValueError("Ungültige oder mehrdeutige Histogrammreihe.")
        werte[schluessel] = wert
    if not werte:
        raise ValueError("HTTP-Serverhistogramm fehlt.")
    return werte


def auswerten(vorher, nachher, modus, sekunden):
    methode, status, anzahl, grenze = {
        "upload-klein": ("POST", "202", 200, 2),
        "upload-gross": ("POST", "202", 50, 2),
        "suche": ("GET", "200", 500, 1),
    }[modus]
    if not math.isfinite(sekunden) or sekunden <= 0:
        raise ValueError("Messfenster muss endlich und positiv sein.")
    alt = lesen(vorher, methode, status)
    neu = lesen(nachher, methode, status)
    delta = {key: neu.get(key, 0) - alt.get(key, 0) for key in alt.keys() | neu.keys()}
    if any(wert < 0 for wert in delta.values()):
        raise ValueError("Zähler zurückgesetzt; kein Messnachweis.")
    if any(wert > 0 and key[2] != status and key[0] == "count" for key, wert in delta.items()):
        raise ValueError("Fehlerantworten im Messfenster.")
    n = delta.get(("count", "", status))
    if n != anzahl:
        raise ValueError("Anzahl passt nicht zum Messlauf; Aufwärmen getrennt erfassen.")
    # Prometheus kann ganzzahlige Bucketgrenzen als 1.0 oder 1 exportieren.
    buckets = {float(k[1]): v for k, v in delta.items() if k[0] == "bucket" and k[2] == status}
    if grenze not in buckets or math.inf not in buckets or buckets[math.inf] != n:
        raise ValueError("Erforderliche Histogrammgrenzen fehlen.")
    geordnet = sorted(buckets.items())
    if any(v < 0 or v > n or v != int(v) for _, v in geordnet) or any(
            a[1] > b[1] for a, b in zip(geordnet, geordnet[1:])):
        raise ValueError("Ungültige kumulative Buckets.")
    rang = math.ceil(0.95 * n)
    p95 = next(g for g, zahl in geordnet if zahl >= rang)
    # Req 3.2 nennt «innert 2 Sekunden», nicht p95.
    benoetigt = rang if modus == "suche" else n
    return {"anzahl": int(n), "p95_obere_grenze_s": p95,
            "durchsatz_pro_s": n / sekunden, "innerhalb_ziel": int(buckets[grenze]),
            "bestanden": buckets[grenze] >= benoetigt}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("modus", choices=["upload-klein", "upload-gross", "suche"])
    parser.add_argument("vorher", type=Path)
    parser.add_argument("nachher", type=Path)
    parser.add_argument("--fenster-s", required=True, type=float)
    args = parser.parse_args()
    try:
        bericht = auswerten(args.vorher.read_text(), args.nachher.read_text(), args.modus, args.fenster_s)
    except (OSError, ValueError):
        parser.exit(2, "Serverauswertung unvollständig oder ungültig; Messfenster und Histogramme prüfen.\n")
    for name, wert in bericht.items():
        print(f"{name}: {wert}")
    return 0 if bericht["bestanden"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
