"""Prüfziffern für UID, IBAN und QR-Referenz (nur erfundene Nummern)."""

from __future__ import annotations

import random

_UID_GEWICHTE = (5, 4, 3, 2, 7, 6, 5, 4)
_MOD10_TABELLE = (0, 9, 4, 6, 8, 2, 7, 1, 3, 5)

# Erfundene Institutsnummern: normale IBAN 96000–96999, QR-IBAN 31900–31999 (QR-IID-Bereich 30000–31999).
IID_NORMAL = (96000, 96999)
IID_QR = (31900, 31999)


def uid_pruefziffer(ziffern8: str) -> int | None:
    rest = sum(int(z) * g for z, g in zip(ziffern8, _UID_GEWICHTE)) % 11
    p = (11 - rest) % 11
    return None if p == 10 else p


def uid(rng: random.Random) -> str:
    while True:
        basis = f"{rng.randrange(10**7, 10**8):08d}"
        p = uid_pruefziffer(basis)
        if p is not None:
            z = basis + str(p)
            return f"CHE-{z[0:3]}.{z[3:6]}.{z[6:9]}"


def uid_gueltig(text: str) -> bool:
    z = "".join(c for c in text if c.isdigit())
    return len(z) == 9 and uid_pruefziffer(z[:8]) == int(z[8])


def _mod97(ziffern: str) -> int:
    return int(ziffern) % 97


def _iban_ziffern(text: str) -> str:
    return "".join(str(int(c, 36)) for c in text)


def iban_ch(iid: int, konto: str) -> str:
    bban = f"{iid:05d}{konto}"
    pruef = 98 - _mod97(_iban_ziffern(bban + "CH00"))
    return f"CH{pruef:02d}{bban}"


def iban(rng: random.Random, qr: bool = False) -> str:
    von, bis = IID_QR if qr else IID_NORMAL
    return iban_ch(rng.randint(von, bis), f"{rng.randrange(10**12):012d}")


def iban_gueltig(text: str) -> bool:
    t = text.replace(" ", "").upper()
    return len(t) == 21 and t.startswith("CH") and _mod97(_iban_ziffern(t[4:] + t[:4])) == 1


def iban_formatiert(text: str) -> str:
    return " ".join(text[i:i + 4] for i in range(0, len(text), 4))


def mod10_rekursiv(ziffern: str) -> int:
    uebertrag = 0
    for z in ziffern:
        uebertrag = _MOD10_TABELLE[(uebertrag + int(z)) % 10]
    return (10 - uebertrag) % 10


def qr_referenz(rng: random.Random) -> str:
    basis = f"{rng.randrange(10**26):026d}"
    return basis + str(mod10_rekursiv(basis))


def qr_referenz_gueltig(text: str) -> bool:
    t = text.replace(" ", "")
    return len(t) == 27 and t.isdigit() and mod10_rekursiv(t[:26]) == int(t[26])


def qr_referenz_formatiert(text: str) -> str:
    return text[:2] + " " + " ".join(text[i:i + 5] for i in range(2, 27, 5))
