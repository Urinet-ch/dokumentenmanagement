#!/usr/bin/env python3
"""Entwurf: kontrollierter Aufruf des bestehenden Benutzerabgleichs."""

import argparse
import json
import re
import subprocess
import sys


def abgleichen(starter, mandant, pruefen):
    argumente = [starter, "benutzer", "abgleichen", mandant]
    if pruefen:
        argumente.append("--pruefen")
    argumente.append("--json")
    try:
        ergebnis = subprocess.run(argumente, capture_output=True, text=True, check=False)
    except (OSError, UnicodeError) as fehler:
        raise RuntimeError(f"Starter konnte nicht ausgeführt werden: {type(fehler).__name__}") from fehler
    if ergebnis.returncode != 0:
        raise RuntimeError(
            f"Abgleich mit Exit-Code {ergebnis.returncode} abgebrochen; "
            "Befunde oder Dienstfehler separat geschützt prüfen."
        )
    try:
        bericht = json.loads(ergebnis.stdout)
    except (ValueError, UnicodeError) as fehler:
        raise RuntimeError("Abgleich lieferte keinen gültigen JSON-Bericht.") from fehler
    if (not isinstance(bericht, dict) or bericht.get("mandant") != mandant
            or bericht.get("abweichungen") != []
            or type(bericht.get("uebernahme_geplant")) is not int
            or type(bericht.get("uebernommen")) is not int
            or bericht["uebernahme_geplant"] < 0 or bericht["uebernommen"] < 0):
        raise RuntimeError("Abgleich lieferte einen widersprüchlichen oder unvollständigen Bericht.")
    if pruefen and bericht["uebernommen"] != 0:
        raise RuntimeError("Prüflauf meldet unerwartete Übernahmen.")
    return bericht


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("starter", help="Ausführbarer Starter für das Archiv-Kommando")
    parser.add_argument("mandant", help="Mandantencode")
    parser.add_argument("--uebernehmen", action="store_true", help="Nach Prüfung explizit übernehmen")
    args = parser.parse_args()
    if (len(args.mandant) > 40 or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", args.mandant)
            or args.mandant.startswith(("a-", "p-"))):
        parser.error("Ungültiger Mandantencode.")
    try:
        vorher = abgleichen(args.starter, args.mandant, True)
        print(f"Prüfung: {vorher['uebernahme_geplant']} Übernahmen geplant; keine Abweichungen.")
        if not args.uebernehmen:
            return 0
        if not sys.stdin.isatty():
            raise RuntimeError("Übernahme benötigt eine interaktive Bestätigung.")
        if input("Für diesen Mandanten UEBERNEHMEN eingeben: ") != "UEBERNEHMEN":
            raise RuntimeError("Nicht bestätigt; keine Übernahme gestartet.")
        geschrieben = abgleichen(args.starter, args.mandant, False)
        if geschrieben["uebernommen"] != vorher["uebernahme_geplant"]:
            raise RuntimeError("Übernahmezahl weicht vom Prüflauf ab.")
        nachher = abgleichen(args.starter, args.mandant, True)
        if nachher["uebernahme_geplant"] != 0:
            raise RuntimeError("Nachprüfung meldet noch ausstehende Übernahmen.")
        print(f"Übernahme: {geschrieben['uebernommen']}; Nachprüfung ohne Abweichung oder offene Übernahmen.")
        return 0
    except (RuntimeError, EOFError) as fehler:
        print(f"Abbruch: {fehler}", file=sys.stderr)
        if args.uebernehmen:
            print("Falls die Übernahme gestartet wurde, können bereits Zeilen bestehen; Zustand prüfen.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
