#!/usr/bin/env python3
"""Lastskript Req 5.5: einseitiges Handy-Foto (JPEG oder HEIC), p95 bis zum Status EINGANGSKORB (Ziel <= 30 s).

Nur Standardbibliothek. Der Nachweis gilt erst auf Stufe B (Req 16.16, Aufgabe 5.11), nur mit synthetischen
Testfotos. Das Skript lädt das Foto nacheinander hoch (je Lauf mit eindeutigem Anhang, damit keine Dublette entsteht:
bei JPEG hinter dem EOI-Marker, bei HEIC/HEIF als ISO-BMFF-Box `free` am Ende) und fragt den Status ab, bis
EINGANGSKORB oder FEHLER erreicht ist. `--aufwaermen` schickt vorher Läufe, die nicht zählen.

Messpunkt: von der Antwort 202 bis zum ersten gelesenen EINGANGSKORB. Das ist eine obere Schranke für «Start des
Auftrags bis EINGANGSKORB»: Wartezeit in der Schlange und Abfrageintervall zählen mit. Ohne KI-Wartezeit heisst:
gegen ein Archiv mit KI-Stub messen oder die KI-Zeit aus `ki_verbrauch` abziehen (Festlegung in 5.11).

Token: aus der Datei in ARCHIV_TOKEN_DATEI oder ~/.config/urinet-archiv/token, Rechte 600, nie aus einer
Umgebungsvariable (tech.md, Regel 7). Das Token braucht die Rechte A1 (Upload) und A2 (Dokument lesen). Ein
API-Token mit Scope `import` reicht nicht: Es erreicht nur `POST /api/v1/dokumente`, jeder andere Endpunkt antwortet
mit 403 (Req 2.5). Die Datei enthält darum ein Keycloak-Access-Token eines Benutzers mit A1 und A2; die endgültige
Festlegung trifft Aufgabe 5.11.
"""

import argparse
import json
import math
import os
import stat
import struct
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


# Marken der ftyp-Box (ISO/IEC 23008-12): HEVC-Bilder als HEIC, übrige Bildfolgen und Einzelbilder als HEIF
HEIC_MARKEN = {b"heic", b"heix", b"heim", b"heis", b"hevc", b"hevx"}
HEIF_MARKEN = {b"mif1", b"msf1"}


def fototyp(foto: bytes) -> tuple[str, str]:
    """Endung und Medientyp aus dem Dateikopf; nur JPEG, HEIC und HEIF."""
    if foto.startswith(b"\xff\xd8"):
        return "jpg", "image/jpeg"
    if len(foto) >= 12 and foto[4:8] == b"ftyp":
        marke = foto[8:12]
        if marke in HEIC_MARKEN:
            return "heic", "image/heic"
        if marke in HEIF_MARKEN:
            return "heif", "image/heif"
    sys.exit("Das Foto muss ein JPEG oder HEIC/HEIF sein (synthetisches Testfoto).")


def eindeutig(foto: bytes, endung: str) -> bytes:
    """Macht die SHA-256 je Lauf neu, ohne das Bild zu verändern.

    JPEG-Leser ignorieren Bytes hinter dem EOI-Marker; ISO-BMFF-Leser (HEIC/HEIF) überspringen eine Box vom Typ
    `free` am Dateiende (ISO/IEC 14496-12, 8.1.2)."""
    anhang = b"lastlauf-" + uuid.uuid4().hex.encode()
    if endung == "jpg":
        return foto + anhang
    return foto + struct.pack(">I", 8 + len(anhang)) + b"free" + anhang


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
    endung, typ = fototyp(foto)
    koerper, medientyp = multipart("dateien[]", f"lastlauf.{endung}", eindeutig(foto, endung), typ)
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
    parser.add_argument("--foto", required=True, type=Path, help="synthetisches einseitiges JPEG oder HEIC/HEIF")
    parser.add_argument("--anzahl", type=int, default=20)
    parser.add_argument("--aufwaermen", type=int, default=0, help="Läufe vorweg, die nicht zählen")
    parser.add_argument("--intervall", type=float, default=0.5, help="Abfrageintervall in Sekunden")
    parser.add_argument("--grenze", type=float, default=600.0, help="Abbruch je Lauf in Sekunden")
    args = parser.parse_args()

    if args.anzahl < 1 or args.aufwaermen < 0:
        parser.error("--anzahl muss grösser als 0 sein, --aufwaermen mindestens 0")
    token = token_lesen()
    foto = args.foto.read_bytes()
    fototyp(foto)
    basis = args.basis_url.rstrip("/")
    for nr in range(1, args.aufwaermen + 1):
        dauer = ein_lauf(basis, token, foto, args.intervall, args.grenze)
        print(f"Aufwärmen {nr:3d}: {dauer:6.1f} s (zählt nicht)")
    zeiten = []
    for nr in range(1, args.anzahl + 1):
        dauer = ein_lauf(basis, token, foto, args.intervall, args.grenze)
        zeiten.append(dauer)
        print(f"Lauf {nr:3d}: {dauer:6.1f} s")
    wert = p95(zeiten)
    print(f"p95 über {len(zeiten)} Läufe: {wert:.1f} s (Ziel <= {ZIEL_P95_SEKUNDEN:.0f} s)")
    return 0 if wert <= ZIEL_P95_SEKUNDEN else 1


if __name__ == "__main__":
    sys.exit(main())
