import copy
import unittest

from tools import traefik_rauchtest as rauchtest


def compose_vorlage():
    return {
        "services": {
            "api": {
                "labels": {
                    "traefik.http.routers.archiv-api.rule": (
                        "Host(`archiv.apps.urinet.ch`) && PathPrefix(`/api/`)"
                    ),
                    "traefik.http.routers.archiv-api.service": "archiv",
                    "traefik.http.routers.archiv-api.middlewares": (
                        "archiv-sts,archiv-drossel"
                    ),
                    "traefik.http.routers.archiv-api.tls.certresolver": "le",
                    "traefik.http.services.archiv.loadbalancer.server.port": "8080",
                    "traefik.http.middlewares.archiv-sts.headers.stsseconds": "86400",
                    "traefik.http.middlewares.archiv-sts.headers.stsincludesubdomains": (
                        "false"
                    ),
                    "traefik.http.middlewares.archiv-sts.headers.stspreload": "false",
                    "traefik.http.middlewares.archiv-sts.headers.forcestsheader": (
                        "false"
                    ),
                    "traefik.http.middlewares.archiv-drossel.ratelimit.average": "10",
                    "traefik.http.middlewares.archiv-drossel.ratelimit.period": "1s",
                    "traefik.http.middlewares.archiv-drossel.ratelimit.burst": "50",
                }
            }
        }
    }


class TraefikRauchtestTest(unittest.TestCase):
    def test_leitet_router_labels_ab_und_ersetzt_service(self):
        compose = compose_vorlage()
        unveraendert = copy.deepcopy(compose)
        config = rauchtest.derive_file_configuration(
            compose, {"archiv": "http://rauchtest-api:8080"}
        )

        router = config["http"]["routers"]["archiv-api"]
        self.assertEqual(
            "Host(`archiv.apps.urinet.ch`) && PathPrefix(`/api/`)", router["rule"]
        )
        self.assertEqual(["web"], router["entryPoints"])
        self.assertEqual(["archiv-sts", "archiv-drossel"], router["middlewares"])
        self.assertNotIn("tls", router)
        self.assertEqual(
            "http://rauchtest-api:8080",
            config["http"]["services"]["archiv"]["loadBalancer"]["servers"][0]["url"],
        )
        hsts = config["http"]["middlewares"]["archiv-sts"]["headers"]
        self.assertEqual(86400, hsts["stsSeconds"])
        self.assertTrue(hsts["forceSTSHeader"])
        self.assertEqual(
            {"average": 1, "period": "1s", "burst": 1},
            config["http"]["middlewares"]["archiv-drossel"]["rateLimit"],
        )
        self.assertEqual(unveraendert, compose)

    def test_ableitung_kommt_ohne_docker_aus(self):
        config = rauchtest.derive_file_configuration(
            compose_vorlage(), {"archiv": "http://rauchtest-api:8080"}
        )
        self.assertIn("archiv-api", config["http"]["routers"])

    def test_middleware_von_anderem_compose_dienst_wird_abgewiesen(self):
        compose = compose_vorlage()
        api_labels = compose["services"]["api"]["labels"]
        middleware_labels = {
            key: value
            for key, value in api_labels.items()
            if key.startswith("traefik.http.middlewares.")
        }
        compose["services"]["api"]["labels"] = {
            key: value for key, value in api_labels.items() if key not in middleware_labels
        }
        compose["services"]["kc"] = {"labels": middleware_labels}

        with self.assertRaisesRegex(
            rauchtest.RauchtestFehler, "fremden Compose-Dienst"
        ):
            rauchtest.derive_file_configuration(
                compose, {"archiv": "http://rauchtest-api:8080"}
            )

    def test_vertauschte_reihenfolge_fuehrt_bei_429_zum_fehlenden_hsts(self):
        config = rauchtest.derive_file_configuration(
            compose_vorlage(), {"archiv": "http://rauchtest-api:8080"}
        )
        reversed_config = rauchtest.reversed_router_middlewares(config, "archiv-api")
        self.assertEqual(
            ["archiv-drossel", "archiv-sts"],
            reversed_config["http"]["routers"]["archiv-api"]["middlewares"],
        )
        with self.assertRaisesRegex(
            rauchtest.RauchtestFehler, "Strict-Transport-Security fehlt"
        ):
            rauchtest._assert_throttled(429, {}, 86400)

    def test_429_mit_hsts_wird_akzeptiert(self):
        rauchtest._assert_throttled(
            429, {"Strict-Transport-Security": "max-age=86400"}, 86400
        )


if __name__ == "__main__":
    unittest.main()
