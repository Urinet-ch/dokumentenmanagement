import copy
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import pruefe_compose_konsistenz as pruefung


def modell():
    labels = {
        "traefik.http.routers.archiv-public.rule": "PathPrefix(`/public/`)",
        "traefik.http.routers.archiv-public.middlewares": "archiv-public-drossel",
        "traefik.http.routers.archiv-public-suche.rule": "Path(`/public/v1/suche`) || Path(`/public/v1/suche/`)",
        "traefik.http.routers.archiv-public-suche.middlewares": "archiv-public-drossel,archiv-public-suche-drossel",
    }
    for name in ("archiv-public-drossel", "archiv-public-suche-drossel"):
        for feld in ("average", "burst"):
            labels[f"traefik.http.middlewares.{name}.ratelimit.{feld}"] = "1"
        labels[f"traefik.http.middlewares.{name}.ratelimit.period"] = "1s"
    def dienst(web):
        return {
            "image": "example.invalid/test@sha256:" + "a" * 64,
            "healthcheck": {"test": ["CMD", "true"], "interval": "1s", "timeout": "1s", "retries": 1},
            "mem_limit": "64m", "cpus": "0.5",
            "networks": {"intern": {}, **({"web-archiv": {}} if web else {})},
            "environment": {},
        }
    api, db, kc = dienst(True), dienst(False), dienst(True)
    labels["traefik.docker.network"] = "web-archiv"
    api["labels"] = labels
    kc["labels"] = {"traefik.docker.network": "web-archiv"}
    return {"services": {"api": api, "db": db, "kc": kc},
            "networks": {"intern": {"internal": True}, "web-archiv": {"external": True}}}


class ComposePruefungTest(unittest.TestCase):
    def test_synthetische_vorlage_ohne_befund(self):
        self.assertEqual([], pruefung.pruefe_compose(modell()))

    def test_auth_router_darf_nicht_middleware_der_api_nutzen(self):
        daten = modell()
        daten["services"]["api"]["labels"]["traefik.http.middlewares.archiv-sts.headers.stsseconds"] = "86400"
        daten["services"]["kc"]["labels"] = [
            "traefik.docker.network=web-archiv",
            "traefik.http.routers.archiv-auth.middlewares=archiv-sts",
        ]
        befunde = pruefung.pruefe_compose(daten)
        self.assertEqual(
            [("MIDDLEWARE_FREMDER_DIENST", "services.kc.routers.archiv-auth")],
            [(b["code"], b["ort"]) for b in befunde],
        )

    def test_auth_router_mit_eigener_middleware_und_docker_provider(self):
        daten = modell()
        daten["services"]["kc"]["labels"] = [
            "traefik.docker.network=web-archiv",
            "traefik.http.routers.archiv-auth.middlewares= archiv-auth-sts@docker ",
            "traefik.http.middlewares.archiv-auth-sts.headers.stsseconds=86400",
        ]
        self.assertEqual([], pruefung.pruefe_compose(daten))
        daten["services"]["kc"]["labels"][1] = "traefik.http.routers.archiv-auth.middlewares=archiv-auth-sts@file"
        self.assertIn("MIDDLEWARE_FREMDER_DIENST", {b["code"] for b in pruefung.pruefe_compose(daten)})

    def test_jede_kategorie_meldet_regression(self):
        aenderungen = (
            ("HEALTHCHECK", lambda d: d["services"]["db"].pop("healthcheck")),
            ("HEALTHCHECK", lambda d: d["services"]["db"]["healthcheck"].update(timeout="0s")),
            ("RESSOURCEN", lambda d: d["services"]["api"].update(cpus="0")),
            ("DIENST_NETZ", lambda d: d["services"]["db"].update(networks={"fremd": {}})),
            ("WEB_ZUGRIFF", lambda d: d["services"]["db"]["networks"].update({"web-archiv": {}})),
            ("WEB_ZUGRIFF", lambda d: d["services"]["api"]["networks"].update(web={})),
            ("TRAEFIK_NETZ", lambda d: d["services"]["kc"]["labels"].update({"traefik.docker.network": "web"})),
            ("SECRET_ZUGRIFF", lambda d: d["services"]["kc"].update(secrets=[{"source": "archiv_manifest_key"}])),
            ("SECRET_ZUGRIFF", lambda d: d["services"]["db"].update(
                secrets=[{"source": "archiv_db_postgres", "target": "fremd"}])),
            ("SECRET_VOLUME", lambda d: d["services"]["db"].update(volumes=["./datei:/run/secrets/fremd:ro"])),
            ("PORTS", lambda d: d["services"]["db"].update(ports=["1234:1234"])),
            ("IMAGE_DIGEST", lambda d: d["services"]["kc"].update(image="example.invalid/test:latest")),
            ("PUBLIC_ROUTER", lambda d: d["services"]["api"]["labels"].pop(
                "traefik.http.routers.archiv-public.middlewares")),
            ("PUBLIC_ROUTER", lambda d: d["services"]["api"]["labels"].update(
                {"traefik.http.routers.archiv-public.rule": "!PathPrefix(`/public/`)"})),
            ("RATELIMIT", lambda d: d["services"]["api"]["labels"].update(
                {"traefik.http.middlewares.archiv-public-drossel.ratelimit.average": "0"})),
            ("RATELIMIT", lambda d: d["services"]["api"]["labels"].update(
                {"traefik.http.middlewares.archiv-public-drossel.ratelimit.period": "0s"})),
        )
        for code, aendern in aenderungen:
            with self.subTest(code=code):
                daten = modell()
                aendern(daten)
                self.assertIn(code, {b["code"] for b in pruefung.pruefe_compose(daten)})

    def test_sftp_nutzt_nur_archivnetz_und_keine_hostports(self):
        daten = modell()
        daten["services"]["sftp"] = copy.deepcopy(daten["services"]["kc"])
        self.assertEqual([], pruefung.pruefe_compose(daten))
        daten["services"]["sftp"]["networks"]["web"] = {}
        daten["services"]["sftp"]["ports"] = ["2222:22"]
        codes = {b["code"] for b in pruefung.pruefe_compose(daten)}
        self.assertTrue({"WEB_ZUGRIFF", "PORTS"} <= codes)

    def test_klartext_und_defaultwerte_werden_ohne_wert_gemeldet(self):
        daten = modell()
        daten["services"]["kc"]["environment"] = {
            "SYNTHETIC_PASSWORD": "probe-klartext",
            "SYNTHETIC_SECRET": "${SYNTHETIC_SECRET:-probe-default}",
            "SYNTHETIC_TOKEN": "${SYNTHETIC_TOKEN}",
            "SYNTHETIC_PASSWORD_FILE": "/run/secrets/probe",
        }
        befunde = pruefung.pruefe_compose(daten)
        self.assertEqual(2, sum(b["code"] == "KLARTEXT" for b in befunde))
        self.assertNotIn("probe-klartext", json.dumps(befunde))
        self.assertNotIn("probe-default", json.dumps(befunde))

    def test_vorlage_meldet_literal_aber_keine_datei_oder_variable(self):
        text = ("PASSWORD='synthetisch'\n"
                "SECRET=${SECRET:-/run/secrets/probe}\n"
                "SECRETS=${SECRETS:-/run/secrets}\n"
                "TOKEN=$(lesen token)\n"
                "# PASSWORD=kommentar\n")
        befunde = pruefung.pruefe_vorlage(text, "vorlage.sh")
        self.assertEqual(["KLARTEXT_VORLAGE"], [b["code"] for b in befunde])
        self.assertNotIn("synthetisch", json.dumps(befunde))

    def test_basisimage_und_stage(self):
        digest = "example.invalid/basis@sha256:" + "b" * 64
        self.assertEqual([], pruefung.pruefe_dockerfile(f"FROM {digest} AS build\nFROM build\n", "Dockerfile"))
        befunde = pruefung.pruefe_dockerfile("FROM example.invalid/basis:latest\n", "Dockerfile")
        self.assertEqual(["BASIS_DIGEST"], [b["code"] for b in befunde])
        self.assertEqual("FROM_FEHLT", pruefung.pruefe_dockerfile("# leer", "Dockerfile")[0]["code"])

    def test_separate_befunde_und_ausgangsmodell_unveraendert(self):
        daten = modell()
        vorher = copy.deepcopy(daten)
        daten["services"]["api"]["image"] = "api:latest"
        daten["services"]["db"]["image"] = "db:latest"
        befunde = pruefung.pruefe_compose(daten)
        self.assertEqual(["services.api", "services.db"], [b["ort"] for b in befunde])
        self.assertEqual("api:latest", daten["services"]["api"]["image"])
        self.assertEqual(vorher["networks"], daten["networks"])

    def test_fehler_beim_compose_aufruf_ist_nicht_erfolg(self):
        ausgabe = io.StringIO()
        with patch.object(pruefung.subprocess, "run", side_effect=FileNotFoundError("docker fehlt")), \
                patch.object(pruefung.sys, "argv", ["pruefung"]), contextlib.redirect_stderr(ausgabe):
            self.assertEqual(2, pruefung.main())
        self.assertIn("docker fehlt", ausgabe.getvalue())

    def test_json_ausgabe_und_exitcode_bei_befund(self):
        daten = modell()
        daten["services"]["db"]["image"] = "example.invalid/db:latest"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            dockerfile = root / "Dockerfile"
            dockerfile.write_text("FROM example.invalid/basis@sha256:" + "a" * 64 + "\n")
            vorlage = root / "deploy/infomaniak/keycloak/test-vorlage.sh"
            vorlage.parent.mkdir(parents=True)
            vorlage.write_text("PASSWORD=${PASSWORD}\n")
            ausgabe = io.StringIO()
            antwort = pruefung.subprocess.CompletedProcess([], 0, stdout=json.dumps(daten))
            with patch.object(pruefung, "REPO", root), patch.object(pruefung, "DOCKERFILES", ("Dockerfile",)), \
                    patch.object(pruefung.subprocess, "run", return_value=antwort), \
                    patch.object(pruefung.sys, "argv", ["pruefung", "--json"]), contextlib.redirect_stdout(ausgabe):
                self.assertEqual(1, pruefung.main())
            ergebnis = json.loads(ausgabe.getvalue())
            self.assertEqual(["befunde"], list(ergebnis))
            self.assertEqual({"code", "ort", "meldung"}, set(ergebnis["befunde"][0]))
            self.assertEqual("IMAGE_DIGEST", ergebnis["befunde"][0]["code"])


if __name__ == "__main__":
    unittest.main()
