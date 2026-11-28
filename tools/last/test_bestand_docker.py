"""Optionaler SQL-Trockenlauf auf eigener PostgreSQL-Instanz, keine Ports."""

import os
import csv
import hashlib
import io
import subprocess
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import bestand
import auftrags_export
import auftragsauswertung
from uuid import UUID


@unittest.skipUnless(os.environ.get("LAST_TEST_DB_IMAGE"), "Kein Image für optionalen SQL-Trockenlauf gewählt")
class SqlTest(unittest.TestCase):
    def test_sql_mit_dokument_schema_und_rueckbau(self):
        image = os.environ["LAST_TEST_DB_IMAGE"]
        subprocess.run(["docker", "image", "inspect", image], check=True, capture_output=True)
        container = subprocess.check_output(
            ["docker", "run", "-d", "--network", "none", "--read-only", "--user", "999:999",
             "--memory", "256m", "--cpus", "0.3",
             "--mount", "type=volume,dst=/var/lib/postgresql/data",
             "--tmpfs", "/var/run/postgresql:uid=999,gid=999",
             "--tmpfs", "/tmp", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
             "-e", "POSTGRES_HOST_AUTH_METHOD=trust", image,
             "postgres", "-c", "shared_buffers=16MB"], text=True).strip()
        try:
            for _ in range(60):
                bereit = subprocess.run(["docker", "exec", container, "pg_isready", "-U", "postgres"],
                                        capture_output=True)
                if bereit.returncode == 0:
                    break
                time.sleep(1)
            else:
                self.fail("Wegwerf-Datenbank nicht bereit.")
            repo = Path(__file__).resolve().parents[2]
            xml = ET.parse(repo / "backend/src/main/resources/db/mandant/changesets/005-dokument.xml")
            ns = {"db": "http://www.liquibase.org/xml/ns/dbchangelog"}
            schema = "\n".join(k.text.rstrip().rstrip(";") + ";" for k in xml.findall(".//db:sql", ns))
            init = """CREATE TABLE public.mandant (code text, bucket text);
INSERT INTO public.mandant VALUES ('last','urinet-archiv-a-last');
CREATE TABLE public.auftrag (id bigint);
CREATE SCHEMA m_last; SET search_path=m_last,public;
CREATE TABLE dokumentart(code text PRIMARY KEY);
""" + "INSERT INTO dokumentart VALUES " + ",".join(f"('{art}')" for art in bestand.ARTEN) + \
                ";\n" + schema + "\nALTER TABLE dokument ADD COLUMN dateiname text;\n"
            def psql(sql):
                return subprocess.run(["docker", "exec", "-i", container, "psql", "-U", "postgres",
                                       "-v", "ON_ERROR_STOP=1", "-q"], input=sql, text=True,
                                      capture_output=True, timeout=120)
            lauf = psql(init + bestand.sql())
            self.assertEqual(lauf.returncode, 0, lauf.stderr)
            self.assertIn("50000", lauf.stdout)
            self.assertNotEqual(psql(bestand.sql()).returncode, 0, "Bestehender Bestand muss geschützt sein.")
            self.assertEqual(psql(bestand.kontroll_sql()).returncode, 0)
            self.assertEqual(psql("""
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM m_last.dokument d JOIN m_last.dokument_finanz f ON f.dokument_id=d.id
            WHERE (d.art='AUSGANGSRECHNUNG' AND f.richtung<>'EINNAHME')
               OR (d.art='LIEFERANTENBELEG' AND f.richtung<>'AUSGABE')
               OR d.art NOT IN ('AUSGANGSRECHNUNG','LIEFERANTENBELEG'))
 THEN RAISE EXCEPTION 'Unpassende synthetische Finanzfelder'; END IF;
END $$;
""").returncode, 0)
            auftraege = """
ALTER TABLE public.auftrag ADD COLUMN status text, ADD COLUMN versuche int,
 ADD COLUMN mandant text, ADD COLUMN typ text, ADD COLUMN nutzlast jsonb,
 ADD COLUMN gestartet timestamptz, ADD COLUMN beendet timestamptz;
INSERT INTO public.auftrag
SELECT n*2+k, 'FERTIG', 0, 'last',
 CASE WHEN k=0 THEN 'VERARBEITEN' ELSE 'KI_VORSCHLAG' END,
 jsonb_build_object('dokumentId', md5('archiv-last-' || n)::uuid),
 '2026-01-01T00:00:00Z'::timestamptz,
 '2026-01-01T00:00:30Z'::timestamptz
FROM generate_series(1,50) n CROSS JOIN generate_series(0,1) k;
UPDATE m_last.dokument SET status='EINGANGSKORB'
 WHERE id IN (SELECT md5('archiv-last-' || n)::uuid FROM generate_series(1,50) n);
"""
            self.assertEqual(psql(auftraege).returncode, 0)
            ids = [str(UUID(hashlib.md5(f"archiv-last-{n}".encode()).hexdigest())) for n in range(1, 51)]
            export = psql(auftrags_export.sql(ids))
            self.assertEqual(export.returncode, 0, export.stderr)
            zeilen = list(csv.DictReader(io.StringIO(export.stdout)))
            self.assertTrue(auftragsauswertung.auswerten(zeilen)["bestanden"])
            self.assertEqual(psql("UPDATE public.auftrag SET beendet=NULL WHERE id=2;").returncode, 0)
            export = psql(auftrags_export.sql(ids))
            self.assertEqual(export.returncode, 0, export.stderr)
            with self.assertRaises(ValueError):
                auftragsauswertung.auswerten(list(csv.DictReader(io.StringIO(export.stdout))))
        finally:
            subprocess.run(["docker", "rm", "-fv", container], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
