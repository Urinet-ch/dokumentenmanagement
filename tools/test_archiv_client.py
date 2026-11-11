"""Tests für tools/archiv_client.py (Req 11.4, 11.5, 11.9) gegen einen lokalen HTTP-Server; nur synthetische Daten.

Aufruf: python3 -m unittest tools/test_archiv_client.py
"""

from __future__ import annotations

import json
import os
import socket
import sys
import tempfile
import threading
import unittest
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import archiv_client  # noqa: E402

TOKEN = "urn_probe_token"


class _Archiv(BaseHTTPRequestHandler):
    antworten: list[tuple[int, dict]] = []
    empfangen: list[dict] = []

    def do_POST(self):
        laenge = int(self.headers["Content-Length"])
        roh = self.rfile.read(laenge)
        nachricht = BytesParser(policy=HTTP).parsebytes(
            f"Content-Type: {self.headers['Content-Type']}\r\n\r\n".encode("ascii") + roh)
        teile = {teil.get_param("name", header="content-disposition"): teil for teil in nachricht.iter_parts()}
        self.empfangen.append({
            "pfad": self.path,
            "token": self.headers["Authorization"],
            "dateiname": teile["datei"].get_filename(),
            "pdf": teile["datei"].get_payload(decode=True),
            "metadaten": json.loads(teile["metadaten"].get_payload(decode=True).decode("utf-8")),
        })
        status, inhalt = self.antworten.pop(0)
        daten = json.dumps(inhalt).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json" if status < 400 else "application/problem+json")
        self.send_header("Content-Length", str(len(daten)))
        self.end_headers()
        self.wfile.write(daten)

    def log_message(self, *argumente):
        pass


class ArchivClientTest(unittest.TestCase):

    def setUp(self):
        self.ordner = Path(tempfile.mkdtemp(prefix="archiv-client-")).resolve()
        token = self.ordner / "token"
        token.write_text(TOKEN + "\n", encoding="utf-8")
        token.chmod(0o600)
        self.umgebung = {k: os.environ.get(k) for k in ("ARCHIV_TOKEN_DATEI", "ARCHIV_URL")}
        os.environ["ARCHIV_TOKEN_DATEI"] = str(token)
        os.environ.pop("ARCHIV_URL", None)
        _Archiv.antworten = []
        _Archiv.empfangen = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Archiv)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.ausgabe = self.ordner / "ausgabe"
        self.ausgabe.mkdir()
        self.stderr = sys.stderr
        sys.stderr = open(os.devnull, "w")

    def tearDown(self):
        sys.stderr.close()
        sys.stderr = self.stderr
        self.server.shutdown()
        self.server.server_close()
        for name, wert in self.umgebung.items():
            if wert is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = wert
        for pfad in sorted(self.ordner.rglob("*"), reverse=True):
            pfad.rmdir() if pfad.is_dir() else pfad.unlink()
        self.ordner.rmdir()

    def pdf(self, name: str) -> Path:
        pfad = self.ausgabe / name
        pfad.write_bytes(b"%PDF-1.4\n% synthetisch " + name.encode("ascii") + b"\n%%EOF\n")
        return pfad

    @staticmethod
    def metadaten(belegnummer: str) -> dict:
        return {"art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnüng", "datum": "2026-03-10",
                "partner": {"crmId": "K-PROBE"},
                "finanz": {"belegnummer": belegnummer, "richtung": "EINNAHME", "betrag": "1.00", "waehrung": "CHF"}}

    def unerreichbar(self) -> str:
        with socket.socket() as frei:
            frei.bind(("127.0.0.1", 0))
            return f"http://127.0.0.1:{frei.getsockname()[1]}"

    def test_req_11_4_sendet_pdf_und_metadaten_mit_einer_zeile(self):
        _Archiv.antworten = [(202, {"id": "d-1", "status": "NEU"})]
        pdf = self.pdf("rechnung-2026-901.pdf")

        ergebnis = archiv_client.archivieren(pdf, self.metadaten("2026-901"), url=self.url)

        self.assertEqual(("ANGENOMMEN", "d-1", "NEU"), (ergebnis.zustand, ergebnis.id, ergebnis.status))
        anfrage = _Archiv.empfangen[0]
        self.assertEqual("/api/v1/dokumente/generator", anfrage["pfad"])
        self.assertEqual(f"Bearer {TOKEN}", anfrage["token"])
        self.assertEqual("rechnung-2026-901.pdf", anfrage["dateiname"])
        self.assertEqual(pdf.read_bytes(), anfrage["pdf"])
        self.assertEqual(self.metadaten("2026-901"), anfrage["metadaten"])
        self.assertTrue(pdf.is_file())

    def test_req_11_8_vorhandenes_dokument_gilt_als_erfolg(self):
        _Archiv.antworten = [(200, {"id": "d-1", "status": "ARCHIVIERT"})]

        ergebnis = archiv_client.archivieren(self.pdf("r.pdf"), self.metadaten("2026-901"), url=self.url)

        self.assertEqual(("VORHANDEN", "d-1"), (ergebnis.zustand, ergebnis.id))

    def test_req_11_5_ohne_archiv_ausstehend_ablegen_und_beim_naechsten_aufruf_senden(self):
        erstes = self.pdf("rechnung-2026-901.pdf")

        ergebnis = archiv_client.archivieren(erstes, self.metadaten("2026-901"), url=self.unerreichbar())

        ausstehend = self.ausgabe / "archiv-ausstehend"
        self.assertEqual("AUSSTEHEND", ergebnis.zustand)
        self.assertEqual(erstes.read_bytes(), (ausstehend / erstes.name).read_bytes())
        self.assertEqual(self.metadaten("2026-901"),
                         json.loads((ausstehend / "rechnung-2026-901.json").read_text(encoding="utf-8")))

        _Archiv.antworten = [(202, {"id": "d-1", "status": "NEU"}), (202, {"id": "d-2", "status": "NEU"})]
        ergebnis = archiv_client.archivieren(self.pdf("rechnung-2026-902.pdf"), self.metadaten("2026-902"),
                                             url=self.url)

        self.assertEqual("d-2", ergebnis.id)
        self.assertEqual(["2026-901", "2026-902"],
                         [a["metadaten"]["finanz"]["belegnummer"] for a in _Archiv.empfangen])
        self.assertEqual([], list(ausstehend.iterdir()))

    def test_req_11_5_serverfehler_gilt_als_nicht_erreichbar(self):
        _Archiv.antworten = [(503, {"code": "ABLAGE_NICHT_ERREICHBAR"})]

        ergebnis = archiv_client.archivieren(self.pdf("r.pdf"), self.metadaten("2026-901"), url=self.url)

        self.assertEqual("AUSSTEHEND", ergebnis.zustand)
        self.assertTrue((self.ausgabe / "archiv-ausstehend" / "r.pdf").is_file())

    def test_req_11_5_bleibt_das_archiv_weg_wird_das_neue_pdf_ohne_versuch_angehaengt(self):
        weg = self.unerreichbar()
        archiv_client.archivieren(self.pdf("a.pdf"), self.metadaten("2026-901"), url=weg)

        ergebnis = archiv_client.archivieren(self.pdf("b.pdf"), self.metadaten("2026-902"), url=weg)

        self.assertEqual("AUSSTEHEND", ergebnis.zustand)
        self.assertEqual(["a.json", "a.pdf", "b.json", "b.pdf"],
                         sorted(p.name for p in (self.ausgabe / "archiv-ausstehend").iterdir()))

    def test_req_11_9_bei_409_nach_archiv_konflikt_verschieben_und_nicht_erneut_senden(self):
        _Archiv.antworten = [(409, {"code": "BELEGNUMMER_VERGEBEN", "detail": "Die Belegnummer ist vergeben."})]
        pdf = self.pdf("rechnung-2026-901.pdf")

        ergebnis = archiv_client.archivieren(pdf, self.metadaten("2026-901"), url=self.url)

        konflikt = self.ausgabe / "archiv-konflikt" / "rechnung-2026-901.pdf"
        self.assertEqual(("KONFLIKT", konflikt), (ergebnis.zustand, ergebnis.pfad))
        self.assertFalse(pdf.exists())
        self.assertTrue(konflikt.is_file())

        _Archiv.antworten = [(202, {"id": "d-2", "status": "NEU"})]
        archiv_client.archivieren(self.pdf("andere.pdf"), self.metadaten("2026-902"), url=self.url)

        self.assertEqual(2, len(_Archiv.empfangen))
        self.assertTrue(konflikt.is_file())

    def test_req_11_9_ausstehendes_pdf_mit_409_wandert_nach_archiv_konflikt(self):
        archiv_client.archivieren(self.pdf("a.pdf"), self.metadaten("2026-901"), url=self.unerreichbar())
        _Archiv.antworten = [(409, {"code": "BELEGNUMMER_VERGEBEN"}), (202, {"id": "d-2", "status": "NEU"})]

        archiv_client.archivieren(self.pdf("b.pdf"), self.metadaten("2026-902"), url=self.url)

        self.assertEqual([], list((self.ausgabe / "archiv-ausstehend").iterdir()))
        self.assertEqual(["a.json", "a.pdf"], sorted(p.name for p in (self.ausgabe / "archiv-konflikt").iterdir()))

    def test_req_11_6_abgelehnte_metadaten_ergeben_einen_fehler_mit_feldern(self):
        _Archiv.antworten = [(422, {"code": "PFLICHTFELD_FEHLT", "felder": ["betrag"]})]
        pdf = self.pdf("r.pdf")

        with self.assertRaises(archiv_client.ArchivFehler) as fehler:
            archiv_client.archivieren(pdf, {"art": "AUSGANGSRECHNUNG"}, url=self.url)

        self.assertEqual((422, "PFLICHTFELD_FEHLT", ["betrag"]),
                         (fehler.exception.status, fehler.exception.code, fehler.exception.felder))
        self.assertTrue(pdf.is_file())
        self.assertFalse((self.ausgabe / "archiv-ausstehend").exists())

    def test_token_datei_muss_rechte_600_haben(self):
        Path(os.environ["ARCHIV_TOKEN_DATEI"]).chmod(0o644)

        with self.assertRaisesRegex(archiv_client.ArchivFehler, "chmod 600"):
            archiv_client.archivieren(self.pdf("r.pdf"), self.metadaten("2026-901"), url=self.url)

        self.assertEqual([], _Archiv.empfangen)


if __name__ == "__main__":
    unittest.main()
