"""Synthetische Tests der Stufe-A-Stichproben und Budgetauswertung."""

import csv
import io
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER))

import speicherauswertung_stufe_a as auswertung
import speichermessung_stufe_a as messung

IDS = ["a" * 12, "b" * 12, "c" * 12]


def docker_antwort(argumente, **_kwargs):
    kennung = argumente[-1]
    dienst = IDS.index(kennung)
    if argumente[1] == "inspect":
        limits = [(1024, 600000000), (768, 500000000), (256, 400000000)]
        mib, nano = limits[dienst]
        ausgabe = f"archiv {('api', 'kc', 'db')[dienst]} {mib * 1024 ** 2} {nano} 0 0"
    else:
        ausgabe = ["100MiB / 1024MiB", "0.2GiB / 768MiB", "10MB / 256MiB"][dienst]
    return subprocess.CompletedProcess(argumente, 0, stdout=ausgabe, stderr="")


class SpeichermessungTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.pfade = [Path(self.tmp.name) / f"{phase}.csv" for phase in auswertung.PHASEN]
        with patch.object(messung.subprocess, "run", side_effect=docker_antwort), patch.object(messung.time, "sleep"):
            for phase, pfad in zip(auswertung.PHASEN, self.pfade):
                messung.messen(phase, pfad, 2, 1, IDS)

    def test_sammelt_sanitisiert_und_auswertung_an_budgetgrenze(self):
        for pfad in self.pfade:
            self.assertEqual(os.stat(pfad).st_mode & 0o777, 0o600)
            self.assertFalse(any(kennung in pfad.read_text() for kennung in IDS))
        ergebnis = auswertung.auswerten(self.pfade)
        self.assertEqual(ergebnis["messwerte"]["ocr"][2], auswertung.Decimal("0.2") * 1024 ** 3)
        self.assertEqual(auswertung.abbruch(ergebnis, "nein", "ok"), "kein-abbruch")
        self.assertEqual(auswertung.abbruch(ergebnis, "manuell", "ok"), "kein-abbruch")
        self.assertEqual(auswertung.abbruch(ergebnis, "unbekannt", "ok"), "ungeklaert")
        self.assertEqual(auswertung.abbruch(ergebnis, "ja", "ok"), "abbruch")
        self.assertEqual(auswertung.abbruch(ergebnis, "nein", "fehler"), "abbruch")
        ergebnis["limits"][1] = (1024 * 1024 ** 2 - 1, auswertung.Decimal("0.599999999"))
        self.assertEqual(auswertung.abbruch(ergebnis, "nein", "ok"), "kein-abbruch")
        ergebnis["limits"][1] = (1024 * 1024 ** 2 + 1, auswertung.Decimal("0.6"))
        self.assertEqual(auswertung.abbruch(ergebnis, "nein", "ok"), "abbruch")
        ergebnis["limits"][1] = (1024 * 1024 ** 2, auswertung.Decimal("0.600000001"))
        self.assertEqual(auswertung.abbruch(ergebnis, "nein", "ok"), "abbruch")

    def test_unvollstaendige_und_veraenderte_messung_abgelehnt(self):
        pfad = self.pfade[1]
        zeilen = pfad.read_text().splitlines()
        pfad.write_text("\n".join(zeilen[:-1]) + "\n")
        with self.assertRaisesRegex(ValueError, "unvollständig"):
            auswertung.auswerten(self.pfade)
        pfad.write_text("\n".join(zeilen) + "\n")
        with pfad.open(newline="") as datei:
            leser = csv.DictReader(datei)
            daten = list(leser)
        daten[-1]["mem_limit_bytes"] = "1"
        with pfad.open("w", newline="") as datei:
            schreiber = csv.DictWriter(datei, fieldnames=messung.SPALTEN)
            schreiber.writeheader()
            schreiber.writerows(daten)
        with self.assertRaisesRegex(ValueError, "Limits änderten"):
            auswertung.auswerten(self.pfade)

    def test_fehler_und_ueberschreiben_werden_nicht_verdeckt(self):
        with self.assertRaises(FileExistsError):
            messung.messen("ocr", self.pfade[0], 1, 0, IDS)
        with self.assertRaisesRegex(ValueError, "verschiedene"):
            messung.messen("ocr", Path(self.tmp.name) / "doppelt", 1, 0, [IDS[0]] * 3)
        with patch.object(messung.subprocess, "run", return_value=subprocess.CompletedProcess(
                ["docker"], 1, stdout="", stderr="Daemon nicht erreichbar")):
            with self.assertRaisesRegex(RuntimeError, "Docker-Aufruf fehlgeschlagen"):
                messung.messen("ocr", Path(self.tmp.name) / "fehler.csv", 1, 0, IDS)
        with patch.object(messung.subprocess, "run", return_value=subprocess.CompletedProcess(
                ["docker"], 0, stdout="fremd api 123 123 0 0", stderr="")):
            with self.assertRaisesRegex(ValueError, "nicht der erwartete Archiv-Dienst"):
                messung.messen("ocr", Path(self.tmp.name) / "fremd.csv", 1, 0, IDS)
        with patch.object(messung.subprocess, "run", side_effect=subprocess.TimeoutExpired("docker", 60)):
            with self.assertRaisesRegex(RuntimeError, "60 Sekunden"):
                messung.messen("ocr", Path(self.tmp.name) / "timeout.csv", 1, 0, IDS)

    def test_cpu_ohne_limit_und_abweichende_limits_abgelehnt(self):
        self.assertEqual(auswertung.cpu_limit(0, 100000, 25000), auswertung.Decimal("0.25"))
        with self.assertRaisesRegex(ValueError, "CPU-Limit fehlt"):
            auswertung.cpu_limit(0, 0, 0)
        with self.assertRaisesRegex(ValueError, "Widersprüchliche"):
            auswertung.cpu_limit(300000000, 100000, 20000)

    def test_vorpruefung_und_einheiten(self):
        ergebnis = auswertung.auswerten(self.pfade[:1], vorpruefung=True)
        self.assertEqual(auswertung.abbruch(ergebnis, "nein", "ok"), "kein-abbruch")
        self.assertEqual(auswertung.speicher_bytes("1.5kB"), 1500)
        with self.assertRaisesRegex(ValueError, "genau eine Phase"):
            auswertung.auswerten(self.pfade[:2], vorpruefung=True)
        with self.assertRaisesRegex(ValueError, "Alle drei"):
            auswertung.auswerten(self.pfade[:1])

    def test_cli_tabelle_und_exit_codes(self):
        for oom, health, erwartet in (("nein", "ok", 0), ("ja", "ok", 1), ("unbekannt", "ok", 2)):
            ausgabe = io.StringIO()
            args = ["auswertung", *map(str, self.pfade), "--oom", oom, "--health", health]
            with patch.object(sys, "argv", args), redirect_stdout(ausgabe):
                self.assertEqual(auswertung.main(), erwartet)
            self.assertEqual(ausgabe.getvalue().count("| ocr |"), 4)
            self.assertIn("Limit-Summe: 2048.0 MiB; CPU-Summe: 1.5.", ausgabe.getvalue())

    def test_fehlerausgaben_enthalten_keine_ids_pfade_oder_csv_inhalte(self):
        marker = "synthetisch-vertraulich"
        ausgabe = Path(self.tmp.name) / f"{marker}.csv"
        args = ["messung", "ocr", str(ausgabe), "--anzahl", "1", "--intervall", "0", "--container", *IDS]
        for meldung in ("No such container", "error: no such object"):
            ausgabe.unlink(missing_ok=True)
            fehler = io.StringIO()
            antwort = subprocess.CompletedProcess(["docker"], 1, stdout="", stderr=f"{meldung}: {IDS[0]} {marker}")
            with patch.object(sys, "argv", args), patch.object(messung.subprocess, "run", return_value=antwort), redirect_stderr(fehler):
                self.assertEqual(messung.main(), 2)
            self.assertIn("Container nicht gefunden", fehler.getvalue())
            self.assertNotIn(IDS[0], fehler.getvalue())
            self.assertNotIn(marker, fehler.getvalue())

        fehlender_pfad = Path(self.tmp.name) / marker / "daten.csv"
        args[2] = str(fehlender_pfad)
        fehler = io.StringIO()
        with patch.object(sys, "argv", args), redirect_stderr(fehler):
            self.assertEqual(messung.main(), 2)
        self.assertIn("errno", fehler.getvalue())
        self.assertNotIn(marker, fehler.getvalue())
        self.assertNotIn(str(fehlender_pfad), fehler.getvalue())

        for feld in ("lauf", "zeit_utc"):
            pfad = self.pfade[0]
            with pfad.open(newline="") as datei:
                daten = list(csv.DictReader(datei))
            original = daten[0][feld]
            daten[0][feld] = marker
            with pfad.open("w", newline="") as datei:
                schreiber = csv.DictWriter(datei, fieldnames=messung.SPALTEN)
                schreiber.writeheader()
                schreiber.writerows(daten)
            args = ["auswertung", *map(str, self.pfade), "--oom", "nein", "--health", "ok"]
            fehler = io.StringIO()
            with patch.object(sys, "argv", args), redirect_stderr(fehler):
                self.assertEqual(auswertung.main(), 2)
            self.assertIn("CSV-Zeile 2", fehler.getvalue())
            self.assertNotIn(marker, fehler.getvalue())
            daten[0][feld] = original
            with pfad.open("w", newline="") as datei:
                schreiber = csv.DictWriter(datei, fieldnames=messung.SPALTEN)
                schreiber.writeheader()
                schreiber.writerows(daten)

        args = ["auswertung", str(fehlender_pfad), "--vorpruefung", "--oom", "nein", "--health", "ok"]
        fehler = io.StringIO()
        with patch.object(sys, "argv", args), redirect_stderr(fehler):
            self.assertEqual(auswertung.main(), 2)
        self.assertIn("errno", fehler.getvalue())
        self.assertNotIn(marker, fehler.getvalue())

        args[-1] = marker
        fehler = io.StringIO()
        with patch.object(sys, "argv", args), redirect_stderr(fehler), self.assertRaises(SystemExit) as ende:
            auswertung.main()
        self.assertEqual(ende.exception.code, 2)
        self.assertIn("Ungültige Argumente", fehler.getvalue())
        self.assertNotIn(marker, fehler.getvalue())


if __name__ == "__main__":
    unittest.main()
