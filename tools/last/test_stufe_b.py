"""Dockerfreie Tests von Bestand, Serverhistogrammen und Auftragsauswertung."""

import tempfile
import unittest
from pathlib import Path
from uuid import UUID

import auftrags_export
import auftragsauswertung
import bestand
import serverauswertung


def histogramm(n, innerhalb, methode="GET", status="200"):
    labels = f'method="{methode}",status="{status}",uri="/api/v1/dokumente"'
    return "\n".join([
        f'http_server_requests_seconds_count{{{labels}}} {n}',
        f'http_server_requests_seconds_bucket{{{labels},le="1.0"}} {innerhalb}',
        f'http_server_requests_seconds_bucket{{{labels},le="2.0"}} {innerhalb}',
        f'http_server_requests_seconds_bucket{{{labels},le="+Inf"}} {n}',
    ])


class BestandTest(unittest.TestCase):
    def test_genaue_deterministische_verteilung(self):
        laengen = [len(bestand.text(n)) for n in range(1, 50001)]
        self.assertEqual(sum(laengen) / len(laengen), 1500)
        self.assertEqual(sum(n > 20000 for n in laengen), 2500)
        self.assertEqual(bestand.sql(), bestand.sql())
        self.assertIn("Nur isolierter Messmandant last", bestand.sql())
        self.assertIn("VACUUM ANALYZE", bestand.sql())
        self.assertIn("'synthetischer-subject'", bestand.sql("synthetischer-subject"))
        with self.assertRaises(ValueError):
            bestand.sql("'; DROP TABLE dokument;")

    def test_ausgabe_schuetzt_bestehende_dateien(self):
        with tempfile.TemporaryDirectory() as ordner:
            pfad = Path(ordner) / "bestand.sql"
            bestand.schreiben(pfad, bestand.sql())
            self.assertEqual(pfad.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                bestand.schreiben(pfad, "überschreiben")
        with self.assertRaises(ValueError):
            bestand.schreiben(Path(__file__).parent / "nicht-erzeugen.sql", "")


class HistogrammTest(unittest.TestCase):
    def test_delta_ohne_aufwaermen_und_p95_grenze(self):
        ergebnis = serverauswertung.auswerten(histogramm(20, 20), histogramm(520, 495), "suche", 10)
        self.assertTrue(ergebnis["bestanden"])
        self.assertEqual(ergebnis["durchsatz_pro_s"], 50)
        self.assertFalse(serverauswertung.auswerten(histogramm(20, 20), histogramm(520, 494),
                                                  "suche", 10)["bestanden"])

    def test_upload_verlangt_alle_anfragen_in_zwei_sekunden(self):
        self.assertFalse(serverauswertung.auswerten(
            histogramm(20, 20, "POST", "202"), histogramm(220, 219, "POST", "202"),
            "upload-klein", 10)["bestanden"])

    def test_reset_fehler_anzahl_und_fehlende_buckets(self):
        for nachher in (histogramm(19, 19), histogramm(519, 519),
                        histogramm(520, 520).splitlines()[0],
                        histogramm(520, 520) + "\n" + histogramm(1, 1, status="500")):
            with self.assertRaises(ValueError):
                serverauswertung.auswerten(histogramm(20, 20), nachher, "suche", 10)


class AuftragTest(unittest.TestCase):
    def zeilen(self):
        return [{"id": str(n), "status": "FERTIG", "versuche": "0", "auftraege": "2",
                 "ki_stub": "true", "gestartet": "2026-01-01T00:00:00+00:00",
                 "beendet": "2026-01-01T00:00:30+00:00"} for n in range(50)]

    def test_grenze_und_wiederholungen(self):
        zeilen = self.zeilen()
        self.assertTrue(auftragsauswertung.auswerten(zeilen)["bestanden"])
        for z in zeilen:
            z["beendet"] = "2026-01-01T00:00:30.001+00:00"
        self.assertFalse(auftragsauswertung.auswerten(zeilen)["bestanden"])
        zeilen[0]["versuche"] = "1"
        with self.assertRaises(ValueError):
            auftragsauswertung.auswerten(zeilen)

    def test_keine_stille_teilauswertung_oder_unbestaetigte_ki(self):
        with self.assertRaises(ValueError):
            auftragsauswertung.auswerten(self.zeilen()[:49])
        for feld, wert in (("ki_stub", "false"), ("status", "FEHLER"), ("auftraege", "1"),
                           ("gestartet", "2026-01-01T00:00:00"), ("beendet", None)):
            zeilen = self.zeilen()
            zeilen[0][feld] = wert
            with self.assertRaises(ValueError):
                auftragsauswertung.auswerten(zeilen)

    def test_export_nur_valide_foto_ids(self):
        ids = [str(UUID(int=n)) for n in range(50)]
        sql = auftrags_export.sql(ids)
        self.assertIn("KI_VORSCHLAG", sql)
        self.assertIn("m_last.dokument", sql)
        for falsch in (ids[:-1], [ids[0]] * 50, ids[:-1] + ["'; DROP TABLE dokument;"],
                       ids[:-1] + [1]):
            with self.assertRaises(ValueError):
                auftrags_export.sql(falsch)


if __name__ == "__main__":
    unittest.main()
