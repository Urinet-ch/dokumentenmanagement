"""Formatierung von Beträgen und Datumswerten, wie sie auf Belegen stehen."""

from __future__ import annotations

from datetime import date
from decimal import ROUND_HALF_UP, Decimal

RAPPEN = Decimal("0.01")
FUENF_RAPPEN = Decimal("0.05")

MONATE = {
    "de": ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September",
           "Oktober", "November", "Dezember"],
    "fr": ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre",
           "octobre", "novembre", "décembre"],
    "it": ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
           "settembre", "ottobre", "novembre", "dicembre"],
}


def d(wert: str | int | Decimal) -> Decimal:
    return Decimal(str(wert))


def rp(wert: Decimal) -> Decimal:
    return wert.quantize(RAPPEN, rounding=ROUND_HALF_UP)


def rund5(wert: Decimal) -> Decimal:
    return ((wert / FUENF_RAPPEN).quantize(Decimal(1), rounding=ROUND_HALF_UP) * FUENF_RAPPEN).quantize(RAPPEN)


def betrag(wert: Decimal, tausender: str = "'", strich: bool = False) -> str:
    """1234.5 → 1'234.50; tausender '' für ohne Trennung; strich: 260.– statt 260.00."""
    wert = rp(wert)
    vorzeichen = "-" if wert < 0 else ""
    ganz, rappen = f"{abs(wert):.2f}".split(".")
    gruppen = []
    while len(ganz) > 3:
        gruppen.insert(0, ganz[-3:])
        ganz = ganz[:-3]
    gruppen.insert(0, ganz)
    rappen_text = "–" if strich and rappen == "00" else rappen
    return f"{vorzeichen}{tausender.join(gruppen)}.{rappen_text}"


def json_betrag(wert: Decimal | None) -> str | None:
    return None if wert is None else f"{rp(wert):.2f}"


def datum(wert: date, stil: str) -> str:
    """Stile: ddmmyyyy (08.11.2026), dmyy (8.11.26), lang (8. November 2026), iso, fr, it."""
    if stil == "ddmmyyyy":
        return wert.strftime("%d.%m.%Y")
    if stil == "dmyy":
        return f"{wert.day}.{wert.month}.{wert.year % 100:02d}"
    if stil == "lang":
        return f"{wert.day}. {MONATE['de'][wert.month - 1]} {wert.year}"
    if stil == "iso":
        return wert.isoformat()
    if stil == "fr":
        tag = "1er" if wert.day == 1 else str(wert.day)
        return f"{tag} {MONATE['fr'][wert.month - 1]} {wert.year}"
    if stil == "it":
        return f"{wert.day} {MONATE['it'][wert.month - 1]} {wert.year}"
    raise ValueError(stil)
