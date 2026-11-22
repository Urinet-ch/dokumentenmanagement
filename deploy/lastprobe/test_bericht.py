"""Tests für die Auswertung synthetischer Lastprobe-Daten."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SKRIPT = Path(__file__).with_name("bericht.py")


class BerichtTest(unittest.TestCase):
    def setUp(self):
        self.ordner = tempfile.TemporaryDirectory()
        self.addCleanup(self.ordner.cleanup)
        self.pfad = Path(self.ordner.name)
        self.schreiben("upload.json", json.dumps({
            "p50_s": 0.3, "p95_s": 2.0004, "max_s": 3.2, "grenze_s": 2, "bestanden": False,
        }))
        self.schreiben("stats.jsonl", "\n".join(json.dumps(d) for d in [
            {"Name": "probe-api-1", "MemUsage": "100MiB / 640MiB", "CPUPerc": "20.25%"},
            {"Name": "probe-worker-1", "MemUsage": "1GB / 2GiB", "CPUPerc": "110.5%"},
            {"Name": "probe-api-1", "MemUsage": "125MiB / 640MiB", "CPUPerc": "15.00%"},
        ]))
        self.schreiben("free.txt", "              total used free shared buff/cache available\n"
                       "Mem:           4000 1000 1000 0 2000 2500\n"
                       "Mem:           4000 1500  500 0 2000 2100\n")

    def schreiben(self, name, text):
        (self.pfad / name).write_text(text, encoding="utf-8")

    def starten(self):
        return subprocess.run([
            sys.executable, str(SKRIPT), "--messung", f"Upload={self.pfad / 'upload.json'}",
            "--dienst", "probe-api-1", "--dienst", "probe-worker-1",
            "--docker-stats", str(self.pfad / "stats.jsonl"),
            "--host-speicher", str(self.pfad / "free.txt"), "--ziel", str(self.pfad / "messbericht.md"),
        ], capture_output=True, text=True)

    def test_bericht_zeigt_rohwerte_und_spitzen(self):
        lauf = self.starten()
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        text = (self.pfad / "messbericht.md").read_text(encoding="utf-8")
        self.assertIn("| Upload | 0.3 | 2.0004 | 3.2 | 2 | nicht bestanden |", text)
        self.assertIn("| probe-api-1 | 125.00 | 20.25 |", text)
        self.assertIn("| probe-worker-1 | 953.67 | 110.50 |", text)
        self.assertIn("Gesamt: 4000 MiB", text)
        self.assertIn("Höchste Belegung: 1500 MiB", text)
        self.assertIn("Niedrigste Verfügbarkeit: 2100 MiB", text)

    def test_fehlende_containerwerte_erzeugen_keinen_bericht(self):
        self.schreiben("stats.jsonl", json.dumps(
            {"Name": "probe-api-1", "MemUsage": "100MiB / 640MiB", "CPUPerc": "20.25%"}))
        lauf = self.starten()
        self.assertNotEqual(lauf.returncode, 0)
        self.assertIn("keine Messwerte", lauf.stderr)
        self.assertFalse((self.pfad / "messbericht.md").exists())

    def test_widerspruch_und_bestehenden_bericht_nicht_ueberschreiben(self):
        self.schreiben("upload.json", json.dumps({
            "p50_s": 0.3, "p95_s": 2.0004, "max_s": 3.2, "grenze_s": 2, "bestanden": True,
        }))
        lauf = self.starten()
        self.assertNotEqual(lauf.returncode, 0)
        self.assertIn("widerspricht", lauf.stderr)
        self.schreiben("upload.json", json.dumps({
            "p50_s": 0.3, "p95_s": 1.5, "max_s": 3.2, "grenze_s": 2, "bestanden": True,
        }))
        self.schreiben("messbericht.md", "Vorheriger Bericht")
        lauf = self.starten()
        self.assertNotEqual(lauf.returncode, 0)
        self.assertEqual((self.pfad / "messbericht.md").read_text(encoding="utf-8"), "Vorheriger Bericht")


if __name__ == "__main__":
    unittest.main()
