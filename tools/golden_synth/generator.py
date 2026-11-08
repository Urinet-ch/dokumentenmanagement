"""Erzeugt die Belege und erwartet.json in ein Verzeichnis ausserhalb des Repositorys."""

from __future__ import annotations

import importlib.util
import os
from collections import Counter
from pathlib import Path

import numpy as np

from . import erwartung, foto, katalog
from .modell import KATEGORIEN, Beleg

REPO = Path(__file__).resolve().parents[2]
STANDARD_SEED = 20261108
STANDARD_GOLDEN_DIR = "~/urinet-archiv-golden"
MARKER = ".golden-synth"
ENDUNGEN = ("pdf", "jpg", "heic")


def golden_wurzel() -> Path:
    return Path(os.environ.get("ARCHIV_GOLDEN_DIR", STANDARD_GOLDEN_DIR)).expanduser().resolve()


def heic_verfuegbar() -> bool:
    return importlib.util.find_spec("pillow_heif") is not None


def belege(seed: int) -> list[Beleg]:
    liste = katalog.alle(seed, heic=heic_verfuegbar())
    erwartung.teile_zu(liste, seed)
    return liste


def pruefe_ziel(ziel: Path, golden: Path) -> None:
    """Bricht ab, bevor etwas geschrieben oder gelöscht wird, wenn das Ziel nicht sicher dem Generator gehört."""
    if ziel.is_relative_to(REPO):
        raise SystemExit(f"Ausgabe im Repository ist nicht erlaubt: {ziel}")
    if golden.is_relative_to(ziel):
        raise SystemExit(f"Ausgabe in die Golden-Wurzel ({golden}) oder einen übergeordneten Ordner ist nicht "
                         f"erlaubt: {ziel}. Unterordner verwenden, z. B. {golden / 'synthetisch'}.")
    if ziel.exists() and any(ziel.iterdir()) and not (ziel / MARKER).is_file():
        raise SystemExit(f"{ziel} ist nicht leer und enthält keinen Marker {MARKER}; der Ordner stammt nicht "
                         "vom Generator. Leeren Ordner wählen.")


def erzeuge(seed: int, ziel: Path, golden: Path | None = None) -> list[Beleg]:
    ziel = ziel.expanduser().resolve()
    pruefe_ziel(ziel, (golden or golden_wurzel()).expanduser().resolve())
    liste = belege(seed)
    (ziel / "belege").mkdir(parents=True, exist_ok=True)
    (ziel / MARKER).write_text("Erzeugt von tools/golden_synth. Inhalt wird bei jedem Lauf ersetzt.\n", encoding="utf-8")
    for b in liste:
        stamm = Path(b.datei).with_suffix("")
        for endung in ENDUNGEN:
            (ziel / stamm.with_suffix(f".{endung}")).unlink(missing_ok=True)
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
