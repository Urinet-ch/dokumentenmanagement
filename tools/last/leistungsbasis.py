#!/usr/bin/env python3
"""Auswertung der lokalen Leistungsbasis; keine Verbindung zu einem Archiv."""

import csv
import math
import sys
from pathlib import Path

from suche import perzentil

ABLAEUFE = ("suche", "eingangskorb", "dokument", "chat")
VERGLEICHSZIELE_MS = {"suche": 1000, "chat": 10000}


def auswerten(zeilen):
    if any(z["ablauf"] not in ABLAEUFE for z in zeilen):
        raise ValueError("Unbekannter Ablauf in der Messung")
    ergebnis = []
    for ablauf in ABLAEUFE:
        proben = [z for z in zeilen if z["ablauf"] == ablauf]
        if not proben:
            raise ValueError(f"Keine Messwerte für {ablauf}")
        if any(z["erfolg"] not in ("true", "false") for z in proben):
            raise ValueError(f"Ungültiger Erfolgswert für {ablauf}")
        zeiten = [float(z["dauer_ms"]) for z in proben if z["erfolg"] == "true"]
        if any(not math.isfinite(w) or w < 0 for w in zeiten):
            raise ValueError(f"Ungültige Laufzeit für {ablauf}")
        fehler = len(proben) - len(zeiten)
        ergebnis.append((ablauf, len(proben), fehler, 100 * fehler / len(proben),
                         perzentil(zeiten, 50) if zeiten else None,
                         perzentil(zeiten, 95) if zeiten else None))
    return ergebnis


def main():
    if len(sys.argv) != 2:
        sys.exit("Aufruf: python3 tools/last/leistungsbasis.py <roh-csv>")
    datei = Path(sys.argv[1])
    with datei.open(encoding="utf-8", newline="") as eingabe:
        ergebnis = auswerten(list(csv.DictReader(eingabe)))
    ziel = datei.with_name("leistungsbasis.csv")
    with ziel.open("w", encoding="utf-8", newline="") as ausgabe:
        writer = csv.writer(ausgabe)
        writer.writerow(("ablauf", "anzahl", "fehler", "fehlerquote_prozent", "p50_ms", "p95_ms",
                         "vergleichsziel_p95_ms"))
        for ablauf, anzahl, fehler, quote, p50, p95 in ergebnis:
            werte = (ablauf, anzahl, fehler, f"{quote:.2f}",
                     f"{p50:.2f}" if p50 is not None else "",
                     f"{p95:.2f}" if p95 is not None else "", VERGLEICHSZIELE_MS.get(ablauf, ""))
            writer.writerow(werte)
            print(f"{ablauf}: n={anzahl}, Fehler={fehler} ({quote:.2f} %), "
                  f"p50={werte[4] or '–'} ms, p95={werte[5] or '–'} ms, "
                  f"Vergleichsziel p95={werte[6] or '–'} ms")
    print(f"CSV: {ziel}; lokaler Vergleich, kein Abnahmenachweis.")


if __name__ == "__main__":
    main()
