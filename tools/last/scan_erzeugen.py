#!/usr/bin/env python3
"""Erzeugt einen synthetischen 20-seitigen Bildscan ausserhalb des Repositories."""

import argparse
import io
import random
from pathlib import Path

from PIL import Image, ImageDraw
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas


def erzeugen(ziel: Path, *, vorfallsgroesse: bool = False) -> None:
    repo = Path(__file__).resolve().parents[2]
    if ziel.resolve().is_relative_to(repo):
        raise ValueError("Ausgabe muss ausserhalb des Repositories liegen.")
    with ziel.open("xb") as datei:
        pdf = canvas.Canvas(datei, pagesize=(595, 842), invariant=1 if vorfallsgroesse else None)
        for nr in range(1, 21):
            if vorfallsgroesse:
                rauschen = random.Random(nr).randbytes(1240 * 1754)
                bild = Image.frombytes("L", (1240, 1754), rauschen)
                bild = bild.point([238 + wert * 17 // 255 for wert in range(256)]).convert("RGB")
            else:
                bild = Image.new("RGB", (1240, 1754), "white")
            zeichner = ImageDraw.Draw(bild)
            zeichner.text((80, 80), f"SYNTHETISCHER SCAN - SEITE {nr:02}", fill="black")
            for zeile in range(35):
                y = 160 + zeile * 42
                zeichner.text((80, y), f"Testzeile {zeile + 1:02}  Synthetischer Inhalt {nr:02}", fill="black")
            puffer = io.BytesIO()
            if vorfallsgroesse:
                bild.save(puffer, format="JPEG", quality=92, optimize=True)
            else:
                bild.save(puffer, format="PNG")
            puffer.seek(0)
            pdf.drawImage(ImageReader(puffer), 0, 0, width=595, height=842)
            pdf.showPage()
        pdf.save()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ziel", type=Path)
    args = parser.parse_args()
    erzeugen(args.ziel)


if __name__ == "__main__":
    main()
