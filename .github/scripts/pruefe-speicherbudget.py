#!/usr/bin/env python3
"""Speicherbudget des Zielhosts vor dem Deploy prüfen (Req 16.18, tech.md «Plattform und Betrieb»).

Liest `docker compose config --format json` auf stdin. Jeder Dienst braucht mem_limit und cpus; liegt die Summe
über dem Budget, bricht der Deploy ab, bevor ein Bundle gebaut wird. Aufruf:
  docker compose -f deploy/infomaniak/compose.yml config --format json | pruefe-speicherbudget.py <MiB> <cpus>
"""
import json
import sys
from decimal import Decimal


def main() -> int:
    budget_mib, budget_cpus = Decimal(sys.argv[1]), Decimal(sys.argv[2])
    dienste = json.load(sys.stdin)["services"]
    summe_mib, summe_cpus, fehler = Decimal(0), Decimal(0), []
    for name, dienst in sorted(dienste.items()):
        if "mem_limit" not in dienst or "cpus" not in dienst:
            fehler.append(f"Dienst {name} ohne mem_limit oder cpus")
            continue
        mib = Decimal(int(dienst["mem_limit"])) / (1024 * 1024)
        cpus = Decimal(str(dienst["cpus"]))
        summe_mib += mib
        summe_cpus += cpus
        print(f"{name}: {mib:.0f} MiB, {cpus} CPU")
    print(f"Summe: {summe_mib:.0f} MiB (Budget {budget_mib}), {summe_cpus} CPU (Budget {budget_cpus})")
    if summe_mib > budget_mib or summe_cpus > budget_cpus:
        fehler.append("Summe über dem Speicherbudget des Zielhosts: Req 16.18 tritt ein. Deploy abbrechen und Budget "
                      "anpassen.")
    for f in fehler:
        print(f"::error::{f}")
    return 1 if fehler else 0


if __name__ == "__main__":
    sys.exit(main())
