"""Tests der Lastskripte gegen einen lokalen Test-Server (nur Standardbibliothek, keine echten Daten)."""

from __future__ import annotations

import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER))

import verarbeitung_foto  # noqa: E402
import auswertung  # noqa: E402
import upload  # noqa: E402
import suche  # noqa: E402


def box(typ: bytes, inhalt: bytes) -> bytes:
    return struct.pack(">I", 8 + len(inhalt)) + typ + inhalt


def boxen(daten: bytes) -> list[bytes]:
    """Typen der Boxen auf oberster Ebene; scheitert, wenn eine Länge nicht aufgeht."""
    typen, pos = [], 0
    while pos < len(daten):
        laenge = struct.unpack(">I", daten[pos:pos + 4])[0]
        if laenge < 8 or pos + laenge > len(daten):
            raise ValueError(f"ungültige Box bei {pos}")
        typen.append(daten[pos + 4:pos + 8])
        pos += laenge
    return typen


HEIC = box(b"ftyp", b"heic" + b"\0\0\0\0" + b"mif1heic") + box(b"meta", b"\0" * 12) + box(b"mdat", b"bild")
JPEG = b"\xff\xd8\xff\xe0" + b"\0" * 16 + b"\xff\xd9"


class FototypTest(unittest.TestCase):
    def test_erkennt_jpeg_heic_und_heif(self):
        self.assertEqual(verarbeitung_foto.fototyp(JPEG), ("jpg", "image/jpeg"))
        self.assertEqual(verarbeitung_foto.fototyp(HEIC), ("heic", "image/heic"))
        heif = box(b"ftyp", b"mif1" + b"\0\0\0\0" + b"mif1") + box(b"mdat", b"x")
        self.assertEqual(verarbeitung_foto.fototyp(heif), ("heif", "image/heif"))

    def test_lehnt_andere_formate_ab(self):
        for daten in (b"%PDF-1.4", b"\x89PNG\r\n\x1a\n" + b"\0" * 8, box(b"ftyp", b"isom\0\0\0\0")):
            with self.assertRaises(SystemExit):
                verarbeitung_foto.fototyp(daten)

    def test_eindeutig_jpeg_haengt_hinter_eoi_an(self):
        a, b = verarbeitung_foto.eindeutig(JPEG, "jpg"), verarbeitung_foto.eindeutig(JPEG, "jpg")
        self.assertTrue(a.startswith(JPEG) and b.startswith(JPEG))
        self.assertNotEqual(a, b)

    def test_eindeutig_heic_als_free_box(self):
        a, b = verarbeitung_foto.eindeutig(HEIC, "heic"), verarbeitung_foto.eindeutig(HEIC, "heic")
        self.assertNotEqual(a, b)
        self.assertTrue(a.startswith(HEIC))
        self.assertEqual(boxen(a), [b"ftyp", b"meta", b"mdat", b"free"])

    @unittest.skipUnless(__import__("importlib").util.find_spec("pillow_heif"), "pillow-heif nicht installiert")
    def test_echtes_heic_bleibt_lesbar(self):
        import pillow_heif
        from PIL import Image

        pillow_heif.register_heif_opener()
        puffer = io.BytesIO()
        Image.new("RGB", (64, 48), (120, 80, 40)).save(puffer, format="HEIF")
        original = puffer.getvalue()
        endung, _ = verarbeitung_foto.fototyp(original)
        bild = Image.open(io.BytesIO(verarbeitung_foto.eindeutig(original, endung)))
        self.assertEqual(bild.size, (64, 48))


class Server(BaseHTTPRequestHandler):
    anfragen: list[tuple[str, str, bytes]] = []
    sperre = threading.Lock()
    scan_abfragen = 0
    scan_sofort_fertig = False
    upload_status = "NEU"

    def log_message(self, *_):
        pass

    def antworten(self, status: int, daten: object) -> None:
        koerper = json.dumps(daten).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(koerper)))
        self.end_headers()
        self.wfile.write(koerper)

    def do_GET(self):  # noqa: N802
        with self.sperre:
            self.anfragen.append(("GET", self.path, b""))
        if self.path == "/api/v1/dokumente/scan":
            Server.scan_abfragen += 1
            status = "EINGANGSKORB" if Server.scan_sofort_fertig or Server.scan_abfragen > 1 else "VERARBEITUNG"
            self.antworten(200, {"status": status})
        elif self.path.startswith("/api/v1/dokumente/"):
            self.antworten(200, {"status": "EINGANGSKORB"})
        else:
            self.antworten(200, {"gesamt": 7, "treffer": []})

    def do_POST(self):  # noqa: N802
        koerper = self.rfile.read(int(self.headers["Content-Length"]))
        with self.sperre:
            self.anfragen.append(("POST", self.path, koerper))
        if b'name="dateien"' not in koerper:
            self.antworten(400, {})
            return
        dokument = "scan" if b'filename="lastlauf-20-seiten-scan.pdf"' in koerper else "00000000-0000-0000-0000-000000000001"
        self.antworten(202, [{"id": dokument, "status": Server.upload_status}])


class SkriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Server)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}"
        cls.ordner = tempfile.TemporaryDirectory()
        cls.token = Path(cls.ordner.name) / "token"
        cls.token.write_text("test-token", encoding="utf-8")
        cls.token.chmod(0o600)
        pdfinfo = Path(cls.ordner.name) / "pdfinfo"
        pdfinfo.write_text("#!/bin/sh\nprintf 'Pages:          20\\n'\n", encoding="utf-8")
        pdfinfo.chmod(0o700)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.ordner.cleanup()

    def setUp(self):
        Server.anfragen = []
        Server.scan_abfragen = 0
        Server.scan_sofort_fertig = False
        Server.upload_status = "NEU"

    def starten(self, skript: str, *argumente: str) -> subprocess.CompletedProcess:
        umgebung = {**os.environ, "ARCHIV_TOKEN_DATEI": str(self.token),
                    "PATH": self.ordner.name + os.pathsep + os.environ.get("PATH", "")}
        return subprocess.run([sys.executable, str(HIER / skript), *argumente], capture_output=True, text=True,
                              env=umgebung, timeout=60)

    def test_upload_zaehlt_aufwaermen_nicht(self):
        lauf = self.starten("upload.py", "--url", self.url, "--anzahl", "5", "--parallel", "2", "--groesse", "2000",
                            "--aufwaermen", "3", "--grenze", "30", "--json")
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        bericht = json.loads(lauf.stdout)
        self.assertEqual((bericht["angenommen"], bericht["aufwaermen"]), (5, 3))
        self.assertEqual(len([a for a in Server.anfragen if a[0] == "POST"]), 8)

    def test_upload_lehnt_dublette_trotz_202_ab(self):
        Server.upload_status = "DUBLETTE"
        lauf = self.starten("upload.py", "--url", self.url, "--anzahl", "2", "--parallel", "1",
                            "--groesse", "2000", "--aufwaermen", "1")
        self.assertNotEqual(lauf.returncode, 0)
        self.assertIn("Aufwärmen fehlgeschlagen", lauf.stderr)
        self.assertEqual(len(Server.anfragen), 1)

    def test_foto_lehnt_dublette_trotz_202_ab(self):
        Server.upload_status = "DUBLETTE"
        foto = Path(self.ordner.name) / "foto-dublette.jpg"
        foto.write_bytes(JPEG)
        lauf = self.starten("verarbeitung_foto.py", "--basis-url", self.url, "--foto", str(foto),
                            "--anzahl", "1", "--aufwaermen", "0")
        self.assertNotEqual(lauf.returncode, 0)
        self.assertIn("Upload nicht angenommen", lauf.stderr)
        self.assertEqual(len(Server.anfragen), 1)

    def test_upload_pdf_hat_gueltige_kreuzreferenz_und_eine_seite(self):
        dokument = upload.pdf(200000)
        xref = int(dokument.split(b"startxref\n")[1].splitlines()[0])
        self.assertTrue(dokument[xref:].startswith(b"xref\n0 4\n"))
        for nummer, zeile in enumerate(dokument[xref:].splitlines()[3:6], 1):
            offset = int(zeile[:10])
            self.assertTrue(dokument[offset:].startswith(f"{nummer} 0 obj\n".encode()))
        self.assertIn(b"/Type /Page ", dokument)
        self.assertNotEqual(dokument, upload.pdf(200000))

    def test_p95_entscheidung_nicht_gerundet(self):
        with patch.object(upload, "token_lesen", return_value="token"), \
             patch.object(upload, "hochladen", return_value=(202, 2.0004)), \
             patch.object(sys, "argv", ["upload.py", "--url", self.url, "--anzahl", "1",
                                        "--parallel", "1", "--groesse", "2000"]), \
             patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(upload.main(), 1)
        with patch.object(suche, "token_lesen", return_value="token"), \
             patch.object(suche, "suchen", return_value=(200, 1.0004, 50000)), \
             patch.object(sys, "argv", ["suche.py", "--url", self.url, "--anzahl", "1",
                                        "--parallel", "1"]), \
             patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(suche.main(), 1)

    def test_suche_zaehlt_aufwaermen_nicht(self):
        lauf = self.starten("suche.py", "--url", self.url, "--anzahl", "6", "--parallel", "2", "--aufwaermen", "4",
                            "--grenze", "30", "--json")
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        bericht = json.loads(lauf.stdout)
        self.assertEqual((bericht["beantwortet"], bericht["aufwaermen"]), (6, 4))
        self.assertEqual(len(Server.anfragen), 1 + 4 + 6)  # Probe, Aufwärmen, Messung
        self.assertEqual(bericht["modus"], "volltext")

    def test_hybridsuche_sendet_begriffe_und_verwendet_p95_grenze_zwei(self):
        lauf = self.starten("suche.py", "--url", self.url, "--anzahl", "8", "--parallel", "2", "--modus", "hybrid",
                            "--json")
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        bericht = json.loads(lauf.stdout)
        self.assertEqual((bericht["modus"], bericht["grenze_s"], bericht["beantwortet"]), ("hybrid", 2.0, 8))
        messanfragen = [parse_qs(urlsplit(path).query) for _, path, _ in Server.anfragen[1:]]
        self.assertTrue(all(q.get("modus") == ["hybrid"] and "q" in q for q in messanfragen))

    def test_foto_heic_mit_aufwaermen(self):
        foto = Path(self.ordner.name) / "foto.heic"
        foto.write_bytes(HEIC)
        lauf = self.starten("verarbeitung_foto.py", "--basis-url", self.url, "--foto", str(foto), "--anzahl", "3",
                            "--aufwaermen", "2", "--intervall", "0.01")
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        self.assertIn("p95 über 3 Läufe", lauf.stdout)
        self.assertEqual(lauf.stdout.count("zählt nicht"), 2)
        uploads = [a[2] for a in Server.anfragen if a[0] == "POST"]
        self.assertEqual(len(uploads), 5)
        self.assertTrue(all(b'filename="lastlauf.heic"' in u and b"Content-Type: image/heic" in u for u in uploads))
        self.assertEqual(len(set(uploads)), 5)

    def test_foto_mit_synthetischem_begleit_scan(self):
        foto = Path(self.ordner.name) / "foto.heic"
        foto.write_bytes(HEIC)
        scan = Path(self.ordner.name) / "scan.pdf"
        scan.write_bytes(b"%PDF-1.7 synthetischer 20-Seiten-Scan")

        lauf = self.starten("verarbeitung_foto.py", "--basis-url", self.url, "--foto", str(foto),
                            "--begleit-pdf", str(scan), "--anzahl", "2", "--aufwaermen", "1",
                            "--intervall", "0.01")

        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        uploads = [a[2] for a in Server.anfragen if a[0] == "POST"]
        self.assertEqual(len(uploads), 4)
        self.assertIn(b'filename="lastlauf-20-seiten-scan.pdf"', uploads[1])
        self.assertIn(b"Content-Type: application/pdf", uploads[1])
        self.assertIn(b'filename="lastlauf.heic"', uploads[2])
        self.assertIn(b'filename="lastlauf.heic"', uploads[3])
        self.assertIn("Begleit-Scan im Eingangskorb.", lauf.stdout)
        self.assertIn("Fotoläufe mit beobachtetem Scan in VERARBEITUNG: 1", lauf.stdout)
        self.assertEqual(Server.anfragen[-1][:2], ("GET", "/api/v1/dokumente/scan"))

    def test_begleit_scan_ohne_beobachtete_ueberlappung_ist_kein_nachweis(self):
        foto = Path(self.ordner.name) / "foto-heic.heic"
        foto.write_bytes(HEIC)
        scan = Path(self.ordner.name) / "scan-fertig.pdf"
        scan.write_bytes(b"%PDF-1.7")
        Server.scan_sofort_fertig = True
        lauf = self.starten("verarbeitung_foto.py", "--basis-url", self.url, "--foto", str(foto),
                            "--begleit-pdf", str(scan), "--anzahl", "1", "--aufwaermen", "0")
        self.assertEqual(lauf.returncode, 1)
        self.assertIn("Keine Überlappung beobachtet", lauf.stderr)

    def test_begleit_scan_lehnt_falsche_seitenzahl_ab(self):
        scan = Path(self.ordner.name) / "falsch.pdf"
        scan.write_bytes(b"%PDF-1.7")
        with patch.object(verarbeitung_foto.subprocess, "run") as pdfinfo:
            pdfinfo.return_value.stdout = "Pages: 19\n"
            with self.assertRaisesRegex(ValueError, "genau 20 Seiten"):
                verarbeitung_foto.begleit_scan_einreihen(self.url, "test-token", scan)

    def test_foto_schreibt_csv(self):
        foto = Path(self.ordner.name) / "csv-foto.jpg"
        foto.write_bytes(JPEG)
        csv = Path(self.ordner.name) / "messung.csv"
        lauf = self.starten("verarbeitung_foto.py", "--basis-url", self.url, "--foto", str(foto),
                            "--anzahl", "3", "--aufwaermen", "0", "--csv", str(csv))
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        self.assertEqual(auswertung.auswerten(csv)["laeufe"], 3)

    def test_foto_standardwerte_entsprechen_messbedingungen(self):
        foto = Path(self.ordner.name) / "foto.jpg"
        foto.write_bytes(JPEG)

        lauf = self.starten("verarbeitung_foto.py", "--basis-url", self.url, "--foto", str(foto),
                            "--intervall", "0.01")

        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        self.assertIn("p95 über 50 Läufe", lauf.stdout)
        self.assertEqual(lauf.stdout.count("zählt nicht"), 20)
        self.assertEqual(len([a for a in Server.anfragen if a[0] == "POST"]), 70)


class AuswertungTest(unittest.TestCase):
    def test_perzentile_und_fehlerquote(self):
        with tempfile.TemporaryDirectory() as ordner:
            pfad = Path(ordner) / "werte.csv"
            pfad.write_text("lauf,status,dauer_s\n1,ok,1\n2,fehler,\n3,ok,3\n4,ok,2\n", encoding="utf-8")
            wert = auswertung.auswerten(pfad)
            self.assertEqual((wert["p50_s"], wert["p95_s"], wert["fehlerquote_prozent"]), (2, 3, 25))

    def test_ungueltige_werte_und_leere_csv(self):
        with tempfile.TemporaryDirectory() as ordner:
            pfad = Path(ordner) / "werte.csv"
            for zeilen in ("", "1,ok,NaN\n", "2,ok,1\n", "1,fehler,2\n"):
                pfad.write_text("lauf,status,dauer_s\n" + zeilen, encoding="utf-8")
                with self.assertRaises(ValueError):
                    auswertung.auswerten(pfad)


if __name__ == "__main__":
    unittest.main()
