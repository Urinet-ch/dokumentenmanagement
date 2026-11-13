#!/usr/bin/env python3
"""Client für den Generator-Endpunkt des UriNet Archivs (Spec 01, Req 11.4, 11.5, 11.9; Aufgabe 4.7).

Generatoren wie rechnung_gen.py und urinet_pdf.py legen ein fertiges PDF mit einer Zeile ab:

    from archiv_client import archivieren
    archivieren("ausgabe/rechnung-2026-003.pdf", {"art": "AUSGANGSRECHNUNG", "titel": "...", ...})

Verhalten:
- 202 (angenommen) oder 200 (dieselbe Datei liegt schon im Archiv): Erfolg, Rückgabe mit der Dokument-ID.
- Archiv nicht erreichbar (Netzfehler, Zeitüberschreitung, HTTP 5xx): Das PDF wird als Kopie samt Metadaten in
  `archiv-ausstehend/` abgelegt und beim nächsten Aufruf vor dem neuen PDF erneut gesendet (Req 11.5).
- 409 (Belegnummer vergeben, Frist abgelaufen): Das PDF wird nach `archiv-konflikt/` verschoben und nicht erneut
  gesendet (Req 11.9).
- andere Fehler (400, 401, 403, 413, 415, 422): `ArchivFehler` mit Fehlercode (`fehlercode` der Problem Details) und
  Text des Archivs; das PDF bleibt, wo es ist.
- Weiterleitungen (3xx) folgt der Client nicht, damit das Token nie an eine andere Adresse geht: `ArchivFehler`.

Beide Ordner liegen im Ordner des PDFs, sofern `ordner` nichts anderes sagt.

Token (tech.md Regel 7): API-Token vom Scope `generator` aus einer Datei mit Rechten 600, Pfad aus
ARCHIV_TOKEN_DATEI, sonst ~/.config/urinet-archiv/token; nie das Token selbst aus einer Umgebungsvariable.
Adresse des Archivs: Parameter `url`, sonst ARCHIV_URL, sonst https://archiv.apps.urinet.ch. Nur `https://`; `http://`
nur für localhost, 127.0.0.1 und ::1 (Tests), damit das Token nicht im Klartext übers Netz geht.

Nur Python-Standardbibliothek, damit der Client ohne Installation in jedes Generator-Skript passt.

Aufruf von der Kommandozeile:
    python3 tools/archiv_client.py ausgabe/rechnung-2026-003.pdf metadaten.json
    python3 tools/archiv_client.py --nachsenden ausgabe
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import stat
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

STANDARD_URL = "https://archiv.apps.urinet.ch"
PFAD = "/api/v1/dokumente/generator"
AUSSTEHEND = "archiv-ausstehend"
KONFLIKT = "archiv-konflikt"
ZEITGRENZE = 120
LOKALE_HOSTS = ("localhost", "127.0.0.1", "::1")

ANGENOMMEN = "ANGENOMMEN"
VORHANDEN = "VORHANDEN"
AUSSTEHEND_ZUSTAND = "AUSSTEHEND"
KONFLIKT_ZUSTAND = "KONFLIKT"


class ArchivFehler(Exception):
    """Das Archiv hat die Anfrage abgelehnt (4xx ausser 409) oder der Client ist falsch eingerichtet."""

    def __init__(self, meldung: str, status: int | None = None, code: str | None = None,
                 felder: list[str] | None = None):
        super().__init__(meldung)
        self.status = status
        self.code = code
        self.felder = felder or []


@dataclass(frozen=True)
class Ergebnis:
    """`zustand`: ANGENOMMEN (202), VORHANDEN (200), AUSSTEHEND (Archiv nicht erreichbar) oder KONFLIKT (409)."""

    zustand: str
    id: str | None = None
    status: str | None = None
    pfad: Path | None = None
    meldung: str | None = None


class _NichtErreichbar(Exception):
    pass


def archivieren(pdf: str | os.PathLike, metadaten: Mapping[str, Any], *, url: str | None = None,
                ordner: str | os.PathLike | None = None) -> Ergebnis:
    """Sendet zuerst ausstehende PDFs aus `<ordner>/archiv-ausstehend/`, dann `pdf` mit `metadaten`."""
    pdf = Path(pdf)
    if not pdf.is_file():
        raise ArchivFehler(f"Das PDF {pdf} gibt es nicht.")
    basis = Path(ordner) if ordner is not None else pdf.resolve().parent
    adresse = _adresse(url)
    token = token_lesen()
    if not _nachsenden(adresse, token, basis):
        return _zurueckstellen(pdf, metadaten, basis, "Das Archiv ist nicht erreichbar.")
    try:
        return _behandeln(adresse, token, pdf, metadaten, basis)
    except _NichtErreichbar as grund:
        return _zurueckstellen(pdf, metadaten, basis, str(grund))


def nachsenden(ordner: str | os.PathLike, *, url: str | None = None) -> bool:
    """Sendet die ausstehenden PDFs aus `<ordner>/archiv-ausstehend/`; False, wenn das Archiv nicht erreichbar ist."""
    return _nachsenden(_adresse(url), token_lesen(), Path(ordner))


def token_lesen() -> str:
    pfad = Path(os.environ.get("ARCHIV_TOKEN_DATEI") or Path.home() / ".config" / "urinet-archiv" / "token")
    if not pfad.is_file():
        raise ArchivFehler(f"Keine Token-Datei unter {pfad}. Bitte das API-Token dort ablegen (Rechte 600).")
    if stat.S_IMODE(pfad.stat().st_mode) & 0o077:
        raise ArchivFehler(f"Die Token-Datei {pfad} ist für andere lesbar. Bitte mit «chmod 600 {pfad}» schützen.")
    token = pfad.read_text(encoding="utf-8").strip()
    if not token:
        raise ArchivFehler(f"Die Token-Datei {pfad} ist leer.")
    return token


def _adresse(url: str | None) -> str:
    adresse = (url or os.environ.get("ARCHIV_URL") or STANDARD_URL).rstrip("/")
    teile = urllib.parse.urlsplit(adresse)
    if not teile.hostname or not (teile.scheme == "https"
                                  or teile.scheme == "http" and teile.hostname in LOKALE_HOSTS):
        raise ArchivFehler(f"Die Adresse {adresse} ist nicht erlaubt. Bitte https:// verwenden "
                           "(http:// nur für localhost).")
    return adresse


class _KeineWeiterleitung(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *argumente, **benannt):
        return None


_OEFFNER = urllib.request.build_opener(_KeineWeiterleitung)


def _nachsenden(adresse: str, token: str, basis: Path) -> bool:
    ausstehend = basis / AUSSTEHEND
    if not ausstehend.is_dir():
        return True
    for pdf in sorted(ausstehend.glob("*.pdf")):
        begleit = pdf.with_suffix(".json")
        if not begleit.is_file():
            print(f"archiv_client: Zu {pdf} fehlen die Metadaten ({begleit.name}); bleibt liegen.", file=sys.stderr)
            continue
        try:
            metadaten = json.loads(begleit.read_text(encoding="utf-8"))
            ergebnis = _behandeln(adresse, token, pdf, metadaten, basis)
        except _NichtErreichbar:
            return False
        except (ArchivFehler, ValueError) as fehler:
            print(f"archiv_client: {pdf.name} bleibt ausstehend: {fehler}", file=sys.stderr)
            continue
        if ergebnis.zustand in (ANGENOMMEN, VORHANDEN):
            pdf.unlink(missing_ok=True)
            begleit.unlink(missing_ok=True)
        elif ergebnis.zustand == KONFLIKT_ZUSTAND:
            begleit.unlink(missing_ok=True)
    return True


def _behandeln(adresse: str, token: str, pdf: Path, metadaten: Mapping[str, Any], basis: Path) -> Ergebnis:
    status, antwort = _senden(adresse, token, pdf, metadaten)
    if status in (200, 201, 202):
        zustand = VORHANDEN if status == 200 else ANGENOMMEN
        return Ergebnis(zustand, id=antwort.get("id"), status=antwort.get("status"))
    meldung = _meldung(status, antwort)
    if status == 409:
        ziel = _verschieben(pdf, basis / KONFLIKT, metadaten)
        print(f"archiv_client: {meldung} Das PDF liegt jetzt unter {ziel}.", file=sys.stderr)
        return Ergebnis(KONFLIKT_ZUSTAND, pfad=ziel, meldung=meldung)
    raise ArchivFehler(meldung, status=status, code=antwort.get("fehlercode"), felder=antwort.get("felder"))


def _senden(adresse: str, token: str, pdf: Path, metadaten: Mapping[str, Any]) -> tuple[int, dict]:
    grenze = f"----archiv-client-{uuid.uuid4().hex}"
    dateiname = _dateiname(pdf.name)
    koerper = b"".join([
        f"--{grenze}\r\n".encode("ascii"),
        f'Content-Disposition: form-data; name="datei"; filename="{dateiname}"\r\n'.encode("utf-8"),
        b"Content-Type: application/pdf\r\n\r\n",
        pdf.read_bytes(),
        f"\r\n--{grenze}\r\n".encode("ascii"),
        b'Content-Disposition: form-data; name="metadaten"\r\n',
        b"Content-Type: text/plain; charset=UTF-8\r\n\r\n",
        json.dumps(metadaten, ensure_ascii=False).encode("utf-8"),
        f"\r\n--{grenze}--\r\n".encode("ascii"),
    ])
    anfrage = urllib.request.Request(adresse + PFAD, data=koerper, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": f"multipart/form-data; boundary={grenze}",
        "Accept": "application/json, application/problem+json",
    })
    try:
        with _OEFFNER.open(anfrage, timeout=ZEITGRENZE) as antwort:
            return antwort.status, _json(antwort.read())
    except urllib.error.HTTPError as fehler:
        inhalt = _json(fehler.read())
        if 300 <= fehler.code < 400:
            raise ArchivFehler(f"Das Archiv leitet weiter (HTTP {fehler.code}); der Client folgt keiner Weiterleitung. "
                               "Bitte die Adresse (ARCHIV_URL) prüfen.", status=fehler.code) from None
        if fehler.code >= 500:
            raise _NichtErreichbar(f"Das Archiv antwortet mit HTTP {fehler.code}.") from None
        return fehler.code, inhalt
    except (urllib.error.URLError, socket.timeout, ConnectionError, TimeoutError) as fehler:
        raise _NichtErreichbar(f"Das Archiv ist nicht erreichbar ({fehler}).") from None


def _dateiname(name: str) -> str:
    """Ersetzt Anführungszeichen und die Zeichen nach Req 3.10 (Cc, Cs, Zl, Zp, Cf ausser U+200C/U+200D) durch «_».

    Das Archiv speichert den Namen nach Req 11.12 und liesse einen ungültigen leer; so bleibt er erhalten.
    """
    return "".join("_" if z == '"' or (unicodedata.category(z) in {"Cc", "Cs", "Zl", "Zp", "Cf"}
                                         and z not in "\u200c\u200d") else z for z in name)


def _json(inhalt: bytes) -> dict:
    try:
        wert = json.loads(inhalt.decode("utf-8")) if inhalt else {}
    except ValueError:
        return {}
    return wert if isinstance(wert, dict) else {}


def _meldung(status: int, antwort: dict) -> str:
    teile = [f"HTTP {status}"]
    if antwort.get("fehlercode"):
        teile.append(antwort["fehlercode"])
    text = antwort.get("detail") or antwort.get("title") or ""
    if antwort.get("felder"):
        text = f"{text} Felder: {', '.join(antwort['felder'])}".strip()
    return f"Das Archiv lehnt das PDF ab ({', '.join(teile)}): {text}".rstrip(": ")


def _zurueckstellen(pdf: Path, metadaten: Mapping[str, Any], basis: Path, grund: str) -> Ergebnis:
    ziel = _freier_name(basis / AUSSTEHEND, pdf.name)
    shutil.copy2(pdf, ziel)
    ziel.with_suffix(".json").write_text(json.dumps(metadaten, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"archiv_client: {grund} Das PDF liegt unter {ziel} und wird beim nächsten Aufruf gesendet.",
          file=sys.stderr)
    return Ergebnis(AUSSTEHEND_ZUSTAND, pfad=ziel, meldung=grund)


def _verschieben(pdf: Path, ordner: Path, metadaten: Mapping[str, Any]) -> Path:
    ziel = _freier_name(ordner, pdf.name)
    shutil.move(str(pdf), ziel)
    ziel.with_suffix(".json").write_text(json.dumps(metadaten, ensure_ascii=False, indent=2), encoding="utf-8")
    return ziel


def _freier_name(ordner: Path, name: str) -> Path:
    ordner.mkdir(parents=True, exist_ok=True)
    ziel = ordner / name
    stamm, endung = Path(name).stem, Path(name).suffix or ".pdf"
    nummer = 1
    while ziel.exists() or ziel.with_suffix(".json").exists():
        ziel = ordner / f"{stamm}-{nummer}{endung}"
        nummer += 1
    return ziel


def main(argumente: list[str]) -> int:
    try:
        if len(argumente) == 2 and argumente[0] == "--nachsenden":
            return 0 if nachsenden(argumente[1]) else 3
        if len(argumente) != 2:
            print(__doc__, file=sys.stderr)
            return 2
        metadaten = json.loads(Path(argumente[1]).read_text(encoding="utf-8"))
        ergebnis = archivieren(argumente[0], metadaten)
    except ArchivFehler as fehler:
        print(f"archiv_client: {fehler}", file=sys.stderr)
        return 1
    except ValueError as fehler:
        print(f"archiv_client: Die Metadaten sind kein gültiges JSON ({fehler}).", file=sys.stderr)
        return 2
    print(json.dumps({"zustand": ergebnis.zustand, "id": ergebnis.id, "status": ergebnis.status,
                      "pfad": str(ergebnis.pfad) if ergebnis.pfad else None}, ensure_ascii=False))
    return {ANGENOMMEN: 0, VORHANDEN: 0, AUSSTEHEND_ZUSTAND: 3, KONFLIKT_ZUSTAND: 4}[ergebnis.zustand]


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
