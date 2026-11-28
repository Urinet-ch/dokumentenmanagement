import copy
import io
import json
import subprocess
import unittest
from unittest.mock import patch

import wechsel_pruefen as wp


def bestand():
    soll = {
        "realms": ["r1"],
        "mandanten": ["alpha", "beta"],
        "rollen": ["rolle-1"],
        "benutzer": [
            {"id": "dienstkonto", "realm": "r1", "mandanten": []},
            {"id": "person-1", "realm": "r1", "mandanten": ["alpha"]},
        ],
        "clients": [],
    }
    archiv = {"mandanten": soll["mandanten"].copy(), "clients": [], "sicher": True}
    keycloak = {
        "realms": soll["realms"].copy(), "benutzer": copy.deepcopy(soll["benutzer"]),
        "rollen": soll["rollen"].copy(), "federation": 0, "sicher": True,
    }
    return soll, archiv, keycloak


class WechselPruefenTest(unittest.TestCase):
    def test_leerer_clientbestand_und_erwartete_benutzer(self):
        soll, archiv, keycloak = bestand()
        sauber, meldungen = wp.pruefen(soll, archiv, keycloak)
        self.assertTrue(sauber)
        self.assertIn("Mandant beta, Benutzer: 0 vorhanden, 0 unerwartet, 0 fehlen", meldungen)
        self.assertIn("Benutzer ohne Mandant: 1", meldungen)

    def test_zwei_mandanten_unerwartete_tokens_und_benutzer_sperren(self):
        soll, archiv, keycloak = bestand()
        archiv["clients"] = [
            {"id": "eins", "mandant": "alpha", "scope": "generator"},
            {"id": "zwei", "mandant": "beta", "scope": "webseite-lesen"},
        ]
        keycloak["benutzer"].append({"id": "test", "realm": "r1", "mandanten": ["beta"]})
        sauber, meldungen = wp.pruefen(soll, archiv, keycloak)
        self.assertFalse(sauber)
        self.assertIn("Mandant alpha, API-Tokens: 1 vorhanden, 1 unerwartet, 0 fehlen", meldungen)
        self.assertIn("Mandant beta, Webseiten-Schlüssel: 1 vorhanden, 1 unerwartet, 0 fehlen", meldungen)

    def test_freigegebene_clients_sowie_fehlerhafte_oder_fremde_rolle(self):
        soll, archiv, keycloak = bestand()
        client = {"id": "prod", "mandant": "beta", "scope": "webseite-erfassung"}
        soll["clients"].append(client)
        archiv["clients"].append(client)
        self.assertTrue(wp.pruefen(soll, archiv, keycloak)[0])
        keycloak["rollen"].append("ungeplant")
        self.assertFalse(wp.pruefen(soll, archiv, keycloak)[0])
        keycloak["rollen"] = []
        self.assertFalse(wp.pruefen(soll, archiv, keycloak)[0])

    def test_unvollstaendige_oder_mehrdeutige_daten_sperren(self):
        for mutation in ("rls", "federation", "scope", "unbekannt", "mehrdeutig", "realm", "doppelt"):
            soll, archiv, keycloak = bestand()
            if mutation == "rls":
                archiv["sicher"] = None
            elif mutation == "federation":
                keycloak["federation"] = 1
            elif mutation == "scope":
                archiv["clients"].append({"id": "a", "mandant": "alpha", "scope": "?"})
            elif mutation == "unbekannt":
                keycloak["benutzer"].append({"id": "b", "realm": "r1", "mandanten": ["fremd"]})
            elif mutation == "mehrdeutig":
                keycloak["benutzer"][1]["mandanten"] = ["alpha", "beta"]
            elif mutation == "realm":
                soll["realms"] = []
            else:
                soll["benutzer"].append({**soll["benutzer"][0], "realm": "r2"})
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                wp.pruefen(soll, archiv, keycloak)

    def test_doppelte_json_felder_und_verbindungsfehler(self):
        with self.assertRaises(ValueError):
            json.loads('{"rollen":[],"rollen":[]}', object_pairs_hook=wp.objekt)
        for antwort in (subprocess.CompletedProcess([], 1, "", "fehler"),
                        subprocess.CompletedProcess([], 0, "unvollständig", "")):
            with patch.object(wp.subprocess, "run", return_value=antwort), self.assertRaises(ValueError):
                wp.lesen("lesedienst", wp.ARCHIV_SQL)
        with self.assertRaises(ValueError):
            wp.lesen("falsch service", wp.ARCHIV_SQL)
        with patch.dict(wp.os.environ, {"PGPASSWORD": "synthetisch"}), self.assertRaises(ValueError):
            wp.lesen("lesedienst", wp.ARCHIV_SQL)

    def test_cli_fehlschlag_und_lesetransaktion(self):
        soll, archiv, keycloak = bestand()
        keycloak["benutzer"].append({"id": "alt", "realm": "r1", "mandanten": []})
        with patch("sys.argv", ["wechsel_pruefen.py", "--archiv-service", "a", "--keycloak-service", "k",
                                "--soll", "bestand.json"]), \
             patch.object(wp.Path, "open", return_value=io.StringIO(json.dumps(soll))), \
             patch.object(wp, "lesen", side_effect=[archiv, keycloak]), \
             patch("sys.stdout", new_callable=io.StringIO), patch("sys.stderr", new_callable=io.StringIO):
            self.assertEqual(wp.main(), 1)
        for sql in (wp.ARCHIV_SQL, wp.KEYCLOAK_SQL):
            self.assertIn("REPEATABLE READ READ ONLY", sql)

    def test_cli_bei_datenbankausfall_nicht_erfolgreich(self):
        soll, _, _ = bestand()
        with patch("sys.argv", ["wechsel_pruefen.py", "--archiv-service", "a", "--keycloak-service", "k",
                                "--soll", "bestand.json"]), \
             patch.object(wp.Path, "open", return_value=io.StringIO(json.dumps(soll))), \
             patch.object(wp, "lesen", side_effect=ValueError("Datenbankabfrage fehlgeschlagen.")), \
             patch("sys.stdout", new_callable=io.StringIO), \
             patch("sys.stderr", new_callable=io.StringIO) as fehler:
            self.assertEqual(wp.main(), 2)
            self.assertIn("Datenbankabfrage fehlgeschlagen", fehler.getvalue())


if __name__ == "__main__":
    unittest.main()
