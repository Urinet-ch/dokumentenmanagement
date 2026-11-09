# Golden-Lauf (Aufgabe 0.5) – Kennzahlen

Stand 09.11.2026 · Req 0.2, 0.3, 0.7–0.10, 7.1, 7.11, 7.12, 16.22 · App: [`spikes/golden-lauf/`](../../spikes/golden-lauf/)

Im Repository stehen nur Kennzahlen und Beleg-IDs (synthetisch: Dateiname). Die Vollberichte mit Erwartung,
Vorschlag und Sicherheit je Beleg liegen in `$ARCHIV_GOLDEN_DIR/berichte/<Zeitpunkt>-<Modus>/`.

## Kurz

- **Prüfteil (Gate, Req 0.3/0.9): Gate-Lauf 2026-11-09-185812 (Fabian), Kriterien erfüllt.** 20/20 Belege mit Art, Datum,
  Partner, Betrag richtig (Gate: ≥ 18), 1 falsches Feld mit Sicherheit ≥ 0.8 (Gate: ≤ 1), kleinste Schwelle 0.00,
  Schwelle 0.8. Abnahme: `docs/umsetzungsplan.md`, Abschnitt 6.
- **Gate-Vorbereitung (09.11.2026, unten):** Req 0.9 neu, Regeln R3/E1/E2. gemma-4-31B-it erreicht 30/30 in vier
  Läufen mit kleinster Schwelle 0.00 (gate-reif), Qwen3.5-397B 30/30 und 28/30 (gate-reif, teuer, Timeouts),
  Mistral-Small-4 27/30 und 24/30 (nicht gate-reif). Fabian hat am 09.11.2026 entschieden: gemma-4-31B-it, Schwelle 0.8.
- **Nachschärfen (09.11.2026, unten):** Prompt v5 mit Bild für Fotos und engerer Nachbearbeitung, 21 und 26/30 Belege
  mit Art, Datum, Partner, Betrag richtig (Mittel 23.5); in keinem Lauf eine Schwelle ≤ 1.0 mit höchstens einem Fehler.
  Vorläufig eingefroren: `v5-fb5c021d33fc` (abgelöst durch den eingefrorenen Stand der Gate-Vorbereitung, siehe oben).
- **Abstimmungsteil (30 synthetische Belege), Endstand Prompt v3:** 82 % der Felder richtig, 18/30 Belege mit Art,
  Datum, Partner und Betrag richtig (60 %). Hochgerechnet auf den Prüfteil wären das rund 12/20, das Gate verlangt 18/20.
- **Schwelle (Req 0.9):** Mit 0.8 bleiben 8–13 falsche Felder mit hoher Sicherheit. Die kleinste Schwelle mit höchstens
  einem Fehler lag je nach Lauf bei 0.91 oder höher als 1.0. Die Selbsteinschätzung des Modells trennt richtig und falsch kaum.
- **Grösste Fehlerquelle:** Handy-Fotos über OCR (5 von 6 Fotos im Abstimmungsteil weitgehend falsch). Mit Variante K1
  (Bild statt OCR-Text) sind die Fotos überwiegend richtig.
- **Zweite Fehlerquelle:** Lieferantenrechnungen werden als `AUSGANGSRECHNUNG` eingestuft, darunter der Testfall `2026-123` (Req 7.12).
- **Realitätsprüfung (Req 0.10):** 20 echte Belege bestätigt, 0 ausgeschlossen (Fabian, 09.11.2026, 14:35);
  `--realitaet` noch nicht gelaufen.
- Kosten: rund CHF 0.014 je 30 Belege (Text) bzw. CHF 0.025 (K1); KI rund 3–4 s je Beleg.

## Läufe auf dem Abstimmungsteil

Alle mit `mistralai/Mistral-Small-4-119B-2603`, `json_schema` strict, `temperature=0`, ein Aufruf je Beleg (logisch;
Transport-Wiederholungen siehe Befund 14), Schwelle 0.8.

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

## Nachschärfen vor Gate 0 (09.11.2026, PR #15)

Umgesetzt nach den Entscheiden von Fabian (09.11.2026): Umstufung bei fremdem QR-Zahlungsempfänger ja, Bild an das
Modell für Bild-Originale (K1 nur Fotos) ja, Feldregeln im Prompt ja. Jede Variante zweimal voll auf dem
Abstimmungsteil; verglichen werden Mittel und Spannweite, nie ein Einzellauf. Die älteren Läufe oben (2239, 0616) haben
verschiedene Prompt-Hashes und keine rohe Art; sie bleiben nur Referenz. Lauf 2243 (`--k1`, jeder Beleg als Bild)
ist ein Einzellauf und ebenfalls nur Referenz.

- **V1 – v3, OCR-Text (Basis):** Prompt-Hash neu über gerenderten Prompt, Schema und Bildweg; v3 unverändert.
- **V2 – v3, Bildweg `fotos`:** Bild-Originale (Typ am Inhalt) als gerenderte Seiten an das Modell, PDFs als OCR-Text.
- **V3 – v5, Bildweg `fotos`, neue Nachbearbeitung:** Vorrang des fremden QR, Kennung nur im Kopf- oder Fussbereich
  (erste und letzte 10 nicht leere Zeilen je Seite), Art-Sicherheit 0 bei eigener Art ohne Kennung auch ohne QR,
  Umstufung `AUSGANGSRECHNUNG`/`OFFERTE` → `LIEFERANTENBELEG` (Sicherheit 0.5) bei fremdem QR; Prompt v5 mit
  fremden Arten zuerst, Anzeigetexten, Sozialversicherungs-Regel und Feldregeln (Offerten-Total, «Antrag»).

| Lauf | Prompt | Eingabe | Art | Datum | Partner | Betrag | Beleg-Nr. | Felder | Gate-Belege | Fehler ≥ 0.8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-09-1419 | V1 `v3-18f86be53e9e` | OCR-Text | 23 | 24 | 27 | 26 | 24 | 124/150 | 18/30 | 10 |
| 2026-11-09-1424 | V1 `v3-18f86be53e9e` | OCR-Text | 26 | 22 | 27 | 26 | 24 | 125/150 | 20/30 | 12 |
| 2026-11-09-1428 | V2 `v3-bf24bc4755ca` | Bild für Fotos | 26 | 27 | 29 | 28 | 28 | 138/150 | 22/30 | 9 |
| 2026-11-09-1431 | V2 `v3-bf24bc4755ca` | Bild für Fotos | 28 | 28 | 29 | 28 | 27 | 140/150 | 24/30 | 5 |
| **2026-11-09-1434** | **V3 `v5-fb5c021d33fc`** | Bild für Fotos | 25 | 28 | 29 | 28 | 29 | 139/150 | 21/30 | 9 |
| **2026-11-09-1438** | **V3 `v5-fb5c021d33fc`** | Bild für Fotos | 26 | 30 | 29 | 30 | 28 | 143/150 | 26/30 | 6 |
| 2026-11-09-1442 | V4 `v6-35426948c145` (verworfen) | Bild für Fotos | 25 | 28 | 26 | 27 | 29 | 135/150 | 19/30 | 11 |
| 2026-11-09-1445¹ | V4 `v6-35426948c145` (verworfen) | Bild für Fotos | 24 | 29 | 27 | 29 | 29 | 138/150 | 22/30 | 6 |

¹ Vollbericht durch Prüflauf (v5, 1 Beleg) überschrieben; die Zahlen hat Copilot vorher mit seinem Auswertungsskript aus
dem Vollbericht gelesen, nachprüfen lassen sie sich nicht mehr.

Je Variante (Belege mit Art, Datum, Partner, Betrag richtig; Mittel und Spannweite der zwei Läufe):

| Variante | Gate-Belege | Art roh (Modell) → nach Nachbearbeitung | Umstufungen | Fehler ≥ 0.8 | kleinste Schwelle (≤ 1 Fehler) |
| --- | --- | --- | --- | --- | --- |
| V1 v3, OCR-Text | 19.0 (18–20) | 23 → 23; 26 → 26 | – | 10; 12 | keine ≤ 1.0; keine ≤ 1.0 |
| V2 v3, Bild für Fotos | 23.0 (22–24) | 23 → 26; 27 → 28 | – (Regel erst ab V3, Commit «Prompt v5 und engere Nachbearbeitung») | 9; 5 | keine ≤ 1.0; keine ≤ 1.0 |
| **V3 v5, Bild für Fotos** | **23.5 (21–26)** | 20 → 25; 21 → 26 | 0; 0 | 9; 6 | keine ≤ 1.0; keine ≤ 1.0 |
| V4 v6 (verworfen) | 20.5 (19–22¹) | 20 → 25; 19 → 24¹ | 0; 0¹ | 11; 6¹ | keine ≤ 1.0; keine ≤ 1.0¹ |

Tokens und Kosten je Lauf (30 Belege, Preise wie oben):
- V1: 35'814 ein / rund 9'300 aus, CHF 0.014 (alles Text).
- V2: 48'881 ein / rund 9'400 aus, CHF 0.017; davon 6 Bild-Belege 19'191 ein / rund 1'800 aus, CHF 0.005;
  24 Text-Belege 29'690 ein / rund 7'600 aus, CHF 0.012.
- V3: 53'921 ein / 8'943 bzw. 13'502 aus, CHF 0.018 bzw. 0.021; davon Bild-Belege 20'199 ein / rund 1'700 aus,
  CHF 0.005; Text-Belege 33'722 ein, CHF 0.012 bzw. 0.016. Im Lauf 1438 lieferte ein Beleg (40) 4'821 Ausgabe-Tokens.

Verbleibende Fehlschläge V3 (Beleg-ID: Feld):
- Lauf 1434: 03_quittung_restaurant.pdf: ART; 10_quittung_handschrift.pdf: DATUM;
  32_behoerde_versicherung_steuer_provisorisch.pdf: ART; 36_behoerde_versicherung_haftpflicht_police.pdf: DATUM;
  38_behoerde_versicherung_brief_ohne_betrag.pdf: BETRAG; 40_behoerde_versicherung_rueckerstattung.pdf: ART;
  42_handy_foto_drogerie_zerknittert.jpg: ART; 43_handy_foto_tankstelle_gedreht.jpg: PARTNER, BETRAG, BELEGNUMMER;
  48_handy_foto_ahv_schatten.heic: ART
- Lauf 1438: 03_quittung_restaurant.pdf: ART; 32_behoerde_versicherung_steuer_provisorisch.pdf: ART;
  40_behoerde_versicherung_rueckerstattung.pdf: BELEGNUMMER; 42_handy_foto_drogerie_zerknittert.jpg: ART;
  43_handy_foto_tankstelle_gedreht.jpg: ART, PARTNER, BELEGNUMMER

Befunde aus dem Nachschärfen (Fortsetzung der Nummerierung oben):

7. **Bildweg für Fotos ist der grösste Hebel.** V1 → V2: Gate-Belege im Mittel 19 → 23, Fehler ≥ 0.8 im Mittel 11 → 7,
   Kosten rund +20 %. Kontrolle: in allen V2- bis V4-Läufen liefen genau die 6 `HANDY_FOTO` als Bild (V4 Lauf 2
   nicht mehr nachprüfbar¹).
8. **v5 verschiebt den Rückfallwert der Art.** Mit fremden Arten zuerst meldet das Modell bei Unsicherheit
   `LIEFERANTENBELEG` statt `AUSGANGSRECHNUNG`: Die rohe Art sinkt (20–21 statt 23–27 richtig), auch eigene Rechnungen
   kommen roh als `LIEFERANTENBELEG`; QR und Belegnummer-Muster korrigieren sie (nach Nachbearbeitung 25–26). V3 liegt
   mit 23.5 im Mittel innerhalb der Laufschwankung von V2 (23.0); die Spannweite ist grösser (21–26).
   Beleg 28 (Offerte, Total ohne Monatspauschale) war in V2 zweimal falsch, in V3 zweimal richtig.
9. **Umstufung greift im Abstimmungsteil nicht (0 in allen V3- und V4-Läufen):** Unter v3 hätte es je Lauf 1–4
   Kandidaten gegeben (rohe Art `AUSGANGSRECHNUNG`/`OFFERTE` bei fremdem QR). Unter v5 meldet das Modell bei fremdem QR
   keine eigene Art mehr; der Fehler verschiebt sich zu sicherem `LIEFERANTENBELEG` bei Behördenbelegen mit QR (32, 48).
   Die Regel bleibt als Sicherung.
10. **Kennung im Kopf- oder Fussbereich:** Kontrolle bestanden, kein eigener Beleg verliert `mandantIstAussteller`
    (21, 24–28, 47). Grenze: Beim kurzen Rückerstattungsschreiben 40 (17 Zeilen, also ganze Seite) steht die
    Mandanten-IBAN als Gutschriftskonto zwei Zeilen über dem Seitenende; der Mandant gilt dort weiter als Aussteller.
    Ohne eigene Art oder passendes Muster wirkt sich das nicht aus; eine Zeilenregel trennt Fuss und Schlusssatz hier nicht.
11. **Req 0.9 bleibt der Engpass:** In allen acht Läufen gibt es keine Schwelle ≤ 1.0 mit höchstens einem Fehler. In V3
    kommen falsche Felder weiterhin mit Sicherheit 0.9–1.0, vor allem die Art (Quittung 03, Behördenschreiben 32, 40, 48,
    Foto 42). Die sicheren Art-Fehler bei 32 und 48 folgen aus dem Rückfallwert von v5 (Befund 8) und belasten Req 0.9.
12. **Kann 7 (v6, Schemafeld `absender` + Partner-Wache) verworfen:** V4 erreicht 19 und 22¹ Gate-Belege, unter dem
    V3-Mittel 23.5, und hat in einem Lauf mehr Fehler ≥ 0.8 (11). Der Entscheid gegen v6 stützt sich belegbar auf
    Lauf 1442 (19 < 23.5). Code zurückgenommen (Revert im PR).
13. **Generator-Befund (nur Code von `tools/golden_synth` gelesen):** `e28` erwartet das Total nach Rabatt ohne die
    Monatspauschale; keine andere Offerte trägt wiederkehrende Gebühren. Kein Behördenbrief nutzt die Beschriftung
    «Antrag» oder «Geschäftsnummer»; die Antrag-Regel ist synthetisch nicht messbar. Der Generator erwartet
    Sozialversicherungs-Schreiben (auch Rückerstattungen) als `BEHOERDE`, UVG und KTG als `VERSICHERUNG`; geprüft am
    Code von tools/golden_synth.
14. **Transport-Wiederholungen:** `max-retries=1` bedeutet in langchain4j 1.19.3 eine Wiederholung; 0 lehnt Quarkus ab.
    Genauigkeit nicht betroffen, kiMs und Kosten schon; in 1424 kiMs max. rund 186 s. Vorgekommen je einmal in den
    Läufen 1424 und 1445¹; die Pipeline zählte sie nicht (`aufrufe` blieb 1). Seit PR #15 nutzt der Prototyp ein eigenes
    `ChatModel` ohne Wiederholung (`KiModell`, `maxRetries(0)`; WireMock-Test: bei HTTP 502 genau ein Request).

**Vorläufig eingefroren: Prompt `v5-fb5c021d33fc`** (V3; Bildweg `fotos`; Nachbearbeitung wie in PR #15). Kein
Gate-Lauf vor Abschluss von Aufgabe 0.5 Gate-Vorbereitung (Req 0.9 neu, Regeln R3/E1/E2, Modellvergleich). Der
Gate-Lauf läuft auf dem Commit, mit dem Prompt-Hash, Modell und Nachbearbeitung eingefroren werden (Merge-Commit des
PR, der sie zuletzt festlegt), ohne spätere Änderungen im Spike. Abgelöst durch «Eingefroren für den Gate-Lauf» im
Abschnitt «Gate-Vorbereitung» (PR #21).

## Gate-Vorbereitung (09.11.2026, PR #21)

Req 0.9 neu (Sicherheit nach der deterministischen Nachbearbeitung; Festlegung nur auf dem Abstimmungsteil, Einfrieren
vor dem Gate-Lauf). Drei neue Prototyp-Regeln nach der KI-Antwort (Fabian, 09.11.2026; Nachbearbeitung `nb-2026-11-09`,
Eingangsdatum `2026-11-10`). **Alle drei Regeln wurden auf dem Abstimmungsteil gefunden und gemessen; Überanpassung ist
möglich.**
- **R3:** Belegdatum strikt nach dem Eingangsdatum → Datum-Sicherheit 0 (ersetzt «mehr als 1 Jahr in der Zukunft»).
- **E1:** Betrag 0 des Modells ohne QR-Betrag → leer.
- **E2:** `LIEFERANTENBELEG` (roh und nach Muster/QR) ohne Swiss QR Code, ohne `partnerIban`, ohne Fälligkeit →
  `QUITTUNG`, Art-Sicherheit 0.5.

Modellvergleich mit Prompt v5 (`v5-fb5c021d33fc`), Bild für Fotos, je zwei volle Läufe. Preise laut
[Infomaniak-Preisliste](https://www.infomaniak.com/en/hosting/ai-services/prices), abgerufen 09.11.2026, CHF je 1 Mio.
Tokens ein/aus, exkl. MWST: Mistral-Small-4 0.20/0.75, Qwen3.5-397B-A17B-FP8 0.80/3.60, gemma-4-31B-it 0.20/0.40.
Vorab-Test (`--limit 1` als Text und `--limit 1 --k1`): Qwen und gemma akzeptieren strict `json_schema`, Bild-Eingabe
und füllen die Sicherheitsfelder.

| Lauf | Variante | Modell | Art | Datum | Partner | Betrag | Beleg-Nr. | Felder | Gate-Belege | Fehler ≥ 0.8 | kleinste Schwelle |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-09-162856 | M1 | Mistral-Small-4 | 29 | 30 | 28 | 29 | 28 | 144/150 | 27/30 | 5 | keine ≤ 1.0 |
| 2026-11-09-164304 | M1 | Mistral-Small-4 | 29 | 29 | 27 | 28 | 29 | 142/150 | 24/30 | 8 | keine ≤ 1.0 |
| 2026-11-09-164900 | M2 | Qwen3.5-397B-A17B-FP8 | 30 | 30 | 30 | 30 | 30 | 150/150 | 30/30 | 0 | 0.00 |
| 2026-11-09-170820 | M2 | Qwen3.5-397B-A17B-FP8 | 28 | 28 | 28 | 28 | 28 | 140/150 | 28/30 | 0 | 0.00 |
| 2026-11-09-163532 | M3 | gemma-4-31B-it | 30 | 30 | 30 | 30 | 29 | 149/150 | 30/30 | 1 | 0.00 |
| 2026-11-09-163748 | M3 | gemma-4-31B-it | 30 | 30 | 30 | 30 | 29 | 149/150 | 30/30 | 1 | 0.00 |
| 2026-11-09-170735 | M3 Bestätigung | gemma-4-31B-it | 30 | 30 | 30 | 30 | 29 | 149/150 | 30/30 | 1 | 0.00 |
| 2026-11-09-170952 | M3 Bestätigung | gemma-4-31B-it | 30 | 30 | 30 | 30 | 29 | 149/150 | 30/30 | 1 | 0.00 |

Ein weiterer M1-Lauf (Ordner `2026-11-09-163315`) brach ohne Bericht ab; die Ursache ist nicht erfasst, weil die
Konsolenausgabe gefiltert war. Er zählt nicht; seine Kosten sind unten geschätzt.

| Variante | Gate-Belege Mittel (Spannweite) | Fehler ≥ 0.8 | Umstufungen QR; E2 | R3-Treffer (davon richtig) | «bitte prüfen» bei 0.8 (davon richtig) | Kosten je Lauf | KI je Beleg |
| --- | --- | --- | --- | --- | --- | --- | --- |
| M1 Mistral-Small-4 | 25.5 (24–27) | 5; 8 | 0; 5 bzw. 0; 4 | 0; 0 | 10 (9); 9 (9) | CHF 0.018 | 8.3 bzw. 6.1 s |
| M2 Qwen3.5-397B-A17B-FP8 | 29.0 (28–30) | 0; 0 | 0; 0 bzw. 0; 0 | 0; 0 | 2 (2); 2 (2) | CHF 0.37 bzw. 0.33² | 38.4 bzw. 46.0 s |
| **M3 gemma-4-31B-it** | **30.0 (30–30)** | 1; 1 | 0; 0 bzw. 0; 0 | 0; 0 | 1 (1); 1 (1) | CHF 0.011 | 4.2 s |
| M3 Bestätigung | 30.0 (30–30) | 1; 1 | 0; 0 bzw. 0; 0 | 0; 0 | 1 (1); 1 (1) | CHF 0.011 | 4.2 bzw. 3.5 s |

² Im Lauf 170820 liefen zwei Belege (21, 32) nach 180 s in den Timeout und blieben ohne Vorschlag; ihre Tokens sind
unbekannt, die Kosten also höher als ausgewiesen.

Befunde aus der Gate-Vorbereitung (Fortsetzung der Nummerierung oben):

15. **gemma-4-31B-it ist im Abstimmungsteil am stabilsten:** vier Mal 30/30 Gate-Belege, je genau ein falsches Feld
    (Beleg 38, Belegnummer, Sicherheit 1.0), kleinste Schwelle 0.00; Ausgabe fast gleich zwischen den Läufen. Rund
    CHF 0.011 je 30 Belege, KI rund 4 s je Beleg; Bild-Belege brauchen weniger Eingabe-Tokens als bei Mistral.
16. **Qwen3.5-397B macht keinen sicheren Fehler, ist aber langsam und teuer:** 30/30 und 28/30, in beiden Läufen kein
    falsches Feld ≥ 0.8. Das Modell denkt laut (rund 80'000–90'000 Ausgabe-Tokens je Lauf), daher CHF 0.33–0.37 je
    30 Belege und 38–46 s je Beleg; im zweiten Lauf zwei Timeouts nach 180 s (Belege ohne Vorschlag).
17. **Mistral-Small-4 bleibt unter dem Gate:** 27/30 und 24/30, 5 bzw. 8 Fehler ≥ 0.8, keine Schwelle ≤ 1.0 mit
    höchstens einem Fehler. Foto 43 liefert in beiden Läufen drei falsche Felder mit Sicherheit 1.0.
18. **Wirkung der Regeln:** E2 stufte bei Mistral 5 bzw. 4 Belege zu `QUITTUNG` um, alle richtig; bei Qwen und gemma
    griff E2 nie (sie melden Quittungen direkt). R3 traf in keinem Lauf. E1 ist nicht gesondert messbar, weil der
    rohe Betrag des Modells nicht im Vollbericht steht. QR-Umstufungen: keine.
19. **Ein M1-Lauf brach ohne Bericht ab** (Ordner `2026-11-09-163315`, leer); die Ursache ist nicht erfasst. Er
    zählt nicht; die Wiederholung (164304) lief normal.

**Kosten** aller Läufe dieses Auftrags: CHF 0.79 laut Berichten, dazu der abgebrochene M1-Lauf (höchstens rund
CHF 0.02) und die Tokens der zwei Qwen-Timeouts (unbekannt); deutlich unter dem Kostendach von CHF 2.–.

**Auswahl (Kriterium: in beiden Läufen Schwelle ≤ 1.0 mit höchstens 1 Fehler, Mittel ≥ 27/30, kein Lauf unter 26/30):**
- M1 Mistral-Small-4: nicht gate-reif (keine Schwelle ≤ 1.0, Mittel 25.5).
- M2 Qwen3.5-397B: gate-reif (Mittel 29.0, min. 28, Schwelle 0.00 in beiden Läufen); nicht bestätigt.
- M3 gemma-4-31B-it: gate-reif (Mittel 30.0, Schwelle 0.00) und in zwei weiteren Läufen bestätigt (30/30, 30/30).

**Entscheid Fabian (09.11.2026): M3 gemma-4-31B-it, Schwelle 0.8** (`max(0.8, 0.00)`). Bei 0.8 stehen 1 von 149
richtigen Feldern auf «bitte prüfen» (0.7 %). Gründe: vier gleich gute Läufe, keine Timeouts, Kosten und Laufzeit rund
einen Zehntel bis Dreissigstel von Qwen. Risiko: Der wiederkehrende sichere Fehler verbraucht den einen erlaubten Fehler
(Req 0.9); ein zweiter sicherer Fehler im Prüfteil liesse das Gate scheitern. Qwen hat dieses Risiko nicht, dafür
Timeouts (ein Beleg ohne Vorschlag zählt als falsch) und rund 30-fache Kosten.

**Eingefroren für den Gate-Lauf:** Prompt `v5-fb5c021d33fc`, Modell `google/gemma-4-31B-it`, Nachbearbeitung `nb-2026-11-09`,
Eingangsdatum `2026-11-10`, Schwelle 0.8, Commit = Merge-Commit von PR #21; alle Werte sind Standard in
`application.properties` (Kontroll-Lauf `--limit 1` ohne Startparameter zeigt sie in der Konsolenzeile).

## Gate-Lauf (Prüfteil, 09.11.2026)

Lauf `2026-11-09-185812-gate`, gestartet von Fabian auf dem Merge-Commit von PR #21 (`2c36441`) ohne Zusatzparameter.
Konsolenzeile und `kennzahlen.md` zeigen die eingefrorenen Werte: Prompt `v5-fb5c021d33fc`, Modell
`google/gemma-4-31B-it`, Nachbearbeitung `nb-2026-11-09`, Eingangsdatum `2026-11-10`, Schwelle 0.8 (≥).

| Art | Datum | Partner | Betrag | Beleg-Nr. | Felder | Gate-Belege | Fehler ≥ 0.8 | kleinste Schwelle |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 20 | 20 | 20 | 20 | 19 | 99/100 | 20/20 | 1 | 0.00 |

- Belege 20, ohne Vorschlag 0, Nachfragen 0, Swiss QR gelesen 10; Eingabe Bild 4, Text 16 (Kontrolle `HANDY_FOTO` als
  Bild: ok).
- Art roh (Modell) 20/20, nach Nachbearbeitung 20/20; Umstufungen keine; R3 ohne Treffer; «bitte prüfen» bei 0.8: 0
  Felder. Der eine Fehler ist eine Belegnummer mit Sicherheit ≥ 0.8; er steht damit nicht auf «bitte prüfen».
- Tokens 26'166 ein / 4'900 aus, Kosten ca. CHF 0.0072; KI im Mittel 3.3 s je Beleg, OCR 3.4 s (inkl. Cache).
- Beleg-IDs des Prüfteils stehen nur im Bericht unter `$ARCHIV_GOLDEN_DIR` (CLAUDE.md Regel 9).

**Ergebnis:** Req 0.3 (≥ 18/20) und Req 0.9 (höchstens 1 Fehler ab der Schwelle) sind erfüllt. Wie im Abstimmungsteil
war das einzige falsche Feld eine Belegnummer mit hoher Sicherheit; der eine erlaubte Fehler ist damit ausgeschöpft.
Befund für Welle 3 (KI-Vorschläge im Produkt): Die Belegnummer ist in Abstimmungs- und Prüfteil die einzige Fehlerquelle
mit hoher Sicherheit; in Aufgabe 3.2 gezielt prüfen, ohne den eingefrorenen Stand des Prototyps zu ändern. Abnahme durch Fabian: `docs/umsetzungsplan.md`, Abschnitt 6.

## Offen für Gate 0 (Aufgabe 0.6, Fabian)

Gate-Lauf in fünf Schritten (auch im [README](../../spikes/golden-lauf/README.md)):
1. Commit = Merge-Commit von PR #21 (Gate-Vorbereitung), ohne spätere Änderungen im Spike; `cd spikes/golden-lauf && mvn package`.
   Kein Gate-Lauf vor dem Merge von PR #21.
2. Schwelle 0.8 (entschieden von Fabian am 09.11.2026; gemma-4-31B-it, kleinste Schwelle 0.00 in vier Läufen).
3. `java -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token -jar target/quarkus-app/quarkus-run.jar --gate`.
   Modell, Preise, Schwelle, Eingangsdatum und Bildweg für Fotos sind Standard. Zusatzparameter machen den Lauf ungültig.
4. Konsolenzeile mit den eingefrorenen Werten vergleichen: Prompt `v5-fb5c021d33fc`, Modell `google/gemma-4-31B-it`,
   Nachbearbeitung `nb-2026-11-09`, Eingangsdatum `2026-11-10`; Schwelle 0.8 in `kennzahlen.md`. Weicht einer ab, gilt
   der Lauf nicht. Der Hash deckt Vorlagen, Schema, ArtCodes und Bildweg ab, nicht
   QR-Format, Kürzung (`MAX_ZEICHEN`), Bildaufbereitung (`Seitenbild`) und Modell/Parameter; massgeblich ist deshalb
   auch der Commit nach Schritt 1.
5. Kennzahlen des Prüfteils hier eintragen. «Phase 0 bestanden» bei mindestens 18 von 20 Belegen mit Art, Datum,
   Partner, Betrag richtig und erfüllter Req 0.9 (Req 0.3). Meldet der Prüfteil «keine ≤ 1.0», ist das Gate nicht
   bestanden (Req 0.9); wie es weitergeht, entscheidet Fabian.

Weiter offen:
- Abstand zum Gate: erledigt, Gate-Lauf oben (20/20 im Prüfteil).
- Modellwechsel: umgesetzt in PR #22. gemma-4-31B-it gilt nur für die Erfassung (`archiv.ki.erfassung-modell`), Chat
  und Wissensbasis bleiben bei Mistral-Small-4 (entschieden von Fabian am 09.11.2026). Req 0.5, Aufgabe 0.3 und Spec 04
  bleiben unverändert, weil sie Chat-Modell und Tool Calling betreffen.
- Realitätsprüfung: 20 echte Belege bestätigt, 0 ausgeschlossen (Fabian, 09.11.2026, 14:35); `--realitaet` noch nicht
  gelaufen (sendet bei Bild-Originalen die gerenderten Seiten an Infomaniak AI).
- Welche Bildfassung das Produkt sendet: entschieden von Fabian am 09.11.2026, design.md «KI», Bildweg.
- «Geschäftsnummer» als Beschriftung der Belegnummer (nur Vorschlag, nicht umgesetzt).
- Historie: Die Git-Historie enthält einen frühen Commit, der die Zugehörigkeit von drei Belegen zum Prüfteil mit
  erwarteter Art nennt; die erwartete Art ergibt sich ohnehin aus dem Generator-Code. Entscheid Fabian 09.11.2026:
  akzeptiert, keine Umschreibung; Agenten durchsuchen die Git-Historie nicht nach Golden-Daten (CLAUDE.md Regel 9).
