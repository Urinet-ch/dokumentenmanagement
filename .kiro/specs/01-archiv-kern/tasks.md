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
    - Stand 10.11.2026 (Aufgabe 1.9): Infra-Repo PR #4 «eine Datei je Secret» (Ordner `secrets/` im Bundle) auf `main`; PR #5 «mehrere Images je App» (`archiv:<sha>`, `archiv:db-…`, `archiv:kc-…`) am 10.11.2026 auf `main` (806e1fa); Bundle und `compose.yml` des Archivs bestehen `compose-policy.py` (`image`, `ids`, `compose` mit `image.tar`) mit den Tags des Workflows. Bisher hier: «umgetaggte Images» ohne Schema; neu: nach PR #5. Ausgangswerte vor dem ersten Deploy noch festhalten
  - Datenregel Stufe A festhalten: nur Test- und anonymisierte Daten, keine echten Belege, keine Personal- oder Lohndaten
    - Ergebnis: von Fabian bestätigt (Auftrag 1.9, 10.11.2026)
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

- [x] 1.3 Mandantenregister und Provisionierung [Backend]
  - Liquibase `db/public`: `mandant` (inkl. Geschäftsjahresende), `auftrag`, `api_client`
  - `MandantProvisionierung` (Schema, Liquibase `db/mandant`, Bucket `<archiv.ablage.bucket-praefix><code>` mit Object Lock + Versionierung und Bucket Policy mit Deny auf `s3:BypassGovernanceRetention` als Schutz gegen Versehen (design.md «Ablage»; entschieden von Fabian am 09.11.2026), Seed der Dokumentarten aus `seed/<code>/dokumentarten.json`); Admin-Kommando `archiv mandant anlegen <code>`
  - beim Start Migration aller aktiven Mandanten über Liquibase, Hibernate nur `validate`
  - Nachtrag (PR #113, 13.11.2026): geplante Jobs mit Mandanten-Daten und der Nachhollauf der Manifeste erst nach der Migration bzw. Prüfung (`SchemaBereitschaft`), design.md «Start und Migration»
  - Test: zwei Mandanten anlegen, Schemas und Buckets getrennt; jeder Bucket trägt die Bucket Policy mit Deny auf `s3:BypassGovernanceRetention`; Bucket-Namen mit den Präfixen `urinet-archiv-` und `urinet-archiv-a-`
  - _Anforderungen: 1.1, 1.5, 1.7, 9.6_

- [x] 1.4 Mandanten-Kontext und Rechtematrix [Backend]
  - `MandantTenantResolver`, `MandantKontext` für Worker, `@RolesAllowed` je Endpunkt
  - Rechtematrix A1–A14 als eine Domain-Regel (Rolle × Aktion), die alle Endpunkte abfragen; 404 vor 403 bei unsichtbaren Dokumenten; spätere Aufgaben ergänzen je Endpunkt die Szenarien ihrer Matrixzeilen
  - Szenarien: ohne Claim → 403; inaktiver Mandant → 403; Mandantentrennung per Probe (Teilnachweis, voll in 2.2); `treuhaender` schreibt → 403; Matrix-Tabelle gegen die Domain-Regel
  - _Anforderungen: 1.2, 1.3, 1.4, 1.6, 2.2, 2.4, 2.6_

- [x] 1.5 API-Tokens für Generatoren und Import [Backend]
  - `ApiTokenAuthMechanism`, Argon2id, an einen Mandanten gebunden, Scopes `generator` und `import`; ein Token erreicht nur die Endpunkte seines Scopes, sonst 403
  - Kommando `archiv token erstellen <mandant> <name> <scope>` (gibt Geheimnis einmal aus); Anlegen, Anzeigen und Widerruf nur mit Recht A14
  - _Anforderungen: 2.5, 2.20_

- [x] 1.6 Querschnitt: Problem Details, Protokoll-Grundlage, ArchUnit [Backend]
  - `ExceptionMapper` → RFC 9457 mit `fehlercode`
  - Tabelle `zugriff_protokoll` mit Trigger (nur `INSERT`); `@Protokolliert`-Interceptor schreibt synchron in derselben Transaktion, IP aus `X-Real-Ip`; schlägt der Eintrag fehl, wird die Aktion nicht ausgeführt; verweigerte Zugriffe mit Aktion `VERWEIGERT`
  - ArchUnit-Regeln aus `structure.md` (Blueprint)
  - _Anforderungen: 10.2, 10.3, 10.7, 10.8, 16.13_

- [x] 1.7 Stammdaten der Dokumentarten festlegen [Fabian]
  - Entwurf der Stammdaten-Tabelle in design.md prüfen und je Art Code, Gruppen, Pflichtfelder, Standard-Vertraulichkeit, Aufbewahrung (Jahre, ab wann) und `gesetzliche_pflicht` festlegen – entschieden von Fabian am 10.11.2026
  - Freigabe per Merge; ersetzt den [offen]-Vermerk in Requirement 9.2
  - _Anforderungen: 9.1, 9.2_

- [x] 1.8 Leitplanken im Repository [Infra] [Fabian]
  - Git-Hook `pre-commit` mit Secret-Scan (gitleaks), `pre-push` sperrt `main`; Secret-Scan zusätzlich als CI-Workflow, grün vor dem Merge als Regel des Vorgehens (nicht erzwungen, Entscheid Fabian 09.11.2026, siehe tech.md)
  - Claude-Code-Hooks der Harness-Vorlage in `.claude/settings.json`: Änderungen an bestehenden Changesets unter `db/**` beim Bearbeiten mit Edit/Write nachfragen (harness.yml; Änderungen per Bash prüft der Hook nicht) und in der CI blockieren (pruefe-liquibase.sh), keine Secret-Dateien lesen oder schreiben; die Tests laufen in `mvn verify` und in der CI (Entscheid Fabian 09.11.2026: kein Hook, der nach dem Bearbeiten einer Java-Datei Tests ausführt)
  - [Fabian] Leitplanken-Vorlage übernehmen; `main` per pre-push-Hook gesperrt (Branch-Schutz im Free-Plan nicht verfügbar, Entscheid Fabian 09.11.2026)
  - _Anforderungen: 16.9, 16.14_

- [ ] 1.9 Deploy-Workflow und Compose für die VPS [Infra]
  - `deploy/infomaniak/compose.yml`: API (mit PWA), Worker, PostgreSQL, Keycloak; Traefik-Labels für `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch`; Compose-Policy (Limits, `cap_drop: ALL`, `no-new-privileges`, uid ungleich 0); Secrets nur als Dateien unter `/run/secrets/`, nie `environment` oder `env_file`
  - Images von PostgreSQL und Keycloak als `<app>:…` umtaggen, Eintrag im Infra-Repo der VPS
  - `archiv_db_owner` nur dem Dienst `archiv-api` mounten; das Admin-Kommando läuft per `exec` im API-Container (geändert 12.11.2026: in einem Wegwerf-Container des Dienstes `api` mit dessen Secrets, nie per `exec`, weil eine zweite JVM das `mem_limit` sprengt; README «Admin-Kommandos auf der VPS»)
  - GitHub-Actions-Workflow: Image bauen, Bundle per SSH an den Deploy-Befehl der VPS, Health-Prüfung, bei Fehler Rollback auf das Vorgänger-Image ohne Rückbau der Datenbank
  - erster Deploy des Gerüsts auf Stufe A (VPS aus 0.7) in der Form aus tech.md (API und Worker in einem Container, Limits im Speicherbudget), nur mit Testdaten (15.3, 16.15)
  - nach jedem Deploy auf Stufe A Speicherabbrüche der VPS und Health der anderen Apps prüfen; tritt 16.18 ein, gilt das Abbruchkriterium aus 1.10
  - Stand 10.11.2026 (PR #42, Copilot): `compose.yml`, abgeleitete Images, `infomaniak-deploy.yml` (nur `workflow_dispatch` und Tags `v*`), Szenarien `@req-16.7` (Allowlist) und `@req-16.11`; lokal gemessen braucht das Gerüst 820 MiB (api 320, db 100, kc 400); Fabian hat das Budget am 10.11.2026 von 700 auf 900 MiB angehoben (16.18, design.md «Stufen der Plattform»). Nachbesserung 10.11.2026: Prüfung «Commit liegt auf main», Neustart-Erkennung über die Laufzeit der Container, Rollback-Exit-Code, strengere Secret-Prüfung, Keycloak in der Readiness der API (nach Review-Befund M1 wieder entfernt: Readiness nur Datenbank), `postgres` nur über den Socket. Entscheide 11.11.2026 (Fabian, PR #42): Keycloak wieder in der Readiness, dazu Selbstheilung (OIDC-Wächter: nach 5 Minuten nicht bereit Exit-Code 75 und Neustart, P42-1); kein Environment mit Required Reviewer (P42-2); umask-Prüfung auf alle Schritte, die Dateien schreiben. Haken erst nach dem ersten Deploy durch Fabian
  - _Anforderungen: 16.1, 16.9, 16.10, 16.11, 16.18_
  - _Abhängig von: 0.7, 1.1_

- [ ] 1.10 Stufe A einrichten und Speicherbudget messen [Infra]
  - Stufenwerte versioniert unter `deploy/infomaniak/`: `archiv-api` mit Image `archiv-worker` und `archiv.rolle=api,worker`, `archiv.stufe=A` (2.3), `archiv.worker.parallel=1`, OCR seitenweise (`--jobs 1`); knappe Heap- bzw. Speicher-Einstellungen für JVM, PostgreSQL und Keycloak; Summe der `mem_limit` ≤ 900 MiB (geändert 10.11.2026, Entscheid Fabian; bisher 700 MiB), `cpus` zusammen ≤ 0.75
  - Netz prüfen: Datenbank (`archiv-db`), Worker und Sicherung nur im eigenen internen Docker-Netz des Archivs; am Netz «web» von Traefik hängen nur `archiv-api` (API/PWA) und `archiv-keycloak` (öffentlich nur `/realms/` und `/resources/`, Admin-Konsole nicht öffentlich)
  - Buckets der Stufe A mit Präfix `urinet-archiv-a-` und `archiv.ablage.max-sperrfrist=P1D` (entschieden von Fabian am 09.11.2026, design.md «Protokoll und Manifest»)
  - Mandant `urinet` anlegen (`mandant anlegen urinet`) in einem Wegwerf-Container mit den Einstellungen der API, nie per `docker exec` im laufenden Container (eine zweite JVM sprengt dessen `mem_limit`, Erkenntnis 12.11.2026); Kommando und Benutzer in Keycloak nach README, Abschnitte «Mandant anlegen» und «Benutzer in Keycloak anlegen»
  - Messung nur mit synthetischen Testbelegen: Spitzenverbrauch je Dienst im Leerlauf, beim Upload und bei OCR mehrseitiger Belege; verfügbarer Speicher der VPS gegenüber den Ausgangswerten aus 0.7; Health der anderen Apps während der Messung; Ergebnis in `docs/spikes/stufe-a-speicher.md` (nur Zahlen, keine Namen anderer Apps); darin [Fabian] bestätigen, welche Testdaten auf Stufe A geladen wurden (16.15)
  - **Abbruchkriterium:** Das Archiv braucht mehr als das Budget (Summe `mem_limit` > 1000 MiB (geändert 13.11.2026, Entscheid Fabian; bisher 900 MiB) oder `cpus` > 0.75), ein Container auf der VPS wird wegen Speichermangel beendet, oder die Health einer anderen App fällt während der Messung aus → Archiv-Dienste auf Stufe A stoppen, Fabian informieren, K5 (4.9) und Wechsel (5.11) vorziehen (16.18). Ein Speicherabbruch durch einen manuellen Eingriff (z. B. zweite JVM per `docker exec` wie am 12.11.2026) zählt nicht; er wird mit Datum im Messbericht festgehalten (vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
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

- [x] 2.1 Dokumentarten und Aufbewahrung [Backend] [Fabian]
  - Entität `Dokumentart` mit Standard-Vertraulichkeit und `gesetzliche_pflicht`; REST `/api/v1/dokumentarten` (CRUD nur mit Recht A11; Löschen nur ohne Dokumente, sonst 409 `DOKUMENTART_IN_GEBRAUCH` → inaktiv); JSON-Schema-Validierung für `zusatz` (422 `ZUSATZ_UNGUELTIG`)
  - Seed `backend/src/main/resources/seed/urinet/dokumentarten.json` mit den 11 Arten aus 1.7; ein Test prüft den Seed Feld für Feld gegen die Stammdaten-Tabelle in design.md
  - Spalte «Beschreibung» in Seed und Stammdaten-Tabelle (design.md) mit den Abgrenzungen zwischen den Arten (z. B. Sozialversicherungen AHV, IV, EO, FAK = `BEHOERDE`; Policen der UVG- und Krankentaggeld-Versicherer = `VERSICHERUNG`, ihre Prämienrechnungen = `LIEFERANTENBELEG`); sie fliesst in den KI-Prompt (entschieden von Fabian am 09.11.2026; Texte abgenommen von Fabian am 10.11.2026; Prämienrechnungen von UVG-, Krankentaggeld- und BVG-Versicherern = `LIEFERANTENBELEG` entschieden von Fabian am 11.11.2026)
  - Entscheide F1–F7 (entschieden von Fabian am 10.11.2026): Neuberechnung ohne Sperre übernimmt auch ein früheres Ergebnis, mit Sperre bleibt der Konflikt (9.7); Regel einer Art mit Dokumenten änderbar, Änderung mit alter und neuer Regel im Zugriffsprotokoll (`REGEL_AENDERN`, 9.10; Wirkung auf bestehende Dokumente mit 2.2/2.3); `AUSGANGSRECHNUNG` ohne Pflichtfeld `faelligkeit`; `BEHOERDE` mit Gruppe `finanz`; `LOHNABRECHNUNG` mit Lohnausweis (`zusatz.periode` `JJJJ-MM` oder `JJJJ`)
  - Entscheide P40-1 und P40-2 (entschieden von Fabian am 11.11.2026): Eine gesetzlich pflichtige Art darf gesenkt werden (`gesetzliche_pflicht` auf «nein», weniger als 10 Jahre), die Änderung wird nur protokolliert (9.10); Prämienrechnungen von UVG-, Krankentaggeld- und BVG-Versicherern = `LIEFERANTENBELEG` (Beschreibungen `LOHNABRECHNUNG` und `BEHOERDE`)
  - `AufbewahrungRechner`: Ende des bestätigten Geschäftsjahres (Geschäftsjahresende des Mandanten) plus Jahre der Regel; ohne Belegdatum Eingangsdatum; Verträge ab `gueltig_bis`, ohne `gueltig_bis` unbefristet; Neuberechnung verlängert nur; Frist in der Vergangenheit → «Frist abgelaufen»; keine Löschung bei Fristablauf
  - Unit-Tests: Beleg 2026 → 31.12.2036; abweichendes Geschäftsjahresende; ohne Belegdatum; Vertrag ohne `gueltig_bis`; Neuberechnung mit früherem Datum → mit Sperre bleibt die Frist, Konflikt protokolliert, ohne Sperre gilt das neue Ergebnis (entschieden von Fabian am 10.11.2026)
  - [Fabian] Abnahme: Seed gegen die freigegebene Tabelle prüfen
  - _Anforderungen: 2.17, 9.1–9.10_
  - _Abhängig von: 1.7_

- [x] 2.2 Datenmodell Dokument und Sichtbarkeit [Backend]
  - Liquibase `db/mandant`: `dokument` (Status `NEU`, `VERARBEITUNG`, `EINGANGSKORB`, `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER`, `ARCHIVIERT`, `FEHLER`, `VERWORFEN`; Vertraulichkeit `NORMAL`, `INTERN`, `PERSONAL`; Erfasser, `person_benutzer`), `dokument_datei`, `dokument_finanz`, `dokument_vertrag`, `dokument_version` (Trigger: nur `INSERT`), generierte `volltext_tsv` inkl. Tags
  - Indizes gemäss design.md: Dubletten je Datei nur über nicht verworfene Dokumente, Belegnummer eindeutig je Art nur bei eigenen Arten, `gin(tags)`, `geschaeftsjahr`, `dokument_finanz(betrag)`, `richtung`, Belegnummer
  - Panache-Entitäten im Adapter; `VertraulichkeitFilter` nach A2–A4: unbestätigte Art gilt als `PERSONAL` (sichtbar für Rollen mit A7 und den Erfasser, nicht über `person_benutzer`; entschieden von Fabian am 11.11.2026); unsichtbare Dokumente fehlen in Listen, Treffern und Zählern, Direktzugriff 404
  - Szenarien je Rolle und Vertraulichkeit mit zwei Mandanten; Szenario «fremde Dokument-ID → 404» (Req 1.4, aus 1.4 übernommen)
  - Eingangskorb-Abfrage nach A5: `mitarbeiter` erhält nur selbst erfasste Dokumente
  - _Anforderungen: 1.4, 2.3, 2.8, 2.9, 2.10, 2.21, 2.24, 10.1, 10.3_

- [x] 2.3 Ablage-Service S3 [Backend]
  - Port `AblagePort` mit S3-Adapter und Dev-Stub: `ablegenEingang`, `archivieren`, `sperreVerlaengern`, `loeschenEingang`, `vorsignierteUrl`
  - `archivieren`: Original, Arbeitsfassung (falls vorhanden) und PDF/A nach `archiv/` kopieren, Prüfsummen vergleichen, Versions-ID je Datei in `dokument_datei` speichern; Sperre GOVERNANCE bis `aufbewahren_bis` nur bei `gesetzliche_pflicht`, unbefristete Dokumente dieser Arten 10 Jahre (Reihenfolge der Prüfung gemäss design.md, `retainUntil`); danach `eingang/` löschen
  - nur der App-Schlüssel aus `s3_app_zugang_id` und `s3_app_zugang_geheimnis`; der S3-Adapter setzt nie `x-amz-bypass-governance-retention` (SDK: `bypassGovernanceRetention`) und verkürzt keine Sperre (6.5; Option B, ADR-007, Nachtrag 09.11.2026); `vorsignierteUrl` nur für die gespeicherte Versions-ID; Manifeste unter `manifest/` im Modus COMPLIANCE (6.11)
  - Sperrmodus von `manifest/` per Konfiguration (entschieden von Fabian am 09.11.2026): Standard COMPLIANCE; ab Stufe B (16.17; dort kommen echte Belege ins Archiv) fest COMPLIANCE mit der Frist nach 15.4, API und Worker brechen den Start ab, falls dort ein anderer Modus oder eine kürzere Frist konfiguriert ist; abweichend (GOVERNANCE oder kürzere Frist) nur in Tests, in der lokalen Entwicklung und in den Stufenwerten der Stufe A unter `deploy/infomaniak/` (nur Testdaten, 16.15), damit Test-Buckets löschbar bleiben; die Regel hängt nicht am Profil `prod`, weil Stufe A mit denselben Images und demselben Deploy-Workflow läuft wie Stufe B (design.md «Protokoll und Manifest»); die Stufe erkennt die Startprüfung am Konfigurationswert `archiv.stufe` (`A` | `B`) in den Stufenwerten unter `deploy/infomaniak/`, fehlt er, gilt `B` (sicherer Standard: COMPLIANCE wird erzwungen), Stufe A setzt ausdrücklich `archiv.stufe=A`, ebenso die Konfiguration der Tests und der lokalen Entwicklung; Test: abweichender Sperrmodus oder kürzere Frist mit den Stufenwerten der Stufe B → Start von `archiv-api` und `archiv-worker` bricht ab; ohne `archiv.stufe` → wie mit `B`, Start bricht ab
  - Höchstfrist der Dokumente unter `archiv/` auf Stufe A (`archiv.ablage.max-sperrfrist=P1D`, GOVERNANCE) wie bei `manifest/`; Startprüfung ab Stufe B: Start bricht ab, wenn `archiv.ablage.max-sperrfrist` gesetzt ist oder `archiv.ablage.bucket-praefix` `urinet-archiv-a-` lautet; Konfigurationstest (JUnit) (6.11, entschieden von Fabian am 09.11.2026)
  - MinIO aus den Compose Dev Services mit Object Lock: Löschen und Verkürzen einer gesperrten Version scheitern mit den Aufrufen der Anwendung (ohne Bypass-Header), Prüfsummen-Abweichung bricht ab, Art ohne gesetzliche Pflicht ohne Sperre, Sperre nur verlängerbar, Objekt unter `manifest/` in COMPLIANCE auch mit Bypass-Header weder löschbar noch verkürzbar (6.11; Test-Bucket ohne Bucket Policy, sonst greift deren Deny [T 6c])
  - Nachweis 6.5 (JUnit): Request-Interceptor in den Integrationstests prüft, dass kein S3-Aufruf der Anwendung `x-amz-bypass-governance-retention` sendet; ergänzend ArchUnit-Regel gegen `bypassGovernanceRetention` im Produktionscode (Testklassen ausgenommen)
  - _Anforderungen: 6.2, 6.3, 6.4, 6.5, 6.9, 6.11, 6.12, 14.6_

- [x] 2.4 Upload-Endpunkt [Backend]
  - `POST /api/v1/dokumente` multipart (Recht A1), je Datei: SHA-256 vor allem anderen in `dokument_datei`, Typprüfung per Tika-Detektor (415 `DATEITYP_UNBEKANNT`), über 50 MB (413 `DATEI_ZU_GROSS`), leer oder beschädigt (422 `DATEI_UNGUELTIG`), Dublettenprüfung (`DUBLETTE` mit `vorhandenId`, ohne Leserecht nur «bereits vorhanden»)
  - ein Ergebnis je Datei, gültige Dateien trotz abgelehnter Nachbarn; `zusammenfassen`; Handy-Upload mit Original und Arbeitsfassung je Seite
  - Antwort 202, Status `NEU`, Auftrag `VERARBEITEN` einreihen; Lastskript für ≤ 2 s bereitstellen, Nachweis erst auf Stufe B in 5.11 (Messbedingungen nach Req 3.2, 5.5, 14.3 und 16.26), in Stufe A nur Funktion
  - Hinweis aus 2.5: `AuftragService.einreihen` nimmt nur Typen an, für die ein Handler besteht. `VERARBEITEN` lässt sich darum erst mit dem `VerarbeitenHandler` aus 2.6 einreihen; 2.4 nutzt den echten Handler aus 2.6 (Teil b), Szenario Upload → `VERARBEITUNG` → `KI_VORSCHLAG` in `upload.feature`.
  - generierten OpenAPI-Client an die Upload-Seite der PWA anschliessen (`UploadPort`, aus 2.7) und die Browser-Szenarien `dokument/upload-pwa.feature` gegen das echte Backend laufen lassen (danach `@attrappe` entfernen)
  - _Anforderungen: 2.7, 3.1–3.8, 4.7, 6.1_

- [x] 2.5 Auftragsschlange [Backend]
  - `AuftragService.einreihen(typ, nutzlast)`, `AuftragPoller` mit `SKIP LOCKED`, insgesamt 3 Versuche mit wachsender Wartezeit, Mandantenkontext pro Auftrag
  - Zeitgrenze je Auftragstyp (design.md) und Lebenszeichen; verwaiste `LAEUFT`-Aufträge nach Ablauf der Zeitgrenze freigeben, zählt als Versuch
  - Tests: zwei Worker holen denselben Auftrag nie doppelt; Zeitüberschreitung und verwaister Auftrag zählen als Fehlversuch
  - _Anforderungen: 1.6, 5.4, 5.6, 5.7_

- [ ] 2.6 Worker-Image und Verarbeitung [Backend] [Infra]
  - Dockerfile Worker (JVM + ocrmypdf, tesseract-ocr-deu/eng, img2pdf, libheif-examples, poppler-utils, ghostscript) nach Compose-Policy (uid ungleich 0)
  - Port `OcrPort` mit Adapter und Dev-Stub; `VerarbeitenHandler`: Status `VERARBEITUNG`, HEIC→JPEG, Bilder→PDF, bei Handy-Erfassung PDF/A aus der Arbeitsfassung, OCRmyPDF (`--skip-text` bei Textebene), `pdftotext`, Vorschaubilder; Folgeauftrag `KI_VORSCHLAG`; nach 3 Fehlversuchen `FEHLER` mit Fehlertext
  - signierte PDFs: PDF/A auch bei Verlust der Signatur, Original unverändert; keine PDF/A möglich (z. B. verschlüsselt) → Vermerk `pdfa_fehlt` und Eingangskorb
  - veraPDF-Prüfung im Test; Lastskript für die Laufzeit einseitiges Foto als p95 ≤ 30 s bereitstellen, Nachweis erst auf Stufe B in 5.11 (Messbedingungen nach Req 3.2, 5.5, 14.3 und 16.26)
  - Pflicht für jeden Handler und Hook (aus 2.5, design.md «Pflichten der Handler»): eigene Zeitgrenzen für jede Ein- und Ausgabe unter der Zeitgrenze des Typs (JDBC `queryTimeout`/`statement_timeout`, S3 `apiCallTimeout`, Prozesse `waitFor` + `destroyForcibly()`); Ergebnisse nur in einer kurzen Transaktion schreiben, die zuerst `AuftragService.anspruchSichern(auftrag)` aufruft (`select … from public.auftrag where id = ? and versuche = ? and status = 'LAEUFT' for update`); Test je Handler: verlorener Anspruch schreibt nichts
  - in `OcrAuswahl` `folgtMit` entfernen, damit in `prod` ein fehlender OCR-Adapter den Start abbricht (design.md «Ablage», Startprüfungen); Handler `ARCHIVIEREN`: `archivieren` nur bei Status ungleich `ARCHIVIERT` und nie parallel für dasselbe Dokument (design.md «Ablage», Umsetzung)
  - _Anforderungen: 5.1–5.10_
  - Teil a erledigt (PR #46, ohne Abhängigkeit zu 2.2): Werkzeuge im gemeinsamen Image `Dockerfile.jvm` (uid 10001); `OcrPort` mit `OcrMyPdfAdapter` (HEIC→JPEG, Bilder→PDF, Arbeitsfassungen, OCRmyPDF mit `--skip-text`, signierte PDFs, `pdfa_fehlt`, `pdftotext`, Vorschaubilder; Zeitgrenze mit `destroyForcibly`, ohne Shell, ein Laufverzeichnis je Lauf) und Dev-Stub; `folgtMit` in `OcrAuswahl` entfernt; Startprüfung `OcrPruefung`; Tests mit veraPDF auf synthetischen Dateien; Lastskript `tools/last/verarbeitung_foto.py`. Offen für Teil b (nach 2.2): `VerarbeitenHandler` mit Status `VERARBEITUNG`, Speichern von PDF/A, Volltext und Vorschau, Anspruch sichern, Folgeauftrag `KI_VORSCHLAG`, `FEHLER` nach 3 Fehlversuchen, Handler `ARCHIVIEREN`; Cucumber-Szenarien `@req-5.x`
  - Teil b erledigt (PR #54): `VerarbeitenHandler` mit Status `VERARBEITUNG`, Quellen aus `eingang/` mit SHA-256-Prüfung, PDF/A unter `eingang/<jahr>/<docId>/pdfa.pdf`, Vorschau unter `vorschau/<docId>/`, Volltext, Seiten und `pdfa_fehlt` in `dokument`, Anspruch sichern vor jedem Schreiben, Folgeauftrag `KI_VORSCHLAG` in derselben Transaktion, `FEHLER` mit Fehlertext nach 3 Fehlversuchen; Szenarien `features/verarbeitung/verarbeiten.feature` (`@req-5.1`–`5.4`, `5.8`–`5.10`), JUnit-Tests zu Anspruch, Mandant, Idempotenz und Zeitüberschreitung (design.md «Umsetzung (Aufgabe 2.6, Teil b)»). Offen bleiben: Handler `ARCHIVIEREN` (mit 3.4), Verarbeitung von Generator-Dokumenten und `ohneKi` (mit 4.7), Swiss QR Code (mit 3.2), Nachweis 5.5 auf Stufe B (5.11). Die Checkbox bleibt offen, bis `ARCHIVIEREN` steht.
  - Handler `ARCHIVIEREN` erledigt mit 3.4 (PR #57, `ArchivierenHandler`: nur bei Status `ARCHIVIERUNG`, bei `ARCHIVIERT` nur Aufräumen von `eingang/`; ein Auftrag je Dokument, weil `wiederholen` nur aus `ARCHIVIERUNG_FEHLER` neu einreiht). Die Checkbox bleibt offen für Generator und `ohneKi` (4.7), Swiss QR Code (3.2) und den Nachweis 5.5 (5.11).
  - Generator und `ohneKi` erledigt mit 4.7 (`VerarbeitungSpeichern.ergebnisSpeichern(…, ohneKi)`: Dokumente mit `quelle = GENERATOR` gehen ohne `KI_VORSCHLAG` nach `ARCHIVIERUNG`, design.md «Umsetzung (Aufgabe 4.7)»). Die Checkbox bleibt offen für Swiss QR Code (3.2) und den Nachweis 5.5 (5.11).
  - Swiss QR Code erledigt mit 3.2 (PR #93): `KI_VORSCHLAG` liest den Code aus den Seiten der PDF/A, nicht `VERARBEITEN` (design.md «Umsetzung (Aufgabe 3.2)»). Die Checkbox bleibt offen für den Nachweis 5.5 (5.11).

- [x] 2.7 Frontend-Grundgerüst und Upload [Frontend]
  - Layout mobil/Desktop im UriNet-Branding, Login per OIDC (PKCE) gegen Keycloak, Navigation mit Eingangskorb-Zähler, Menüpunkte nach Rechtematrix
  - Seite Upload mit Drag & Drop, Mehrfachauswahl, «als ein Dokument», Fortschritt, Ergebnis je Datei (angenommen, «bereits vorhanden», Fehlermeldung)
  - API-Client aus OpenAPI generieren (Generator `npm run api:generieren` besteht; der Upload-Endpunkt fehlt noch, darum Upload-Seite hinter `UploadPort` und Anschluss an den generierten Client in 2.4)
  - _Anforderungen: 3.1, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 8.6_

- [x] 2.8 Mindestversionen der Handys festlegen [Fabian]
  - Mindestversionen iOS (Safari) und Android (Chrome) festlegen und per PR in Requirement 4.1 nachtragen; Testgeräte für 3.7 bereitstellen
  - Stand 13.11.2026: entschieden von Fabian – nur die aktuelle Hauptversion von iOS (Safari) und das aktuelle Chrome auf Android (Req 4.1); Testgeräte sind Fabians eigene Handys (Gerätetest in 3.7)
  - _Anforderungen: 4.1_

- [ ] 2.9 Ziel für echte Belege festlegen (Auflage A2 aus Gate 0) [Fabian]
  - Zielwerte für die Realitätsprüfung vor der Übernahme, Aufteilung der echten Belege in Abstimmungs- und zurückgehaltenen Teil und Umgang mit Änderungen aus dem Abstimmen (Prompt, Regeln, Parameter: neuer Golden-Lauf nach Req 0.9 oder nur Regeln, die Sicherheiten senken wie A1) entscheiden: [offen: Zielwerte und Aufteilung der echten Belege, Umgang mit Änderungen aus dem Abstimmen – Entscheid vor Welle 3]; Ergebnis in `docs/umsetzungsplan.md`, Abschnitt 6, und per PR in Req 0.10 (alt → neu) nachtragen
  - Der erneute Lauf vor der Übernahme ist Voraussetzung von 5.8
  - _Anforderungen: 0.10_

## Welle 3 — KI-Vorschläge, Eingangskorb, Handy-Erfassung

- [x] 3.1 Kontakte und Partner-Abgleich [Backend]
  - Liquibase `kontakt`, `verkaufschance` mit `pg_trgm`; `Normalisierer` gemäss design.md; REST `/api/v1/kontakte` (lesen; ändern nur mit Recht A12)
  - `PartnerAbgleich` (E-Mail, UID, IBAN, Ähnlichkeit ≥ 0.6, «unsicher» bei Abstand < 0.1) mit Tests
  - _Anforderungen: 2.18, 7.4_

- [x] 3.8 Golden-Generator nach F4/F6 anpassen, neuer Golden-Lauf durch Fabian (Entscheid 11.11.2026)
  - Erledigt (PR #82, #90): Generator nach F4/F6, Prompt v6 und Prototyp-Regel E3. Gate-Lauf 2026-11-12-224842 (Fabian) auf dem Merge-Commit `5b6d727` von PR #90, Prompt `v6-7a39224f2f5e`, Nachbearbeitung `nb-2026-11-12`, Modell `google/gemma-4-31B-it`, Schwelle 0.8: Prüfteil 19/20 Belege mit Art, Datum, Partner und Betrag richtig (Req 0.3), 95/100 Felder, 0 falsche Felder ab 0.8 (kleinste Schwelle mit höchstens 1 Fehler: 0.00, Req 0.9) – bestanden. Der eine Fehlschlag ist ein Beleg ohne Vorschlag: Aufruf ohne Modellantwort (Timeout oder HTTP-Fehler, Tokens unbekannt), kein falscher Vorschlag; mittlere KI-Zeit 12.4 s je Beleg statt rund 4 s in den Abstimmungsläufen. Kosten ca. CHF 0.007.
  - Folge für 3.2: Art-Regel aus Prompt v6, Regel E3 und fehlende Modellantwort (Zeitüberschreitung, HTTP-Fehler) wie eine ungültige Antwort behandeln: Dokument ohne Vorschlag in den Eingangskorb (Req 7.3).
  - Eigener Folgeauftrag vor 3.2 (`tools/golden_synth`): Prämienrechnungen von Versicherern (auch UVG, Krankentaggeld, BVG) und Mahnungen mit Mahngebühr oder Verzugszins = `LIEFERANTENBELEG`, Mahnungen ohne Gebühr = `KORRESPONDENZ` (F4/F6, entschieden von Fabian am 10.11.2026; P40-2, entschieden von Fabian am 11.11.2026); in PR #40 nicht geändert; Nummer 3.8, weil bestehende Nummern bleiben, steht aber vor 3.2
  - _Anforderungen: 0.9, 0.10_

- [x] 3.2 Metadaten-Extraktor [Backend]
  - Port `KiPort` mit Adapter (LangChain4j) und Dev-Stub; `MetadatenExtraktor` mit Prompt aus Welle 0, Schema-Validierung + eine Nachfrage, Sicherheiten je Feld, Modell (`archiv.ki.erfassung-modell`), Aufrufparameter und Schwelle aus Gate 0 (design.md «KI»), `ki_verbrauch`
  - Swiss QR Code vor dem KI-Aufruf deterministisch auslesen, seine Werte vor den KI-Werten verwenden
  - Vorschlag inkl. Vertraulichkeit (vorbelegt mit dem Standard der Art); das Belegnummer-Muster setzt die Art nur, wenn der Mandant Aussteller ist (Testfall Lieferantenrechnung `2026-123`)
  - `KiVorschlagHandler`: Vorschlag speichern, Partner verknüpfen, Status `EINGANGSKORB`; bei KI-Ausfall ohne Vorschlag weiter; Logs nur mit IDs
  - `KiVorschlagHandler` nimmt auch Dokumente in `EINGANGSKORB` mit `pdfa_fehlt` an. Er überschreibt nur, solange das Dokument nicht bestätigt und nicht vom Benutzer geändert ist (`version`), und ändert den Status nicht zurück (Review PR #54, S1; design.md «Umsetzung (Aufgabe 2.6, Teil b)»)
  - WireMock-Tests für gültige, ungültige und fehlende Antworten; ein WireMock-Test prüft den Request: `model` = Wert von `archiv.ki.erfassung-modell` (nicht `archiv.ki.chat-modell`), `temperature` 0, `top_p` 1, `presence_penalty` und `frequency_penalty` 0, `response_format` `json_schema` mit `strict`
  - Befund Gate-Lauf (`docs/spikes/golden-ergebnis.md`): Belegnummer-Vorschläge mit hoher Sicherheit gezielt prüfen, ohne Prompt, Modell oder Aufrufparameter gegenüber Gate 0 zu ändern (sonst neuer Golden-Lauf)
  - Auflage A1 (Gate 0): Sicherheit der Belegnummer höchstens 0.7, ausser die Belegnummer entspricht einem Muster des Mandanten und der Mandant ist Aussteller (design.md «KI», Nachbearbeitung); Unit-Tests: eigener Beleg im Muster (keine Begrenzung), Fremdbeleg ohne Muster (Begrenzung), Lieferantenrechnung `2026-123` im Muster des Mandanten (Req 7.12, Begrenzung)
  - in `KiAuswahl` `folgtMit` entfernen, damit in `prod` ein fehlender KI-Adapter den Start abbricht (design.md «Ablage», Startprüfungen)
  - `KiBasisUrlPruefung` prüft die wirksame Konfiguration (`LangChain4jOpenAiConfig`: `defaultConfig()` und alle `namedConfig()`) statt einzelner Schlüssel; Testfall benannter Client ohne `base-url` → Abbruch (Req 16.21)
  - Die WireMock-Testressource setzt `archiv.ki.wiremock-port` dynamisch beim Start (bis 3.2 fest `8089`, Req 16.21)
  - _Anforderungen: 7.1, 7.2, 7.3, 7.5, 7.6, 7.8, 7.11, 7.12, 16.21_
  - _Abhängig von: 3.8_
  - Nachtrag (PR #120, Review zu #93): Bildweg rendert farbige Seiten als PPM statt PNG und liest sie mit `PpmLeser` direkt als `TYPE_3BYTE_BGR`; Pixel und JPEG gleich wie im Gate (`SeitenbildGleichheitTest`), 3 Seiten auf Stufe A in rund 4 s statt 34–53 s (design.md «Umsetzung (Aufgabe 3.2)», «Bildweg als PPM»)
  - Nachtrag (PR #124, Review zu #120): Format aus `pdfinfo -box` (je Richtung das grössere aus MediaBox und CropBox, Seite ohne lesbare MediaBox nicht gerendert), dpi mit je einer Zeile und Spalte Spielraum, Pixelprüfung aus dem Dateikopf im QR-Weg (`ZxingQrLeser` lässt die Seite aus) und im Bildweg (`AwtSeitenbild`, `PpmLeser`: `SeiteUngeeignet` bei ungültigem Kopf, Pixeln über der Grenze oder Grösse ungleich Kopf), `SeiteUngeeignet` → ohne Seitenbild weiter, kein Fehlversuch (design.md «Umsetzung (Aufgabe 3.2)», «Speicher»)
  - Nachtrag (PR #126, Review zu #93): `KI_VORSCHLAG` trägt die `version` des Dokuments nach der Verarbeitung in der Nutzlast (`dokumentVersion`); der `KiVorschlagHandler` ruft das Modell nur bei genau dieser Version auf und speichert nur bei ihr, sonst endet der Auftrag ohne Modellaufruf und ohne Vorschlag (aus `VERARBEITUNG` über `ohneVorschlagWeiter` in den Eingangskorb, im Eingangskorb unverändert; Berichtigung PR #136); Szenarien zu Änderung zwischen zwei Versuchen, alter Nutzlast, letztem Fehlversuch und bestehendem Partner (design.md «Umsetzung (Aufgabe 3.2)», «Version in der Nutzlast»)

- [ ] 3.3 Lernen aus Korrekturen [Backend]
  - Beim Bestätigen Differenz Vorschlag ↔ Ergebnis als `ki_beispiel` speichern, mit den Merkmalen IBAN, UID, E-Mail, Swiss QR Code und Belegnummer-Muster
  - Vorauswahl vor dem KI-Aufruf deterministisch über diese Merkmale, ohne Treffer die 5 neuesten; kein zweiter KI-Aufruf
  - Korrekturquote je Feld als Kennzahl fortschreiben
  - Szenario: Nach einer Korrektur bei Partner X enthält der nächste Beleg mit der IBAN von X das Beispiel
  - Umgesetzt (PR #109) hinter `archiv.ki.beispiele.aktiv`: `ki_beispiel` (Changeset 016, Merkmale als Arrays, Sicht aus dem Dokument), `KorrekturLernen` beim Bestätigen, `BeispielAuswahl` und `BeispielVorauswahl` vor dem Aufruf, Zähler `archiv.ki.feld.bestaetigt` und `archiv.ki.feld.korrigiert`; siehe design.md «Umsetzung (Aufgabe 3.3)»
  - Nachtrag (PR #129, Nachprüfung #109): Changeset 022 trägt `ki_partner_id` für unbestätigte Dokumente mit Vorschlag nach, die vor 016 eingegangen sind; die eigene UID kommt per Muster aus `kennungen.uid`; Open Point zum Rang der Kandidaten; siehe design.md «Umsetzung (Aufgabe 3.3)», Nachtrag
  - Offen: Die Beispiele kommen nur mit `archiv.ki.beispiele.aktiv=true` in den Prompt (Standard aus, auch in prod, wegen Gate 0); Req 7.1 (Teil Korrekturbeispiele), 7.9 und 7.10 wirken in prod erst nach einem Golden-Lauf mit Beispielen und dem Einschalten [Fabian]. Die Checkbox wird erst danach abgehakt
  - _Anforderungen: 7.7, 7.9, 7.10_
  - _Abhängig von: 3.2_

- [x] 3.4 Bestätigen, Verwerfen, Archivieren, Wiederholen [Backend]
  - `POST /api/v1/dokumente/{id}/bestaetigen` (Recht A7): nur aus `EINGANGSKORB`, sonst 409; Pflichtfelder (422 `PFLICHTFELD_FEHLT`); Vertraulichkeit übernehmen; `aufbewahren_bis`; Frist in der Vergangenheit → «Frist abgelaufen», der Inhaber entscheidet; Belegnummer einer eigenen Art schon vergeben → 409, bei Fremdbelegen nur Warnung; Status `ARCHIVIERUNG` und Auftrag `ARCHIVIEREN`
  - `ArchivierenHandler`: `AblageService.archivieren`; Status `ARCHIVIERT` erst nach Kopie, Prüfsummenvergleich und Sperre; bei Fehler `ARCHIVIERUNG_FEHLER`, bestätigte Metadaten bleiben, E-Mail an den Inhaber (nur Dokument-ID)
  - `POST /api/v1/dokumente/{id}/verwerfen` (Recht A9): aus `EINGANGSKORB` und `FEHLER` Dateien löschen, Protokoll mit Prüfsumme und Grund, Status `VERWORFEN`; aus `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER` oder `ARCHIVIERT` → 409
  - `POST /api/v1/dokumente/{id}/wiederholen` (Recht A10): `FEHLER` → `NEU` (Verarbeitung neu), `ARCHIVIERUNG_FEHLER` → `ARCHIVIERUNG` (nur Archivierung, Metadaten unverändert), sonst 409
  - Dokument mit `pdfa_fehlt`: der Inhaber archiviert das Original allein oder verwirft
  - _Anforderungen: 2.13, 2.15, 2.16, 2.22, 5.9, 6.3, 6.4, 6.6, 6.7, 6.8, 6.13, 8.2, 8.3, 8.5, 8.8, 8.9, 8.10, 9.8_
  - erledigt (PR #57): `DokumentBestaetigen`, `DokumentVerwerfen`, `DokumentWiederholen`, `ArchivierenHandler` mit Mail an den Inhaber (nur IDs); Szenarien `features/dokument/bestaetigen.feature`, `verwerfen.feature`, `wiederholen.feature`, `features/verarbeitung/archivieren.feature`; JUnit `DokumentBestaetigenTest`, `ArchivierenHandlerTest` (design.md «Umsetzung (Aufgabe 3.4, 11.11.2026)»). Empfänger und SMTP der Benachrichtigung in `prod`: umgesetzt mit Aufgabe 5.6 (Ergänzung 14.11.2026). Offen: `SPERRE_KONFLIKT` und Änderungen nach `ARCHIVIERT` folgen mit 3.5

- [x] 3.5 Metadaten ändern und Versionen [Backend]
  - `PATCH /api/v1/dokumente/{id}` (Recht A6) mit `If-Match`: 412 `VERSION_KONFLIKT` bei Konflikt, 428 ohne Header; Grund Pflicht bei `ARCHIVIERT`, vorherige Fassung in `dokument_version`
  - Vertraulichkeit und `person_benutzer` nur mit Recht A8
  - Änderung von Belegdatum, Art, Geschäftsjahr oder `gueltig_bis` → `aufbewahren_bis` neu berechnen, Sperre nur verlängern (`sperreVerlaengern`), Konflikt protokollieren
  - Die Szenarien zu 9.5 und 10.1 vom Probe-Endpunkt `PATCH /probe/dokumente/{id}/zusatz` (Teilnachweis aus 2.2) auf `PATCH /api/v1/dokumente/{id}` umhängen; erst damit sind 9.5 und 10.1 voll nachgewiesen (Review PR #45)
  - _Anforderungen: 2.12, 2.14, 2.23, 9.7, 10.1, 10.4, 10.6_
  - erledigt (PR #67): `PATCH /api/v1/dokumente/{id}` mit `DokumentAendern` (Fassung, Grund, Frist und Sperre, `SPERRE_KONFLIKT`); Probe-Endpunkt und `DokumentZusatzSpeichern` entfernt, 9.5 und 10.1 über den echten Endpunkt; Szenarien `features/dokument/aendern.feature`, `datenmodell.feature`; JUnit `DokumentAendernTest` (design.md «Umsetzung (Aufgabe 3.5, 12.11.2026)»)

- [x] 3.6 Eingangskorb-Oberfläche [Frontend] (PR #68)
  - Liste nach Recht A5 (`mitarbeiter` nur selbst erfasste) mit Vorschaubild, Vorschlägen, «bitte prüfen»-Markierung, Partnerauswahl mit Suche, Bestätigen in ≤ 2 Tipps, Verwerfen mit Rückfrage und Grund
  - `FEHLER` mit Fehlertext, «erneut verarbeiten» und «verwerfen»; `ARCHIVIERUNG_FEHLER` mit «Archivierung wiederholen»; Hinweise «Frist abgelaufen», `pdfa_fehlt` und Belegnummer-Warnung bei Fremdbelegen
  - Aktionen nur nach Rechtematrix sichtbar
  - Playwright im 375-px-Viewport
  - _Anforderungen: 2.11, 2.24, 8.1–8.10_
  - Nachtrag (PR #128, Review zu #117): Partnersuche der Karte nach dem Muster aus 4.6, Vitests in `eingangskorb-karte.spec.ts` (verzögerter Abruf, laufende Wartezeit, gleiche Eingabe nach Entfernen)

- [ ] 3.7 Handy-Erfassung (PWA) [Frontend] [Fabian]
  - Kamera mehrseitig, jscanify im Web Worker (Kanten, Zuschnitt, Entzerrung), ohne erkannte Kanten das ganze Bild, Vorschau anpassen/verwerfen, JPEG 2'480 px
  - Upload je Seite: Rohaufnahme als Original, Zuschnitt als Arbeitsfassung
  - Offline-Warteschlange (IndexedDB) mit Zähler, automatischer Upload bei `online`; `DUBLETTE` gilt als übertragen und wird gelöscht; nur Netzwerkfehler und 5xx zählen als Fehlversuch, offline nicht; 3 Fehlversuche → manuell wiederholen; 4xx sofort fehlerhaft mit Meldung des Servers (401 → neu anmelden)
  - Nach einem Neuladen der PWA meldet man sich neu an (Tokens nur im Arbeitsspeicher, aus 2.7). `/erfassen` ist darum offline auch ohne Anmeldung zugänglich und nimmt Aufnahmen in die Warteschlange; hochgeladen wird erst nach der Anmeldung (entschieden von Fabian am 11.11.2026, PR #44)
  - [Fabian] Test auf echtem iPhone (Safari) und Android (Chrome) mit den Mindestversionen aus 2.8: Installation und 4.2 bis 4.8
  - Stand Umsetzung (PR #123, 13.11.2026): Kamera, Scanner im Web Worker, Vorschau, Warteschlange und Upload sind umgesetzt, dazu Eintrag `aufgenommen` und Aufruf von `aufnahmeName` aus 4.10 (Req 4.9); Browser-Szenarien in `features/dokument/erfassen-handy-pwa.feature` (`@req-4.2` bis `@req-4.9`). Der Haken folgt erst nach dem Gerätetest [Fabian] (Req 4.1). Nachgebessert nach dem Review: Warteschlange mit Benutzer und Mandant (nur eigene Einträge sichtbar und hochgeladen), Meldung bei einer Seite mit Fehler statt gesperrtem Knopf, Sperre während des Speicherns, Zeitpunkt der ersten verbliebenen Seite, gemeinsames `uploadProblem` mit `fehlercode`
  - _Anforderungen: 4.1–4.8_
  - _Abhängig von: 2.8_

## Welle 4 — CSV-Import, Suche, Generatoren, Altbestand

- [x] 4.1 CSV-Erkennung und Spaltenzuordnung [Backend]
  - Upload `/api/v1/importe/crm` (Recht A12), SHA-256, ICU4J-Zeichensatz, Trennzeichen aus Kopfzeile, Grenzen (Spalte für `name` Pflicht, bei 21.CRM `Name` oder `Organisation`; Zeile ohne Namen oder mit falscher Spaltenzahl «fehlerhaft»; ≤ 10'000 Zeilen; ohne Datenzeilen abgelehnt); Parser nach RFC 4180 (Felder in Anführungszeichen mit `;` oder Zeilenumbruch)
  - Profil «21.CRM» aus design.md (Erkennung der Kopfzeile, Name aus `Name` oder `Organisation`, E-Mail/Telefon über Index, gelöschte Zeilen übersprungen, Zuordnung nach Spaltenposition) als Vorschlag im Zuordnungsdialog und Synonymliste für andere Dateien (beide design.md, Befund in `docs/spikes/21crm-export.md`); `import_zuordnung` lesen/speichern; abweichende Kopfzeile → Zuordnungsdialog
  - Tests mit den Beispieldateien aus 0.4 (`21crm-kontakte-beispiel.csv` Windows-1252/Semikolon, `21crm-verkaufschancen-beispiel.csv` ohne Datenzeilen) plus daraus abgeleitete Varianten: UTF-8 mit BOM, Komma, Feld in Anführungszeichen mit `;` und Zeilenumbruch, Zeile mit falscher Spaltenzahl, Zeile ohne Namen, `Ist gelöscht = true`, eigene Datei nur mit Kopfzeile für Req 13.11 (die Verkaufschancen-Beispieldatei wird nach dem Nachexport gefüllt)
  - _Anforderungen: 2.18, 13.1, 13.2, 13.10, 13.11_
  - _Abhängig von: 0.4_

- [x] 4.2 Vorschau und Abgleich [Backend]
  - Auftrag `CSV_VORSCHAU`: Abgleich Kontakt-ID → E-Mail → normalisierter Name + PLZ (ein exakter Treffer zählt), Ergebnis je Zeile in `import_zeile`, Zähler im Lauf
  - «mögliche Dublette» bei mehreren Kandidaten, sich widersprechenden Stufen oder nur Ähnlichkeit ab der Schwelle aus design.md
  - _Anforderungen: 13.3, 13.4, 13.5_

- [ ] 4.3 Übernahme und Protokoll [Backend]
  - Auftrag `CSV_UEBERNEHMEN` in einer Transaktion; leere Zellen überschreiben nichts; Dubletten-Entscheide; «nicht mehr im CRM-Export» nur bei Vollexport und nur für Kontakte mit `crm_id`, nie löschen; Originaldatei nach `importe/`
  - Verkaufschancen-Import (Importart `verkaufschance`) über die Kontakt-ID aus 21.CRM zuordnen (Profil «21.CRM»: `ACCOUNTGUID` → `kontakt.crm_id`, `GGUID` → `verkaufschance.crm_id`, design.md), bestehende über `crm_id` aktualisieren
  - _Anforderungen: 13.6, 13.7, 13.8, 13.9_

- [x] 4.4 Import-Oberfläche [Frontend]
  - Upload mit Markierung «Vollexport», Zuordnungsdialog (Spalte → Feld mit Vorschlag; erkanntes Profil «21.CRM» vorausgewählt), Vorschau mit Zählern und Filter je Ergebnis, Dubletten entscheiden, Übernehmen, Liste der Läufe; nur mit Recht A12
  - _Anforderungen: 13.2, 13.3, 13.5, 13.6, 13.7_

- [x] 4.5 Volltextsuche [Backend]
  - `GET /api/v1/dokumente` mit `websearch_to_tsquery` (inkl. Tags), Filtern (Art, Partner, Zeitraum, Betrag, Richtung, Tags, Status, Geschäftsjahr, Belegnummer exakt), Sortierung, `ts_headline`; Vertraulichkeit nach 2.3 und Mandant
  - Lasttest mit 50'000 synthetischen Dokumenten: p95 ≤ 1 s; Lastskript hier bereitstellen, Nachweis erst auf Stufe B in 5.11 (Messbedingungen nach Req 3.2, 5.5, 14.3 und 16.26), in Stufe A nur Funktion
  - `GET /api/v1/dokumente/{id}/datei` mit vorsignierter URL (5 Min.) für die gespeicherte Versions-ID, mit Protokoll
  - _Anforderungen: 14.1–14.7_
  - Erledigt (PR #56): Suche und Download wie design.md «Suche», «Umsetzung (Aufgabe 4.5)»; Lastskript `tools/last/suche.py`. 14.3 lokal nur als Grössenordnung (10'000 Dokumente), der Nachweis folgt in 5.11

- [x] 4.6 Suche und Dokumentansicht [Frontend]
  - Suchfeld, Filterleiste (Art, Partner, Zeitraum, Betrag, Richtung, Status, Tags, Geschäftsjahr, Belegnummer), Trefferliste mit Auszug
  - Dokumentansicht: PDF-Vorschau, Metadaten bearbeiten (mit Grund, nur mit Recht A6), Versionen, Download
  - _Anforderungen: 10.1, 14.1, 14.2, 14.4, 14.7_
  - Teilweise erledigt (PR #71): Suche mit Filterleiste und Auszug, Dokumentansicht mit Metadaten, PDF/A-Vorschau und Download (dazu `GET /dokumente/{id}/datei` mit `Accept: application/json`, 14.6), design.md «Frontend», «Suche und Dokumentansicht (Aufgabe 4.6)». Offen: Metadaten bearbeiten mit Grund (A6, 10.1; braucht 3.5), Versionsliste (`GET /dokumente/{id}` liefert laut Endpunkt-Tabelle die Versionen, `DokumentDto` noch nicht; folgt mit oder nach 3.5, PR #67), Vorschaubild (PR #68)
  - Erledigt (PR #100): Metadaten bearbeiten mit Grund und `If-Match` (A6, A8 für die Vertraulichkeit, 10.1), Versionsliste (`DokumentDto.versionen`), Vorschaubild der ersten Seite; design.md «Suche und Dokumentansicht (Aufgabe 4.6)», «Rest von 4.6»
  - Nachtrag (PR #107, Review zu #104/#106): Art nur im KI-Vorschlag geht als `?art=` an die Dokumentansicht, die das Formular einmal mit dieser Art als Artwechsel öffnet (Vitests in `eingangskorb-karte.spec.ts` und `dokument-ansicht.spec.ts`, «Art aus dem Eingangskorb»); Artwechsel ohne passende `zusatz` → 422 (`aendern.feature`, `@req-9.5 @req-10.1`); Versionsliste nur mit Fassungen `version < dokument.version` (`datenmodell.feature`, `@req-10.1`); Partnersuche setzt sich beim Wählen und Entfernen zurück (Vitest `metadaten-formular.spec.ts`)
  - Nachtrag (PR #117, Review zu #107): `?art=` gilt nach Neuladen (409/412/404) nicht mehr; Karte gibt eine inaktive Art nicht als `?art=` weiter und verlangt zuerst eine aktive Art; Partnersuche verwirft auch einen schon laufenden Abruf (Abbruch vor dem Warten); Vitests in `dokument-ansicht.spec.ts` (412, ohne A6, gleiche Art, Status ausserhalb der änderbaren), `eingangskorb-karte.spec.ts` (inaktive Art) und `metadaten-formular.spec.ts` (verzögerter Abruf); design.md «Art nur im KI-Vorschlag», «Rest von 4.6» (Konsistenz Versionsliste als eigener Punkt), Routenübersicht mit `?art=<code>`

- [x] 4.7 Generator-Endpunkt und Python-Client [Backend]
  - `POST /api/v1/dokumente/generator` (Scope `generator`): Pflichtfelder der Art (422 `PFLICHTFELD_FEHLT`), unbekannte `crmId` (422); `vertraulichkeit` und `personBenutzer` Pflicht bei Standard `PERSONAL`, sonst Standard der Art
  - Verarbeitung ohne KI (Prüfsumme, Dublettenprüfung, PDF/A, Volltext, Vorschau), `aufbewahren_bis`, Archivierung über `ARCHIVIERUNG` nach `ARCHIVIERT`; Antwort 201 oder 202 gemäss design.md
  - gleiche SHA-256 wie ein vorhandenes Dokument → 200 mit vorhandener ID; Belegnummer einer eigenen Art mit abweichender SHA-256 → 409
  - Verwerfen eines Generator-Dokuments (aus `FEHLER` oder `EINGANGSKORB`) gibt die Reservation in `belegnummer_eigen` in derselben Transaktion frei; Integrationstest: verwerfen, korrigiert neu senden → 202
  - `tools/archiv_client.py` (requests, Token aus Datei mit Rechten 600: `~/.config/urinet-archiv/token` oder Pfad aus `ARCHIV_TOKEN_DATEI`, nie im Code oder als Umgebungsvariable); bei Ausfall Ordner `archiv-ausstehend/`, bei 409 nach `archiv-konflikt/` ohne erneutes Senden; Einbau-Beispiel für `rechnung_gen.py` und `urinet_pdf.py` in `tools/README.md`
  - _Anforderungen: 11.1–11.11_
  - Erledigt: `DokumentGeneratorResource`/`DokumentGenerator`, Worker-Pfad ohne KI, `tools/archiv_client.py` (nur Standardbibliothek statt `requests`) mit `tools/test_archiv_client.py` und `tools/README.md`; Szenarien `features/dokument/generator.feature` (`@req-11.1`–`11.11`; für `11.4`, `11.5` und `11.9` ruft das Szenario den echten Client per `python3` gegen den Testserver auf); `ProbeTokenResource` entfernt (design.md «Umsetzung (Aufgabe 4.7)»)

- [x] 4.8 Drive-Import-Werkzeug [Backend]
  - `tools/drive_import` (Java, Picocli), Anmeldung mit API-Token vom Scope `import` (Token aus Datei wie in 4.7, nie als Umgebungsvariable): rekursiv, Trockenlauf, Ordnerpfad als `hinweis`; Pfad-Muster → Hinweise an die KI (Kunde, Art)
  - `DUBLETTE` gilt als übertragen («schon vorhanden»); `.gdoc/.gsheet` in der Liste «bitte als PDF exportieren»; Office-, `.eml`- und ZIP-Dateien sowie vom Archiv abgelehnte Dateien unter «nicht übernommen» mit Pfad und Grund
  - Schlussbericht: gefunden = übertragen + schon vorhanden + übersprungen + abgelehnt
  - importierte Dokumente landen im Eingangskorb, nie direkt im Archiv
  - _Anforderungen: 12.1–12.8_

- [ ] 4.9 Entscheide vor Welle 5 [Fabian]
  - Alle Punkte vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86 (Kennungen 4.9-E1 bis 4.9-E7, nicht zu verwechseln mit den Konzept-Entscheiden E1–E5; Übersicht in `docs/umsetzungsplan.md`, Abschnitt 6, Zeile «Entscheide vor Welle 5 (Aufgabe 4.9)»); nachgetragen in requirements.md, design.md, tech.md, structure.md, ADR-003, ADR-007, ADR-008, ADR-010, Spec 03 (8.5) und `docs/umsetzungsplan.md` (Abschnitte 6–8). Der Haken folgt erst, wenn die Vorbedingungen [Fabian] unten erledigt sind
  - 4.9-E1 Zeitstempel: Hauptdienst TSA der Swiss Government PKI (BIT, ZertES-qualifiziert, ohne Anmeldung; geduldete Nutzung ohne formelle Anfrage), jedes Manifest zusätzlich bei einem zweiten RFC-3161-Dienst gestempelt (FreeTSA; Sectigo als Option), Dienste als Konfiguration; Hash-Kette erneuert die Stempel des Vortags (Req 15.2, 15.3, 15.11, 15.12, 16.7)
  - 4.9-E2 Zweitkopie: bestehendes Hostpoint-Webhosting, restic über SSH mit Forced Command `rclone serve restic --stdio --append-only <pfad>`, Bucket-Zuwächse mit eigenem Tag und `--keep-tag`, Ausdünnen nur von Hand mit zweitem Schlüssel; Rückfall bei Absage: cloudscale.ch Object Storage, Standort RMA, mit Object Lock (Req 16.2, 16.12)
  - 4.9-E3 Rhythmus der Restore-Probe: monatlich (GitHub-Cron `23 4 2 * *`, UTC) im maintenance-Repo, Gruppe `restic`, dazu von Hand nach Änderungen an Export oder Wiederherstellung, nach Hauptversionswechsel von PostgreSQL oder Keycloak, in 5.11 auf der neuen VPS vor dem Umstellen und vor 5.8; Alarm rot oder älter als 35 Tage (Req 16.3, 16.25)
  - 4.9-E4 Schlüsselweg der Restore-Probe: zweiter age-Empfänger «Probe-Schlüssel», privat nur auf der Stufe-B-VPS (root-only, entschlüsselt über eine sudo-Regel nur für `probe-entschluesseln`), Runner streamt nur Chiffrat über `probe-gate.sh`; Probe-Netz `internal: true` (Req 16.8, 16.23)
  - 4.9-E5 Sperren im Probe-Bucket: ja, Stufe P (`manifest/` COMPLIANCE, `archiv/` GOVERNANCE, je höchstens 1 Tag, Präfix `urinet-archiv-p-`, nur in `deploy/probe/`, ohne Manifest, Zeitstempel, KI und Mails); Kappung beim Wiederherstellen nur beim Präfix `urinet-archiv-p-`; Ist-Sperre aus dem Inventar gegen Soll-Sperre aus der DB (Req 6.11, 16.24, 16.27)
  - 4.9-E6 Aufbewahrungsfrist des Zugriffsprotokolls: später von `aufbewahren_bis` (nur pflichtige oder gesperrte, bestätigte Dokumente) und Ende Geschäftsjahr + 10 Jahre; in Phase 1 kein Löschen, keine Pseudonymisierung; Benutzerverzeichnis nur einfügend (Req 9.9, 10.9, 10.10)
  - 4.9-E7 K5: eigene Infomaniak VPS Lite 4 vCPU / 4 GB / 80 GB, CHF 9.00/Mt. bei Jahresbindung, Bestellung erst zu Beginn von 5.11 nach einer Lastmessung auf einer Public-Cloud-Instanz; bei verfehlten Zielen an Ort auf 4/8/160 aufrüsten; Messbedingungen für 3.2, 5.5 und 14.3 mit Mess-Mandant `last`; Budget und Abbruchkriterium nach Plattform, nicht nach `archiv.stufe`; Speicherabbrüche durch manuelle Eingriffe zählen nicht für 16.18 (Req 3.2, 5.5, 14.3, 16.1, 16.16–16.18, 16.26)
  - Vorbedingungen [Fabian], offen bis zum Haken: (1) schriftliche Zusage von Hostpoint, dass ein externes, verschlüsseltes Backup von bis zu rund 80 GB im Webspace zulässig ist (AGB Webhosting Ziff. 2.2.2); bei Absage gilt der Rückfall cloudscale.ch, und ein Folge-PR führt Req 16.2, ADR-008 und tech.md nach; (2) Paket und freien Platz des Webhostings im Control Panel prüfen; (3) Website-Deploys auf eigene FTP-Konten mit festem Verzeichnis umstellen, weil ein Zugang mit Vollzugriff auf das Konto sonst die Zweitkopie löschen kann; (4) nur falls Sectigo zweiter Dienst wird: dessen Bedingungen prüfen; (5) Konto der Zweitkopie klären: `--append-only` schützt nur gegen einen gestohlenen Workflow-Schlüssel, nicht gegen eine Kompromittierung des Hostpoint-Kontos (PHP der Websites läuft als Benutzer des Kontos und kann Repository, `authorized_keys` und rclone ändern); bevorzugt ein eigenes Hostpoint-Konto oder Paket ohne Websites, PHP und fremde Deploy-Schlüssel (Kosten prüfen); sonst Repository, `authorized_keys` und rclone ausserhalb des Webroots und die Frist der Hostpoint-eigenen Sicherungen erfragen, Restrisiko im PR festhalten (design.md «Sicherung und Wiederherstellung»)
  - Bei «Anforderungen» stehen nur Kriterien mit Nachweis «manuell», die der Entscheid selbst festlegt; die übrigen geänderten Kriterien stehen bei den umsetzenden Aufgaben 5.1, 5.2, 5.5, 5.7 und 5.11, damit der Haken von 4.9 keine noch nicht umgesetzten Kriterien als nachgewiesen zählt
  - _Anforderungen: 16.1, 16.2, 16.3, 16.18, 16.23, 16.25, 16.26_

- [x] 4.10 Dateiname des Uploads speichern, anzeigen und durchsuchen [Backend] [Frontend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 13.11.2026: Dokumente in Verarbeitung oder ohne Vorschlag zeigen mindestens den Dateinamen; gespeichert, angezeigt und durchsuchbar, nur Ersatzanzeige, der Titel wird nicht vorbelegt (design.md «Dateiname», «Suche», «Frontend»); Nummer 4.10 als nächste freie der Welle 4
  - Liquibase `db/mandant`, nächste freie Nummer (z. B. `012-dokument-dateiname`), bestehende Changesets unverändert: Spalte `dokument.dateiname text null` mit Check 1–255 Zeichen, Funktion `archiv_dateiname_text` (`IMMUTABLE`, ASCII-Ausdruck aus design.md «Suche»), `volltext_tsv` per `alter column … set expression` um den Teil `D` erweitern; rückwärtskompatibel zum Vorgänger-Release (16.11), Bestand bleibt ohne Namen; vor der Übernahme 5.8 in Betrieb nehmen, sonst Dauer der Sperre vorher auf einer Kopie messen
  - `DateiName`: speicherbaren Namen als eigenen Wert (`speicherbar()`, leer beim Ersatz `datei` oder ungültigem Namen), Zl und Zp (U+2028, U+2029) zusätzlich ablehnen (Req 3.10); Javadoc («nie in … die Datenbank») nachführen
  - Upload (`DokumentErfassen`): `speicherbar()` speichern, nie den Ersatz `datei`; bei `zusammenfassen` der Name der ersten angenommenen Original-Datei; Dublette und abgelehnte Datei ohne Namen
  - Generator (`DokumentErfassen.generatorAnlegen`, `DokumentGeneratorResource`): Namen des Teils `datei` mit `DateiName` bereinigen und `speicherbar()` speichern; ein ungültiger Name ergibt `null`, das PDF wird angenommen wie bisher; bei 200 (gleiche SHA-256) nichts; `tools/archiv_client.py` ersetzt alle Zeichen nach Req 3.10 durch `_`, Fall in `tools/test_archiv_client.py`
  - Drive-Import: Das Werkzeug sendet schon nur den Namen ohne Ordner (4.8); Szenario belegt Speicherung und Ordnerpfad nur im `hinweis`
  - Handy-Erfassung (PWA): 4.10 baut Funktion und Aufruf: Name «Aufnahme TT.MM.JJJJ HH:MM» über `formatToParts` (Europe/Zurich, `hourCycle: 'h23'`), Zeitpunkt der ersten Seite (`aufgenommen`) im Eintrag der Warteschlange; 3.7 bleibt unverändert
  - nicht änderbar (Req 3.14): `PATCH /dokumente/{id}`, Bestätigen und Generator-`metadaten` mit Feld `dateiname` → 400 `ANFRAGE_UNGUELTIG`, nichts geändert
  - API: `DokumentDto.dateiname` (in jeder Antwort mit dem Dokument, auch `PATCH` und Bestätigen) und `SuchtrefferDto.dateiname` (`nullable`); OpenAPI und generierter Client nachführen
  - Suche: Auszug zusätzlich über `archiv_dateiname_text(dateiname)`; `SuchePort` mit Variante ohne Dateinamen (`mitDateiname`, Req 3.13) für Spec 02, 03 und 04; `SucheLeistungTest` mit synthetischen Dateinamen, `tools/last/suche.py` mit Suchbegriffen, die auch nur Dateinamen treffen; 14.3 unverändert
  - KI (Req 3.12): Der WireMock-Test zum Request aus 3.2 prüft zusätzlich, dass der Dateiname des Testdokuments im Request fehlt; Prompt `v6-7a39224f2f5e` unverändert, kein neuer Golden-Lauf
  - nie nach aussen: Log, Mail, Problem Details (ausser `ergebnisse[].datei` des Uploads), `zugriff_protokoll.details`, Nutzlast der Aufträge, S3-Schlüssel, vorsignierte Download-URL und Antwort-Header; das Manifest prüft 5.2 (es entsteht erst dort)
  - PWA: `anzeigeTitel` und Platzhalter «Ohne Titel · <Dateiname>» (kursiv, Screenreader-Text, gekürzt mit vollem Namen im `title`, nur als Text gesetzt, isoliert in `<bdi>`) in Eingangskorb-Karte, Trefferliste, Dokumentansicht sowie in Erfolgsmeldungen und Rückfragen; Zeile «Dateiname» in der Dokumentansicht in jedem Status; weder das Bestätigen noch «Metadaten bearbeiten» (`PATCH`) belegt `titel` mit dem Dateinamen vor oder sendet ihn
  - Szenarien (Java, gegen das echte Backend): `@req-3.8`, `@req-3.9`, `@req-3.10` in `features/dokument/upload.feature` (Einzeldatei, `zusammenfassen` mit Dublette an erster Stelle, ohne Namen, Pfadanteile, Datei namens `datei` gespeichert, Ersatz `datei` nicht gespeichert, U+202E und U+2028 → 400 im Ergebnis der Datei, Nachbar angenommen); `@req-3.12` in `upload.feature`: Upload und Verarbeitung bis `EINGANGSKORB` durchlaufen lassen, dann Log, Mailpit, `zugriff_protokoll.details`, `auftrag.nutzlast`, S3-Schlüssel, WireMock-Request und `Location` sowie Header des Downloads auf den Namen prüfen; `@req-3.11` in `features/dokument/datenmodell.feature` (Sichtregel je Rolle mit zwei Mandanten, Titel bleibt leer); `@req-3.14` in `aendern.feature`, `bestaetigen.feature` und `generator.feature` (Feld `dateiname` → 400); `@req-11.12` in `generator.feature` (mit Namen, ohne Namen, ungültiger Name → angenommen ohne Namen, 200 ohne Namen); `@req-12.9` in `features/importe/drive-import.feature`; `@req-14.8` und `@req-14.9` (API-Feld) in `features/suche/volltextsuche.feature` und `features/dokument/eingangskorb.feature` (Testfall `Scan_Rechnung-2026.pdf`, unsichtbares Dokument nicht über seinen Namen auffindbar, Auszug); `@req-16.11` (Rücksprung auf das Vorgänger-Release mit der neuen Spalte)
  - Browser-Szenarien gegen das echte Archiv im Testmodus: `@req-4.9` in `features/dokument/erfassen-pwa.feature` (feste Uhr, Playwright mit `timezoneId` ausserhalb Europe/Zurich, Upload aus der Warteschlange am Folgetag, gespeicherter Name); `@req-8.11` in `eingangskorb-pwa.feature` (Platzhalter, Bestätigen ohne Titel aus dem Dateinamen, Meldung mit Platzhalter); `@req-14.9` und `@req-14.10` in `suche-pwa.feature` (Platzhalter, Zeile «Dateiname», 375 px ohne waagrechtes Scrollen, `PATCH` ohne Titel aus dem Dateinamen, Name mit Zeichen von rechts nach links isoliert)
  - Unit-Tests: JUnit `ArchivDateinameTextTest` gegen PostgreSQL der Dev Services (`.pdf`, `.PDF`, `.jpeg`, `.7z` entfernt, `Beleg 13.11.2026` behält `.2026`, nur `.pdf`, Endung mit Umlaut bleibt, `_`, `-` und `.` als Trenner, `null` → leer), `DateiNameTest` ergänzt (U+2028, U+200D zulässig, Datei namens `datei`, `speicherbar()` leer beim Ersatz), `AuszugTest` für die Variante ohne Dateinamen (Req 3.13); Vitest für `anzeigeTitel` und den Aufnahme-Namen (genau `Aufnahme 13.11.2026 08:15`, Zeitumstellung 29.03. und 25.10.2026, Gerät in anderer Zeitzone, Mitternacht)
  - Req 3.12 ohne den Teil «Manifest», den prüft 5.2
  - Stand Umsetzung (PR #104, 13.11.2026): Backend, API, Suche, Generator, Drive-Import, Tools, `anzeigeTitel`/`TitelAnzeige` und der Aufnahme-Name (`aufnahmeName`, Vitest) sind umgesetzt; Changeset `015-dokument-dateiname` (012 belegt PR #91, 013 main und PR #87); `SucheOhneDateinameTest` statt `AuszugTest`; Mail-Prüfung in `features/verarbeitung/archivieren.feature` (`@req-3.12`). Die Browser-Szenarien `@req-8.11`, `@req-14.9`, `@req-14.10` laufen gegen die Attrappen der PWA (API-Vertrag durch die Java-Szenarien belegt), nicht gegen das echte Archiv. WireMock-Prüfung des KI-Requests (`@req-3.12` in `upload.feature`) und Rücksprung (`@req-16.11` in `datenmodell.feature`) sind umgesetzt. Offen und darum nicht abgehakt: Eintrag `aufgenommen` in der Warteschlange, Aufruf von `aufnahmeName` beim Upload und `@req-4.9` in `erfassen-pwa.feature` mit 3.7; Manifest mit 5.2
  - Nachtrag (PR #107, Review zu #104/#106): `DokumentUploadResource` liest die Reihenfolge der Teile über `MultipartFormDataInput`; passt die Zahl der Datei-Teile nicht, antwortet der Upload mit 400 `ANFRAGE_UNGUELTIG` statt 500 (`DokumentUploadTeileTest`); Index-Hinweis zur Suche ohne Dateinamen (Req 3.13) in Spec 02 (7.1) und Spec 03 (11.2), nur Doku
  - Nachtrag (PR #117, Review zu #107): Szenario `@req-3.8 @req-3.3` in `upload.feature` mit Arbeitsfassungen und einem Teil `dateien` ohne Namen → 400 `ANFRAGE_UNGUELTIG`, nichts gespeichert; design.md API-Tabelle `POST /dokumente` um 400 ergänzt, «Umsetzung 4.10» um die abweichende Zahl der Datei-Teile
  - Nachtrag (PR #123, mit 3.7): Eintrag `aufgenommen` in der Warteschlange, `aufnahmeName` beim Upload und `@req-4.9` in `features/dokument/erfassen-handy-pwa.feature` sind umgesetzt (statt in `erfassen-pwa.feature`); offen bleibt nur das Manifest mit 5.2, darum weiterhin ohne Haken
  - Nachtrag (PR #135, Abschluss): Browser-Szenarien `@req-8.11`, `@req-14.9` und `@req-14.10` gegen das echte Archiv im Testmodus in `features/dokument/dateiname-pwa.feature` (ohne `@attrappe`; Weiterleitung aller Anfragen an `/api/v1/**` in `frontend/e2e/hilfen/echtes-backend.mjs`, Schritte in `schritte/echtes-archiv.mjs`); Testhilfe für den Status in `BrowserBackendTest` (Anfragedateien unter `target/browser-backend-anfragen/`, `EINGANGSKORB` oder `FEHLER`); `suche-pwa.feature` und `eingangskorb-pwa.feature` (seither `@attrappe`) bleiben Teilnachweis; das Manifest ohne Dateinamen belegt `features/protokoll/manifest.feature` (`@req-3.12`, 5.2), darum abgehakt
  - _Anforderungen: 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14, 4.9, 8.11, 11.12, 12.9, 14.8, 14.9, 14.10, 16.11_
  - _Abhängig von: 2.4, 3.2, 3.6, 3.7, 4.5, 4.6, 4.7, 4.8_

- [x] 4.11 Kontakte ansehen und pflegen: Schnittstelle [Backend]
  - Herkunft: Spec-Erweiterung, entschieden von Fabian am 14.11.2026 nach dem ersten CSV-Import mit Adressen (Req 17; design.md «Kontakte ansehen und pflegen»); Auslegungen vorgeschlagen 14.11.2026, entschieden mit dem Merge des Spec-PR. Nummern 4.11 und 4.12 als nächste freie der Welle 4. Läuft parallel zu Spec 02, Welle 7 (Chat); der Chat hat Vorrang (entschieden von Fabian am 14.11.2026)
  - Liquibase `db/mandant`, nächste freie Nummer ab 026 (vor dem Push auf `origin/main` prüfen), bestehende Changesets unverändert: `kontakt.aktiv`, `inaktiv_seit`, `zusammengefuehrt_in` mit den Checks aus «Data Models»; `import_zeile.ergebnis` um `INAKTIV`, `import_lauf.inaktiv`
  - REST mit OpenAPI: `GET /kontakte/liste`, `GET /kontakte/{id}`, `GET /kontakte/{id}/verweise`, `POST /kontakte/{id}/zusammenfuehren`, `POST /kontakte/{id}/inaktiv`, `POST /kontakte/{id}/aktiv`; `PATCH /kontakte/{id}` auf inaktive → 409; `GET /kontakte?q=` nur aktive
  - Zusammenführen in einer Transaktion (`KontaktZusammenfuehren`, `KontaktVerweise`): Verweise umhängen, Dokumente mit neuer Version und `dokument_version` wie `DokumentAendern`, Quelle inaktiv mit `zusammengefuehrt_in`; Protokoll `KONTAKT_ZUSAMMENFUEHREN` (Lauf und je Dokument), `KONTAKT_INAKTIV`, `KONTAKT_AKTIV`; `KontaktVerweiseTest` gegen `information_schema`
  - Inaktive kein Ziel neuer Zuordnungen: `PartnerAbgleich`, Kandidaten im Eingangskorb, `PATCH /dokumente/{id}` und Bestätigen, Generator (`KontaktRegister.mitCrmId`); CSV-Import: Ergebnis `INAKTIV`, Kandidaten und Dubletten-Entscheide nur aktive, Übernahme lässt inaktive aus, «nicht mehr im CRM-Export» nur für aktive
  - Szenarien (Java, gegen das echte Backend) in `features/kontakt/kontakte-pflegen.feature` und Ergänzungen der bestehenden Features für 7.4, 11.7, 13.3, 13.4 und 13.7; Rollen je Recht (2.6) inkl. `VERWEIGERT`; nur synthetische Kontaktdaten
  - _Anforderungen: 2.18, 7.4, 11.7, 13.3, 13.4, 13.7, 17.1–17.16_
  - _Abhängig von: 3.1, 3.5, 4.2, 4.5; mit 4.3 abstimmen (Übernahme `CsvUebernehmenSpeichern`, dieselben Dateien)_
  - erledigt (PR #146): Changeset 026, `KontaktResource` (Liste, Detail, Verweise, Zusammenführen, inaktiv, aktiv), `KontaktZusammenfuehren`, `KontaktVerweise`; Szenarien `features/kontakt/kontakte-pflegen.feature` und Ergänzungen in `kontakte.feature`, `dokument/generator.feature`, `aendern.feature`, `bestaetigen.feature`, `importe/vorschau-abgleich.feature`, `uebernahme.feature`; JUnit `KontaktVerweiseTest`, `KontaktAbgleichTest`, `JdbcKontaktRegisterTest`, `KontaktListeParameterTest` (design.md «Umsetzung (Aufgabe 4.11, 14.11.2026)»)

- [ ] 4.12 Kontakte ansehen und pflegen: Oberfläche [Frontend]
  - Herkunft wie 4.11 (Req 17; design.md «Kontakte ansehen und pflegen», Abschnitt «Oberfläche»); parallel zu Spec 02, Welle 7, der Chat hat Vorrang
  - Routen `/kontakte` und `/kontakte/:id` (lazy, Guard auf A6), Eintrag «Kontakte» in der Navigation nur mit A6
  - Liste mit Suche, Filtern (Status, Herkunft, «nicht mehr im CRM-Export»), Blättern und Gesamtzahl; Detail mit Stammdaten, Kennzeichen und den sichtbaren Dokumenten (`GET /dokumente?partner=`, Platzhalter nach 8.11)
  - Mit A12: Bearbeiten (`PATCH`, `If-Match`, 412 → neu laden), Zusammenführen (Ziel suchen, Grund Pflicht, Rückfrage mit den Zahlen aus `/verweise`), als inaktiv markieren, wieder aktivieren
  - Import-Oberfläche (4.4): Zahl und Filter «inaktiv», Zeilen mit Kontakt verlinkt; Dokumentansicht (4.6): Partner verlinkt mit A6; Zugriffsprotokoll (5.1): neue Aktionen im Filter
  - 375 px ohne waagrechtes Scrollen; Vitests je Komponente, Szenarien für 17.5, 17.6 und 17.17
  - _Anforderungen: 13.3, 17.1–17.6, 17.17_
  - _Abhängig von: 4.11, 4.4, 4.6_

## Welle 5 — GeBüV, Betrieb, Abnahme Phase 1 (Voraussetzung: Entscheide aus 4.9)

- [x] 5.1 Zugriffsprotokoll vollständig [Backend] [Frontend]
  - `@Protokolliert` an allen Endpunkten der Liste in design.md, inkl. verweigerter Zugriffe (`VERWEIGERT`); REST `/api/v1/protokoll` (Recht A13) mit Filtern; Seite `/verwaltung/protokoll`
  - Aufbewahrungsfrist aus 4.9 (4.9-E6, Req 10.9): kein Codepfad löscht oder pseudonymisiert Einträge von `zugriff_protokoll` oder `dokument_version`, Trigger und Rechte bleiben (nur Einfügen); Nachweis hier per JUnit (kein Löschpfad, Rechte nur `INSERT` und `SELECT`); die Regel steht in `docs/verfahrensbeschreibung.md`, Abschnitt Zugriffe, die erst in 5.7 entsteht (dort der Teilnachweis «Dokument»)
  - Benutzerverzeichnis (Req 10.10): Tabelle `benutzer_verzeichnis` je Mandant, nur Einfügen (Trigger wie `zugriff_protokoll`); wann ein Eintrag entsteht (erste Aktion, geänderter Name oder Rolle, Austritt durch den Inhaber) legt der PR fest; API-Tokens bleiben nach dem Widerruf als Zeile erhalten (design.md «Sicherheit»)
  - Tests: UPDATE/DELETE auf `zugriff_protokoll`, `dokument_version` und `benutzer_verzeichnis` scheitert; ohne Protokolleintrag keine Aktion
  - _Anforderungen: 2.19, 10.2, 10.3, 10.5, 10.7, 10.8, 10.9, 10.10_
  - _Abhängig von: 4.9_
  - Erledigt: Changeset `010-benutzer-verzeichnis` (nur Einfügen, Indizes für die Filter), `BenutzerVerzeichnisPflege` im Schreibweg von `JdbcZugriffProtokoll` und beim Widerruf eines Tokens, `ProtokollResource` (`GET /protokoll`, `GET /protokoll/benutzer`, `POST /protokoll/benutzer/{benutzer}/austritt`, A13), Seite `/verwaltung/protokoll`; Szenarien `features/protokoll/protokoll-einsicht.feature` (`@req-2.19`, `@req-10.5`, `@req-10.10`), bestehende Szenarien für `10.2`, `10.3`, `10.7`, `10.8`; `10.9` per JUnit (`KeinLoeschpfadTest`, `MandantEigentuemerTest`); Teilnachweis «Dokument» für 10.9 folgt mit 5.7 (design.md «Umsetzung (Aufgabe 5.1)»)

- [x] 5.2 Tages-Manifest [Backend]
  - Auftrag `MANIFEST` täglich 03:30 Europe/Zurich: alle Dateien seit dem letzten Manifest nach Zeitpunkt der gesetzten Sperre (ohne gesetzliche Pflicht: Wechsel auf `ARCHIVIERT`), je Datei Dokument-ID, Variante, SHA-256 und als Zusatz die Versions-ID; Hash der Zeilen von `zugriff_protokoll` und `dokument_version` des Vortags
  - Protokollzeilen so hashen, dass ein späterer Löschweg (9.9) einzelne Zeilen entfernen kann, ohne den Nachweis der übrigen zu zerstören: SHA-256 je Zeile (bevorzugt, weil sich die Frist eines Dokuments nach 9.7 ändern kann) oder je Fristklasse nach 10.9; Wahl im PR (design.md «Manifest»)
  - Feld `sicherung`: SHA-256 der Exporte von `archiv-sicherung` seit dem vorigen Manifest, damit eine Wiederherstellung nach Verlust der VPS nur belegte Snapshots einspielt (design.md «Manifest»); bleibt leer, bis 5.5 die Liste anbindet
  - Hash-Kette (erstes Manifest `vorher_sha256 = null`), Ed25519-Signatur (Schlüssel als Secret-Datei, öffentlicher Schlüssel unter `docs/`); die Kette nimmt zusätzlich die SHA-256 von `.sig`, allen `.tsr` und den abgelegten Prüfdaten des Vortags auf (15.2, Erneuerung nach RFC 4998)
  - RFC-3161-Zeitstempel nach 4.9 (4.9-E1): Liste `archiv.tsa.dienste` (zuerst der Hauptdienst TSA der Swiss Government PKI, dann der zweite Dienst, z. B. FreeTSA), beide parallel, an beide nur der Hash; Anfrage mit Nonce und `certReq`, Antwort prüfen (Status, Nonce, Imprint, Policy-OID und ausstellende CA aus der Allowlist je Dienst, beim Hauptdienst auf CA-Ebene nach der Schweizer Trusted List); je Dienst `.tsr`, Zertifikatskette und OCSP- oder CRL-Antwort mit derselben Sperre ablegen, Tabelle `manifest_zeitstempel`; kein Stempel → Manifest fehlt (15.8), nur einer → gültig mit Meldung (15.11); Startprüfung in `prod` (Stufen A und B): ohne Dienst kein Start, mit nur einem Dienst Warnung bei jedem Start (Übergang nach Wegfall des Hauptdiensts), Stufenwerte unter `deploy/infomaniak/` mit zwei Diensten (Konfigurationstest); in Stufe P keine Prüfung; Endpunkte nur in der Konfiguration, nicht im Code
  - `backend/pom.xml`: `bcpkix-jdk18on` vom Scope `test` in den Compile-Scope (TSP-Anfrage und -Prüfung); WireMock-Stubs für beide Dienste
  - Ablage unter `manifest/` mit Object Lock im Modus COMPLIANCE (6.11, Option B) bis zum spätesten `aufbewahren_bis` der enthaltenen Dateien, Tabelle `manifest`
  - verpasste Läufe in Datumsreihenfolge nachholen, höchstens ein Manifest pro Datum; Tests für 29.03.2026 und 25.10.2026; Alarm bei fehlendem Manifest
  - Port `ManifestUeberwachung` (5.6) umsetzen, ersetzt den Platzhalter `ManifestUeberwachungNichtAngebunden`; Szenario `@req-16.5` für ein fehlendes Manifest – umgesetzt (PR #114): `ManifestBefunde`, Frist `archiv.ueberwachung.manifest-frist`, die direkte Mail aus `ManifestErzeugen.nachLetztemFehlversuch` entfällt
  - Prüfwerkzeug `archiv manifest pruefen <datum>` (15.12): Signatur, Kette inkl. Erneuerung, jeder Zeitstempel; Lücke in der Kette ist ein Fehler, fehlender Stempel eines Dienstes und `genTime` später als `archiv.manifest.verspaetung-schwelle` nach `bis` werden gemeldet
  - Prüflauf: SHA-256 mit dem Manifest-Inhalt vergleichen (Rest von 5.3, Req 15.5): der `PRUEFLAUF` vergleicht die Prüfsumme jeder Datei zusätzlich mit ihrem Eintrag im Tages-Manifest; fehlt der Eintrag oder weicht er ab, ist das eine Abweichung im Bericht
  - Manifest ohne Dateinamen (Req 3.12, Aufgabe 4.10): Szenario `@req-3.12` prüft, dass der Dateiname eines Testdokuments im Manifest fehlt (ergänzt 13.11.2026, entschieden von Fabian)
  - Reviews zu PR #87: Changeset `014-manifest` (bis zum Rebase vom 13.11.2026 `013-manifest`) ohne Index auf `zugriff_protokoll` (kommt mit 5.1, PR #88; 5.2 erst nach #88 mergen), Prüfdaten aktuell zu `genTime`, ausgetragene Dienste mit eigener Allowlist, öffentlicher Schlüssel nie aus dem privaten, Wiederverwenden nur gleicher Manifeste, Sperre mindestens bis zur Protokollfrist nach 10.9, weitere Versionen unter `manifest/` als Fehler, PDF/A als dritte Variante, Smoke-Test `TsaSmokeIT` gegen die echten Dienste vor dem Merge; Nachtrag: «festgehalten» aus der Datenbank (auch am jüngsten Tag), Höchstalter der Prüfdaten je Dienst, älteste Version unter `manifest/` eindeutig oder Abbruch (design.md «Manifest», «Umsetzung»)
  - 3.12 in den Anforderungen ergänzt 13.11.2026
  - _Anforderungen: 3.12, 6.11, 15.1–15.4, 15.5, 15.6, 15.7, 15.8, 15.11, 15.12, 16.7, 16.9_
  - _Abhängig von: 4.9, 4.10_ (4.10 ergänzt 13.11.2026)

- [ ] 5.3 Monatlicher Prüflauf [Backend]
  - Auftrag `PRUEFLAUF`: alle Dateien über die gespeicherte Versions-ID stromweise hashen, mit Datenbank und Manifest vergleichen (massgeblich SHA-256); fremde Versionen und Löschmarken unter `archiv/` als Abweichung
  - Sperren unbefristeter Dokumente und von Manifesten, die in weniger als 12 Monaten ablaufen, um 10 Jahre verlängern, mit derselben Kappung wie `Sperrfrist` (Höchstfrist der Stufe A): dafür einen öffentlichen Einstieg schaffen, z. B. `Sperrfrist.verlaengert(bisher, jetzt, hoechstfrist)` (heute ist `begrenzt` privat, und `retainUntil`/`manifest` verlängern keine bestehende Sperre); in Stufe P nicht verlängern, nur zählen (Req 16.24); Test: in Stufe A setzt der Prüflauf keine Sperre über 1 Tag, in Stufe P keine (4.9-E5)
  - Bericht und E-Mail an den Inhaber bei Abweichung (nur IDs)
  - _Anforderungen: 6.9, 15.5, 15.9, 15.10, 16.24_
  - Teil erledigt (PR #89): `PRUEFLAUF` mit Vergleich gegen die Datenbank, fremden Versionen und Löschmarken unter `archiv/` und `manifest/`, Sperrpflege (Stufe A gekappt auf 1 Tag, Manifeste im bisherigen Modus), Fehler je Datei als `PRUEFFEHLER`, täglicher Takt mit Nachholen (ein Lauf je Monat), Bericht und Mail nur mit IDs; Stufe P ist im Handler vorbereitet (nur zählen), als Startwert aber bis 5.5 abgelehnt (design.md, «Prüflauf», Umsetzung 5.3). Offen: der Vergleich mit dem Manifest-Inhalt, er folgt als Unterpunkt von 5.2, sobald es Tages-Manifeste gibt; die Checkbox bleibt bis dahin offen
  - Nachtrag (PR #97, Review zu PR #89): vorübergehende S3-Fehler und Fehler in Folge wiederholen den Teil, `PRUEFFEHLER` erst im letzten Versuch; Abbruch an der Zeitgrenze auch ohne Interrupt-Flag erkannt; Wettlauf mit `ARCHIVIEREN` behoben (erst Status, dann bekannte Versionen); kein leerer `version-id-marker`; Tests für Löschmarke unter `manifest/`, `SPERRE_FEHLT` in Stufe B und Fortsetzung über mehrere Seiten
  - Nachtrag (PR #98, Review zu PR #97): Abbruch mit `UnsichereFortsetzung`, wenn eine abgeschnittene Versionsliste `NextKeyMarker` oder `NextVersionIdMarker` nicht liefert (auch Marke ohne Versions-ID, auch im Dev-Stub), statt Versionen still zu überspringen; im letzten Versuch überspringen nur vorübergehende Fehler in Folge den Rest des Teils, dauerhafte werden einzeln festgehalten; nach dem letzten Fehlversuch E-Mail `prueflaufAbgebrochen` an den Inhaber aus `nachLetztemFehlversuch` (nur IDs, nicht in Stufe P); `RequestTimeout` und 408 gelten als vorübergehend; Tag `@req-15.9` für die Löschmarke unter `manifest/`
  - Nachtrag (PR #101, Review zu PR #98): Die Abbruch-Mail nennt zusätzlich die Zähler und die ersten 50 schon festgehaltenen Abweichungen (nur IDs), weil der abgebrochene Lauf nie abschliesst, und je Phase, was ab der Marke ungeprüft bleibt (Req 15.5, 16.7); Req 16.5 um den Anlass «Prüflauf-Teil endgültig gescheitert» ergänzt; Abwechseln der Fehlerarten im letzten Versuch begründet; Hinweis auf einen Mailweg nach 5.6 in design.md. Die Checkbox bleibt offen, bis der Manifest-Vergleich aus 5.2 umgesetzt ist

- [ ] 5.4 Produktivbetrieb aufsetzen [Infra] [Fabian]
  - Produktion auf Stufe B (VPS aus 5.11) mit `deploy/infomaniak/compose.yml` und dem Deploy-Workflow aus 1.9 unter `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch`; Keycloak-Realm produktiv mit TOTP
  - Bucket `urinet-archiv-urinet` im Public-Cloud-Projekt produktiv, mit der Bucket Policy aus 1.3 (Deny auf `s3:BypassGovernanceRetention`)
  - [Fabian] App-Schlüssel (zwei GitHub-Secrets `s3_app_zugang_id` und `s3_app_zugang_geheimnis`; hat technisch das Bypass-Recht, Option B, ADR-007, Nachtrag 09.11.2026) als GitHub-Secret hinterlegen (auf dem Server nur unter `/run/secrets/`); Inhaber-Zugang zum Public-Cloud-Projekt des Object Storage (Zugangsdaten, mit denen Schlüssel ausgestellt und Korrekturen ausgeführt werden) nur offline ablegen (Notfallunterlagen, 5.10), nie auf dem Server oder in GitHub-Secrets
  - produktives Archivieren erst, wenn der Zeitstempeldienst angebunden ist (15.3)
  - _Anforderungen: 6.5, 6.10, 15.3, 16.1, 16.7, 16.9, 16.10, 16.17_
  - _Abhängig von: 1.9, 4.9, 5.11_

- [ ] 5.5 Sicherung und Wiederherstellung [Infra] [Backend] [Fabian]
  - im Repository (`deploy/backup/`): täglicher Export von Archiv-Datenbank, Keycloak-Datenbank und Realm, Bucket (Original, Arbeitsfassung, PDF/A, Manifeste; Zuwachs seit dem letzten Export) und Manifest-Schlüssel; je Lauf ein Inventar mit Sperrmodus und `retainUntil` aller Versionen, nicht nur der neuen; der Server verschlüsselt mit `age` an zwei Empfänger (Sicherungsschlüssel offline, Probe-Schlüssel) und hält die SHA-256 jedes Exports für `probe-gate.sh` fest; dieselbe Liste gelangt ins Feld `sicherung` des nächsten Manifests (Weg im PR festlegen, design.md «Manifest»)
  - [Fabian] [Infra] im Infra-Repo der VPS: Sicherungsworkflow nach dem Muster `maintenance` holt die verschlüsselten Daten ab und schreibt sie mit restic auf das Hostpoint-Webhosting aus 4.9 (4.9-E2, Konto nach Vorbedingung 5): SSH-Schlüssel mit Forced Command `rclone serve restic --stdio --append-only <pfad>` (rclone für das Betriebssystem des Webhostings, ausserhalb des Webroots), restic-Schlüssel nur als GitHub-Secret dieses Workflows und in den Notfallunterlagen; Zuwächse des Buckets mit eigenem Tag, im täglichen Workflow kein `forget` und kein `prune`; quartalsweise von Hand `forget --keep-daily 30 --keep-monthly 12 --keep-yearly 10 --keep-tag <tag der Zuwächse> --prune` mit dem zweiten Schlüssel aus den Notfallunterlagen; Test vor dem Echtbetrieb: `backup`, `check` und `restore` gelingen über den Schlüssel, der nur anhängen kann, `forget` scheitert; `backup-gate.sh` und Restore-Probe um PostgreSQL und den Bucket erweitern (`archiv_arbeit` ausgenommen)
  - automatisierte Restore-Probe nach 4.9 (4.9-E3 bis 4.9-E5, Req 16.23, 16.24, 16.27) auf der Stufe-B-VPS: Workflow im maintenance-Repo (Cron `23 4 2 * *`, UTC, nach dem täglichen Abholen; Gruppe `restic`; zusätzlich `workflow_dispatch` für die Auslöser aus 16.3); Laufzeit beim ersten Lauf messen und als Zeitgrenze setzen; die Probe auf Stufe B setzt die neue VPS aus 5.11 voraus (dort entsteht der Probe-Schlüssel), ihr erster Lauf ist der in 5.11 vor dem Umstellen
  - Schlüsselweg (4.9-E4): Probe-Schlüssel auf der VPS erzeugen (privater Teil nur root-lesbar, in keinen Container eingehängt; Ausnahme zur ADR-008-Folge, ADR-008 Nachtrag 12.11.2026), öffentlicher Teil unter `docs/`; [Infra] im Infra-Repo eigener SSH-Schlüssel mit Forced Command `bin/probe-gate.sh` (Befehle `probe archiv` und `probe aufraeumen`; nimmt nur Chiffrat mit festgehaltener SHA-256 an) und `probe-entschluesseln`, das `probe-gate.sh` über eine sudo-Regel nur für dieses Programm als root aufruft und das den Klartext nur an die Wiederherstellung streamt; `probe-gate.sh` stoppt den Worker vor der Probe und startet ihn mit `trap` bei jedem Ende wieder; `backup-gate.sh` bleibt nur lesend; Wechsel des Probe-Schlüssels heisst neuer Vollexport
  - `deploy/probe/compose.yml` (Projekt `archiv-probe`, ohne Traefik-Labels, nur ein Netz mit `internal: true`, Keycloak mit eigenem Hostnamen und Issuer, API mit passender `auth-server-url`) mit `archiv.stufe=P`, Präfix `urinet-archiv-p-`, Höchstfristen 1 Tag; Limits je Dienst zusammen höchstens der Anteil des Workers (1'024 MiB), geprüft mit `.github/scripts/pruefe-speicherbudget.py` (Erweiterung aus 5.11); ohne KI-Token und SMTP-Zugang; Wegwerf-Passwörter je Lauf auf tmpfs, auch für den Testbenutzer (über einen temporären Admin der Probe, `kc.sh bootstrap-admin`); Dumps nicht als Superuser einspielen; Wiederherstellung vor dem Start von API und Keycloak der Probe; Bucket aus der ganzen Kette der Zuwächse aufbauen und Anzahl Objekte = Einträge in der Datenbank prüfen; Sperren neu setzen (Ist-Sperre aus dem Inventar, gekappt auf den folgenden Tag) und prüfen (Ist ≥ Soll-Sperre aus der wiederhergestellten DB, Modus gleich, aktive Sperre, Löschen ohne Bypass scheitert in einer Stichprobe, Abweichungen im Status); danach `compose down -v`
    - Pflichtliste der Secrets (Nachtrag PR #93): `%prod.archiv.secrets.pflicht` verlangt seit 3.2 `infomaniak_ai_token`. Die Probe-Compose übersteuert die Liste ohne `infomaniak_ai_token` (z. B. `ARCHIV_SECRETS_PFLICHT`), weil P weder KI noch das Token hat (design.md «Was Stufe P abschaltet»); sonst bricht der Start in P ab. Ein Konfigurationstest prüft, dass P ohne Token startet
  - [Infra] Aufräumen: Workflow `probe aufraeumen` täglich (Cron `53 4 * * *`, UTC), leert den Probe-Bucket ohne Bypass-Header nur, wenn das Ende des letzten Probe-Laufs mindestens 25 h zurückliegt; der nächste Probe-Lauf prüft zuerst, dass der Probe-Bucket leer ist
  - [Backend] Stufe P im Code: `Stufe` und `Stufenregel` um `P` erweitern, Startabbruch in P bei fehlendem Präfix `urinet-archiv-p-`, fehlender oder über 1 Tag liegender Höchstfrist oder Modus von `manifest/` ungleich COMPLIANCE, in B beim Präfix `urinet-archiv-p-`; `MandantCode` lehnt auch Codes mit `p-` am Anfang ab; in P kein `MANIFEST` im Zeitplan und beim Start, TSA-Startprüfung ausgesetzt, Worker nur `PRUEFLAUF`, KI-Port darf auf den Stub fallen, kein SMTP; Konfigurationstest für Stufe P; `DeployStufeTest` weist `P` in `deploy/infomaniak/compose.yml` ab und prüft `deploy/probe/compose.yml` nur mit `P`
    - Mit 5.3 vorbereitet (PR #89): Enum `Stufe.P` (beim Start über `Stufe.aus` weiter abgelehnt), die P-Regeln in `Stufenregel` (Präfix, Höchstfristen, COMPLIANCE), `MandantCode` lehnt `p-` ab, `Sperrpflege` mit `NUR_ZAEHLEN`, der `PRUEFLAUF` entscheidet nach der konfigurierten Stufe und zählt in P nur. Offen hier: `Stufe.aus` für `P` freigeben, kein `MANIFEST`, Worker nur `PRUEFLAUF`, ohne SMTP und KI, TSA-Startprüfung, Konfigurationstest für P und `DeployStufeTest` für `deploy/probe/compose.yml`
  - [Backend] Wiederherstellungswerkzeug: setzt die Sperren aus dem Inventar, kappt nur bei einem Ziel-Bucket mit Präfix `urinet-archiv-p-` und bricht ab, wenn bei einem anderen Präfix eine Kappung verlangt wird; JUnit-Test für beide Fälle (Req 16.27)
  - [Fabian] Probe-Projekt (eigenes Public-Cloud-Projekt) und dessen S3-Schlüssel anlegen, Schlüssel als GitHub-Secret des App-Repos
  - [Backend] Port `SicherungUeberwachung` (5.6) umsetzen, ersetzt den Platzhalter `SicherungUeberwachungNichtAngebunden`; Szenario `@req-16.5` für eine fehlgeschlagene oder zu alte Sicherung
  - `docs/wiederherstellung.md`, einmal vollständig in einer Testumgebung durchgespielt (Nachweis: manuell [Fabian]); darin: nur Snapshots einspielen, deren SHA-256 ein Manifest unter `sicherung` belegt; Sperren nach der Wiederherstellung aus dem Inventar ohne Kappung neu setzen; Ausdünnen von Hand; Rückfall cloudscale.ch
  - solange Stufe A läuft: nur Funktionstest mit Testdaten und eigenem Testschlüsselpaar auf der VPS der Stufe A (16.19; der Probe-Schlüssel entsteht erst auf Stufe B), keine Aufbewahrungspflicht; Aufbewahrung nach 16.2 erst auf Stufe B (5.11)
  - _Anforderungen: 6.11, 16.2, 16.3, 16.7, 16.8, 16.19, 16.23, 16.24, 16.27_
  - _Abhängig von: 4.9, 5.3; für die Restore-Probe auf Stufe B die neue VPS aus 5.11_

- [ ] 5.6 Überwachung und Benachrichtigung [Backend] [Infra]
  - Health: öffentlich nur ein schlanker Pfad über Traefik; Detail-Health und Micrometer-Kennzahlen (offene Aufträge, Fehler, Eingangskorb, Korrekturquote je Feld) nur intern
  - E-Mail an den Inhaber bei einem Dokument > 1 h in `VERARBEITUNG`, `FEHLER` oder `ARCHIVIERUNG_FEHLER`, bei fehlgeschlagener Sicherung und fehlendem Manifest; Inhalte nur IDs
  - Teil Backend umgesetzt (PR #91): Überwachung mit Tabelle `alarm`, Alarm-Mail, Kennzahlen; Anbindung Manifest (5.2) mit PR #114, Folge zu den Reviews #113/#114: ein neuer Auftrag nach einem Fehlschlag hebt den Befund nicht auf (`AuftragStand.letzterFehler`), genau eine Mail (design.md «Überwachung»); offen: Korrekturquote (mit 3.3), Anbindung Sicherung (5.5), Teil Infra
  - Ergänzung 14.11.2026 (Entscheid Fabian): SMTP-Zugang und Empfänger in prod umgesetzt (PR «Betrieb: Mail-Versand und Alarm-Empfänger in prod»): Versand über Hostpoint mit `archiv@urinet.ch`, Passwort aus dem GitHub-Secret `ARCHIV_SMTP_PASSWORT` als Secret-Datei `archiv_smtp_passwort`, Empfänger aus der GitHub-Variable `ARCHIV_ALARM_EMPFAENGER_URINET`; ohne beide Werte startet prod weiter und loggt nur (design.md «Umsetzung 5.6»). Aktiv erst, wenn Fabian die zwei Werte gesetzt hat (README «Benachrichtigungen per Mail»)
  - [Infra] Restore-Probe (Req 16.25): Benachrichtigung des maintenance-Workflows (`notify`) bei rotem Lauf; Alarm beim Kontingent des Hostpoint-Webhostings (ein gestohlener Workflow-Schlüssel kann den Webspace durch Anhängen füllen), Schwelle im PR
  - _Anforderungen: 16.4, 16.5, 16.7, 16.25_

- [ ] 5.7 Verfahrensbeschreibung «Erfassen und Archivieren» [Fabian]
  - `docs/verfahrensbeschreibung.md` (≤ 2 Seiten, neu anzulegen): Eingänge, Scan-Regeln (ganze Seite, lesbar, ≥ 300 dpi), Prüfung im Eingangskorb, Sperre, Manifest, Zugriffe (Frist nach Req 10.9, Zweck Nachweis nach GeBüV und Datensicherheit, keine Auswertung des Verhaltens, Einsicht nur Inhaber nach A13; vorgemerkt mit 4.9-E6), Umgang mit Papier (Konzept-Entscheid E5), Notfallablauf für den Treuhänder und Ort der Notfallunterlagen (ohne Zugangsdaten); vom Treuhänder gegenlesen lassen (K3)
  - jede freigegebene Fassung mit «gültig ab» als PDF/A im Archiv ablegen
  - _Anforderungen: 10.9, 16.6, 16.12_

- [ ] 5.8 Übernahme Belege 2026 und Abnahme [Fabian] [QA]
  - Voraussetzung: Wechsel auf Stufe B abgeschlossen (5.11, 16.17), Zeitstempeldienst angebunden (15.3), Sicherung und Restore-Probe grün; Realitätsprüfung nach Auflage A2 aus Gate 0 erneut gelaufen und Ziel aus 2.9 erreicht (`docs/umsetzungsplan.md`, Abschnitt 6)
  - CRM-Kontakte per CSV importieren, Drive-Altbestand importieren und im Eingangskorb bestätigen, Papierbelege 2026 fotografieren, Generatoren umstellen
  - Abnahmetest: Stichprobe 20 Dokumente auffindbar, Schlussbericht des Drive-Imports geht auf, Prüflauf ohne Abweichung, Mandantentrennung mit Testmandant `test` geprüft
  - **Gate Phase 1:** alle Belege 2026 im Archiv, Prüflauf grün
  - _Anforderungen: 1.4, 12.7, 13.6, 15.3, 15.5, 16.3, 16.17_
  - _Abhängig von: 2.9, 5.1–5.7, 5.9, 5.10, 5.11_

- [ ] 5.9 Anbindung an das Monitoring der VPS [Infra]
  - interne Kennzahlen und Detail-Health an das Monitoring der VPS (Stufe B aus 5.11) anbinden; Alarme für Erreichbarkeit, Health und Fehleraufträge; Eintrag im Infra-Repo der VPS
  - Regel «Restore-Probe archiv rot oder letzter grüner Lauf älter als 35 Tage» (Req 16.25) im Monitoring; das Status-Artefakt der Probe enthält nur Zähler und IDs
  - Messpunkt `JdbcKiBeispiel.kandidaten`: Zeilenzahl `ki_beispiel` je Mandant überwachen; sobald sie 1'000 übersteigt, auch die Abfragedauer messen (Details in design.md, «Offen»)
  - _Anforderungen: 16.4, 16.5, 16.25_
  - _Abhängig von: 5.11_

- [ ] 5.10 Notfallunterlagen [Fabian]
  - versiegelt zusammenstellen: Keycloak-Admin-Zugang, TOTP-Backup-Codes, restic-Schlüssel, privater Sicherungsschlüssel (Export-Verschlüsselung), S3-Zugang des Inhabers (Inhaber-Zugang, 6.10), öffentlicher Manifest-Schlüssel, zweiter SSH-Schlüssel für das Ausdünnen der Zweitkopie auf dem Hostpoint-Webhosting (16.2); Ablage ausserhalb des Systems am Ort aus der Verfahrensbeschreibung, nie im Repository
  - nicht hinein gehört der Probe-Schlüssel: Er ist ersetzbar (neuer Empfänger, danach neuer Vollexport, 16.23)
  - Vollständigkeit einmal mit dem Notfallablauf aus 5.7 prüfen
  - _Anforderungen: 6.10, 16.12_

- [ ] 5.11 Wechsel auf Stufe B [Infra] [Fabian]
  - Zeitpunkt: zu Beginn von Welle 5, vor 5.4 und vor der Übernahme 5.8; vorgezogen (Ausnahme von «Aufgaben anderer Wellen nicht vorziehen»), wenn 1.10 abbricht oder 16.18 später eintritt
  - [Fabian] [Infra] vor der Bestellung (4.9-E7): Lastskripte aus 2.4, 2.6 und 4.5 einige Stunden auf einer stundenweise abgerechneten Infomaniak-Public-Cloud-Instanz ähnlicher Grösse (4 vCPU, 4 GB) mit synthetischen Daten laufen lassen; Ergebnis im Messbericht
  - [Fabian] eigene Infomaniak VPS Lite 4 vCPU / 4 GB / 80 GB (K5, CHF 9.00/Mt. bei Jahresbindung, im Voraus) erst zu Beginn dieser Aufgabe bestellen; Zugang des Deploy-Workflows als GitHub-Secret; Cloud-Firewall im Manager für 80/443 öffnen; DNS für `archiv.apps.urinet.ch` und `archiv-auth.apps.urinet.ch` als eigene A-Records auf die neue VPS (der Wildcard `*.apps.urinet.ch` zeigt auf Stufe A)
  - [Infra] neue VPS nach dem Muster des Betriebs einrichten (Traefik mit Let's Encrypt, Deploy-Befehl für das SSH-Bundle, Compose-Policy, Monitoring); Einträge im Infra-Repo der VPS
  - [Infra] getrennter Worker erst nach der Migration (ergänzt 13.11.2026, Review PR #113): `archiv-worker` bekommt `depends_on: archiv-api` mit `condition: service_healthy`, oder das Signal `SchemaBereitschaft` des Workers wird zusätzlich an den Liquibase-Stand jedes Mandanten-Schemas gebunden (keine offenen Changesets in `databasechangelog`); Grund: der Worker migriert nicht, und seine Hibernate-Prüfung erkennt Spalten nicht, die nur JDBC-Adapter lesen (design.md «Mandant», «Start und Migration»)
  - [Infra] Deploy-Ziel im Workflow aus 1.9 umstellen; Stufenwerte für B: `archiv-api` (`archiv.rolle=api`) und `archiv-worker` (`archiv.rolle=worker`) getrennt, beide mit `archiv.stufe=B`, Parallelität und Limits nach den Startwerten in design.md «Stufen der Plattform» (Speicherbudget Stufe B); `.github/scripts/pruefe-speicherbudget.py` erweitern: Budget je Plattform nach dem Zielhost des Deploys, nicht nach `archiv.stufe` (die Messkonfiguration mit `archiv.stufe=A` auf der neuen VPS fällt unter das Budget der Stufe B, Req 16.26), dazu Prüfung von `deploy/probe/compose.yml` gegen den Anteil des Workers; Datasource-Pool des Workers ausdrücklich setzen, Summe der Verbindungen unter `max_connections`; Sperrmodus von `manifest/` COMPLIANCE ohne Abweichung (2.3)
  - Restore-Probe nachts mit angehaltenem Worker (Container gestoppt, damit sein Speicher frei wird); sie zählt zum Speicherbudget der Stufe B; `MemTotal` der VPS und einen Probe-Lauf neben den übrigen Diensten der Stufe B messen, Startwerte bei zu kleiner Reserve kürzen; reicht der Anteil des Workers nicht, gilt das als verfehltes Ziel
  - Testdaten verwerfen: Datenbanken und Realm-Benutzer der Stufe A nicht übernehmen, Stufe B startet leer; Test-Buckets (`urinet-archiv-a-<code>`) frühestens 1 Tag nach dem letzten Upload aufräumen: alle Versionen mit dem App-Schlüssel löschen, ohne Bypass-Header, danach die Buckets selbst (ohne Inhaber-Zugang; entschieden von Fabian am 09.11.2026); danach Archiv-Dienste auf Stufe A entfernen und die Einträge im Infra-Repo der bestehenden VPS zurückbauen; im Wechselprotokoll [Fabian] bestätigen, welche Testdaten auf Stufe A geladen wurden (16.15)
  - Sicherung und Restore-Probe aus 5.5 auf Stufe B einrichten bzw. umstellen, Aufbewahrung nach 16.2 ab hier; ist 5.5 beim Wechsel noch offen, richtet 5.5 sie direkt auf Stufe B ein
  - Leistungsmessung auf der neuen VPS vor dem Umstellen (Req 16.26): Dienste und Limits der Stufe B, aber `archiv.stufe=A` mit eigenem Mess-Mandanten `last` (Bucket `urinet-archiv-a-last`, nicht ein Test-Bucket der laufenden Stufe A), nur synthetische Daten, nie im produktiven Mandanten; Messbedingungen aus Req 3.2 (`upload.py --anzahl 200 --parallel 4 --groesse 200000` und `--anzahl 50 --parallel 2 --groesse 10000000`, Worker läuft mit), 5.5 (`verarbeitung_foto.py` mit 12-MP-JPEG `--anzahl 50`, zusätzlich HEIC und neben einem 20-seitigen Scan), 14.3 (`suche.py --modus volltext --anzahl 500 --parallel 4` je Rolle, mit und ohne OCR-Lauf, 50'000 Dokumente) und Spec 02, Req 2.6 (`suche.py --modus hybrid --grenze 2 --anzahl 500 --parallel 4` unter denselben Rollen- und OCR-Bedingungen); überall `--aufwaermen 20`; Messpunkt Server bleibt (Dauer serverseitig, das Lastskript liefert die obere Schranke); Messbericht im PR verlinken; danach mit denselben Messdaten die Restore-Probe nach 16.3 laufen lassen (Stände mit Tag `messung`, beim nächsten Ausdünnen von Hand entfernt), dann Messdaten, Mandant `last` und Bucket `urinet-archiv-a-last` verwerfen (frühestens 1 Tag nach dem letzten Upload, wie die Test-Buckets) und Stufe B leer starten; bei vorgezogenem Wechsel die Leistungsmessung nachholen, sobald 2.4, 2.6 und 4.5 fertig sind, spätestens vor 5.4: auf derselben VPS als eigenes Compose-Projekt (z. B. `archiv-last`, über den Deploy-Workflow mit eigenem Projektnamen) mit `archiv.stufe=A`, Mandant `last` und Bucket `urinet-archiv-a-last`, nur synthetische Daten; während der Messung ruht der Archiv-Stack der Stufe B (vor 5.8 liegen dort keine echten Belege); danach Projekt und Bucket verwerfen
  - Bildweg mitmessen (Review PR #93): ein mehrseitiges farbiges Foto-Dokument (3 Seiten) über den Bildweg mit `archiv.ki.render-zeitgrenze`; Seitenbild ja/nein und Dauer des Renderns in den Messbericht (design.md «Umsetzung (Aufgabe 3.2)», «Bildweg als PPM»)
  - Mess-Mandant `last` mit synthetischen Dateinamen füllen und `suche.py` mit Suchbegriffen laufen lassen, die auch nur Dateinamen treffen (Req 14.8, Aufgabe 4.10; ergänzt 13.11.2026, entschieden von Fabian)
  - verfehlt die Messung ein Ziel: VPS an Ort auf 4 vCPU / 8 GB / 160 GB aufrüsten (CHF 18.00/Mt., nicht umkehrbar), Limits nachführen, neu messen; Wechsel auf VPS Cloud, wenn auch 4/8 ein Ziel verfehlt, die Vektoren aus Spec 02/04 mehr Speicher brauchen, Spec 03 (öffentliche Schnittstelle, zweiter Mandant) die Last erhöht oder Fabian SLA, Snapshots oder Support will (design.md «Stufen der Plattform»)
  - _Anforderungen: 3.2, 5.5, 14.3, 16.1, 16.2, 16.3, 16.10, 16.15, 16.16, 16.17, 16.18, 16.19, 16.26_
  - _Abhängig von: 1.9, 2.4, 2.6, 4.5, 4.9 (bei vorgezogenem Wechsel nur der Entscheid K5 aus 4.9)_

## Optionen
Optionale Aufgaben tragen die Nummern `O.<n>` über alle Specs (O.1–O.3 in Spec 02, O.4–O.6 in Spec 04, O.7–O.8 in Spec 01).

- [ ]* O.7 Übersicht und Ablauf der API-Tokens vor Stufe B [Backend] [Frontend]
  - Herkunft: Review von PR #37 (Aufgabe 1.5); Grundlage: Tokens laufen nicht ab, sie gelten bis zum Widerruf (entschieden von Fabian am 10.11.2026)
  - ungenutzte Tokens nach `zuletzt` in der Token-Verwaltung anzeigen
  - optionales `gueltig_bis` je Token, vor dem Wechsel auf Stufe B (5.11); ohne Wert gilt das Token weiter bis zum Widerruf
  - Spec 03 ergänzt: Die Spalte `gueltig_bis` legt Spec 03, Aufgabe 11.7 für Webseiten-Schlüssel nach einer Rotation an (Spec 03, Req 12.4); diese Option nutzt sie für `generator` und `import` mit
  - _Anforderungen: 2.5_

- [ ]* O.8 Anmeldung mit Google [Infra] [Backend] [Fabian]
  - Herkunft: Auftrag Fabian vom 12.11.2026; Entscheide [Fabian]: Stufe (2.25), Verknüpfung durch Benutzer oder Admin (2.27), Verzicht auf TOTP (2.28, Vorschlag: nein), Aufbewahrung der Keycloak-Ereignisse
  - Google-OAuth-Client (Redirect-URI `https://archiv-auth.apps.urinet.ch/realms/urinet-archiv/broker/google/endpoint`) anlegen und Geheimnis als GitHub-Secret `GOOGLE_CLIENT_SECRET` setzen [Fabian]
  - Identity Provider, Flows und Ereignisse im Realm-Export, `start.sh` setzt den Platzhalter, Pflichtprüfung des Secrets im Deploy-Workflow, `RealmExportTest` und `RealmExportKeycloakTest` angepasst; bei Stufe A einmalige Einrichtung im bestehenden Realm per `kcadm.sh` im Wegwerf-Container
  - Szenarien gegen einen `oidc`-Test-IdP: unbekanntes Konto abgelehnt, E-Mail allein verknüpft nicht, verknüpftes Konto angemeldet mit TOTP, Rollen und `mandant` nur aus Keycloak, Lösen der Verknüpfung im Ereignisprotokoll und Sitzungen beendet
  - ab Stufe B: Google als Empfänger der Anmeldedaten im Verzeichnis der Bearbeitungen und im Datenschutzhinweis [Fabian]
  - _Anforderungen: 2.25–2.29_
