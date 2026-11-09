# golden-lauf – Prototyp-Pipeline auf dem Golden-Datensatz (Aufgabe 0.5)

Kommandozeilen-App (Java 25, Quarkus 3.40.1, `quarkus-langchain4j-openai`). Je Beleg: Datei → OCRmyPDF im lokalen
Docker-Image → `pdftotext` → `pdftoppm -r 300` → Swiss QR Code (ZXing) → genau ein KI-Aufruf bei Infomaniak
(`json_schema` strict) → Nachbearbeitung → Vergleich nach Req 0.7. Kennzahlen: [docs/spikes/golden-ergebnis.md](../../docs/spikes/golden-ergebnis.md).

## Voraussetzungen

- Docker. OCR-Image einmal bauen (OCRmyPDF 17.13.0 + Italienisch + poppler-utils, Container ohne Netzwerk):
  ```sh
  docker build -t golden-ocr:17.13.0 spikes/golden-lauf/ocr
  ```
  `pdftotext` und `pdftoppm` laufen im Container, `brew install poppler` ist nicht nötig. HEIC wandelt macOS mit
  `sips -s format jpeg` vor der OCR.
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
| `--realitaet` | echte Belege mit `bestaetigt: true` ohne Prüfmarke | Realitätsprüfung (Req 0.10), zählt nie für das Gate |
| `--k1` | wie ohne Schalter | Variante K1: Seitenbild statt OCR-Text (nur Abstimmungsteil) |
| `--limit N` | erste N | Probelauf |

Vor dem ersten Beleg bricht die App ab, wenn `archiv.ki.basis-url` nicht per `https` exakt auf `api.infomaniak.com`
zeigt (Req 16.22). Kein Profil `lokal`, kein LM Studio (ADR-012).

## Ausgabe

- Konsole: nur Beleg-ID (synthetisch Dateiname, echt die ersten 8 Zeichen des SHA-256 des Dateinamens), Feld, richtig/falsch.
- `$ARCHIV_GOLDEN_DIR/berichte/<JJJJ-MM-TT-HHMM>-<modus>[-k1]/`: `ergebnisse.json` (Vollbericht je Beleg) und
  `kennzahlen.md` (nur Zahlen und IDs, für `docs/spikes/golden-ergebnis.md`).
- OCR-Cache: `$ARCHIV_GOLDEN_DIR/.cache-golden-lauf/` (Text, Seitenbilder je Dateiinhalt). Enthält Kopien der Belege und
  bleibt deshalb im Golden-Ordner; zum Löschen den Ordner entfernen.

## Tests

```sh
mvn verify                                     # Unit-Tests, ohne Netz und ohne Golden-Daten
mvn test -Dtest=GoldenDatensatzIT -Dgolden=true -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token
```

`GoldenDatensatzIT` läuft nur manuell (nie in der CI) auf dem Abstimmungsteil und wird ohne Token-Datei oder Golden-Daten übersprungen.
