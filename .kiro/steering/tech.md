---
inclusion: always
---

# Technik und verbindliche Regeln

## Stack
| Bereich | Wahl | Hinweis |
| --- | --- | --- |
| Backend | Java 21, Quarkus (aktuelle LTS beim Projektstart), Maven | ein Modul `backend`, zwei Laufarten: `api` und `worker` (Konfig `archiv.rolle`) |
| Persistenz | PostgreSQL 17 mit `pgvector` und `pg_trgm`, Hibernate ORM mit Panache, Flyway | Mandantentrennung **Schema pro Mandant** (`quarkus.hibernate-orm.multitenant=SCHEMA`) |
| Dateien | Infomaniak Object Storage (S3) über `quarkus-amazon-s3`, Endpoint-Override, Path-Style | Object Lock im Modus GOVERNANCE, Versionierung an |
| OCR | OCRmyPDF + Tesseract (`deu`, `eng`), img2pdf, libheif | im Worker-Image installiert, Aufruf per `ProcessBuilder` |
| Textextraktion | Apache Tika (nur für digitale PDFs/Office ohne OCR) | |
| KI | Quarkus LangChain4j (`quarkus-langchain4j-openai`) gegen Infomaniak AI Services (OpenAI-kompatibel) | Sprachmodell `mistralai/Mistral-Small-4-119B-2603`; Embeddings BGE Multilingual Gemma2; Reranker `BAAI/bge-reranker-v2-m3` |
| Anmeldung | Keycloak (OIDC, PKCE, TOTP-2FA), `quarkus-oidc` | Mandant kommt aus dem Token-Claim `mandant` |
| Frontend | Angular (aktuelle Version), Standalone Components, Signals, Angular Material, PWA (`@angular/pwa`) | Offline-Warteschlange in IndexedDB |
| CSV | Apache Commons CSV, ICU4J (Zeichensatz-Erkennung) | |
| Betrieb | Docker Compose auf Infomaniak (VPS oder Public Cloud), Caddy als Reverse Proxy mit TLS | Domains `archiv.urinet.ch`, `auth.urinet.ch` |
| Sicherung | `pg_dump` + restic nach Swiss Backup (S3) | täglich, Wiederherstellung halbjährlich testen |
| Tests | JUnit 5, Quarkus Dev Services (PostgreSQL, Keycloak), Testcontainers (MinIO mit Object Lock), WireMock, AssertJ, ArchUnit, Playwright | |

## Nicht verhandelbare Regeln
1. **Mandant nie aus Benutzereingaben.** Der Mandant stammt ausschliesslich aus dem verifizierten Token (`mandant`-Claim) bzw. aus dem API-Token. Kein Header, kein Query-Parameter, kein Prompt setzt ihn.
2. **Dateien sind unveränderbar.** Eine archivierte Datei wird nie überschrieben oder gelöscht. Änderungen betreffen nur Metadaten und erzeugen eine neue Version in `dokument_version`.
3. **SHA-256 bei Eingang.** Jede Datei erhält beim ersten Empfang eine Prüfsumme; sie wird protokolliert, bevor irgendetwas anderes passiert.
4. **Zugriffsprotokoll ist append-only.** `zugriff_protokoll` erlaubt nur `INSERT` (Trigger verhindert `UPDATE`/`DELETE`).
5. **KI rechnet nicht.** Summen, Zählungen und Fristen kommen aus SQL. Das Sprachmodell wählt nur Werkzeuge und formuliert. Kein freies Text-zu-SQL.
6. **Daten bleiben in der Schweiz.** Nur Infomaniak-Dienste für Speicher, Datenbank und KI. Alle KI-Aufrufe (Sprachmodell, Embeddings, Reranker) gehen an das bestehende Konto bei Infomaniak AI Services (Modellliste geprüft 07.11.2026). Keine Aufrufe an andere KI-Anbieter, auch nicht als Rückfall oder in Tests gegen echte Modelle.

## Infomaniak AI Services (bestehendes Konto)
- Zugang über Umgebungsvariablen `INFOMANIAK_AI_PRODUCT_ID` und `INFOMANIAK_AI_TOKEN`; Basis-URL der OpenAI-kompatiblen Schnittstelle in `archiv.ki.basis-url` (aus der Infomaniak-Kontoübersicht übernehmen, nicht raten).
- Modelle per Konfiguration: `archiv.ki.chat-modell=mistralai/Mistral-Small-4-119B-2603` (Alternative `Apertus-v1.5-70B`, solange BETA nur zum Testen), `archiv.ki.embedding-modell` = BGE Multilingual Gemma2 (Test: Qwen3-Embedding-8B), `archiv.ki.reranker-modell=BAAI/bge-reranker-v2-m3` (Reserve `Qwen3-Reranker-0.6B`). Exakte Modellnamen aus der Modellliste im Konto übernehmen.
- Kosten pro Aufruf über `ki_verbrauch` nachvollziehen; Richtwert ca. CHF 1.50 pro 1'000 Chat-Fragen mit Mistral.
7. **Keine Geheimnisse im Code.** Tokens, Passwörter und Schlüssel nur über Umgebungsvariablen/Secret-Dateien; AbaNinja-Tokens in der DB nur verschlüsselt (AES-GCM, Schlüssel aus Umgebung).
8. **Das Archiv markiert nie etwas als bezahlt.** Zahlstatus wird nur aus AbaNinja gelesen.
9. **Beträge als `NUMERIC(12,2)` / `BigDecimal`**, nie `double`. Währung immer mitspeichern (Standard `CHF`).
10. **Zeit in UTC speichern**, in der Oberfläche `Europe/Zurich` anzeigen. Belegdatum ist `LocalDate`.

## Konventionen Backend
- REST unter `/api/v1`, JSON, OpenAPI über `quarkus-smallrye-openapi`; Fehler als RFC 9457 Problem Details.
- Optimistisches Locking über `version` und `If-Match`/`ETag` bei `PATCH`.
- Lange Arbeiten (OCR, KI, Import, Manifest) laufen als Auftrag in der Tabelle `public.auftrag`, abgeholt mit `SELECT … FOR UPDATE SKIP LOCKED`. Kein Message-Broker.
- Konfiguration in `application.properties` mit Profilen `dev`, `test`, `prod`; Infomaniak-Endpunkte und Modellnamen konfigurierbar.
- Logging strukturiert (JSON in `prod`), nie Dokumentinhalte, Beträge oder Personendaten loggen – nur IDs.

## Konventionen Frontend
- Deutsch (Schweiz) als einzige Sprache, trotzdem Texte in einer Datei `texte.de-CH.ts` sammeln.
- Mobile first (Handy-Erfassung ist der Hauptfall), WCAG 2.1 AA.
- Farben aus dem UriNet-Branding: Dunkel `#05070c`, Karten `#0d1017`, Gelb `#eeb111`, Hell `#f2efe7`; Schrift Inter.

## Tests und Qualität
- Jede Anforderung hat mindestens einen automatisierten Test, der ihre Akzeptanzkriterien prüft (Testname enthält die Nummer, z. B. `A13_3_dublette_wird_markiert`).
- Mandantentrennung wird in jedem Repository-Test mit zwei Mandanten geprüft.
- KI-Aufrufe in Unit-/Integrationstests mit WireMock; Qualität der Erkennung separat gegen den Golden-Datensatz (`backend/src/test/resources/golden/`, nicht im Repo, wenn echte Belege – nur anonymisierte).
- Definition of Done pro Aufgabe: Tests grün, `mvn verify` grün, keine neuen Sonar-/SpotBugs-Funde der Stufe hoch, OpenAPI aktualisiert, Akzeptanzkriterien im PR genannt.
