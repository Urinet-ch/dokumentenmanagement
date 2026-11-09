# Golden-Lauf (Aufgabe 0.5) – Kennzahlen

Stand 09.11.2026 · Req 0.2, 0.3, 0.7–0.10, 7.1, 7.11, 7.12, 16.22 · App: [`spikes/golden-lauf/`](../../spikes/golden-lauf/)

Im Repository stehen nur Kennzahlen und Beleg-IDs (synthetisch: Dateiname). Die Vollberichte mit Erwartung,
Vorschlag und Sicherheit je Beleg liegen in `$ARCHIV_GOLDEN_DIR/berichte/<Zeitpunkt>-<Modus>/`.

## Kurz

- **Prüfteil (Gate, Req 0.3/0.9): noch nicht gelaufen.** Den Gate-Lauf (`--gate`, 20 zurückgehaltene Belege) startet Fabian.
- **Nachschärfen (09.11.2026, unten):** Prompt v5 mit Bild für Fotos und engerer Nachbearbeitung, 21 und 26/30 Belege
  mit Art, Datum, Partner, Betrag richtig (Mittel 23.5); in keinem Lauf eine Schwelle ≤ 1.0 mit höchstens einem Fehler.
  Eingefroren für den Gate-Lauf: `v5-fb5c021d33fc`.
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
| 2026-11-09-1445 | V4 `v6-35426948c145` (verworfen) | Bild für Fotos | 24 | 29 | 27 | 29 | 29 | 138/150 | 22/30 | 6 |

Je Variante (Belege mit Art, Datum, Partner, Betrag richtig; Mittel und Spannweite der zwei Läufe):

| Variante | Gate-Belege | Art roh (Modell) → nach Nachbearbeitung | Umstufungen | Fehler ≥ 0.8 | kleinste Schwelle (≤ 1 Fehler) |
| --- | --- | --- | --- | --- | --- |
| V1 v3, OCR-Text | 19.0 (18–20) | 23 → 23; 26 → 26 | – | 10; 12 | keine ≤ 1.0; keine ≤ 1.0 |
| V2 v3, Bild für Fotos | 23.0 (22–24) | 23 → 26; 27 → 28 | 0; 0 | 9; 5 | keine ≤ 1.0; keine ≤ 1.0 |
| **V3 v5, Bild für Fotos** | **23.5 (21–26)** | 20 → 25; 21 → 26 | 0; 0 | 9; 6 | keine ≤ 1.0; keine ≤ 1.0 |
| V4 v6 (verworfen) | 20.5 (19–22) | 20 → 25; 19 → 24 | 0; 0 | 11; 6 | keine ≤ 1.0; keine ≤ 1.0 |

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
   Kosten rund +20 %. Kontrolle: in allen V2- bis V4-Läufen liefen genau die 6 `HANDY_FOTO` als Bild.
8. **v5 verschiebt den Rückfallwert der Art.** Mit fremden Arten zuerst meldet das Modell bei Unsicherheit
   `LIEFERANTENBELEG` statt `AUSGANGSRECHNUNG`: Die rohe Art sinkt (20–21 statt 23–27 richtig), auch eigene Rechnungen
   kommen roh als `LIEFERANTENBELEG`; QR und Belegnummer-Muster korrigieren sie (nach Nachbearbeitung 25–26). V3 liegt
   mit 23.5 im Mittel innerhalb der Laufschwankung von V2 (23.0); die Spannweite ist grösser (21–26).
   Beleg 28 (Offerte, Total ohne Monatspauschale) war in V2 zweimal falsch, in V3 zweimal richtig.
9. **Umstufung greift im Abstimmungsteil nicht (0 in allen Läufen):** Mit v5 meldet das Modell bei fremdem QR keine
   eigene Art mehr. Die Regel bleibt als Sicherung.
10. **Kennung im Kopf- oder Fussbereich:** Kontrolle bestanden, kein eigener Beleg verliert `mandantIstAussteller`
    (21, 24–28, 47). Grenze: Beim kurzen Rückerstattungsschreiben 40 (17 Zeilen, also ganze Seite) steht die
    Mandanten-IBAN als Gutschriftskonto zwei Zeilen über dem Seitenende; der Mandant gilt dort weiter als Aussteller.
    Ohne eigene Art oder passendes Muster wirkt sich das nicht aus; eine Zeilenregel trennt Fuss und Schlusssatz hier nicht.
11. **Req 0.9 bleibt der Engpass:** In allen acht Läufen gibt es keine Schwelle ≤ 1.0 mit höchstens einem Fehler. In V3
    kommen falsche Felder weiterhin mit Sicherheit 0.9–1.0, vor allem die Art (Quittung 03, Behördenschreiben 32, 40, 48,
    Foto 42).
12. **Kann 7 (v6, Schemafeld `absender` + Partner-Wache) verworfen:** V4 erreicht 19 und 22 Gate-Belege, unter dem
    V3-Mittel 23.5, und hat in einem Lauf mehr Fehler ≥ 0.8 (11). Code zurückgenommen (Revert im PR).
13. **Generator-Befund (nur Code von `tools/golden_synth` gelesen):** `e28` erwartet das Total nach Rabatt ohne die
    Monatspauschale; keine andere Offerte trägt wiederkehrende Gebühren. Kein Behördenbrief nutzt die Beschriftung
    «Antrag» oder «Geschäftsnummer»; die Antrag-Regel ist synthetisch nicht messbar. Sozialversicherungs-Schreiben
    (31, 39, 40 inkl. Rückerstattung) sind als `BEHOERDE`, UVG und KTG (34, 35, 37) als `VERSICHERUNG` erwartet.
14. **Wiederholungen:** `quarkus.langchain4j.openai.max-retries=1` schickt nach Timeout oder HTTP 502 einen zweiten
    Request (je einmal in den Läufen 1424 und 1445). Für Req 7.1 («einmal») im Produkt beachten.

**Eingefroren für den Gate-Lauf: Prompt `v5-fb5c021d33fc`** (V3; Bildweg `fotos`; Nachbearbeitung wie in PR #15).

## Offen für Gate 0 (Aufgabe 0.6, Fabian)

Gate-Lauf in fünf Schritten (auch im [README](../../spikes/golden-lauf/README.md)):
1. main mit PR #15, `cd spikes/golden-lauf && mvn package`.
2. Schwelle festlegen. Regel: `max(0.8, kleinste Schwelle)` mit der grösseren kleinsten Schwelle der zwei V3-Läufe.
   **Kein Vorschlag:** Beide V3-Läufe melden «keine ≤ 1.0». Den Wert setzt Fabian (`-Darchiv.ki.schwelle=<wert>`,
   nie unter 0.8, Req 7.6, 0.9).
3. `java -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token -Darchiv.ki.schwelle=<wert> -jar target/quarkus-app/quarkus-run.jar --gate`
   (ohne Zusatzschalter, der Bildweg für Fotos ist Standard).
4. Prompt-Hash der Konsole mit `v5-fb5c021d33fc` vergleichen; weicht er ab, gilt der Lauf nicht.
5. Kennzahlen des Prüfteils hier eintragen. «Phase 0 bestanden» bei mindestens 18 von 20 Belegen mit Art, Datum,
   Partner, Betrag richtig und erfüllter Req 0.9 (Req 0.3). Meldet der Prüfteil «keine ≤ 1.0», ist das Gate nicht
   bestanden (Req 0.9); wie es weitergeht, entscheidet Fabian.

Weiter offen:
- Abstand zum Gate: V3 erreicht im Abstimmungsteil 21–26/30 (hochgerechnet 14–17/20), das Gate verlangt 18/20.
- Bestätigung echter Belege für die Realitätsprüfung (`bestaetigt: true`, Prüfmarken auflösen), danach `--realitaet`
  (sendet bei Bild-Originalen die gerenderten Seiten an Infomaniak AI).
- Welche Bildfassung das Produkt sendet (Rohaufnahme oder Arbeitsfassung), design.md «KI», Bildweg.
- «Geschäftsnummer» als Beschriftung der Belegnummer (nur Vorschlag, nicht umgesetzt).
