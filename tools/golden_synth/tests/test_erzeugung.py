"""Erzeugt alle Dateien zweimal (langsamer Test, rund 20 s) und prüft Bytes, Schema und QR-Inhalt."""

import json
from pathlib import Path

import cv2
import jsonschema
import numpy as np
import pypdfium2 as pdfium
import pytest

from tools.golden_synth import generator

SEED = generator.STANDARD_SEED
SCHEMA = json.loads((Path(__file__).parents[1] / "erwartet.schema.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def lauf(tmp_path_factory):
    a, b = tmp_path_factory.mktemp("lauf_a"), tmp_path_factory.mktemp("lauf_b")
    generator.erzeuge(SEED, a)
    generator.erzeuge(SEED, b)
    return a, b


def test_50_dateien_und_schema(lauf):
    a, _ = lauf
    daten = json.loads((a / "erwartet.json").read_text(encoding="utf-8"))
    jsonschema.validate(daten, SCHEMA)
    dateien = sorted(p.name for p in (a / "belege").iterdir())
    assert len(dateien) == 50
    assert sorted(Path(e["datei"]).name for e in daten["belege"]) == dateien


def test_zwei_laeufe_sind_byte_gleich(lauf):
    a, b = lauf
    assert (a / "erwartet.json").read_bytes() == (b / "erwartet.json").read_bytes()
    for datei in (a / "belege").iterdir():
        assert datei.read_bytes() == (b / "belege" / datei.name).read_bytes(), datei.name


def _qr_lesen(pdf: Path) -> list[str]:
    dokument = pdfium.PdfDocument(str(pdf))
    for dpi in (200, 300, 400, 150):
        bild = dokument[len(dokument) - 1].render(scale=dpi / 72).to_numpy()[:, :, :3]
        unten = cv2.cvtColor(np.ascontiguousarray(bild[int(bild.shape[0] * 0.6):]), cv2.COLOR_RGB2BGR)
        for detektor in (cv2.QRCodeDetector(), cv2.QRCodeDetectorAruco()):
            text = detektor.detectAndDecode(unten)[0]
            if text:
                return [z.strip("\r") for z in text.split("\n")]
    raise AssertionError(f"kein QR-Code lesbar: {pdf.name}")


def test_qr_inhalt_entspricht_der_erwartung(lauf):
    a, _ = lauf
    daten = json.loads((a / "erwartet.json").read_text(encoding="utf-8"))
    geprueft = 0
    for e in daten["belege"]:
        if not e["qr"] or e["foto"]:
            continue
        z = _qr_lesen(a / e["datei"])
        q = e["qr"]
        assert z[0] == "SPC" and z[3] == q["konto"] and z[5] == q["zahlungsempfaenger"]
        assert (z[18] or None) == q["betrag"] and z[19] == q["waehrung"]
        assert z[27] == q["referenztyp"] and (z[28] or None) == q["referenz"]
        geprueft += 1
    assert geprueft >= 15


def test_ausgabe_im_repository_wird_abgelehnt():
    with pytest.raises(SystemExit):
        generator.erzeuge(SEED, generator.REPO / "tmp-golden")
