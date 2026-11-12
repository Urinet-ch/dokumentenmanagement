# golden_synth – synthetische Golden-Belege

Erzeugt 50 synthetische Schweizer Geschäftsbelege und die Erwartungsdatei `erwartet.json` für den
Golden-Datensatz (Spec 01, Req 0.1, 0.7–0.10; Aufgabe 0.1). Der Prototyp (Aufgabe 0.5) misst damit die
Erkennung von Art, Datum, Partner, Betrag und Belegnummer.

- Kein Netzwerkzugriff, keine KI-Aufrufe. Fester Seed (Standard `20261108`): gleicher Seed ergibt
  byte-gleiche Dateien und dieselbe `erwartet.json`.
- Ausgabe **nur ausserhalb des Repositorys**; der Generator bricht ab, wenn das Ziel im Repository liegt.
  Erzeugte Belege nie committen.
- Ausgabeschutz: Abbruch, bevor etwas geschrieben wird, wenn das Ziel die Golden-Wurzel
  (`$ARCHIV_GOLDEN_DIR`, sonst `~/urinet-archiv-golden`) ist oder sie enthält, oder wenn ein nicht leerer Ordner
  den Marker `.golden-synth` nicht trägt. Der Generator legt den Marker selbst an und ersetzt nur die
  Dateinamen aus seinem Katalog; fremde Dateien im Ziel bleiben liegen.
- Beleg und Erwartung stammen aus denselben Werten im Katalog (`katalog.py`), nicht aus nachträglichem Lesen.

## Einrichten und Aufruf

```sh
/opt/homebrew/bin/python3.12 -m venv tools/golden_synth/.venv
tools/golden_synth/.venv/bin/pip install -r tools/golden_synth/requirements.txt
tools/golden_synth/.venv/bin/python -m tools.golden_synth --seed 20261108 --out "$ARCHIV_GOLDEN_DIR/synthetisch"
```

Ohne `--out` gilt `$ARCHIV_GOLDEN_DIR/synthetisch`, sonst `~/urinet-archiv-golden/synthetisch`. Ausgabe:
`belege/<nr>_<kategorie>_<stichwort>.(pdf|jpg|heic)` und `erwartet.json` (Schema: `erwartet.schema.json`).
Der Lauf druckt einen Bericht (Verteilung, Randfälle je Teil, Dateigrössen).

Tests (rund 20 s, erzeugen zweimal in temporäre Verzeichnisse):

```sh
cd tools/golden_synth && .venv/bin/python -m pytest -q
```

## Inhalt

| Nr. | Kategorie | Arten |
| --- | --- | --- |
| 01–10 | `QUITTUNG` | `QUITTUNG`: Kassenbons 80 mm, Online-Shop (A4), handschriftliche Quittung |
| 11–20 | `LIEFERANTENRECHNUNG` | `LIEFERANTENBELEG`: 7 Layouts, 9 mit QR-Rechnung (2 QR-IBAN + QR-Referenz, 1 ohne Betrag), EUR, Gutschrift, zweiseitig |
| 21–30 | `EIGENE_RECHNUNG_OFFERTE` | 6 `AUSGANGSRECHNUNG` (`2026-0xx`, QR), 4 `OFFERTE` (`O-2026-0xx`, «gültig bis») |
| 31–40 | `BEHOERDE_VERSICHERUNG` | `BEHOERDE`, `VERSICHERUNG` (nur Policen), `LIEFERANTENBELEG` (UVG-Prämienrechnung, Mahnung mit Mahngebühr): Akonto, Steuer, Verfügung, UVG, Policen, Mahnung, Brief, Frist, Rückerstattung |
| 41–50 | `HANDY_FOTO` | gemischt; JPEG, 2 als HEIC (falls `pillow-heif` installiert, sonst JPEG) |

Die Randfälle stehen je Beleg in `randfaelle` (z. B. `datum_franzoesisch`, `rundung_5rp`,
`gleiche_nummer_1001`, `zwei_belege_nur_vorderer`).

## Regeln für die Erwartung

- `partner`: Gegenpartei aus Sicht von UriNet, so wie sie auf dem Beleg steht (Vergleich nach Normalisierung, Req 0.7).
- `betrag`: Endtotal, also der zu zahlende bzw. bezahlte Betrag: nach Rabatt, MWST, 5-Rappen-Rundung und
  Trinkgeld, nach Abzug einer Anzahlung; nicht Bar oder Rückgeld. Policen: Jahresprämie. Gutschrift: negativ.
  Ohne Zahlbetrag (Verfügung, Brief, Aufforderung): `null`.
- `richtung`: Lieferanten, Quittungen, Behörden- und Versicherungsrechnungen `AUSGABE`; eigene Rechnungen,
  Offerten und Rückerstattungen `EINNAHME`; Belege ohne Betrag `null`.
- `faelligkeit`: Datum auf dem Beleg; bei «zahlbar innert 30 Tagen» Belegdatum + 30 Tage; bei der
  Lohndeklaration die Einreichefrist; Offerten und Quittungen `null`.
- `belegnummer`: Wert nach der Beschriftung (z. B. «Bon 4711» → `4711`); fehlt sie, `null`.
- `teil`: je Kategorie 6 `abstimmen`, 4 `zurueckgehalten`, aus dem Seed abgeleitet (lokale Suche, Randfälle
  möglichst gleichmässig, mehrfache Randfälle in beiden Teilen) und danach fix (Req 0.8).
- `qr`: erwartete Felder des Swiss QR Codes (Konto, Betrag, Währung, Referenztyp, Referenz, Zahlungsempfänger).

## Erfundene Daten

- Firmen, Personen und Strassen erfunden, PLZ/Orte echt. Absender der eigenen Belege «UriNet Aschwanden,
  Seedorf UR» (Strasse erfunden), Kunden erfunden.
- UID `CHE-…` mit gültiger Prüfziffer (Modulo 11), zufällig.
- IBAN mit gültiger Prüfsumme und erfundener Institutsnummer: normale IBAN `96000–96999`, QR-IBAN `31900–31999`
  (QR-IID-Bereich 30000–31999); QR-Referenzen mit Modulo 10 rekursiv.
- Swiss QR-Rechnung mit `qrbill`; die Tests lesen den QR-Code aus jedem PDF und vergleichen ihn mit `erwartet.json`.

## Schriften

PDF-Standardschriften (Helvetica, Times, Courier), Bitstream Vera (mit reportlab ausgeliefert) und
Kalam (SIL Open Font License 1.1, `fonts/Kalam-Regular.ttf`, Lizenz `fonts/OFL-Kalam.txt`) für die Handschrift.
