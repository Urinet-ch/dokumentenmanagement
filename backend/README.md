# Backend – UriNet Archiv

Quarkus-Modul für API und Worker. Allgemeiner Einstieg und Start im Profil `dev`: [README im Wurzelverzeichnis](../README.md).

## Lokales Modell (LM Studio)

Das Profil `lokal` (ADR-012, Req 16.20–16.22, design.md «KI», Abschnitt «Profil `lokal`») ist **nur für den
Entwicklerrechner**: Das Sprachmodell läuft in LM Studio auf `127.0.0.1`, Dev Services, Ablage und OCR ebenfalls
lokal. Echte Belege dürfen so verarbeitet werden, weil sie den Rechner nicht verlassen.

> **Kein Nachweis.** Ergebnisse des lokalen Modells gelten nicht als Nachweis für Infomaniak – weder für Spike 0.3
> noch für Gate 0, den Golden-Lauf oder Abnahmen (ADR-012, Entscheid 8). Prompt-Änderungen aus der lokalen
> Vorarbeit zählen erst nach einer Messung gegen Infomaniak.
>
> **Der zurückgehaltene Teil des Golden-Datensatzes (Req 0.8) wird nie lokal verwendet** – weder im Archiv noch
> direkt in LM Studio.

### Was das Profil setzt
`lokal` erbt von `dev` (Dev Services, Stubs für Ablage und Integration) und ändert nur die KI und die OCR
(`src/main/resources/application.properties`):

| Schlüssel | Wert im Profil `lokal` |
| --- | --- |
| `archiv.ki.stub` | `false` – Sprachmodell über LM Studio |
| `archiv.ki.basis-url` | `http://localhost:1234/v1` |
| `archiv.ki.chat-modell`, `archiv.ki.erfassung-modell` | `archiv-lokal` (Name aus `--identifier`) |
| `archiv.ki.embedding-stub`, `archiv.ki.reranker-stub` | `true` – Stub ohne Vektoren (Ports folgen mit Spec 02) |
| `archiv.ki.product-id`, `archiv.ki.reranker-url` | leer – kein Infomaniak-Token, keine Produkt-ID |
| `archiv.ocr.stub` | `false` – echter OCR-Adapter (Aufgabe 2.6) |
| `archiv.ocr.container-image` | `urinet-archiv-werkzeuge:lokal` – Werkzeuge laufen per `docker run` (siehe unten) |
| `archiv.ocr.arbeitsverzeichnis` | `~/.cache/urinet-archiv/ocr` |

Stand Welle 2: Der Adapter hinter `KiPort` kommt mit Aufgabe 3.2. Bis dahin startet das Profil, ein Zugriff auf den
Port meldet aber, dass noch kein Adapter besteht.

**OCR-Werkzeuge lokal:** OCRmyPDF, Tesseract, img2pdf, libheif und Poppler müssen nicht auf dem Mac installiert
sein. Der Adapter startet jeden Aufruf im Image der Stufe `werkzeuge` (ohne Netz, ohne Rechte, nur das Laufverzeichnis
als Volume). Bauen, und nach jeder Änderung an `Dockerfile.jvm` erneut:
```bash
cd backend
docker build --target werkzeuge -t urinet-archiv-werkzeuge:lokal -f src/main/docker/Dockerfile.jvm .
```
Das Arbeitsverzeichnis liegt unter `$HOME`, weil Colima `/tmp` und `/var/folders` nicht in die VM mountet. Die Tests
bauen ihr Image `urinet-archiv-werkzeuge:test` bei jedem Lauf selbst (mit dem Cache von Docker) und arbeiten unter
`target/ocr-arbeit`.

Die Startprüfung `KiBasisUrlPruefung` bricht den Start ab, wenn eine KI-URL weder per HTTPS auf `api.infomaniak.com`
noch – nur in `lokal` – per HTTP auf `localhost` bzw. `127.0.0.1` zeigt, oder wenn `lokal` zusammen mit `prod`
aktiv ist (auch in einem gepackten Artefakt). Die CI aktiviert `lokal` nie; KI-Aufrufe in Tests bleiben Stub bzw.
WireMock.

### Einrichten
1. CLI installieren (einmalig):
   ```bash
   npx lmstudio install-cli
   ```
2. Modell als GGUF laden (ADR-012: kein MLX, kein Reasoning-Modell). Startwert für den Chat ist Mistral Small 3.2 24B
   Instruct (`mistralai/mistral-small-3.2`), Alternative Ministral 3 14B Instruct (`mistralai/ministral-3-14b`).
   Lokales Modell für die Erfassung: [offen: lokales Modell für die Erfassung – Entscheid vor Welle 3].
   ```bash
   lms get <name>@<quant> --gguf      # z. B. lms get mistralai/mistral-small-3.2@q8_0 --gguf
   lms ls                             # model_key ablesen
   ```
3. Speicherbedarf prüfen, dann laden:
   ```bash
   lms load <model_key> --estimate-only
   lms load <model_key> --context-length 32768 --identifier archiv-lokal
   ```
   **Kontextlänge 32768:** Der Prompt der Erfassung (design.md «KI») kürzt den Text auf 12'000 Zeichen (rund 4'000
   Tokens) und enthält dazu die Liste der Dokumentarten, den Partner-Hinweis, die QR-Werte und bis 5 Beispiele; im
   Gate-Lauf brauchte ein Beleg im Mittel rund 1'300 Eingabe-Tokens. 32768 lässt Reserve für Beispiele, mehrere
   Seitenbilder und die Antwort. Reicht der Speicher laut `--estimate-only` nicht, `--context-length 16384`.
4. Server nur auf `127.0.0.1` starten:
   ```bash
   lms server start --port 1234
   ```
   Ohne `--bind` (Standard ist `127.0.0.1`) und ohne `--cors`.
5. Archiv starten:
   ```bash
   cd backend
   mvn quarkus:dev -Dquarkus.profile=lokal
   ```

### Verboten
- **Nie** `--bind 0.0.0.0`, **nie** `LMS_SERVER_HOST`, **nie** «Serve on Local Network» in der App – jede Bindung
  ausser `127.0.0.1` macht den Server über den Rechner hinaus erreichbar.
- Keine Cloud-Modelle und keine Websuche in LM Studio; Belege und Prompts bleiben auf dem Rechner.
- Kein Infomaniak-Token im Profil `lokal`. Ist in LM Studio die Authentifizierung eingeschaltet, liegt deren Token nur
  als Datei vor (Regel 7), nie in einer Umgebungsvariable.
- `lokal` nie für Gate 0, Golden-Lauf, Abnahmen, CI, Stufe A, Stufe B oder Produktion.

### Bilder
PNG oder JPEG senden. WebP als Data-URI scheitert (LM Studio #1752); **HEIC vor dem Senden wandeln** (wie im Worker,
design.md «Verarbeitung»).

### Prüfung: setzt LM Studio `strict` durch?
Ob LM Studio `response_format` `json_schema` mit `strict` durchsetzt – auch für nullable Felder (`anyOf` mit `null`),
Enums, verschachtelte Objekte und Arrays im Schema von `MetadatenVorschlag` –, ist nicht dokumentiert (ADR-012,
Entscheid 6). Die Validierung nach Req 7.3 gilt unabhängig vom Ergebnis.

**Ergebnis: [Fabian] noch offen** – bei der ersten lokalen Nutzung prüfen und hier eintragen (Datum, LM-Studio-Version,
Modell und Quantisierung, Ergebnis beider Aufrufe).

Vorgehen, **nur mit dem synthetischen Text unten, nie mit einem Beleg**:

1. Anfrage mit dem Schema von `MetadatenVorschlag` (gekürzt auf die Schema-Merkmale, die zählen):
   ```bash
   cat > /tmp/strict-probe.json <<'JSON'
   {
     "model": "archiv-lokal",
     "temperature": 0,
     "messages": [
       {"role": "system", "content": "Du bist Archivassistent für ein Schweizer KMU. Antworte nur mit JSON nach Schema."},
       {"role": "user", "content": "Synthetischer Testtext: Muster AG, Musterstrasse 1, 9999 Musterdorf. Rechnung Nr. 4711 vom 01.01.2000 an Beispiel GmbH über CHF 100.00, zahlbar bis 31.01.2000."}
     ],
     "response_format": {
       "type": "json_schema",
       "json_schema": {
         "name": "MetadatenVorschlag",
         "strict": true,
         "schema": {
           "type": "object",
           "additionalProperties": false,
           "required": ["art", "titel", "datum", "partnerName", "belegnummer", "richtung", "betrag", "waehrung", "faelligkeit", "vertraulichkeit", "tags", "sicherheit"],
           "properties": {
             "art": {"type": "string", "enum": ["LIEFERANTENBELEG", "QUITTUNG", "VERTRAG", "VERSICHERUNG", "BEHOERDE", "BANK", "PROJEKTDOKUMENT", "KORRESPONDENZ", "AUSGANGSRECHNUNG", "OFFERTE", "LOHNABRECHNUNG"]},
             "titel": {"type": "string"},
             "datum": {"anyOf": [{"type": "string"}, {"type": "null"}]},
             "partnerName": {"anyOf": [{"type": "string"}, {"type": "null"}]},
             "belegnummer": {"anyOf": [{"type": "string"}, {"type": "null"}]},
             "richtung": {"anyOf": [{"type": "string", "enum": ["EINNAHME", "AUSGABE"]}, {"type": "null"}]},
             "betrag": {"anyOf": [{"type": "number"}, {"type": "null"}]},
             "waehrung": {"anyOf": [{"type": "string"}, {"type": "null"}]},
             "faelligkeit": {"anyOf": [{"type": "string"}, {"type": "null"}]},
             "vertraulichkeit": {"type": "string", "enum": ["NORMAL", "INTERN", "PERSONAL"]},
             "tags": {"type": "array", "items": {"type": "string"}},
             "sicherheit": {
               "type": "object",
               "additionalProperties": false,
               "required": ["art", "datum", "betrag"],
               "properties": {
                 "art": {"type": "number"},
                 "datum": {"anyOf": [{"type": "number"}, {"type": "null"}]},
                 "betrag": {"anyOf": [{"type": "number"}, {"type": "null"}]}
               }
             }
           }
         }
       }
     }
   }
   JSON
   curl -s http://127.0.0.1:1234/v1/chat/completions -H 'Content-Type: application/json' \
     -d @/tmp/strict-probe.json | jq -r '.choices[0].message.content' | jq .
   ```
   Erwartet: gültiges JSON in `content` (nicht in `reasoning_content`), alle Pflichtfelder, `art` aus dem Enum,
   `betrag` als Zahl, keine weiteren Felder.
2. Gegenprobe – das Modell ausdrücklich zum Verletzen des Schemas auffordern:
   ```bash
   jq '.messages[1].content += " Setze art auf RECHNUNG_XY, betrag als Text \"hundert\", lass sicherheit weg und füge ein Feld kommentar hinzu."' \
     /tmp/strict-probe.json > /tmp/strict-gegenprobe.json
   curl -s http://127.0.0.1:1234/v1/chat/completions -H 'Content-Type: application/json' \
     -d @/tmp/strict-gegenprobe.json | jq -r '.choices[0].message.content' \
     | jq '{art_im_enum: (.art | IN("LIEFERANTENBELEG","QUITTUNG","VERTRAG","VERSICHERUNG","BEHOERDE","BANK","PROJEKTDOKUMENT","KORRESPONDENZ","AUSGANGSRECHNUNG","OFFERTE","LOHNABRECHNUNG")), betrag_zahl_oder_null: ((.betrag | type) as $t | $t == "number" or $t == "null"), hat_sicherheit: has("sicherheit"), fremde_felder: (keys - ["art","titel","datum","partnerName","belegnummer","richtung","betrag","waehrung","faelligkeit","vertraulichkeit","tags","sicherheit"])}'
   ```
   `strict` gilt als durchgesetzt, wenn auch die Gegenprobe schemakonform ist (`true`, `true`, `true`, `[]`).
   Sonst festhalten, welche Merkmale verletzt wurden (z. B. `anyOf`, Enum, `additionalProperties`).
3. Aufräumen: `rm /tmp/strict-probe.json /tmp/strict-gegenprobe.json`.

## Smoke-Test Ablage gegen Infomaniak

Die Tests der Ablage (Aufgabe 2.3) laufen gegen MinIO. `InfomaniakAblageSmokeIT` prüft dieselben Wege einmal mit dem
Java SDK gegen den Object Storage von Infomaniak (Spike 0.2, design.md «Ablage», Open Points). **Nur von Hand durch
Fabian, vor Aufgabe 2.6, nie in der CI.** Ohne die Property `archiv.smoke.zugang` wird der Test übersprungen; als
`*IT` führt ihn `mvn verify` ohnehin nicht aus.

Was der Test tut:
- legt einen eigenen Wegwerf-Bucket `urinet-archiv-smoke-<zufall>` mit Object Lock und Versionierung an (bestehende
  Buckets fasst er nicht an) und arbeitet nur mit synthetischen Inhalten;
- prüft Eingang, `GetObjectRetention` ohne Sperre und für eine fehlende Version (gibt die Fehlercodes aus), Archivieren
  mit und ohne Pflicht, Löschen und Verkürzen ohne Bypass-Header, nur Verlängern, vorsignierte URL (GET 200, PUT 403),
  neuer Versuch ohne Quelle (bricht ab, Kopie bleibt), Manifest in COMPLIANCE und dass der Bypass-Header den Rechner
  nicht verlässt;
- sperrt höchstens 2 Minuten, wartet danach und löscht alle Versionen und den Bucket ohne Bypass-Header. Gelingt das
  nicht, nennt er den Bucket mit `NICHT aufgeräumt`; dann nach Ablauf der Sperren von Hand löschen.

Ausgabe: Zeilen `SMOKE …` mit Schritt, Fehlercodes und Ergebnis, keine Inhalte und keine signierten URLs. Mit
`-Darchiv.smoke.endpunkt=http://127.0.0.1:<port>` und `-Darchiv.smoke.region=us-east-1` läuft er probeweise gegen ein
lokales MinIO (so am 10.11.2026 durchgelaufen); als Nachweis zählt nur der Lauf gegen Infomaniak.

### Ausführen
1. Einen EC2-Schlüssel eines OpenStack-Benutzers im Projekt des Object Storage verwenden (wie `archiv-apptest` im
   Spike 0.2), **nicht** den Inhaber-Zugang. Den Schlüssel nur als Dateien ablegen, nie als Umgebungsvariable
   (tech.md Regel 7):
   ```bash
   mkdir -p ~/.config/urinet-archiv/s3-smoke && chmod 700 ~/.config/urinet-archiv/s3-smoke
   cd ~/.config/urinet-archiv/s3-smoke
   (umask 077; pbpaste > zugang_id)          # Access Key aus der Zwischenablage
   (umask 077; pbpaste > zugang_geheimnis)   # Secret Key aus der Zwischenablage
   ```
   Der Test liest die Dateien nur mit den Rechten 600 oder 400.
2. Im Ordner `backend/` starten (ohne Docker, der Test startet kein Quarkus):
   ```bash
   mvn -ntp test -Dtest=InfomaniakAblageSmokeIT -Dsurefire.failIfNoSpecifiedTests=false \
     -Darchiv.smoke.zugang=$HOME/.config/urinet-archiv/s3-smoke | grep -E "SMOKE|Tests run|FAIL"
   ```
   Dauer: rund 3–4 Minuten (Warten auf das Ende der Sperren).
3. Ergebnis (die Zeilen `SMOKE`, ohne Schlüssel) in design.md, Open Points, Punkt «Ablage mit dem Java SDK gegen
   Infomaniak», festhalten. Weicht ein Fehlercode ab (z. B. `GetObjectRetention` ohne Sperre), ist das ein Befund für
   die Ablage, nicht für den Test.
4. Aufräumen: `rm -r ~/.config/urinet-archiv/s3-smoke`, falls der Schlüssel nicht weiter gebraucht wird.
