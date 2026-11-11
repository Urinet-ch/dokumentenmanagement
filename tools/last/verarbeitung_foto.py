#!/usr/bin/env python3
"""Lastskript Req 5.5: einseitiges Handy-Foto, p95 bis zum Status EINGANGSKORB (Ziel <= 30 s).

Nur Standardbibliothek. Der Nachweis gilt erst auf Stufe B (Req 16.16, Aufgabe 5.11), nur mit synthetischen
Testfotos. Das Skript lädt das Foto nacheinander hoch (je Lauf mit eindeutigem Anhang hinter dem JPEG-Ende, damit
keine Dublette entsteht) und fragt den Status ab, bis EINGANGSKORB oder FEHLER erreicht ist.

Messpunkt: von der Antwort 202 bis zum ersten gelesenen EINGANGSKORB. Das ist eine obere Schranke für «Start des
Auftrags bis EINGANGSKORB»: Wartezeit in der Schlange und Abfrageintervall zählen mit. Ohne KI-Wartezeit heisst:
gegen ein Archiv mit KI-Stub messen oder die KI-Zeit aus `ki_verbrauch` abziehen (Festlegung in 5.11).

Token: aus der Datei in ARCHIV_TOKEN_DATEI oder ~/.config/urinet-archiv/token, Rechte 600, nie aus einer
Umgebungsvariable (tech.md, Regel 7). Das Token braucht die Rechte A1 (Upload) und A2 (Dokument lesen).
"""

import argparse
import json
import math
import os
import stat
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ZIEL_P95_SEKUNDEN = 30.0
ENDSTATUS = {"EINGANGSKORB", "FEHLER"}


def token_lesen() -> str:
    pfad = Path(os.environ.get("ARCHIV_TOKEN_DATEI", Path.home() / ".config/urinet-archiv/token"))
    if stat.S_IMODE(pfad.stat().st_mode) & 0o077:
        sys.exit(f"Die Token-Datei {pfad} ist für andere lesbar. Bitte mit «chmod 600» schützen.")
    token = pfad.read_text(encoding="utf-8").strip()
    if not token:
        sys.exit(f"Die Token-Datei {pfad} ist leer.")
    return token


def eindeutig(jpeg: bytes) -> bytes:
    """Bytes hinter dem EOI-Marker ignorieren JPEG-Leser; die SHA-256 wird damit je Lauf neu."""
    if not jpeg.startswith(b"\xff\xd8"):
        sys.exit("Das Foto muss ein JPEG sein (synthetisches Testfoto).")
    return jpeg + b"lastlauf-" + uuid.uuid4().hex.encode()


def multipart(feld: str, dateiname: str, inhalt: bytes, medientyp: str) -> tuple[bytes, str]:
    grenze = uuid.uuid4().hex
    teile = [
        f"--{grenze}\r\n".encode(),
        f'Content-Disposition: form-data; name="{feld}"; filename="{dateiname}"\r\n'.encode(),
        f"Content-Type: {medientyp}\r\n\r\n".encode(),
        inhalt,
        f"\r\n--{grenze}\r\n".encode(),
        b'Content-Disposition: form-data; name="zusammenfassen"\r\n\r\ntrue',
        f"\r\n--{grenze}--\r\n".encode(),
    ]
    return b"".join(teile), f"multipart/form-data; boundary={grenze}"


def anfrage(url: str, token: str, daten: bytes | None = None, medientyp: str | None = None) -> tuple[int, object]:
    kopf = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    if medientyp:
        kopf["Content-Type"] = medientyp
    req = urllib.request.Request(url, data=daten, headers=kopf, method="POST" if daten else "GET")
    try:
        with urllib.request.urlopen(req, timeout=60) as antwort:
            return antwort.status, json.loads(antwort.read() or b"null")
    except urllib.error.HTTPError as fehler:
        return fehler.code, None


def ein_lauf(basis: str, token: str, foto: bytes, intervall: float, grenze: float) -> float:
    koerper, medientyp = multipart("dateien[]", "lastlauf.jpg", eindeutig(foto), "image/jpeg")
    status, ergebnis = anfrage(f"{basis}/api/v1/dokumente", token, koerper, medientyp)
    start = time.monotonic()
    if status != 202 or not isinstance(ergebnis, list) or not ergebnis or "id" not in ergebnis[0]:
        raise RuntimeError(f"Upload nicht angenommen (HTTP {status}).")
    dokument = ergebnis[0]["id"]
    while time.monotonic() - start < grenze:
        status, daten = anfrage(f"{basis}/api/v1/dokumente/{dokument}", token)
        if status != 200 or not isinstance(daten, dict):
            raise RuntimeError(f"Dokument {dokument} nicht lesbar (HTTP {status}).")
        if daten.get("status") in ENDSTATUS:
            if daten["status"] == "FEHLER":
                raise RuntimeError(f"Dokument {dokument} endete in FEHLER.")
            return time.monotonic() - start
        time.sleep(intervall)
    raise RuntimeError(f"Dokument {dokument} nach {grenze:.0f} s nicht im Eingangskorb.")


def p95(werte: list[float]) -> float:
    """Nächster Rang (ohne Interpolation), damit p95 immer ein gemessener Wert ist."""
    geordnet = sorted(werte)
    return geordnet[math.ceil(0.95 * len(geordnet)) - 1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--basis-url", required=True, help="z. B. https://archiv.apps.urinet.ch")
    parser.add_argument("--foto", required=True, type=Path, help="synthetisches einseitiges JPEG")
    parser.add_argument("--anzahl", type=int, default=20)
    parser.add_argument("--intervall", type=float, default=0.5, help="Abfrageintervall in Sekunden")
    parser.add_argument("--grenze", type=float, default=600.0, help="Abbruch je Lauf in Sekunden")
    args = parser.parse_args()

    token = token_lesen()
    foto = args.foto.read_bytes()
    zeiten = []
    for nr in range(1, args.anzahl + 1):
        dauer = ein_lauf(args.basis_url.rstrip("/"), token, foto, args.intervall, args.grenze)
        zeiten.append(dauer)
        print(f"Lauf {nr:3d}: {dauer:6.1f} s")
    wert = p95(zeiten)
    print(f"p95 über {len(zeiten)} Läufe: {wert:.1f} s (Ziel <= {ZIEL_P95_SEKUNDEN:.0f} s)")
    return 0 if wert <= ZIEL_P95_SEKUNDEN else 1


if __name__ == "__main__":
    sys.exit(main())
