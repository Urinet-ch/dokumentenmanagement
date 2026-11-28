#!/usr/bin/env python3
"""Führt alle unittest-Tests der Messwerkzeuge aus."""

import sys
import unittest
from pathlib import Path


if __name__ == "__main__":
    tests = unittest.defaultTestLoader.discover(str(Path(__file__).resolve().parent), pattern="test_*.py")
    ergebnis = unittest.TextTestRunner(verbosity=2).run(tests)
    sys.exit(0 if ergebnis.wasSuccessful() else 1)
