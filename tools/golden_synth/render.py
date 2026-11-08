"""Zeichnen der Belege als PDF (reportlab). Alle Werte kommen fertig formatiert aus dem Katalog."""

from __future__ import annotations

import io
from dataclasses import dataclass, field
from pathlib import Path

from qrbill import QRBill
from reportlab.graphics import renderPDF
from reportlab.lib.pagesizes import A4, A6, landscape
from reportlab.lib.units import mm
from reportlab.lib.utils import simpleSplit
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from svglib.svglib import svg2rlg

from .modell import QrAngaben

_FONTS = Path(__file__).parent / "fonts"
pdfmetrics.registerFont(TTFont("Vera", "Vera.ttf"))
pdfmetrics.registerFont(TTFont("Vera-Bold", "VeraBd.ttf"))
pdfmetrics.registerFont(TTFont("Kalam", str(_FONTS / "Kalam-Regular.ttf")))

BREITE, HOEHE = A4
RAND = 20 * mm
QR_HOEHE = 106 * mm


def _canvas(groesse) -> tuple[canvas.Canvas, io.BytesIO]:
    puffer = io.BytesIO()
    # invariant: keine Zeitstempel/IDs im PDF, gleicher Seed ergibt gleiche Bytes
    c = canvas.Canvas(puffer, pagesize=groesse, invariant=1, pageCompression=1)
    c.setCreator("golden_synth")
    return c, puffer


# ---------------------------------------------------------------- Kassenbon

def bon(zeilen: list[tuple], breite_mm: float = 80, papier=(0.985, 0.98, 0.955), tinte=(0.18, 0.18, 0.2)) -> bytes:
    """Zeilentypen: ('c', t) zentriert, ('cb', t) gross fett, ('l', t), ('lr', l, r), ('lrb', l, r), ('-',), ('=',), (' ',)."""
    zeilenhoehe = 11
    breite = breite_mm * mm
    hoehe = 30 + sum(18 if z[0] == "cb" else zeilenhoehe for z in zeilen) + 30
    c, puffer = _canvas((breite, hoehe))
    c.setFillColorRGB(*papier)
    c.rect(0, 0, breite, hoehe, stroke=0, fill=1)
    c.setFillColorRGB(*tinte)
    links, rechts = 9, breite - 9
    y = hoehe - 30
    for z in zeilen:
        typ = z[0]
        if typ == "cb":
            groesse = min(13.0, (rechts - links) / (0.6 * len(z[1])))
            c.setFont("Courier-Bold", groesse)
            c.drawCentredString(breite / 2, y, z[1])
            y -= 18
            continue
        c.setFont("Courier-Bold" if typ == "lrb" else "Courier", 8.5)
        if typ == "c":
            c.drawCentredString(breite / 2, y, z[1])
        elif typ == "l":
            c.drawString(links, y, z[1])
        elif typ in ("lr", "lrb"):
            c.drawString(links, y, z[1])
            c.drawRightString(rechts, y, z[2])
        elif typ in ("-", "="):
            c.drawString(links, y, (typ * 60)[: int((rechts - links) / 5.1)])
        y -= zeilenhoehe
    c.showPage()
    c.save()
    return puffer.getvalue()


# ---------------------------------------------------------------- A4-Dokumente

@dataclass(frozen=True)
class Stil:
    schrift: str = "Helvetica"
    fett: str = "Helvetica-Bold"
    akzent: tuple[float, float, float] = (0.15, 0.3, 0.55)
    fenster_rechts: bool = True
    kopf_rechts: bool = False
    tabelle: str = "linien"  # linien | zebra | box
    logo: str = "kreis"  # kreis | balken | quadrat | dreieck | keins
    groesse: float = 9.5


STILE = {
    "A": Stil(),
    "B": Stil("Times-Roman", "Times-Bold", (0.45, 0.1, 0.1), True, True, "box", "balken", 10),
    "C": Stil("Vera", "Vera-Bold", (0.0, 0.45, 0.4), False, False, "zebra", "quadrat", 8.5),
    "D": Stil("Helvetica", "Helvetica-Bold", (0.3, 0.3, 0.3), True, False, "box", "dreieck", 9),
    "E": Stil("Courier", "Courier-Bold", (0.1, 0.1, 0.1), False, True, "linien", "keins", 9),
    "F": Stil("Vera", "Vera-Bold", (0.55, 0.3, 0.0), True, True, "zebra", "kreis", 9),
    "G": Stil("Times-Roman", "Times-Bold", (0.1, 0.2, 0.45), True, False, "linien", "keins", 10.5),
    "URINET": Stil("Helvetica", "Helvetica-Bold", (0.93, 0.69, 0.07), True, False, "linien", "balken", 9.5),
    "AMT": Stil("Helvetica", "Helvetica-Bold", (0.0, 0.0, 0.0), False, False, "linien", "keins", 10),
}


@dataclass
class Tabelle:
    kopf: list[str]
    zeilen: list[list[str]]
    breiten: list[float]
    rechts: list[bool]


@dataclass
class A4Dokument:
    absender_name: str
    absender_zeilen: list[str]
    empfaenger: list[str]
    ort_datum: str | None
    titel: str
    meta: list[tuple[str, str]] = field(default_factory=list)
    absaetze: list[str] = field(default_factory=list)
    tabelle: Tabelle | None = None
    summen: list[tuple[str, str, bool]] = field(default_factory=list)
    absaetze_nach: list[str] = field(default_factory=list)
    fuss: list[str] = field(default_factory=list)
    qr: QrAngaben | None = None
    umbruch_nach: int | None = None
    uebertrag: str | None = None
    seite: str = "Seite"


def qr_bill(q: QrAngaben) -> QRBill:
    return QRBill(
        account=q.konto,
        creditor=q.empfaenger.qr_adresse(),
        amount=None if q.betrag is None else f"{q.betrag:.2f}",
        currency=q.waehrung,
        debtor=q.zahler.qr_adresse() if q.zahler else None,
        reference_number=q.referenz,
        additional_information=q.mitteilung,
        language=q.sprache,
    )


def _qr_zeichnen(c: canvas.Canvas, q: QrAngaben) -> None:
    svg = io.StringIO()
    qr_bill(q).as_svg(svg)
    zeichnung = svg2rlg(io.BytesIO(svg.getvalue().encode()))
    faktor = BREITE / zeichnung.width
    zeichnung.scale(faktor, faktor)
    renderPDF.draw(zeichnung, c, 0, 0)


def _logo(c: canvas.Canvas, stil: Stil, x: float, y: float) -> float:
    c.setFillColorRGB(*stil.akzent)
    if stil.logo == "kreis":
        c.circle(x + 7, y + 4, 7, stroke=0, fill=1)
    elif stil.logo == "balken":
        c.rect(x, y - 3, 5, 16, stroke=0, fill=1)
    elif stil.logo == "quadrat":
        c.rect(x, y - 3, 14, 14, stroke=0, fill=1)
    elif stil.logo == "dreieck":
        p = c.beginPath()
        p.moveTo(x, y - 3)
        p.lineTo(x + 16, y - 3)
        p.lineTo(x + 8, y + 12)
        p.close()
        c.drawPath(p, stroke=0, fill=1)
    else:
        return 0
    return 22


def a4(dok: A4Dokument, stil: Stil) -> bytes:
    c, puffer = _canvas(A4)
    s = stil.groesse
    rechts = BREITE - RAND
    seiten = 2 if dok.umbruch_nach is not None else 1
    grenze = {"unten": 25 * mm if seiten > 1 or not dok.qr else QR_HOEHE + 12 * mm}

    def text(x, y, t, schrift=None, groesse=s, farbe=(0, 0, 0), ausrichtung="l"):
        c.setFont(schrift or stil.schrift, groesse)
        c.setFillColorRGB(*farbe)
        {"l": c.drawString, "r": c.drawRightString, "c": c.drawCentredString}[ausrichtung](x, y, t)

    def kopf():
        y = HOEHE - 18 * mm
        breite_name = pdfmetrics.stringWidth(dok.absender_name, stil.fett, 14)
        if stil.kopf_rechts:
            x_logo = rechts - breite_name - 22
            versatz = _logo(c, stil, x_logo, y)
            text(x_logo + versatz, y, dok.absender_name, stil.fett, 14, stil.akzent)
            for i, z in enumerate(dok.absender_zeilen):
                text(rechts, y - 14 - i * (s + 1), z, groesse=s - 1.5, ausrichtung="r")
        else:
            versatz = _logo(c, stil, RAND, y)
            text(RAND + versatz, y, dok.absender_name, stil.fett, 14, stil.akzent)
            for i, z in enumerate(dok.absender_zeilen):
                text(RAND, y - 14 - i * (s + 1), z, groesse=s - 1.5)

    def fuss(seite):
        y = (QR_HOEHE + 4 * mm) if (dok.qr and seite == seiten) else 12 * mm
        for i, z in enumerate(dok.fuss):
            text(BREITE / 2, y + (len(dok.fuss) - 1 - i) * (s - 0.5), z, groesse=s - 2.5, farbe=(0.35, 0.35, 0.35), ausrichtung="c")
        if seiten > 1:
            text(rechts, y + len(dok.fuss) * (s - 0.5) + 2, f"{dok.seite} {seite}/{seiten}", groesse=s - 2)

    def platz(y):
        if y < grenze["unten"]:
            raise ValueError(f"Platz reicht nicht auf A4: {dok.titel}")

    def absaetze(y, liste):
        for absatz in liste:
            for zeile in simpleSplit(absatz, stil.schrift, s, rechts - RAND):
                platz(y)
                text(RAND, y, zeile)
                y -= s + 3
            y -= 5
        return y

    def tabellenkopf(y, t: Tabelle):
        xs = _spalten(t)
        if stil.tabelle in ("zebra", "box"):
            c.setFillColorRGB(*_hell(stil.akzent))
            c.rect(RAND, y - 4, rechts - RAND, s + 6, stroke=0, fill=1)
        for (x0, x1), k, r in zip(xs, t.kopf, t.rechts):
            text(x1 - 3 if r else x0 + 3, y, k, stil.fett, s, ausrichtung="r" if r else "l")
        c.setStrokeColorRGB(*stil.akzent)
        c.setLineWidth(0.8)
        c.line(RAND, y - 5, rechts, y - 5)
        return y - s - 8

    def tabellenzeilen(y, t: Tabelle, zeilen):
        xs = _spalten(t)
        for n, zeile in enumerate(zeilen):
            umbrochen = [simpleSplit(w, stil.schrift, s, (x1 - x0) - 6) or [""] for w, (x0, x1) in zip(zeile, xs)]
            hoehe = max(len(u) for u in umbrochen) * (s + 2) + 3
            platz(y - hoehe)
            if stil.tabelle == "zebra" and n % 2 == 1:
                c.setFillColorRGB(0.95, 0.95, 0.95)
                c.rect(RAND, y - hoehe + s - 1, rechts - RAND, hoehe, stroke=0, fill=1)
            for teile, (x0, x1), r in zip(umbrochen, xs, t.rechts):
                for i, teil in enumerate(teile):
                    text(x1 - 3 if r else x0 + 3, y - i * (s + 2), teil, ausrichtung="r" if r else "l")
            if stil.tabelle in ("linien", "box"):
                c.setStrokeColorRGB(0.75, 0.75, 0.75)
                c.setLineWidth(0.4)
                c.line(RAND, y - hoehe + s - 1, rechts, y - hoehe + s - 1)
            y -= hoehe
        return y - 4

    kopf()
    # Adressfenster: rechts 118 mm, links 22 mm; 45 mm unter dem oberen Rand
    x_fenster = 118 * mm if stil.fenster_rechts else 22 * mm
    y = HOEHE - 45 * mm
    for i, z in enumerate(dok.empfaenger):
        text(x_fenster, y - i * (s + 2.5), z, groesse=s + 0.5)
    y = HOEHE - 82 * mm
    if dok.ort_datum:
        text(x_fenster if stil.fenster_rechts else RAND, y, dok.ort_datum)
        y -= 10 * mm
    else:
        y -= 4 * mm
    text(RAND, y, dok.titel, stil.fett, s + 4)
    y -= s + 12
    for label, wert in dok.meta:
        text(RAND, y, label)
        text(RAND + 45 * mm, y, wert)
        y -= s + 3
    y -= 4
    y = absaetze(y, dok.absaetze)

    if dok.tabelle:
        y = tabellenkopf(y, dok.tabelle)
        if dok.umbruch_nach is not None:
            y = tabellenzeilen(y, dok.tabelle, dok.tabelle.zeilen[: dok.umbruch_nach])
            if dok.uebertrag:
                text(rechts - 3, y - 2, dok.uebertrag, stil.fett, ausrichtung="r")
            fuss(1)
            c.showPage()
            if dok.qr:
                grenze["unten"] = QR_HOEHE + 12 * mm
            text(RAND, HOEHE - 18 * mm, dok.absender_name, stil.fett, 11, stil.akzent)
            text(RAND, HOEHE - 26 * mm, dok.titel, stil.schrift, s)
            y = HOEHE - 40 * mm
            if dok.uebertrag:
                text(rechts - 3, y, dok.uebertrag, stil.fett, ausrichtung="r")
                y -= s + 8
            y = tabellenkopf(y, dok.tabelle)
            y = tabellenzeilen(y, dok.tabelle, dok.tabelle.zeilen[dok.umbruch_nach:])
        else:
            y = tabellenzeilen(y, dok.tabelle, dok.tabelle.zeilen)

    if dok.summen:
        y -= 4
        for label, wert, fettgedruckt in dok.summen:
            platz(y)
            schrift = stil.fett if fettgedruckt else stil.schrift
            if fettgedruckt:
                c.setStrokeColorRGB(0, 0, 0)
                c.setLineWidth(0.6)
                c.line(rechts - 80 * mm, y + s + 1, rechts, y + s + 1)
            text(rechts - 80 * mm, y, label, schrift)
            text(rechts - 3, y, wert, schrift, ausrichtung="r")
            y -= s + 4
        y -= 6
    absaetze(y, dok.absaetze_nach)
    fuss(seiten)
    if dok.qr:
        _qr_zeichnen(c, dok.qr)
    c.showPage()
    c.save()
    return puffer.getvalue()


def _hell(farbe):
    return tuple(0.82 + 0.18 * a for a in farbe)


def _spalten(t: Tabelle) -> list[tuple[float, float]]:
    gesamt = BREITE - 2 * RAND
    x = RAND
    spalten = []
    for b in t.breiten:
        spalten.append((x, x + b * gesamt))
        x += b * gesamt
    return spalten


# ---------------------------------------------------------------- handschriftliche Quittung

@dataclass
class Handquittung:
    nummer: str | None
    betrag: str
    von: str
    fuer: str
    ort_datum: str
    unterschrift: str
    name: str
    neigungen: tuple[float, ...]


def handquittung(q: Handquittung) -> bytes:
    groesse = landscape(A6)
    c, puffer = _canvas(groesse)
    b, h = groesse
    c.setFillColorRGB(0.99, 0.99, 0.97)
    c.rect(0, 0, b, h, stroke=0, fill=1)
    c.setStrokeColorRGB(0.35, 0.45, 0.35)
    c.setLineWidth(1.2)
    c.rect(8 * mm, 8 * mm, b - 16 * mm, h - 16 * mm)
    c.setFillColorRGB(0.25, 0.35, 0.25)
    c.setFont("Helvetica-Bold", 16)
    c.drawString(14 * mm, h - 20 * mm, "QUITTUNG")
    c.setFont("Helvetica", 9)
    c.drawString(b - 60 * mm, h - 20 * mm, "Nr.")
    c.drawString(b - 60 * mm, h - 30 * mm, "Fr.")
    c.rect(b - 52 * mm, h - 33 * mm, 38 * mm, 9 * mm)
    felder = [("Empfangen von", 42), ("für", 56), ("Betrag dankend erhalten.", 0), ("Ort / Datum", 78), ("Unterschrift", 92)]
    for label, abstand in felder:
        if abstand:
            c.drawString(14 * mm, h - abstand * mm, label)
            c.setDash(1, 2)
            c.line(42 * mm, h - abstand * mm - 1, b - 14 * mm, h - abstand * mm - 1)
            c.setDash()
    c.drawString(14 * mm, h - 66 * mm, "Betrag dankend erhalten.")
    tinte = (0.08, 0.12, 0.42)

    def hand(x, y, t, groesse, neigung):
        c.saveState()
        c.translate(x, y)
        c.rotate(neigung)
        c.setFillColorRGB(*tinte)
        c.setFont("Kalam", groesse)
        c.drawString(0, 0, t)
        c.restoreState()

    n = q.neigungen
    if q.nummer:
        hand(b - 52 * mm, h - 20 * mm, q.nummer, 13, n[0])
    hand(b - 50 * mm, h - 31 * mm, q.betrag, 16, n[1])
    hand(44 * mm, h - 41 * mm, q.von, 14, n[2])
    hand(44 * mm, h - 55 * mm, q.fuer, 14, n[3])
    hand(44 * mm, h - 77 * mm, q.ort_datum, 14, n[4])
    hand(44 * mm, h - 91 * mm, q.unterschrift, 17, n[5])
    hand(90 * mm, h - 91 * mm, q.name, 12, n[0])
    c.showPage()
    c.save()
    return puffer.getvalue()
