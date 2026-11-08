# Spike Infomaniak AI Services (Aufgabe 0.3)

Stand 08.11.2026 · Req 0.5, 16.7 · Mini-App: [`spikes/infomaniak-ai/`](../../spikes/infomaniak-ai/) · nur synthetische Belege

## Ergebnis in Kürze

| Frage (Req 0.5) | Ergebnis |
| --- | --- |
| JSON-Schema-Antwortformat | **funktioniert** (`response_format.type=json_schema`, `strict=true`), aber nur mit flachen Typen: `LocalDate` und `Map` müssen im Antwort-Record anders abgebildet werden (siehe unten) |
| JSON-Modus | **nicht verfügbar**: Infomaniak lehnt `json_object` ab (`validation_rule_response_format_types_blacklist`, «use the new response format type 'json_schema'»). Der Rückfall aus design.md entfällt; es bleibt nur JSON-Schema |
| Tool Calling mit Mistral-Small-4 | **funktioniert** (`tools`, `tool_choice=auto`, `finish_reason=tool_calls`, mehrere Werkzeuge in einer Antwort) |
| Embeddings | `bge_multilingual_gemma2`: **3'584 Dimensionen**, Vektoren **nicht normiert** (Länge ≈ 159), Parameter `dimensions` nicht unterstützt |
| Reranker | Cohere-kompatibel unter `POST /2/ai/{product_id}/cohere/v2/rerank`, nicht unter der OpenAI-Basis-URL (Form unten) |
| Bild-Eingabe (K1) | **funktioniert**: Mistral-Small-4 liest ein PNG (`image_url` als Base64-Data-URL) und gibt Belegnummer, Datum, Betrag und Aussteller richtig zurück |
| Speicherung / Training, AVV | keine dauerhafte Speicherung der Inhalte, kein Training (vertraglich); AVV gilt über die ANB – Details und offene Punkte im Abschnitt «Datenschutz und AVV» |

## Zugang und Konfiguration

- Basis-URL OpenAI-kompatibel: `https://api.infomaniak.com/2/ai/{product_id}/openai/v1`, Produkt-ID `113354` (`archiv.ki.product-id`, `archiv.ki.basis-url`).
- Reranker-URL: `https://api.infomaniak.com/2/ai/{product_id}/cohere/v2/rerank` (eigene Einstellung `archiv.ki.reranker-url`).
- Token als Secret-Datei (`archiv.ki.token-datei`, Standard `/run/secrets/infomaniak_ai_token`); eine `ConfigSourceFactory` liest die Datei und setzt `quarkus.langchain4j.openai[.<name>].api-key`. Kein Token in Umgebungsvariablen oder Logs (tech.md Regel 7).
- Exakte Modellnamen laut `GET …/openai/v1/models` (08.11.2026):

| Zweck | Modellname in der API | Bemerkung |
| --- | --- | --- |
| Sprachmodell | `mistralai/Mistral-Small-4-119B-2603` | `max_token_input` 256'000 |
| Embeddings | `bge_multilingual_gemma2` | Antwort meldet `model: BAAI/bge-multilingual-gemma2` |
| Embeddings (Test) | `Qwen/Qwen3-Embedding-8B` | 4'096 Dimensionen, `dimensions` wird unterstützt (2'048 geprüft) |
| Reranker | `BAAI/bge-reranker-v2-m3` | nur über die Cohere-Schnittstelle, nicht in `/models` |
| Reranker (Reserve) | `Qwen/Qwen3-Reranker-0.6B` | geprüft, gleiche Schnittstelle |

Weitere Chat-Modelle im Konto: `swiss-ai/Apertus-v1.5-70B`, `mistralai/Ministral-3-14B-Instruct-2512`, `google/gemma-4-31B-it`, `moonshotai/Kimi-K2.6`, `Qwen/Qwen3.5-397B-A17B-FP8`, `Qwen/Qwen3.8-27B-FP8`.

Versionen der Mini-App: Quarkus 3.40.1 (LTS), `quarkus-langchain4j-openai` 1.13.3, LangChain4j 1.19.3, Java 25.

## Ausführen

```sh
cd spikes/infomaniak-ai
mvn package
java -Djava.awt.headless=true \
     -Darchiv.ki.token-datei="$HOME/.config/urinet-archiv/infomaniak_ai_token" \
     -jar target/quarkus-app/quarkus-run.jar
```

Die App führt sechs Prüfungen aus und schreibt nur synthetische Inhalte und Kennzahlen auf die Konsole.

## Befunde im Einzelnen

### 1 JSON-Schema-Antwortformat

Konfiguration als benanntes Modell: `quarkus.langchain4j.openai.strukturiert.chat-model.response-format=json_schema` und `…strict-json-schema=true`; AI-Service mit Rückgabetyp `Result<Record>`. Drei synthetische Belege (Ausgangsrechnung, Lieferantenrechnung, Offerte), je drei Aufrufe, `temperature=0`, sieben geprüfte Felder.

| Variante | Treffer | Befund |
| --- | --- | --- |
| A: `MetadatenVorschlag` wie in design.md (`LocalDate`, `Map<String, Double> sicherheit`) | 45/63 | LangChain4j bildet `LocalDate` als Objekt `{year, month, day}` ab; das Modell lässt es meist leer (`datum` 8× null von 9). `Map` wird zum Objekt ohne Eigenschaften, `sicherheit` kommt immer leer (`{}`) |
| B: Datum als ISO-Text mit `@Description`, `sicherheit` als Record mit festen Feldern | 56/63 | alle Datumswerte richtig, `sicherheit` je Feld gefüllt (0.7–0.95) |

Verbleibende Fehler in B sind inhaltlich, nicht technisch: Der synthetische Name «Beispiel Energie AG» wird als «Energie AG» gelesen (3×), «Gültig bis» der Offerte wird als Fälligkeit gelesen (3×), einmal fehlt die Offertnummer. Das ist Gegenstand des Prompts in Aufgabe 0.5, nicht dieses Spikes.

Ein Versuch per curl mit `"format": "date"` im Schema lieferte `"3102-03-20"` statt `2026-02-03`. `format` ist daher kein Ersatz für die Prüfung nach der Antwort.

Weitere Beobachtungen:
- **Chat-Memory abschalten.** Quarkus LangChain4j hält standardmässig ein Memory je Request-Kontext. Mehrere Belege im selben Kontext (z. B. in einem Worker-Durchlauf) vermischen sich sonst; die Eingabe-Tokens wuchsen im ersten Lauf von 290 auf 2'212. Lösung: `chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class`. AI-Services mit Werkzeugen brauchen dagegen ein Memory (Build-Fehler «Tool usage requires chat memory»).
- Antwortzeit rund 3 s je Beleg; Tokens je Aufruf ca. 290–380 ein / 220–290 aus (kurze synthetische Texte, ohne Beispiele). Die Antwort liefert `usage` inkl. `cached_tokens`; das genügt für `ki_verbrauch`.
- Im Programm müssen die Prompt-Variablen mit `-parameters` kompiliert werden (`maven.compiler.parameters=true`), sonst findet das Template `{text}` nicht.

### 2 JSON-Modus

`response_format: {"type": "json_object"}` wird mit HTTP 400 abgelehnt:
`validation_rule_response_format_types_blacklist – The 'json_object' is no longer supported for 'response_format.type', please use the new response format type 'json_schema'.`

### 3 Tool Calling

Frage «Wie viel haben wir 2026 der Muster Treuhand AG fakturiert, und wie viele Rechnungen sind dort offen?» mit zwei Werkzeugen (`summeAusgangsrechnungen(partner, jahr)`, `anzahlOffeneRechnungen(partner)`): Das Modell ruft beide Werkzeuge mit richtigen Argumenten auf und übernimmt die Werte unverändert in die Antwort («CHF 4'820.50», «2 offene Rechnungen»). In allen Läufen rief es beide Werkzeuge ein zweites Mal auf (einmal mit Jahr 2025). Für den Chat (Spec 02) die Zahl der Werkzeug-Runden begrenzen und doppelte Aufrufe zulassen (Werkzeuge nur lesend). Rohtest per curl: `finish_reason=tool_calls`, Argumente als JSON-String.

### 4 Embeddings

- `POST /openai/v1/embeddings` mit `model=bge_multilingual_gemma2`, Eingabe als String oder Liste.
- Dimension **3'584**; liegt über der HNSW-Grenze von 2'000 für `vector`, aber unter 4'000 für `halfvec` → Annahme in Spec 02 (`halfvec(3584)`, HNSW) bestätigt.
- Vektoren sind **nicht normiert** (Länge ≈ 159). Für die Suche Kosinus verwenden (`halfvec_cosine_ops`, wie in Spec 02) oder vor dem Speichern normieren; Skalarprodukt (`<#>`) wäre falsch.
- `dimensions` wird abgelehnt («dimensions is currently not supported»).
- Plausibilität: Frage «Stromrechnung vom Januar» → Kosinus 0.64 (Stromrechnung), 0.53 (Ausgangsrechnung), 0.38 (Offerte).
- Qwen3-Embedding-8B (Test laut tech.md) liefert 4'096 Dimensionen, also über der `halfvec`-Grenze, unterstützt aber `dimensions` (2'048 geprüft). Die Risikozeile «Embedding-Dimension > 4'000» im Umsetzungsplan betrifft daher nur Qwen ohne `dimensions`.

### 5 Bild-Eingabe (K1)

Synthetischer Beleg als PNG (1100 × 420 px, Base64) mit Mistral-Small-4 über `ImageContent` (OpenAI-Format `image_url` mit Data-URL). Antwort: `Rechnung 2026-123; 15.01.2026; CHF 87.35; Beispiel Energie AG, Werkstrasse 9, 6460 Altdorf.` – alle Werte richtig, 666 Eingabe-Tokens, rund 1–2 s. Das gewünschte Datumsformat (ISO) hielt das Modell im Freitext nicht ein; mit JSON-Schema wäre das abgesichert.

Folgerung für K1: Bilder lassen sich direkt lesen. Das ersetzt die OCR nicht (Volltext für Suche und PDF/A bleibt nötig), kann aber in Aufgabe 0.5 als Variante gegen die Golden-Belege gemessen werden.

### 6 Reranker

Anfrage:

```http
POST https://api.infomaniak.com/2/ai/{product_id}/cohere/v2/rerank
Authorization: Bearer <Token>
Content-Type: application/json

{"model": "BAAI/bge-reranker-v2-m3", "query": "…", "documents": ["…", "…"], "top_n": 2}
```

Antwort (absteigend nach Relevanz):

```json
{"id": "score-…", "model": "BAAI/bge-reranker-v2-m3",
 "usage": {"prompt_tokens": 253, "total_tokens": 253},
 "results": [{"index": 1, "document": {"text": "…", "multi_modal": null}, "relevance_score": 0.346}, …]}
```

- `index` verweist auf die Position in `documents`; der Text kommt zurück (bei langen Chunks Datenmenge beachten).
- Die Skala hängt vom Modell ab (bge 0.35 gegenüber Qwen3-Reranker 0.99 für denselben Treffer); eine Schwelle gilt daher nur je Modell.
- LangChain4j-OpenAI deckt den Reranker nicht ab; ein eigener REST-Client (wie im Spike) genügt.

## Folgen für das Design

- `MetadatenVorschlag`: Datumsfelder als ISO-Text mit Beschreibung im Schema und Umwandlung in `LocalDate` nach der Antwort (ungültig → leer, Sicherheit 0); `sicherheit` als Record mit festen Feldern statt `Map`. Eingetragen in design.md, Abschnitt KI.
- Kein JSON-Modus als Rückfall; bleibt das Schema einmal aus, gilt die bestehende Regel «ohne Antwort in den Eingangskorb» (Req 7.1, `KI_VORSCHLAG` setzt nie `FEHLER`).
- Extraktor ohne Chat-Memory.
- Embedding-Spalte `halfvec(3584)` mit Kosinus (Spec 02 bestätigt).
- Reranker über eigene Einstellung `archiv.ki.reranker-url` und eigenen Client.

## Datenschutz und AVV

Massgebend ist [infomaniak-ai-datenschutz.md](infomaniak-ai-datenschutz.md) (Prüfung der öffentlichen Infomaniak-Dokumente am 08.11.2026) mit Belegen, offenen Punkten und Rückfragen an Infomaniak. Kurz zu Req 0.5:

- **Speichert Infomaniak Eingaben?** Nicht dauerhaft; nur Betriebsdaten und eine vorübergehende Speicherung während der Anfrage. Fristen sind offen (Abschnitt 1).
- **Training?** Vertraglich ausgeschlossen, für alle Modelle der API (Abschnitt 2).
- **AVV:** gilt über die ANB ohne separate Unterzeichnung; Verweise unter [docs/vertrag/README.md](../vertrag/README.md) (Abschnitt 4).
- Die im Spike verwendeten Modelle tragen kein Beta-Kennzeichen (Abschnitt 5).
