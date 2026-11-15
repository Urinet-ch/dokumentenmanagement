"""Tests für .github/scripts/pruefe-speicherbudget.py (Req 16.24).

Aufruf: python3 -m unittest tools/test_pruefe_speicherbudget.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / ".github" / "scripts" / "pruefe-speicherbudget.py"


class SpeicherbudgetTest(unittest.TestCase):

    def pruefen(self, dienste: dict) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "1024", "2.0"],
            input=json.dumps({"services": dienste}),
            text=True,
            capture_output=True,
            check=False,
        )

    def test_probe_ueber_mem_limit_ist_rot(self):
        ergebnis = self.pruefen({"api": {"mem_limit": 1025 * 1024 * 1024, "cpus": 1.0}})

        self.assertEqual(1, ergebnis.returncode)
        self.assertIn("Zielhosts", ergebnis.stdout)

    def test_probe_ueber_cpu_budget_ist_rot(self):
        ergebnis = self.pruefen({"api": {"mem_limit": 1024 * 1024 * 1024, "cpus": 2.1}})

        self.assertEqual(1, ergebnis.returncode)
        self.assertIn("Zielhosts", ergebnis.stdout)


if __name__ == "__main__":
    unittest.main()
