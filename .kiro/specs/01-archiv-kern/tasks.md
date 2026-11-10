# Implementation Plan — 01 Archiv-Kern

Aufgaben sind in **Wellen** gruppiert. Innerhalb einer Welle laufen Aufgaben mit verschiedener
Rolle parallel, eine Welle beginnt erst, wenn die vorherige abgeschlossen und das Gate
erfüllt ist. Rollen: **[Backend]**, **[Frontend]**, **[Infra]**, **[QA]**, **[Fabian]** (manuell, nicht vom Agenten).

Arbeitsweise je Aufgabe (Claude Code):
- Eine Sitzung, ein Branch (`feature/01-<aufgabe>-<stichwort>`), ein PR. Der PR nennt die erfüllten Kriterien als `@req-<x.y>` und hakt die Checkbox hier ab.
- Szenarien und Tests zuerst bzw. mitliefern: Cucumber-Features mit `@req-<x.y>`, Domain-Logik zusätzlich mit JUnit. Es gilt die Definition of Done aus `tech.md`.
- Kriterien mit «Nachweis: manuell [Fabian]», «Nachweis: Dokument» oder «Nachweis: JUnit» brauchen kein Cucumber-Szenario; der Nachweis wird im PR verlinkt.
- Steht ein Wert noch auf «[offen: …]», wird er nicht geraten; die Aufgabe wartet auf den Entscheid (Aufgaben 1.7, 1.10, 2.8, 2.9, 4.9, 5.5, 5.11).
- Aufgabennummern bleiben stabil; neue Aufgaben erhalten die nächste freie Nummer ihrer Welle.

## Welle 0 — Phase 0 Klären (Gate 0: ≥ 18/20 auf den zurückgehaltenen Belegen, höchstens 1 Fehler ab der Sicherheitsschwelle, Spikes dokumentiert; Stufe A aus 0.7 ist Voraussetzung für Welle 1, nicht für Gate 0)

- [x] 0.1 Golden-Datensatz zusammenstellen [Fabian]
  - 50 Belege gemäss Requirement 0.1 auswählen (genau 10 je Kategorie), Personendaten schwärzen (AHV-Nr., IBAN Dritter)
  - `erwartet.json` mit Art, Datum, Partner, Betrag, Belegnummer je Datei erfassen; fehlt ein Feld auf dem Beleg, bleibt es leer (Regel 0.7)
  - vor dem ersten Lauf je Kategorie 6 Belege zum Abstimmen und 4 zurückgehaltene festlegen (zusammen 30/20) und in `erwartet.json` vermerken; die Aufteilung bleibt danach unverändert
  - Ablage nur ausserhalb des Repositorys im Verzeichnis aus `ARCHIV_GOLDEN_DIR`, nie in Git, CI oder Tickets
  - Ergebnis 09.11.2026: 50 synthetische Belege (Generator `tools/golden_synth`), Aufteilung 30/20 fest; dazu 20 echte Belege für die Realitätsprüfung, bestätigt von Fabian
  - _Anforderungen: 0.1, 0.7, 0.8_

- [x] 0.2 Spike Infomaniak Object Storage [Infra] [Fabian]
  - [Fabian] eigenes Infomaniak-Public-Cloud-Projekt nur für den Object Storage anlegen und Zugang für den Spike als Secret-Datei bereitstellen
  - Bucket mit Object Lock + Versionierung anlegen, `PutObjectRetention` GOVERNANCE pro Objekt testen, Löschen mit/ohne `x-amz-bypass-governance-retention` prüfen
  - prüfen, ob sich ein Schlüssel ohne Recht `s3:BypassGovernanceRetention` ausstellen lässt (App-Schlüssel): Löschen und Verkürzen einer gesperrten Version müssen mit ihm scheitern, auch mit Bypass-Header
  - prüfen, ob der Modus COMPLIANCE verfügbar ist (Testobjekt mit kurzer Frist)
  - prüfen, ob Objekte ohne Retention im selben Bucket löschbar bleiben (Präfix `eingang/`)
  - Ergebnis in `docs/spikes/s3-object-lock.md`; gibt es keinen Schlüssel ohne Bypass-Recht, gilt COMPLIANCE für `manifest/` (6.11); bei negativem Befund zu Object Lock pro Objekt Rückfall aus design.md wählen. Befund: kein Schlüssel ohne Bypass-Recht im selben Projekt [T 6b–7g], Object Lock pro Objekt geht; entschieden 09.11.2026: Option B (ADR-007, Nachtrag 09.11.2026)
  - _Anforderungen: 0.4, 6.2, 6.3, 6.5, 6.11_

- [x] 0.3 Spike Infomaniak AI Services [Backend] [Fabian]
  - Das Konto und die Modelle bestehen bereits (Modellliste geprüft 07.11.2026); kein Konto anlegen, keine anderen Anbieter
  - [Fabian] API-Token als Secret-Datei (`/run/secrets/infomaniak_ai_token`), Produkt-ID und Basis-URL aus dem Infomaniak-Konto bereitstellen (`archiv.ki.product-id`, `archiv.ki.basis-url`)
  - Quarkus-Mini-App mit `quarkus-langchain4j-openai`, Base-URL und Produkt-ID aus Konfiguration
  - testen: JSON-Schema-Antwortformat, JSON-Modus, Tool Calling mit Mistral-Small-4, Embeddings-Endpunkt (Dimension notieren), Bild-Eingabe (K1), Reranker-Endpunkt (Form der API notieren)
  - [Fabian] festhalten, ob Infomaniak AI Eingaben speichert oder zum Training nutzt; den AVV unter `docs/vertrag/` referenzieren
  - Ergebnis in `docs/spikes/infomaniak-ai.md`
  - _Anforderungen: 0.5, 16.7_

- [x] 0.4 Test-Export 21.CRM [Fabian]
  - Kontakte und Verkaufschancen als CSV exportieren, 5 Beispielzeilen anonymisieren
  - als `backend/src/test/resources/csv/21crm-kontakte-beispiel.csv` und `…-verkaufschancen-beispiel.csv` ablegen; Spalten, Trennzeichen, Zeichensatz in `docs/spikes/21crm-export.md`
  - Ergebnis 08.11.2026: Kontakte erledigt; der Export enthielt keine Verkaufschancen. [Fabian] Nachexport mit 2 Test-Verkaufschancen vor Welle 4, dann Zuordnung bestätigen und die Beispieldatei füllen (design.md, offener Punkt)
  - _Anforderungen: 0.6, 13.1_

- [x] 0.5 Prototyp-Pipeline auf dem Golden-Datensatz [Backend]
  - Kommandozeilen-Klasse `GoldenLauf`: Datei → OCRmyPDF (lokales Docker-Image) → Text → Swiss QR Code deterministisch auslesen → KI-Vorschlag (Prompt gemäss design.md, QR-Werte vor KI-Werten) → Vergleich mit `erwartet.json` nach den Regeln aus 0.7
  - Prompt und Sicherheitsschwelle nur auf den 30 Belegen zum Abstimmen iterieren; die 20 zurückgehaltenen erst im Gate-Lauf verwenden
  - Bericht getrennt nach Abstimmungs- und Prüfteil: Trefferquote je Feld und gesamt, Fehler unter Feldern mit Sicherheit ab der Schwelle (≥, 0.9), Liste der Fehlschläge; der Bericht liegt im Verzeichnis aus `ARCHIV_GOLDEN_DIR`, ins Repository (`docs/spikes/golden-ergebnis.md`) kommen nur Kennzahlen und Beleg-IDs, keine Inhalte
  - im Repository nur das Schema von `erwartet.json` und `GoldenDatensatzIT` (manuell gestartet, nie in der CI)
  - Hostprüfung: `GoldenLauf` und `GoldenDatensatzIT` brechen vor dem ersten Beleg ab, wenn `archiv.ki.basis-url` nicht per `https` auf den Host `api.infomaniak.com` zeigt (exakter Vergleich, ohne Suffix-Treffer); Unit-Test mit `localhost`, `http` und Suffix-Trick (`api.infomaniak.com.example.org`) → Abbruch
  - Ergebnis 09.11.2026: Gate-Lauf auf dem Merge-Commit von PR #21: Prüfteil 20/20, 1 Fehler ab 0.8, Schwelle 0.8, Modell `google/gemma-4-31B-it` (`docs/spikes/golden-ergebnis.md`)
  - _Anforderungen: 0.1, 0.2, 0.3, 0.7, 0.8, 0.9, 7.11, 16.22_
  - _Abhängig von: 0.1, 0.3_

- [x] 0.6 Gate-Review und ADRs [Fabian]
  - Spike-Berichte und Golden-Ergebnis prüfen; Gate 0 nach 0.3 und 0.9 abnehmen, die Schwelle für 7.6 festhalten und beides in `docs/umsetzungsplan.md` (Abschnitt 6) eintragen
  - Konzept-Entscheide E2 (Eigenbau), E3 (PWA) bestätigen
  - D8 einer anderen Spec zuordnen oder bewusst streichen; Ergebnis in der Rückverfolgung von requirements.md und in P14 (`docs/umsetzungsplan.md`) nachtragen. Ergebnis: bewusst gestrichen (entschieden von Fabian am 09.11.2026: keine native App, nur Web-App), in der Rückverfolgung und in P14 nachgetragen
  - ADR-001 bis ADR-011 in `docs/adr/` per Merge freigeben (nach dem Gate-Lauf, in einem PR zusammen mit dem Gate-Ergebnis; Fabian, 09.11.2026); Spike-Erkenntnisse per PR in design.md zurückschreiben, bevor Welle 1 startet
  - Ergebnis 09.11.2026: Gate 0 bestanden mit Auflagen A1 und A2 (abgenommen von Fabian, `docs/umsetzungsplan.md`, Abschnitt 6); ADR-001 bis ADR-011 per Merge von PR #23 freigegeben
  - _Anforderungen: 0.3, 0.9_
  - _Abhängig von: 0.2, 0.3, 0.4, 0.5_

- [ ] 0.7 Stufe A auf der bestehenden VPS vorbereiten [Fabian] [Infra]
  - K5 (Grösse der eigenen VPS) ist nicht mehr Teil von Welle 0; Entscheid vor Welle 5 in 4.9, Wechsel in 5.11 (P1, geändert 08.11.2026)
  - [Fabian] Zugang des Deploy-Workflows zur bestehenden Infomaniak-VPS (Deploy-Befehl für das SSH-Bundle) als GitHub-Secret bereitstellen; DNS für `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch` auf diese VPS
    - Ergebnis 09.11.2026: Deploy-Key `archiv` im Infra-Repo (`deploy-keys/archiv.pub`, `einrichten.sh` gelaufen, Forced Command `deploy-app.sh archiv`); im Repo Secret `INFOMANIAK_SSH_KEY` und Variablen `INFOMANIAK_HOST`, `INFOMANIAK_KNOWN_HOSTS` gesetzt. DNS über den bestehenden Wildcard-Eintrag `*.apps.urinet.ch`, beide Namen lösen auf die VPS auf; `archiv-auth.apps.urinet.ch` deckt die Compose-Policy als `<app>-…` ohne Eintrag in `etc/app-hosts` ab
  - [Infra] Einträge im Infra-Repo der VPS (Compose-Policy, umgetaggte Images, internes Netz des Archivs); Ausgangswerte vor dem ersten Deploy festhalten (verfügbarer Speicher, freie Disk; Stand 08.11.2026: rund 1'140 MB, 15 GB; 09.11.2026: 1'130 MB verfügbar, 15 GB frei)
  - Datenregel Stufe A festhalten: nur Test- und anonymisierte Daten, keine echten Belege, keine Personal- oder Lohndaten
  - _Anforderungen: 16.1, 16.10, 16.15_

## Welle 1 — Fundament (Voraussetzung: Gate 0, Stufe A aus 0.7 bereit)

- [x] 1.1 Repository-Gerüst und CI [Infra]
  - Struktur gemäss `structure.md`, Paketstruktur hexagonal je Fachbereich (`domain`, `application`, `adapter`)
  - Quarkus-Projekt `backend` (Java 25, Extensions: rest-jackson, hibernate-orm-panache, jdbc-postgresql, liquibase, oidc, smallrye-openapi, smallrye-health, micrometer, scheduler, amazon-s3, langchain4j-openai, quinoa); Hibernate nur `validate`
  - Angular-Projekt `frontend` (Standalone, Material, PWA), über Quinoa im API-Artefakt ausgeliefert
  - Cucumber-Runner für Backend-Features unter `backend/src/test/resources/features/<fachbereich>/`, Cucumber.js + Playwright für Browser-Szenarien
  - GitHub Actions: `mvn verify` (Cucumber, JUnit, ArchUnit), `ng test`, Build über Quinoa, Docker-Images; Prüfungen: bestehende Liquibase-Changesets unverändert, OpenAPI und generierter Client aktuell, jedes Kriterium mit `@req`-Szenario oder Nachweis-Vermerk; CI-Logs nur mit IDs
  - _Anforderungen: 1.7, 16.1, 16.7, 16.13, 16.14_

- [x] 1.2 Entwicklungsumgebung [Infra]
  - Quarkus Dev Services für PostgreSQL 17 + pgvector und Keycloak; MinIO (Object Lock) und Mailpit für Entwicklung und Tests
  - Keycloak-Realm-Export `deploy/keycloak/realm-export.json` (Realm `urinet-archiv`) mit den Rollen `inhaber`, `mitarbeiter`, `treuhaender`, `geschaeftsstelle`, Claim-Mapper `mandant`, TOTP-Pflicht, ohne Benutzer und ohne Secrets; Testbenutzer je Rolle nur in `deploy/keycloak/realm-test.json` (design.md «Sicherheit»)
  - Dev-Stubs der Driven Ports (Ablage, KI, OCR, Integration) im Profil `dev`
  - Datasource aktiv, OIDC (`quarkus.oidc.tenant-enabled`), Dev Services und automatisches OpenAPI-Security-Schema wieder einschalten (in 1.1 vorübergehend aus)
  - _Anforderungen: 2.1, 2.2, 16.13_

- [ ] 1.3 Mandantenregister und Provisionierung [Backend]
  - Liquibase `db/public`: `mandant` (inkl. Geschäftsjahresende), `auftrag`, `api_client`
  - `MandantProvisionierung` (Schema, Liquibase `db/mandant`, Bucket mit Object Lock + Versionierung und Bucket Policy mit Deny auf `s3:BypassGovernanceRetention` als Schutz gegen Versehen (design.md «Ablage»; entschieden von Fabian am 09.11.2026), Seed der Dokumentarten aus `seed/<code>/dokumentarten.json`); Admin-Kommando `archiv mandant anlegen <code>`
  - beim Start Migration aller aktiven Mandanten über Liquibase, Hibernate nur `validate`
  - Test: zwei Mandanten anlegen, Schemas und Buckets getrennt; jeder Bucket trägt die Bucket Policy mit Deny auf `s3:BypassGovernanceRetention`
  - _Anforderungen: 1.1, 1.5, 1.7, 9.6_

- [ ] 1.4 Mandanten-Kontext und Rechtematrix [Backend]
  - `MandantTenantResolver`, `MandantKontext` für Worker, `@RolesAllowed` je Endpunkt
  - Rechtematrix A1–A14 als eine Domain-Regel (Rolle × Aktion), die alle Endpunkte abfragen; 404 vor 403 bei unsichtbaren Dokumenten; spätere Aufgaben ergänzen je Endpunkt die Szenarien ihrer Matrixzeilen
  - Szenarien: ohne Claim → 403; inaktiver Mandant → 403; fremde Dokument-ID → 404; `treuhaender` schreibt → 403; Matrix-Tabelle gegen die Domain-Regel
  - _Anforderungen: 1.2, 1.3, 1.4, 1.6, 2.2, 2.4, 2.6_

- [ ] 1.5 API-Tokens für Generatoren und Import [Backend]
  - `ApiTokenAuthMechanism`, Argon2id, an einen Mandanten gebunden, Scopes `generator` und `import`; ein Token erreicht nur die Endpunkte seines Scopes, sonst 403
  - Kommando `archiv token erstellen <mandant> <name> <scope>` (gibt Geheimnis einmal aus); Anlegen, Anzeigen und Widerruf nur mit Recht A14
  - _Anforderungen: 2.5, 2.20_

- [ ] 1.6 Querschnitt: Problem Details, Protokoll-Grundlage, ArchUnit [Backend]
  - `ExceptionMapper` → RFC 9457 mit `fehlercode`
  - Tabelle `zugriff_protokoll` mit Trigger (nur `INSERT`); `@Protokolliert`-Interceptor schreibt synchron in derselben Transaktion, IP aus `X-Real-Ip`; schlägt der Eintrag fehl, wird die Aktion nicht ausgeführt; verweigerte Zugriffe mit Aktion `VERWEIGERT`
  - ArchUnit-Regeln aus `structure.md` (Blueprint)
  - _Anforderungen: 10.2, 10.3, 10.7, 10.8, 16.13_

- [ ] 1.7 Stammdaten der Dokumentarten festlegen [Fabian]
  - Entwurf der Stammdaten-Tabelle in design.md prüfen und je Art Code, Gruppen, Pflichtfelder, Standard-Vertraulichkeit, Aufbewahrung (Jahre, ab wann) und `gesetzliche_pflicht` festlegen [offen: Werte der 11 Dokumentarten – Entscheid vor Welle 2]
  - Freigabe per Merge; ersetzt den [offen]-Vermerk in Requirement 9.2
  - _Anforderungen: 9.1, 9.2_

- [x] 1.8 Leitplanken im Repository [Infra] [Fabian]
  - Git-Hooks `pre-commit` und `pre-push` mit Secret-Scan; Secret-Scan zusätzlich als CI-Workflow, Pflicht vor dem Merge
  - Claude-Code-Hooks der Harness-Vorlage in `.claude/settings.json`: Änderungen an bestehenden Changesets unter `db/**` nachfragen (harness.yml) und in der CI blockieren (pruefe-liquibase.sh), keine Secret-Dateien lesen oder schreiben; die Tests laufen in `mvn verify` und in der CI (Entscheid Fabian 09.11.2026: kein Hook, der nach dem Bearbeiten einer Java-Datei Tests ausführt)
  - [Fabian] Leitplanken-Vorlage übernehmen; `main` per pre-push-Hook gesperrt (Branch-Schutz im Free-Plan nicht verfügbar, Entscheid Fabian 09.11.2026)
  - _Anforderungen: 16.9, 16.14_

- [ ] 1.9 Deploy-Workflow und Compose für die VPS [Infra]
  - `deploy/infomaniak/compose.yml`: API (mit PWA), Worker, PostgreSQL, Keycloak; Traefik-Labels für `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch`; Compose-Policy (Limits, `cap_drop: ALL`, `no-new-privileges`, uid ungleich 0); Secrets nur als Dateien unter `/run/secrets/`, nie `environment` oder `env_file`
  - Images von PostgreSQL und Keycloak als `<app>:…` umtaggen, Eintrag im Infra-Repo der VPS
  - GitHub-Actions-Workflow: Image bauen, Bundle per SSH an den Deploy-Befehl der VPS, Health-Prüfung, bei Fehler Rollback auf das Vorgänger-Image ohne Rückbau der Datenbank
  - erster Deploy des Gerüsts auf Stufe A (VPS aus 0.7) in der Form aus tech.md (API und Worker in einem Container, Limits im Speicherbudget), nur mit Testdaten (15.3, 16.15)
  - nach jedem Deploy auf Stufe A Speicherabbrüche der VPS und Health der anderen Apps prüfen; tritt 16.18 ein, gilt das Abbruchkriterium aus 1.10
  - _Anforderungen: 16.1, 16.9, 16.10, 16.11, 16.18_
  - _Abhängig von: 0.7, 1.1_

- [ ] 1.10 Stufe A einrichten und Speicherbudget messen [Infra]
  - Stufenwerte versioniert unter `deploy/infomaniak/`: `archiv-api` mit Image `archiv-worker` und `archiv.rolle=api,worker`, `archiv.stufe=A` (2.3), `archiv.worker.parallel=1`, OCR seitenweise (`--jobs 1`); knappe Heap- bzw. Speicher-Einstellungen für JVM, PostgreSQL und Keycloak; Summe der `mem_limit` ≤ 700 MiB, `cpus` zusammen ≤ 0.75
  - Netz prüfen: Datenbank (`archiv-db`), Worker und Sicherung nur im eigenen internen Docker-Netz des Archivs; am Netz «web» von Traefik hängen nur `archiv-api` (API/PWA) und `archiv-keycloak` (öffentlich nur `/realms/` und `/resources/`, Admin-Konsole nicht öffentlich)
  - Buckets der Stufe A nach dem Entscheid aus design.md (Open Points: Bucket-Namen der Stufe A, Entscheid vor Welle 1) konfigurieren
  - Messung nur mit synthetischen Testbelegen: Spitzenverbrauch je Dienst im Leerlauf, beim Upload und bei OCR mehrseitiger Belege; verfügbarer Speicher der VPS gegenüber den Ausgangswerten aus 0.7; Health der anderen Apps während der Messung; Ergebnis in `docs/spikes/stufe-a-speicher.md` (nur Zahlen, keine Namen anderer Apps); darin [Fabian] bestätigen, welche Testdaten auf Stufe A geladen wurden (16.15)
  - **Abbruchkriterium:** Das Archiv braucht mehr als das Budget (Summe `mem_limit` > 700 MiB oder `cpus` > 0.75), ein Container auf der VPS wird wegen Speichermangel beendet, oder die Health einer anderen App fällt während der Messung aus → Archiv-Dienste auf Stufe A stoppen, Fabian informieren, K5 (4.9) und Wechsel (5.11) vorziehen (16.18)
  - _Anforderungen: 16.1, 16.10, 16.15, 16.18_
  - _Abhängig von: 0.7, 1.9_

- [x] 1.11 Profil lokal und Prüfung der KI-Basis-URL [Backend]
  - Quarkus-Profil `lokal` nach design.md («KI», Profil `lokal`, ADR-012): erbt von `dev`, `archiv.ki.basis-url=http://localhost:1234/v1`, Modellname `archiv-lokal` (für `archiv.ki.chat-modell` und `archiv.ki.erfassung-modell`), Dev-Stub von `KiPort` für das Sprachmodell aus (`archiv.ki.stub=false`), Embeddings und Reranker als Stub ohne Vektoren, kein Infomaniak-Token
  - echten OCR-Adapter im Profil `lokal` einschalten (`%lokal.archiv.ocr.stub=false`, OCRmyPDF als lokales Docker-Image wie in 0.5), damit das Sprachmodell den echten Volltext erhält (7.1); wirksam, sobald der OCR-Adapter aus 2.6 besteht
  - Startprüfung `KiBasisUrlPruefung` für `archiv.ki.basis-url` und `archiv.ki.reranker-url` (Spike 0.3), für Embeddings mit Spec 02: Host nur `api.infomaniak.com` (HTTPS), in `lokal` zusätzlich `localhost` bzw. `127.0.0.1`, in `test` nur mit dem Port der WireMock-Testressource (nie 1234); bei aktivem Stub entfällt die Prüfung der zugehörigen URL (leer zulässig); `lokal` zusammen mit `prod` bricht ab; die Hostprüfung von `GoldenLauf` und `GoldenDatensatzIT` stammt aus 0.5, der Gate-0-Lauf (0.6) liegt vor dieser Aufgabe
  - Konfigurationstest als reiner Unit-Test (JUnit, ohne Quarkus-Start und ohne aktives Profil `lokal`; Prüflogik mit Profilnamen und URI als Eingabe, Profilwerte aus `application.properties` per `SmallRyeConfigBuilder` gelesen): fremder Host (z. B. `api.openai.com`), Suffix-Trick (`api.infomaniak.com.example.org`), Benutzerangabe in der URI, `localhost` im Profil `prod`, `localhost:1234` im Profil `test`, fremder Host in `archiv.ki.reranker-url`, `lokal` zusammen mit `prod` → Prüfung bricht ab, die Meldung nennt nur Host und Profil; `api.infomaniak.com` in `prod`, `localhost` in `lokal`, `localhost` mit WireMock-Port in `test` und `dev` mit Stub und leerer Basis-URL → Prüfung gelingt; Profil `lokal`: keine Eigenschaft verweist auf `/run/secrets/infomaniak_ai_token` (fehlende Datei → Prüfung gelingt), Embedding- und Reranker-Port sind Stubs
  - [Fabian] bei der ersten lokalen Nutzung prüfen und im README festhalten, ob LM Studio das Schema von `MetadatenVorschlag` (inkl. nullable/`anyOf`) mit `strict` durchsetzt (Vorgehen mit synthetischem Text in `backend/README.md`)
  - Anleitung in `backend/README.md`, Abschnitt «Lokales Modell (LM Studio)»: CLI mit `npx lmstudio install-cli`; Modell nach ADR-012 als GGUF mit `lms get <name>@<quant> --gguf`, `model_key` mit `lms ls`; Speicherbedarf mit `lms load <model_key> --estimate-only` prüfen, dann `lms load <model_key> --context-length <n> --identifier archiv-lokal` (Kontextlänge so, dass der Prompt aus design.md passt, Wert im README); Server mit `lms server start --port 1234` ohne `--bind` (Standard `127.0.0.1`) und ohne `--cors`; nie `--bind 0.0.0.0`, `LMS_SERVER_HOST` oder «Serve on Local Network», keine Cloud-Modelle und keine Websuche in LM Studio; Start des Archivs mit `mvn quarkus:dev -Dquarkus.profile=lokal`; Hinweise: Ergebnisse gelten nicht als Nachweis (ADR-012, Entscheid 8), der zurückgehaltene Teil des Golden-Datensatzes (0.8) wird nie lokal verwendet, HEIC vor dem Senden wandeln
  - die CI aktiviert das Profil `lokal` nie (der Konfigurationstest startet Quarkus nicht); KI-Aufrufe in Tests bleiben WireMock
  - _Anforderungen: 16.20, 16.21, 16.22_
  - _Abhängig von: 0.5, 1.1, 1.2_

## Welle 2 — Dokumente, Ablage, Verarbeitung

- [ ] 2.1 Dokumentarten und Aufbewahrung [Backend] [Fabian]
  - Entität `Dokumentart` mit Standard-Vertraulichkeit und `gesetzliche_pflicht`; REST `/api/v1/dokumentarten` (CRUD nur mit Recht A11; Löschen nur ohne Dokumente, sonst 409 `DOKUMENTART_IN_GEBRAUCH` → inaktiv); JSON-Schema-Validierung für `zusatz` (422 `ZUSATZ_UNGUELTIG`)
  - Seed `backend/src/main/resources/seed/urinet/dokumentarten.json` mit den 11 Arten aus 1.7; ein Test prüft den Seed Feld für Feld gegen die Stammdaten-Tabelle in design.md
  - Spalte «Beschreibung» in Seed und Stammdaten-Tabelle (design.md) mit den Abgrenzungen zwischen den Arten (z. B. Sozialversicherungen AHV, IV, EO, FAK = `BEHOERDE`; UVG- und Krankentaggeld-Versicherer = `VERSICHERUNG`); sie fliesst in den KI-Prompt (entschieden von Fabian am 09.11.2026)
  - `AufbewahrungRechner`: Ende des bestätigten Geschäftsjahres (Geschäftsjahresende des Mandanten) plus Jahre der Regel; ohne Belegdatum Eingangsdatum; Verträge ab `gueltig_bis`, ohne `gueltig_bis` unbefristet; Neuberechnung verlängert nur; Frist in der Vergangenheit → «Frist abgelaufen»; keine Löschung bei Fristablauf
  - Unit-Tests: Beleg 2026 → 31.12.2036; abweichendes Geschäftsjahresende; ohne Belegdatum; Vertrag ohne `gueltig_bis`; Neuberechnung mit früherem Datum → Sperre bleibt, Konflikt protokolliert
  - [Fabian] Abnahme: Seed gegen die freigegebene Tabelle prüfen
  - _Anforderungen: 2.17, 9.1–9.9_
  - _Abhängig von: 1.7_

- [ ] 2.2 Datenmodell Dokument und Sichtbarkeit [Backend]
  - Liquibase `db/mandant`: `dokument` (Status `NEU`, `VERARBEITUNG`, `EINGANGSKORB`, `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER`, `ARCHIVIERT`, `FEHLER`, `VERWORFEN`; Vertraulichkeit `NORMAL`, `INTERN`, `PERSONAL`; Erfasser, `person_benutzer`), `dokument_datei`, `dokument_finanz`, `dokument_vertrag`, `dokument_version` (Trigger: nur `INSERT`), generierte `volltext_tsv` inkl. Tags
  - Indizes gemäss design.md: Dubletten je Datei nur über nicht verworfene Dokumente, Belegnummer eindeutig je Art nur bei eigenen Arten, `gin(tags)`, `geschaeftsjahr`, `dokument_finanz(betrag)`, `richtung`, Belegnummer
  - Panache-Entitäten im Adapter; `VertraulichkeitFilter` nach A2–A4: unbestätigte Art gilt als `PERSONAL` (sichtbar für Rollen mit A7 und den Erfasser); unsichtbare Dokumente fehlen in Listen, Treffern und Zählern, Direktzugriff 404
  - Szenarien je Rolle und Vertraulichkeit mit zwei Mandanten
  - Eingangskorb-Abfrage nach A5: `mitarbeiter` erhält nur selbst erfasste Dokumente
  - _Anforderungen: 2.3, 2.8, 2.9, 2.10, 2.21, 2.24, 10.1, 10.3_

- [ ] 2.3 Ablage-Service S3 [Backend]
  - Port `AblagePort` mit S3-Adapter und Dev-Stub: `ablegenEingang`, `archivieren`, `sperreVerlaengern`, `loeschenEingang`, `vorsignierteUrl`
  - `archivieren`: Original, Arbeitsfassung (falls vorhanden) und PDF/A nach `archiv/` kopieren, Prüfsummen vergleichen, Versions-ID je Datei in `dokument_datei` speichern; Sperre GOVERNANCE bis `aufbewahren_bis` nur bei `gesetzliche_pflicht`, unbefristete Dokumente dieser Arten 10 Jahre (Reihenfolge der Prüfung gemäss design.md, `retainUntil`); danach `eingang/` löschen
  - nur der App-Schlüssel aus `s3_app_zugang`; der S3-Adapter setzt nie `x-amz-bypass-governance-retention` (SDK: `bypassGovernanceRetention`) und verkürzt keine Sperre (6.5; Option B, ADR-007, Nachtrag 09.11.2026); `vorsignierteUrl` nur für die gespeicherte Versions-ID; Manifeste unter `manifest/` im Modus COMPLIANCE (6.11)
  - Sperrmodus von `manifest/` per Konfiguration (entschieden von Fabian am 09.11.2026): Standard COMPLIANCE; ab Stufe B (16.17; dort kommen echte Belege ins Archiv) fest COMPLIANCE mit der Frist nach 15.4, API und Worker brechen den Start ab, falls dort ein anderer Modus oder eine kürzere Frist konfiguriert ist; abweichend (GOVERNANCE oder kürzere Frist) nur in Tests, in der lokalen Entwicklung und in den Stufenwerten der Stufe A unter `deploy/infomaniak/` (nur Testdaten, 16.15), damit Test-Buckets löschbar bleiben; die Regel hängt nicht am Profil `prod`, weil Stufe A mit denselben Images und demselben Deploy-Workflow läuft wie Stufe B (design.md «Protokoll und Manifest»); die Stufe erkennt die Startprüfung am Konfigurationswert `archiv.stufe` (`A` | `B`) in den Stufenwerten unter `deploy/infomaniak/`, fehlt er, gilt `B` (sicherer Standard: COMPLIANCE wird erzwungen), Stufe A setzt ausdrücklich `archiv.stufe=A`, ebenso die Konfiguration der Tests und der lokalen Entwicklung; Test: abweichender Sperrmodus oder kürzere Frist mit den Stufenwerten der Stufe B → Start von `archiv-api` und `archiv-worker` bricht ab; ohne `archiv.stufe` → wie mit `B`, Start bricht ab
  - MinIO aus den Compose Dev Services mit Object Lock: Löschen und Verkürzen einer gesperrten Version scheitern mit den Aufrufen der Anwendung (ohne Bypass-Header), Prüfsummen-Abweichung bricht ab, Art ohne gesetzliche Pflicht ohne Sperre, Sperre nur verlängerbar, Objekt unter `manifest/` in COMPLIANCE auch mit Bypass-Header weder löschbar noch verkürzbar (6.11; Test-Bucket ohne Bucket Policy, sonst greift deren Deny [T 6c])
  - Nachweis 6.5 (JUnit): Request-Interceptor in den Integrationstests prüft, dass kein S3-Aufruf der Anwendung `x-amz-bypass-governance-retention` sendet; ergänzend ArchUnit-Regel gegen `bypassGovernanceRetention` im Produktionscode (Testklassen ausgenommen)
  - _Anforderungen: 6.2, 6.3, 6.4, 6.5, 6.9, 6.11, 6.12, 14.6_

- [ ] 2.4 Upload-Endpunkt [Backend]
  - `POST /api/v1/dokumente` multipart (Recht A1), je Datei: SHA-256 vor allem anderen in `dokument_datei`, Typprüfung per Tika-Detektor (415 `DATEITYP_UNBEKANNT`), über 50 MB (413 `DATEI_ZU_GROSS`), leer oder beschädigt (422 `DATEI_UNGUELTIG`), Dublettenprüfung (`DUBLETTE` mit `vorhandenId`, ohne Leserecht nur «bereits vorhanden»)
  - ein Ergebnis je Datei, gültige Dateien trotz abgelehnter Nachbarn; `zusammenfassen`; Handy-Upload mit Original und Arbeitsfassung je Seite
  - Antwort 202, Status `NEU`, Auftrag `VERARBEITEN` einreihen; Lastskript für ≤ 2 s bereitstellen, Nachweis erst auf Stufe B in 5.11 (Messbedingungen aus 4.9, 16.16), in Stufe A nur Funktion
  - _Anforderungen: 2.7, 3.1–3.8, 4.7, 6.1_

- [ ] 2.5 Auftragsschlange [Backend]
  - `AuftragService.einreihen(typ, nutzlast)`, `AuftragPoller` mit `SKIP LOCKED`, insgesamt 3 Versuche mit wachsender Wartezeit, Mandantenkontext pro Auftrag
  - Zeitgrenze je Auftragstyp (design.md) und Lebenszeichen; verwaiste `LAEUFT`-Aufträge nach Ablauf der Zeitgrenze freigeben, zählt als Versuch
  - Tests: zwei Worker holen denselben Auftrag nie doppelt; Zeitüberschreitung und verwaister Auftrag zählen als Fehlversuch
  - _Anforderungen: 1.6, 5.4, 5.6, 5.7_

- [ ] 2.6 Worker-Image und Verarbeitung [Backend] [Infra]
  - Dockerfile Worker (JVM + ocrmypdf, tesseract-ocr-deu/eng, img2pdf, libheif-examples, poppler-utils, ghostscript) nach Compose-Policy (uid ungleich 0)
  - Port `OcrPort` mit Adapter und Dev-Stub; `VerarbeitenHandler`: Status `VERARBEITUNG`, HEIC→JPEG, Bilder→PDF, bei Handy-Erfassung PDF/A aus der Arbeitsfassung, OCRmyPDF (`--skip-text` bei Textebene), `pdftotext`, Vorschaubilder; Folgeauftrag `KI_VORSCHLAG`; nach 3 Fehlversuchen `FEHLER` mit Fehlertext
  - signierte PDFs: PDF/A auch bei Verlust der Signatur, Original unverändert; keine PDF/A möglich (z. B. verschlüsselt) → Vermerk `pdfa_fehlt` und Eingangskorb
  - veraPDF-Prüfung im Test; Lastskript für die Laufzeit einseitiges Foto als p95 ≤ 30 s bereitstellen, Nachweis erst auf Stufe B in 5.11 (Messbedingungen aus 4.9, 16.16)
  - _Anforderungen: 5.1–5.10_

- [ ] 2.7 Frontend-Grundgerüst und Upload [Frontend]
  - Layout mobil/Desktop im UriNet-Branding, Login per OIDC (PKCE) gegen Keycloak, Navigation mit Eingangskorb-Zähler, Menüpunkte nach Rechtematrix
  - Seite Upload mit Drag & Drop, Mehrfachauswahl, «als ein Dokument», Fortschritt, Ergebnis je Datei (angenommen, «bereits vorhanden», Fehlermeldung)
  - API-Client aus OpenAPI generieren
  - _Anforderungen: 3.1, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 8.6_

- [ ] 2.8 Mindestversionen der Handys festlegen [Fabian]
  - Mindestversionen iOS (Safari) und Android (Chrome) festlegen [offen: Mindestversionen iOS und Android – Entscheid vor Welle 3] und per PR in Requirement 4.1 nachtragen; Testgeräte für 3.7 bereitstellen
  - _Anforderungen: 4.1_

- [ ] 2.9 Ziel für echte Belege festlegen (Auflage A2 aus Gate 0) [Fabian]
  - Zielwerte für die Realitätsprüfung vor der Übernahme, Aufteilung der echten Belege in Abstimmungs- und zurückgehaltenen Teil und Umgang mit Änderungen aus dem Abstimmen (Prompt, Regeln, Parameter: neuer Golden-Lauf nach Req 0.9 oder nur Regeln, die Sicherheiten senken wie A1) entscheiden: [offen: Zielwerte und Aufteilung der echten Belege, Umgang mit Änderungen aus dem Abstimmen – Entscheid vor Welle 3]; Ergebnis in `docs/umsetzungsplan.md`, Abschnitt 6, und per PR in Req 0.10 (alt → neu) nachtragen
  - Der erneute Lauf vor der Übernahme ist Voraussetzung von 5.8
  - _Anforderungen: 0.10_

## Welle 3 — KI-Vorschläge, Eingangskorb, Handy-Erfassung

- [ ] 3.1 Kontakte und Partner-Abgleich [Backend]
  - Liquibase `kontakt`, `verkaufschance` mit `pg_trgm`; `Normalisierer` gemäss design.md; REST `/api/v1/kontakte` (lesen; ändern nur mit Recht A12)
  - `PartnerAbgleich` (E-Mail, UID, IBAN, Ähnlichkeit ≥ 0.6, «unsicher» bei Abstand < 0.1) mit Tests
  - _Anforderungen: 2.18, 7.4_

- [ ] 3.2 Metadaten-Extraktor [Backend]
  - Port `KiPort` mit Adapter (LangChain4j) und Dev-Stub; `MetadatenExtraktor` mit Prompt aus Welle 0, Schema-Validierung + eine Nachfrage, Sicherheiten je Feld, Modell (`archiv.ki.erfassung-modell`), Aufrufparameter und Schwelle aus Gate 0 (design.md «KI»), `ki_verbrauch`
  - Swiss QR Code vor dem KI-Aufruf deterministisch auslesen, seine Werte vor den KI-Werten verwenden
  - Vorschlag inkl. Vertraulichkeit (vorbelegt mit dem Standard der Art); das Belegnummer-Muster setzt die Art nur, wenn der Mandant Aussteller ist (Testfall Lieferantenrechnung `2026-123`)
  - `KiVorschlagHandler`: Vorschlag speichern, Partner verknüpfen, Status `EINGANGSKORB`; bei KI-Ausfall ohne Vorschlag weiter; Logs nur mit IDs
  - WireMock-Tests für gültige, ungültige und fehlende Antworten; ein WireMock-Test prüft den Request: `model` = Wert von `archiv.ki.erfassung-modell` (nicht `archiv.ki.chat-modell`), `temperature` 0, `top_p` 1, `presence_penalty` und `frequency_penalty` 0, `response_format` `json_schema` mit `strict`
  - Befund Gate-Lauf (`docs/spikes/golden-ergebnis.md`): Belegnummer-Vorschläge mit hoher Sicherheit gezielt prüfen, ohne Prompt, Modell oder Aufrufparameter gegenüber Gate 0 zu ändern (sonst neuer Golden-Lauf)
  - Auflage A1 (Gate 0): Sicherheit der Belegnummer höchstens 0.7, ausser die Belegnummer entspricht einem Muster des Mandanten und der Mandant ist Aussteller (design.md «KI», Nachbearbeitung); Unit-Tests: eigener Beleg im Muster (keine Begrenzung), Fremdbeleg ohne Muster (Begrenzung), Lieferantenrechnung `2026-123` im Muster des Mandanten (Req 7.12, Begrenzung)
  - _Anforderungen: 7.1, 7.2, 7.3, 7.5, 7.6, 7.8, 7.11, 7.12_

- [ ] 3.3 Lernen aus Korrekturen [Backend]
  - Beim Bestätigen Differenz Vorschlag ↔ Ergebnis als `ki_beispiel` speichern, mit den Merkmalen IBAN, UID, E-Mail, Swiss QR Code und Belegnummer-Muster
  - Vorauswahl vor dem KI-Aufruf deterministisch über diese Merkmale, ohne Treffer die 5 neuesten; kein zweiter KI-Aufruf
  - Korrekturquote je Feld als Kennzahl fortschreiben
  - Szenario: Nach einer Korrektur bei Partner X enthält der nächste Beleg mit der IBAN von X das Beispiel
  - _Anforderungen: 7.7, 7.9, 7.10_
  - _Abhängig von: 3.2_

- [ ] 3.4 Bestätigen, Verwerfen, Archivieren, Wiederholen [Backend]
  - `POST /api/v1/dokumente/{id}/bestaetigen` (Recht A7): nur aus `EINGANGSKORB`, sonst 409; Pflichtfelder (422 `PFLICHTFELD_FEHLT`); Vertraulichkeit übernehmen; `aufbewahren_bis`; Frist in der Vergangenheit → «Frist abgelaufen», der Inhaber entscheidet; Belegnummer einer eigenen Art schon vergeben → 409, bei Fremdbelegen nur Warnung; Status `ARCHIVIERUNG` und Auftrag `ARCHIVIEREN`
  - `ArchivierenHandler`: `AblageService.archivieren`; Status `ARCHIVIERT` erst nach Kopie, Prüfsummenvergleich und Sperre; bei Fehler `ARCHIVIERUNG_FEHLER`, bestätigte Metadaten bleiben, E-Mail an den Inhaber (nur Dokument-ID)
  - `POST /api/v1/dokumente/{id}/verwerfen` (Recht A9): aus `EINGANGSKORB` und `FEHLER` Dateien löschen, Protokoll mit Prüfsumme und Grund, Status `VERWORFEN`; aus `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER` oder `ARCHIVIERT` → 409
  - `POST /api/v1/dokumente/{id}/wiederholen` (Recht A10): `FEHLER` → `NEU` (Verarbeitung neu), `ARCHIVIERUNG_FEHLER` → `ARCHIVIERUNG` (nur Archivierung, Metadaten unverändert), sonst 409
  - Dokument mit `pdfa_fehlt`: der Inhaber archiviert das Original allein oder verwirft
  - _Anforderungen: 2.13, 2.15, 2.16, 2.22, 5.9, 6.3, 6.4, 6.6, 6.7, 6.8, 6.13, 8.2, 8.3, 8.5, 8.8, 8.9, 8.10, 9.8_

- [ ] 3.5 Metadaten ändern und Versionen [Backend]
  - `PATCH /api/v1/dokumente/{id}` (Recht A6) mit `If-Match`: 412 `VERSION_KONFLIKT` bei Konflikt, 428 ohne Header; Grund Pflicht bei `ARCHIVIERT`, vorherige Fassung in `dokument_version`
  - Vertraulichkeit und `person_benutzer` nur mit Recht A8
  - Änderung von Belegdatum, Art, Geschäftsjahr oder `gueltig_bis` → `aufbewahren_bis` neu berechnen, Sperre nur verlängern (`sperreVerlaengern`), Konflikt protokollieren
  - _Anforderungen: 2.12, 2.14, 2.23, 9.7, 10.1, 10.4, 10.6_

- [ ] 3.6 Eingangskorb-Oberfläche [Frontend]
  - Liste nach Recht A5 (`mitarbeiter` nur selbst erfasste) mit Vorschaubild, Vorschlägen, «bitte prüfen»-Markierung, Partnerauswahl mit Suche, Bestätigen in ≤ 2 Tipps, Verwerfen mit Rückfrage und Grund
  - `FEHLER` mit Fehlertext, «erneut verarbeiten» und «verwerfen»; `ARCHIVIERUNG_FEHLER` mit «Archivierung wiederholen»; Hinweise «Frist abgelaufen», `pdfa_fehlt` und Belegnummer-Warnung bei Fremdbelegen
  - Aktionen nur nach Rechtematrix sichtbar
  - Playwright im 375-px-Viewport
  - _Anforderungen: 2.11, 2.24, 8.1–8.10_

- [ ] 3.7 Handy-Erfassung (PWA) [Frontend] [Fabian]
  - Kamera mehrseitig, jscanify im Web Worker (Kanten, Zuschnitt, Entzerrung), ohne erkannte Kanten das ganze Bild, Vorschau anpassen/verwerfen, JPEG 2'480 px
  - Upload je Seite: Rohaufnahme als Original, Zuschnitt als Arbeitsfassung
  - Offline-Warteschlange (IndexedDB) mit Zähler, automatischer Upload bei `online`; `DUBLETTE` gilt als übertragen und wird gelöscht; nur Netzwerkfehler und 5xx zählen als Fehlversuch, offline nicht; 3 Fehlversuche → manuell wiederholen; 4xx sofort fehlerhaft mit Meldung des Servers (401 → neu anmelden)
  - [Fabian] Test auf echtem iPhone (Safari) und Android (Chrome) mit den Mindestversionen aus 2.8: Installation und 4.2 bis 4.8
  - _Anforderungen: 4.1–4.8_
  - _Abhängig von: 2.8_

## Welle 4 — CSV-Import, Suche, Generatoren, Altbestand

- [ ] 4.1 CSV-Erkennung und Spaltenzuordnung [Backend]
  - Upload `/api/v1/importe/crm` (Recht A12), SHA-256, ICU4J-Zeichensatz, Trennzeichen aus Kopfzeile, Grenzen (Spalte für `name` Pflicht, bei 21.CRM `Name` oder `Organisation`; Zeile ohne Namen oder mit falscher Spaltenzahl «fehlerhaft»; ≤ 10'000 Zeilen; ohne Datenzeilen abgelehnt); Parser nach RFC 4180 (Felder in Anführungszeichen mit `;` oder Zeilenumbruch)
  - Profil «21.CRM» aus design.md (Erkennung der Kopfzeile, Name aus `Name` oder `Organisation`, E-Mail/Telefon über Index, gelöschte Zeilen übersprungen, Zuordnung nach Spaltenposition) als Vorschlag im Zuordnungsdialog und Synonymliste für andere Dateien (beide design.md, Befund in `docs/spikes/21crm-export.md`); `import_zuordnung` lesen/speichern; abweichende Kopfzeile → Zuordnungsdialog
  - Tests mit den Beispieldateien aus 0.4 (`21crm-kontakte-beispiel.csv` Windows-1252/Semikolon, `21crm-verkaufschancen-beispiel.csv` ohne Datenzeilen) plus daraus abgeleitete Varianten: UTF-8 mit BOM, Komma, Feld in Anführungszeichen mit `;` und Zeilenumbruch, Zeile mit falscher Spaltenzahl, Zeile ohne Namen, `Ist gelöscht = true`, eigene Datei nur mit Kopfzeile für Req 13.11 (die Verkaufschancen-Beispieldatei wird nach dem Nachexport gefüllt)
  - _Anforderungen: 2.18, 13.1, 13.2, 13.10, 13.11_
  - _Abhängig von: 0.4_

- [ ] 4.2 Vorschau und Abgleich [Backend]
  - Auftrag `CSV_VORSCHAU`: Abgleich Kontakt-ID → E-Mail → normalisierter Name + PLZ (ein exakter Treffer zählt), Ergebnis je Zeile in `import_zeile`, Zähler im Lauf
  - «mögliche Dublette» bei mehreren Kandidaten, sich widersprechenden Stufen oder nur Ähnlichkeit ab der Schwelle aus design.md
  - _Anforderungen: 13.3, 13.4, 13.5_

- [ ] 4.3 Übernahme und Protokoll [Backend]
  - Auftrag `CSV_UEBERNEHMEN` in einer Transaktion; leere Zellen überschreiben nichts; Dubletten-Entscheide; «nicht mehr im CRM-Export» nur bei Vollexport und nur für Kontakte mit `crm_id`, nie löschen; Originaldatei nach `importe/`
  - Verkaufschancen-Import (Importart `verkaufschance`) über die Kontakt-ID aus 21.CRM zuordnen (Profil «21.CRM»: `ACCOUNTGUID` → `kontakt.crm_id`, `GGUID` → `verkaufschance.crm_id`, design.md), bestehende über `crm_id` aktualisieren
  - _Anforderungen: 13.6, 13.7, 13.8, 13.9_

- [ ] 4.4 Import-Oberfläche [Frontend]
  - Upload mit Markierung «Vollexport», Zuordnungsdialog (Spalte → Feld mit Vorschlag; erkanntes Profil «21.CRM» vorausgewählt), Vorschau mit Zählern und Filter je Ergebnis, Dubletten entscheiden, Übernehmen, Liste der Läufe; nur mit Recht A12
  - _Anforderungen: 13.2, 13.3, 13.5, 13.6, 13.7_

- [ ] 4.5 Volltextsuche [Backend]
  - `GET /api/v1/dokumente` mit `websearch_to_tsquery` (inkl. Tags), Filtern (Art, Partner, Zeitraum, Betrag, Richtung, Tags, Status, Geschäftsjahr, Belegnummer exakt), Sortierung, `ts_headline`; Vertraulichkeit nach 2.3 und Mandant
  - Lasttest mit 50'000 synthetischen Dokumenten: p95 ≤ 1 s; Lastskript hier bereitstellen, Nachweis erst auf Stufe B in 5.11 (Messbedingungen aus 4.9, 16.16), in Stufe A nur Funktion
  - `GET /api/v1/dokumente/{id}/datei` mit vorsignierter URL (5 Min.) für die gespeicherte Versions-ID, mit Protokoll
  - _Anforderungen: 14.1–14.7_

- [ ] 4.6 Suche und Dokumentansicht [Frontend]
  - Suchfeld, Filterleiste (Art, Partner, Zeitraum, Betrag, Richtung, Status, Tags, Geschäftsjahr, Belegnummer), Trefferliste mit Auszug
  - Dokumentansicht: PDF-Vorschau, Metadaten bearbeiten (mit Grund, nur mit Recht A6), Versionen, Download
  - _Anforderungen: 10.1, 14.1, 14.2, 14.4, 14.7_

- [ ] 4.7 Generator-Endpunkt und Python-Client [Backend]
  - `POST /api/v1/dokumente/generator` (Scope `generator`): Pflichtfelder der Art (422 `PFLICHTFELD_FEHLT`), unbekannte `crmId` (422); `vertraulichkeit` und `personBenutzer` Pflicht bei Standard `PERSONAL`, sonst Standard der Art
  - Verarbeitung ohne KI (Prüfsumme, Dublettenprüfung, PDF/A, Volltext, Vorschau), `aufbewahren_bis`, Archivierung über `ARCHIVIERUNG` nach `ARCHIVIERT`; Antwort 201 oder 202 gemäss design.md
  - gleiche SHA-256 wie ein vorhandenes Dokument → 200 mit vorhandener ID; Belegnummer einer eigenen Art mit abweichender SHA-256 → 409
  - Verwerfen eines Generator-Dokuments (aus `FEHLER` oder `EINGANGSKORB`) gibt die Reservation in `belegnummer_eigen` in derselben Transaktion frei; Integrationstest: verwerfen, korrigiert neu senden → 202
  - `tools/archiv_client.py` (requests, Token aus Datei mit Rechten 600: `~/.config/urinet-archiv/token` oder Pfad aus `ARCHIV_TOKEN_DATEI`, nie im Code oder als Umgebungsvariable); bei Ausfall Ordner `archiv-ausstehend/`, bei 409 nach `archiv-konflikt/` ohne erneutes Senden; Einbau-Beispiel für `rechnung_gen.py` und `urinet_pdf.py` in `tools/README.md`
  - _Anforderungen: 11.1–11.11_

- [ ] 4.8 Drive-Import-Werkzeug [Backend]
  - `tools/drive_import` (Java, Picocli), Anmeldung mit API-Token vom Scope `import` (Token aus Datei wie in 4.7, nie als Umgebungsvariable): rekursiv, Trockenlauf, Ordnerpfad als `hinweis`; Pfad-Muster → Hinweise an die KI (Kunde, Art)
  - `DUBLETTE` gilt als übertragen («schon vorhanden»); `.gdoc/.gsheet` in der Liste «bitte als PDF exportieren»; Office-, `.eml`- und ZIP-Dateien sowie vom Archiv abgelehnte Dateien unter «nicht übernommen» mit Pfad und Grund
  - Schlussbericht: gefunden = übertragen + schon vorhanden + übersprungen + abgelehnt
  - importierte Dokumente landen im Eingangskorb, nie direkt im Archiv
  - _Anforderungen: 12.1–12.8_

- [ ] 4.9 Entscheide vor Welle 5 [Fabian]
  - Zeitstempeldienst (RFC 3161) in der Schweiz wählen und Zugang bereitstellen [offen: Schweizer TSA-Anbieter – Entscheid vor Welle 5]
  - zweiten Schweizer Anbieter für die Zweitkopie wählen [offen: zweiter Schweizer Backup-Anbieter – Entscheid vor Welle 5]
  - Rhythmus der Restore-Probe [offen: Rhythmus der Restore-Probe – Entscheid vor Welle 5] und Aufbewahrungsfrist des Zugriffsprotokolls [offen: Aufbewahrungsfrist des Zugriffsprotokolls – Entscheid vor Welle 5] festlegen
  - Schlüsselweg der Restore-Probe festlegen [offen: Schlüsselweg der Restore-Probe – Entscheid vor Welle 5]
  - festlegen, ob die Restore-Probe im Probe-Bucket Sperren setzt [offen: ob die Restore-Probe im Probe-Bucket Sperren setzt – Entscheid vor Welle 5] (Manifeste in COMPLIANCE wären dort bis zum Ablauf nicht löschbar, 5.5)
  - Grösse und Kosten der eigenen Infomaniak-VPS für Stufe B (API, Worker mit OCR, PostgreSQL mit pgvector, Keycloak) festlegen [offen: VPS-Grösse K5 – Entscheid vor Welle 5]; Messbedingungen (Eingabe, Last, Hardware) für 3.2, 5.5 und 14.3 daraus ableiten; bricht 1.10 ab oder tritt 16.18 später ein, wird dieser Punkt vorgezogen
  - Ergebnisse per PR in requirements.md, design.md, tech.md und `docs/umsetzungsplan.md` nachtragen
  - _Anforderungen: 3.2, 5.5, 10.9, 14.3, 15.3, 16.2, 16.3, 16.17_

## Welle 5 — GeBüV, Betrieb, Abnahme Phase 1 (Voraussetzung: Entscheide aus 4.9)

- [ ] 5.1 Zugriffsprotokoll vollständig [Backend] [Frontend]
  - `@Protokolliert` an allen Endpunkten der Liste in design.md, inkl. verweigerter Zugriffe (`VERWEIGERT`); REST `/api/v1/protokoll` (Recht A13) mit Filtern; Seite `/verwaltung/protokoll`
  - Aufbewahrungsfrist aus 4.9 umsetzen
  - Tests: UPDATE/DELETE auf `zugriff_protokoll` scheitert; ohne Protokolleintrag keine Aktion
  - _Anforderungen: 2.19, 10.2, 10.3, 10.5, 10.7, 10.8, 10.9_
  - _Abhängig von: 4.9_

- [ ] 5.2 Tages-Manifest [Backend]
  - Auftrag `MANIFEST` täglich 03:30 Europe/Zurich: alle Dateien seit dem letzten Manifest nach Zeitpunkt der gesetzten Sperre (ohne gesetzliche Pflicht: Wechsel auf `ARCHIVIERT`), je Datei Dokument-ID, Variante, SHA-256 und als Zusatz die Versions-ID; Hash der Zeilen von `zugriff_protokoll` und `dokument_version` des Vortags
  - Hash-Kette (erstes Manifest `vorher_sha256 = null`), Ed25519-Signatur (Schlüssel als Secret-Datei, öffentlicher Schlüssel unter `docs/`), RFC-3161-Zeitstempel als Pflicht beim Anbieter aus 4.9
  - Ablage unter `manifest/` mit Object Lock im Modus COMPLIANCE (6.11, Option B) bis zum spätesten `aufbewahren_bis` der enthaltenen Dateien, Tabelle `manifest`
  - verpasste Läufe in Datumsreihenfolge nachholen, höchstens ein Manifest pro Datum; Tests für 29.03.2026 und 25.10.2026; Alarm bei fehlendem Manifest
  - Prüfwerkzeug `archiv manifest pruefen <datum>` (Signatur, Kette, Zeitstempel)
  - _Anforderungen: 6.11, 15.1–15.4, 15.6, 15.7, 15.8, 16.9_
  - _Abhängig von: 4.9_

- [ ] 5.3 Monatlicher Prüflauf [Backend]
  - Auftrag `PRUEFLAUF`: alle Dateien über die gespeicherte Versions-ID stromweise hashen, mit Datenbank und Manifest vergleichen (massgeblich SHA-256); fremde Versionen und Löschmarken unter `archiv/` als Abweichung
  - Sperren unbefristeter Dokumente und von Manifesten, die in weniger als 12 Monaten ablaufen, um 10 Jahre verlängern
  - Bericht und E-Mail an den Inhaber bei Abweichung (nur IDs)
  - _Anforderungen: 6.9, 15.5, 15.9, 15.10_

- [ ] 5.4 Produktivbetrieb aufsetzen [Infra] [Fabian]
  - Produktion auf Stufe B (VPS aus 5.11) mit `deploy/infomaniak/compose.yml` und dem Deploy-Workflow aus 1.9 unter `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch`; Keycloak-Realm produktiv mit TOTP
  - Bucket `urinet-archiv-urinet` im Public-Cloud-Projekt produktiv, mit der Bucket Policy aus 1.3 (Deny auf `s3:BypassGovernanceRetention`)
  - [Fabian] App-Schlüssel (`s3_app_zugang`; hat technisch das Bypass-Recht, Option B, ADR-007, Nachtrag 09.11.2026) als GitHub-Secret hinterlegen (auf dem Server nur unter `/run/secrets/`); Inhaber-Zugang zum Public-Cloud-Projekt des Object Storage (Zugangsdaten, mit denen Schlüssel ausgestellt und Korrekturen ausgeführt werden) nur offline ablegen (Notfallunterlagen, 5.10), nie auf dem Server oder in GitHub-Secrets
  - produktives Archivieren erst, wenn der Zeitstempeldienst angebunden ist (15.3)
  - _Anforderungen: 6.5, 6.10, 15.3, 16.1, 16.7, 16.9, 16.10, 16.17_
  - _Abhängig von: 1.9, 4.9, 5.11_

- [ ] 5.5 Sicherung und Wiederherstellung [Infra] [Fabian]
  - im Repository (`deploy/backup/`): täglicher Export von Archiv-Datenbank, Keycloak-Datenbank und Realm, Bucket (Original, Arbeitsfassung, PDF/A, Manifeste) und Manifest-Schlüssel; der Server verschlüsselt asymmetrisch und hält nur den öffentlichen Schlüssel
  - [Fabian] [Infra] im Infra-Repo der VPS: Sicherungsworkflow nach dem Muster `maintenance` holt die verschlüsselten Daten ab und schreibt sie mit restic zum Anbieter aus 4.9 (restic-Schlüssel nur als GitHub-Secret dieses Workflows und in den Notfallunterlagen); Aufbewahrung pro App 30 Tage, 12 Monate, 10 Jahresstände; `backup-gate.sh` und Restore-Probe um PostgreSQL und den Bucket erweitern
  - automatisierte Restore-Probe im Rhythmus aus 4.9: Datenbanken und Dateien wiederherstellen, Anmeldung prüfen, Prüflauf (5.3) grün; [offen: ob die Restore-Probe im Probe-Bucket Sperren setzt – Entscheid vor Welle 5]; läuft die Probe mit den Stufenwerten der Stufe B (Sperrmodus von `manifest/` fest COMPLIANCE, 2.3), wären Manifeste dort (6.11, Option B) bis zum Ablauf ihrer Sperre nicht löschbar
  - Restore-Probe nach dem Schlüsselweg aus 4.9
  - `docs/wiederherstellung.md`, einmal vollständig in einer Testumgebung durchgespielt (Nachweis: manuell [Fabian])
  - solange Stufe A läuft: nur Funktionstest mit Testdaten (Restore-Probe), keine Aufbewahrungspflicht; Aufbewahrung nach 16.2 erst auf Stufe B (5.11)
  - _Anforderungen: 16.2, 16.3, 16.7, 16.8, 16.19_
  - _Abhängig von: 4.9, 5.3_

- [ ] 5.6 Überwachung und Benachrichtigung [Backend] [Infra]
  - Health: öffentlich nur ein schlanker Pfad über Traefik; Detail-Health und Micrometer-Kennzahlen (offene Aufträge, Fehler, Eingangskorb, Korrekturquote je Feld) nur intern
  - E-Mail an den Inhaber bei einem Dokument > 1 h in `VERARBEITUNG`, `FEHLER` oder `ARCHIVIERUNG_FEHLER`, bei fehlgeschlagener Sicherung und fehlendem Manifest; Inhalte nur IDs
  - _Anforderungen: 16.4, 16.5, 16.7_

- [ ] 5.7 Verfahrensbeschreibung «Erfassen und Archivieren» [Fabian]
  - `docs/verfahrensbeschreibung.md` (≤ 2 Seiten): Eingänge, Scan-Regeln (ganze Seite, lesbar, ≥ 300 dpi), Prüfung im Eingangskorb, Sperre, Manifest, Zugriffe, Umgang mit Papier (Konzept-Entscheid E5), Notfallablauf für den Treuhänder und Ort der Notfallunterlagen (ohne Zugangsdaten); vom Treuhänder gegenlesen lassen (K3)
  - jede freigegebene Fassung mit «gültig ab» als PDF/A im Archiv ablegen
  - _Anforderungen: 16.6, 16.12_

- [ ] 5.8 Übernahme Belege 2026 und Abnahme [Fabian] [QA]
  - Voraussetzung: Wechsel auf Stufe B abgeschlossen (5.11, 16.17), Zeitstempeldienst angebunden (15.3), Sicherung und Restore-Probe grün; Realitätsprüfung nach Auflage A2 aus Gate 0 erneut gelaufen und Ziel aus 2.9 erreicht (`docs/umsetzungsplan.md`, Abschnitt 6)
  - CRM-Kontakte per CSV importieren, Drive-Altbestand importieren und im Eingangskorb bestätigen, Papierbelege 2026 fotografieren, Generatoren umstellen
  - Abnahmetest: Stichprobe 20 Dokumente auffindbar, Schlussbericht des Drive-Imports geht auf, Prüflauf ohne Abweichung, Mandantentrennung mit Testmandant `test` geprüft
  - **Gate Phase 1:** alle Belege 2026 im Archiv, Prüflauf grün
  - _Anforderungen: 1.4, 12.7, 13.6, 15.3, 15.5, 16.3, 16.17_
  - _Abhängig von: 2.9, 5.1–5.7, 5.9, 5.10, 5.11_

- [ ] 5.9 Anbindung an das Monitoring der VPS [Infra]
  - interne Kennzahlen und Detail-Health an das Monitoring der VPS (Stufe B aus 5.11) anbinden; Alarme für Erreichbarkeit, Health und Fehleraufträge; Eintrag im Infra-Repo der VPS
  - _Anforderungen: 16.4, 16.5_
  - _Abhängig von: 5.11_

- [ ] 5.10 Notfallunterlagen [Fabian]
  - versiegelt zusammenstellen: Keycloak-Admin-Zugang, TOTP-Backup-Codes, restic-Schlüssel, privater Sicherungsschlüssel (Export-Verschlüsselung), S3-Zugang des Inhabers (Inhaber-Zugang, 6.10), öffentlicher Manifest-Schlüssel; Ablage ausserhalb des Systems am Ort aus der Verfahrensbeschreibung, nie im Repository
  - Vollständigkeit einmal mit dem Notfallablauf aus 5.7 prüfen
  - _Anforderungen: 6.10, 16.12_

- [ ] 5.11 Wechsel auf Stufe B [Infra] [Fabian]
  - Zeitpunkt: zu Beginn von Welle 5, vor 5.4 und vor der Übernahme 5.8; vorgezogen (Ausnahme von «Aufgaben anderer Wellen nicht vorziehen»), wenn 1.10 abbricht oder 16.18 später eintritt
  - [Fabian] eigene Infomaniak-VPS in der Grösse aus 4.9 (K5) bestellen; Zugang des Deploy-Workflows als GitHub-Secret; DNS für `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch` auf die neue VPS umstellen
  - [Infra] neue VPS nach dem Muster des Betriebs einrichten (Traefik mit Let's Encrypt, Deploy-Befehl für das SSH-Bundle, Compose-Policy, Monitoring); Einträge im Infra-Repo der VPS
  - [Infra] Deploy-Ziel im Workflow aus 1.9 umstellen; Stufenwerte für B: `archiv-api` (`archiv.rolle=api`) und `archiv-worker` (`archiv.rolle=worker`) getrennt, beide mit `archiv.stufe=B`, Parallelität und Limits nach K5; Sperrmodus von `manifest/` COMPLIANCE ohne Abweichung (2.3)
  - Testdaten verwerfen: Datenbanken und Realm-Benutzer der Stufe A nicht übernehmen, Stufe B startet leer; Test-Buckets nach dem Entscheid aus design.md (Open Points: Bucket-Namen der Stufe A); danach Archiv-Dienste auf Stufe A entfernen und die Einträge im Infra-Repo der bestehenden VPS zurückbauen; im Wechselprotokoll [Fabian] bestätigen, welche Testdaten auf Stufe A geladen wurden (16.15)
  - Sicherung und Restore-Probe aus 5.5 auf Stufe B einrichten bzw. umstellen, Aufbewahrung nach 16.2 ab hier; ist 5.5 beim Wechsel noch offen, richtet 5.5 sie direkt auf Stufe B ein
  - Leistungsmessung auf Stufe B mit den Lastskripten aus 2.4, 2.6 und 4.5 unter den Messbedingungen aus 4.9; Messbericht im PR verlinken; bei vorgezogenem Wechsel die Leistungsmessung nachholen, sobald 2.4, 2.6 und 4.5 fertig sind, spätestens vor 5.4
  - _Anforderungen: 3.2, 5.5, 14.3, 16.1, 16.2, 16.10, 16.15, 16.16, 16.17, 16.18, 16.19_
  - _Abhängig von: 1.9, 2.4, 2.6, 4.5, 4.9 (bei vorgezogenem Wechsel nur der Entscheid K5 aus 4.9)_
