#!/usr/bin/env python3
"""Erzeugt synthetische Messbelege ausserhalb des Repositories, ohne Netzwerkzugriff."""

import argparse
import io
import os
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

import scan_erzeugen

BREITE, HOEHE = 4032, 3024
SCAN_NAME = "scan-20-seiten-ca-15-mib.pdf"
FOTO_NAME = "handyfoto-12mp.jpg"


def foto_bytes() -> bytes:
    foto = Image.new("RGB", (BREITE, HOEHE))
    zeichner = ImageDraw.Draw(foto)
    for y in range(HOEHE):
        stufe = y * 24 // HOEHE
        zeichner.line((0, y, BREITE, y), fill=(72 + stufe, 53 + stufe, 38 + stufe))

    schatten = ((880, 300), (3130, 390), (2970, 2770), (730, 2660))
    papier = ((820, 250), (3070, 340), (2910, 2720), (670, 2610))
    zeichner.polygon(schatten, fill=(42, 34, 29))
    zeichner.polygon(papier, fill=(244, 241, 226))
    zeichner.polygon(((1020, 470), (2740, 535), (2710, 750), (1010, 685)), fill=(38, 89, 132))
    zeichner.rectangle((1020, 820, 2580, 1000), fill=(225, 222, 210))
    for zeile in range(26):
        y = 1120 + zeile * 52
        laenge = 1180 + (zeile * 137 % 520)
        zeichner.line((1040, y, 1040 + laenge, y), fill=(72, 76, 80), width=5)
    zeichner.rectangle((1020, 2560, 1760, 2580), fill=(152, 55, 45))

    rauschen = random.Random(20261122).randbytes(512 * 384)
    struktur = Image.frombytes("L", (512, 384), rauschen).resize((BREITE, HOEHE), Image.Resampling.BILINEAR)
    foto = Image.blend(foto, Image.merge("RGB", (struktur, struktur, struktur)), 0.025)
    puffer = io.BytesIO()
    foto.save(puffer, format="JPEG", quality=88, optimize=True)
    return puffer.getvalue()


def erzeugen(zielordner: Path) -> tuple[Path, Path]:
    repo = Path(__file__).resolve().parents[2]
    zielordner = zielordner.resolve()
    if zielordner.is_relative_to(repo):
        raise ValueError("Ausgabe muss ausserhalb des Repositories liegen.")

    zielordner.mkdir(parents=True, mode=0o700, exist_ok=False)
    os.chmod(zielordner, 0o700)
    scan = zielordner / SCAN_NAME
    foto = zielordner / FOTO_NAME
    try:
        scan_erzeugen.erzeugen(scan, vorfallsgroesse=True)
        with foto.open("xb") as datei:
            datei.write(foto_bytes())
        os.chmod(scan, 0o600)
        os.chmod(foto, 0o600)
    except Exception:
        for datei in (scan, foto):
            datei.unlink(missing_ok=True)
        zielordner.rmdir()
        raise
    return scan, foto


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("zielordner", type=Path, help="neuer Ausgabepfad ausserhalb des Repositories")
    args = parser.parse_args()
    try:
        scan, foto = erzeugen(args.zielordner)
    except (OSError, ValueError) as fehler:
        print(f"Abbruch: {fehler}", file=sys.stderr)
        return 1
    print(f"Scan: {scan} ({scan.stat().st_size} Bytes)")
    print(f"Foto: {foto} ({foto.stat().st_size} Bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
