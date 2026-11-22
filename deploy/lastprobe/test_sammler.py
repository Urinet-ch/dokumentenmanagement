"""Tests für die isolierte Ressourcenerfassung."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import sammler
import bericht

PROJEKT = "archiv-lastprobe-a1b2c3d4e5f6"


class SammlerTest(unittest.TestCase):
    def test_erfasst_nur_die_benannten_container_und_host(self):
        stats = "\n".join(json.dumps({"Name": f"{PROJEKT}-{dienst}-1", "MemUsage": "1MiB / 2MiB",
                                      "CPUPerc": "1.5%"}) for dienst in sammler.DIENSTE)
        with patch.object(sammler.subprocess, "run", side_effect=[
                subprocess.CompletedProcess([], 0, stdout=stats),
                subprocess.CompletedProcess([], 0, stdout="Mem: 4000 1000 1000 0 2000 2500\n"),
        ]) as run:
            docker, host = sammler.aufnehmen(PROJEKT)
        self.assertEqual(len(docker.splitlines()), len(sammler.DIENSTE))
        self.assertTrue(all(json.loads(z)["Zeit"] for z in docker.splitlines()))
        self.assertIn("Mem: 4000", host)
        befehl = run.call_args_list[0].args[0]
        self.assertEqual(befehl[:5], ["docker", "stats", "--no-stream", "--format", "{{json .}}"])
        self.assertEqual(befehl[5:], [f"{PROJEKT}-{d}-1" for d in sammler.DIENSTE])
        with tempfile.TemporaryDirectory() as ordner:
            pfad = Path(ordner)
            (pfad / "stats").write_text(docker, encoding="utf-8")
            (pfad / "host").write_text(host, encoding="utf-8")
            self.assertEqual(len(bericht.docker_spitzen(pfad / "stats", befehl[5:])), len(sammler.DIENSTE))
            self.assertEqual(bericht.host_spitzen(pfad / "host"), (4000, 1000, 2500))

    def test_lehnt_fremden_oder_fehlenden_container_ab(self):
        stats = json.dumps({"Name": "fremd-api-1"})
        with patch.object(sammler.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=stats)):
            with self.assertRaisesRegex(ValueError, "unerwarteten"):
                sammler.aufnehmen(PROJEKT)
        with patch.object(sammler.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout="")):
            with self.assertRaisesRegex(ValueError, "nicht alle Container"):
                sammler.aufnehmen(PROJEKT)

    def test_lehnt_andere_projekte_vor_jedem_docker_aufruf_ab(self):
        with tempfile.TemporaryDirectory() as ordner:
            pfad = Path(ordner)
            lauf = subprocess.run([
                sys.executable, str(Path(sammler.__file__)), "--projekt", "archiv",
                "--docker-stats", str(pfad / "stats"), "--host-speicher", str(pfad / "host"),
            ], capture_output=True, text=True)
            self.assertNotEqual(lauf.returncode, 0)
            self.assertIn("--projekt muss", lauf.stderr)
            self.assertFalse((pfad / "stats").exists())


if __name__ == "__main__":
    unittest.main()
