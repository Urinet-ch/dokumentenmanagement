"""Optionale PostgreSQL-Prüfung der synthetischen Suchdaten in einem netzlosen Wegwerf-Container."""

import os
import subprocess
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

HIER = Path(__file__).resolve().parent
REPO = HIER.parents[1]
IMAGE = "pgvector/pgvector:0.8.7-pg17-trixie"
NS = "{http://www.liquibase.org/xml/ns/dbchangelog}"


@unittest.skipUnless(os.environ.get("ARCHIV_LASTPROBE_SQL_TEST") == "1", "expliziter Docker-Test")
class SuchdatenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        start = subprocess.run(
            ["docker", "run", "--rm", "-d", "--network", "none", "--cpus", "0.5", "--memory", "256m",
             "-e", "POSTGRES_HOST_AUTH_METHOD=trust", IMAGE],
            check=True, capture_output=True, text=True, timeout=60,
        )
        cls.container = start.stdout.strip()
        cls.addClassCleanup(cls.stoppen)
        ende = time.monotonic() + 90
        while time.monotonic() < ende:
            bereit = subprocess.run(["docker", "exec", cls.container, "pg_isready", "-U", "postgres", "-q"],
                                    capture_output=True, timeout=10)
            if bereit.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Die isolierte PostgreSQL-Testinstanz ist nicht bereit")
        cls.psql("postgres", "CREATE DATABASE archiv_lastprobe;")
        sql = [
            "CREATE SCHEMA m_last; CREATE TABLE public.mandant (code text primary key);",
            "INSERT INTO public.mandant VALUES ('last'); SET search_path TO m_last,public;",
            "CREATE TABLE dokumentart (code text primary key);",
            "INSERT INTO dokumentart VALUES ('AUSGANGSRECHNUNG'),('LIEFERANTENBELEG'),('OFFERTE'),"
            "('QUITTUNG'),('VERTRAG'),('KORRESPONDENZ'),('LOHNABRECHNUNG'),('VERSICHERUNG');",
        ]
        changesets = REPO / "backend/src/main/resources/db/mandant/changesets"
        for datei in ("005-dokument.xml", "015-dokument-dateiname.xml",
                      "028-dokument-volltext-ohne-dateiname.xml"):
            for changeset in ET.parse(changesets / datei).getroot().findall(NS + "changeSet"):
                sql.extend((element.text or "") + ";" for element in changeset.findall(NS + "sql"))
        cls.psql("archiv_lastprobe", "\n".join(sql))

    @classmethod
    def stoppen(cls):
        subprocess.run(["docker", "stop", cls.container], check=True, capture_output=True, timeout=30)

    @classmethod
    def psql(cls, datenbank, sql, *, check=True):
        return subprocess.run(
            ["docker", "exec", "-i", cls.container, "psql", "-U", "postgres", "-d", datenbank,
             "-v", "ON_ERROR_STOP=1", "-At"],
            input=sql, capture_output=True, text=True, check=check, timeout=180,
        )

    def test_fuellt_50_000_und_verweigert_unsichere_ziele(self):
        sql = (HIER / "suchdaten.sql").read_text(encoding="utf-8")
        falsch = self.psql("postgres", sql, check=False)
        self.assertNotEqual(falsch.returncode, 0)
        self.assertIn("Wegwerf-Datenbank", falsch.stderr)
        self.psql("archiv_lastprobe", sql)
        pruefung = self.psql("archiv_lastprobe", """
            SELECT count(*), count(*) FILTER (WHERE vertraulichkeit = 'NORMAL'),
                   count(*) FILTER (WHERE vertraulichkeit = 'INTERN'),
                   count(*) FILTER (WHERE vertraulichkeit = 'PERSONAL')
            FROM m_last.dokument;
            SELECT count(*) FROM m_last.dokument_finanz;
            SELECT count(*) FROM m_last.dokument
            WHERE volltext_tsv @@ to_tsquery('german', 'Kassenzettel');
            SELECT (avg(length(volltext)) BETWEEN 1000 AND 2000)::text,
                   count(*) FILTER (WHERE volltext LIKE '%Rechnung%' AND volltext LIKE '%Lieferung%') > 40000
            FROM m_last.dokument;
        """)
        self.assertEqual(pruefung.stdout.splitlines(), ["50000|30000|10000|10000", "16666", "10000", "true|t"])
        erneut = self.psql("archiv_lastprobe", sql, check=False)
        self.assertNotEqual(erneut.returncode, 0)
        self.assertIn("muss für die Füllung leer sein", erneut.stderr)
        self.psql("archiv_lastprobe", "INSERT INTO public.mandant VALUES ('anderer');")
        anderer_mandant = self.psql("archiv_lastprobe", sql, check=False)
        self.assertNotEqual(anderer_mandant.returncode, 0)
        self.assertIn("ausschliesslich den Mess-Mandanten", anderer_mandant.stderr)
        self.assertEqual(self.psql("archiv_lastprobe", "SELECT count(*) FROM m_last.dokument;").stdout, "50000\n")


if __name__ == "__main__":
    unittest.main()
