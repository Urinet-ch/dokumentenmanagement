#!/usr/bin/env python3
"""Erstellt aus ausschliesslich synthetischen Angaben einen lokalen Migrationsplan."""

import argparse
import json
import re
import sys
from pathlib import Path

ROLLEN = {"inhaber", "mitarbeiter", "treuhaender", "geschaeftsstelle"}
FELDER = {"kennung", "mandanten", "rollen", "vorhanden", "totp", "passkey"}


def plan_erstellen(daten: object) -> dict:
    if not isinstance(daten, list) or not daten:
        raise ValueError("Eine nicht leere Liste synthetischer Benutzer wird erwartet.")
    eintraege = []
    zaehler = {}
    for benutzer in daten:
        if not isinstance(benutzer, dict) or set(benutzer) != FELDER:
            raise ValueError("Benutzer muss genau die dokumentierten Felder enthalten.")
        name = benutzer["kennung"]
        if not isinstance(name, str) or not re.fullmatch(r"synthetisch-[a-z0-9._-]{1,60}", name):
            raise ValueError("Nur Kennungen mit Präfix synthetisch- sind zulässig.")
        for feld in ("mandanten", "rollen"):
            wert = benutzer[feld]
            if not isinstance(wert, list) or any(not isinstance(x, str) or not x for x in wert):
                raise ValueError(f"{feld} muss eine Liste nicht leerer Texte sein.")
        for feld in ("vorhanden", "totp", "passkey"):
            if benutzer[feld] is not None and type(benutzer[feld]) is not bool:
                raise ValueError(f"{feld} muss true, false oder null sein.")
        zaehler[name] = zaehler.get(name, 0) + 1
        konflikte = []
        mandanten = benutzer["mandanten"]
        rollen = benutzer["rollen"]
        if len(mandanten) != 1:
            konflikte.append("Mandant fehlt oder ist mehrdeutig")
        if len(rollen) != 1:
            konflikte.append("Archiv-Rolle fehlt oder ist mehrdeutig")
        if any(rolle not in ROLLEN for rolle in rollen):
            konflikte.append("Unbekannte Archiv-Rolle")
        if benutzer["vorhanden"] is None:
            konflikte.append("Vorhandensein im Ziel ungeklärt")
        aktion = {True: "bestehenden Zugang abgleichen", False: "Anlage prüfen", None: "Zielzustand klären"}[
            benutzer["vorhanden"]
        ]
        eintraege.append({
            "kennung": name,
            "mandanten": mandanten,
            "rollen": rollen,
            "aktion": aktion,
            "zugang": {
                "totp": "vorhanden prüfen" if benutzer["totp"] else "Einrichtung oder Zustand prüfen",
                "passkey": "vorhanden prüfen" if benutzer["passkey"] else "optional: Einrichtung oder Zustand prüfen",
            },
            "konflikte": konflikte,
        })
    for eintrag in eintraege:
        if zaehler[eintrag["kennung"]] > 1:
            eintrag["konflikte"].append("Kennung mehrfach vorhanden")
        if eintrag["konflikte"]:
            eintrag["aktion"] = "Konflikte klären; keine Übernahme planen"
    return {"modus": "synthetischer Trockenlauf", "benutzer": eintraege}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("eingabe", type=Path, help="JSON-Datei mit synthetischen Benutzern")
    args = parser.parse_args()
    try:
        with args.eingabe.open(encoding="utf-8") as datei:
            plan = plan_erstellen(json.load(datei))
    except (OSError, UnicodeError, ValueError) as fehler:
        print(f"Plan nicht erstellt: {fehler}", file=sys.stderr)
        return 2
    print(json.dumps(plan, ensure_ascii=False, indent=2))
    return 1 if any(e["konflikte"] for e in plan["benutzer"]) else 0


if __name__ == "__main__":
    sys.exit(main())
