"""Erzeugt anonymisierte 21.CRM-Beispieldateien: Kopfzeile 1:1 aus dem echten Export, Werte erfunden.

Aus dem Export übernommen werden nur Spaltennamen und technische Aufzählungswerte (true/false, Status-
texte, Länderformat). Keine Zeile, kein Name, keine Adresse, keine ID wird kopiert.
Aufruf: python3 tools/crm/beispiel_erzeugen.py <exportordner> <zielordner>
"""
import csv, io, random, sys
from pathlib import Path

quelle, ziel = Path(sys.argv[1]), Path(sys.argv[2])
rng = random.Random(20261108)
gguid = lambda: "".join(rng.choice("0123456789ABCDEF") for _ in range(32))
BESITZER = gguid()


def kopf(datei):
    return next(csv.reader(io.StringIO((quelle / datei).read_bytes().decode("cp1252"), newline=""), delimiter=";"))


def zeile(h, werte):
    r = [""] * len(h)
    for k, v in werte.items():
        r[h.index(k)] = v  # bei der doppelten Spalte «Sekretär(in)» bleibt die zweite leer
    return r


TECHNIK = {
    "Ist gelöscht": "false", "Kontakt deaktiviert": "false", "Länderformat": "DE", "EBID-Status": "nicht geprüft",
    "ADDRESSCHECKSTATUS": "false", "Ist Vorlage": "false", "ISSYSTEMUNCOMMITTED": "false",
    "Maximales Fremdzugriffsrecht": "65535", "Synchrone Feldwerte": "true",
    "Geänderte Daten in Unternehmensverzeichnis.org": "false", "Swiss21": "false",
    "OWNERGUID": BESITZER, "Erzeugt von": "Max Muster", "Geändert von": "Max Muster", "Zugriffsberechtigte": "Max Muster",
}

ORGS = [  # erfundene Firmen, echte PLZ/Orte; Domains unter example.com (RFC 2606), Nummern ohne Zuteilung (000 …)
    dict(org="Muster Bau AG", strasse="Werkstrasse 12", plz="6460", ort="Altdorf UR", kt="UR", web="www.muster-bau.example.com",
         mail="info@muster-bau.example.com", tel="+41 41 000 01 10", geo=("46.880000000", "8.640000000"), erzeugt="03.02.26 09:15:00"),
    dict(org="Beispiel Treuhand GmbH", strasse="Seeweg 5", plz="6003", ort="Luzern", kt="LU", web="www.beispiel-treuhand.example.com",
         mail="", tel="+41 41 000 02 20", geo=("47.050000000", "8.300000000"), erzeugt="14.05.26 16:40:12"),
    dict(org="Verein Testlauf", strasse="", plz="8001", ort="Zürich", kt="", web="", mail="", tel="",
         geo=None, erzeugt="21.09.26 11:02:59"),
]
PERSONEN = [
    dict(anrede="Frau", brief="Sehr geehrte Frau Beispiel", vorname="Anna", name="Beispiel", geschlecht="Weiblich",
         org="Muster Bau AG", mail="anna.beispiel@muster-bau.example.com", tel="+41 79 000 03 30", erzeugt="03.02.26 09:20:41"),
    dict(anrede="Herrn", brief="Sehr geehrter Herr Muster", vorname="Peter", name="Muster", geschlecht="Männlich",
         org="", mail="", tel="", erzeugt="30.10.26 08:05:00"),
]

h = kopf("ADDRESS.csv")
zeilen = []
for o in ORGS:
    w = dict(TECHNIK, **{"GGUID": gguid(), "Kontakt ist eine Organisation": "true", "Organisation": o["org"],
                         "Straße (Geschäft)": o["strasse"], "PLZ (Geschäft)": o["plz"], "Ort (Geschäft)": o["ort"],
                         "Kanton (Geschäft)": o["kt"], "Land (Geschäft)": "CH", "Homepage (Zentrale)": o["web"],
                         "E-Mail (Zentrale)": o["mail"], "Telefon (Zentrale)": o["tel"], "Erzeugt am": o["erzeugt"],
                         "Geändert am": o["erzeugt"], "Standardanschrift": "0",
                         "Georeferenzierungsstatus": "Genau" if o["geo"] else "Ausstehend"})
    if o["mail"]:
        w["Standard-E-Mail"] = "5"
    if o["tel"]:  # 21.CRM: Index 10 = Telefon (Zentrale), PHONEID10 = Nummer nur aus Ziffern mit 0041
        w["Standard-Telefon 1"], w["PHONEID10"] = "10", "00" + o["tel"].replace("+", "").replace(" ", "")
    if o["geo"]:
        w["Geografische Breite"], w["Geografische Länge"] = o["geo"]
        w["GEOTILEID"] = "".join(rng.choice("0123456789") for _ in range(23))
    zeilen.append(zeile(h, w))
for p in PERSONEN:
    w = dict(TECHNIK, **{"GGUID": gguid(), "Kontakt ist eine Organisation": "false", "Anrede": p["anrede"],
                         "Briefanrede": p["brief"], "Vorname": p["vorname"], "Name": p["name"], "Geschlecht": p["geschlecht"],
                         "Organisation": p["org"], "E-Mail (Geschäft)": p["mail"], "Telefon (Geschäft)": p["tel"],
                         "Erzeugt am": p["erzeugt"], "Geändert am": p["erzeugt"], "Georeferenzierungsstatus": "Ausstehend"})
    if p["mail"]:
        w["Standard-E-Mail"] = "1"
    if p["tel"]:  # Index 4 = Telefon (Geschäft)
        w["Standard-Telefon 1"], w["PHONEID4"] = "4", "00" + p["tel"].replace("+", "").replace(" ", "")
    zeilen.append(zeile(h, w))


def schreibe(name, kopfzeile, daten):
    # wie 21.CRM: Windows-1252, Semikolon, LF, keine Anführungszeichen
    text = "".join(";".join(r) + "\n" for r in [kopfzeile] + daten)
    assert all(";" not in f and "\n" not in f and '"' not in f for r in daten for f in r)
    (ziel / name).write_bytes(text.encode("cp1252"))


ziel.mkdir(parents=True, exist_ok=True)
schreibe("21crm-kontakte-beispiel.csv", h, zeilen)
schreibe("21crm-verkaufschancen-beispiel.csv", kopf("GWOPPORTUNITY.csv"), [])  # echter Export: keine Datenzeilen
print(len(zeilen), "Kontakte,", len(h), "Spalten")
