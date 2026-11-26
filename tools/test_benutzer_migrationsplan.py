import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.benutzer_migrationsplan import plan_erstellen


def benutzer(**aenderungen):
    daten = {"kennung": "synthetisch-eins", "mandanten": ["test"], "rollen": ["inhaber"],
             "vorhanden": True, "totp": True, "passkey": False}
    daten.update(aenderungen)
    return daten


class MigrationsplanTest(unittest.TestCase):
    def test_bestehend_und_neuanlage_ohne_schreibvorgang(self):
        plan = plan_erstellen([benutzer(), benutzer(kennung="synthetisch-zwei", vorhanden=False)])
        self.assertEqual([x["aktion"] for x in plan["benutzer"]],
                         ["bestehenden Zugang abgleichen", "Anlage prüfen"])
        self.assertIn("optional", plan["benutzer"][0]["zugang"]["passkey"])
        self.assertFalse(any(x["konflikte"] for x in plan["benutzer"]))

    def test_konflikte_verhindern_uebernahmeplan(self):
        plan = plan_erstellen([benutzer(mandanten=[]), benutzer(rollen=["inhaber", "andere"], vorhanden=None)])
        for eintrag in plan["benutzer"]:
            self.assertEqual(eintrag["aktion"], "Konflikte klären; keine Übernahme planen")
            self.assertIn("Kennung mehrfach vorhanden", eintrag["konflikte"])
        self.assertGreaterEqual(len(plan["benutzer"][1]["konflikte"]), 3)

    def test_keine_echten_kennungen_oder_weiteren_felder(self):
        for eintrag in (benutzer(kennung="real"), benutzer(email="person@example.invalid"),
                        benutzer(totp="ja"), benutzer(rollen="inhaber")):
            with self.subTest(eintrag=eintrag), self.assertRaises(ValueError):
                plan_erstellen([eintrag])

    def test_cli_liest_datei_und_gibt_konflikte_aus(self):
        with tempfile.TemporaryDirectory() as ordner:
            pfad = Path(ordner) / "eingabe.json"
            pfad.write_text(json.dumps([benutzer(mandanten=[])]), encoding="utf-8")
            skript = Path(__file__).with_name("benutzer_migrationsplan.py")
            ergebnis = subprocess.run([sys.executable, str(skript), str(pfad)], capture_output=True, text=True)
            self.assertEqual(ergebnis.returncode, 1)
            self.assertTrue(json.loads(ergebnis.stdout)["benutzer"][0]["konflikte"])
            self.assertEqual(list(Path(ordner).iterdir()), [pfad])


if __name__ == "__main__":
    unittest.main()
