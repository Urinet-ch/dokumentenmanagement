#!/usr/bin/env python3
"""Erzeugt SQL für einen isolierten synthetischen Suchbestand; führt kein SQL aus."""

import argparse
import os
import re
from pathlib import Path
from suche import WOERTER

ANZAHL = 50_000
WOERTER_TEXT = WOERTER[:-5]
ARTEN = ["VERTRAG", "KORRESPONDENZ", "AUSGANGSRECHNUNG", "OFFERTE", "QUITTUNG", "LIEFERANTENBELEG"]


def text(nummer):
    laenge = 20025 if nummer % 20 == 0 else 525
    wort = f"Synthetisch {WOERTER_TEXT[(nummer-1) % len(WOERTER_TEXT)]} " \
           f"{WOERTER_TEXT[(nummer+6) % len(WOERTER_TEXT)]} {nummer:05d} "
    return (wort * (laenge // len(wort) + 1))[:laenge]


def pruefung():
    return """DO $$
BEGIN
  IF (SELECT count(*) FROM public.mandant) <> 1 OR
     NOT EXISTS (SELECT 1 FROM public.mandant WHERE code='last' AND bucket='urinet-archiv-a-last')
  THEN RAISE EXCEPTION 'Nur isolierter Messmandant last zulässig'; END IF;
END $$;
"""


def sql(mitarbeiter_sub="last-mitarbeiter"):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", mitarbeiter_sub):
        raise ValueError("Ungültiger Subject-Claim des synthetischen Mitarbeiters.")
    woerter = "ARRAY[" + ",".join("'" + wort + "'" for wort in WOERTER_TEXT) + "]"
    arten = "ARRAY[" + ",".join("'" + art + "'" for art in ARTEN) + "]"
    return "\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL TIME ZONE 'UTC';\n" + pruefung() + f"""
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM m_last.dokument) OR EXISTS (SELECT 1 FROM public.auftrag)
  THEN RAISE EXCEPTION 'Messbestand und Auftragsschlange müssen leer sein'; END IF;
END $$;
INSERT INTO m_last.dokument
 (id,art,titel,quelle,vertraulichkeit,person_benutzer,erstellt_von,status,
  bestaetigt,bestaetigt_von,datum,geschaeftsjahr,volltext,dateiname)
SELECT md5('archiv-last-' || n)::uuid, ({arten})[1+(n-1)%6],
 'Synthetische Messprobe ' || n, 'UPLOAD',
 (ARRAY['NORMAL','INTERN','PERSONAL'])[1+n%3],
 CASE WHEN n%3=2 THEN '{mitarbeiter_sub}' END, 'last-inhaber', 'ARCHIVIERT',
 '2026-01-01'::timestamptz, 'last-inhaber',
 '2023-01-01'::date + (n%1461), 2023+n%4,
 left(repeat('Synthetisch ' || ({woerter})[1+(n-1)%{len(WOERTER_TEXT)}] || ' ' ||
      ({woerter})[1+(n+6)%{len(WOERTER_TEXT)}] || ' ' ||
      lpad(n::text,5,'0') || ' ',1000), CASE WHEN n%20=0 THEN 20025 ELSE 525 END),
 (ARRAY['Scan','Kassenzettel','Fotobeleg','Ablage','Einzahlung'])[1+n%5] || '-' || n || '.pdf'
FROM generate_series(1,50000) n;
INSERT INTO m_last.dokument_finanz (dokument_id,richtung,betrag,waehrung,zahlstatus)
SELECT md5('archiv-last-' || n)::uuid, CASE WHEN n%6=3 THEN 'EINNAHME' ELSE 'AUSGABE' END,
 (n%100000)::numeric/100, 'CHF', 'UNBEKANNT'
FROM generate_series(1,50000) n WHERE n%3=0;
COMMIT;
VACUUM ANALYZE m_last.dokument;
VACUUM ANALYZE m_last.dokument_finanz;
""" + kontroll_sql()


def kontroll_sql():
    return pruefung() + """
DO $$ BEGIN
  IF (SELECT count(*) FROM m_last.dokument) <> 50000 OR
     (SELECT count(*) FROM m_last.dokument WHERE length(volltext)>20000) <> 2500 OR
     (SELECT avg(length(volltext)) FROM m_last.dokument) <> 1500 OR
     (SELECT count(*) FROM m_last.dokument_finanz) <> 16666 OR
     (SELECT count(DISTINCT art) FROM m_last.dokument) <> 6 OR
     (SELECT count(DISTINCT vertraulichkeit) FROM m_last.dokument) <> 3 OR
     EXISTS (SELECT 1 FROM m_last.dokument WHERE dateiname IS NULL OR bestaetigt IS NULL)
  THEN RAISE EXCEPTION 'Messbestand entspricht nicht der Verteilung'; END IF;
END $$;
SELECT count(*) AS anzahl, avg(length(volltext)) AS mittel_zeichen,
 count(*) FILTER (WHERE length(volltext)>20000) AS lange_texte FROM m_last.dokument;
"""


def schreiben(pfad, inhalt):
    pfad = pfad.resolve()
    if pfad.is_relative_to(Path(__file__).resolve().parents[2]):
        raise ValueError("Ausgabe muss ausserhalb des Repositories liegen.")
    fd = os.open(pfad, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as datei:
        datei.write(inhalt)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ausgabe", type=Path)
    parser.add_argument("--nur-pruefung", action="store_true")
    parser.add_argument("--mitarbeiter-sub", default="last-mitarbeiter",
                        help="Subject-Claim des ausschliesslich synthetischen Messbenutzers")
    args = parser.parse_args()
    try:
        schreiben(args.ausgabe, ("\\set ON_ERROR_STOP on\n" + kontroll_sql()) if args.nur_pruefung
                  else sql(args.mitarbeiter_sub))
    except (OSError, ValueError):
        parser.exit(2, "SQL-Ausgabe fehlgeschlagen; neuen geschützten Pfad ausserhalb des Repositories prüfen.\n")


if __name__ == "__main__":
    main()
