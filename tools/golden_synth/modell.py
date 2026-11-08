"""Datenmodell: ein Beleg trägt seine Erwartung und die Anweisung zum Zeichnen."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Callable

KATEGORIEN = ("QUITTUNG", "LIEFERANTENRECHNUNG", "EIGENE_RECHNUNG_OFFERTE", "BEHOERDE_VERSICHERUNG", "HANDY_FOTO")


@dataclass(frozen=True)
class Firma:
    name: str
    strasse: str
    hausnr: str
    plz: str
    ort: str
    uid: str | None = None
    iban: str | None = None
    zusatz: tuple[str, ...] = ()

    @property
    def adresse(self) -> list[str]:
        return [self.name, *self.zusatz, f"{self.strasse} {self.hausnr}", f"{self.plz} {self.ort}"]

    def qr_adresse(self) -> dict:
        return {"name": self.name, "street": self.strasse, "house_num": self.hausnr,
                "pcode": self.plz, "city": self.ort, "country": "CH"}


@dataclass
class QrAngaben:
    konto: str
    empfaenger: Firma
    betrag: Decimal | None
    waehrung: str = "CHF"
    referenz: str | None = None
    zahler: Firma | None = None
    mitteilung: str = ""
    sprache: str = "de"

    @property
    def referenztyp(self) -> str:
        if self.referenz is None:
            return "NON"
        return "SCOR" if self.referenz.startswith("RF") else "QRR"


@dataclass
class FotoParameter:
    winkel: float
    schatten: bool = False
    ungleiches_licht: bool = True
    unschaerfe: float = 0.8
    zerknittert: bool = False
    drehung: int = 0
    format: str = "jpg"
    zweiter_beleg: Callable[[], bytes] | None = None


@dataclass
class Beleg:
    nr: int
    kategorie: str
    stichwort: str
    art: str
    partner: str | None
    datum: date | None
    betrag: Decimal | None
    waehrung: str
    belegnummer: str | None
    richtung: str | None
    faelligkeit: date | None
    randfaelle: list[str]
    erzeuge_pdf: Callable[[], bytes]
    qr: QrAngaben | None = None
    foto: FotoParameter | None = None
    teil: str | None = field(default=None)

    @property
    def endung(self) -> str:
        return self.foto.format if self.foto else "pdf"

    @property
    def datei(self) -> str:
        return f"belege/{self.nr:02d}_{self.kategorie.lower()}_{self.stichwort}.{self.endung}"
