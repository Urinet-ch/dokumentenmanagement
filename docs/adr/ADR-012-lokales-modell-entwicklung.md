# ADR-012: Lokales Sprachmodell über LM Studio nur für die Entwicklung (Profil `lokal`)

**Status:** Vorgeschlagen – Freigabe per eigenem Merge (nicht Teil von Aufgabe 0.6)

**Datum:** 08.11.2026

## Kontext
- Produktiv läuft die KI nur über Infomaniak AI Services (OpenAI-kompatibel, Quarkus LangChain4j):
  Sprachmodell `mistralai/Mistral-Small-4-119B-2603`, Embeddings `bge_multilingual_gemma2`
  (3584 Dimensionen), Reranker `BAAI/bge-reranker-v2-m3` über einen Cohere-kompatiblen Pfad.
  Die Antwort kommt per `response_format` `json_schema` mit `strict`, `json_object` lehnt
  Infomaniak ab.
- tech.md Regel 6 verbietet bisher jeden anderen KI-Endpunkt, «auch nicht als Rückfall oder in Tests
  gegen echte Modelle». Req 16.7 erlaubt die Verarbeitung echter Daten nur in Schweizer
  Rechenzentren, mit einer einzigen Ausnahme (Sicherung, 16.8).
- Für die Entwicklung (Adapter, Fehlerpfade, Prompt-Vorarbeit) will Fabian zusätzlich ein Modell
  auf dem eigenen Entwicklerrechner nutzen, einem Mac mit 64 GB RAM, auch mit echten,
  ungeschwärzten Belegen. Daten, die den Rechner nicht verlassen, gehen an keinen Dritten.
- LM Studio bietet `/v1/chat/completions`, `/v1/embeddings` und `/v1/models` OpenAI-kompatibel an;
  bestehende Clients funktionieren nach Umstellen der Basis-URL
  ([Doku](https://lmstudio.ai/docs/developer/openai-compat)). `lms server start` bindet
  standardmässig an `127.0.0.1` ([Doku](https://lmstudio.ai/docs/cli/serve/server-start)).
  Nach dem Herunterladen der Modelle läuft LM Studio vollständig offline; die App hat laut
  Datenschutzerklärung keine Telemetrie, der Update-Check überträgt App-Version, Betriebssystem
  und IP-Adresse ([Offline](https://lmstudio.ai/docs/app/offline),
  [Datenschutz](https://lmstudio.ai/app-privacy)).
- Fabian hat am 08.11.2026 entschieden: Ein lokales Modell über LM Studio ist erlaubt, eng begrenzt (P19).

## Entscheid
1. **Profil `lokal`:** Ein eigenes Quarkus-Profil `%lokal` (erbt von `dev`) setzt
   `archiv.ki.basis-url=http://localhost:1234/v1` (gleichwertig `http://127.0.0.1:1234/v1`). Der
   KI-Adapter hinter `KiPort` bleibt derselbe; es wechseln nur Basis-URL und Modellname. Port 1234
   wird beim Start ausdrücklich gesetzt (`lms server start --port 1234`), weil der Server ohne
   `--port` den zuletzt benutzten Port nimmt.
2. **Nur Entwicklerrechner, nur `localhost`:** LM Studio läuft nur auf dem Entwicklerrechner und
   hört nur auf `127.0.0.1`. Verboten sind `--bind 0.0.0.0`, `LMS_SERVER_HOST`, «Serve on Local
   Network» und `--cors`; in LM Studio werden keine Cloud-Modelle und keine Websuche genutzt, weil
   diese Funktionen Daten an Anbieter senden.
3. **Erlaubt:** Entwicklung, Prompt-Vorarbeit und Arbeit mit echten, ungeschwärzten Belegen, die
   den Rechner nicht verlassen. Im Profil `lokal` speichert und verarbeitet das Archiv diese Daten
   auf dem Entwicklerrechner (Dev Services, Ablage, OCR, Sprachmodell in LM Studio) (Ausnahme zu
   Req 16.7, Req 16.20). Der OCR-Adapter ist im Profil `lokal` echt (OCRmyPDF als lokales
   Docker-Image), damit das Sprachmodell den echten Volltext erhält.
4. **Nicht erlaubt:**
   - Gate 0, Golden-Lauf (Aufgabe 0.5, `GoldenLauf`, `GoldenDatensatzIT`) und Abnahmen: gemessen
     wird nur gegen Infomaniak (Req 16.22); die Hostprüfung des Golden-Laufs entsteht in Aufgabe 0.5.
     Der zurückgehaltene Teil des Golden-Datensatzes (Req 0.8) wird auch lokal nie zum Abstimmen
     verwendet.
   - CI: KI-Aufrufe bleiben WireMock (tech.md, Tests und Qualität).
   - Stufe A, Stufe B und Produktion: nur Infomaniak; `lokal` zusammen mit `prod` bricht ab (Req 16.22).
   - Jeder andere Endpunkt: `archiv.ki.basis-url` und `archiv.ki.reranker-url` zeigen per HTTPS auf
     `api.infomaniak.com` oder, nur in den Profilen `lokal` und `test`, auf `localhost` bzw.
     `127.0.0.1`. Sonst bricht das Archiv beim Start ab (Req 16.21). In `test` ist `localhost` nur
     für WireMock zulässig (Port der WireMock-Testressource), nie für LM Studio (Port 1234).
5. **Modellwahl nach Belegen:**
   - **Mistral Small 4 lokal: nicht gewählt.** Der LM-Studio-Katalog verlangt mindestens 65 GB RAM
     ([Katalog](https://lmstudio.ai/models/mistralai/mistral-small-4)); jede Q4-Variante ist grösser
     als 64 GB (bartowski: Q4_K_S 69.88 GB, Q4_K_M 72.64 GB). Unter 64 GB liegen nur Q3/IQ2-Stufen
     (Q3_K_XL 57.37 GB, IQ2_M 41.63 GB) mit Qualitätsverlust und fast dem ganzen RAM
     ([Quants](https://huggingface.co/bartowski/mistralai_Mistral-Small-4-119B-2603-GGUF)).
     Lauffähig in 64 GB ist damit nicht belegt.
   - **Startmodell: Mistral Small 3.2 24B Instruct (2506)**, im LM-Studio-Katalog
     `mistralai/mistral-small-3.2`: Apache 2.0, Bild-Eingabe, Deutsch (HF-Tag `de`), mindestens
     14 GB RAM; GGUF Q8_0 25.05 GB ([Katalog](https://lmstudio.ai/models/mistralai/mistral-small-3.2),
     [Modellkarte](https://huggingface.co/mistralai/Mistral-Small-3.2-24B-Instruct-2506)). Grund:
     Laut Modellkarte von Small 4 entspricht dessen Modus `reasoning_effort=none` dem Stil von
     Small 3.2; es ist damit das nächste belegte Verhalten zum Produktivmodell.
   - **Alternative: Ministral 3 14B Instruct (2512)**, Katalog `mistralai/ministral-3-14b`:
     Apache 2.0, Bild-Eingabe, Deutsch ausdrücklich, natives Function Calling und JSON-Ausgabe,
     mindestens 10 GB, GGUF von Mistral selbst (Q8_0 14.36 GB)
     ([Katalog](https://lmstudio.ai/models/mistralai/ministral-3-14b),
     [GGUF](https://huggingface.co/mistralai/Ministral-3-14B-Instruct-2512-GGUF)). Für Werkzeugaufrufe
     (Spec 02) oder wenn der Speicher knapp ist.
   - **Format GGUF, kein Reasoning-Modell:** Bei MLX ignoriert die erste `json_schema`-Anfrage nach
     einer Anfrage ohne Schema das Schema (offener Fehler
     [#2476](https://github.com/lmstudio-ai/lmstudio-bug-tracker/issues/2476)); bei
     Reasoning-Modellen landet das JSON teils in `reasoning_content` statt in `content`.
6. **Antwortformat:** LM Studio nimmt `response_format` `json_schema` im OpenAI-Format an und lehnt
   `json_object` wie Infomaniak mit 400 ab
   ([Structured Output](https://lmstudio.ai/docs/developer/openai-compat/structured-output),
   [#189](https://github.com/lmstudio-ai/lmstudio-bug-tracker/issues/189)). Was `strict` dort
   bewirkt, ist nicht dokumentiert; die Antwort wird wie bei Infomaniak validiert (Req 7.3).
7. **Embeddings und Reranker im Profil `lokal` als Stub ohne Vektoren.** `bge_multilingual_gemma2`
   wurde auf Hugging Face weder als GGUF noch als MLX gefunden; die Konvertierung scheitert in llama.cpp
   ([#10215](https://github.com/ggml-org/llama.cpp/issues/10215)); LM Studio hat keinen
   Rerank-Endpunkt ([#470](https://github.com/lmstudio-ai/lmstudio-bug-tracker/issues/470)).
   Infomaniak-Embeddings im Profil `lokal` scheiden aus, weil echte Belege den Rechner sonst
   verlassen würden. Vektoren aus einem anderen Modell kommen nie in eine Datenbank, die mit
   Infomaniak-Vektoren arbeitet.
8. **Kein Nachweis:** Erkenntnisse aus dem lokalen Modell gelten nicht als Nachweis für
   Infomaniak (weder Spike AI Services (Aufgabe 0.3) noch Gate 0 noch Abnahmen). Prompt-Änderungen aus der lokalen
   Vorarbeit werden gegen Infomaniak gemessen, bevor sie zählen.

## Betrachtete Alternativen
- **Kein lokales Modell (bisherige Regel 6):** Abgelehnt. Entwicklung und Prompt-Vorarbeit mit
  echten Belegen wären nur über Infomaniak oder mit dem Dev-Stub möglich.
- **Mistral Small 4 in Q3/IQ2:** Abgelehnt. Fast der ganze RAM ist belegt, die Qualität sinkt, und
  ob macOS so viel Speicher für die GPU freigibt, ist nicht belegt.
- **Andere lokale Modelle:** Gemma 3 27B nicht gewählt (Gemma-Lizenz, gesperrtes Repo, andere
  Modellfamilie); Qwen2.5-VL 32B nicht gewählt (Deutsch in der Modellkarte nicht belegt, 32k Kontext).
- **LM Studio im lokalen Netz oder auf einem anderen Rechner:** Abgelehnt. Jede Bindung ausser
  `127.0.0.1` macht den Server über `localhost` hinaus erreichbar
  ([Doku](https://lmstudio.ai/docs/developer/core/server/serve-on-network)), und echte Belege
  verliessen den Rechner.
- **Lokales Embedding-Modell (z. B. EmbeddingGemma):** Nicht gewählt. Die Vektoren wären nicht mit
  den 3584-Dim.-Vektoren von Infomaniak vergleichbar.
- **Lokales Modell als Rückfall bei Ausfall von Infomaniak:** Abgelehnt; Stufe A, Stufe B und
  Produktion bleiben bei Infomaniak (Regel 6).

## Folgen
- **Positiv:** Entwicklung und Prompt-Vorarbeit ohne Kosten pro Aufruf und offline. Echte Belege
  können geprüft werden, ohne den Rechner zu verlassen. Der Adapter bleibt unverändert, weil nur die
  Basis-URL wechselt.
- **Negativ:** Das lokale Modell ist nicht das Produktivmodell. Trefferquoten und Antwortverhalten
  sind nicht übertragbar, die Messung gegen Infomaniak bleibt Pflicht. Die Regel «nur an
  `localhost`» prüft das Archiv nur auf seiner Seite (Basis-URL), die Bindung von LM Studio liegt
  in der Verantwortung des Entwicklers.
- **Umsetzung:** Aufgabe 0.5 (Hostprüfung `GoldenLauf`/`GoldenDatensatzIT`), Aufgabe 1.11 (Profil `lokal`, Startprüfung `KiBasisUrlPruefung`, Konfigurationstest,
  Anleitung im `backend/README.md`).
- **Tests:** Konfigurationstest als reiner Unit-Test (JUnit, Profilnamen und URI als Eingabe, ohne
  Quarkus-Start): fremder Host, `localhost` im Profil `prod`, `localhost:1234` im Profil `test` und
  `lokal` zusammen mit `prod` werden abgelehnt. Die CI aktiviert das Profil `lokal` nie. Die
  Hostprüfung des Golden-Laufs testet Aufgabe 0.5.
- **Bilder:** Laut Doku des Python-SDK unterstützt der Server JPEG, PNG und WebP
  ([Python-SDK](https://lmstudio.ai/docs/python/llm-prediction/image-input)); für die OpenAI-API
  nicht dokumentiert. PNG oder JPEG senden (WebP-Data-URI-Fehler
  [#1752](https://github.com/lmstudio-ai/lmstudio-bug-tracker/issues/1752)); HEIC vorher wandeln,
  wie im Worker ohnehin (design.md, Verarbeitung).

## Verweise
- Req 0.2, 0.3, 0.8, 7.3, 16.7, 16.14, 16.20–16.22; P19
- tech.md Regel 6 und «Infomaniak AI Services»; design.md «KI», Abschnitt «Profil `lokal`»
- Aufgaben 0.5, 1.11
- ADR-006, ADR-011
