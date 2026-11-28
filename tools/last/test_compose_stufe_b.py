"""Prüft die normalisierte Messvorlage mit Compose, ohne Containerstart."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("docker"), "Docker-CLI nicht installiert")
class ComposeTest(unittest.TestCase):
    def test_isolation_limits_und_stubs(self):
        with tempfile.TemporaryDirectory() as ordner:
            env = {**os.environ, "LAST_SECRETS": ordner, "LAST_S3_ENDPOINT": "https://s3.example.invalid",
                   "LAST_S3_REGION": "messregion", "LAST_API_IMAGE": "example.invalid/archiv:test",
                   "LAST_DB_IMAGE": "example.invalid/db:test", "LAST_KC_IMAGE": "example.invalid/kc:test",
                   "LAST_BUCKET_PRAEFIX": "urinet-archiv-a-"}
            lauf = subprocess.run(["docker", "compose", "-f", str(REPO / "deploy/last/compose.yml"),
                                   "config", "--format", "json"], env=env, capture_output=True, text=True,
                                  timeout=30, check=True)
            config = json.loads(lauf.stdout)
            self.assertEqual(config["name"], "archiv-last")
            dienste = config["services"]
            self.assertEqual(set(dienste), {"api", "db", "kc"})
            self.assertLessEqual(sum(int(s["mem_limit"]) for s in dienste.values()), 2144 * 1024**2)
            self.assertLessEqual(sum(float(s["cpus"]) for s in dienste.values()), 1.6)
            self.assertTrue(config["networks"]["intern"]["internal"])
            for dienst in dienste.values():
                self.assertFalse(dienst.get("ports"))
                self.assertFalse(dienst.get("labels"))
                self.assertNotEqual(dienst["user"].split(":")[0], "0")
                self.assertTrue(dienst["read_only"])
                self.assertIn("ALL", dienst["cap_drop"])
            for netz in config["networks"].values():
                self.assertFalse(netz.get("external", False))
            self.assertEqual(set(dienste["db"]["networks"]), {"intern"})
            self.assertEqual(set(dienste["kc"]["networks"]), {"intern"})
            self.assertEqual(dienste["api"]["environment"]["ARCHIV_KI_STUB"], "true")
            self.assertEqual(dienste["api"]["environment"]["ARCHIV_OCR_STUB"], "false")
            self.assertEqual(dienste["api"]["environment"]["ARCHIV_STUFE"], "A")
            self.assertEqual(dienste["api"]["environment"]["ARCHIV_ABLAGE_BUCKET_PRAEFIX"] + "last",
                             "urinet-archiv-a-last")
            self.assertEqual(set(v["name"] for v in config["volumes"].values()),
                             {"archiv_last_daten", "archiv_last_arbeit"})
            for secret in config["secrets"].values():
                self.assertTrue(Path(secret["file"]).is_relative_to(ordner))


if __name__ == "__main__":
    unittest.main()
