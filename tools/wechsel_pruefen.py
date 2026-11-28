#!/usr/bin/env python3
"""Vergleicht Archiv- und Keycloak-Bestand lesend mit einem freigegebenen Sollbestand."""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

SERVICE = re.compile(r"[A-Za-z0-9_.-]+\Z")
MANDANT = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*\Z")
SCOPES = {"generator", "import", "webseite-lesen", "webseite-erfassung"}
FELDER = {"realms", "benutzer", "rollen", "mandanten", "clients"}

# Auch deaktivierte Benutzer, Mandanten und widerrufene Clients zählen. Ohne Filter auf Mandant oder Realm,
# damit fehlende und falsch zugeordnete Objekte nicht übersehen werden.
ARCHIV_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
 'mandanten', coalesce((SELECT json_agg(code ORDER BY code) FROM public.mandant), '[]'::json),
 'clients', coalesce((SELECT json_agg(json_build_object('id', id, 'mandant', mandant, 'scope', scope)
                                     ORDER BY id) FROM public.api_client), '[]'::json),
 'sicher', (SELECT bool_and(c.relkind IN ('r', 'p') AND NOT row_security_active(c.oid))
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname IN ('mandant', 'api_client')
            HAVING count(*) = 2)
);
COMMIT;
"""

KEYCLOAK_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
 'realms', coalesce((SELECT json_agg(id ORDER BY id) FROM public.realm), '[]'::json),
 'benutzer', coalesce((SELECT json_agg(json_build_object(
     'id', u.id, 'realm', u.realm_id,
     'mandanten', coalesce((SELECT json_agg(a.value ORDER BY a.value) FROM public.user_attribute a
                            WHERE a.user_id = u.id AND a.name = 'mandant'), '[]'::json))
     ORDER BY u.id) FROM public.user_entity u), '[]'::json),
 'rollen', coalesce((SELECT json_agg(id ORDER BY id) FROM public.keycloak_role), '[]'::json),
 'federation', (SELECT count(*) FROM public.component
                WHERE provider_type = 'org.keycloak.storage.UserStorageProvider')
                + (SELECT count(*) FROM public.user_federation_provider),
 'sicher', (SELECT bool_and(c.relkind IN ('r', 'p') AND NOT row_security_active(c.oid))
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname IN
              ('realm', 'user_entity', 'user_attribute', 'keycloak_role', 'component', 'user_federation_provider')
            HAVING count(*) = 6)
);
COMMIT;
"""


def lesen(service: str, sql: str) -> dict:
    if not SERVICE.fullmatch(service):
        raise ValueError("Ungültiger PostgreSQL-Service.")
    if "PGPASSWORD" in os.environ:
        raise ValueError("PGPASSWORD ist nicht zulässig; Passwortdatei verwenden.")
    try:
        lauf = subprocess.run(
            ["psql", "-X", "-w", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-d", "service=" + service],
            input=sql, text=True, capture_output=True, timeout=60, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as fehler:
        raise ValueError("Datenbankabfrage nicht ausführbar.") from fehler
    if lauf.returncode != 0:
        raise ValueError("Datenbankabfrage fehlgeschlagen; Zugriff und Schema prüfen.")
    try:
        antwort = json.loads(lauf.stdout)
    except json.JSONDecodeError as fehler:
        raise ValueError("Datenbankantwort unvollständig oder ungültig.") from fehler
    if not isinstance(antwort, dict):
        raise ValueError("Datenbankantwort ist kein Objekt.")
    return antwort


def liste(daten: object, feld: str, eintrag_felder: set[str] | None = None) -> list:
    if not isinstance(daten, list):
        raise ValueError(f"{feld}: Liste erwartet.")
    kanonisch = []
    ids = set()
    for wert in daten:
        if eintrag_felder is None:
            if not isinstance(wert, str) or not wert.strip():
                raise ValueError(f"{feld}: nicht leere Kennung erwartet.")
        else:
            if not isinstance(wert, dict) or set(wert) != eintrag_felder:
                raise ValueError(f"{feld}: ungültiger Eintrag.")
            for name, inhalt in wert.items():
                if name == "mandanten":
                    liste(inhalt, feld + ".mandanten")
                elif not isinstance(inhalt, str) or not inhalt.strip():
                    raise ValueError(f"{feld}: nicht leere Kennung erwartet.")
            if wert["id"] in ids:
                raise ValueError(f"{feld}: Kennung mehrfach vorhanden.")
            ids.add(wert["id"])
            if "mandanten" in wert:
                wert = {**wert, "mandanten": sorted(wert["mandanten"])}
        kanonisch.append(json.dumps(wert, sort_keys=True, ensure_ascii=True))
    if len(set(kanonisch)) != len(kanonisch):
        raise ValueError(f"{feld}: doppelter Eintrag.")
    return sorted(kanonisch)


def pruefen(soll: object, archiv: dict, keycloak: dict) -> tuple[bool, list[str]]:
    if not isinstance(soll, dict) or set(soll) != FELDER:
        raise ValueError("Sollbestand muss genau realms, benutzer, rollen, mandanten und clients enthalten.")
    if set(archiv) != {"mandanten", "clients", "sicher"} or set(keycloak) != {
        "realms", "benutzer", "rollen", "federation", "sicher"
    } or archiv["sicher"] is not True or keycloak["sicher"] is not True:
        raise ValueError("Datenquelle unvollständig oder durch Zeilensicherheit eingeschränkt.")
    if type(keycloak["federation"]) is not int or keycloak["federation"] != 0:
        raise ValueError("Externe Benutzerföderation vorhanden oder nicht eindeutig; vollständigen Bestand klären.")
    schema = {"benutzer": {"id", "realm", "mandanten"}, "clients": {"id", "mandant", "scope"}}
    ist = {**{k: archiv[k] for k in ("mandanten", "clients")},
           **{k: keycloak[k] for k in ("realms", "benutzer", "rollen")}}
    meldungen = []
    sauber = True
    for feld in sorted(FELDER):
        erwartet = liste(soll[feld], feld, schema.get(feld))
        gefunden = liste(ist[feld], feld, schema.get(feld))
        fehlend = set(erwartet) - set(gefunden)
        unerwartet = set(gefunden) - set(erwartet)
        sauber &= not fehlend and not unerwartet
        meldungen.append(f"{feld}: {len(gefunden)} vorhanden, {len(unerwartet)} unerwartet, {len(fehlend)} fehlen")
    if not soll["realms"] or not soll["mandanten"]:
        raise ValueError("Sollbestand muss mindestens einen Realm und einen Mandanten enthalten.")
    if any(not MANDANT.fullmatch(m) for m in soll["mandanten"]):
        raise ValueError("Ungültiger Mandanten-Code im Sollbestand.")
    for client in soll["clients"] + ist["clients"]:
        if client["mandant"] not in soll["mandanten"] or client["scope"] not in SCOPES:
            raise ValueError("Client mit unbekanntem Mandanten oder Scope.")
    for benutzer in soll["benutzer"] + ist["benutzer"]:
        if len(benutzer["mandanten"]) > 1 or benutzer["realm"] not in soll["realms"] or any(
            mandant not in soll["mandanten"] for mandant in benutzer["mandanten"]
        ):
            raise ValueError("Benutzer mit unbekanntem Realm oder unbekanntem oder mehrdeutigem Mandanten.")
    for mandant in sorted(soll["mandanten"]):
        for feld, titel, filter_fn in (
            ("benutzer", "Benutzer", lambda b: mandant in b["mandanten"]),
            ("clients", "API-Tokens", lambda c: c["mandant"] == mandant and c["scope"] in {"generator", "import"}),
            ("clients", "Webseiten-Schlüssel", lambda c: c["mandant"] == mandant and c["scope"] in {
                "webseite-lesen", "webseite-erfassung"}),
        ):
            erwartet = set(liste(list(filter(filter_fn, soll[feld])), feld, schema[feld]))
            gefunden = set(liste(list(filter(filter_fn, ist[feld])), feld, schema[feld]))
            meldungen.append(f"Mandant {mandant}, {titel}: {len(gefunden)} vorhanden, "
                             f"{len(gefunden - erwartet)} unerwartet, {len(erwartet - gefunden)} fehlen")
    meldungen.append(f"Benutzer ohne Mandant: {sum(not b['mandanten'] for b in ist['benutzer'])}")
    return sauber, meldungen


def objekt(paarungen: list[tuple[str, object]]) -> dict:
    ergebnis = {}
    for schluessel, wert in paarungen:
        if schluessel in ergebnis:
            raise ValueError("Sollbestand enthält doppelte Felder.")
        ergebnis[schluessel] = wert
    return ergebnis


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archiv-service", required=True, help="Name des PostgreSQL-Services für das Archiv")
    parser.add_argument("--keycloak-service", required=True, help="Name des PostgreSQL-Services für Keycloak")
    parser.add_argument("--soll", type=Path, required=True, help="Freigegebener Sollbestand als JSON")
    args = parser.parse_args()
    try:
        with args.soll.open(encoding="utf-8") as datei:
            soll = json.load(datei, object_pairs_hook=objekt)
        archiv = lesen(args.archiv_service, ARCHIV_SQL)
        keycloak = lesen(args.keycloak_service, KEYCLOAK_SQL)
        sauber, meldungen = pruefen(soll, archiv, keycloak)
    except (OSError, UnicodeError):
        print("Prüfung fehlgeschlagen: Sollbestand nicht lesbar.", file=sys.stderr)
        return 2
    except ValueError as fehler:
        print(f"Prüfung fehlgeschlagen: {fehler}", file=sys.stderr)
        return 2
    for meldung in meldungen:
        print(meldung)
    if not sauber:
        print("Prüfung fehlgeschlagen: Bestand mit dem freigegebenen Soll abgleichen.", file=sys.stderr)
        return 1
    print("Bestand entspricht dem Soll; Betriebsfreigabe und vollständiges Inventar bleiben manuell zu prüfen.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
