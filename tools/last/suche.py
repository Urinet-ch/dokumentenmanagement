#!/usr/bin/env python3
"""Lastskript für Volltext- und Hybridsuche (Spec 01, Req 14.3; Spec 02, Req 2.6).

Schickt Suchanfragen parallel an GET /api/v1/dokumente und gibt p50, p95 und Maximum der Antwortzeit aus. Gemessen
wird ab gesendeter Anfrage bis zur vollständig gelesenen Antwort (die Netzlaufzeit ist enthalten). Für 50'000
Dokumente gilt p95 ≤ 1 Sekunde im Volltextmodus und p95 ≤ 2 Sekunden im Hybridmodus; der Nachweis erfolgt erst auf
Stufe B in Aufgabe 5.11. Anzahl und Parallelität sind Pflichtparameter ohne Standardwerte. `--aufwaermen` schickt
vorher Suchen, die nicht zählen.

Das Skript liest nur. Der Mandant muss vorher mit synthetischen Dokumenten gefüllt sein (für Req 14.3: 50'000); die
Zahl der für das Token sichtbaren Dokumente steht in der Ausgabe. Die Suchbegriffe stammen aus einer festen Liste
synthetischer Wörter oder aus einer Datei (ein Begriff je Zeile); nie echte Belegdaten verwenden. Die feste Liste
enthält auch Wörter, die nur in den synthetischen Dateinamen der Füllung stehen, damit die Messung den Teil des Index
für den Dateinamen abdeckt (Req 14.8, Aufgabe 4.10); eigene `--begriffe` sollten ebenso solche Wörter enthalten. Die Ausgabe nennt
keine Suchbegriffe.

Token (Regel 7): ein Access-Token (OIDC) mit Recht A2 aus einer Datei mit Rechten 600, Pfad aus ARCHIV_TOKEN_DATEI,
sonst ~/.config/urinet-archiv/token; nie das Token selbst aus einer Umgebungsvariable.

Beispiel:
    python3 tools/last/suche.py --url https://archiv.apps.urinet.ch --anzahl 500 --parallel 4
"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import os
import random
import stat
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlencode, urlsplit

WOERTER = [
    "Rechnung", "Lieferung", "Trikots", "Clubhaus", "Beleuchtung", "Wartung", "Netzwerk", "Server", "Drucker",
    "Offerte", "Vertrag", "Versicherung", "Lohn", "Spesen", "Miete", "Reparatur", "Installation", "Schulung",
    "Beratung", "Material", "Kabel", "Lizenz", "Abonnement", "Mahnung", "Gutschrift", "Projekt", "Webseite", "Hosting",
    # nur in den synthetischen Dateinamen der Füllung (Req 14.8, Aufgabe 4.10; Füllung des Mandanten `last` in 5.11)
    "Scan", "Kassenzettel", "Fotobeleg", "Ablage", "Einzahlung",
]
ARTEN = ["AUSGANGSRECHNUNG", "LIEFERANTENBELEG", "OFFERTE", "QUITTUNG", "VERTRAG", "KORRESPONDENZ"]


def token_lesen() -> str:
    pfad = Path(os.environ.get("ARCHIV_TOKEN_DATEI") or Path.home() / ".config" / "urinet-archiv" / "token")
    if not pfad.is_file():
        sys.exit(f"Keine Token-Datei unter {pfad}. Bitte das Token dort ablegen (Rechte 600).")
    if stat.S_IMODE(pfad.stat().st_mode) & 0o077:
        sys.exit(f"Die Token-Datei {pfad} ist für andere lesbar. Bitte mit «chmod 600 {pfad}» schützen.")
    token = pfad.read_text(encoding="utf-8").strip()
    if not token:
        sys.exit(f"Die Token-Datei {pfad} ist leer.")
    return token


def begriffe_lesen(datei: str | None) -> list[str]:
    if datei is None:
        return WOERTER
    zeilen = [z.strip() for z in Path(datei).read_text(encoding="utf-8").splitlines()]
    begriffe = [z for z in zeilen if z and not z.startswith("#")]
    if not begriffe:
        sys.exit(f"Die Datei {datei} enthält keine Suchbegriffe.")
    return begriffe


def parameter(nummer: int, begriffe: list[str], zufall: random.Random, modus: str = "volltext") -> dict[str, str]:
    """Volltext mischt auch Filter ohne Suchbegriff; hybrid enthält immer einen Suchbegriff."""
    begriff = zufall.choice(begriffe)
    art = nummer % (4 if modus == "hybrid" else 5)
    if art == 0:
        werte = {"q": begriff}
    elif art == 1:
        werte = {"q": f"{begriff} {zufall.choice(begriffe)}"}
    elif art == 2:
        werte = {"q": f'"{begriff} {zufall.choice(begriffe)}" -{zufall.choice(begriffe)}'}
    elif art == 3:
        werte = {"q": begriff, "art": zufall.choice(ARTEN), "datumVon": "2024-01-01", "richtung": "EINNAHME"}
    else:
        werte = {"art": zufall.choice(ARTEN), "geschaeftsjahr": str(zufall.choice([2023, 2024, 2025, 2026]))}
    return {"modus": modus, **werte}


def suchen(url: str, token: str, werte: dict[str, str]) -> tuple[int, float, int | None]:
    teile = urlsplit(url)
    klasse = http.client.HTTPSConnection if teile.scheme == "https" else http.client.HTTPConnection
    verbindung = klasse(teile.netloc, timeout=60)
    try:
        pfad = teile.path.rstrip("/") + "/api/v1/dokumente?" + urlencode(werte)
        start = time.perf_counter()
        verbindung.request("GET", pfad, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
        antwort = verbindung.getresponse()
        koerper = antwort.read()
        dauer = time.perf_counter() - start
        gesamt = json.loads(koerper).get("gesamt") if antwort.status == 200 else None
        return antwort.status, dauer, gesamt
    except (OSError, ValueError):
        return 0, 0.0, None
    finally:
        verbindung.close()


def perzentil(werte: list[float], p: float) -> float:
    """Nearest-Rank-Perzentil."""
    geordnet = sorted(werte)
    return geordnet[max(0, math.ceil(p / 100 * len(geordnet)) - 1)]


def main() -> int:
    parser = argparse.ArgumentParser(description="Lastskript Volltext- und Hybridsuche, Nachweis in Aufgabe 5.11")
    parser.add_argument("--url", required=True, help="Basis-URL des Archivs, z. B. https://archiv.apps.urinet.ch")
    parser.add_argument("--anzahl", type=int, required=True, help="Anzahl Suchanfragen")
    parser.add_argument("--parallel", type=int, required=True, help="gleichzeitige Suchanfragen")
    parser.add_argument("--modus", choices=("volltext", "hybrid"), default="volltext",
                        help="Suchmodus (Standard: volltext)")
    parser.add_argument("--begriffe", help="Datei mit synthetischen Suchbegriffen, einer je Zeile")
    parser.add_argument("--aufwaermen", type=int, default=0, help="Suchen vorweg, die nicht zählen")
    parser.add_argument("--grenze", type=float, help="Grenze für p95 in Sekunden (Standard: volltext 1, hybrid 2)")
    parser.add_argument("--seed", type=int, default=143, help="Startwert für die Auswahl der Anfragen")
    parser.add_argument("--json", action="store_true", help="Ergebnis als JSON ausgeben")
    args = parser.parse_args()
    if args.anzahl < 1 or args.parallel < 1:
        parser.error("--anzahl und --parallel müssen grösser als 0 sein")
    if args.aufwaermen < 0:
        parser.error("--aufwaermen muss mindestens 0 sein")

    token = token_lesen()
    begriffe = begriffe_lesen(args.begriffe)
    status, _, sichtbar = suchen(args.url, token, {"modus": "volltext", "groesse": "1"})
    if status != 200:
        sys.exit(f"Die Suche antwortet mit Status {status}. Bitte URL und Token (Recht A2) prüfen.")
    grenze = args.grenze if args.grenze is not None else (2.0 if args.modus == "hybrid" else 1.0)
    zufall = random.Random(args.seed)
    # Gemessene Anfragen hängen nur vom Seed ab, nicht von der Zahl der Aufwärm-Anfragen
    anfragen = [parameter(n, begriffe, zufall, args.modus) for n in range(args.anzahl)]
    zufall_vorweg = random.Random(args.seed + 1)
    aufwaermen = [parameter(n, begriffe, zufall_vorweg, args.modus) for n in range(args.aufwaermen)]
    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        list(pool.map(lambda w: suchen(args.url, token, w), aufwaermen))
        ergebnisse = list(pool.map(lambda w: suchen(args.url, token, w), anfragen))

    zeiten = [dauer for status, dauer, _ in ergebnisse if status == 200]
    fehler: dict[int, int] = {}
    for status, _, _ in ergebnisse:
        if status != 200:
            fehler[status] = fehler.get(status, 0) + 1
    bericht = {
        "anzahl": args.anzahl,
        "parallel": args.parallel,
        "aufwaermen": args.aufwaermen,
        "modus": args.modus,
        "sichtbare_dokumente": sichtbar,
        "beantwortet": len(zeiten),
        "fehler": {str(status): zahl for status, zahl in sorted(fehler.items())},
        "p50_s": round(statistics.median(zeiten), 3) if zeiten else None,
        "p95_s": round(perzentil(zeiten, 95), 3) if zeiten else None,
        "max_s": round(max(zeiten), 3) if zeiten else None,
        "grenze_s": grenze,
    }
    bericht["bestanden"] = not fehler and bericht["p95_s"] is not None and bericht["p95_s"] <= grenze
    if args.json:
        print(json.dumps(bericht, ensure_ascii=False))
    else:
        print(f"Suchen: {args.anzahl} (parallel {args.parallel}), sichtbare Dokumente: {sichtbar}, "
              f"beantwortet: {len(zeiten)}")
        if fehler:
            # Status 0: keine Antwort (Verbindung abgebrochen oder Zeitüberschreitung)
            print("Fehler nach Status: " + ", ".join(f"{s}: {z}" for s, z in sorted(fehler.items())))
        if zeiten:
            print(f"p50 {bericht['p50_s']} s, p95 {bericht['p95_s']} s, max {bericht['max_s']} s "
                  f"(Grenze p95 {grenze} s)")
        print("bestanden" if bericht["bestanden"] else "nicht bestanden")
    return 0 if bericht["bestanden"] else 1


if __name__ == "__main__":
    sys.exit(main())
