"""Tests für die synthetischen Testdaten der Speichermessung."""

import base64
import re
import stat
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

from PIL import Image

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER))

import testdaten_erzeugen  # noqa: E402

MIB = 1024 * 1024


def pdf_inhaltsstroeme(pdf: bytes) -> list[bytes]:
    stroeme = []
    for objekt_id in re.findall(rb"/Contents (\d+) 0 R", pdf):
        objekt_start = pdf.index(b"\n" + objekt_id + b" 0 obj\n") + 1
        strom_start = pdf.index(b"stream\n", objekt_start) + len(b"stream\n")
        strom_ende = pdf.index(b"endstream", strom_start)
        kodiert = pdf[strom_start:strom_ende].rstrip(b"\r\n")
        stroeme.append(zlib.decompress(base64.a85decode(kodiert, adobe=True)))
    return stroeme


class TestdatenErzeugenTest(unittest.TestCase):
    def test_ein_aufruf_erstellt_bildscan_und_12mp_foto(self):
        with tempfile.TemporaryDirectory() as temporaer:
            zielordner = Path(temporaer) / "messbelege"
            scan, foto = testdaten_erzeugen.erzeugen(zielordner)

            self.assertEqual({pfad.name for pfad in zielordner.iterdir()},
                             {testdaten_erzeugen.SCAN_NAME, testdaten_erzeugen.FOTO_NAME})
            self.assertEqual(stat.S_IMODE(zielordner.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(scan.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(foto.stat().st_mode), 0o600)
            self.assertGreaterEqual(scan.stat().st_size, 14 * MIB)
            self.assertLessEqual(scan.stat().st_size, 16 * MIB)

            pdf = scan.read_bytes()
            self.assertEqual(len(re.findall(rb"/Type\s*/Page\b", pdf)), 20)
            self.assertEqual(pdf.count(b"/Subtype /Image"), 20)
            inhaltsstroeme = pdf_inhaltsstroeme(pdf)
            self.assertEqual(len(inhaltsstroeme), 20)
            for inhalt in inhaltsstroeme:
                self.assertNotRegex(inhalt, rb"(?<!\S)(?:Tj|TJ|'|\")(?!\S)")

            with Image.open(foto) as bild:
                self.assertEqual((bild.format, bild.size), ("JPEG", (4032, 3024)))
                bild.verify()

    def test_lehnt_repository_und_bestehenden_ausgabepfad_ab(self):
        repo = Path(__file__).resolve().parents[2]
        with self.assertRaisesRegex(ValueError, "ausserhalb"):
            testdaten_erzeugen.erzeugen(repo / "tools" / "last" / "messbelege")
        with tempfile.TemporaryDirectory() as temporaer:
            zielordner = Path(temporaer) / "bestehend"
            zielordner.mkdir()
            with self.assertRaises(FileExistsError):
                testdaten_erzeugen.erzeugen(zielordner)


if __name__ == "__main__":
    unittest.main()
