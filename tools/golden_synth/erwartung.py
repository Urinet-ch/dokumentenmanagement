"""Aufteilung in Abstimmungs- und Prüfteil und Ausgabe von erwartet.json."""

from __future__ import annotations

import json
import random
from collections import Counter

from .formate import json_betrag
from .modell import KATEGORIEN, Beleg

ZURUECKGEHALTEN_JE_KATEGORIE = 4
NEUSTARTS = 30


def _note(belege: list[Beleg], auswahl: set[int], gesamt: Counter) -> float:
    geprueft = Counter(r for b in belege if b.nr in auswahl for r in set(b.randfaelle))
    return sum((geprueft[r] - 0.4 * n) ** 2 + (5 if n >= 2 and geprueft[r] in (0, n) else 0)
               for r, n in gesamt.items())


def teile_zu(belege: list[Beleg], seed: int) -> None:
    """Je Kategorie 6 'abstimmen' und 4 'zurueckgehalten'. Lokale Suche (Tausch innerhalb der Kategorie) ab
    zufälligen Startaufteilungen, Ziel: je Randfall 40 % im Prüfteil, mehrfache Randfälle in beiden Teilen.
    Deterministisch aus dem Seed."""
    rng = random.Random(f"golden-{seed}-teil")
    je_kategorie = {k: [b.nr for b in belege if b.kategorie == k] for k in KATEGORIEN}
    gesamt = Counter(r for b in belege for r in set(b.randfaelle))
    beste, beste_note = set(), None
    for _ in range(NEUSTARTS):
        auswahl = {nr for k in KATEGORIEN for nr in rng.sample(je_kategorie[k], ZURUECKGEHALTEN_JE_KATEGORIE)}
        note = _note(belege, auswahl, gesamt)
        besser = True
        while besser:
            besser = False
            for k in KATEGORIEN:
                for raus in sorted(auswahl & set(je_kategorie[k])):
                    for rein in sorted(set(je_kategorie[k]) - auswahl):
                        kandidat = (auswahl - {raus}) | {rein}
                        n = _note(belege, kandidat, gesamt)
                        if n < note:
                            auswahl, note, besser = kandidat, n, True
                            break
                    if besser:
                        break
                if besser:
                    break
        if beste_note is None or note < beste_note:
            beste, beste_note = auswahl, note
    for b in belege:
        b.teil = "zurueckgehalten" if b.nr in beste else "abstimmen"


def _qr(b: Beleg) -> dict | None:
    if not b.qr:
        return None
    q = b.qr
    return {"konto": q.konto, "betrag": json_betrag(q.betrag), "waehrung": q.waehrung, "referenztyp": q.referenztyp,
            "referenz": q.referenz, "zahlungsempfaenger": q.empfaenger.name}


def eintrag(b: Beleg, seed: int) -> dict:
    return {
        "datei": b.datei,
        "art": b.art,
        "kategorie": b.kategorie,
        "datum": b.datum.isoformat() if b.datum else None,
        "partner": b.partner,
        "betrag": json_betrag(b.betrag),
        "waehrung": b.waehrung,
        "belegnummer": b.belegnummer,
        "richtung": b.richtung,
        "faelligkeit": b.faelligkeit.isoformat() if b.faelligkeit else None,
        "foto": b.foto is not None,
        "qr": _qr(b),
        "teil": b.teil,
        "randfaelle": sorted(set(b.randfaelle)),
        "seed": seed,
    }


def mandant(seed: int) -> dict:
    """Kennungen des Mandanten (design.md «Mandant», Req 7.5): Absender der eigenen Belege."""
    from .katalog import Kontext

    f = Kontext(seed).firma("urinet")
    return {"name": f.name, "uid": f.uid, "ibans": [f.iban]}


def als_json(belege: list[Beleg], seed: int) -> str:
    inhalt = {"version": 1, "seed": seed, "mandant": mandant(seed),
              "belege": [eintrag(b, seed) for b in sorted(belege, key=lambda b: b.nr)]}
    return json.dumps(inhalt, ensure_ascii=False, indent=2) + "\n"
