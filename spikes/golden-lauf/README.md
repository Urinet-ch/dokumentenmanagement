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

Vor dem ersten Beleg bricht die App ab, wenn `archiv.ki.basis-url` oder die vom Client tatsächlich verwendete
`quarkus.langchain4j.openai.base-url` nicht per `https` exakt auf `api.infomaniak.com` zeigt (Req 16.22). Kein Profil
`lokal`, kein LM Studio (ADR-012). Eine Nachfrage (Req 7.3) gibt es nur bei Parse- oder Schemafehlern; Timeouts und
HTTP-Fehler zählen als Fehlschlag mit Grund.

## Prompt-Hash

Die Konsole zeigt `Prompt v5-<12 Hex>`. Der Digest deckt den fertig gerenderten System-Prompt (Arten-Liste mit
Anzeigetexten, Reihenfolge, Mandantenname, Markierungen aus `golden.eigene-arten`), die Benutzer- und
Nachfrage-Vorlagen, das erzeugte JSON-Schema, die Reihenfolge von `ArtCode` und den Bildweg (`fotos`, bei `--k1`
`alle`) ab. Der System-Prompt wird in Java gerendert und fertig an das Modell übergeben; gehashter und gesendeter Text
sind dadurch gleich. Abstimmen und `--gate` nutzen denselben synthetischen Mandanten und haben denselben Hash; bei
`--realitaet` weicht er wegen des Mandantennamens der echten Belege ab.

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

## Gate-Lauf (nur Fabian)

1. main mit PR #15, `cd spikes/golden-lauf && mvn package`.
2. Schwelle festlegen: Vorschlag `max(0.8, kleinste Schwelle)` mit der grösseren kleinsten Schwelle der zwei Abstimmungsläufe
   der übernommenen Variante (Wert in [golden-ergebnis.md](../../docs/spikes/golden-ergebnis.md), «Offen für Gate 0»);
   meldet einer davon «keine ≤ 1.0», gibt es keinen Vorschlag. Den Wert setzt Fabian.
3. `java -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token -Darchiv.ki.schwelle=<wert> -jar target/quarkus-app/quarkus-run.jar --gate`
   (ohne Zusatzschalter, der Bildweg für Fotos ist Standard).
4. Prompt-Hash der Konsole mit dem eingefrorenen Hash in golden-ergebnis.md vergleichen; weicht er ab, gilt der Lauf nicht.
5. Kennzahlen des Prüfteils in golden-ergebnis.md eintragen. Bestanden bei mindestens 18/20 Belegen mit Art, Datum,
   Partner, Betrag richtig und erfüllter Req 0.9 (Req 0.3); «keine ≤ 1.0» im Prüfteil heisst nicht bestanden.

## Ausgabe

- Konsole: nur Beleg-ID (synthetisch Dateiname, echt die ersten 8 Zeichen des SHA-256 des Dateinamens), Feld, richtig/falsch.
- `$ARCHIV_GOLDEN_DIR/berichte/<JJJJ-MM-TT-HHMM>-<modus>[-k1]/`: `ergebnisse.json` (Vollbericht je Beleg) und
  `kennzahlen.md` (nur Zahlen und IDs, für `docs/spikes/golden-ergebnis.md`).
- OCR-Cache: `$ARCHIV_GOLDEN_DIR/.cache-golden-lauf/` (Text, Seitenbilder je Dateiinhalt). Ein Eintrag gilt erst mit der
  Datei `fertig` als vollständig; schlägt ein OCR-Schritt fehl, wird der Arbeitsordner gelöscht. Enthält Kopien der
  Belege und bleibt deshalb im Golden-Ordner; zum Löschen den Ordner entfernen.

## Tests

```sh
mvn verify                                     # Unit-Tests, ohne Netz und ohne Golden-Daten
mvn test -Dtest=GoldenDatensatzIT -Dgolden=true -Darchiv.ki.token-datei=$HOME/.config/urinet-archiv/infomaniak_ai_token
```

`GoldenDatensatzIT` läuft nur manuell (nie in der CI) auf dem Abstimmungsteil und wird ohne Token-Datei oder Golden-Daten übersprungen.
