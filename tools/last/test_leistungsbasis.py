import unittest
import csv
import subprocess
import sys
import tempfile
from pathlib import Path

from leistungsbasis import auswerten


class AuswertungTest(unittest.TestCase):
    def test_erfolgszeiten_und_fehlerquote(self):
        zeilen = [{"ablauf": ablauf, "erfolg": erfolg, "dauer_ms": str(dauer)}
                  for ablauf in ("suche", "eingangskorb", "dokument", "chat")
                  for erfolg, dauer in (("true", 10), ("true", 30), ("true", 20), ("false", 999))]
        self.assertEqual(auswerten(zeilen)[0], ("suche", 4, 1, 25.0, 20.0, 30.0))

    def test_alle_fehlgeschlagen_und_fehlende_proben(self):
        zeilen = [{"ablauf": ablauf, "erfolg": "false", "dauer_ms": "1"}
                  for ablauf in ("suche", "eingangskorb", "dokument", "chat")]
        self.assertEqual(auswerten(zeilen)[0], ("suche", 1, 1, 100.0, None, None))
        with self.assertRaises(ValueError):
            auswerten(zeilen[:-1])
        with self.assertRaises(ValueError):
            auswerten(zeilen + [{"ablauf": "unbekannt", "erfolg": "true", "dauer_ms": "1"}])
        with self.assertRaises(ValueError):
            auswerten(zeilen + [{"ablauf": "chat", "erfolg": "true", "dauer_ms": "nan"}])

    def test_bericht_enthaelt_zeiten_fehler_und_vergleichsziel(self):
        with tempfile.TemporaryDirectory() as ordner:
            roh = Path(ordner) / "roh.csv"
            with roh.open("w", encoding="utf-8", newline="") as datei:
                writer = csv.writer(datei)
                writer.writerow(("ablauf", "erfolg", "dauer_ms"))
                for ablauf in ("suche", "eingangskorb", "dokument", "chat"):
                    writer.writerow((ablauf, "true", "12"))
                    writer.writerow((ablauf, "false", "0"))
            lauf = subprocess.run([sys.executable, str(Path(__file__).with_name("leistungsbasis.py")), str(roh)],
                                  check=True, text=True, capture_output=True)
            self.assertIn("kein Abnahmenachweis", lauf.stdout)
            with (Path(ordner) / "leistungsbasis.csv").open(encoding="utf-8", newline="") as datei:
                zeilen = list(csv.DictReader(datei))
            self.assertEqual(zeilen[0]["p95_ms"], "12.00")
            self.assertEqual(zeilen[0]["fehlerquote_prozent"], "50.00")
            self.assertEqual(zeilen[0]["vergleichsziel_p95_ms"], "1000")
            self.assertEqual(zeilen[3]["vergleichsziel_p95_ms"], "10000")
