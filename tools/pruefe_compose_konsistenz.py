#!/usr/bin/env python3
"""Statische Prüfung der Deploy-Vorlagen; startet keine Dienste."""

import argparse
import json
import re
import subprocess
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DIGEST = re.compile(r".+@sha256:[0-9a-f]{64}$")
GEHEIMNIS = re.compile(r"(?i)(password|passwort|secret|token|access.key|private.key)")
ZUWEISUNG = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
DOCKERFILES = (
    "backend/src/main/docker/Dockerfile.jvm",
    "deploy/infomaniak/db/Dockerfile",
    "deploy/infomaniak/keycloak/Dockerfile",
)
DIENST_SECRETS = {
    "api": {"archiv_db_app", "archiv_db_owner", "s3_app_zugang_id", "s3_app_zugang_geheimnis",
            "infomaniak_ai_token", "archiv_manifest_key", "archiv_data_key", "archiv_smtp_passwort",
            "namenssperre_salz", "keycloak_verwaltung_secret"},
    "db": {"archiv_db_postgres", "archiv_db_owner_scram", "archiv_db_app_scram", "keycloak_db_scram"},
    "kc": {"keycloak_db", "keycloak_admin", "keycloak_smtp_passwort", "google_client_id", "google_client_secret"},
}


def positiv(wert):
    try:
        zahl = Decimal(str(wert))
        return zahl.is_finite() and zahl > 0
    except InvalidOperation:
        return False


def limit(wert):
    treffer = re.fullmatch(r"(\d+(?:\.\d+)?)(?:[kmg](?:b)?)?", str(wert), re.IGNORECASE)
    return bool(treffer and positiv(treffer.group(1)))


def dauer(wert):
    text = str(wert)
    if not re.fullmatch(r"(?:\d+(?:\.\d+)?(?:ns|us|µs|ms|s|m|h))+", text):
        return False
    return any(positiv(zahl) for zahl in re.findall(r"(\d+(?:\.\d+)?)(?:ns|us|µs|ms|s|m|h)", text))


def wert_ist_referenz(wert):
    if wert is None:
        return True
    wert = str(wert).strip().strip("\"'")
    if not wert or wert == "/run/secrets" or wert.startswith("/run/secrets/"):
        return True
    if re.fullmatch(r"\$\{[A-Za-z_][A-Za-z0-9_]*:?\?.*}", wert):
        return True
    variable = re.fullmatch(r"\$\{[A-Za-z_][A-Za-z0-9_]*(?:(:-|-)(.*))?\}", wert)
    if variable:
        standard = variable.group(2)
        return standard is None or not standard or standard == "/run/secrets" or standard.startswith("/run/secrets/")
    return bool(re.fullmatch(r"\$[A-Za-z_][A-Za-z0-9_]*", wert) or wert.startswith("$("))


def pruefe_compose(compose):
    befunde = []

    def melde(code, ort, meldung):
        befunde.append({"code": code, "ort": ort, "meldung": meldung})

    dienste = compose.get("services", {})
    netze = compose.get("networks", {})
    for name in ("api", "db", "kc"):
        if name not in dienste:
            melde("DIENST_FEHLT", name, "Erwarteter Dienst fehlt.")
    if not netze.get("intern", {}).get("internal"):
        melde("NETZ_INTERN", "networks.intern", "Internes Netz fehlt oder ist nicht isoliert.")
    if not netze.get("web-archiv", {}).get("external"):
        melde("NETZ_WEB", "networks.web-archiv", "Externes Archivnetz fehlt.")

    for name, dienst in sorted(dienste.items()):
        ort = f"services.{name}"
        health = dienst.get("healthcheck") or {}
        test = health.get("test") or []
        if (health.get("disable") or not test or test in (["NONE"], "NONE")
                or not dauer(health.get("interval")) or not dauer(health.get("timeout"))
                or not positiv(health.get("retries"))):
            melde("HEALTHCHECK", ort, "Aktiver Healthcheck mit positiven Grenzen fehlt.")
        if not limit(dienst.get("mem_limit")) or not positiv(dienst.get("cpus")):
            melde("RESSOURCEN", ort, "Positives Speicher- und CPU-Limit erforderlich.")
        dienstnetze = set(dienst.get("networks") or [])
        if "intern" not in dienstnetze or not dienstnetze <= set(netze):
            melde("DIENST_NETZ", ort, "Internes oder deklariertes Netz fehlt.")
        erwartet = {"intern", "web-archiv"} if name in {"api", "kc", "sftp"} else {"intern"}
        if dienstnetze != erwartet:
            melde("WEB_ZUGRIFF", ort, "Dienst ist nicht ausschliesslich mit den vorgesehenen Netzen verbunden.")
        if dienst.get("ports"):
            melde("PORTS", ort, "Direkt veröffentlichte Ports sind nicht vorgesehen.")
        for secret in dienst.get("secrets") or []:
            quelle = secret.get("source") if isinstance(secret, dict) else secret
            ziel = secret.get("target", quelle) if isinstance(secret, dict) else quelle
            if quelle not in DIENST_SECRETS.get(name, set()) or ziel not in (quelle, f"/run/secrets/{quelle}"):
                melde("SECRET_ZUGRIFF", ort, "Secret ist für diesen Dienst oder dieses Ziel nicht vorgesehen.")
        for volume in dienst.get("volumes") or []:
            ziel = volume.get("target", "") if isinstance(volume, dict) else str(volume).split(":")[1:2]
            if isinstance(ziel, list):
                ziel = ziel[0] if ziel else ""
            if ziel == "/run/secrets" or ziel.startswith("/run/secrets/"):
                melde("SECRET_VOLUME", ort, "Secret-Dateien dürfen nicht über Volumes eingehängt werden.")
        if not DIGEST.fullmatch(str(dienst.get("image", ""))):
            melde("IMAGE_DIGEST", ort, "Dienstimage ist nicht mit SHA-256-Digest gepinnt.")
        umgebung = dienst.get("environment") or {}
        if isinstance(umgebung, list):
            umgebung = dict(e.split("=", 1) if "=" in e else (e, "") for e in umgebung)
        for schluessel, wert in umgebung.items():
            if GEHEIMNIS.search(schluessel) and not wert_ist_referenz(wert):
                melde("KLARTEXT", f"{ort}.environment.{schluessel}", "Möglicher Klartextwert.")

        dienstlabels = dienst.get("labels") or {}
        if isinstance(dienstlabels, list):
            dienstlabels = dict(e.split("=", 1) if "=" in e else (e, "") for e in dienstlabels)
        if name in {"api", "kc", "sftp"} and dienstlabels.get("traefik.docker.network") != "web-archiv":
            melde("TRAEFIK_NETZ", ort, "Traefik muss das Archivnetz verwenden.")
        definiert = {
            treffer.group(1)
            for label in dienstlabels
            if (treffer := re.fullmatch(r"traefik\.http\.middlewares\.([^.]+)\..+", label))
        }
        for label, wert in dienstlabels.items():
            router = re.fullmatch(r"traefik\.http\.routers\.([^.]+)\.middlewares", label)
            if not router:
                continue
            for mittel in str(wert or "").split(","):
                mittel = mittel.strip()
                if not mittel:
                    continue
                teile = mittel.split("@")
                if len(teile) > 2 or (len(teile) == 2 and teile[1] != "docker") or teile[0] not in definiert:
                    melde("MIDDLEWARE_FREMDER_DIENST", f"{ort}.routers.{router.group(1)}",
                          f"Middleware {mittel} ist nicht am Dienst des Routers definiert.")

    labels = dienste.get("api", {}).get("labels") or {}
    if isinstance(labels, list):
        labels = dict(e.split("=", 1) if "=" in e else (e, "") for e in labels)
    prefix = "traefik.http."
    for router, pfade, extra in (
        ("archiv-public", ("PathPrefix(`/public/`)",), set()),
        ("archiv-public-suche", ("Path(`/public/v1/suche`)", "Path(`/public/v1/suche/`)"),
         {"archiv-public-suche-drossel"}),
    ):
        regel = re.sub(r"\s+", "", str(labels.get(f"{prefix}routers.{router}.rule", "")))
        mittel = set(str(labels.get(f"{prefix}routers.{router}.middlewares", "")).replace(" ", "").split(","))
        if (any(pfad not in regel or "!" + pfad in regel for pfad in pfade)
                or not ({"archiv-public-drossel"} | extra) <= mittel):
            melde("PUBLIC_ROUTER", router, "Pfadregel oder erforderliche Drosselung fehlt.")
    for mittel in ("archiv-public-drossel", "archiv-public-suche-drossel"):
        for feld in ("average", "burst"):
            if not positiv(labels.get(f"{prefix}middlewares.{mittel}.ratelimit.{feld}")):
                melde("RATELIMIT", mittel, f"Positiver Wert für {feld} fehlt.")
        if not dauer(labels.get(f"{prefix}middlewares.{mittel}.ratelimit.period")):
            melde("RATELIMIT", mittel, "Positive Dauer für period fehlt.")
    return befunde


def pruefe_dockerfile(text, ort):
    befunde, stufen = [], set()
    gefunden = False
    for nummer, zeile in enumerate(text.splitlines(), 1):
        if not re.match(r"^\s*FROM\s", zeile, re.IGNORECASE):
            continue
        gefunden = True
        teile = zeile.split()
        while len(teile) > 1 and teile[1].startswith("--"):
            teile.pop(1)
        if len(teile) < 2:
            befunde.append({"code": "FROM_UNGUELTIG", "ort": f"{ort}:{nummer}", "meldung": "FROM ist ungültig."})
            continue
        image = teile[1]
        if not DIGEST.fullmatch(image) and image.lower() not in stufen:
            befunde.append({"code": "BASIS_DIGEST", "ort": f"{ort}:{nummer}",
                            "meldung": "Basisimage ist nicht mit SHA-256-Digest gepinnt."})
        if len(teile) >= 4 and teile[2].lower() == "as":
            stufen.add(teile[3].lower())
    if not gefunden:
        befunde.append({"code": "FROM_FEHLT", "ort": ort, "meldung": "Keine FROM-Anweisung gefunden."})
    return befunde


def pruefe_vorlage(text, ort):
    befunde = []
    for nummer, zeile in enumerate(text.splitlines(), 1):
        treffer = ZUWEISUNG.match(zeile)
        if treffer and GEHEIMNIS.search(treffer.group(1)) and not wert_ist_referenz(treffer.group(2)):
            befunde.append({"code": "KLARTEXT_VORLAGE", "ort": f"{ort}:{nummer}",
                            "meldung": "Möglicher Klartextwert in einer Zuweisung."})
    return befunde


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Befunde als JSON ausgeben")
    args = parser.parse_args()
    datei = REPO / "deploy/infomaniak/compose.yml"
    try:
        result = subprocess.run(
            ["docker", "compose", "-f", str(datei), "config", "--no-interpolate", "--no-env-resolution",
             "--no-path-resolution", "--format", "json"],
            check=True, capture_output=True, text=True,
        )
        compose = json.loads(result.stdout)
        befunde = pruefe_compose(compose)
        for dateiname in DOCKERFILES:
            befunde.extend(pruefe_dockerfile((REPO / dateiname).read_text(encoding="utf-8"), dateiname))
        vorlagen = sorted((REPO / "deploy/infomaniak/keycloak").glob("*.sh"))
        if not any(v.name.endswith("-vorlage.sh") for v in vorlagen):
            raise FileNotFoundError("Keycloak-Vorlagen fehlen")
        for vorlage in vorlagen:
            befunde.extend(pruefe_vorlage(vorlage.read_text(encoding="utf-8"), str(vorlage.relative_to(REPO))))
    except subprocess.CalledProcessError as fehler:
        print(f"Docker Compose konnte die Datei nicht prüfen (Exit {fehler.returncode}).", file=sys.stderr)
        return 2
    except OSError as fehler:
        print(f"Datei oder Programm nicht verfügbar: {fehler.filename or fehler}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as fehler:
        print(f"Compose-Ausgabe ist kein gültiges JSON (Zeile {fehler.lineno}).", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps({"befunde": befunde}, ensure_ascii=False, indent=2))
    elif befunde:
        for befund in befunde:
            print(f"{befund['code']}: {befund['ort']}: {befund['meldung']}")
    else:
        print("Keine Befunde.")
    return 1 if befunde else 0


if __name__ == "__main__":
    sys.exit(main())
