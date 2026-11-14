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
from urllib.parse import parse_qs, urlsplit

HIER = Path(__file__).resolve().parent
sys.path.insert(0, str(HIER))

import verarbeitung_foto  # noqa: E402


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
        if self.path.startswith("/api/v1/dokumente/"):
            self.antworten(200, {"status": "EINGANGSKORB"})
        else:
            self.antworten(200, {"gesamt": 7, "treffer": []})

    def do_POST(self):  # noqa: N802
        koerper = self.rfile.read(int(self.headers["Content-Length"]))
        with self.sperre:
            self.anfragen.append(("POST", self.path, koerper))
        self.antworten(202, [{"id": "00000000-0000-0000-0000-000000000001"}])


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

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.ordner.cleanup()

    def setUp(self):
        Server.anfragen = []

    def starten(self, skript: str, *argumente: str) -> subprocess.CompletedProcess:
        umgebung = {**os.environ, "ARCHIV_TOKEN_DATEI": str(self.token)}
        return subprocess.run([sys.executable, str(HIER / skript), *argumente], capture_output=True, text=True,
                              env=umgebung, timeout=60)

    def test_upload_zaehlt_aufwaermen_nicht(self):
        lauf = self.starten("upload.py", "--url", self.url, "--anzahl", "5", "--parallel", "2", "--groesse", "2000",
                            "--aufwaermen", "3", "--grenze", "30", "--json")
        self.assertEqual(lauf.returncode, 0, lauf.stderr)
        bericht = json.loads(lauf.stdout)
        self.assertEqual((bericht["angenommen"], bericht["aufwaermen"]), (5, 3))
        self.assertEqual(len([a for a in Server.anfragen if a[0] == "POST"]), 8)

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


if __name__ == "__main__":
    unittest.main()
