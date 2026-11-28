#!/usr/bin/env python3
"""Erzeugt JPEG, HEIC und dreiseitiges farbiges Bild-PDF ausschliesslich lokal."""

import argparse
import io
import os
from pathlib import Path

from PIL import Image
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

import testdaten_erzeugen


def erzeugen(ziel):
    import pillow_heif

    pillow_heif.register_heif_opener()
    scan, jpeg = testdaten_erzeugen.erzeugen(ziel)
    heic = jpeg.with_suffix(".heic")
    pdf = jpeg.with_name("bildweg-drei-seiten.pdf")
    with Image.open(jpeg) as bild:
        with heic.open("xb") as datei:
            bild.save(datei, format="HEIF", quality=90)
        with Image.open(heic) as gelesen:
            if gelesen.size != bild.size:
                raise ValueError("HEIC hat nicht die Masse des JPEG.")
    with pdf.open("xb") as datei:
        dokument = canvas.Canvas(datei, pagesize=(595, 842), invariant=1)
        for _ in range(3):
            dokument.drawImage(ImageReader(io.BytesIO(jpeg.read_bytes())), 0, 0, width=595, height=842)
            dokument.showPage()
        dokument.save()
    for datei in (heic, pdf):
        os.chmod(datei, 0o600)
    return scan, jpeg, heic, pdf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ziel", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        erzeugen(args.ziel)
    except ImportError:
        parser.exit(2, "pillow-heif fehlt; vorhandene Generator-Abhängigkeiten installieren.\n")
    except (OSError, ValueError):
        parser.exit(2, "Bilderzeugung fehlgeschlagen; unvollständigen Ausgabeordner nicht verwenden.\n")
    print("Synthetische Messbilder erzeugt; kein Upload und kein Messnachweis.")


if __name__ == "__main__":
    main()
