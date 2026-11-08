import random
from decimal import Decimal

from stdnum import iban as std_iban
from stdnum.ch import esr, uid

from tools.golden_synth import pruefziffern as pz
from tools.golden_synth.formate import betrag, rund5


def test_uid_hat_gueltige_pruefziffer():
    rng = random.Random(1)
    for _ in range(300):
        nummer = pz.uid(rng)
        assert uid.is_valid(nummer), nummer
        assert pz.uid_gueltig(nummer)


def test_iban_hat_gueltige_pruefsumme_und_erfundene_iid():
    rng = random.Random(2)
    for _ in range(300):
        normal, qr = pz.iban(rng), pz.iban(rng, qr=True)
        assert std_iban.is_valid(normal) and std_iban.is_valid(qr)
        assert 96000 <= int(normal[4:9]) <= 96999
        assert 30000 <= int(qr[4:9]) <= 31999


def test_iban_beispiel_aus_der_norm():
    assert pz.iban_gueltig("CH9300762011623852957")
    assert not pz.iban_gueltig("CH9300762011623852958")


def test_qr_referenz_modulo_10_rekursiv():
    assert pz.qr_referenz_gueltig("210000000003139471430009017")
    rng = random.Random(3)
    for _ in range(300):
        assert esr.is_valid(pz.qr_referenz(rng))


def test_betrag_und_rundung():
    assert betrag(Decimal("1543.67")) == "1'543.67"
    assert betrag(Decimal("2246.3"), " ") == "2 246.30"
    assert betrag(Decimal("-120")) == "-120.00"
    assert betrag(Decimal("260"), strich=True) == "260.–"
    assert rund5(Decimal("244.06")) == Decimal("244.05")
    assert rund5(Decimal("24.88")) == Decimal("24.90")
