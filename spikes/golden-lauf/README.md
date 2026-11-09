# golden-lauf – Prototyp-Pipeline auf dem Golden-Datensatz (Aufgabe 0.5)

Kommandozeilen-App (Java 25, Quarkus 3.40.1, `quarkus-langchain4j-openai`). Je Beleg: Datei → OCRmyPDF im lokalen
Docker-Image → `pdftotext` → `pdftoppm -r 300` → Swiss QR Code (ZXing) → genau ein KI-Aufruf bei Infomaniak
(`json_schema` strict) → Nachbearbeitung → Vergleich nach Req 0.7. Ist das Original ein Bild (JPEG, PNG, HEIC/HEIF, TIFF;
Typ am Dateiinhalt erkannt, nie an Endung oder `kategorie`), erhält das Modell statt des OCR-Texts die von OCRmyPDF
gerenderten Seiten (Req 7.1, Bildweg `fotos`); die OCR läuft trotzdem für Volltext und QR-Code. Kennzahlen:
[docs/spikes/golden-ergebnis.md](../../docs/spikes/golden-ergebnis.md).

## Voraussetzungen

- Docker. OCR-Image einmal bauen (OCRmyPDF 17.13.0 + Italienisch + poppler-utils, Container ohne Netzwerk):
  ```sh
  docker build -t golden-ocr:17.13.0 spikes/golden-lauf/ocr
  ```
  `pdftotext` und `pdftoppm` laufen im Container, `brew install poppler` ist nicht nötig. HEIC wandelt macOS mit
  `sips -s format jpeg` vor der OCR. Gepinnt ist nur der Basis-Tag `jbarlow83/ocrmypdf:v17.13.0`; die apt-Pakete
  (`tesseract-ocr-ita`, `poppler-utils`) sind nicht versioniert und entsprechen dem Stand beim Bauen. Der Container
  läuft wie das Basis-Image als Benutzer `app` (uid 1000).
- Token als Datei (Regel 7), nie als Umgebungsvariable: `-Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token`.
- `ARCHIV_GOLDEN_DIR` (Standard `~/urinet-archiv-golden`) mit `synthetisch/erwartet.json` (ab golden_synth mit Kopfeintrag
  `mandant`), optional `erwartet.json` und `mandant-kennungen.json` für die echten Belege. Docker muss diesen Ordner
  einbinden dürfen (Docker Desktop: Ordner unter dem Home-Verzeichnis).
- Start im Repository (die App liest die Dokumentarten aus `.kiro/specs/01-archiv-kern/design.md`).

## Aufruf

```sh
cd spikes/golden-lauf
mvn package
java -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token -jar target/quarkus-app/quarkus-run.jar [Schalter]
```

| Schalter | Belege | Zweck |
| --- | --- | --- |
| (keiner) | 30 synthetische, `teil = abstimmen` | Prompt und Schwelle abstimmen (Req 0.8) |
| `--gate` | 20 synthetische, `teil = zurueckgehalten` | Gate-Lauf; **startet nur Fabian**, nie zum Abstimmen |
| `--realitaet` | echte Belege mit `bestaetigt: true` ohne Prüfmarke | Realitätsprüfung (Req 0.10), zählt nie für das Gate; Bild-Originale gehen als gerenderte Seiten an Infomaniak AI |
| `--k1` | wie ohne Schalter | Variante K1: jeder Beleg als Seitenbild (Bildweg `alle`, nur Abstimmungsteil) |
| `--limit N` | erste N | Probelauf |

Vor dem ersten Beleg bricht die App ab, wenn `archiv.ki.basis-url` (verwendet vom Client `KiModell`) oder
`quarkus.langchain4j.openai.base-url` nicht per `https` exakt auf `api.infomaniak.com` zeigt (Req 16.22). Kein Profil
`lokal`, kein LM Studio (ADR-012). Je Aufruf geht genau ein Request raus: `KiModell` setzt `maxRetries(0)`, weil
`quarkus.langchain4j.openai.max-retries` den Wert 0 ablehnt und 1 eine Wiederholung bedeutet. Eine Nachfrage (Req 7.3)
gibt es nur bei Parse- oder Schemafehlern; Timeouts und HTTP-Fehler zählen als Fehlschlag mit Grund.

## Prompt-Hash

Die Konsole zeigt `Prompt v5-<12 Hex>`. Der Digest deckt den fertig gerenderten System-Prompt (Arten-Liste mit
Anzeigetexten, Reihenfolge, Mandantenname, Markierungen aus `golden.eigene-arten`), die Benutzer-, Nachfrage- und
K1-Vorlage (`BENUTZER_K1`, Bildweg), das erzeugte JSON-Schema, die Reihenfolge von `ArtCode` und den Bildweg (`fotos`, bei `--k1`
`alle`) ab. Der System-Prompt wird in Java gerendert und fertig an das Modell übergeben; gehashter und gesendeter Text
sind dadurch gleich. Abstimmen und `--gate` nutzen denselben synthetischen Mandanten und haben denselben Hash; bei
`--realitaet` ist er gleich, solange `golden.mandant-name` für beide Teile gilt (Lauf 2026-11-09-191327). Nicht abgedeckt sind QR-Format, Kürzung
(`MAX_ZEICHEN`), Bildaufbereitung (`Seitenbild`) und Modell/Parameter; massgeblich sind deshalb auch der Commit
(Gate-Lauf Schritt 1) und das Modell in `kennzahlen.md`.

## Nachbearbeitung (nur Prototyp)

- Ein Swiss QR Code mit IBAN ausserhalb `mandant.kennungen` schliesst den Mandanten als Aussteller aus.
- Sonst ist der Mandant Aussteller, wenn der QR-Zahlungsempfänger seine IBAN trägt oder seine IBAN/UID im Kopf- oder
  Fussbereich einer Seite steht: die ersten und letzten `KOPF_FUSS_ZEILEN = 10` nicht leeren Zeilen (Seiten durch
  Seitenvorschub getrennt). Begründung für 10: Briefkopf mit Firma, Adresse, Telefon, E-Mail, UID/MWST und die
  Fusszeile mit Bankverbindung passen in je 10 Zeilen; Anrede, Positionen und Zahlungshinweise (z. B. «Gutschrift auf
  Ihr Konto …») liegen bei einseitigen Briefen meist dazwischen. Bei sehr kurzen Seiten (bis 20 Zeilen) gilt die
  ganze Seite.
- Eigene Art ohne Kennung des Mandanten: Sicherheit der Art 0; bei fremdem QR-Zahlungsempfänger werden
  `AUSGANGSRECHNUNG` und `OFFERTE` zu `LIEFERANTENBELEG` (Sicherheit 0.5, Standard-Vertraulichkeit), `LOHNABRECHNUNG`
  nie. `mandant.kennungen.ibans` muss auch die QR-IBANs des Mandanten enthalten.
- R3: Belegdatum strikt nach `golden.eingangsdatum` (Standard `2026-11-10`) → Datum-Sicherheit 0; der gleiche Tag nicht.
- E1: Betrag 0 des Modells ohne QR-Betrag → leer (ein QR-Betrag, auch 0.00, bleibt).
- E2: rohe und nachbearbeitete Art `LIEFERANTENBELEG`, kein Swiss QR Code, keine `partnerIban`, keine Fälligkeit und
  Art nicht vom Belegnummer-Muster gesetzt → `QUITTUNG` mit Art-Sicherheit 0.5.
- R3, E1 und E2 sind Prototyp-Regeln (Fabian, 09.11.2026), auf dem Abstimmungsteil gefunden und gemessen.

## Laufidentität

Konsole, `kennzahlen.md` und `ergebnisse.json` nennen Prompt-Hash, Modell (`archiv.ki.chat-modell`), Fassung der
Nachbearbeitung (`Nachbearbeitung.FASSUNG`, bei jeder Regeländerung hochgezählt) und `golden.eingangsdatum`.

## Gate-Lauf (nur Fabian)

1. Commit = Merge-Commit von PR #21 (Gate-Vorbereitung), ohne spätere Änderungen im Spike; `cd spikes/golden-lauf && mvn package`.
   Kein Gate-Lauf vor dem Merge von PR #21.
2. Schwelle 0.8 (entschieden von Fabian am 09.11.2026; gemma-4-31B-it, kleinste Schwelle 0.00 in vier Läufen).
3. `java -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token -jar target/quarkus-app/quarkus-run.jar --gate`.
   Modell, Preise, Schwelle, Eingangsdatum und Bildweg für Fotos sind Standard. Zusatzparameter machen den Lauf ungültig.
4. Konsolenzeile mit den eingefrorenen Werten vergleichen: Prompt `v5-fb5c021d33fc`, Modell `google/gemma-4-31B-it`,
   Nachbearbeitung `nb-2026-11-09`, Eingangsdatum `2026-11-10`; Schwelle 0.8 in `kennzahlen.md`. Weicht einer ab, gilt
   der Lauf nicht.
   Der Hash deckt Vorlagen, Schema, ArtCodes und Bildweg ab, nicht QR-Format, Kürzung, Bildaufbereitung und
   Modell/Parameter; massgeblich ist deshalb auch der Commit nach Schritt 1.
5. Kennzahlen des Prüfteils in golden-ergebnis.md eintragen. Bestanden bei mindestens 18/20 Belegen mit Art, Datum,
   Partner, Betrag richtig und erfüllter Req 0.9 (Req 0.3); «keine ≤ 1.0» im Prüfteil heisst nicht bestanden.

## Ausgabe

- Konsole: nur Beleg-ID (synthetisch Dateiname, echt die ersten 8 Zeichen des SHA-256 des Dateinamens), Feld, richtig/falsch.
- `$ARCHIV_GOLDEN_DIR/berichte/<JJJJ-MM-TT-HHMMSS>-<modus>[-k1][-limitN]/` (Startzeitpunkt): `ergebnisse.json`
  (Vollbericht je Beleg) und `kennzahlen.md` (nur Zahlen und IDs, für `docs/spikes/golden-ergebnis.md`). Der Ordner
  entsteht vor dem ersten KI-Aufruf; gibt es ihn schon, bricht der Lauf ab, statt einen Bericht zu überschreiben.
- OCR-Cache: `$ARCHIV_GOLDEN_DIR/.cache-golden-lauf/` (Text, Seitenbilder je Dateiinhalt). Ein Eintrag gilt erst mit der
  Datei `fertig` als vollständig; schlägt ein OCR-Schritt fehl, wird der Arbeitsordner gelöscht. Enthält Kopien der
  Belege und bleibt deshalb im Golden-Ordner; zum Löschen den Ordner entfernen.

## Tests

```sh
mvn verify                                     # Unit-Tests, ohne Netz und ohne Golden-Daten
mvn test -Dtest=GoldenDatensatzIT -Dgolden=true -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token
```

`GoldenDatensatzIT` läuft nur manuell (nie in der CI) auf dem Abstimmungsteil und wird ohne Token-Datei oder Golden-Daten übersprungen.
