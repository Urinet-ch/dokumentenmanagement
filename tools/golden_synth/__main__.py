"""Aufruf: python -m tools.golden_synth --seed 20261108 --out "$ARCHIV_GOLDEN_DIR/synthetisch"."""

from __future__ import annotations

import argparse
from pathlib import Path

from .generator import STANDARD_SEED, bericht, erzeuge, golden_wurzel


def main(argv: list[str] | None = None) -> None:
    standard = golden_wurzel() / "synthetisch"
    parser = argparse.ArgumentParser(prog="python -m tools.golden_synth",
                                     description="Erzeugt 50 synthetische Golden-Belege und erwartet.json.")
    parser.add_argument("--seed", type=int, default=STANDARD_SEED)
    parser.add_argument("--out", type=Path, default=standard)
    args = parser.parse_args(argv)
    liste = erzeuge(args.seed, args.out)
    print(bericht(liste, args.out.expanduser().resolve()))


if __name__ == "__main__":
    main()
