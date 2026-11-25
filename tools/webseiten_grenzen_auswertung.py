"""Wertet synthetisch oder extern erhobene Stichproben der Webseiten-Grenzen aus."""

from __future__ import annotations

import argparse
import csv
import math
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import TextIO

FELDER = {
    "alias",
    "grenze",
    "modus",
    "fenster_sekunden",
    "anfragen",
    "abgelehnt_429",
    "startwert",
    "spitze",
}
GRENZEN = {"lesen", "suche", "erfassung", "uploads", "datei"}
MODI = {"dauer", "spitze"}
ALIAS = re.compile(r"K[0-9]{2}\Z")


@dataclass(frozen=True)
class Beobachtung:
    alias: str
    grenze: str
    modus: str
    fenster_sekunden: int
    anfragen: int
    abgelehnt_429: int
    startwert: Decimal
    spitze: Decimal


@dataclass(frozen=True)
class Auswertung:
    alias: str
    grenze: str
    modus: str
    stichproben: int
    anfragen: int
    nicht_429: int
    abgelehnt_429: int
    quote_429: Decimal
    bedarf: Decimal
    grenzwert: Decimal
    auslastung: Decimal
    einheit: str


def _ganzzahl(zeile: int, feld: str, wert: str | None) -> int:
    try:
        zahl = int(wert if wert is not None else "")
    except ValueError as fehler:
        raise ValueError(f"CSV-Zeile {zeile}: {feld} muss eine ganze Zahl sein") from fehler
    if zahl < 0:
        raise ValueError(f"CSV-Zeile {zeile}: {feld} darf nicht negativ sein")
    return zahl


def _dezimal(zeile: int, feld: str, wert: str | None) -> Decimal:
    try:
        zahl = Decimal(wert if wert is not None else "")
    except InvalidOperation as fehler:
        raise ValueError(f"CSV-Zeile {zeile}: {feld} muss eine Zahl sein") from fehler
    if not zahl.is_finite() or zahl <= 0:
        raise ValueError(f"CSV-Zeile {zeile}: {feld} muss grösser als 0 sein")
    return zahl


def lese_messungen(datei: TextIO) -> list[Beobachtung]:
    reader = csv.DictReader(datei)
    if reader.fieldnames is None:
        raise ValueError("CSV-Kopfzeile fehlt")
    if len(reader.fieldnames) != len(set(reader.fieldnames)):
        raise ValueError("CSV-Kopfzeile enthält doppelte Spalten")
    fehlend = FELDER - set(reader.fieldnames)
    unbekannt = set(reader.fieldnames) - FELDER
    if fehlend or unbekannt:
        raise ValueError(
            f"CSV-Spalten ungültig; fehlend: {sorted(fehlend)}, unbekannt: {sorted(unbekannt)}"
        )

    beobachtungen = []
    for zeile, daten in enumerate(reader, start=2):
        if None in daten:
            raise ValueError(f"CSV-Zeile {zeile}: zu viele Werte")
        alias = daten["alias"] or ""
        grenze = daten["grenze"] or ""
        modus = daten["modus"] or ""
        if not ALIAS.fullmatch(alias):
            raise ValueError(f"CSV-Zeile {zeile}: alias muss ein erfundener Wert wie K01 sein")
        if grenze not in GRENZEN:
            raise ValueError(f"CSV-Zeile {zeile}: unbekannte Grenze")
        if modus not in MODI:
            raise ValueError(f"CSV-Zeile {zeile}: modus muss dauer oder spitze sein")

        fenster = _ganzzahl(zeile, "fenster_sekunden", daten["fenster_sekunden"])
        if fenster == 0:
            raise ValueError(f"CSV-Zeile {zeile}: fenster_sekunden muss grösser als 0 sein")
        anfragen = _ganzzahl(zeile, "anfragen", daten["anfragen"])
        abgelehnt = _ganzzahl(zeile, "abgelehnt_429", daten["abgelehnt_429"])
        if abgelehnt > anfragen:
            raise ValueError(f"CSV-Zeile {zeile}: 429 kann anfragen nicht übersteigen")

        beobachtungen.append(
            Beobachtung(
                alias,
                grenze,
                modus,
                fenster,
                anfragen,
                abgelehnt,
                _dezimal(zeile, "startwert", daten["startwert"]),
                _dezimal(zeile, "spitze", daten["spitze"]),
            )
        )
    if not beobachtungen:
        raise ValueError("CSV enthält keine Messzeilen")
    return beobachtungen


def _p95(werte: list[Decimal]) -> Decimal:
    sortiert = sorted(werte)
    return sortiert[math.ceil(0.95 * len(sortiert)) - 1]


def auswerten(beobachtungen: list[Beobachtung]) -> list[Auswertung]:
    gruppen: dict[tuple[str, str, str], list[Beobachtung]] = defaultdict(list)
    for beobachtung in beobachtungen:
        gruppen[(beobachtung.alias, beobachtung.grenze, beobachtung.modus)].append(beobachtung)

    ergebnisse = []
    for (alias, grenze, modus), gruppe in sorted(gruppen.items()):
        konfigurationen = {(eintrag.startwert, eintrag.spitze) for eintrag in gruppe}
        if len(konfigurationen) != 1:
            raise ValueError(f"Uneinheitliche Startwerte für {alias}/{grenze}/{modus}")
        startwert, spitze = next(iter(konfigurationen))
        anfragen = sum(eintrag.anfragen for eintrag in gruppe)
        abgelehnt = sum(eintrag.abgelehnt_429 for eintrag in gruppe)
        nicht_429 = anfragen - abgelehnt
        quote = Decimal(100 * abgelehnt) / Decimal(anfragen) if anfragen else Decimal(0)

        if modus == "dauer":
            faktor = Decimal(3600) if grenze == "uploads" else Decimal(1)
            bedarf = _p95(
                [
                    Decimal(eintrag.anfragen) * faktor / Decimal(eintrag.fenster_sekunden)
                    for eintrag in gruppe
                ]
            )
            grenzwert = startwert
            einheit = "pro Stunde" if grenze == "uploads" else "pro Sekunde"
        else:
            bedarf = Decimal(max(eintrag.anfragen for eintrag in gruppe))
            grenzwert = spitze
            einheit = "pro Stoss"

        ergebnisse.append(
            Auswertung(
                alias,
                grenze,
                modus,
                len(gruppe),
                anfragen,
                nicht_429,
                abgelehnt,
                quote,
                bedarf,
                grenzwert,
                bedarf * Decimal(100) / grenzwert,
                einheit,
            )
        )
    return ergebnisse


def schreiben(ergebnisse: list[Auswertung], ausgabe: TextIO) -> None:
    tsv = csv.writer(ausgabe, delimiter="\t", lineterminator="\n")
    tsv.writerow(
        (
            "alias",
            "grenze",
            "modus",
            "stichproben",
            "anfragen",
            "nicht_429",
            "abgelehnt_429",
            "quote_429_pct",
            "bedarf_p95_oder_max",
            "grenzwert",
            "auslastung_pct",
            "einheit",
        )
    )
    for eintrag in ergebnisse:
        tsv.writerow(
            (
                eintrag.alias,
                eintrag.grenze,
                eintrag.modus,
                eintrag.stichproben,
                eintrag.anfragen,
                eintrag.nicht_429,
                eintrag.abgelehnt_429,
                f"{eintrag.quote_429:.2f}",
                f"{eintrag.bedarf:.2f}",
                f"{eintrag.grenzwert:.2f}",
                f"{eintrag.auslastung:.2f}",
                eintrag.einheit,
            )
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_datei", type=Path, help="CSV-Messdaten mit erfundenen Schlüssel-Aliasen")
    argumente = parser.parse_args(argv)
    try:
        with argumente.csv_datei.open(encoding="utf-8-sig", newline="") as datei:
            ergebnisse = auswerten(lese_messungen(datei))
    except (OSError, UnicodeError, csv.Error, ValueError) as fehler:
        parser.error(str(fehler))
    schreiben(ergebnisse, sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
