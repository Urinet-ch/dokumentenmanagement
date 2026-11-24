import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "migration", Path(__file__).with_name("benutzer-migration-entwurf.py")
)
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


def antwort(geplant=0, uebernommen=0, abweichungen=None, code=0):
    bericht = {
        "mandant": "test-mandant",
        "abweichungen": abweichungen if abweichungen is not None else [],
        "uebernahme_geplant": geplant,
        "uebernommen": uebernommen,
    }
    return subprocess.CompletedProcess([], code, json.dumps(bericht), "vertrauliches Log")


class MigrationTest(unittest.TestCase):
    def ausfuehren(self, antworten, bestaetigung="UEBERNEHMEN", tty=True):
        ausgabe, fehler = io.StringIO(), io.StringIO()
        with (patch.object(sys, "argv", ["entwurf", "/starter", "test-mandant", "--uebernehmen"]),
              patch.object(sys.stdin, "isatty", return_value=tty),
              patch("builtins.input", return_value=bestaetigung),
              patch.object(migration.subprocess, "run", side_effect=antworten) as aufruf,
              contextlib.redirect_stdout(ausgabe), contextlib.redirect_stderr(fehler)):
            code = migration.main()
        self.assertNotIn("vertrauliches Log", ausgabe.getvalue() + fehler.getvalue())
        return code, aufruf.call_args_list, fehler.getvalue()

    def test_schreiben_nur_nach_pruefung_und_nachpruefung(self):
        code, aufrufe, _ = self.ausfuehren([antwort(2), antwort(2, 2), antwort()])
        self.assertEqual(0, code)
        self.assertEqual(3, len(aufrufe))
        self.assertIn("--pruefen", aufrufe[0].args[0])
        self.assertNotIn("--pruefen", aufrufe[1].args[0])
        self.assertIn("--pruefen", aufrufe[2].args[0])

    def test_fehler_und_abweichungen_verhindern_schreiben(self):
        for bericht in (antwort(code=1), antwort(code=3),
                        antwort(abweichungen=[{"code": "TEST", "benutzer": "synthetisch"}])):
            with self.subTest(bericht=bericht):
                code, aufrufe, fehler = self.ausfuehren([bericht])
                self.assertEqual(1, code)
                self.assertEqual(1, len(aufrufe))
                self.assertNotIn("synthetisch", fehler)

    def test_keine_uebernahme_ohne_interaktives_einverstaendnis(self):
        for bestaetigung, tty in (("NEIN", True), ("UEBERNEHMEN", False)):
            with self.subTest(tty=tty):
                code, aufrufe, _ = self.ausfuehren([antwort(1)], bestaetigung, tty)
                self.assertEqual(1, code)
                self.assertEqual(1, len(aufrufe))

    def test_keine_bestaetigung_oder_schreiben_bei_null_geplanten_uebernahmen(self):
        code, aufrufe, _ = self.ausfuehren([antwort()], tty=False)
        self.assertEqual(0, code)
        self.assertEqual(1, len(aufrufe))

    def test_dienstfehler_ist_von_abweichung_unterschieden(self):
        _, _, dienstfehler = self.ausfuehren([antwort(code=3)])
        _, _, abweichung = self.ausfuehren([antwort(code=1)])
        self.assertIn("technischer Fehler oder Dienst nicht erreichbar", dienstfehler)
        self.assertIn("Abweichung oder fachlicher Fehler", abweichung)
        self.assertIn("nichts geschrieben", dienstfehler)

    def test_abweichung_erst_im_schreiblauf_meldet_moegliche_uebernahmen(self):
        code, aufrufe, fehler = self.ausfuehren([
            antwort(2), antwort(2, 2, [{"code": "TEST", "benutzer": "synthetisch"}], code=1)
        ])
        self.assertEqual(1, code)
        self.assertEqual(2, len(aufrufe))
        self.assertIn("können bereits Zeilen bestehen", fehler)
        self.assertNotIn("synthetisch", fehler)

    def test_teilweiser_lauf_oder_offene_nachpruefung_meldet_abbruch(self):
        for antworten in ([antwort(2), antwort(2, 1)],
                          [antwort(2), antwort(2, 2), antwort(1)]):
            with self.subTest(antworten=antworten):
                code, _, fehler = self.ausfuehren(antworten)
                self.assertEqual(1, code)
                self.assertIn("können bereits Zeilen bestehen", fehler)

    def test_ungueltiger_bericht_bricht_ab(self):
        for inhalt in ("nicht json", "{}", '{"mandant":"test-mandant","abweichungen":[],"uebernahme_geplant":true,"uebernommen":0}'):
            with self.subTest(inhalt=inhalt), patch.object(
                migration.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, inhalt, "")
            ):
                with self.assertRaises(RuntimeError):
                    migration.abgleichen("/starter", "test-mandant", True)

    def test_synthetischer_starter_erhaelt_alle_argumente_unveraendert(self):
        with tempfile.TemporaryDirectory() as verzeichnis:
            starter = Path(verzeichnis) / "starter"
            starter.write_text(
                "#!/usr/bin/env python3\n"
                "import json, sys\n"
                "assert sys.argv[1:] == ['benutzer', 'abgleichen', 'test-mandant', '--pruefen', '--json']\n"
                "print(json.dumps({'mandant': 'test-mandant', 'abweichungen': [], "
                "'uebernahme_geplant': 2, 'uebernommen': 0}))\n",
                encoding="utf-8",
            )
            starter.chmod(0o700)
            self.assertEqual(2, migration.abgleichen(str(starter), "test-mandant", True)["uebernahme_geplant"])


if __name__ == "__main__":
    unittest.main()
