import json
import re
from collections import Counter
from pathlib import Path

import jsonschema
import pytest

from tools.golden_synth import erwartung, generator, pruefziffern as pz
from tools.golden_synth.modell import KATEGORIEN

SEED = generator.STANDARD_SEED
SCHEMA = json.loads((Path(__file__).parents[1] / "erwartet.schema.json").read_text(encoding="utf-8"))

PFLICHT_RANDFAELLE = {
    "datum_ddmmyyyy", "datum_dmyy", "datum_ausgeschrieben", "datum_iso", "datum_franzoesisch", "mehrere_betraege",
    "rundung_5rp", "tausender_apostroph", "tausender_leerzeichen", "nummer_wie_eigene", "gleiche_nummer_1001",
    "ohne_belegnummer", "ohne_datum", "sprache_fr", "sprache_it", "mwst_8_1", "mwst_2_6", "mwst_3_8", "mwst_gemischt",
    "faelligkeit_datum", "faelligkeit_innert_30_tagen",
}


@pytest.fixture(scope="module")
def belege():
    return generator.belege(SEED)


@pytest.fixture(scope="module")
def daten(belege):
    return json.loads(erwartung.als_json(belege, SEED))


def test_verteilung_5_mal_10_und_6_zu_4(belege):
    assert len(belege) == 50
    for k in KATEGORIEN:
        teile = Counter(b.teil for b in belege if b.kategorie == k)
        assert teile == {"abstimmen": 6, "zurueckgehalten": 4}, k


def test_schema(daten):
    jsonschema.validate(daten, SCHEMA)


def test_reproduzierbar():
    a = erwartung.als_json(generator.belege(SEED), SEED)
    b = erwartung.als_json(generator.belege(SEED), SEED)
    assert a == b
    assert a != erwartung.als_json(generator.belege(SEED + 1), SEED + 1)


def test_alle_pflicht_randfaelle_und_mehrfache_in_beiden_teilen(daten):
    je_teil = Counter((r, e["teil"]) for e in daten["belege"] for r in e["randfaelle"])
    gesamt = Counter(r for e in daten["belege"] for r in e["randfaelle"])
    assert PFLICHT_RANDFAELLE <= set(gesamt)
    for r, n in gesamt.items():
        if n >= 2:
            assert je_teil[(r, "abstimmen")] and je_teil[(r, "zurueckgehalten")], r


def test_eigene_belegnummern(daten):
    for e in daten["belege"]:
        if e["art"] == "AUSGANGSRECHNUNG":
            assert re.fullmatch(r"\d{4}-\d{3}", e["belegnummer"])
        if e["art"] == "OFFERTE":
            assert re.fullmatch(r"O-\d{4}-\d{3}", e["belegnummer"]) and e["faelligkeit"] is None


def test_lieferanten_qr_vorgaben(daten):
    lieferanten = [e for e in daten["belege"] if e["kategorie"] == "LIEFERANTENRECHNUNG"]
    mit_qr = [e for e in lieferanten if e["qr"]]
    assert len(mit_qr) >= 7
    assert sum(e["qr"]["referenztyp"] == "QRR" for e in mit_qr) >= 2
    assert sum(e["qr"]["betrag"] is None for e in mit_qr) >= 1
    nummern = Counter(e["belegnummer"] for e in lieferanten)
    assert nummern["1001"] == 2


def test_qr_felder_passen_zum_beleg(daten):
    for e in daten["belege"]:
        q = e["qr"]
        if not q:
            continue
        assert pz.iban_gueltig(q["konto"])
        assert q["waehrung"] == e["waehrung"]
        if q["betrag"] is not None:
            assert q["betrag"] == e["betrag"], e["datei"]
        qr_iid = 30000 <= int(q["konto"][4:9]) <= 31999
        assert qr_iid == (q["referenztyp"] == "QRR"), e["datei"]
        if q["referenztyp"] == "QRR":
            assert pz.qr_referenz_gueltig(q["referenz"])
        empfaenger = "UriNet Aschwanden" if e["art"] == "AUSGANGSRECHNUNG" else e["partner"]
        assert q["zahlungsempfaenger"] == empfaenger


def test_fotos(daten):
    fotos = [e for e in daten["belege"] if e["kategorie"] == "HANDY_FOTO"]
    assert all(e["foto"] for e in fotos) and len(fotos) == 10
    assert not any(e["foto"] for e in daten["belege"] if e["kategorie"] != "HANDY_FOTO")
    assert {e["datei"].rsplit(".", 1)[1] for e in fotos} <= {"jpg", "heic"}


def test_uid_und_iban_der_firmen_gueltig(belege):
    for b in belege:
        if b.qr:
            assert pz.iban_gueltig(b.qr.empfaenger.iban)
            if b.qr.empfaenger.uid:
                assert pz.uid_gueltig(b.qr.empfaenger.uid)
