"""Erzeugt alle Dateien zweimal in denselben Ordner (langsamer Test, rund 20 s) und prüft Bytes, Schema,
QR-Inhalt und dass nur eigene Dateien ersetzt werden. Die Golden-Wurzel ist ein Nachbau in tmp."""

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
FREMD = "01_fremd_von_hand.pdf"


def _golden_nachbau(wurzel: Path) -> dict[Path, bytes]:
    (wurzel / "belege").mkdir(parents=True)
    dateien = {wurzel / "erwartet.json": b'{"echt": true}\n', wurzel / "belege" / "01_echt.pdf": b"%PDF echt",
               wurzel / "schwaerzen.json": b"{}"}
    for pfad, inhalt in dateien.items():
        pfad.write_bytes(inhalt)
    return dateien


@pytest.fixture(scope="module")
def lauf(tmp_path_factory):
    golden = tmp_path_factory.mktemp("golden")
    echt = _golden_nachbau(golden)
    ziel = golden / "synthetisch"
    generator.erzeuge(SEED, ziel, golden=golden)
    erster = {p.relative_to(ziel): p.read_bytes() for p in ziel.rglob("*") if p.is_file()}
    (ziel / "belege" / FREMD).write_bytes(b"nicht vom Generator")
    generator.erzeuge(SEED, ziel, golden=golden)
    return ziel, erster, echt


def test_50_dateien_und_schema(lauf):
    ziel, _, _ = lauf
    daten = json.loads((ziel / "erwartet.json").read_text(encoding="utf-8"))
    jsonschema.validate(daten, SCHEMA)
    katalog = sorted(Path(e["datei"]).name for e in daten["belege"])
    assert len(katalog) == 50
    assert sorted(p.name for p in (ziel / "belege").iterdir()) == sorted([*katalog, FREMD])


def test_zweiter_lauf_mit_marker_ist_byte_gleich(lauf):
    ziel, erster, _ = lauf
    assert (ziel / generator.MARKER).is_file()
    for pfad, inhalt in erster.items():
        assert (ziel / pfad).read_bytes() == inhalt, pfad


def test_nur_eigene_dateien_ersetzt_und_golden_unberuehrt(lauf):
    ziel, _, echt = lauf
    assert (ziel / "belege" / FREMD).read_bytes() == b"nicht vom Generator"
    for pfad, inhalt in echt.items():
        assert pfad.read_bytes() == inhalt, pfad


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
    a, _, _ = lauf
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


def test_ausgabe_im_repository_wird_abgelehnt(tmp_path):
    with pytest.raises(SystemExit):
        generator.erzeuge(SEED, generator.REPO / "tmp-golden", golden=tmp_path)


def test_ziel_gleich_golden_wurzel_wird_abgelehnt(tmp_path):
    echt = _golden_nachbau(tmp_path / "golden")
    with pytest.raises(SystemExit, match="Golden-Wurzel"):
        generator.erzeuge(SEED, tmp_path / "golden", golden=tmp_path / "golden")
    assert all(p.read_bytes() == inhalt for p, inhalt in echt.items())
    assert not (tmp_path / "golden" / generator.MARKER).exists()


def test_ziel_ueber_golden_wurzel_wird_abgelehnt(tmp_path):
    _golden_nachbau(tmp_path / "golden")
    with pytest.raises(SystemExit, match="Golden-Wurzel"):
        generator.erzeuge(SEED, tmp_path, golden=tmp_path / "golden")


def test_golden_wurzel_ueber_symlink_wird_erkannt(tmp_path):
    _golden_nachbau(tmp_path / "golden")
    (tmp_path / "verweis").symlink_to(tmp_path / "golden")
    with pytest.raises(SystemExit, match="Golden-Wurzel"):
        generator.erzeuge(SEED, tmp_path / "verweis", golden=tmp_path / "golden")


def test_fremder_ordner_ohne_marker_wird_abgelehnt(tmp_path):
    fremd = tmp_path / "fremd"
    (fremd / "belege").mkdir(parents=True)
    (fremd / "belege" / "02_wichtig.pdf").write_bytes(b"wichtig")
    with pytest.raises(SystemExit, match=generator.MARKER):
        generator.erzeuge(SEED, fremd, golden=tmp_path / "golden")
    assert (fremd / "belege" / "02_wichtig.pdf").read_bytes() == b"wichtig"
    assert not (fremd / generator.MARKER).exists()


def test_golden_wurzel_aus_umgebung(monkeypatch, tmp_path):
    monkeypatch.setenv("ARCHIV_GOLDEN_DIR", str(tmp_path))
    assert generator.golden_wurzel() == tmp_path.resolve()
