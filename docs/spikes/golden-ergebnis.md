# Golden-Lauf (Aufgabe 0.5) – Kennzahlen

Stand 09.11.2026 · Req 0.2, 0.3, 0.7–0.10, 7.1, 7.11, 7.12, 16.22 · App: [`spikes/golden-lauf/`](../../spikes/golden-lauf/)

Im Repository stehen nur Kennzahlen und Beleg-IDs (synthetisch: Dateiname). Die Vollberichte mit Erwartung,
Vorschlag und Sicherheit je Beleg liegen in `$ARCHIV_GOLDEN_DIR/berichte/<Zeitpunkt>-<Modus>/`.

## Kurz

- **Prüfteil (Gate, Req 0.3/0.9): noch nicht gelaufen.** Den Gate-Lauf (`--gate`, 20 zurückgehaltene Belege) startet Fabian.
- **Abstimmungsteil (30 synthetische Belege), Endstand Prompt v3:** 82 % der Felder richtig, 18/30 Belege mit Art,
  Datum, Partner und Betrag richtig (60 %). Hochgerechnet auf den Prüfteil wären das rund 12/20, das Gate verlangt 18/20.
- **Schwelle (Req 0.9):** Mit 0.8 bleiben 8–13 falsche Felder mit hoher Sicherheit. Die kleinste Schwelle mit höchstens
  einem Fehler lag je nach Lauf bei 0.91 oder höher als 1.0. Die Selbsteinschätzung des Modells trennt richtig und falsch kaum.
- **Grösste Fehlerquelle:** Handy-Fotos über OCR (5 von 6 Fotos im Abstimmungsteil weitgehend falsch). Mit Variante K1
  (Bild statt OCR-Text) sind die Fotos überwiegend richtig.
- **Zweite Fehlerquelle:** Lieferantenrechnungen werden als `AUSGANGSRECHNUNG` eingestuft, darunter der Testfall `2026-123` (Req 7.12).
- **Realitätsprüfung (Req 0.10):** 0 bestätigte echte Belege (20 noch nicht bestätigt oder mit Prüfmarke).
- Kosten: rund CHF 0.014 je 30 Belege (Text) bzw. CHF 0.025 (K1); KI rund 3–4 s je Beleg.

## Läufe auf dem Abstimmungsteil

Alle mit `mistralai/Mistral-Small-4-119B-2603`, `json_schema` strict, `temperature=0`, ein Aufruf je Beleg, Schwelle 0.8.

| Lauf | Prompt | Eingabe | Art | Datum | Partner | Betrag | Beleg-Nr. | Felder | Gate-Belege | Fehler ≥ 0.8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-08-2106 | v1 | OCR-Text | 12 | 24 | 26 | 26 | 22 | 110/150 | 11/30 | 22 |
| 2026-11-08-2110 | v2 | OCR-Text | 24 | 24 | 27 | 25 | 25 | 125/150 | 20/30 | 11 |
| 2026-11-08-2239 | v3 | OCR-Text | 24 | 23 | 28 | 25 | 25 | 125/150 | 19/30 | 8 |
| 2026-11-08-2245 | v4 (verworfen) | OCR-Text | 27 | 24 | 24 | 26 | 22 | 123/150 | 20/30 | 18 |
| **2026-11-09-0616** | **v3** `v3-24e40a6cf5ab` | OCR-Text | 21 | 23 | 28 | 26 | 25 | 123/150 | 18/30 | 13 |
| 2026-11-08-2243 | v3 `v3-24e40a6cf5ab` | **K1: Bild** | 22 | 28 | 29 | 27 | 28 | 134/150 | 18/30 | 12 |
| 2026-11-08-2247 | v4 (verworfen) | K1: Bild | 24 | 28 | 23 | 29 | 28 | 132/150 | 17/30 | 17 |

Zahlen = richtige Felder von 30. Die zwei v3-Läufe mit OCR-Text unterscheiden sich trotz `temperature=0` (Art 24 gegenüber 21,
Fehler ≥ 0.8: 8 gegenüber 13). Das Modell ist zwischen Läufen nicht deterministisch, kleine Unterschiede zwischen
Prompt-Fassungen sind daher Rauschen.

Prompt-Fassungen (Iteration nur auf dem Abstimmungsteil, Req 0.8):
- **v1:** Rolle, Mandant, Dokumentarten (Code, Name, Pflichtfelder, Vertraulichkeit), Feldregeln.
- **v2:** `art` als Enum im Schema (v1 lieferte u. a. `AUSGABE` als Art), Arten des Mandanten aus `eigene_arten`
  (design.md «Mandant») markiert, Zuordnung fremder Belege (Quittung, Lieferant, Behörde, Versicherung),
  Beschriftungen von Belegnummern, kein erfundenes Datum.
- **v3:** Aussteller im Briefkopf gegenüber Adressfeld, Zahlungsempfänger im QR-Code; zweistellige Jahre; «ohne Betrag null, nicht 0».
  Dazu die deterministische Plausibilitätsprüfung (siehe unten).
- **v4:** Aussteller und «Mandant ist Aussteller» als eigene Schemafelder vor der Art. Art besser, Partner schlechter,
  mehr sichere Fehler → verworfen.

## Endstand v3, OCR-Text (Lauf 2026-11-09-0616)

| Feld | richtig | Quote |
| --- | --- | --- |
| Art | 21/30 | 70 % |
| Datum | 23/30 | 77 % |
| Partner | 28/30 | 93 % |
| Betrag | 26/30 | 87 % |
| Belegnummer | 25/30 | 83 % |
| **gesamt (Felder)** | 123/150 | 82 % |
| **Belege mit Art, Datum, Partner, Betrag richtig** | 18/30 | 60 % |

- Falsche Felder mit Sicherheit ≥ 0.8: 13; kleinste Schwelle mit höchstens 1 Fehler: 0.91
- Swiss QR Code gelesen: 14 von 14 erwarteten, darunter die Fotos 47 und 48; Nachfragen nach Req 7.3: 0
- Tokens 35'814 ein / 9'247 aus, Kosten ca. CHF 0.014 (Preisliste Infomaniak 08.11.2026: CHF 0.20 / 0.75 je 1 Mio., exkl. MWST)
- Laufzeit je Beleg: KI im Mittel 4.1 s; OCR mit Cache 0.3 s, ohne Cache unter 2 s für Text-PDFs (`--skip-text`) und 9–21 s für Fotos

Fehlschläge (Beleg-ID: Feld):
- 09_quittung_onlineshop.pdf: ART, DATUM
- 10_quittung_handschrift.pdf: DATUM
- 11_lieferantenrechnung_hosting.pdf: ART
- 13_lieferantenrechnung_telekom.pdf: ART
- 15_lieferantenrechnung_buerobedarf.pdf: ART
- 18_lieferantenrechnung_eur_franzoesisch.pdf: ART
- 40_behoerde_versicherung_rueckerstattung.pdf: ART
- 41_handy_foto_baeckerei_schraeg.jpg: ART, DATUM, BETRAG, BELEGNUMMER
- 42_handy_foto_drogerie_zerknittert.jpg: DATUM, BETRAG, BELEGNUMMER
- 43_handy_foto_tankstelle_gedreht.jpg: ART, DATUM, PARTNER, BETRAG, BELEGNUMMER
- 48_handy_foto_ahv_schatten.heic: ART, DATUM, BELEGNUMMER
- 50_handy_foto_parkticket_unscharf_schatten.jpg: DATUM, PARTNER, BETRAG, BELEGNUMMER

## Variante K1: Bild statt OCR-Text (Lauf 2026-11-08-2243, nur Abstimmungsteil)

Seiten (300 dpi) untereinander, lange Kante 2'000 px, JPEG; QR-Code und Nachbearbeitung unverändert.
134/150 Felder (89 %), 18/30 Gate-Belege, 12 Fehler ≥ 0.8, 87'953 Eingabe-Tokens (2.5-mal so viele wie Text).
Die Fotos sind bis auf einzelne Felder richtig. Fehlschläge: 03_quittung_restaurant.pdf: ART;
09_quittung_onlineshop.pdf: ART; 10_quittung_handschrift.pdf: DATUM, BETRAG, BELEGNUMMER;
11_lieferantenrechnung_hosting.pdf: ART; 13_lieferantenrechnung_telekom.pdf: ART;
14_lieferantenrechnung_druckerei.pdf: ART; 18_lieferantenrechnung_eur_franzoesisch.pdf: ART;
20_lieferantenrechnung_zweiseitig.pdf: DATUM; 38_behoerde_versicherung_brief_ohne_betrag.pdf: BETRAG;
40_behoerde_versicherung_rueckerstattung.pdf: ART, BELEGNUMMER; 42_handy_foto_drogerie_zerknittert.jpg: ART;
43_handy_foto_tankstelle_gedreht.jpg: PARTNER, BETRAG.

## Befunde

1. **Fotos über OCR scheitern.** Tesseract (OCRmyPDF 17.13.0, `--rotate-pages --deskew`) liest Rohfotos mit
   Perspektive, Schatten, Drehung oder Unschärfe nur bruchstückhaft. Andere Einstellungen (`--rotate-pages-threshold 1`,
   `--tesseract-pagesegmode 4/11`, `--oversample 400`) brachten auf drei Fotos keine Besserung. In der App sorgt die
   Arbeitsfassung (Zuschnitt, Req 5.10) vermutlich für bessere Vorlagen; der Golden-Datensatz enthält aber Rohfotos.
2. **Bilder direkt an das Modell (K1) helfen bei Fotos deutlich.** Kandidat für die Verarbeitung: Fotos zusätzlich als Bild
   an das Modell geben, Volltext für die Suche weiter per OCR. Das wäre ein Designentscheid (0.6), nicht dieses Laufs.
3. **Art bei fremden Rechnungen ist die zweite Schwäche.** Das Modell stuft Lieferantenrechnungen trotz Regeln als
   `AUSGANGSRECHNUNG` ein, auch `2026-123`. Die Nachbearbeitung ändert die Art nur, wenn der Mandant Aussteller ist
   (Req 7.12). Neu senkt sie bei einer eigenen Art und einem QR-Code mit fremdem Zahlungsempfänger die Sicherheit
   der Art auf 0, damit das Feld «bitte prüfen» zeigt. Eine Umstufung wäre wirksamer, bräuchte aber einen Entscheid.
4. **Die Sicherheit des Modells taugt kaum als Schwelle.** Falsche Felder kommen oft mit 0.9–1.0 zurück. Für Req 0.9
   braucht es entweder eine Schwelle über 0.9 (dann bleiben viele richtige Felder «bitte prüfen») oder deterministische
   Signale (QR, Kennungen, Muster) statt Selbsteinschätzung.
5. **Swiss QR Code:** alle 14 erwarteten QR-Codes gelesen, auch auf zwei Fotos (ZXing, 300 dpi, zusätzlich halbe
   Auflösung). Betrag, Währung und Zahlungsempfänger ersetzen die KI-Werte. Beim Foto 48 waren dadurch Partner und
   Betrag richtig, obwohl der OCR-Text unbrauchbar war.
6. **Laufschwankung:** gleiche Eingabe, `temperature=0`, trotzdem unterschiedliche Antworten zwischen Läufen.

## Offen für Gate 0 (Aufgabe 0.6, Fabian)

- Gate-Lauf `--gate` auf dem Prüfteil (20 Belege) mit Prompt v3 und Schwelle 0.8; Ergebnis hier ergänzen.
- Bestätigung echter Belege für die Realitätsprüfung (`bestaetigt: true`, Prüfmarken auflösen), danach `--realitaet`.
- Entscheid zu K1 (Bild an das Modell) und zur Umstufung der Art bei fremdem QR-Zahlungsempfänger.
