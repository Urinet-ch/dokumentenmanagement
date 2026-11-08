"""Erzeugt die Belege und erwartet.json in ein Verzeichnis ausserhalb des Repositorys."""

from __future__ import annotations

import importlib.util
from collections import Counter
from pathlib import Path

import numpy as np

from . import erwartung, foto, katalog
from .modell import KATEGORIEN, Beleg

REPO = Path(__file__).resolve().parents[2]
STANDARD_SEED = 20261108


def heic_verfuegbar() -> bool:
    return importlib.util.find_spec("pillow_heif") is not None


def belege(seed: int) -> list[Beleg]:
    liste = katalog.alle(seed, heic=heic_verfuegbar())
    erwartung.teile_zu(liste, seed)
    return liste


def erzeuge(seed: int, ziel: Path) -> list[Beleg]:
    ziel = ziel.expanduser().resolve()
    if ziel.is_relative_to(REPO):
        raise SystemExit(f"Ausgabe im Repository ist nicht erlaubt: {ziel}")
    liste = belege(seed)
    ordner = ziel / "belege"
    ordner.mkdir(parents=True, exist_ok=True)
    for alt in ordner.glob("[0-9][0-9]_*"):
        alt.unlink()
    for b in liste:
        pdf = b.erzeuge_pdf()
        daten = foto.fotografiere(pdf, b.foto, np.random.default_rng([seed, b.nr])) if b.foto else pdf
        (ziel / b.datei).write_bytes(daten)
    (ziel / "erwartet.json").write_text(erwartung.als_json(liste, seed), encoding="utf-8")
    return liste


def bericht(liste: list[Beleg], ziel: Path) -> str:
    zeilen = [f"{len(liste)} Belege in {ziel}", ""]
    for k in KATEGORIEN:
        teil = Counter(b.teil for b in liste if b.kategorie == k)
        zeilen.append(f"{k:<26} {sum(teil.values()):>2}  abstimmen {teil['abstimmen']}  zurueckgehalten {teil['zurueckgehalten']}")
    zeilen += ["", f"{'Randfall':<34} abstimmen  zurueckgehalten"]
    gesamt = Counter((r, b.teil) for b in liste for r in set(b.randfaelle))
    for r in sorted({r for r, _ in gesamt}):
        zeilen.append(f"{r:<34} {gesamt[(r, 'abstimmen')]:>9}  {gesamt[(r, 'zurueckgehalten')]:>15}")
    groessen = {b.datei: (ziel / b.datei).stat().st_size for b in liste}
    endungen = Counter(Path(n).suffix for n in groessen)
    zeilen += ["", "Formate: " + ", ".join(f"{e} {n}" for e, n in sorted(endungen.items())),
               f"Grösse: min {min(groessen.values()) // 1024} KB, max {max(groessen.values()) // 1024} KB, "
               f"total {sum(groessen.values()) // 1024 // 1024} MB",
               f"HEIC: {'ja' if heic_verfuegbar() else 'nein (pillow-heif fehlt, JPEG)'}"]
    return "\n".join(zeilen)
