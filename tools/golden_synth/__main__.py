"""Aufruf: python -m tools.golden_synth --seed 20261108 --out "$ARCHIV_GOLDEN_DIR/synthetisch"."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from .generator import STANDARD_SEED, bericht, erzeuge


def main(argv: list[str] | None = None) -> None:
    standard = Path(os.environ.get("ARCHIV_GOLDEN_DIR", "~/urinet-archiv-golden")).expanduser() / "synthetisch"
    parser = argparse.ArgumentParser(prog="python -m tools.golden_synth",
                                     description="Erzeugt 50 synthetische Golden-Belege und erwartet.json.")
    parser.add_argument("--seed", type=int, default=STANDARD_SEED)
    parser.add_argument("--out", type=Path, default=standard)
    args = parser.parse_args(argv)
    liste = erzeuge(args.seed, args.out)
    print(bericht(liste, args.out.expanduser().resolve()))


if __name__ == "__main__":
    main()
