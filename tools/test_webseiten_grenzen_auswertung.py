"""Synthetische Tests für die Auswertung der Webseiten-Grenzen."""

from __future__ import annotations

import io
import unittest
from decimal import Decimal

from tools.webseiten_grenzen_auswertung import auswerten, lese_messungen, schreiben


class WebseitenGrenzenAuswertungTest(unittest.TestCase):
    def test_p95_429_und_stosswerte_werden_pro_alias_ausgewertet(self):
        daten = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze\n"
            "K01,lesen,dauer,60,480,0,20,100\n"
            "K01,lesen,dauer,60,1200,600,20,100\n"
            "K01,lesen,spitze,1,110,10,20,100\n"
            "K02,uploads,dauer,3600,60,0,120,120\n"
        )

        ergebnisse = auswerten(lese_messungen(daten))
        dauer = next(e for e in ergebnisse if e.alias == "K01" and e.modus == "dauer")
        stoss = next(e for e in ergebnisse if e.alias == "K01" and e.modus == "spitze")
        uploads = next(e for e in ergebnisse if e.grenze == "uploads")

        self.assertEqual(Decimal("20"), dauer.bedarf)
        self.assertEqual(1080, dauer.nicht_429)
        self.assertEqual(Decimal("35.71428571428571428571428571"), dauer.quote_429)
        self.assertEqual(Decimal("110"), stoss.bedarf)
        self.assertEqual(Decimal("110"), stoss.auslastung)
        self.assertEqual(Decimal("60"), uploads.bedarf)
        self.assertEqual("pro Stunde", uploads.einheit)

    def test_alias_muss_synthetisch_sein(self):
        daten = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze\n"
            "token-secret,lesen,dauer,60,1,0,20,100\n"
        )

        with self.assertRaisesRegex(ValueError, "erfundener Wert"):
            lese_messungen(daten)

    def test_tsv_bezeichnet_nicht_429_antworten_neutral(self):
        daten = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze\n"
            "K01,lesen,dauer,60,2,1,20,100\n"
        )
        ausgabe = io.StringIO()

        schreiben(auswerten(lese_messungen(daten)), ausgabe)
        kopfzeile, daten_zeile = ausgabe.getvalue().splitlines()

        self.assertIn("nicht_429", kopfzeile.split("\t"))
        self.assertEqual("1", daten_zeile.split("\t")[5])

    def test_csv_kopfzeile_und_zeilenform_muessen_eindeutig_sein(self):
        doppelte_spalte = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze,alias\n"
        )
        zu_viele_werte = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze\n"
            "K01,lesen,dauer,60,1,0,20,100,extra\n"
        )

        with self.assertRaisesRegex(ValueError, "doppelte Spalten"):
            lese_messungen(doppelte_spalte)
        with self.assertRaisesRegex(ValueError, "zu viele Werte"):
            lese_messungen(zu_viele_werte)

    def test_429_und_konfigurationswerte_muessen_konsistent_sein(self):
        unmoegliche_429 = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze\n"
            "K01,lesen,dauer,60,1,2,20,100\n"
        )
        with self.assertRaisesRegex(ValueError, "429 kann"):
            lese_messungen(unmoegliche_429)

        wechselnde_werte = io.StringIO(
            "alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze\n"
            "K01,lesen,dauer,60,1,0,20,100\n"
            "K01,lesen,dauer,60,1,0,21,100\n"
        )
        with self.assertRaisesRegex(ValueError, "Uneinheitliche Startwerte"):
            auswerten(lese_messungen(wechselnde_werte))


if __name__ == "__main__":
    unittest.main()
