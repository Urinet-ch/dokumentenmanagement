"""Tests für pruefe-testaufteilung.py: je Job zählt nur der neueste Versuch (Re-run failed jobs)."""
import importlib.util
import pathlib
import tempfile
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "pruefe_testaufteilung", pathlib.Path(__file__).with_name("pruefe-testaufteilung.py"))
modul = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(modul)


def bericht(verzeichnis, klasse, fehler):
    verzeichnis.mkdir(parents=True, exist_ok=True)
    (verzeichnis / f"TEST-{klasse}.xml").write_text(
        f'<testsuite name="{klasse}" tests="1" skipped="0" failures="{fehler}" errors="0"/>', encoding="utf-8")


class NeuesterVersuchTest(unittest.TestCase):
    def test_nimmt_den_hoechsten_versuch(self):
        with tempfile.TemporaryDirectory() as tmp:
            basis = pathlib.Path(tmp)
            for versuch in (1, 2, 10):
                (basis / f"surefire-gruppe-1-{versuch}").mkdir()
            (basis / "surefire-gruppe-10-1").mkdir()
            self.assertEqual(modul.neuester(basis / "surefire-gruppe-1").name, "surefire-gruppe-1-10")

    def test_ohne_versuch_bleibt_der_pfad(self):
        with tempfile.TemporaryDirectory() as tmp:
            pfad = pathlib.Path(tmp) / "surefire-backend"
            self.assertEqual(modul.neuester(pfad), pfad)

    def test_alter_fehlbericht_zaehlt_nicht(self):
        with tempfile.TemporaryDirectory() as tmp:
            basis = pathlib.Path(tmp)
            quellen = basis / "src" / "a"
            quellen.mkdir(parents=True)
            (quellen / "ATest.java").write_text("class ATest { @Test void a() {} }", encoding="utf-8")
            bericht(basis / "b" / "surefire-gruppe-1-1", "a.ATest", 1)
            bericht(basis / "b" / "surefire-gruppe-1-2", "a.ATest", 0)
            rc = modul.main(["x", str(basis / "src"), f"gruppe-1={basis / 'b' / 'surefire-gruppe-1'}"])
            self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
