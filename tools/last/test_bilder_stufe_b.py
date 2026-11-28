"""Tests der lokal erzeugten Messbilder, ohne Upload."""

import importlib.util
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(importlib.util.find_spec("pillow_heif"), "pillow-heif nicht installiert")
class BilderTest(unittest.TestCase):
    def test_heic_und_dreiseitiges_bild_pdf(self):
        import bilder_stufe_b
        from PIL import Image
        import pypdfium2

        with tempfile.TemporaryDirectory() as ordner:
            scan, jpeg, heic, pdf = bilder_stufe_b.erzeugen(Path(ordner) / "bilder")
            with Image.open(jpeg) as a, Image.open(heic) as b:
                self.assertEqual(a.size, (4032, 3024))
                self.assertEqual(a.size, b.size)
            with pypdfium2.PdfDocument(pdf) as dokument:
                self.assertEqual(len(dokument), 3)
                seite = dokument[0]
                bild = seite.render(scale=0.1)
                try:
                    self.assertEqual(bild.to_pil().mode, "RGB")
                finally:
                    bild.close()
                    seite.close()
            self.assertTrue(scan.exists())
            self.assertEqual(heic.stat().st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
