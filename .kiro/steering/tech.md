---
inclusion: always
---

# Technik und verbindliche Regeln

## Stack
| Bereich | Wahl | Hinweis |
| --- | --- | --- |
| Backend | Java 25, Quarkus (aktuelle LTS beim Projektstart), Maven | ein Modul `backend`, zwei Laufarten: `api` und `worker` (Konfig `archiv.rolle`) |
| Persistenz | PostgreSQL 17 mit `pgvector` und `pg_trgm`, Hibernate ORM mit Panache, Liquibase | Mandantentrennung **Schema pro Mandant** (`quarkus.hibernate-orm.multitenant=SCHEMA`); Changelogs `db/public` und `db/mandant`; Hibernate nur `validate` |
| Dateien | Infomaniak Object Storage (S3) über `quarkus-amazon-s3`, Endpoint-Override, Path-Style | eigenes Infomaniak-Public-Cloud-Projekt nur für den Object Storage; Object Lock GOVERNANCE, Versionierung an (Regel 2) |
| Zeitstempel | RFC 3161, **Pflicht** für jedes Manifest | Schweizer Anbieter [offen: Schweizer TSA-Anbieter – Entscheid vor Welle 5] |
| OCR | OCRmyPDF + Tesseract (`deu`, `eng`), img2pdf, libheif | im Worker-Image installiert, Aufruf per `ProcessBuilder` |
| Textextraktion | Apache Tika (nur für digitale PDFs/Office ohne OCR) | |
| KI | Quarkus LangChain4j (`quarkus-langchain4j-openai`) gegen Infomaniak AI Services (OpenAI-kompatibel) | Sprachmodell `mistralai/Mistral-Small-4-119B-2603`; Embeddings BGE Multilingual Gemma2; Reranker `BAAI/bge-reranker-v2-m3` |
| Anmeldung | Keycloak (OIDC, PKCE, TOTP-2FA), `quarkus-oidc` | bleibt (externer Treuhänder, Mandant 2); Mandant kommt aus dem Token-Claim `mandant` |
| Frontend | Angular (aktuelle Version), Standalone Components, Signals, Angular Material, PWA (`@angular/pwa`) | über Quinoa im API-Artefakt ausgeliefert; Offline-Warteschlange in IndexedDB |
| CSV | Apache Commons CSV, ICU4J (Zeichensatz-Erkennung) | |
| Betrieb | Docker Compose (`deploy/infomaniak/compose.yml`) auf einer eigenen Infomaniak-VPS, Traefik (Labels) | Domains `archiv.apps.urinet.ch`, `archiv-auth.apps.urinet.ch`; Details unter «Plattform und Betrieb» |
| Sicherung | `pg_dump` + Bucket-Kopie → restic, Zweitkopie bei einem zweiten Schweizer Anbieter | [offen: zweiter Schweizer Backup-Anbieter – Entscheid vor Welle 5]; Details unter «Plattform und Betrieb» |
| Tests | Cucumber (Features mit Tag `@req-<x.y>`), JUnit 5 für Unit-Tests, Quarkus Dev Services (PostgreSQL, Keycloak), Testcontainers (MinIO mit Object Lock), WireMock, AssertJ, ArchUnit, REST-assured, Playwright | Backend-Szenarien mit Java-Runner, Browser-Szenarien mit Cucumber.js + Playwright gegen den Prod-Build |

## Nicht verhandelbare Regeln
1. **Mandant nie aus Benutzereingaben.** Der Mandant stammt ausschliesslich aus dem verifizierten Token (`mandant`-Claim) bzw. aus dem API-Token. Kein Header, kein Query-Parameter, kein Prompt setzt ihn.
2. **Dateien sind unveränderbar.** Eine archivierte Datei wird nie überschrieben. Änderungen betreffen nur Metadaten und erzeugen eine neue Version in `dokument_version`. Object Lock gilt für Arten mit `gesetzliche_pflicht = ja`; Dokumente anderer Arten sind nur über die (spätere) Löschfunktion mit Protokoll löschbar. Sperren werden nur verlängert, nie verkürzt. Die Anwendung arbeitet mit einem Schlüssel **ohne** `s3:BypassGovernanceRetention`; der einzige Bypass-Schlüssel liegt offline beim Inhaber.
3. **SHA-256 bei Eingang.** Jede Datei erhält beim ersten Empfang eine Prüfsumme; sie wird protokolliert, bevor irgendetwas anderes passiert. Integrität wird über SHA-256 nachgewiesen, die S3-Versions-ID ist nur ein Zusatz.
4. **Protokolle sind append-only.** `zugriff_protokoll` und `dokument_version` erlauben nur `INSERT` (Trigger verhindert `UPDATE`/`DELETE`).
5. **KI rechnet nicht.** Summen, Zählungen und Fristen kommen aus SQL. Das Sprachmodell wählt nur Werkzeuge und formuliert. Kein freies Text-zu-SQL.
6. **Daten bleiben in der Schweiz.** Speicher, Datenbank und KI nur bei Infomaniak; die Zweitkopie der Sicherung und der Zeitstempel bei Schweizer Anbietern. Alle KI-Aufrufe (Sprachmodell, Embeddings, Reranker) gehen an das bestehende Konto bei Infomaniak AI Services (Modellliste geprüft 07.11.2026); keine Aufrufe an andere KI-Anbieter, auch nicht als Rückfall oder in Tests gegen echte Modelle. Die Regel gilt auch für echte Daten in Tests (z. B. Golden-Datensatz); CI-Logs und Benachrichtigungen enthalten nur IDs, keine Inhalte. Synthetische Testdaten dürfen in der CI verarbeitet werden.
   **Ausdrückliche Ausnahme Sicherung:** Die Sicherung läuft über GitHub Actions, also über Runner ausserhalb der Schweiz. Zulässig nur, weil der Server die Daten vorher asymmetrisch verschlüsselt (er hält nur den öffentlichen Schlüssel) und der Runner ausschliesslich verschlüsselte Daten sieht.
7. **Keine Geheimnisse im Code und nicht in Umgebungsvariablen.** Secrets liegen nur als Dateien unter `/run/secrets/`, Quelle sind die GitHub-Secrets des Repos. Nie als `environment` oder `env_file` in Compose, weil Umgebungsvariablen über die Docker-API (z. B. die Log-Ansicht) lesbar sind. AbaNinja-Tokens in der DB nur verschlüsselt (AES-GCM, Schlüssel aus der Secret-Datei `archiv_data_key`). Manifest- und Datenschlüssel (`archiv_data_key`) liegen zusätzlich im Passwort-Manager; der öffentliche Manifest-Schlüssel liegt unter `docs/`. Lokale Clients (Generator-Client, Drive-Import) lesen ihr API-Token aus einer Datei mit Rechten 600 (`~/.config/urinet-archiv/token` oder Pfad aus `ARCHIV_TOKEN_DATEI`), nie das Token selbst aus einer Umgebungsvariable.
8. **Das Archiv markiert nie etwas als bezahlt.** Zahlstatus wird nur aus AbaNinja gelesen.
9. **Beträge als `NUMERIC(12,2)` / `BigDecimal`**, nie `double`. Währung immer mitspeichern (Standard `CHF`).
10. **Zeit in UTC speichern**, in der Oberfläche `Europe/Zurich` anzeigen. Belegdatum ist `LocalDate`.
11. **Schema nur über Liquibase.** Bestehende Changesets werden nie geändert, nur neue angehängt. Jede Migration bleibt rückwärtskompatibel zum Vorgänger-Release (Rollback ohne Datenverlust).
12. **Hexagonale Architektur.** Abhängigkeiten nur `adapter → application → domain`; die Domain ist framework-frei. ArchUnit erzwingt das, ein Verstoss bricht den Build.

## Infomaniak AI Services (bestehendes Konto)
- Zugang: Token als Secret-Datei `/run/secrets/infomaniak_ai_token`, Produkt-ID in `archiv.ki.product-id`, Basis-URL der OpenAI-kompatiblen Schnittstelle in `archiv.ki.basis-url` (aus der Infomaniak-Kontoübersicht übernehmen, nicht raten).
- Modelle per Konfiguration: `archiv.ki.chat-modell=mistralai/Mistral-Small-4-119B-2603` (Alternative `Apertus-v1.5-70B`, solange BETA nur zum Testen), `archiv.ki.embedding-modell` = BGE Multilingual Gemma2 (Test: Qwen3-Embedding-8B), `archiv.ki.reranker-modell=BAAI/bge-reranker-v2-m3` (Reserve `Qwen3-Reranker-0.6B`). Exakte Modellnamen aus der Modellliste im Konto übernehmen.
- Der Spike AI Services hält fest, ob Infomaniak Eingaben speichert oder zum Training nutzt; der AVV wird unter `docs/vertrag/` referenziert.
- Kosten pro Aufruf über `ki_verbrauch` nachvollziehen; Richtwert ca. CHF 1.50 pro 1'000 Chat-Fragen mit Mistral.

## Plattform und Betrieb (nicht verhandelbar)
Dieser Abschnitt gilt auch ohne Zugriff auf andere Repos, Wikis oder Skills. Was hier fehlt, wird nachgefragt, nicht geraten.
- **Server:** eigene Infomaniak-VPS nur für das Archiv (nicht die bestehende kleine VPS). Grösse [offen: VPS-Grösse K5 – Entscheid vor Welle 1]; Leistungsziele der Requirements gelten für diese Grösse. Object Storage in einem eigenen Infomaniak-Public-Cloud-Projekt.
- **Reverse Proxy:** Traefik der VPS mit Let's Encrypt; die Dienste melden sich per Docker-Labels an. Kein eigener Proxy. Für Logs zählt die Client-IP aus `X-Real-Ip` von Traefik.
- **Deploy nur per GitHub-Actions-Workflow:** Image bauen, Bundle (Image, `deploy/infomaniak/compose.yml`, Secret-Dateien) per SSH an den Deploy-Befehl der VPS, danach Health-Prüfung; schlägt sie fehl, Rollback auf das Vorgänger-Image. Kein manuelles `docker compose up` auf dem Server.
- **Compose-Policy der VPS:** Speicher- und CPU-Limits, `cap_drop: ALL`, `no-new-privileges`, numerische uid ungleich 0, Volumes nur mit Eintrag für die Sicherung. Fremd-Images (PostgreSQL, Keycloak) werden als `<app>:…` umgetaggt, mit Eintrag im Infra-Repo der VPS.
- **Erreichbarkeit:** öffentlich nur `archiv.apps.urinet.ch` (API + PWA) und `archiv-auth.apps.urinet.ch` (Keycloak) sowie ein schlanker Health-Pfad. Kennzahlen und Detail-Health nur intern. Die Anbindung an das Monitoring der VPS ist eine Aufgabe in Welle 5.
- **Sicherung:** täglich PostgreSQL (Archiv und Keycloak inkl. Realm) und der Bucket (Original, Arbeitsfassung, PDF/A, Manifeste). Der Server verschlüsselt asymmetrisch, die zentrale Sicherung über GitHub Actions (Muster `maintenance`, Gate-Skript `backup-gate.sh` und Restore-Probe, erweitert um PostgreSQL und Bucket) holt die Daten ab und schreibt sie mit restic zum zweiten Schweizer Anbieter. Aufbewahrung 30 Tage, 12 Monate, 10 Jahresstände pro App.
- **Restore-Probe:** wiederkehrend und automatisiert; umfasst Datenbank und Dateien inklusive Anmeldung und endet mit einem grünen Prüflauf.
- **Notfallunterlagen:** Keycloak-Admin, TOTP-Backup-Codes, restic-Schlüssel, privater Sicherungsschlüssel (Export-Verschlüsselung), S3-Zugang inkl. Bypass-Schlüssel und öffentlicher Manifest-Schlüssel versiegelt an einem dokumentierten Ort ausserhalb des Systems.

## Konventionen Backend
- Paketstruktur hexagonal je Fachbereich (`domain`, `application`, `adapter`), siehe `structure.md`. Driven Ports für Ablage, KI, OCR und Integration, jeweils mit Dev-Stub.
- REST unter `/api/v1`, JSON, OpenAPI über `quarkus-smallrye-openapi`; Fehler als RFC 9457 Problem Details.
- Optimistisches Locking über `version` und `If-Match`/`ETag` bei `PATCH`.
- Lange Arbeiten (OCR, KI, Archivierung, Import, Manifest) laufen als Auftrag in der Tabelle `public.auftrag`, abgeholt mit `SELECT … FOR UPDATE SKIP LOCKED`. Kein Message-Broker.
- Konfiguration in `application.properties` mit Profilen `dev`, `test`, `prod`; Infomaniak-Endpunkte und Modellnamen konfigurierbar.
- Logging strukturiert (JSON in `prod`), nie Dokumentinhalte, Beträge oder Personendaten loggen – nur IDs.

## Konventionen Frontend
- Deutsch (Schweiz) als einzige Sprache, trotzdem Texte in einer Datei `texte.de-CH.ts` sammeln.
- Mobile first (Handy-Erfassung ist der Hauptfall), WCAG 2.1 AA.
- Farben aus dem UriNet-Branding: Dunkel `#05070c`, Karten `#0d1017`, Gelb `#eeb111`, Hell `#f2efe7`; Schrift Inter.

## Tests und Qualität
- Jedes Akzeptanzkriterium hat mindestens ein Cucumber-Szenario mit dem Tag `@req-<x.y>` (z. B. `@req-3.4` für Requirement 3, Kriterium 4). EARS-Kriterien werden direkt als Szenario geschrieben. Feature-Dateien unter `backend/src/test/resources/features/<fachbereich>/`, per Tag an Java-Runner (REST-assured) oder Cucumber.js + Playwright geroutet.
- Domain-Logik zusätzlich mit JUnit-5-Unit-Tests, ohne Quarkus.
- Mandantentrennung wird in jedem Repository-Test mit zwei Mandanten geprüft.
- KI-Aufrufe in Unit- und Integrationstests mit WireMock.
- **Golden-Datensatz** nur ausserhalb des Repos im Verzeichnis aus `ARCHIV_GOLDEN_DIR`. Im Repo liegen nur Schema und Test; `GoldenDatensatzIT` wird manuell gestartet, nicht in der CI.
- **Ausnahmen vom Szenario:** Kriterien mit dem Vermerk «Nachweis: manuell [Fabian]» oder «Nachweis: Dokument» brauchen kein automatisiertes Szenario, Kriterien mit «Nachweis: JUnit» (z. B. ArchUnit) kein Cucumber-Szenario. Der Nachweis (Protokoll, Bericht, Dokument oder Test) wird im PR verlinkt.
- **Definition of Done pro Aufgabe:** Szenarien und Tests grün, `mvn verify` grün (inkl. ArchUnit), keine neuen Sonar-/SpotBugs-Funde der Stufe hoch, OpenAPI aktualisiert, bestehende Liquibase-Changesets unverändert, erfüllte Akzeptanzkriterien mit `@req`-Tags im PR genannt, Checkbox in `tasks.md` im selben PR abgehakt.
