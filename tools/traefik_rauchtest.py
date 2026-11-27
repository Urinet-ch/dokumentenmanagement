#!/usr/bin/env python3
"""Lokaler Laufzeittest der Traefik-Router aus der Archiv-Compose-Datei."""

import argparse
import copy
import http.client
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
COMPOSE_DEFAULT = REPO / "deploy/infomaniak/compose.yml"
TRAEFIK_IMAGE = "traefik:v3.5"
STUB_IMAGE = "python:3.13-alpine"
STUB_CODE = """from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os

name = os.environ["RAUCHTEST_SERVICE"]

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = f"synthetic service: {name}".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Rauchtest-Dienst", name)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass

ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
"""

ROUTER_LABEL = re.compile(r"traefik\.http\.routers\.([^.]+)\.(.+)")
MIDDLEWARE_LABEL = re.compile(r"traefik\.http\.middlewares\.([^.]+)\.(.+)")
SERVICE_PORT_LABEL = re.compile(
    r"traefik\.http\.services\.([^.]+)\.loadbalancer\.server\.port"
)


class RauchtestFehler(RuntimeError):
    pass


def _labels(service):
    labels = service.get("labels") or {}
    if isinstance(labels, dict):
        return {str(key): str(value) for key, value in labels.items()}
    if isinstance(labels, list):
        return {
            str(label).split("=", 1)[0]: (
                str(label).split("=", 1)[1] if "=" in str(label) else ""
            )
            for label in labels
        }
    raise RauchtestFehler("Compose-Labels haben ein unerwartetes Format.")


def _compose_modell(compose):
    router_labels = {}
    router_besitzer = {}
    middleware_labels = {}
    middleware_besitzer = {}
    service_besitzer = {}

    for compose_service, service in (compose.get("services") or {}).items():
        for key, value in _labels(service).items():
            if treffer := ROUTER_LABEL.fullmatch(key):
                name, option = treffer.groups()
                if name in router_besitzer and router_besitzer[name] != compose_service:
                    raise RauchtestFehler(
                        f"Router {name} ist mehreren Compose-Diensten zugeordnet."
                    )
                router_besitzer[name] = compose_service
                router_labels.setdefault(name, {})[option] = value
            elif treffer := MIDDLEWARE_LABEL.fullmatch(key):
                name, option = treffer.groups()
                if name in middleware_besitzer and middleware_besitzer[name] != compose_service:
                    raise RauchtestFehler(
                        f"Middleware {name} ist mehreren Compose-Diensten zugeordnet."
                    )
                middleware_besitzer[name] = compose_service
                definition = middleware_labels.setdefault(name, {})
                if option in definition and definition[option] != value:
                    raise RauchtestFehler(
                        f"Middleware {name} ist mehrfach unterschiedlich definiert."
                    )
                definition[option] = value
            elif treffer := SERVICE_PORT_LABEL.fullmatch(key):
                name = treffer.group(1)
                if name in service_besitzer and service_besitzer[name] != compose_service:
                    raise RauchtestFehler(
                        f"Traefik-Service {name} ist mehreren Compose-Diensten zugeordnet."
                    )
                service_besitzer[name] = compose_service

    if not router_labels:
        raise RauchtestFehler("Keine Traefik-Router in der Compose-Datei gefunden.")
    if not service_besitzer:
        raise RauchtestFehler("Keine Traefik-Services in der Compose-Datei gefunden.")
    return (
        router_labels,
        middleware_labels,
        service_besitzer,
        router_besitzer,
        middleware_besitzer,
    )


def _option_name(middleware_type, option):
    names = {
        "headers": {
            "stsseconds": "stsSeconds",
            "stsincludesubdomains": "stsIncludeSubdomains",
            "stspreload": "stsPreload",
            "forcestsheader": "forceSTSHeader",
        },
        "ratelimit": {
            "average": "average",
            "period": "period",
            "burst": "burst",
        },
        "inflightreq": {"amount": "amount"},
    }
    try:
        section = {
            "headers": "headers",
            "ratelimit": "rateLimit",
            "inflightreq": "inFlightReq",
        }[middleware_type]
        return section, names[middleware_type][option]
    except KeyError as error:
        raise RauchtestFehler(
            f"Nicht unterstützte Traefik-Middleware-Option: {middleware_type}.{option}"
        ) from error


def _typed_value(middleware_type, option, value):
    if middleware_type == "headers" and option in {
        "stsseconds",
        "stsincludesubdomains",
        "stspreload",
        "forcestsheader",
    }:
        if option == "stsseconds":
            return int(value)
        if value.lower() not in {"true", "false"}:
            raise RauchtestFehler(f"Ungültiger Boolean-Wert für headers.{option}.")
        return value.lower() == "true"
    if middleware_type == "ratelimit" and option in {"average", "burst"}:
        return int(value)
    if middleware_type == "inflightreq" and option == "amount":
        return int(value)
    return value


def derive_file_configuration(compose, stub_urls):
    """Leitet eine lokale File-Provider-Konfiguration aus Compose-Labels ab."""
    (
        router_labels,
        middleware_labels,
        service_besitzer,
        router_besitzer,
        middleware_besitzer,
    ) = _compose_modell(compose)
    routers = {}
    referenced_middlewares = set()

    for name, labels in sorted(router_labels.items()):
        rule = labels.get("rule")
        service = labels.get("service")
        if not rule or not service:
            raise RauchtestFehler(f"Router {name} braucht rule und service.")
        if service not in service_besitzer or service not in stub_urls:
            raise RauchtestFehler(f"Für Traefik-Service {service} fehlt eine Attrappe.")
        if service_besitzer[service] != router_besitzer[name]:
            raise RauchtestFehler(
                f"Router {name} und Traefik-Service {service} gehören nicht "
                "zum selben Compose-Dienst."
            )
        middlewares = [
            item.strip().split("@", 1)[0]
            for item in labels.get("middlewares", "").split(",")
            if item.strip()
        ]
        if any(middleware_besitzer.get(item) != router_besitzer[name] for item in middlewares):
            raise RauchtestFehler(
                f"Router {name} verwendet eine Middleware eines fremden Compose-Dienstes."
            )
        referenced_middlewares.update(middlewares)
        routers[name] = {
            "rule": rule,
            "entryPoints": ["web"],
            "service": service,
            "middlewares": middlewares,
        }

    middlewares = {}
    for name in sorted(referenced_middlewares):
        definition = middleware_labels.get(name)
        if not definition:
            raise RauchtestFehler(f"Definition für Middleware {name} fehlt.")
        config = {}
        for path, value in sorted(definition.items()):
            teile = path.split(".", 1)
            if len(teile) != 2:
                raise RauchtestFehler(f"Ungültige Middleware-Option: {path}.")
            middleware_type, option = teile
            section, key = _option_name(middleware_type, option)
            config.setdefault(section, {})[key] = _typed_value(
                middleware_type, option, value
            )
        if "headers" in config:
            # HTTP-only smoke test: production enables HSTS on HTTPS routers.
            config["headers"]["forceSTSHeader"] = True
        if "rateLimit" in config:
            # A tiny burst makes each local router produce a deterministic 429.
            config["rateLimit"].update(average=1, period="1s", burst=1)
        middlewares[name] = config

    services = {
        name: {"loadBalancer": {"servers": [{"url": stub_urls[name]}]}}
        for name in sorted({router["service"] for router in routers.values()})
    }
    return {"http": {"routers": routers, "middlewares": middlewares, "services": services}}


def reversed_router_middlewares(configuration, router_name):
    mutated = copy.deepcopy(configuration)
    chain = mutated["http"]["routers"][router_name]["middlewares"]
    header_index = next(
        (
            index
            for index, name in enumerate(chain)
            if "headers" in mutated["http"]["middlewares"][name]
        ),
        None,
    )
    limit_index = next(
        (
            index
            for index, name in enumerate(chain)
            if "rateLimit" in mutated["http"]["middlewares"][name]
        ),
        None,
    )
    if header_index is None or limit_index is None or header_index >= limit_index:
        raise RauchtestFehler(
            f"Router {router_name} braucht HSTS vor einer Drosselungs-Middleware."
        )
    chain[header_index], chain[limit_index] = chain[limit_index], chain[header_index]
    return mutated


def _assert_hsts(headers, expected_seconds):
    value = next(
        (
            value
            for key, value in headers.items()
            if key.lower() == "strict-transport-security"
        ),
        None,
    )
    if value is None:
        raise RauchtestFehler("Strict-Transport-Security fehlt in der Antwort.")
    if not re.search(rf"(?:^|;)\s*max-age={expected_seconds}(?:;|$)", value, re.I):
        raise RauchtestFehler(
            f"Strict-Transport-Security hat nicht max-age={expected_seconds}."
        )


def _assert_success(status, headers, expected_service, expected_seconds):
    if status != 200:
        raise RauchtestFehler(f"Erwartet HTTP 200, erhalten: {status}.")
    _assert_hsts(headers, expected_seconds)
    actual_service = next(
        (
            value
            for key, value in headers.items()
            if key.lower() == "x-rauchtest-dienst"
        ),
        None,
    )
    if actual_service != expected_service:
        raise RauchtestFehler(
            f"Router erreichte Attrappe {actual_service!r} statt {expected_service!r}."
        )


def _assert_throttled(status, headers, expected_seconds):
    if status != 429:
        raise RauchtestFehler(f"Erwartet gedrosseltes HTTP 429, erhalten: {status}.")
    _assert_hsts(headers, expected_seconds)


def _request(port, host, path):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", headers={"Host": host}
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=4) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()
    except (
        urllib.error.URLError,
        http.client.HTTPException,
        TimeoutError,
        OSError,
    ) as error:
        raise RauchtestFehler(f"Lokale Anfrage fehlgeschlagen: {error}.") from error


def _request_target(rule):
    host = re.search(r"\bHost\(`([^`]+)`\)", rule)
    if host is None:
        raise RauchtestFehler(f"Kein unterstützter Host-Ausdruck in Regel {rule!r}.")
    for match in re.finditer(r"(!)?Path(Prefix)?\(`([^`]+)`\)", rule):
        if match.group(1):
            continue
        path, is_prefix = match.group(3), match.group(2)
        if is_prefix:
            path = path.rstrip("/") + "/rauchtest"
        return host.group(1), path
    return host.group(1), "/"


def _docker(*arguments):
    try:
        result = subprocess.run(
            ["docker", *arguments], check=True, capture_output=True, text=True
        )
    except FileNotFoundError as error:
        raise RauchtestFehler("Docker CLI ist nicht verfügbar.") from error
    except subprocess.CalledProcessError as error:
        command = arguments[0] if arguments else "Befehl"
        raise RauchtestFehler(
            f"docker {command} schlug mit Exit-Code {error.returncode} fehl."
        ) from error
    return result.stdout.strip()


def _load_compose(path):
    try:
        result = subprocess.run(
            [
                "docker",
                "compose",
                "-f",
                str(path),
                "config",
                "--no-interpolate",
                "--no-env-resolution",
                "--no-path-resolution",
                "--format",
                "json",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as error:
        raise RauchtestFehler("Docker Compose CLI ist nicht verfügbar.") from error
    except subprocess.CalledProcessError as error:
        raise RauchtestFehler(
            f"Docker Compose konnte die Konfiguration nicht lesen "
            f"(Exit-Code {error.returncode})."
        ) from error
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise RauchtestFehler(
            f"Docker Compose lieferte ungültiges JSON (Zeile {error.lineno})."
        ) from error


def _start_stub(name, alias, compose_service, network, containers):
    _docker(
        "create",
        "--name",
        name,
        "--network",
        network,
        "--network-alias",
        alias,
        "--env",
        f"RAUCHTEST_SERVICE={compose_service}",
        STUB_IMAGE,
        "python",
        "-u",
        "-c",
        STUB_CODE,
    )
    containers.append(name)
    _docker("start", name)


def _start_traefik(name, network, configuration, workdir, containers):
    config_path = Path(workdir) / f"{name}.json"
    config_path.write_text(json.dumps(configuration), encoding="utf-8")
    _docker(
        "create",
        "--name",
        name,
        "--network",
        network,
        "--publish",
        "127.0.0.1::8080/tcp",
        TRAEFIK_IMAGE,
        "--entrypoints.web.address=:8080",
        "--providers.file.filename=/tmp/dynamic.json",
        "--providers.file.watch=true",
        "--log.level=ERROR",
    )
    containers.append(name)
    _docker("cp", str(config_path), f"{name}:/tmp/dynamic.json")
    _docker("start", name)
    published = _docker("port", name, "8080/tcp").rsplit(":", 1)[-1]
    return int(published)


def _wait_ready(port, router, compose_service):
    host, path = _request_target(router["rule"])
    deadline = time.monotonic() + 20
    last_error = "Traefik hat noch nicht geantwortet."
    while time.monotonic() < deadline:
        try:
            status, headers, _ = _request(port, host, path)
            if status == 200 and any(
                key.lower() == "x-rauchtest-dienst" and value == compose_service
                for key, value in headers.items()
            ):
                return
            last_error = f"unerwarteter HTTP-Status {status}"
        except RauchtestFehler as error:
            last_error = str(error)
        time.sleep(0.25)
    raise RauchtestFehler(f"Traefik wurde nicht bereit: {last_error}.")


def _cleanup(containers, network):
    errors = []
    for name in reversed(containers):
        result = subprocess.run(
            ["docker", "rm", "--force", name], capture_output=True, text=True
        )
        if result.returncode:
            errors.append(f"Container {name} (Exit {result.returncode})")
    result = subprocess.run(
        ["docker", "network", "rm", network], capture_output=True, text=True
    )
    if result.returncode:
        errors.append(f"Netz {network} (Exit {result.returncode})")
    if errors:
        raise RauchtestFehler("Eigene Testressourcen nicht entfernt: " + ", ".join(errors))


def _router_hsts_seconds(configuration, router):
    for name in router["middlewares"]:
        headers = configuration["http"]["middlewares"][name].get("headers")
        if headers:
            return headers["stsSeconds"]
    raise RauchtestFehler("Router hat keine HSTS-Middleware.")


def _negative_control_router(configuration):
    for name, router in configuration["http"]["routers"].items():
        chain = router["middlewares"]
        header_index = next(
            (
                index
                for index, middleware in enumerate(chain)
                if "headers" in configuration["http"]["middlewares"][middleware]
            ),
            None,
        )
        limit_index = next(
            (
                index
                for index, middleware in enumerate(chain)
                if "rateLimit" in configuration["http"]["middlewares"][middleware]
            ),
            None,
        )
        if header_index == 0 and limit_index == 1:
            return name
    raise RauchtestFehler("Kein Router für die vertauschte Reihenfolge gefunden.")


def run_smoke_test(compose_path):
    compose = _load_compose(compose_path)
    _, _, service_owners, _, _ = _compose_modell(compose)
    suffix = uuid.uuid4().hex[:12]
    network = f"urinet-traefik-rauchtest-{suffix}"
    containers = []
    _docker(
        "network",
        "create",
        "--opt",
        "com.docker.network.bridge.enable_ip_masquerade=false",
        network,
    )

    try:
        compose_services = sorted(set(service_owners.values()))
        aliases = {
            service: f"rauchtest-{service}" for service in compose_services
        }
        for service in compose_services:
            _start_stub(
                f"urinet-traefik-stub-{service}-{suffix}",
                aliases[service],
                service,
                network,
                containers,
            )
        stub_urls = {
            service_name: f"http://{aliases[owner]}:8080"
            for service_name, owner in service_owners.items()
        }
        configuration = derive_file_configuration(compose, stub_urls)

        with tempfile.TemporaryDirectory(prefix="urinet-traefik-rauchtest-") as workdir:
            traefik_name = f"urinet-traefik-{suffix}"
            port = _start_traefik(
                traefik_name, network, configuration, workdir, containers
            )
            routers = configuration["http"]["routers"]
            first_name = next(iter(routers))
            _wait_ready(port, routers[first_name], service_owners[routers[first_name]["service"]])

            for name, router in routers.items():
                host, path = _request_target(router["rule"])
                expected_service = service_owners[router["service"]]
                expected_seconds = _router_hsts_seconds(configuration, router)
                time.sleep(1.1)
                status, headers, _ = _request(port, host, path)
                _assert_success(status, headers, expected_service, expected_seconds)
                status, headers, _ = _request(port, host, path)
                _assert_throttled(status, headers, expected_seconds)
                print(f"OK {name}: 200 und 429 mit HSTS; Attrappe {expected_service}.")

            control_router = _negative_control_router(configuration)
            control_configuration = reversed_router_middlewares(
                configuration, control_router
            )
            control_name = f"urinet-traefik-gegenprobe-{suffix}"
            control_port = _start_traefik(
                control_name, network, control_configuration, workdir, containers
            )
            router = control_configuration["http"]["routers"][control_router]
            host, path = _request_target(router["rule"])
            expected_service = service_owners[router["service"]]
            expected_seconds = _router_hsts_seconds(control_configuration, router)
            _wait_ready(control_port, router, expected_service)
            time.sleep(1.1)
            status, headers, _ = _request(control_port, host, path)
            _assert_success(status, headers, expected_service, expected_seconds)
            status, headers, _ = _request(control_port, host, path)
            if status != 429:
                raise RauchtestFehler(
                    f"Gegenprobe: erwartet HTTP 429, erhalten: {status}."
                )
            try:
                _assert_throttled(status, headers, expected_seconds)
            except RauchtestFehler as error:
                if "Strict-Transport-Security fehlt" not in str(error):
                    raise
            else:
                raise RauchtestFehler(
                    "Gegenprobe: vertauschte Reihenfolge liess HSTS unerwartet durch."
                )
            print(
                f"OK Gegenprobe {control_router}: vertauschte Reihenfolge "
                "verliert HSTS und wird erkannt."
            )
    finally:
        _cleanup(containers, network)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--compose",
        type=Path,
        default=COMPOSE_DEFAULT,
        help="Compose-Datei (Standard: deploy/infomaniak/compose.yml)",
    )
    args = parser.parse_args()
    try:
        run_smoke_test(args.compose)
    except RauchtestFehler as error:
        print(f"Rauchtest fehlgeschlagen: {error}", file=sys.stderr)
        return 1
    print("Traefik-Rauchtest erfolgreich; eigene Container und Netz entfernt.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
