#!/usr/bin/env python3
"""Lastskript für den Upload (Spec 01, Req 3.2, Aufgabe 2.4).

Lädt synthetische PDFs parallel an POST /api/v1/dokumente hoch und gibt p50, p95 und Maximum der Antwortzeit aus.
Gemessen wird ab vollständig gesendeter Anfrage bis zum Eintreffen der Antwort (nahe am Messpunkt «Server, ab
vollständig empfangener Anfrage»; die Netzlaufzeit ist enthalten). Der Nachweis erfolgt erst auf Stufe B in Aufgabe
5.11; Eingabe, Last und Hardware sind dort festzulegen ([offen: Messbedingungen nach VPS-Grösse K5]). Darum sind
Anzahl, Parallelität und Grösse Pflichtparameter ohne Standardwerte.

Nur synthetische Dateien, nie echte Belege. Jede Datei ist eindeutig, es entstehen keine Dubletten; die angelegten
Dokumente bleiben im Eingangskorb des Mandanten (nur Stufe A oder eine Testumgebung).

Token (Regel 7): ein Access-Token (OIDC) oder API-Token mit Recht A1 aus einer Datei mit Rechten 600, Pfad aus
ARCHIV_TOKEN_DATEI, sonst ~/.config/urinet-archiv/token; nie das Token selbst aus einer Umgebungsvariable.

Beispiel:
    python3 tools/last/upload.py --url https://archiv.apps.urinet.ch --anzahl 200 --parallel 4 --groesse 200000
"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import os
import secrets
import stat
import statistics
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit


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


def pdf(groesse: int) -> bytes:
    """Gültiges, eindeutiges Mini-PDF, mit Kommentarzeilen auf etwa `groesse` Bytes aufgefüllt."""
    kopf = f"%PDF-1.4\n% Lasttest {secrets.token_hex(16)}\n".encode("ascii")
    inhalt = b"1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n"
    fuellung = max(0, groesse - len(kopf) - len(inhalt))
    zeilen = (b"%" + b" " * 78 + b"\n") * (fuellung // 80)
    return kopf + zeilen + inhalt


def multipart(name: str, daten: bytes) -> tuple[str, bytes]:
    grenze = f"----archiv-last-{uuid.uuid4().hex}"
    koerper = (
        (
            f"--{grenze}\r\n"
            f'Content-Disposition: form-data; name="dateien"; filename="{name}"\r\n'
            "Content-Type: application/pdf\r\n\r\n"
        ).encode("ascii")
        + daten
        + f"\r\n--{grenze}--\r\n".encode("ascii")
    )
    return f"multipart/form-data; boundary={grenze}", koerper


def hochladen(url: str, token: str, groesse: int, nummer: int) -> tuple[int, float]:
    teile = urlsplit(url)
    klasse = http.client.HTTPSConnection if teile.scheme == "https" else http.client.HTTPConnection
    verbindung = klasse(teile.netloc, timeout=120)
    try:
        typ, koerper = multipart(f"last-{nummer:05d}.pdf", pdf(groesse))
        verbindung.putrequest("POST", teile.path.rstrip("/") + "/api/v1/dokumente")
        verbindung.putheader("Authorization", f"Bearer {token}")
        verbindung.putheader("Content-Type", typ)
        verbindung.putheader("Content-Length", str(len(koerper)))
        verbindung.endheaders()
        verbindung.send(koerper)
        start = time.perf_counter()
        antwort = verbindung.getresponse()
        antwort.read()
        return antwort.status, time.perf_counter() - start
    except OSError:
        return 0, 0.0
    finally:
        verbindung.close()


def perzentil(werte: list[float], p: float) -> float:
    """Nearest-Rank-Perzentil."""
    geordnet = sorted(werte)
    return geordnet[max(0, math.ceil(p / 100 * len(geordnet)) - 1)]


def main() -> int:
    parser = argparse.ArgumentParser(description="Lastskript Upload (Req 3.2), Nachweis in Aufgabe 5.11")
    parser.add_argument("--url", required=True, help="Basis-URL des Archivs, z. B. https://archiv.apps.urinet.ch")
    parser.add_argument("--anzahl", type=int, required=True, help="Anzahl Uploads (je eine Datei)")
    parser.add_argument("--parallel", type=int, required=True, help="gleichzeitige Uploads")
    parser.add_argument("--groesse", type=int, required=True, help="Grösse je PDF in Bytes")
    parser.add_argument("--grenze", type=float, default=2.0, help="Grenze für p95 in Sekunden (Req 3.2: 2)")
    parser.add_argument("--json", action="store_true", help="Ergebnis als JSON ausgeben")
    args = parser.parse_args()
    if args.anzahl < 1 or args.parallel < 1 or args.groesse < 1:
        parser.error("--anzahl, --parallel und --groesse müssen grösser als 0 sein")

    token = token_lesen()
    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        ergebnisse = list(pool.map(lambda n: hochladen(args.url, token, args.groesse, n), range(args.anzahl)))

    zeiten = [dauer for status, dauer in ergebnisse if status == 202]
    fehler: dict[int, int] = {}
    for status, _ in ergebnisse:
        if status != 202:
            fehler[status] = fehler.get(status, 0) + 1
    bericht = {
        "anzahl": args.anzahl,
        "parallel": args.parallel,
        "groesse": args.groesse,
        "angenommen": len(zeiten),
        "fehler": {str(status): zahl for status, zahl in sorted(fehler.items())},
        "p50_s": round(statistics.median(zeiten), 3) if zeiten else None,
        "p95_s": round(perzentil(zeiten, 95), 3) if zeiten else None,
        "max_s": round(max(zeiten), 3) if zeiten else None,
        "grenze_s": args.grenze,
    }
    bericht["bestanden"] = not fehler and bericht["p95_s"] is not None and bericht["p95_s"] <= args.grenze
    if args.json:
        print(json.dumps(bericht, ensure_ascii=False))
    else:
        print(f"Uploads: {args.anzahl} (parallel {args.parallel}, je {args.groesse} Bytes), angenommen: {len(zeiten)}")
        if fehler:
            # Status 0: keine Antwort (Verbindung abgebrochen oder Zeitüberschreitung)
            print("Fehler nach Status: " + ", ".join(f"{s}: {z}" for s, z in sorted(fehler.items())))
        if zeiten:
            print(f"p50 {bericht['p50_s']} s, p95 {bericht['p95_s']} s, max {bericht['max_s']} s "
                  f"(Grenze p95 {args.grenze} s)")
        print("bestanden" if bericht["bestanden"] else "nicht bestanden")
    return 0 if bericht["bestanden"] else 1


if __name__ == "__main__":
    sys.exit(main())
