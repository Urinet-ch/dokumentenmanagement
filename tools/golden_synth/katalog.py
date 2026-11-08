"""Katalog der 50 synthetischen Belege. Jede Funktion liefert Erwartung und Zeichnung aus denselben Werten."""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal

from . import pruefziffern as pz
from . import render
from .formate import betrag as fb
from .formate import d, datum as fd, rp, rund5
from .modell import Beleg, Firma, FotoParameter, QrAngaben
from .render import A4Dokument, Tabelle

# ---------------------------------------------------------------- Stammdaten (alles erfunden, nur PLZ/Orte echt)

_FIRMEN = {
    "urinet": ("UriNet Aschwanden", "Sonnhaldenweg", "4", "6462", "Seedorf UR"),
    "dorfladen": ("Dorfladen Zwyssig", "Chilchweg", "7", "6463", "Bürglen UR"),
    "tankstelle": ("Tankstelle Reussbrugg", "Reussbruggweg", "2", "6460", "Altdorf UR"),
    "gasthaus": ("Gasthaus Krummer Ahorn", "Ahornweg", "1", "6475", "Bristen"),
    "parking": ("Parking Schächenmatt", "Schächenmattweg", "11", "6460", "Altdorf UR"),
    "oev": ("Verkehrsverbund Urnersee", "Hafenweg", "3", "6454", "Flüelen"),
    "paket": ("PaketPunkt Urnerland", "Gotthardweg", "19", "6472", "Erstfeld"),
    "baumarkt": ("Bau- und Hobbymarkt Brunnital", "Brunnitalweg", "15", "6465", "Unterschächen"),
    "kiosk": ("Kiosk am Seeplatz", "Seeplatz", "1", "6454", "Flüelen"),
    "pixelkiste": ("Pixelkiste Versand GmbH", "Lagerhofstrasse", "88", "8404", "Winterthur"),
    "bergnetz": ("Bergnetz Hosting AG", "Pilatusblickweg", "18", "6010", "Kriens"),
    "wolkenwerk": ("Wolkenwerk Software AG", "Rigiblickstrasse", "5", "6300", "Zug"),
    "talwind": ("Talwind Telecom AG", "Aareuferweg", "40", "3011", "Bern"),
    "druckerei": ("Druckerei Zgraggen & Arnold AG", "Druckereiweg", "9", "6460", "Altdorf UR"),
    "papeterie": ("Papeterie Bannwald AG", "Mythenblick", "12", "6430", "Schwyz"),
    "calcolo": ("Calcolo Leventina SA", "Via dei Castagni", "4", "6500", "Bellinzona"),
    "imhof": ("Lea Imhof", "Lindenmatt", "2", "6370", "Stans"),
    "alpwerk": ("Alpwerk Photographie Sàrl", "Chemin des Vergers-Hauts", "11", "1950", "Sion"),
    "kabelwerk": ("Kabelwerk Montagen GmbH", "Industriering Nord", "6", "6467", "Schattdorf"),
    "zurfluh": ("Holzbau Zurfluh GmbH", "Sagenmattweg", "3", "6468", "Attinghausen"),
    "mattli": ("Physiotherapie Mattli", "Rosenhaldenweg", "8", "6460", "Altdorf UR"),
    "musikfreunde": ("Verein Musikfreunde Isenthal", "Dorfplatz", "1", "6461", "Isenthal"),
    "tresch": ("Bäckerei-Café Tresch", "Mühlegasse", "5", "6484", "Wassen UR"),
    "planzer": ("Gärtnerei Planzer", "Gartenweg", "21", "6463", "Bürglen UR"),
    "treuhand": ("Treuhand Arnold & Partner AG", "Rathausgässli", "2", "6460", "Altdorf UR"),
    "herger": ("Schreinerei Herger AG", "Werkhofweg", "4", "6452", "Sisikon"),
    "gitschenblick": ("Hotel Gitschenblick", "Seeuferweg", "10", "6466", "Bauen"),
    "dubey": ("Atelier Céramique Dubey", "Chemin des Potiers", "7", "1700", "Fribourg"),
    "muheim": ("Velo Muheim GmbH", "Kreuzmattweg", "12", "6472", "Erstfeld"),
    "imholz": ("Garage Imholz AG", "Werkstrasse", "30", "6472", "Erstfeld"),
    "ausgleichskasse": ("Ausgleichskasse Urnerland", "Amtshausplatz", "2", "6460", "Altdorf UR"),
    "steuer": ("Steuerverwaltung Gotthardtal", "Amtsgasse", "6", "6460", "Altdorf UR"),
    "foehnwind": ("Föhnwind Versicherungen AG", "Versicherungsplatz", "1", "6003", "Luzern"),
    "mythenstein": ("Mythenstein Taggeld AG", "Hochstuckliweg", "3", "6440", "Brunnen"),
    "hagelwolke": ("Hagelwolke Assekuranz AG", "Sturmweg", "2", "8400", "Winterthur"),
    "handelsregister": ("Handelsregisteramt Gotthardtal", "Amtsgasse", "6", "6460", "Altdorf UR"),
    "leventina": ("Cassa di compensazione Leventina", "Via del Ticino", "18", "6780", "Airolo"),
    "backstube": ("Backstube Chlausen", "Klausenweg", "3", "6465", "Unterschächen"),
    "drogerie": ("Drogerie Alpenkraut", "Kräuterweg", "5", "6472", "Erstfeld"),
    "tankhof": ("Tankhof Wassen", "Tankhofweg", "1", "6484", "Wassen UR"),
    "seestern": ("Kaffee & Kiosk Seestern", "Quaiweg", "2", "6454", "Flüelen"),
    "baumann": ("Elektro Baumann & Gnos GmbH", "Stromweg", "8", "6467", "Schattdorf"),
    "putzwerk": ("Putzwerk Reuss GmbH", "Uferpfad", "14", "6468", "Attinghausen"),
    "hafenmole": ("Parking Hafenmole", "Hafenmole", "1", "6454", "Flüelen"),
}
_ZUSATZ = {"imhof": ("Texte & Lektorat",)}
_OHNE_UID = {"urinet", "imhof"}


@dataclass
class Kontext:
    seed: int
    _firmen: dict = field(default_factory=dict)

    def rng(self, nr: int) -> random.Random:
        return random.Random(f"golden-{self.seed}-beleg-{nr}")

    def firma(self, schluessel: str) -> Firma:
        if schluessel not in self._firmen:
            r = random.Random(f"golden-{self.seed}-firma-{schluessel}")
            name, strasse, nr, plz, ort = _FIRMEN[schluessel]
            self._firmen[schluessel] = Firma(name, strasse, nr, plz, ort,
                                             uid=None if schluessel in _OHNE_UID else pz.uid(r),
                                             iban=pz.iban(r), zusatz=_ZUSATZ.get(schluessel, ()))
        return self._firmen[schluessel]

    def qr_iban(self, schluessel: str) -> str:
        return pz.iban(random.Random(f"golden-{self.seed}-qriban-{schluessel}"), qr=True)


def _tag(rng: random.Random, von: date = date(2026, 1, 5), tage: int = 290) -> date:
    return von + timedelta(days=rng.randrange(tage))


def _inkl(total: Decimal, satz: str) -> Decimal:
    return rp(total * d(satz) / (100 + d(satz)))


def _menge(m: Decimal) -> str:
    return str(int(m)) if m == m.to_integral() else f"{m:.2f}".rstrip("0").rstrip(".")


# ---------------------------------------------------------------- Rechnungen und Offerten (A4)

LABELS = {
    "de": dict(rechnung="Rechnung", nr="Rechnungs-Nr.", datum="Rechnungsdatum", pos="Beschreibung", menge="Menge",
               preis="Preis", betrag="Betrag", netto="Total exkl. MWST", mwst="MWST", total="Total",
               zwischen="Zwischentotal", rabatt="Rabatt", rundung="Rundungsdifferenz", anzahlung="Abzüglich Anzahlung",
               rest="Restbetrag", innert="Zahlbar innert 30 Tagen netto.", bis="Zahlbar bis {}.", seite="Seite",
               uebertrag="Übertrag", kunde="Kunden-Nr."),
    "fr": dict(rechnung="Facture", nr="Facture n°", datum="Date", pos="Désignation", menge="Qté", preis="Prix unit.",
               betrag="Montant", netto="Total HT", mwst="TVA", total="Total", zwischen="Sous-total", rabatt="Rabais",
               rundung="Arrondi", anzahlung="Acompte versé", rest="Solde", innert="Payable dans les 30 jours.",
               bis="Payable jusqu'au {}.", seite="Page", uebertrag="Report", kunde="N° client"),
    "it": dict(rechnung="Fattura", nr="Fattura n.", datum="Data", pos="Descrizione", menge="Qtà", preis="Prezzo",
               betrag="Importo", netto="Totale netto", mwst="IVA", total="Totale", zwischen="Subtotale",
               rabatt="Sconto", rundung="Arrotondamento", anzahlung="Acconto", rest="Saldo",
               innert="Pagabile entro 30 giorni.", bis="Pagabile entro il {}.", seite="Pagina", uebertrag="Riporto",
               kunde="N. cliente"),
}


@dataclass
class Rechnung:
    absender: str
    empfaenger: str
    stil: str
    nummer: str
    datum: date
    datum_stil: str
    positionen: list[tuple[str, Decimal, Decimal]]
    mwst: str | None = "8.1"
    sprache: str = "de"
    titel: str | None = None
    nr_label: str | None = None
    tausender: str = "'"
    waehrung: str = "CHF"
    rabatt: str | None = None
    runden: bool = False
    anzahlung: Decimal | None = None
    faellig: str | None = "innert"
    faellig_tage: int = 30
    qr: str | None = "normal"
    absaetze: list[str] = field(default_factory=list)
    absaetze_nach: list[str] = field(default_factory=list)
    meta: list[tuple[str, str]] = field(default_factory=list)
    umbruch_nach: int | None = None
    ort_datum: bool = False


@dataclass
class Gerechnet:
    dokument: A4Dokument
    betrag: Decimal
    faelligkeit: date | None
    qr: QrAngaben | None


def rechne(r: Rechnung, k: Kontext, rng: random.Random) -> Gerechnet:
    L = LABELS[r.sprache]
    ab, an = k.firma(r.absender), k.firma(r.empfaenger)
    f = lambda x: fb(x, r.tausender)  # noqa: E731
    zeilen = [[t, _menge(m), f(p), f(rp(m * p))] for t, m, p in r.positionen]
    netto = sum((rp(m * p) for _, m, p in r.positionen), d(0))
    summen: list[tuple[str, str, bool]] = []
    if r.rabatt:
        abzug = rp(netto * d(r.rabatt) / 100)
        summen += [(L["zwischen"], f(netto), False), (f"{L['rabatt']} {r.rabatt}%", f(-abzug), False)]
        netto -= abzug
    total = netto
    if r.mwst:
        steuer = rp(netto * d(r.mwst) / 100)
        summen += [(L["netto"], f(netto), False), (f"{L['mwst']} {r.mwst}%", f(steuer), False)]
        total = netto + steuer
    if r.runden:
        gerundet = rund5(total)
        summen.append((L["rundung"], f(gerundet - total), False))
        total = gerundet
    summen.append((f"{L['total']} {r.waehrung}", f(total), True))
    zahlbar = total
    if r.anzahlung:
        zahlbar = total - r.anzahlung
        summen += [(L["anzahlung"], f(-r.anzahlung), False), (f"{L['rest']} {r.waehrung}", f(zahlbar), True)]

    datum_text = fd(r.datum, r.datum_stil)
    nach = list(r.absaetze_nach)
    faelligkeit = None
    if r.faellig == "innert":
        faelligkeit = r.datum + timedelta(days=30)
        nach.insert(0, L["innert"])
    elif r.faellig == "datum":
        faelligkeit = r.datum + timedelta(days=r.faellig_tage)
        nach.insert(0, L["bis"].format(fd(faelligkeit, r.datum_stil)))

    qr = None
    if r.qr:
        qr_iban = r.qr == "qriban"
        qr = QrAngaben(
            konto=k.qr_iban(r.absender) if qr_iban else ab.iban,
            empfaenger=ab, betrag=None if r.qr == "ohne_betrag" else zahlbar, waehrung=r.waehrung,
            referenz=pz.qr_referenz(rng) if qr_iban else None, zahler=an,
            mitteilung="" if qr_iban else f"{L['rechnung']} {r.nummer}", sprache=r.sprache)

    kontakt = [f"{ab.strasse} {ab.hausnr}", f"{ab.plz} {ab.ort}"]
    if ab.uid and r.mwst:
        kontakt.append(f"{L['mwst']} {ab.uid}")
    fuss = [f"{ab.name} · {ab.strasse} {ab.hausnr} · {ab.plz} {ab.ort}"]
    if not r.qr or r.qr == "ohne_betrag":
        fuss.append(f"IBAN {pz.iban_formatiert(ab.iban)}")
    dok = A4Dokument(
        absender_name=ab.name, absender_zeilen=[*ab.zusatz, *kontakt], empfaenger=an.adresse,
        ort_datum=f"{ab.ort.removesuffix(' UR')}, {datum_text}" if r.ort_datum else None,
        titel=r.titel or f"{L['rechnung']} {r.nummer}",
        meta=([] if r.ort_datum else [(L["datum"], datum_text)]) + [(r.nr_label or L["nr"], r.nummer)] + r.meta,
        absaetze=r.absaetze,
        tabelle=Tabelle([L["pos"], L["menge"], L["preis"], L["betrag"]], zeilen, [0.52, 0.12, 0.18, 0.18],
                        [False, True, True, True]),
        summen=summen, absaetze_nach=nach, fuss=fuss, qr=qr, umbruch_nach=r.umbruch_nach,
        uebertrag=None if r.umbruch_nach is None else
        f"{L['uebertrag']} {r.waehrung} {f(sum((rp(m * p) for _, m, p in r.positionen[: r.umbruch_nach]), d(0)))}",
        seite=L["seite"])
    return Gerechnet(dok, zahlbar, faelligkeit, qr)


def _beleg_aus(nr, kategorie, stichwort, art, partner, datum, g: Gerechnet, waehrung, belegnummer, richtung,
               randfaelle, stil) -> Beleg:
    return Beleg(nr, kategorie, stichwort, art, partner, datum, g.betrag, waehrung, belegnummer, richtung,
                 g.faelligkeit, randfaelle, lambda: render.a4(g.dokument, render.STILE[stil]), qr=g.qr)


# ---------------------------------------------------------------- Kategorie 1: Quittungen

def _kopf(f: Firma, uid: bool = True) -> list[tuple]:
    zeilen = [("cb", f.name.upper() if len(f.name) <= 22 else f.name), ("c", f"{f.strasse} {f.hausnr}"), ("c", f"{f.plz} {f.ort}")]
    if uid and f.uid:
        zeilen.append(("c", f"MWST-Nr. {f.uid} MWST"))
    return zeilen + [("-",)]


def _uhr(rng: random.Random) -> str:
    return f"{rng.randint(7, 19):02d}:{rng.randint(0, 59):02d}"


def q01(k: Kontext) -> Beleg:
    rng, f = k.rng(1), k.firma("dorfladen")
    tag = _tag(rng)
    lebensmittel = [("Vollmilch 1 l", d("1.75")), ("Ruchbrot 500 g", d("3.40")), ("Bergkäse 250 g", d("6.95")),
                    ("Äpfel Gala 1 kg", d("4.20"))]
    andere = [("Abwaschmittel", d("3.95")), ("Batterien AA 4 Stk", d("7.90"))]
    s26 = sum(p for _, p in lebensmittel)
    s81 = sum(p for _, p in andere)
    total = s26 + s81
    bon = rng.randint(1000, 9999)
    zeilen = _kopf(f) + [("lr", f"{fd(tag, 'ddmmyyyy')} {_uhr(rng)}", f"Bon {bon}"), ("l", "Kasse 2  Bed. 14"), ("-",)]
    zeilen += [("lr", f"{t} A", fb(p, "")) for t, p in lebensmittel] + [("lr", f"{t} B", fb(p, "")) for t, p in andere]
    zeilen += [("=",), ("lrb", "TOTAL CHF", fb(total, "")), ("lr", "Debitkarte", fb(total, "")), ("-",),
               ("l", "MWST   Satz    Brutto    MWST"),
               ("l", f"A      2.6%   {fb(s26, ''):>7}  {fb(_inkl(s26, '2.6'), ''):>6}"),
               ("l", f"B      8.1%   {fb(s81, ''):>7}  {fb(_inkl(s81, '8.1'), ''):>6}"), (" ",),
               ("c", "Danke für Ihren Einkauf!")]
    return Beleg(1, "QUITTUNG", "detailhandel", "QUITTUNG", f.name, tag, total, "CHF", str(bon), "AUSGABE", None,
                 ["datum_ddmmyyyy", "mwst_2_6", "mwst_8_1", "mwst_gemischt"], lambda: render.bon(zeilen))


def q02(k: Kontext) -> Beleg:
    rng, f = k.rng(2), k.firma("tankstelle")
    tag = _tag(rng)
    liter, preis = d(f"{rng.uniform(28, 52):.2f}"), d("1.889")
    total = rp(liter * preis)
    nummer = f"{rng.randint(10000, 99999):06d}"
    zeilen = _kopf(f) + [("lr", f"{fd(tag, 'dmyy')} {_uhr(rng)}", f"Beleg {nummer}"), ("l", "Säule 3"), ("-",),
                         ("l", "Bleifrei 95"), ("lr", f"  {liter} l x {preis}", fb(total, "")), ("=",),
                         ("lrb", "TOTAL CHF", fb(total, "")), ("lr", "Kreditkarte", fb(total, "")),
                         ("l", f"inkl. MWST 8.1%  {fb(_inkl(total, '8.1'), '')}"), (" ",), ("c", "Gute Fahrt!")]
    return Beleg(2, "QUITTUNG", "tankstelle", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
                 ["datum_dmyy", "mwst_8_1"], lambda: render.bon(zeilen))


def q03(k: Kontext) -> Beleg:
    rng, f = k.rng(3), k.firma("gasthaus")
    tag = _tag(rng)
    zimmer, essen, getraenke = d("95.00"), d("38.50"), d(f"{rng.choice([9, 12, 14])}.00")
    zwischen = zimmer + essen + getraenke
    total = (zwischen / 5).to_integral(rounding="ROUND_CEILING") * 5
    if total == zwischen:
        total += 5
    trinkgeld = total - zwischen
    nummer = str(rng.randint(2000, 2999))
    zeilen = _kopf(f) + [("l", f"{fd(tag, 'lang')}"), ("lr", "Zimmer 4", f"Beleg {nummer}"), ("-",),
                         ("lr", "Übernachtung EZ inkl. Fr.  C", fb(zimmer, "")),
                         ("lr", "Älplermagronen          A", fb(essen, "")),
                         ("lr", "Getränke                A", fb(getraenke, "")), ("-",),
                         ("lr", "Zwischentotal", fb(zwischen, "")), ("lr", "Trinkgeld", fb(trinkgeld, "")), ("=",),
                         ("lrb", "TOTAL CHF", fb(total, "")), ("lr", "Mastercard", fb(total, "")), ("-",),
                         ("l", f"A MWST 8.1% von {fb(essen + getraenke, '')}: {fb(_inkl(essen + getraenke, '8.1'), '')}"),
                         ("l", f"C MWST 3.8% von {fb(zimmer, '')}: {fb(_inkl(zimmer, '3.8'), '')}"), (" ",),
                         ("c", "Auf Wiedersehen im Maderanertal")]
    return Beleg(3, "QUITTUNG", "restaurant", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
                 ["datum_ausgeschrieben", "mehrere_betraege", "mwst_3_8", "mwst_8_1", "mwst_gemischt", "trinkgeld"],
                 lambda: render.bon(zeilen))


def q04(k: Kontext) -> Beleg:
    rng, f = k.rng(4), k.firma("parking")
    tag = _tag(rng)
    stunden = rng.randint(2, 5)
    total = d("1.50") * stunden + d("1.50")
    ticket = str(rng.randint(10000, 99999))
    zeilen = _kopf(f) + [("l", f"Datum      {fd(tag, 'iso')}"), ("l", "Einfahrt   08:12"),
                         ("l", f"Ausfahrt   {8 + stunden:02d}:47"), ("l", f"Ticket-Nr. {ticket}"), ("-",),
                         ("lrb", "Parkgebühr CHF", fb(total, "")), ("lr", "TWINT", fb(total, "")),
                         ("l", f"inkl. 8.1% MWST {fb(_inkl(total, '8.1'), '')}")]
    return Beleg(4, "QUITTUNG", "parkhaus", "QUITTUNG", f.name, tag, total, "CHF", ticket, "AUSGABE", None,
                 ["datum_iso", "mwst_8_1"], lambda: render.bon(zeilen))


def q05(k: Kontext) -> Beleg:
    rng, f = k.rng(5), k.firma("oev")
    tag = _tag(rng)
    total = d(rng.choice(["13.40", "15.80", "21.60"]))
    nummer = f"{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}-{rng.randint(10, 99)}"
    zeilen = _kopf(f) + [("c", "BILLETT / QUITTUNG"), ("-",), ("l", "Altdorf UR  ->  Luzern"),
                         ("l", "2. Klasse, Halbtax, einfach"), ("l", f"Gültig am {fd(tag, 'ddmmyyyy')}"),
                         ("l", f"Billett-Nr. {nummer}"), ("-",), ("lrb", "Preis CHF", fb(total, "")),
                         ("l", f"inkl. MWST 8.1%: {fb(_inkl(total, '8.1'), '')}"), ("l", "Bezahlt: Debitkarte")]
    return Beleg(5, "QUITTUNG", "oev_billett", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
                 ["datum_ddmmyyyy", "mwst_8_1"], lambda: render.bon(zeilen))


def q06(k: Kontext) -> Beleg:
    rng, f = k.rng(6), k.firma("paket")
    tag = _tag(rng)
    posten = [("Paket Priority bis 2 kg", d("10.50")), ("Einschreiben Brief", d("6.50")),
              ("Briefmarken 10 x 1.20", d("12.00"))]
    total = sum(p for _, p in posten)
    nummer = str(rng.randint(400000, 699999))
    zeilen = _kopf(f) + [("lr", f"{fd(tag, 'ddmmyyyy')} {_uhr(rng)}", f"Quittung {nummer}"), ("-",)]
    zeilen += [("lr", t, fb(p, "")) for t, p in posten]
    zeilen += [("=",), ("lrb", "TOTAL CHF", fb(total, "")), ("lr", "Bar", fb(total, "")),
               ("l", "Preise inkl. MWST 8.1% wo anwendbar")]
    return Beleg(6, "QUITTUNG", "post_paket", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
                 ["datum_ddmmyyyy", "mwst_8_1"], lambda: render.bon(zeilen))


def q07(k: Kontext) -> Beleg:
    rng, f = k.rng(7), k.firma("baumarkt")
    tag = _tag(rng)
    posten = [("Spanplattenschrauben 4x40", d("8.95")), ("Dübel 6 mm 50 Stk", d("4.60")), ("Silikon transparent", d("9.80")),
              ("Flachpinsel 50 mm", d("6.45")), ("Abdeckfolie 4x5 m", d("5.90")), ("Malerklebeband", d("4.35"))]
    while True:
        zwischen = sum(p for _, p in posten)
        rabatt = rp(zwischen * d("0.05"))
        total = zwischen - rabatt
        if rund5(total) != total:
            break
        posten[0] = (posten[0][0], posten[0][1] + d("0.10"))
    gerundet = rund5(total)
    bar = d("50.00") if gerundet < 50 else d("100.00")
    nummer = str(rng.randint(100000, 999999))
    zeilen = _kopf(f) + [("lr", fd(tag, "dmyy"), f"Bon-Nr. {nummer}"), ("-",)]
    zeilen += [("lr", t, fb(p, "")) for t, p in posten]
    zeilen += [("-",), ("lr", "Zwischentotal", fb(zwischen, "")), ("lr", "Treuerabatt 5%", fb(-rabatt, "")),
               ("lr", "Total", fb(total, "")), ("lrb", "TOTAL GERUNDET CHF", fb(gerundet, "")),
               ("lr", "Bar", fb(bar, "")), ("lr", "Rückgeld", fb(bar - gerundet, "")),
               ("l", f"inkl. MWST 8.1% {fb(_inkl(gerundet, '8.1'), '')}")]
    return Beleg(7, "QUITTUNG", "baumarkt", "QUITTUNG", f.name, tag, gerundet, "CHF", nummer, "AUSGABE", None,
                 ["datum_dmyy", "mehrere_betraege", "rundung_5rp", "mwst_8_1"], lambda: render.bon(zeilen))


def q08(k: Kontext) -> Beleg:
    rng, f = k.rng(8), k.firma("kiosk")
    tag = _tag(rng)
    zeitung, wasser, kaugummi = d("4.20"), d("2.80"), d("1.90")
    total = zeitung + wasser + kaugummi
    zeilen = [("cb", "KIOSK AM SEEPLATZ"), ("c", f"{f.plz} {f.ort}"), ("-",), ("l", f"{fd(tag, 'ddmmyyyy')} {_uhr(rng)}"),
              ("-",), ("lr", "Tageszeitung     2.6%", fb(zeitung, "")), ("lr", "Mineral 5 dl     8.1%", fb(wasser, "")),
              ("lr", "Kaugummi         8.1%", fb(kaugummi, "")), ("=",), ("lrb", "TOTAL CHF", fb(total, "")),
              ("lr", "Bar", "10.00"), ("lr", "Rückgeld", fb(d("10.00") - total, ""))]
    return Beleg(8, "QUITTUNG", "kiosk_bar", "QUITTUNG", f.name, tag, total, "CHF", None, "AUSGABE", None,
                 ["datum_ddmmyyyy", "ohne_belegnummer", "mehrere_betraege", "mwst_2_6", "mwst_8_1", "mwst_gemischt"],
                 lambda: render.bon(zeilen))


def q09(k: Kontext) -> Beleg:
    rng = k.rng(9)
    f, uri = k.firma("pixelkiste"), k.firma("urinet")
    tag = _tag(rng)
    posten = [("Monitor 27 Zoll IPS", d("1"), d("1149.00")), ("Monitorarm Doppelgelenk", d("1"), d("189.00")),
              ("HDMI-Kabel 2 m", d("2"), d("14.90")), ("Versand", d("1"), d("9.90"))]
    total = sum(rp(m * p) for _, m, p in posten)
    bestellung = f"1000-{rng.randint(10000, 99999)}"
    dok = A4Dokument(
        absender_name=f.name, absender_zeilen=[f"{f.strasse} {f.hausnr}", f"{f.plz} {f.ort}", f"MWST {f.uid}"],
        empfaenger=uri.adresse, ort_datum=None, titel="Zahlungsbestätigung / Quittung",
        meta=[("Bestellung", bestellung), ("Bestelldatum", fd(tag, "ddmmyyyy")), ("Zahlungsart", "Kreditkarte")],
        tabelle=Tabelle(["Artikel", "Anzahl", "Preis", "Total"],
                        [[t, _menge(m), fb(p, " "), fb(rp(m * p), " ")] for t, m, p in posten],
                        [0.52, 0.12, 0.18, 0.18], [False, True, True, True]),
        summen=[("Total CHF", fb(total, " "), True), (f"darin MWST 8.1%", fb(_inkl(total, "8.1"), " "), False)],
        absaetze_nach=["Der Betrag wurde Ihrer Kreditkarte belastet. Vielen Dank für Ihre Bestellung."],
        fuss=[f"{f.name} · Online-Shop · {f.plz} {f.ort}"])
    return Beleg(9, "QUITTUNG", "onlineshop", "QUITTUNG", f.name, tag, total, "CHF", bestellung, "AUSGABE", None,
                 ["datum_ddmmyyyy", "tausender_leerzeichen", "mwst_8_1"], lambda: render.a4(dok, render.STILE["C"]))


def q10(k: Kontext) -> Beleg:
    rng = k.rng(10)
    total = d("260.00")
    q = render.Handquittung(nummer=None, betrag=fb(total, "'", strich=True), von="UriNet Aschwanden",
                            fuer="2 Ster Buchenholz, geliefert", ort_datum="Bristen",
                            unterschrift="H. Zurfluh", name="Heidi Zurfluh",
                            neigungen=tuple(round(rng.uniform(-3, 3), 2) for _ in range(6)))
    return Beleg(10, "QUITTUNG", "handschrift", "QUITTUNG", "Heidi Zurfluh", None, total, "CHF", None, "AUSGABE", None,
                 ["handschrift", "ohne_datum", "ohne_belegnummer"], lambda: render.handquittung(q))


# ---------------------------------------------------------------- Kategorie 2: Lieferantenrechnungen

def l11(k: Kontext) -> Beleg:
    rng = k.rng(11)
    tag = _tag(rng)
    r = Rechnung("bergnetz", "urinet", "A", "2026-123", tag, "ddmmyyyy",
                 [("Webhosting Business, 12 Monate", d(12), d("14.90")), ("Domain kunde-beispiel.ch, 1 Jahr", d(1), d("18.00")),
                  ("SSL-Zertifikat", d(1), d("0.00"))], meta=[("Kunden-Nr.", f"K-{rng.randint(1000, 9999)}")])
    g = rechne(r, k, rng)
    return _beleg_aus(11, "LIEFERANTENRECHNUNG", "hosting", "LIEFERANTENBELEG", k.firma("bergnetz").name, tag, g, "CHF",
                      "2026-123", "AUSGABE", ["nummer_wie_eigene", "faelligkeit_innert_30_tagen", "mwst_8_1", "qr",
                                              "datum_ddmmyyyy"], "A")


def l12(k: Kontext) -> Beleg:
    rng = k.rng(12)
    tag = _tag(rng)
    r = Rechnung("wolkenwerk", "urinet", "B", "1001", tag, "ddmmyyyy",
                 [("Abo «Wolkenwerk Office», 12 Monate", d(12), d("99.00")), ("Zusatzmodul Zeiterfassung", d(1), d("240.00"))],
                 faellig="datum", faellig_tage=20, qr="qriban")
    g = rechne(r, k, rng)
    return _beleg_aus(12, "LIEFERANTENRECHNUNG", "software_abo", "LIEFERANTENBELEG", k.firma("wolkenwerk").name, tag, g,
                      "CHF", "1001", "AUSGABE", ["gleiche_nummer_1001", "qr_iban", "qr", "tausender_apostroph",
                                                 "faelligkeit_datum", "mwst_8_1", "datum_ddmmyyyy"], "B")


def l13(k: Kontext) -> Beleg:
    rng = k.rng(13)
    tag = _tag(rng)
    nummer = f"TT-{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}"
    r = Rechnung("talwind", "urinet", "C", nummer, tag, "lang",
                 [("Mobile Abo «Talwind Business»", d(1), d("49.00")), ("Internet & Festnetz Büro", d(1), d("69.00")),
                  ("Verbindungen Ausland", d(1), d(f"{rng.randint(3, 19)}.{rng.randint(10, 95)}"))],
                 faellig="datum", faellig_tage=25, qr="qriban",
                 meta=[("Periode", "laufender Monat")])
    g = rechne(r, k, rng)
    return _beleg_aus(13, "LIEFERANTENRECHNUNG", "telekom", "LIEFERANTENBELEG", k.firma("talwind").name, tag, g, "CHF",
                      nummer, "AUSGABE", ["qr_iban", "qr", "datum_ausgeschrieben", "faelligkeit_datum", "mwst_8_1"], "C")


def l14(k: Kontext) -> Beleg:
    rng = k.rng(14)
    tag = _tag(rng)
    r = Rechnung("druckerei", "urinet", "D", "1001", tag, "ddmmyyyy",
                 [("Visitenkarten 4/4-farbig, 500 Stk", d(1), d("89.00")), ("Flyer A5, 2'000 Stk", d(1), d("412.00")),
                  ("Gestaltung und Satz (Std.)", d(3), d("95.00"))],
                 anzahlung=d("300.00"), faellig="datum", faellig_tage=30)
    g = rechne(r, k, rng)
    return _beleg_aus(14, "LIEFERANTENRECHNUNG", "druckerei", "LIEFERANTENBELEG", k.firma("druckerei").name, tag, g, "CHF",
                      "1001", "AUSGABE", ["gleiche_nummer_1001", "mehrere_betraege", "anzahlung", "qr",
                                          "faelligkeit_datum", "mwst_8_1", "datum_ddmmyyyy"], "D")


def l15(k: Kontext) -> Beleg:
    rng = k.rng(15)
    tag = _tag(rng)
    nummer = f"RE-{rng.randint(10000, 99999)}"
    r = Rechnung("papeterie", "urinet", "E", nummer, tag, "iso",
                 [("Kopierpapier A4 80 g, 5x500 Bl.", d(2), d("32.50")), ("Ordner breit, blau", d(10), d("3.90")),
                  ("Toner schwarz", d(1), d("89.00")), ("Notizblöcke A5", d(5), d("2.40"))], qr="ohne_betrag")
    g = rechne(r, k, rng)
    return _beleg_aus(15, "LIEFERANTENRECHNUNG", "buerobedarf", "LIEFERANTENBELEG", k.firma("papeterie").name, tag, g,
                      "CHF", nummer, "AUSGABE", ["qr_ohne_betrag", "qr", "datum_iso", "faelligkeit_innert_30_tagen",
                                                 "mwst_8_1"], "E")


def l16(k: Kontext) -> Beleg:
    rng = k.rng(16)
    tag = _tag(rng)
    nummer = f"2026/{rng.randint(100, 999):04d}"
    r = Rechnung("calcolo", "urinet", "F", nummer, tag, "it",
                 [("Notebook 14\" 32 GB / 1 TB", d(1), d("1849.00")), ("Docking station USB-C", d(1), d("229.00")),
                  ("Installazione e configurazione", d(1), d("120.00"))],
                 sprache="it", tausender=" ", faellig="datum", faellig_tage=30)
    g = rechne(r, k, rng)
    return _beleg_aus(16, "LIEFERANTENRECHNUNG", "hardware", "LIEFERANTENBELEG", k.firma("calcolo").name, tag, g, "CHF",
                      nummer, "AUSGABE", ["sprache_it", "datum_italienisch", "tausender_leerzeichen", "faelligkeit_datum",
                                          "qr", "mwst_8_1"], "F")


def l17(k: Kontext) -> Beleg:
    rng = k.rng(17)
    tag = _tag(rng)
    nummer = f"R-26-{rng.randint(1, 30):02d}"
    r = Rechnung("imhof", "urinet", "G", nummer, tag, "ddmmyyyy",
                 [("Texte Webseite Kunde (Std.)", d("6.5"), d("110.00")), ("Lektorat Broschüre (Std.)", d("2"), d("95.00")),
                  ("Spesen Bahn", d(1), d("18.40"))], mwst=None,
                 absaetze_nach=["Ich bin nicht MWST-pflichtig."])
    g = rechne(r, k, rng)
    return _beleg_aus(17, "LIEFERANTENRECHNUNG", "freelancer", "LIEFERANTENBELEG", "Lea Imhof", tag, g, "CHF", nummer,
                      "AUSGABE", ["ohne_mwst", "faelligkeit_innert_30_tagen", "qr", "datum_ddmmyyyy"], "G")


def l18(k: Kontext) -> Beleg:
    rng = k.rng(18)
    tag = _tag(rng)
    nummer = f"F-2026-{rng.randint(100, 199)}"
    r = Rechnung("alpwerk", "urinet", "A", nummer, tag, "fr",
                 [("Shooting photo, 1 journée", d(1), d("980.00")), ("Retouche d'images", d(20), d("12.00")),
                  ("Frais de déplacement", d(1), d("85.00"))],
                 sprache="fr", waehrung="EUR", faellig="datum", faellig_tage=30, ort_datum=True)
    g = rechne(r, k, rng)
    return _beleg_aus(18, "LIEFERANTENRECHNUNG", "eur_franzoesisch", "LIEFERANTENBELEG", k.firma("alpwerk").name, tag, g,
                      "EUR", nummer, "AUSGABE", ["waehrung_eur", "sprache_fr", "datum_franzoesisch", "faelligkeit_datum",
                                                 "qr", "mwst_8_1"], "A")


def l19(k: Kontext) -> Beleg:
    rng = k.rng(19)
    tag = _tag(rng)
    nummer = f"GS-2026-{rng.randint(10, 99):04d}"
    r = Rechnung("wolkenwerk", "urinet", "B", nummer, tag, "ddmmyyyy",
                 [("Gutschrift Zusatzmodul Zeiterfassung, anteilig", d(1), d("-120.00"))],
                 titel=f"Gutschrift {nummer}", nr_label="Gutschrift-Nr.", faellig=None, qr=None,
                 absaetze_nach=["Der Betrag wird mit der nächsten Rechnung verrechnet."])
    g = rechne(r, k, rng)
    return _beleg_aus(19, "LIEFERANTENRECHNUNG", "gutschrift", "LIEFERANTENBELEG", k.firma("wolkenwerk").name, tag, g,
                      "CHF", nummer, "AUSGABE", ["gutschrift", "negativer_betrag", "mwst_8_1", "datum_ddmmyyyy"], "B")


def l20(k: Kontext) -> Beleg:
    rng = k.rng(20)
    tag = _tag(rng)
    nummer = f"KM-2026-{rng.randint(100, 999):04d}"
    material = [("Netzwerkkabel Cat. 6A (m)", 180, "2.40"), ("Datendose 2x RJ45", 12, "38.50"),
                ("Patchpanel 24 Port", 1, "145.00"), ("Kabelkanal 40x60 (m)", 24, "9.80"), ("Patchkabel 1 m", 24, "4.90"),
                ("Patchkabel 3 m", 8, "7.50"), ("Switch 24 Port PoE", 1, "489.00"), ("Rack 19\" 12 HE", 1, "398.00"),
                ("Steckleiste 8-fach", 1, "64.00"), ("Kabelbinder (Pack)", 2, "6.90"), ("Befestigungsmaterial", 1, "28.50"),
                ("WLAN Access Point", 3, "219.00"), ("Montageplatte AP", 3, "18.00"), ("Brandschott", 2, "42.00"),
                ("Beschriftung Dosen", 12, "2.50"), ("Messprotokoll je Dose", 12, "8.00")]
    positionen = [(t, d(m), d(p) + d(rng.choice([0, 0, "0.50", "1.00"]))) for t, m, p in material]
    positionen += [("Montage Elektromonteur (Std.)", d(14), d("98.00")), ("Montage Lehrling (Std.)", d(10), d("62.00")),
                   ("Inbetriebnahme Netzwerk (Std.)", d(3), d("125.00")), ("Fahrzeugpauschale", d(3), d("45.00")),
                   ("Kleinmaterial", d(1), d("38.00")), ("Entsorgung", d(1), d("25.00"))]
    r = Rechnung("kabelwerk", "urinet", "A", nummer, tag, "ddmmyyyy", positionen, umbruch_nach=15,
                 faellig="innert", absaetze=["Netzwerkinstallation Büro gemäss Auftrag."])
    g = rechne(r, k, rng)
    return _beleg_aus(20, "LIEFERANTENRECHNUNG", "zweiseitig", "LIEFERANTENBELEG", k.firma("kabelwerk").name, tag, g, "CHF",
                      nummer, "AUSGABE", ["zweiseitig", "total_auf_seite_2", "qr", "faelligkeit_innert_30_tagen",
                                          "mwst_8_1", "tausender_apostroph", "datum_ddmmyyyy"], "A")


# ---------------------------------------------------------------- Kategorie 3: eigene Rechnungen und Offerten

_NICHT_MWST = "UriNet Aschwanden ist nicht MWST-pflichtig; es wird keine MWST erhoben."


def _eigene_rechnung(k: Kontext, nr: int, kunde: str, stichwort: str, nummer: str, datum_stil: str, positionen,
                     randfaelle: list[str], **mehr) -> Beleg:
    rng = k.rng(nr)
    tag = _tag(rng)
    r = Rechnung("urinet", kunde, "URINET", nummer, tag, datum_stil, positionen, mwst=None, ort_datum=True,
                 absaetze_nach=[_NICHT_MWST], **mehr)
    g = rechne(r, k, rng)
    return _beleg_aus(nr, "EIGENE_RECHNUNG_OFFERTE", stichwort, "AUSGANGSRECHNUNG", k.firma(kunde).name, tag, g, "CHF",
                      nummer, "EINNAHME", ["qr", f"datum_{_STIL_RANDFALL[datum_stil]}", *randfaelle], "URINET")


_STIL_RANDFALL = {"ddmmyyyy": "ddmmyyyy", "dmyy": "dmyy", "lang": "ausgeschrieben", "iso": "iso",
                  "fr": "franzoesisch", "it": "italienisch"}


def e21(k):
    return _eigene_rechnung(k, 21, "zurfluh", "rechnung_wartung", "2026-014", "ddmmyyyy",
                            [("Wartung Webseite, Quartal", d(1), d("270.00")), ("Inhaltsänderungen (Std.)", d("1.5"), d("95.00"))],
                            ["faelligkeit_innert_30_tagen"])


def e22(k):
    return _eigene_rechnung(k, 22, "mattli", "rechnung_hosting", "2026-021", "ddmmyyyy",
                            [("Hosting Webseite, 12 Monate", d(12), d("19.00")), ("Domain physio-beispiel.ch", d(1), d("25.00"))],
                            ["faelligkeit_datum"], faellig="datum", faellig_tage=20)


def e23(k):
    return _eigene_rechnung(k, 23, "musikfreunde", "rechnung_anzahlung", "2026-027", "ddmmyyyy",
                            [("Neue Webseite gemäss Offerte", d(1), d("3200.00")), ("Fotobearbeitung (Std.)", d(4), d("100.00"))],
                            ["mehrere_betraege", "anzahlung", "tausender_apostroph", "faelligkeit_innert_30_tagen"],
                            anzahlung=d("1000.00"))


def e24(k):
    return _eigene_rechnung(k, 24, "tresch", "rechnung_rundung", "2026-033", "lang",
                            [("Anpassung Online-Bestellformular (Std.)", d("2.6"), d("87.50")),
                             ("Fahrspesen (km)", d(23), d("0.72"))],
                            ["rundung_5rp", "faelligkeit_innert_30_tagen"], runden=True)


def e25(k):
    return _eigene_rechnung(k, 25, "planzer", "rechnung_fotos", "2026-038", "dmyy",
                            [("Produktfotos Saisonpflanzen (Std.)", d(3), d("95.00")), ("Bildbearbeitung", d(1), d("120.00"))],
                            ["faelligkeit_innert_30_tagen"])


def e26(k):
    return _eigene_rechnung(k, 26, "treuhand", "rechnung_support", "2026-041", "ddmmyyyy",
                            [("IT-Support Büro (Std.)", d("5.25"), d("110.00")), ("Lizenz Passwortmanager, 5 Benutzer", d(5), d("36.00")),
                             ("Einrichtung Backup", d(1), d("280.00"))],
                            ["faelligkeit_datum", "tausender_apostroph"], faellig="datum", faellig_tage=30)


def _offerte(k: Kontext, nr: int, kunde: str, stichwort: str, nummer: str, datum_stil: str, positionen,
             randfaelle: list[str], sprache: str = "de", rabatt: str | None = None, zusatz: list[str] = ()) -> Beleg:
    rng = k.rng(nr)
    tag = _tag(rng)
    gueltig = tag + timedelta(days=30)
    fr = sprache == "fr"
    r = Rechnung("urinet", kunde, "URINET", nummer, tag, datum_stil, positionen, mwst=None, sprache=sprache,
                 titel=f"{'Offre' if fr else 'Offerte'} {nummer}", nr_label="N° d'offre" if fr else "Offert-Nr.",
                 rabatt=rabatt, faellig=None, qr=None, ort_datum=True,
                 absaetze=["Merci de votre demande. Voici notre offre :" if fr else "Vielen Dank für Ihre Anfrage. Gerne offeriere ich Ihnen:"],
                 absaetze_nach=[*zusatz,
                                f"Cette offre est valable jusqu'au {fd(gueltig, datum_stil)}." if fr else
                                f"Diese Offerte ist gültig bis {fd(gueltig, datum_stil)}.",
                                "UriNet Aschwanden n'est pas assujetti à la TVA." if fr else _NICHT_MWST])
    g = rechne(r, k, rng)
    return _beleg_aus(nr, "EIGENE_RECHNUNG_OFFERTE", stichwort, "OFFERTE", k.firma(kunde).name, tag, g, "CHF", nummer,
                      "EINNAHME", ["offerte_gueltig_bis", f"datum_{_STIL_RANDFALL[datum_stil]}", *randfaelle], "URINET")


def e27(k):
    return _offerte(k, 27, "herger", "offerte_webseite", "O-2026-009", "ddmmyyyy",
                    [("Konzept und Struktur (Std.)", d(8), d("95.00")), ("Umsetzung Webseite (Std.)", d(24), d("95.00"))],
                    ["tausender_apostroph"])


def e28(k):
    return _offerte(k, 28, "gitschenblick", "offerte_rabatt", "O-2026-012", "ddmmyyyy",
                    [("Neue Webseite mit Buchungsanfrage", d(1), d("4200.00"))],
                    ["mehrere_betraege", "rabatt", "monatspauschale_ausserhalb_total", "tausender_apostroph"], rabatt="10",
                    zusatz=["Zusätzlich: Hosting und Wartung CHF 35.00 pro Monat (nicht im Total enthalten)."])


def e29(k):
    return _offerte(k, 29, "dubey", "offerte_franzoesisch", "O-2026-015", "fr",
                    [("Boutique en ligne, mise en place", d(1), d("2600.00")), ("Formation (heures)", d(2), d("95.00"))],
                    ["sprache_fr"], sprache="fr")


def e30(k):
    return _offerte(k, 30, "muheim", "offerte_shop", "O-2026-018", "iso",
                    [("Online-Shop Velozubehör", d(1), d("5400.00")), ("Produktdaten-Import (Std.)", d(6), d("95.00"))],
                    ["tausender_apostroph"])


# ---------------------------------------------------------------- Kategorie 4: Behörden und Versicherungen

def _brief(k: Kontext, absender: str, stil: str, datum_text: str, titel: str, **felder) -> A4Dokument:
    ab, uri = k.firma(absender), k.firma("urinet")
    return A4Dokument(absender_name=ab.name, absender_zeilen=[f"{ab.strasse} {ab.hausnr}", f"{ab.plz} {ab.ort}"],
                      empfaenger=uri.adresse, ort_datum=f"{ab.ort.removesuffix(' UR')}, {datum_text}", titel=titel,
                      fuss=[f"{ab.name} · {ab.strasse} {ab.hausnr} · {ab.plz} {ab.ort}"], **felder)


def _qr_amt(k: Kontext, schluessel: str, betrag: Decimal | None, rng, qrr: bool = True, mitteilung: str = "") -> QrAngaben:
    return QrAngaben(konto=k.qr_iban(schluessel) if qrr else k.firma(schluessel).iban, empfaenger=k.firma(schluessel),
                     betrag=betrag, referenz=pz.qr_referenz(rng) if qrr else None, zahler=k.firma("urinet"),
                     mitteilung=mitteilung)


def b31(k: Kontext) -> Beleg:
    rng = k.rng(31)
    tag = _tag(rng)
    lohnsumme = d(rng.choice(["18000", "21000", "24500"]))
    posten = [("AHV/IV/EO", "10.60"), ("ALV", "2.20"), ("FAK", "1.80"), ("Verwaltungskosten", "0.35")]
    zeilen, total = [], d(0)
    for text, satz in posten:
        b = rp(lohnsumme * d(satz) / 100)
        total += b
        zeilen.append([text, f"{satz}%", fb(lohnsumme), fb(b)])
    nummer = f"AK-2026-{rng.randint(1, 3)}-{rng.randint(10000, 99999)}"
    faellig = tag + timedelta(days=30)
    qr = _qr_amt(k, "ausgleichskasse", total, rng)
    dok = _brief(k, "ausgleichskasse", "AMT", fd(tag, "ddmmyyyy"), "Akontorechnung persönliche und paritätische Beiträge",
                 meta=[("Rechnungs-Nr.", nummer), ("Abrechnungsperiode", "Quartal")],
                 tabelle=Tabelle(["Beitrag", "Satz", "Lohnsumme", "Betrag CHF"], zeilen, [0.4, 0.15, 0.22, 0.23],
                                 [False, True, True, True]),
                 summen=[("Total Akontobeitrag CHF", fb(total), True)],
                 absaetze_nach=[f"Zahlbar bis {fd(faellig, 'ddmmyyyy')}. Bei verspäteter Zahlung werden Verzugszinsen erhoben."],
                 qr=qr)
    return Beleg(31, "BEHOERDE_VERSICHERUNG", "ahv_akonto", "BEHOERDE", k.firma("ausgleichskasse").name, tag, total, "CHF",
                 nummer, "AUSGABE", faellig, ["mehrere_betraege", "tausender_apostroph", "faelligkeit_datum", "qr",
                                              "qr_iban", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["AMT"]), qr=qr)


def b32(k: Kontext) -> Beleg:
    rng = k.rng(32)
    tag = _tag(rng, date(2026, 2, 1), 120)
    total = d(rng.choice(["3480.00", "2915.00", "4120.00"]))
    nummer = f"2026-{rng.randint(10000, 99999)}-01"
    faellig = date(2026, 10, 31)
    qr = _qr_amt(k, "steuer", total, rng)
    dok = _brief(k, "steuer", "AMT", fd(tag, "ddmmyyyy"), "Provisorische Steuerrechnung 2026",
                 meta=[("Referenz-Nr.", nummer), ("Steuerart", "Staats- und Gemeindesteuern")],
                 absaetze=["Gestützt auf die letzte Veranlagung stellen wir Ihnen die provisorische Steuer für das "
                           "laufende Steuerjahr in Rechnung. Die definitive Abrechnung erfolgt nach der Veranlagung."],
                 summen=[("Provisorischer Steuerbetrag CHF", fb(total, " "), True)],
                 absaetze_nach=[f"Zahlbar bis {fd(faellig, 'ddmmyyyy')}. Teilzahlungen sind möglich."], qr=qr)
    return Beleg(32, "BEHOERDE_VERSICHERUNG", "steuer_provisorisch", "BEHOERDE", k.firma("steuer").name, tag, total, "CHF",
                 nummer, "AUSGABE", faellig, ["tausender_leerzeichen", "faelligkeit_datum", "qr", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["AMT"]), qr=qr)


def b33(k: Kontext) -> Beleg:
    rng = k.rng(33)
    tag = _tag(rng)
    nummer = f"V-2025-{rng.randint(100000, 999999)}"
    zeilen = [["Steuerbares Einkommen", fb(d(rng.randint(70, 95) * 1000)), "satzbestimmend gleich"],
              ["Steuerbares Vermögen", fb(d(rng.randint(40, 160) * 1000)), "satzbestimmend gleich"],
              ["Abzüge Berufsauslagen", fb(d("3600")), "gemäss Deklaration"]]
    dok = _brief(k, "steuer", "AMT", fd(tag, "lang"), "Veranlagungsverfügung Staats- und Gemeindesteuern 2025",
                 meta=[("Verfügungs-Nr.", nummer)],
                 absaetze=["Die Veranlagung erfolgt gemäss Ihrer Steuererklärung mit folgenden Faktoren:"],
                 tabelle=Tabelle(["Faktor", "CHF", "Bemerkung"], zeilen, [0.45, 0.2, 0.35], [False, True, False]),
                 absaetze_nach=["Die definitive Steuerrechnung wird Ihnen separat zugestellt.",
                                "Rechtsmittelbelehrung: Gegen diese Verfügung kann innert 30 Tagen schriftlich Einsprache erhoben werden."])
    return Beleg(33, "BEHOERDE_VERSICHERUNG", "veranlagung", "BEHOERDE", k.firma("steuer").name, tag, None, "CHF", nummer,
                 None, None, ["ohne_betrag", "datum_ausgeschrieben", "tausender_apostroph"],
                 lambda: render.a4(dok, render.STILE["AMT"]))


def b34(k: Kontext) -> Beleg:
    rng = k.rng(34)
    tag = _tag(rng)
    lohnsumme = d(rng.choice(["72000", "84000", "96000"]))
    bu, nbu = rp(lohnsumme * d("0.0018")), rp(lohnsumme * d("0.0124"))
    total = bu + nbu
    nummer = f"PR-{rng.randint(1000000, 9999999)}"
    faellig = tag + timedelta(days=30)
    qr = _qr_amt(k, "foehnwind", total, rng, qrr=False, mitteilung=f"Prämienrechnung {nummer}")
    dok = _brief(k, "foehnwind", "B", fd(tag, "ddmmyyyy"), "Prämienrechnung Unfallversicherung UVG",
                 meta=[("Rechnungs-Nr.", nummer), ("Police-Nr.", f"UVG-{rng.randint(100000, 999999)}"),
                       ("Versicherungsjahr", "laufendes Jahr")],
                 tabelle=Tabelle(["Deckung", "Lohnsumme", "Satz", "Prämie CHF"],
                                 [["Berufsunfall (BU)", fb(lohnsumme), "0.180%", fb(bu)],
                                  ["Nichtberufsunfall (NBU)", fb(lohnsumme), "1.240%", fb(nbu)]],
                                 [0.4, 0.22, 0.15, 0.23], [False, True, True, True]),
                 summen=[("Total Prämie CHF", fb(total), True)],
                 absaetze_nach=[f"Zahlbar bis {fd(faellig, 'ddmmyyyy')}."], qr=qr)
    return Beleg(34, "BEHOERDE_VERSICHERUNG", "uvg_praemie", "VERSICHERUNG", k.firma("foehnwind").name, tag, total, "CHF",
                 nummer, "AUSGABE", faellig, ["mehrere_betraege", "faelligkeit_datum", "qr", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["B"]), qr=qr)


def b35(k: Kontext) -> Beleg:
    rng = k.rng(35)
    tag = _tag(rng)
    praemie = d("864.00")
    nummer = f"KT-{rng.randint(100, 999)}.{rng.randint(100, 999)}"
    dok = _brief(k, "mythenstein", "C", fd(tag, "ddmmyyyy"), "Police Kollektiv-Krankentaggeldversicherung",
                 meta=[("Police-Nr.", nummer), ("Vertragsbeginn", f"01.01.{tag.year + 1}"), ("Vertragsdauer", "3 Jahre")],
                 tabelle=Tabelle(["Leistung", "Umfang"],
                                 [["Taggeld", "80% des versicherten Lohnes"], ["Wartefrist", "30 Tage"],
                                  ["Leistungsdauer", "730 Tage abzüglich Wartefrist"],
                                  ["Versicherter Personenkreis", "alle Mitarbeitenden"]],
                                 [0.45, 0.55], [False, False]),
                 summen=[("Jahresprämie CHF", fb(praemie), True)],
                 absaetze_nach=["Die Prämienrechnung folgt separat. Die Allgemeinen Versicherungsbedingungen (AVB) "
                                "Ausgabe 2026 sind Bestandteil dieser Police."])
    return Beleg(35, "BEHOERDE_VERSICHERUNG", "ktg_police", "VERSICHERUNG", k.firma("mythenstein").name, tag, praemie,
                 "CHF", nummer, "AUSGABE", None, ["police", "praemie_pro_jahr", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["C"]))


def b36(k: Kontext) -> Beleg:
    rng = k.rng(36)
    tag = _tag(rng)
    netto = d("392.65")
    stempel = rp(netto * d("0.05"))
    total = netto + stempel
    nummer = f"BH-{rng.randint(1000000, 9999999)}"
    dok = _brief(k, "hagelwolke", "F", fd(tag, "ddmmyyyy"), "Police Betriebshaftpflichtversicherung",
                 meta=[("Police-Nr.", nummer), ("Laufzeit", f"01.01.{tag.year + 1} – 31.12.{tag.year + 3}")],
                 tabelle=Tabelle(["Deckung", "Versicherungssumme"],
                                 [["Personen- und Sachschäden", "CHF 5'000'000"], ["Vermögensschäden", "CHF 100'000"],
                                  ["Selbstbehalt", "CHF 500 je Schadenfall"]], [0.55, 0.45], [False, True]),
                 summen=[("Nettoprämie pro Jahr", fb(netto), False), ("Eidg. Stempelabgabe 5%", fb(stempel), False),
                         ("Jahresprämie CHF", fb(total), True)])
    return Beleg(36, "BEHOERDE_VERSICHERUNG", "haftpflicht_police", "VERSICHERUNG", k.firma("hagelwolke").name, tag, total,
                 "CHF", nummer, "AUSGABE", None, ["police", "praemie_pro_jahr", "mehrere_betraege", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["F"]))


def b37(k: Kontext) -> Beleg:
    rng = k.rng(37)
    tag = _tag(rng)
    offen, gebuehr = d(f"{rng.randint(900, 1400)}.{rng.choice(['20', '45', '80'])}"), d("30.00")
    total = offen + gebuehr
    nummer = f"M-2026-{rng.randint(10000, 99999)}"
    faellig = tag + timedelta(days=10)
    qr = _qr_amt(k, "foehnwind", total, rng, qrr=False, mitteilung=f"Mahnung {nummer}")
    dok = _brief(k, "foehnwind", "B", fd(tag, "ddmmyyyy"), "2. Mahnung",
                 meta=[("Mahnung Nr.", nummer), ("Betrifft", f"Prämienrechnung PR-{rng.randint(1000000, 9999999)}")],
                 absaetze=["Trotz unserer Zahlungserinnerung ist der folgende Betrag noch offen. Wir bitten Sie, "
                           "den Ausstand umgehend zu begleichen."],
                 summen=[("Offene Prämie", fb(offen), False), ("Mahngebühr", fb(gebuehr), False),
                         ("Total offen CHF", fb(total), True)],
                 absaetze_nach=[f"Zahlbar bis {fd(faellig, 'ddmmyyyy')}. Ohne Zahlung ruht der Versicherungsschutz."],
                 qr=qr)
    return Beleg(37, "BEHOERDE_VERSICHERUNG", "mahnung", "VERSICHERUNG", k.firma("foehnwind").name, tag, total, "CHF",
                 nummer, "AUSGABE", faellig, ["mahnung", "mehrere_betraege", "tausender_apostroph", "faelligkeit_datum",
                                              "qr", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["B"]), qr=qr)


def b38(k: Kontext) -> Beleg:
    rng = k.rng(38)
    tag = _tag(rng)
    dok = _brief(k, "handelsregister", "AMT", fd(tag, "ddmmyyyy"), "Eintragung im Handelsregister",
                 absaetze=["Sehr geehrte Damen und Herren",
                           "Wir bestätigen Ihnen, dass die von Ihnen angemeldete Änderung der Geschäftsadresse im "
                           "Handelsregister eingetragen wurde. Die Publikation im Amtsblatt erfolgt in den nächsten Tagen.",
                           "Für Fragen stehen wir Ihnen gerne zur Verfügung.", "Freundliche Grüsse",
                           "Handelsregisteramt Gotthardtal"])
    return Beleg(38, "BEHOERDE_VERSICHERUNG", "brief_ohne_betrag", "BEHOERDE", k.firma("handelsregister").name, tag, None,
                 "CHF", None, None, None, ["ohne_betrag", "ohne_belegnummer", "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["AMT"]))


def b39(k: Kontext) -> Beleg:
    rng = k.rng(39)
    tag = _tag(rng, date(2026, 10, 1), 40)
    frist = date(2027, 1, 30)
    nummer = f"LD-2026-{rng.randint(10000, 99999)}"
    dok = _brief(k, "ausgleichskasse", "AMT", fd(tag, "ddmmyyyy"), "Aufforderung zur Lohndeklaration 2026",
                 meta=[("Referenz", nummer)],
                 absaetze=["Bitte deklarieren Sie die im Jahr 2026 ausbezahlten Löhne aller Mitarbeitenden.",
                           f"Wir bitten Sie, die Lohndeklaration bis spätestens {fd(frist, 'lang')} einzureichen. "
                           "Bei verspäteter Einreichung wird die Lohnsumme ermessensweise festgelegt.",
                           "Die Lohndeklaration können Sie online über das Arbeitgeberportal erfassen."])
    return Beleg(39, "BEHOERDE_VERSICHERUNG", "lohndeklaration", "BEHOERDE", k.firma("ausgleichskasse").name, tag, None,
                 "CHF", nummer, None, frist, ["ohne_betrag", "frist_als_faelligkeit", "faelligkeit_datum",
                                              "datum_ddmmyyyy"],
                 lambda: render.a4(dok, render.STILE["AMT"]))


def b40(k: Kontext) -> Beleg:
    rng = k.rng(40)
    tag = _tag(rng)
    total = d(f"{rng.randint(150, 480)}.{rng.choice(['40', '65', '90'])}")
    nummer = f"RB-{rng.randint(100000, 999999)}"
    uri = k.firma("urinet")
    ab = k.firma("leventina")
    dok = A4Dokument(absender_name=ab.name, absender_zeilen=[f"{ab.strasse} {ab.hausnr}", f"{ab.plz} {ab.ort}"],
                     empfaenger=uri.adresse, ort_datum=f"{ab.ort}, {fd(tag, 'it')}", titel="Rimborso contributi 2025",
                     meta=[("Riferimento", nummer)],
                     absaetze=["Gentili signore e signori,",
                               "in base al conteggio finale dei contributi 2025 risulta un saldo a vostro favore."],
                     summen=[("Contributi fatturati", fb(total + 1200), False), ("Contributi dovuti", fb(d(1200)), False),
                             ("Importo del rimborso CHF", fb(total), True)],
                     absaetze_nach=[f"L'importo verrà versato sul vostro conto IBAN {pz.iban_formatiert(uri.iban)} "
                                    "entro 30 giorni.", "Distinti saluti"],
                     fuss=[f"{ab.name} · {ab.strasse} {ab.hausnr} · {ab.plz} {ab.ort}"], seite="Pagina")
    return Beleg(40, "BEHOERDE_VERSICHERUNG", "rueckerstattung", "BEHOERDE", ab.name, tag, total, "CHF", nummer,
                 "EINNAHME", None, ["einnahme", "sprache_it", "datum_italienisch", "mehrere_betraege",
                                    "tausender_apostroph"], lambda: render.a4(dok, render.STILE["G"]))


# ---------------------------------------------------------------- Kategorie 5: Handy-Fotos

def _als_foto(b: Beleg, nr: int, stichwort: str, p: FotoParameter, zusatz: list[str]) -> Beleg:
    b.nr, b.kategorie, b.stichwort, b.foto = nr, "HANDY_FOTO", stichwort, p
    b.randfaelle = [*b.randfaelle, "foto", *zusatz]
    return b


def _bon_einfach(k: Kontext, nr: int, schluessel: str, posten, datum_stil: str, nummer_label: str | None, zahlung: str,
                 satz: str = "8.1", runden: bool = False, rabatt: str | None = None):
    rng, f = k.rng(nr), k.firma(schluessel)
    tag = _tag(rng)
    zwischen = sum(p for _, p in posten)
    total = zwischen - (rp(zwischen * d(rabatt) / 100) if rabatt else 0)
    zeilen = _kopf(f)
    nummer = str(rng.randint(10000, 99999)) if nummer_label else None
    zeilen.append(("lr", f"{fd(tag, datum_stil)} {_uhr(rng)}", f"{nummer_label} {nummer}" if nummer else ""))
    zeilen += [("-",)] + [("lr", t, fb(p, "")) for t, p in posten] + [("-",)]
    endbetrag = total
    if rabatt:
        zeilen += [("lr", "Zwischentotal", fb(zwischen, "")), ("lr", f"Aktion -{rabatt}%", fb(total - zwischen, ""))]
    if runden:
        endbetrag = rund5(total)
        zeilen += [("lr", "Total", fb(total, "")), ("lrb", "TOTAL GERUNDET CHF", fb(endbetrag, ""))]
    else:
        zeilen.append(("lrb", "TOTAL CHF", fb(total, "")))
    zeilen += [("lr", zahlung, fb(endbetrag, "")), ("l", f"inkl. MWST {satz}% {fb(_inkl(endbetrag, satz), '')}"), (" ",),
               ("c", "Merci und bis bald!")]
    return f, tag, endbetrag, nummer, zeilen


def f41(k: Kontext) -> Beleg:
    f, tag, total, nummer, zeilen = _bon_einfach(
        k, 41, "backstube", [("Zopf 500 g", d("6.20")), ("Gipfeli 4 Stk", d("5.60")), ("Nussgipfel", d("3.30")),
                             ("Kafi crème", d("4.50"))], "dmyy", "Bon", "Bar", satz="2.6")
    b = Beleg(41, "", "", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
              ["datum_dmyy", "mwst_2_6"], lambda: render.bon(zeilen))
    return _als_foto(b, 41, "baeckerei_schraeg", FotoParameter(winkel=12), ["perspektive"])


def f42(k: Kontext) -> Beleg:
    f, tag, total, nummer, zeilen = _bon_einfach(
        k, 42, "drogerie", [("Sonnencreme LSF 50", d("18.90")), ("Lippenpflege", d("4.80")),
                            ("Taschentücher 10er", d("3.95"))], "ddmmyyyy", "Beleg", "Bar", runden=True, rabatt="10")
    b = Beleg(42, "", "", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
              ["datum_ddmmyyyy", "rundung_5rp", "mehrere_betraege", "mwst_8_1"], lambda: render.bon(zeilen))
    return _als_foto(b, 42, "drogerie_zerknittert", FotoParameter(winkel=8, zerknittert=True, unschaerfe=1.0),
                     ["zerknittert"])


def f43(k: Kontext) -> Beleg:
    f, tag, total, nummer, zeilen = _bon_einfach(
        k, 43, "tankhof", [("Diesel 38.21 l x 1.959", rp(d("38.21") * d("1.959"))), ("Scheibenwischwasser", d("6.90"))],
        "iso", "Beleg", "Kreditkarte")
    b = Beleg(43, "", "", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
              ["datum_iso", "mwst_8_1"], lambda: render.bon(zeilen))
    return _als_foto(b, 43, "tankstelle_gedreht", FotoParameter(winkel=6, drehung=90), ["gedreht_90"])


def f44(k: Kontext) -> Beleg:
    f, tag, total, nummer, zeilen = _bon_einfach(
        k, 44, "seestern", [("Cappuccino", d("5.20")), ("Gipfeli", d("2.40")), ("Zeitschrift", d("8.50"))],
        "ddmmyyyy", "Bon", "TWINT")
    _, _, _, _, hinten = _bon_einfach(k, 144, "hafenmole", [("Parkgebühr 2 Std.", d("4.00"))], "ddmmyyyy", "Ticket", "Karte")
    b = Beleg(44, "", "", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
              ["datum_ddmmyyyy", "mwst_8_1"], lambda: render.bon(zeilen))
    return _als_foto(b, 44, "zwei_belege", FotoParameter(winkel=10, zweiter_beleg=lambda: render.bon(hinten)),
                     ["zwei_belege_nur_vorderer"])


def f45(k: Kontext, heic: bool) -> Beleg:
    rng = k.rng(45)
    tag = _tag(rng)
    nummer = f"{rng.randint(26000, 26999)}"
    r = Rechnung("baumann", "urinet", "D", nummer, tag, "ddmmyyyy",
                 [("Ersatz Steckdose Büro", d(2), d("48.00")), ("Arbeitszeit Elektriker (Std.)", d("1.5"), d("108.00")),
                  ("Kleinmaterial", d(1), d("12.50"))])
    g = rechne(r, k, rng)
    b = _beleg_aus(45, "", "", "LIEFERANTENBELEG", k.firma("baumann").name, tag, g, "CHF", nummer, "AUSGABE",
                   ["qr", "faelligkeit_innert_30_tagen", "mwst_8_1", "datum_ddmmyyyy"], "D")
    return _als_foto(b, 45, "rechnung_perspektive", FotoParameter(winkel=22, schatten=True, format="heic" if heic else "jpg"),
                     ["perspektive", "schatten", *(["heic"] if heic else [])])


def f46(k: Kontext) -> Beleg:
    rng = k.rng(46)
    tag = _tag(rng)
    nummer = f"PW-{rng.randint(1000, 9999)}"
    r = Rechnung("putzwerk", "urinet", "G", nummer, tag, "lang",
                 [("Büroreinigung pauschal, Monat", d(1), d("380.00")), ("Fensterreinigung", d(1), d("145.00"))])
    g = rechne(r, k, rng)
    b = _beleg_aus(46, "", "", "LIEFERANTENBELEG", k.firma("putzwerk").name, tag, g, "CHF", nummer, "AUSGABE",
                   ["qr", "faelligkeit_innert_30_tagen", "mwst_8_1", "datum_ausgeschrieben"], "G")
    return _als_foto(b, 46, "rechnung_unscharf", FotoParameter(winkel=7, unschaerfe=2.2), ["unscharf"])


def f47(k: Kontext) -> Beleg:
    b = _eigene_rechnung(k, 47, "imholz", "", "2026-045", "ddmmyyyy",
                         [("Webseite Occasionen-Liste (Std.)", d(7), d("95.00")), ("Hosting, 12 Monate", d(12), d("19.00"))],
                         ["faelligkeit_innert_30_tagen", "tausender_apostroph"])
    return _als_foto(b, 47, "eigene_rechnung", FotoParameter(winkel=15), ["perspektive"])


def f48(k: Kontext, heic: bool) -> Beleg:
    b = b31(_Umnummeriert(k, 48))
    b.nr = 48
    return _als_foto(b, 48, "ahv_schatten", FotoParameter(winkel=18, schatten=True, format="heic" if heic else "jpg"),
                     ["schatten", *(["heic"] if heic else [])])


def f49(k: Kontext) -> Beleg:
    rng = k.rng(49)
    tag = _tag(rng)
    praemie = d(f"{rng.randint(300, 700)}.{rng.choice(['10', '35', '60'])}")
    nummer = f"SR-{rng.randint(1000000, 9999999)}"
    faellig = tag + timedelta(days=30)
    qr = _qr_amt(k, "hagelwolke", praemie, rng, qrr=False, mitteilung=f"Prämienrechnung {nummer}")
    dok = _brief(k, "hagelwolke", "F", fd(tag, "ddmmyyyy"), "Prämienrechnung Sachversicherung Inventar",
                 meta=[("Rechnungs-Nr.", nummer)], summen=[("Jahresprämie CHF", fb(praemie), True)],
                 absaetze_nach=[f"Zahlbar bis {fd(faellig, 'ddmmyyyy')}."], qr=qr)
    b = Beleg(49, "", "", "VERSICHERUNG", k.firma("hagelwolke").name, tag, praemie, "CHF", nummer, "AUSGABE", faellig,
              ["qr", "faelligkeit_datum", "datum_ddmmyyyy"], lambda: render.a4(dok, render.STILE["F"]), qr=qr)
    return _als_foto(b, 49, "versicherung_licht", FotoParameter(winkel=25), ["perspektive", "ungleiches_licht"])


def f50(k: Kontext) -> Beleg:
    f, tag, total, nummer, zeilen = _bon_einfach(
        k, 50, "hafenmole", [("Parkgebühr 3 Std.", d("6.00"))], "ddmmyyyy", "Ticket", "Debitkarte")
    b = Beleg(50, "", "", "QUITTUNG", f.name, tag, total, "CHF", nummer, "AUSGABE", None,
              ["datum_ddmmyyyy", "mwst_8_1"], lambda: render.bon(zeilen))
    return _als_foto(b, 50, "parkticket_unscharf_schatten", FotoParameter(winkel=18, unschaerfe=1.8, schatten=True),
                     ["unscharf", "schatten"])


@dataclass
class _Umnummeriert:
    """Erzeugt einen Behördenbeleg wie b31, aber mit eigenen Zufallswerten für Beleg 48."""
    basis: Kontext
    nr: int

    def rng(self, _nr: int) -> random.Random:
        return self.basis.rng(self.nr)

    def firma(self, schluessel: str) -> Firma:
        return self.basis.firma(schluessel)

    def qr_iban(self, schluessel: str) -> str:
        return self.basis.qr_iban(schluessel)


def alle(seed: int, heic: bool = True) -> list[Beleg]:
    k = Kontext(seed)
    erzeuger = [q01, q02, q03, q04, q05, q06, q07, q08, q09, q10,
                l11, l12, l13, l14, l15, l16, l17, l18, l19, l20,
                e21, e22, e23, e24, e25, e26, e27, e28, e29, e30,
                b31, b32, b33, b34, b35, b36, b37, b38, b39, b40]
    belege = [e(k) for e in erzeuger]
    belege += [f41(k), f42(k), f43(k), f44(k), f45(k, heic), f46(k), f47(k), f48(k, heic), f49(k), f50(k)]
    return belege
