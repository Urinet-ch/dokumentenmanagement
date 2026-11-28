#!/usr/bin/env python3
"""Erzeugt einen SQL-Export für genau 50 gemessene Fotos, ohne DB-Verbindung."""

import argparse
import json
from pathlib import Path
from uuid import UUID

from bestand import pruefung, schreiben


def sql(ids):
    if not isinstance(ids, list) or len(ids) != 50 or not all(isinstance(wert, str) for wert in ids):
        raise ValueError("Genau 50 verschiedene Foto-IDs erforderlich.")
    ids = [str(UUID(wert)) for wert in ids]
    if len(set(ids)) != 50:
        raise ValueError("Genau 50 verschiedene Foto-IDs erforderlich.")
    liste = ",".join(f"'{wert}'::uuid" for wert in ids)
    return "\\set ON_ERROR_STOP on\nSET TIME ZONE 'UTC';\n" + pruefung() + f"""
COPY (
 SELECT d.id,
 CASE WHEN bool_and(a.status='FERTIG' AND a.gestartet IS NOT NULL
                    AND a.beendet IS NOT NULL AND a.beendet>=a.gestartet)
      AND count(DISTINCT a.typ)=2 AND d.status='EINGANGSKORB'
      THEN 'FERTIG' ELSE 'FEHLER' END AS status,
 sum(a.versuche) AS versuche, count(a.id) AS auftraege,
 min(a.gestartet) AS gestartet, max(a.beendet) AS beendet, 'true' AS ki_stub
 FROM m_last.dokument d LEFT JOIN public.auftrag a
 ON a.mandant='last' AND a.nutzlast->>'dokumentId'=d.id::text
 AND a.typ IN ('VERARBEITEN','KI_VORSCHLAG')
 WHERE d.id IN ({liste})
 GROUP BY d.id, d.status ORDER BY d.id
) TO STDOUT WITH CSV HEADER;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ids", type=Path)
    parser.add_argument("ausgabe", type=Path)
    parser.add_argument("--ki-stub-bestaetigt", action="store_true",
                        help="Messkonfiguration tatsächlich auf KI-Stub und echte OCR geprüft")
    args = parser.parse_args()
    if not args.ki_stub_bestaetigt:
        parser.error("Ohne bestätigten KI-Stub kein Export ohne KI-Wartezeit.")
    try:
        schreiben(args.ausgabe, sql(json.loads(args.ids.read_text())))
    except (OSError, ValueError, TypeError):
        parser.exit(2, "Auftrags-Export nicht erzeugt; IDs und neuen geschützten Ausgabepfad prüfen.\n")


if __name__ == "__main__":
    main()
