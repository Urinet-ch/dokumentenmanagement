# Design Document — 01 Archiv-Kern

## Overview
Ein Quarkus-Dienst (Java 25) in zwei Laufarten: **API** (REST für Web-App, Handy-App, Generatoren
und Drive-Import; liefert die Angular-PWA über Quinoa im selben Artefakt aus) und **Worker**
(Verarbeitung aus der Auftragsschlange). Er läuft hinter Traefik, zuerst auf der bestehenden Infomaniak-VPS
(Stufe A, nur Testdaten), ab dem Wechsel vor der Übernahme in Welle 5 auf einer eigenen Infomaniak-VPS (Stufe B).
Daten liegen pro Mandant in einem eigenen PostgreSQL-Schema und einem eigenen Bucket im Infomaniak
Object Storage (eigenes Public-Cloud-Projekt). Die PWA dient für Erfassung, Eingangskorb, Suche,
Import und Verwaltung.

Nummern: «Req x.y» verweist auf `requirements.md`, «A1–A14» auf die Rechtematrix (Req 2.6; A15–A19 ergänzt Spec 04, Req 12.1),
«P1–P19» auf die Prüfentscheide in `docs/umsetzungsplan.md` (P20–P22 ergänzt Spec 04), «Konzept-Entscheid E1–E5» auf das Konzept.

Leitentscheide (ADRs in `docs/adr/`):
- ADR-001 Eigenbau statt Paperless-ngx (Konzept Variante B, Konzept-Entscheid E2).
- ADR-002 Schema pro Mandant statt Mandantenspalte; Schemaänderungen nur über Liquibase mit den Changelogs `db/public` (gemeinsame Tabellen) und `db/mandant` (je Mandant), Hibernate nur `validate` (Req 1.5, 1.7).
- ADR-003 Auftragsschlange in PostgreSQL statt Message-Broker.
- ADR-004 Zweistufige Ablage: `eingang/` ohne Sperre, `archiv/` mit Object Lock GOVERNANCE, gesperrt nur bei Arten mit `gesetzliche_pflicht` (P12).
- ADR-005 Handy zuerst als PWA (Konzept-Entscheid E3); keine native Hülle (D8 gestrichen, entschieden von Fabian am 09.11.2026: nur Web-App).
- ADR-006 KI nur für Vorschläge; Bestätigung durch Menschen; Generatoren ohne KI.
- ADR-007 Unveränderbarkeit (P2): Dokumente unter `archiv/` in GOVERNANCE statt COMPLIANCE, damit Fehlarchivierungen und Löschbegehren nach revDSG lösbar bleiben; Manifeste unter `manifest/` in COMPLIANCE (Req 6.11). Jedes Manifest trägt einen RFC-3161-Zeitstempel eines Schweizer Anbieters (Pflicht). Nach Spike 0.2 gilt Option B (entschieden 09.11.2026; ADR-007, Nachtrag 09.11.2026): Einen App-Schlüssel ohne `s3:BypassGovernanceRetention` gibt es im selben Projekt nicht; die Anwendung setzt den Bypass-Header nie und verkürzt keine Sperre (Req 6.5). GOVERNANCE schützt die Dokumente damit gegen Fehler der App und Versehen. Eine Manipulation mit gestohlenem App-Schlüssel verhindert es nicht; sie fällt über das gesperrte, zeitgestempelte Manifest und den Prüflauf auf (Req 15.5), und bereits gesicherte Dateien lassen sich aus der Zweitkopie wiederherstellen (ADR-008). Der Inhaber-Zugang (Schlüssel ausstellen, Korrekturen) liegt nur offline (Req 6.10).
- ADR-008 Sicherung (P4): Zweitkopie von Datenbanken und Bucket bei einem zweiten Schweizer Anbieter über den zentralen Sicherungs-Workflow (GitHub Actions, maintenance-Muster). Der Server verschlüsselt vorher asymmetrisch, der Runner sieht nur Chiffrat (Req 16.7, 16.8).
- ADR-009 Rollen eng nach Rechtematrix (P3, Req 2.6). Unsichtbare Dokumente antworten mit 404 vor 403, damit die Antwort nicht verrät, dass ein Dokument existiert.
- ADR-010 Plattform (P1, geändert 08.11.2026): Infomaniak-VPS mit Traefik, Deploy nur über GitHub Actions (SSH-Bundle, Health-Prüfung, Rollback), in zwei Stufen: Stufe A auf der bestehenden VPS (nur Test- und anonymisierte Daten, Speicherbudget, ohne Leistungsziele), Stufe B auf einer eigenen, grösseren VPS spätestens vor echten Belegen (Req 16.15–16.19). Keycloak bleibt (P5).
- ADR-011 Hexagonale Struktur je Fachbereich (`domain`, `application`, `adapter`) mit Driven Ports und Dev-Stubs für Ablage, KI, OCR und Integration, durch ArchUnit erzwungen (Req 16.13).
- ADR-012 Lokales Sprachmodell über LM Studio nur im Profil `lokal` auf dem Entwicklerrechner, nur an `localhost`; nie für Gate 0, Golden-Lauf, Abnahmen, CI oder Betrieb; Startprüfung der KI-Basis-URL (P19, Req 16.20–16.22).

## Architecture

```mermaid
flowchart TB
  subgraph Clients
    PWA["Angular PWA<br/>Erfassen · Eingangskorb · Suche · Import"]
    GEN["Generatoren<br/>rechnung_gen.py · urinet_pdf.py"]
    CLI["Drive-Import CLI"]
  end
  subgraph GH["GitHub Actions"]
    DEP["Deploy-Workflow<br/>Images · Bundle · Health-Prüfung"]
    SIC["Sicherungs-Workflow<br/>maintenance-Muster · restic"]
  end
  subgraph VPS["Infomaniak-VPS, Schweiz<br/>Stufe A bestehend · Stufe B eigene"]
    TR["Traefik<br/>TLS · X-Real-Ip"]
    KC["Keycloak<br/>archiv-auth.apps.urinet.ch"]
    API["Quarkus API + PWA über Quinoa<br/>archiv.apps.urinet.ch"]
    WRK["Quarkus Worker<br/>OCRmyPDF · Tika · ZXing"]
    PG[("PostgreSQL<br/>public · Schema je Mandant · Keycloak-DB")]
    EXP["Sicherungs-Export<br/>asymmetrisch verschlüsselt"]
  end
  subgraph PC["Infomaniak Public Cloud, eigenes Projekt"]
    S3[("Object Storage<br/>Bucket je Mandant · Object Lock · Versionierung")]
  end
  AI["Infomaniak AI Services<br/>gemma-4-31B-it (Erfassung) · Mistral-Small-4 (Chat)"]
  TSA["Zeitstempeldienst Schweiz<br/>RFC 3161 · Anbieter offen"]
  B2[("Zweitkopie<br/>zweiter Schweizer Anbieter · offen")]
  PWA -->|HTTPS| TR
  GEN -->|"API-Token generator"| TR
  CLI -->|"API-Token import"| TR
  TR --> API
  TR --> KC
  PWA -.->|OIDC PKCE| KC
  API --> PG
  API --> S3
  WRK --> PG
  WRK --> S3
  WRK --> AI
  WRK --> TSA
  KC --> PG
  PG --> EXP
  S3 --> EXP
  DEP -->|SSH-Bundle| TR
  SIC -->|"holt Chiffrat per SSH"| EXP
  SIC -->|restic| B2
```

API und Worker sind dasselbe Artefakt; `archiv.rolle=api|worker` schaltet REST-Endpunkte bzw.
den Auftrags-Poller ein. Aus einem Build entstehen zwei Images: `archiv` (API mit PWA) und
`archiv-worker` (zusätzlich `ocrmypdf`, `tesseract-ocr-deu`, `img2pdf`, `libheif-examples`,
`ghostscript`, `poppler-utils`). In Stufe A laufen beide Rollen in einem Container
(`archiv.rolle=api,worker`, Image `archiv-worker`, weil es die OCR-Werkzeuge enthält).

### Betrieb auf der VPS (Req 16.1, 16.4, 16.10, P1)
- `deploy/infomaniak/compose.yml` mit den Diensten `archiv-api`, `archiv-worker`, `archiv-db` (PostgreSQL 17 mit `pgvector`), `archiv-keycloak` und `archiv-sicherung` (täglicher Export, siehe «Sicherung»); in Stufe A ohne eigenen Dienst `archiv-worker` (siehe «Stufen»). Limits, `cap_drop: ALL`, `no-new-privileges` und numerische uid gemäss Compose-Policy (tech.md); die Fremd-Images werden als `archiv-db:<version>` und `archiv-keycloak:<version>` umgetaggt und im Infra-Repo der VPS eingetragen.
- Traefik-Labels nur an `archiv-api` und `archiv-keycloak`; nur diese beiden hängen zusätzlich am Netz «web» von Traefik. Datenbank, Worker und Sicherung hängen nur im eigenen internen Docker-Netz des Archivs. Die API ist nur über Traefik erreichbar (kein veröffentlichter Port), deshalb ist `X-Real-Ip` vertrauenswürdig.
- Router `archiv.apps.urinet.ch`: alle Pfade ausser `/q/*`, dazu ein eigener Router nur für `/q/health/live` (schlanker Health-Pfad ohne Details). `/q/health/ready` und `/q/metrics` bleiben im internen Netz für das Monitoring der VPS (Anbindung in Welle 5).
- Router `archiv-auth.apps.urinet.ch`: nur `/realms/` und `/resources/`; die Admin-Konsole ist nicht öffentlich.
- Deploy nur über `.github/workflows/deploy.yml`: `mvn verify`, Images bauen, Bundle (Images, `compose.yml`, Secret-Dateien aus GitHub-Secrets) per SSH an den Deploy-Befehl der VPS, danach Health-Prüfung über `/q/health/live` und die interne Readiness; schlägt sie fehl, Rollback auf das Vorgänger-Image. Weil Migrationen rückwärtskompatibel sind (Req 16.11), läuft das Vorgänger-Image auf dem neuen Schema.

### Stufen der Plattform (Req 16.1, 16.15–16.19, P1)
- **Stufe A** (Welle 1 bis zum Wechsel, bestehende VPS, Werte in `tech.md`): dieselbe `compose.yml` und derselbe Deploy-Workflow wie Stufe B; die stufenabhängigen Werte (ein App-Dienst statt zwei, Limits, Speicher-Einstellungen) liegen versioniert unter `deploy/infomaniak/`, nie von Hand auf dem Server.
  - `archiv-api` mit Image `archiv-worker` und `archiv.rolle=api,worker`; `archiv.stufe=A`; `archiv.worker.parallel=1`; OCRmyPDF seitenweise nacheinander (`--jobs 1`).
  - Speicherbudget: Summe der `mem_limit` aller Archiv-Dienste ≤ 700 MiB, `cpus` zusammen ≤ 0.75; JVM-Heap, `shared_buffers`/`work_mem` von PostgreSQL und der Heap von Keycloak knapp gesetzt. Im JVM-Heap der API reserviert sind `archiv.api-token.parallele-pruefungen` × 19 MiB für gleichzeitige Argon2-Rechnungen der API-Tokens, Prüfen und Anlegen zusammen (Standard 2 × 19 MiB = 38 MiB, Aufgabe 1.5; Anlegen seit 2.1). Die Werte sind Richtwerte und werden in Aufgabe 1.10 gemessen.
  - Nur Test- und anonymisierte Daten (Req 16.15); Leistungsziele gelten nicht (Req 16.16); Sicherung nur als Funktionstest (Req 16.19).
- **Stufe B** (eigene VPS, Grösse nach K5): `archiv-api` (`archiv.rolle=api`) und `archiv-worker` (`archiv.rolle=worker`) getrennt, beide mit `archiv.stufe=B`, Parallelität und Limits nach K5; hier gelten die Leistungsziele.
- **Wechsel** (Aufgabe 5.11, Req 16.17, 16.18): neue VPS nach dem Muster einrichten, Deploy-Ziel im Workflow umstellen (Host und Zugang nur als GitHub-Secrets), DNS beider Domains umstellen, Stufe B leer starten (Datenbanken und Realm-Benutzer der Stufe A werden nicht übernommen), Archiv-Dienste auf Stufe A entfernen, Leistungsmessung auf Stufe B.

### Secrets und Konfiguration (Req 16.9)
- Alle Secrets liegen als Dateien unter `/run/secrets/` (Compose `secrets:` mit `file:` aus dem Bundle), Quelle sind die GitHub-Secrets des Repos; nie `environment` oder `env_file`. Quarkus liest sie über die File-System-Config-Source (`smallrye.config.source.file.locations=/run/secrets`, Dateiname = Konfigurationsschlüssel). PostgreSQL nutzt `POSTGRES_PASSWORD_FILE`, Keycloak einen Einstiegs-Wrapper, der die Dateien liest.
- Dateien: `archiv_db_app` (DB-Rolle `archiv_app`), `archiv_db_owner` (DB-Rolle `archiv_owner`, nur für Liquibase), `keycloak_db`, `keycloak_admin`, `s3_app_zugang_id` und `s3_app_zugang_geheimnis` (App-Schlüssel, aufgeteilt in Schlüssel-ID und Geheimnis; hat technisch das Bypass-Recht, die Anwendung setzt den Bypass-Header nie, Req 6.5; ADR-007, Nachtrag 09.11.2026), `infomaniak_ai_token`, `archiv_manifest_key` (Ed25519, PKCS#8), `archiv_data_key` (AES-256-GCM für Zugangsdaten in der DB, ab Spec 02), `smtp_zugang`, `tsa_zugang` (nur falls der Anbieter einen verlangt).
- S3-Zugang (entschieden am 10.11.2026, Nachbesserung PR #29): `%prod.quarkus.s3.aws.credentials.static-provider.access-key-id=${s3_app_zugang_id}` und `…secret-access-key=${s3_app_zugang_geheimnis}`, ohne Default. Der Browser-Job der CI startet das Archiv mit Platzhalter-Dateien, nicht mit Umgebungsvariablen.
- Dateien lesen (Nachbesserung 2 zu PR #29, 10.11.2026): `smallrye.config.source.file.locations` wirkt nur mit der Abhängigkeit `io.smallrye.config:smallrye-config-source-file-system` (Version aus dem Quarkus-BOM). Ein abschliessender Zeilenumbruch in der Datei fällt weg, eine leere Datei gilt als fehlender Wert. `quarkus.datasource.password` und `quarkus.liquibase.password` lesen `${archiv_db_app}` bzw. `${archiv_db_owner}` ohne Default; weil Quarkus die Passwörter optional mappt, hielte ein fehlender Wert allein den Start nicht auf (der Worker liefe weiter). Darum prüft `SecretPruefung` (`sicherheit/adapter/in/start`) fail closed: Jeder Schlüssel aus `archiv.secrets.pflicht` (`archiv_db_app`, `s3_app_zugang_id`, `s3_app_zugang_geheimnis`) muss aus einer Datei kommen und darf nicht leer sein, `archiv.secrets.pflicht-migration` (`archiv_db_owner`) nur, wenn beim Start migriert wird (API, Admin-Kommando; der Worker braucht den Eigentümer-Zugang nicht). Ein Wert aus einer Umgebungsvariable zählt nicht. Sonst bricht der Start mit den Namen der fehlenden Dateien ab, ohne Werte. Die Listen gelten nur in `prod`.
- Zeitpunkt der Prüfung (Aufgabe 1.4, Review PR #29): `SecretPruefung` ist ein `ConfigSourceInterceptor` (registriert wie `MigrationNachRolle`) und prüft in der Konfigurationsphase, sobald Quarkus in RUNTIME_INIT einen der Zugänge `quarkus.datasource.password`, `quarkus.liquibase.password`, `quarkus.s3.aws.credentials.static-provider.access-key-id` oder `…secret-access-key` liest – also vor Liquibase, Agroal und dem S3-Client. Beim `StartupEvent` wäre es zu spät: Ohne `archiv_db_owner` migrierte Liquibase `db/public` ersatzweise mit dem App-Zugang (die Tabellen gehörten danach `archiv_app`), und die Meldung nennte die fehlende Datei nicht. Zusätzlich muss jeder dieser Zugänge selbst aus seiner Datei stammen (direkt oder genau als `${<datei>}`); ein per Umgebungsvariable oder Systemproperty übersteuerter Zugang bricht ab. Beim Build (Augmentation) prüft der Interceptor nicht. Nachweis: `SecretPruefungTest`, `SecretEigentuemerFehltTest` (DB-Devservice, kein `public.databasechangelog`).
- Nicht geheim: der öffentliche Sicherungsschlüssel (Empfänger der Export-Verschlüsselung) und der öffentliche Manifest-Schlüssel unter `docs/`.
- Weder auf dem Server noch in GitHub: der Inhaber-Zugang zum Public-Cloud-Projekt des Object Storage (Zugangsdaten, mit denen Schlüssel ausgestellt und Korrekturen ausgeführt werden; unter Option B tritt er an die Stelle des «einzigen Bypass-Schlüssels», weil jeder Schlüssel des Projekts bypassen kann; ADR-007, Nachtrag 09.11.2026) und der private Sicherungsschlüssel; sie liegen in den Notfallunterlagen (Req 6.10, 16.12). Manifest- und Datenschlüssel (`archiv_data_key`) liegen zusätzlich im Passwort-Manager.
- Nicht auf dem Server: der restic-Schlüssel; er liegt als GitHub-Secret des zentralen Sicherungs-Workflows und in den Notfallunterlagen (Req 16.2, 16.12).

## Components and Interfaces

### Mandant (`mandant`)
- `MandantTenantResolver implements TenantResolver` (`@PersistenceUnitExtension`): liest `mandant` aus `SecurityIdentity` (OIDC-Claim oder API-Token-Principal). Ohne Mandant → `ForbiddenException`.
- Worker setzt den Mandanten über `MandantKontext.mit(mandant, () -> …)` (ThreadLocal + `@ActivateRequestContext`), der Resolver liest dort, wenn keine Identität vorhanden ist.
  - Umsetzung (Aufgabe 1.4): `quarkus.hibernate-orm.multitenant=SCHEMA`. `MandantTenantResolver` (`@RequestScoped`) fragt `MandantAufloesung` (application); den Mandanten liefert der Port `MandantAusAnmeldung`, umgesetzt von `AnmeldungMandantAdapter`: zuerst das Identitäts-Attribut `mandant` (Anschluss für den API-Token-Principal `token:<id>` aus 1.5, ohne Umbau), sonst der JWT-Claim `mandant`. Gültig ist nur genau ein Text, der `MandantCode` erfüllt (`^[a-z0-9]+(-[a-z0-9]+)*$`, höchstens 40 Zeichen; den Bucket-Namen `<präfix><code>` prüft `MandantCode.bucket` zusätzlich nach den S3-Regeln, 3–63 Zeichen); fehlend, leer, mehrwertig (JSON-Array) oder anders → ungültig. Den Schemanamen liefert immer `public.mandant.schema_name` (`JdbcMandantVerzeichnis`, `… where code = ? and aktiv`, ohne Cache, damit ein Deaktivieren sofort greift), nie der Claim-Text. Ungültig, unbekannt oder inaktiv → `MandantVerweigert` → 403 ohne Inhalt (Req 1.3). Ohne Anmeldung und ohne Kontext ist der Tenant `public` (dort gibt es keine Mandanten-Tabellen, ein Zugriff scheitert); in einem Thread ohne Request-Kontext liefert Hibernate keinen Tenant und bricht ab.
  - `MandantKontext.mit` aktiviert den Request-Kontext selbst (`RequestContextController`), setzt das ThreadLocal und räumt in `finally` auf. Ein verschachteltes `mit` mit anderem Mandanten bricht ab (`IllegalStateException`), mit demselben läuft es durch. Ist schon ein Request-Kontext aktiv, muss die Anmeldung genau diesen Mandanten tragen, sonst Abbruch: Eine offene Session könnte sonst den Tenant der Anfrage behalten (fail closed). Der Worker ruft `mit` darum aus seinem eigenen Thread ohne Request-Kontext auf. Ein neuer Kontext darf auch nicht in einer laufenden JTA-Transaktion beginnen: Die Hibernate-Session hängt an der Transaktion und behielte den Mandanten des ersten `mit`, ein zweites `mit` mit anderem Mandanten in derselben Transaktion läse im fremden Schema. `mit` bricht darum ab (`IllegalStateException`), wenn `TransactionManager.getStatus()` nicht `STATUS_NO_TRANSACTION` ist; die Transaktion gehört in den Block (`mit(code, () -> QuarkusTransaction.requiringNew().call(…))`). Ein verschachteltes `mit` mit demselben Mandanten bleibt erlaubt. Hinweis für 2.5: Quarkus aktiviert in `@Scheduled`-Methoden immer einen Request-Kontext; der Poller übergibt den Auftrag darum an einen eigenen Executor ohne Context Propagation und ruft `mit` erst dort auf. Pool-Verbindungen behalten kein Mandanten-Schema: Agroal setzt ein per `setSchema` geändertes Schema bei der Rückgabe nicht zurück, darum stellt der `AgroalPoolInterceptor` `PublicSchemaBeimAusleihen` jede ausserhalb einer Transaktion ausgeliehene Verbindung auf `public`, bevor Hibernate den Mandanten setzt (Nachweis `MandantVerbindungTest`). Innerhalb einer Transaktion liefert Agroal die Verbindung, die an der Transaktion hängt, mit dem Schema der Session. Widersprechen sich Kontext und Anmeldung beim Auflösen, bricht `MandantAufloesung` ebenfalls ab.
  - Quarkus setzt beim Tenant-Wechsel nur `Connection.setSchema` und beim Zurückgeben der Verbindung nicht zurück; `PublicSchemaBeimAusleihen` stellt beim Ausleihen ausserhalb einer Transaktion wieder `public` ein; innerhalb einer Transaktion trägt die Verbindung das Schema der Session. Rohes JDBC (Agroal) qualifiziert Tabellen darum immer mit Schema (`public.mandant`, `m_<code>.zugriff_protokoll`), unabhängig vom Zustand der Verbindung.
  - Mandanten-Entitäten tragen `@Table` ohne Schema (z. B. `DokumentartEintrag`), gemeinsame Tabellen `@Table(schema = "public")`.
- `MandantProvisionierung`: legt Schema `m_<code>` an, führt den Liquibase-Changelog `db/mandant/changelog.xml` mit `defaultSchemaName=m_<code>` aus, erstellt Bucket `<archiv.ablage.bucket-praefix><code>` (Standard `urinet-archiv-<code>`, Stufe A `urinet-archiv-a-<code>`) mit `ObjectLockEnabledForBucket=true` und Versionierung (ohne Standard-Aufbewahrung) und setzt die Bucket Policy mit Deny auf `s3:BypassGovernanceRetention` (siehe «Ablage»), spielt die Dokumentarten aus `seed/<code>/dokumentarten.json` ein (für `urinet`: `backend/src/main/resources/seed/urinet/dokumentarten.json`, siehe «Stammdaten der Dokumentarten»).
  - Umsetzung (Aufgabe 1.3): Das Admin-Kommando `archiv mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT]` ist der Einstiegspunkt `ArchivMain` derselben Anwendung (ohne Argumente startet der Server), im laufenden Container also per `exec` `java -jar quarkus-run.jar mandant anlegen <code>`. Mit Argumenten setzt `ArchivMain.main` vor dem Start `archiv.rolle=kommando`, `quarkus.http.host-enabled=false` und `quarkus.oidc.tenant-enabled=false`: kein HTTP-Server (kein Port-Konflikt mit dem Dienst), keine OIDC-Verbindung, keine Start-Migration der Mandanten; `db/public` migriert das Kommando (nur neue Changesets), damit es auch vor dem ersten Start der API läuft. Exit-Code 0 ok, 1 `MandantFehler`, 2 falscher Aufruf, 3 technischer Fehler, auch wenn schon der Start der Anwendung abbricht (z. B. falscher Zugang `archiv_db_owner`, fehlende Secret-Datei); `ArchivMain` gibt dann «Start fehlgeschlagen: <Ursache>» aus. Den Aufruf prüft `ArchivMain` vor dem Start: ein falscher Aufruf endet mit 2 und der Aufrufhilfe, ohne Quarkus zu starten und ohne Migration. Ein Startfehler des Dienstes (ohne Argumente) endet weiter mit 1. Felder im Seed, deren Name mit `_` beginnt, gelten als Kommentar und fallen weg; unbekannte Felder brechen ab, ebenso ein fehlendes oder nicht boolesches `gesetzliche_pflicht` und ein `aufbewahrung_jahre`, das weder Zahl noch ausdrücklich `null` ist (`SEED_UNGUELTIG`). Gibt es die Rolle `archiv.db.app-rolle` (Standard `archiv_app`), erhält sie im Mandanten-Schema nur DML-Rechte (auch für künftige Tabellen), keine auf `databasechangelog*`; Liquibase läuft mit `archiv_owner` über eine eigene Verbindung, die nach der Migration auf `autoCommit` zurückgesetzt wird (Liquibase lässt sie sonst in einer offenen Transaktion, und die Rechte gingen beim Schliessen verloren). Zugangsdaten nur als Dateien unter `/run/secrets` (`archiv_db_app`, `archiv_db_owner`, `s3_app_zugang_id`, `s3_app_zugang_geheimnis`; Startprüfung siehe «Secrets und Konfiguration»). Die Rechte (GRANT, ALTER DEFAULT PRIVILEGES, REVOKE auf `databasechangelog*`) vergibt eine eigene Transaktion nach dem Commit der Migration; bricht sie ab, wird sie ganz zurückgerollt, und `archiv_app` hat nie DML auf `databasechangelog*`. Die Rollenliste `archiv.rolle` lesen Start-Migration und Interceptor gleich (`Rollen.lesen`, getrimmt).
- Beim Start führt die API mit der DB-Rolle `archiv_owner` zuerst `db/public`, dann `db/mandant` für jedes aktive Mandanten-Schema aus (nur neue Changesets); danach prüft Hibernate mit `validate` (Req 1.7). Der Worker migriert nie und meldet sich erst bereit, wenn der erwartete Changelog-Stand erreicht ist.
  - Umsetzung (Aufgabe 1.3): `quarkus.liquibase.migrate-at-start` hängt an `archiv.rolle` (`MigrationNachRolle`, ein Config-Interceptor): `db/public` migrieren nur `api` und das Admin-Kommando, die Mandanten-Schemas nur `api`; der Worker braucht darum `archiv_db_owner` nicht. Fehlt das Schema eines aktiven Mandanten (z. B. nach unvollständiger Wiederherstellung), bricht der Start mit `SCHEMA_FEHLT` ab und loggt nur den Code des Mandanten; `create schema` bleibt der Provisionierung vorbehalten. Die Bereitschaft des Workers erst ab dem erwarteten Changelog-Stand folgt mit dem Worker (Aufgaben 2.5/2.6); bis dahin startet ein Worker vor der Migration nicht, weil Hibernate `validate` scheitert.
  - Umsetzung (Aufgabe 1.4, Reihenfolge Migration → Prüfung): Die Persistence Unit startet vor der Mandanten-Migration und prüft beim Start mit `validate` nur die Tabellen mit ausdrücklichem Schema (`public`); dafür sorgt der Schema-Filter `MandantSchemaFilter` (`hibernate.hbm2ddl.schema_filter_provider`). Nach der Migration aller aktiven Mandanten prüft `MandantMigration` jedes Mandanten-Schema ausdrücklich (`HibernateMandantSchemaPruefung`: `SchemaManager.forSchema(m_<code>).validateMappedObjects()`, Filter nur auf dieses Schema); eine Abweichung bricht den Start mit `SCHEMA_ABWEICHUNG` ab; das Log nennt den Code des Mandanten, die Ausnahme Schema, Tabelle und Spalte, keine Inhalte. Der Worker (ohne `api`) migriert nicht, prüft aber jedes aktive Mandanten-Schema genauso (`aktivePruefen`) und startet bei einer Abweichung nicht. Die Provisionierung per Admin-Kommando prüft das neue Schema nach Migration und Seed ebenso (`MandantProvisionierung` → `MandantSchemaPruefung`), vor Bucket und Register-Eintrag; eine Abweichung bricht mit `SCHEMA_ABWEICHUNG` ab, ohne aktiven Mandanten. Welche Laufart beim Start migriert oder nur prüft, entscheidet `MigrationBeimStart.entscheiden` (Nachweis `MigrationEntscheidTest`).
- Mandanten-Einstellungen in `public.mandant`: `geschaeftsjahr_ende` (Monat-Tag, Standard `12-31`, Req 9.6), `eigene_arten` (Arten, die der Mandant selbst ausstellt; `urinet`: `AUSGANGSRECHNUNG`, `OFFERTE`, `LOHNABRECHNUNG`, Req 8.9, 11.3), `kennungen` (eigene UID und IBANs, Req 7.5). Pflege über `GET/PATCH /mandant/einstellungen` (nur `inhaber`).

### Sicherheit (`sicherheit`)
- Keycloak-Realm `urinet-archiv` unter `archiv-auth.apps.urinet.ch`; Benutzerattribut `mandant` als Token-Claim; Realm-Rollen `inhaber`, `mitarbeiter`, `treuhaender`, `geschaeftsstelle`; TOTP Pflicht. Realm-Export ohne Secrets in `deploy/keycloak/realm-export.json`.
- Realm-Export (Stufe A und B, Aufgaben 1.2 und 1.8): keine Benutzer, keine Secrets und keine Identity Provider (geprüft von `RealmExportTest`); Selbstregistrierung aus (`registrationAllowed=false`). Die Anmelde-Bindungen (Browser, Direct Grant, Reset Credentials) verlangen TOTP als `REQUIRED` ohne Bedingung: Browser-Flow `browser-totp-pflicht` (Cookie oder Passwort und TOTP), Direct-Grant-Flow `direct-grant-totp-pflicht` (Benutzername, Passwort, OTP) und Reset-Credentials-Flow `reset-credentials-totp-pflicht` (Benutzer, E-Mail, OTP, neues Passwort); `CONFIGURE_TOTP` ist Standardaktion für neue Benutzer. Kein Client hat Direct Grant oder Implicit Flow, auch die eingebauten nicht (`account`, `account-console`, `admin-cli`, `broker`, `realm-management`, `security-admin-console`); sie stammen aus dem Keycloak-Export der verwendeten Version, ohne IDs und Secrets, und werden bei einem Keycloak-Upgrade gemeinsam nachgeführt. `archiv-pwa` ist öffentlich, nur Standard Flow mit PKCE S256, und trägt einen Audience-Mapper auf `archiv-api`; `archiv-api` ist bearer-only ohne Flows, die API verlangt `aud=archiv-api` (`quarkus.oidc.token.audience`). Attribut `mandant` im Benutzerprofil nur für Admins sicht- und änderbar, Claim `mandant` über einen Attribut-Mapper. Den importierten Export prüft `RealmExportKeycloakTest` (kein Token ohne TOTP über irgendeinen Client, keine überschriebene Flow-Bindung ohne TOTP, keine Identity Provider, Tokens ohne Audience abgelehnt).
- Test-Realm `deploy/keycloak/realm-test.json` (nur `dev` und Tests): der Export plus Testbenutzer je Rolle in den Mandanten `urinet` und `vhf-gsk`, die öffentlichen Clients `archiv-test` (Direct Grant, Audience `archiv-api`) und `archiv-test-fremd` (Direct Grant, ohne Audience) sowie `http://localhost:8080` für `archiv-pwa`. Automatisierte Tests holen das Token über `archiv-test` mit Passwort und TOTP aus einem bekannten Test-Secret; TOTP bleibt damit auch in Tests Pflicht. `RealmExportTest` prüft, dass der Test-Realm ohne Testanteile dem Export gleicht.
- `ApiTokenAuthMechanism implements HttpAuthenticationMechanism`: `Authorization: Bearer ua_<id>_<geheimnis>`; Lookup über `<id>` in `public.api_client`, Argon2id-Vergleich; Principal `token:<id>` mit `mandant` und Scope. Scope `generator` erreicht nur `POST /dokumente/generator`, Scope `import` nur `POST /dokumente` (Quelle `DRIVE`); jeder andere Endpunkt antwortet 403 (Req 2.5).
  - Umsetzung (Aufgabe 1.5):
    - Token `ua_<id>_<geheimnis>`: `id` 12 Zeichen `[a-z0-9]` (Schlüssel in `public.api_client`, auch für Liste und Widerruf), Geheimnis 32 Byte aus `SecureRandom`, Base64url ohne Padding (43 Zeichen). `ApiToken.toString()` maskiert das Geheimnis. Gespeichert wird nur der Argon2id-Hash als PHC-String (`$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`, Bouncy Castle `bcprov-jdk18on`); m=19 MiB, t=2, p=1 ist das OWASP-Minimum und hält das Speicherbudget von Stufe A ein. Beim Prüfen gelten die Parameter aus dem gespeicherten String; unplausible Werte (p < 1, m < 8·p, m > 256 MiB, t < 1, Hash < 16 Byte) gelten als «passt nicht». Kein Cache: jede Anfrage rechnet Argon2 einmal.
    - Sperre gegen Erschöpfen des Heaps (Review PR #37): Jede formal gültige Anfrage `Bearer ua_…` rechnet Argon2id mit 19 MiB, auch ohne Anmeldung und an `/q/health/*` (proactive auth). Darum laufen in `ApiTokenPruefung` höchstens `archiv.api-token.parallele-pruefungen` Vergleiche gleichzeitig (`Semaphore`, Standard 2), auch der gegen die Attrappe (gleicher Weg, gleiches Timing). Wer innerhalb von `archiv.api-token.wartezeit` (Standard 200 ms) keinen Platz erhält, bekommt ohne Argon2 503 `UEBERLASTET` mit `Retry-After: 1`; Health und OIDC-Anfragen bleiben erreichbar. Die Attrappe entsteht beim Start (`StartupEvent`) mit den Parametern des Hashers. Seit Aufgabe 2.1 (Kann-Punkte aus dem Review von PR #37) liegt die Sperre in `HashSperre`, die auch `ApiTokenVerwaltung.erstellen` für den Hash des neuen Geheimnisses nutzt; ist kein Platz frei, antwortet auch das Anlegen mit `UEBERLASTET`. Eine negative `archiv.api-token.wartezeit` bricht den Start ab wie `parallele-pruefungen` < 1, weil die Attrappe beim Start ihren Hash über die `HashSperre` rechnet und sie dadurch erzeugt (Review PR #40, `WartezeitNegativStartTest`, `KeinPlatzStartTest`); `0` ist erlaubt und heisst «nicht warten». Scheitert das Nachführen von `zuletzt`, loggt `ApiTokenPruefung` nur ID und Ursachen-Kette (`Ursachenkette`: Klassennamen, SQLState, keine Meldungen).
    - Empfehlung für Aufgabe 1.9: zusätzlich eine IP-Drosselung in Traefik (Middleware `ratelimit`) für `/api/v1/dokumente*`, den einzigen Pfaden, die ein Token erreicht.
    - `ApiTokenAuthMechanism` (Priorität 2000, vor OIDC): ohne Bearer oder ohne Präfix `ua_` nicht zuständig (OIDC übernimmt); das Präfix gilt in jeder Schreibweise (`UA_`, `Ua_`), damit kein Geheimnis per Introspection an Keycloak geht; Präfix mit falschem Aufbau (auch `UA_…`) → 401 ohne Weitergabe an OIDC. `ApiTokenIdentityProvider` prüft blockierend über `ApiTokenPruefung` (application): unbekannte ID → Vergleich gegen einen Attrappen-Hash (gleiche Laufzeit, kein Orakel für gültige IDs); widerrufen oder falsches Geheimnis → 401. Die Identität trägt den Principal `token:<id>`, die Rolle `api-token:<scope>` und die Attribute `mandant` (für `MandantAufloesung`, ohne Umbau aus 1.4) und `api-token-scope`. `zuletzt` wird höchstens einmal pro Minute und Token nachgeführt; ein Fehler dabei bricht die Anmeldung nicht ab.
    - Scope-Bindung zweifach: `@RolesAllowed` der Scope-Endpunkte nennt die Rolle `api-token:<scope>`, und `ZugangFilter` lässt eine Token-Identität nur an Methoden mit `@ErlaubtScope(<scope>)` durch, sonst 403 `NICHT_ERLAUBT` mit Protokolleintrag `VERWEIGERT` (Benutzer `token:<id>`, Rolle leer). `EndpunktWaechterTest` erzwingt: `@ErlaubtScope` nur an `POST` mit A1 auf dem Pfad des Scopes, und die Methode erlaubt genau die A1-Rollen plus die Scope-Rolle.
    - Ein Token läuft nicht ab, es gilt bis zum Widerruf (`aktiv = false`, die Zeile bleibt für das Protokoll; entschieden von Fabian am 10.11.2026). Ungenutzte Tokens nach `zuletzt` anzeigen und ein optionales `gueltig_bis` stehen als Option O.7 in tasks.md. Ein deaktivierter Mandant sperrt seine Tokens sofort (403 `MANDANT_FEHLT` aus 1.4).
    - Verwaltung `/api/v1/api-tokens` (A14, nur `inhaber`): `GET` Liste des eigenen Mandanten ohne Geheimnis und Hash; `POST` → 201 mit dem Token genau einmal und `Cache-Control: no-store`; `DELETE /{tokenId}` → 204, fremde oder unbekannte ID → 404. Kommando `archiv token erstellen <mandant> <name> <scope>`: Token nur auf stdout, ID, Mandant und Scope auf stderr; im Kommando-Modus gehen das Log (`quarkus.log.console.stderr=true`) und alles andere, was auf `System.out` schreibt (Konsolenausgabe von Liquibase), auf stderr, die Meldungen der Liquibase-Migration ins Log (`LoggerUIService`; seit 2.1 auch für die public-Migration beim Start über den Wurzel-Scope in `ArchivMain`, die Zusammenfassung über `liquibase.command.showSummaryOutput=log`, abgesichert in `TokenKommandoStartTest`; den Wurzel-Scope setzt `ArchivMain` auch im Dienst-Modus, Review PR #40); Exit 0/1/2/3 wie `mandant anlegen`. Logs nennen nur ID, Mandant und Scope.
    - Bis 2.4 und 3.x die fachlichen Endpunkte bringen, stehen Probe-Endpunkte unter src/test (`ProbeTokenResource`, `POST /dokumente` und `POST /dokumente/generator`); die echten Endpunkte übernehmen `@ErlaubtScope` und die Rollen.
- Lokale Clients (`tools/archiv_client.py`, `tools/drive_import`) lesen das Token aus einer Datei mit Rechten 600: Standard `~/.config/urinet-archiv/token`, anderer Pfad über `ARCHIV_TOKEN_DATEI`; nie das Token selbst aus einer Umgebungsvariable (tech.md Regel 7).
- `Berechtigung` (application) setzt die Rechtematrix (Req 2.6) um. Reihenfolge bei Endpunkten mit `{id}`:
  1. Dokument über `VertraulichkeitFilter` laden; fremder Mandant oder unsichtbar → 404 `NICHT_GEFUNDEN` (Req 1.4, 2.3).
  2. Recht der Aktion (A5–A10) prüfen → 403 `NICHT_ERLAUBT`.
  3. `If-Match` prüfen → 428 `VERSION_FEHLT` bzw. 412 `VERSION_KONFLIKT` (Req 10.4, 10.6).
  4. Status prüfen → 409 `STATUS_KONFLIKT`.
  5. Inhalt prüfen → 422.
  Fälle 1 und 2 werden mit Aktion `VERWEIGERT` protokolliert (Req 10.7). Endpunkte ohne Dokumentbezug (A1, A11–A14) prüfen per `@RolesAllowed` bzw. Scope.
- Umsetzung (Aufgabe 1.4):
  - Die Rechtematrix A1–A14 ist eine Domain-Regel (`sicherheit/domain/Rechtematrix`: `Rolle` × `Aktion` → `Ergebnis` mit `ERLAUBT`, `VERBOTEN`, `NICHT_SICHTBAR`, `NUR_EIGENE`, `NUR_LESEND`, `NUR_SELBST_ERFASSTE`); A15–A19 (Spec 04) kommen als weitere `Aktion`-Werte dazu. `geschaeftsstelle` A11–A14 = 403 (Fussnote ¹). `RechtematrixSpecTest` liest die Tabelle aus `requirements.md` und vergleicht jede Zelle und die Spalte «Aktion» (ohne Backticks) mit `Aktion.bezeichnung()`; unbekannter Zellentext, fehlende oder zusätzliche Zeilen und jede Tabellenzeile ausser der Trennzeile, die nicht mit `| A<n> |` beginnt (z. B. `| B1 |`, `|A15|`), machen ihn rot.
  - Rollen kommen aus `realm_access/roles` (`quarkus.oidc.roles.role-claim-path`). Ohne Archiv-Rolle → 403 (fail closed). Hat ein Benutzer mehrere Archiv-Rollen, gilt nur die stärkste in der Rangfolge `inhaber`, `geschaeftsstelle`, `mitarbeiter`, `treuhaender` (`Rolle.ausRealmRollen`); die Rechte werden nicht vereinigt (Req 2.2, Entscheid Fabian 10.11.2026). Die Benutzer-ID ist der Claim `sub` (Keycloak-ID, `quarkus.oidc.token.principal-claim=sub`), nie der Benutzername: `erstellt_von` und `person_benutzer` steuern die Sichtbarkeit von `PERSONAL`, und ein Benutzername kann neu vergeben werden. Der Benutzername dient nur zur Anzeige. Fehlt `sub`, gibt es keinen Zugang (403). Nachweis `BenutzerIdTest`.
  - Jede Ressourcen-Methode trägt genau eine Erklärung: `@ErfordertAktion(Aktion.Ax)`, `@AlleArchivRollen` (alle vier Rollen, z. B. Stammdaten lesen) oder `@Oeffentlich` (`@PermitAll`, ohne Mandant und Rolle). `@RolesAllowed` bleibt: bei dokumentbezogenen Aktionen (A2–A10, `Aktion.dokumentbezogen()`) alle vier Archiv-Rollen, damit der Filter 404 vor 403 prüfen kann; bei A1 und A11–A14 genau die Rollen, für die die Aktion nicht 403 ist. Eine dokumentbezogene Aktion mit Pfad-Parameter führt das Dokument als `{id}`, sonst fiele die Sichtbarkeitsprüfung weg. `EndpunktWaechterTest` prüft das für alle konkreten Klassen, in deren Hierarchie ein `@Path` steht: HTTP-Verb und Methoden-`@Path` dürfen aus Interface oder Oberklasse stammen, die Sicherheitsannotationen gelten an der Implementierung; eine Methode mit `@Path` ohne HTTP-Verb (Sub-Resource-Locator) ist ein Verstoss. `quarkus.security.jaxrs.deny-unannotated-endpoints=true` sperrt Methoden ohne Sicherheits-Annotation.
  - Der `ZugangFilter` (`@ServerRequestFilter`) prüft bei jeder nicht öffentlichen Methode Mandant (`MandantAufloesung.pruefen`), Archiv-Rolle (die stärkste gilt), Benutzer-ID und die Aktion über `Berechtigung`. `@RolesAllowed` greift vor dem Filter; bei A1 und A11–A14 antworten Rollen ohne Recht darum schon dort mit 403, bei A2–A10 lässt es alle Rollen durch. Hat eine dokumentbezogene Aktion den Pfad-Parameter `id`, ruft der Filter `Berechtigung.dokumentAktion` auf; eine `id`, die keine UUID ist, gilt als nicht vorhanden (404 `NICHT_GEFUNDEN`, `Berechtigung.unbekanntesDokument`). `Berechtigung.dokumentAktion` fragt zuerst den Port `Sichtbarkeit` (ab 2.2 `VertraulichkeitFilter`; bis dahin `OhneDokumenteSichtbarkeit`, nichts sichtbar) → 404 `NICHT_GEFUNDEN`, erst danach das Recht → 403 `NICHT_ERLAUBT`. Jede Verweigerung geht an den Port `VerweigerungMelder` (heute nur ein Log mit IDs; 1.6 schreibt `VERWEIGERT` ins Zugriffsprotokoll). Antworten ohne Inhalt; Problem Details folgen mit 1.6.
  - Probe-Endpunkte für die Szenarien liegen nur unter `src/test` (`ch.urinet.archiv.probe`, dazu `ProbeSichtbarkeit` mit festen Probe-Dokumenten `NORMAL` und `INTERN` im Mandanten `urinet`, bis 2.2 den `VertraulichkeitFilter` bringt); `ArchitekturTest` verbietet das Paket in `src/main`, und die OpenAPI-Datei schliesst es aus (`mp.openapi.scan.exclude.packages`).
- `VertraulichkeitFilter` hängt an jede Dokumentabfrage (Liste, Suche, Zähler, Einzelabruf) die Sichtbarkeit nach A2–A4. Ohne bestätigte Art wirkt `PERSONAL` (Req 2.21):

```sql
-- v_eff = case when d.bestaetigt is null then 'PERSONAL' else d.vertraulichkeit end
-- inhaber, geschaeftsstelle: keine Einschränkung
-- treuhaender (nur lesend, A2–A4):
d.bestaetigt is not null
-- mitarbeiter:
v_eff = 'NORMAL'
or (v_eff = 'PERSONAL' and (d.person_benutzer = :benutzer
                            or (d.bestaetigt is null and d.erstellt_von = :benutzer)))
```
- Eingangskorb (A5): `mitarbeiter` zusätzlich nur `d.erstellt_von = :benutzer` (Req 2.24); `treuhaender` 403 (Req 2.11).
- `vertraulichkeit` und `person_benutzer` ändern nur Aktion A8 (`PATCH`, `bestaetigen`) und die Generator-Metadaten (Req 2.23); ein `PATCH` mit diesen Feldern ohne A8 antwortet 403 als Ganzes.

### Dokument (`dokument`)
REST `DokumentResource` (alle Pfade unter `/api/v1`):

Rollen nach Rechtematrix (Req 2.6); `inhaber` und `geschaeftsstelle` haben A1–A10, A11–A14 nur
`inhaber`. Ändernde Aufrufe mit `{id}` verlangen `If-Match` (Req 10.4, 10.6).

| Methode | Pfad | Zweck und Antworten | Recht |
| --- | --- | --- | --- |
| POST | `/dokumente` | Upload (siehe unten) → 202 mit Ergebnis je Datei; 413, 415, 422, 503 | A1; Token `import` |
| POST | `/dokumente/generator` | PDF + `metadaten` (JSON) → 202, 200 (gleiche SHA-256), 409 `BELEGNUMMER_VERGEBEN`, 422 | Token `generator` |
| GET | `/dokumente` | Suche/Liste mit Filtern (Req 14), nur sichtbare | A2–A4 |
| GET | `/dokumente/{id}` | Metadaten, Status, Versionen, Warnungen; `ETag`; 404 | A2–A4 |
| GET | `/dokumente/{id}/datei?variante=pdfa\|original\|arbeitsfassung&nr=` | 302 auf vorsignierte URL der gespeicherten Versions-ID (5 Min.) | A2–A4 |
| GET | `/dokumente/{id}/vorschau?seite=1` | PNG-Vorschau (im Worker erzeugt) | A2–A4 |
| PATCH | `/dokumente/{id}` | Metadaten ändern; bei `ARCHIVIERT` `grund` Pflicht; 412, 428, 422 `ZUSATZ_UNGUELTIG` | A6 (Vertraulichkeit A8) |
| POST | `/dokumente/{id}/bestaetigen` | bestätigte Metadaten inkl. Vertraulichkeit → 202, Status `ARCHIVIERUNG`; 409 `STATUS_KONFLIKT` (nicht `EINGANGSKORB`), `BELEGNUMMER_VERGEBEN`, `FRIST_ABGELAUFEN`; 422 `PFLICHTFELD_FEHLT`; 412, 428 | A7 (+A8) |
| POST | `/dokumente/{id}/verwerfen` | `{grund}` → 204; 409 bei `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER`, `ARCHIVIERT`; 412, 428 | A9 |
| POST | `/dokumente/{id}/wiederholen` | `FEHLER` → `NEU`, `ARCHIVIERUNG_FEHLER` → `ARCHIVIERUNG` → 202; sonst 409; 412, 428 | A10 |
| GET | `/eingangskorb` | Status `EINGANGSKORB`, `FEHLER`, `ARCHIVIERUNG_FEHLER`, neueste zuerst | A5 |
| GET | `/dokumentarten` | aktive und inaktive Arten (Auswahllisten) | alle Rollen |
| POST, PATCH | `/dokumentarten`, `/dokumentarten/{code}` | anlegen, ändern, `aktiv=false`; 422 `ZUSATZ_UNGUELTIG` bei ungültigem Schema | A11 |
| DELETE | `/dokumentarten/{code}` | 204; 409 `DOKUMENTART_IN_GEBRAUCH` | A11 |
| GET | `/protokoll?von=&bis=&benutzer=&dokument=` | Zugriffsprotokoll, paginiert (Req 10.5) | A13 |
| GET, POST, DELETE | `/api-tokens`, `/api-tokens/{tokenId}` | Tokens anlegen (Geheimnis nur einmal angezeigt), auflisten, widerrufen; `{tokenId}` statt `{id}`, weil `{id}` für Dokument-IDs reserviert ist (404 vor 403 im `ZugangFilter`) | A14 |
| GET, PATCH | `/mandant/einstellungen` | Geschäftsjahresende, eigene Arten, Kennungen | wie A11 |

**Upload** (`POST /dokumente`, Req 3, 4.7):
- Multipart: `dateien[]` (Originale); von der Handy-App zusätzlich `arbeitsfassungen[]` in derselben Reihenfolge (Seite n ↔ Datei n); `zusammenfassen=true` (Handy immer); `hinweis` (Drive-Import: Ordnerpfad).
- Grenzen: 50 MB je Datei (413 `DATEI_ZU_GROSS`); Anfrage gesamt höchstens `archiv.upload.max-anfrage` (Startwert 500 MB, darüber 413 für die ganze Anfrage).
- Je Datei, vor der Antwort: in eine temporäre Datei streamen und dabei SHA-256 berechnen → Typ per Tika am Inhalt (415 `DATEITYP_UNBEKANNT`) → leer oder nicht lesbar (422 `DATEI_UNGUELTIG`) → Dublettenprüfung über den Index auf `dokument_datei` → `dokument_datei` schreiben, Ablage unter `eingang/` → Auftrag `VERARBEITEN`. Der Messpunkt von Req 3.2 liegt zwischen vollständig empfangener Anfrage und Antwort.
- Antwort 202, sobald mindestens eine Datei angenommen oder als Dublette erkannt ist (P8, Req 3.4, 3.8):

```json
[ {"datei": "scan1.pdf", "id": "…", "status": "NEU"},
  {"datei": "scan2.pdf", "status": "DUBLETTE", "vorhandenId": "…"},
  {"datei": "foto.heic", "status": "DUBLETTE", "meldung": "bereits vorhanden"},
  {"datei": "notiz.docx", "status": "ABGELEHNT",
   "fehler": {"status": 415, "fehlercode": "DATEITYP_UNBEKANNT", "detail": "…"}} ]
```
  `vorhandenId` fehlt, wenn das vorhandene Dokument für den Benutzer unsichtbar ist (Req 2.3). Sind alle Dateien abgelehnt, antwortet das Archiv mit dem Status der ersten Ablehnung als Problem Details und der Erweiterung `ergebnisse` (gleiche Liste).
- `zusammenfassen=true`: Die angenommenen Dateien bilden ein Dokument in der gesendeten Reihenfolge (alle Einträge tragen dieselbe `id`); Dubletten fallen heraus. Sind alle Originale Dubletten, entsteht kein Dokument; die Handy-App löscht den Eintrag (Req 4.5).

**Generator-Pfad** (`POST /dokumente/generator`, Req 11, P7):
1. Synchron vor der Antwort: Scope `generator`, PDF-Prüfung wie beim Upload, Metadaten gegen die Art: Pflichtfelder (422 `PFLICHTFELD_FEHLT` mit Liste, Req 11.6), `partner.crmId` bekannt (422 `PARTNER_UNBEKANNT`, Req 11.7), `vertraulichkeit` und `personBenutzer` nach Req 11.10, `zusatz` gegen `zusatz_schema` (422 `ZUSATZ_UNGUELTIG`).
2. Gehört die SHA-256 schon zu einem nicht verworfenen Dokument: 200 mit `{id, status}` des vorhandenen Dokuments, nichts Neues (Req 11.8).
3. Eigene Art und Belegnummer schon in `belegnummer_eigen` reserviert: 409 `BELEGNUMMER_VERGEBEN` (Req 11.3); der Client verschiebt die Datei nach `archiv-konflikt/` (Req 11.9).
4. Sonst: Dokument mit `quelle = GENERATOR`, `bestaetigt` gesetzt, Belegnummer reserviert, Auftrag `VERARBEITEN` mit `ohneKi = true` (Prüfsumme, PDF/A, Volltext, Vorschau, `aufbewahren_bis`) → Status `ARCHIVIERUNG`, Auftrag `ARCHIVIEREN` → `ARCHIVIERT`.
- **Antwort 202** `{id, status: "NEU"}` mit `Location`. Begründung: PDF/A und Archivierung laufen im Worker mit eigenen Zeitgrenzen; eine synchrone 201 nach Abschluss würde den Generator minutenlang blockieren und bei Zeitüberschreitung doppelte Sendungen auslösen. Der Client wertet 200 und 202 als Erfolg. Scheitert die spätere Verarbeitung, steht das Dokument in `FEHLER` bzw. `ARCHIVIERUNG_FEHLER` im Eingangskorb, und Req 16.5 alarmiert.

Statusmodell (Req 5, 6, 8):

```mermaid
stateDiagram-v2
  [*] --> NEU: Upload / Import / Generator
  NEU --> VERARBEITUNG: Worker holt Auftrag
  VERARBEITUNG --> EINGANGSKORB: OCR + KI fertig (auch ohne Vorschlag oder mit pdfa_fehlt)
  VERARBEITUNG --> ARCHIVIERUNG: Generator, ohne KI
  VERARBEITUNG --> FEHLER: 3 Versuche fehlgeschlagen
  FEHLER --> NEU: wiederholen (erneut verarbeiten)
  FEHLER --> VERWORFEN: verworfen
  EINGANGSKORB --> ARCHIVIERUNG: bestätigt (A7), Auftrag ARCHIVIEREN
  EINGANGSKORB --> VERWORFEN: verworfen (Dateien gelöscht)
  ARCHIVIERUNG --> ARCHIVIERT: kopiert, Prüfsummen gleich, Sperre gesetzt
  ARCHIVIERUNG --> ARCHIVIERUNG_FEHLER: Abweichung, Sperre fehlt oder 3 Versuche
  ARCHIVIERUNG_FEHLER --> ARCHIVIERUNG: wiederholen (nur Archivierung)
  ARCHIVIERT --> ARCHIVIERT: Metadaten-Version
```

- `bestaetigen` setzt `bestaetigt`, `bestaetigt_von`, `geschaeftsjahr`, `aufbewahren_bis`, reserviert bei eigenen Arten die Belegnummer und reiht `ARCHIVIEREN` ein, alles in einer Transaktion. Die bestätigten Metadaten bleiben bei `ARCHIVIERUNG_FEHLER` unverändert (Req 6.4, 6.8).
- Bestätigen eines Dokuments mit `pdfa_fehlt` verlangt `{ohnePdfa: true}` und archiviert nur Original und Arbeitsfassung (Req 5.9).
- Warnungen im Eingangskorb (Req 8.10): gibt es bei einem Fremdbeleg schon ein nicht verworfenes Dokument mit gleicher Belegnummer, gleichem Partner und gleicher Art, liefert `GET /dokumente/{id}` `warnungen: [{code: "BELEGNUMMER_BEKANNT", vorhandenId}]` (ohne ID, wenn unsichtbar); die Bestätigung bleibt erlaubt.
- Port `DokumentEreignisse` in `dokument/application` (Spec 04 ergänzt, umgesetzt in Spec 04, Aufgabe 14.1; Spec 04, Req 5.1, 15.1): `archiviert(id, art)` meldet der Handler `ARCHIVIEREN` beim Wechsel auf `ARCHIVIERT`, `geaendert(id, felder)` melden `PATCH` (auch A8) und jeder Statuswechsel, jeweils synchron in derselben Transaktion wie der Statuswechsel bzw. die Metadaten-Version. Grund: Asynchron ginge ein Folgeauftrag (z. B. `WISSEN_INGEST`) bei einem Absturz verloren, und andere Fachbereiche erreichen `dokument` nur über `application` (ADR-011).

### Ablage (`ablage`)
- `AblageService` (application) nutzt den Driven Port `AblagePort` mit dem Adapter `S3Ablage` (`adapter/out/s3`, App-Schlüssel aus `s3_app_zugang_id` und `s3_app_zugang_geheimnis`; setzt nie `x-amz-bypass-governance-retention` und verkürzt keine Sperre, Req 6.5) und einem Dev-Stub im Dateisystem. Operationen: `ablegenEingang(mandant, docId, rolle, nr, stream)`, `archivieren(…)`, `loeschenEingang(…)` (alle Versionen), `sperreSetzen(…)`, `sperreVerlaengern(…)`, `vorsignierteUrl(schluessel, versionId, 5 min)`.
- `archivieren` (Auftrag `ARCHIVIEREN`, Req 6.3), je Datei (Originale, Arbeitsfassungen, PDF/A):
  1. CopyObject `eingang/…` → `archiv/…`, Versions-ID der Kopie speichern.
  2. SHA-256 vergleichen: `x-amz-checksum-sha256`, falls Infomaniak ihn liefert (Spike Object Storage, Aufgabe 0.2, Req 0.4), sonst die Kopie über ihre Versions-ID erneut lesen und hashen.
  3. Nur bei `gesetzliche_pflicht`: `PutObjectRetention(mode=GOVERNANCE, retainUntil)` und Kontrolle per `GetObjectRetention`.
  4. Erst wenn alle Dateien kopiert, geprüft und gesperrt sind: `dokument.archiviert = now()`, `sperre_bis`, Status `ARCHIVIERT` und in derselben Transaktion `DokumentEreignisse.archiviert(id, art)` (siehe «Dokument»); danach die `eingang/`-Objekte mit allen Versionen löschen (der Bucket ist versioniert, ein einfaches Löschen liesse Altversionen liegen).
- Abweichende Prüfsumme oder nicht gesetzte Sperre → sofort `ARCHIVIERUNG_FEHLER` ohne weiteren Versuch, Benachrichtigung an den Inhaber (Req 6.4); Netz- oder 5xx-Fehler → Wiederholung nach der Auftragsregel, nach 3 Versuchen ebenfalls `ARCHIVIERUNG_FEHLER`. Ein neuer Versuch übernimmt eine vorhandene, korrekte Kopie (gleiche SHA-256) statt erneut zu kopieren; eine fehlerhafte, ungesperrte Kopie löscht er über ihre Versions-ID, damit unter `archiv/` keine fremden Versionen entstehen (Req 15.10).
- `retainUntil`, Prüfung in dieser Reihenfolge: (1) Art ohne `gesetzliche_pflicht` → keine Sperre, Prüfsummen und Manifest gelten trotzdem (Req 6.12), auch wenn das Dokument unbefristet ist; (2) «Frist abgelaufen» → keine Sperre (Req 9.8); (3) unbefristet → heute + 10 Jahre, der Prüflauf verlängert (Req 6.9, 15.9); (4) sonst Ende des Tages `aufbewahren_bis` (Europe/Zurich, als UTC).
- Sperre bei Metadatenänderung (Req 9.7): Ergibt die Neuberechnung ein späteres `aufbewahren_bis` oder «unbefristet», verlängert `sperreVerlaengern` alle Dateien. Bei einer Art mit `gesetzliche_pflicht` oder einem Dokument mit Sperre (`sperre_bis` gesetzt) ändert ein früheres Datum oder ein Datum statt «unbefristet» die Frist und die Sperre nicht, `aufbewahren_bis` und `sperre_bis` bleiben, und der Konflikt wird mit Aktion `SPERRE_KONFLIKT` protokolliert. Bei einer Art ohne `gesetzliche_pflicht` und einem Dokument ohne Sperre gilt das neue Ergebnis, auch wenn es früher liegt (entschieden von Fabian am 10.11.2026). Wechselt die Art auf eine mit `gesetzliche_pflicht`, wird die Sperre jetzt gesetzt. Verlängern geht in GOVERNANCE ohne Bypass-Header, Verkürzen und Löschen nicht [T 3b, 3c]; die Anwendung setzt den Bypass-Header nie (Req 6.5; ADR-007, Nachtrag 09.11.2026).
- Verwerfen (Req 6.6): löscht alle Versionen unter `eingang/` und `vorschau/`, setzt `dokument_datei.status = 'VERWORFEN'` und protokolliert SHA-256 und Grund. Hat das Dokument eine Zeile in `belegnummer_eigen` (nur möglich bei Generator-Dokumenten, die schon beim Eingang reservieren und danach in `FEHLER` oder mit `pdfa_fehlt` im Eingangskorb landen), wird sie in derselben Transaktion gelöscht (Req 11.11).
- Schlüssel (`<yyyy>` = Jahr des Eingangs): `eingang/<yyyy>/<docId>/<nr>-original.<ext>`, `eingang/<yyyy>/<docId>/<nr>-arbeitsfassung.jpg`, `eingang/<yyyy>/<docId>/pdfa.pdf`, unter `archiv/<yyyy>/<docId>/` dieselben Namen, `vorschau/<docId>/<seite>.png` (ohne Sperre, regenerierbar), `importe/<laufId>.csv`, `manifest/<yyyy-mm-dd>.json` mit `.sig` und `.tsr`.
- Bucket ohne Standard-Aufbewahrung (sonst wäre `eingang/` gesperrt); Sperre nur pro Objekt.
- Bucket Policy mit Deny auf `s3:BypassGovernanceRetention` (entschieden von Fabian am 09.11.2026): `MandantProvisionierung` setzt sie auf jedem Mandanten-Bucket. Sie schützt nur gegen Versehen, ein Aufruf mit Bypass-Header scheitert [T 6c]; gegen einen gestohlenen Schlüssel schützt sie nicht, weil jeder Schlüssel des Projekts sie löschen oder überschreiben kann [T 6b–7g]. Eine Korrektur mit dem Inhaber-Zugang muss sie vorher entfernen.
- Endpunkt (Spike 0.2): Ceph-Dienst von Infomaniak, nicht das veraltete «S3 over Swift» (kein Object Lock). `quarkus.s3.endpoint-override=https://s3.gva2.infomaniak.cloud`, `quarkus.s3.path-style-access=true`, `quarkus.s3.aws.region=gva2`, `quarkus.s3.aws.credentials.type=static`; der Schlüssel kommt aus `s3_app_zugang_id` und `s3_app_zugang_geheimnis` (siehe «Secrets und Konfiguration»). Einen Schlüssel ohne Bypass-Recht gibt es im selben Projekt nicht [T 6b–7g]; es gilt Option B (ADR-007, Nachtrag 09.11.2026; Open Points).

### Dokumentart und Aufbewahrung (`dokumentart`)
- `Aufbewahrung.berechne(art, belegdatum, eingang, geschaeftsjahr, gueltigBis, gjEnde)` ist reine Domain-Logik (JUnit-Tabellentests), Req 9.3:
  - Geschäftsjahr-Vorschlag: das Geschäftsjahr, in das das Belegdatum fällt, ohne Belegdatum das Eingangsdatum (Europe/Zurich); bezeichnet nach dem Kalenderjahr seines Endes. Beispiel GJ-Ende `06-30`: Beleg 15.08.2026 → GJ 2027.
  - `aufbewahrung_ab = GESCHAEFTSJAHR`: Ende des bestätigten Geschäftsjahres + `aufbewahrung_jahre` (Beleg 2026, GJ-Ende `12-31` → 31.12.2036).
  - `aufbewahrung_ab = GUELTIG_BIS`: `gueltig_bis` + `aufbewahrung_jahre`; ohne `gueltig_bis` unbefristet.
  - `aufbewahrung_jahre = null`: unbefristet, `aufbewahren_bis = null`.
  - Ergebnis vor heute: `frist_abgelaufen = true` (Req 9.8).
  - Testfälle: 29.02., GJ-Ende ungleich `12-31`, fehlendes Belegdatum, Vertrag ohne Ende, Änderung der Art.
- «Frist abgelaufen» (Req 9.8): Die erste Bestätigung antwortet 409 `FRIST_ABGELAUFEN`, das Dokument bleibt mit Kennzeichen im Eingangskorb. Der Inhaber bestätigt erneut mit `{fristAbgelaufenAkzeptiert: true}` (archiviert ohne Sperre) oder verwirft.
- Neuberechnung bei Änderung von Belegdatum, Art, Geschäftsjahr oder `gueltig_bis` (Req 9.7, entschieden von Fabian am 10.11.2026): `Aufbewahrung.entscheiden(bisher, neu, gesperrt)` (Domain, JUnit-Tabellentests) ergibt `GLEICH`, `VERLAENGERN` (späteres Datum oder «unbefristet»), mit Sperre `KONFLIKT` (früheres Datum oder ein Datum statt «unbefristet»: Frist und Sperre bleiben, `SPERRE_KONFLIKT`) und ohne Sperre `FRUEHER_UEBERNEHMEN` (das neue, frühere oder befristete Ergebnis gilt). `gesperrt` heisst: die bisherige oder die neue Art hat `gesetzliche_pflicht`, oder das Dokument trägt schon eine Sperre; das bestimmt der Aufrufer (2.2/2.3). Sperren siehe «Ablage».
- Regel einer Art ändern (Req 9.10, entschieden von Fabian am 10.11.2026): Jahre, «ab» und `gesetzliche_pflicht` dürfen sich auch ändern, wenn die Art schon Dokumente hat. Die neue Regel gilt für neue Dokumente und für jede Neuberechnung nach Req 9.7; bestehende Sperren werden nur verlängert, nie verkürzt. Jede Änderung, die mindestens einen dieser drei Werte ändert, schreibt `REGEL_AENDERN` ins Zugriffsprotokoll, ohne `dokument_id`, mit `details = {"dokumentart": <code>, "alt": {aufbewahrungJahre, aufbewahrungAb, gesetzlichePflicht}, "neu": {…}}` (nur Werte der Regel, keine Werte eines Dokuments; `null` heisst unbefristet). Eine Neuberechnung bestehender Dokumente löst die Regeländerung nicht aus; sie folgt mit 2.2/2.3.
- Zusatzfelder: `zusatz_schema` ist JSON Schema (Draft 2020-12). Validiert werden die Werte beim Speichern und das Schema selbst beim Anlegen einer Art; Fehler → 422 `ZUSATZ_UNGUELTIG` (Req 9.5). Pflichtfelder in Zusatzfeldern heissen `zusatz.<feld>`.
- Löschen einer Art mit Dokumenten (auch verworfenen) → 409 `DOKUMENTART_IN_GEBRAUCH`; erlaubt bleibt `aktiv = false` (Req 9.4).
- Umsetzung 2.1 (Erkenntnisse):
  - Schema-Prüfung mit `com.networknt:json-schema-validator` (Draft 2020-12, Apache-2.0) hinter dem Port `ZusatzSchemaPruefer`. Keine Remote-Referenzen (`fetchRemoteResources(false)`); `$ref`, `$dynamicRef` und `$recursiveRef` nur lokal (`#…`); `$id`, `$anchor`, `$dynamicAnchor`, `$recursiveAnchor` und `$vocabulary` abgelehnt; `$schema` nur 2020-12. Die Wurzel ist ein Objekt mit `type: object`. Grenzen: Schema höchstens 16 KiB und 10 Ebenen tief, Werte höchstens 64 KiB. Doppelte Schlüssel und Zeichen nach dem JSON-Wert sind ungültig. Das Schema wird gegen das Meta-Schema geprüft und vollständig vorkompiliert (z. B. ungültiges `pattern`, fehlendes `$ref`-Ziel → 422). Pflichtfelder `zusatz.<feld>` müssen unter `properties` stehen. `format` wird geprüft.
  - Die Zahl der Dokumente einer Art liefert der Port `DokumentZaehlerPort`; bis zur Tabelle `dokument` (2.2) zählt `OhneDokumenteZaehler` 0, 2.2 ersetzt ihn durch eine Abfrage auf `dokument` (auch `VERWORFEN`).
  - Löschen ist physisch (`DELETE`), nur ohne Dokumente. `PATCH` ohne `ETag`/`If-Match`, weil `dokumentart` keine Spalte `version` hat und nur der `inhaber` ändert.
  - `POST` verlangt `code`, `name`, `vertraulichkeit` und `gesetzlichePflicht`. Ohne `aufbewahrungJahre` gilt der Standard von 10 Jahren (Req 9.3), ohne `aufbewahrungAb` dann `GESCHAEFTSJAHR`; ausdrücklich `"aufbewahrungJahre": null` heisst unbefristet (entschieden im Review von PR #40). Ohne `zusatzSchema` gilt `{"type":"object","properties":{},"additionalProperties":false}`. Doppelter Code → 409 `DOKUMENTART_EXISTIERT` (Review PR #40). Doppelte Schlüssel oder Zeichen nach dem JSON-Wert im Body → 400 `ANFRAGE_UNGUELTIG` (gleicher strenger Leser wie für das Schema).
  - Rechenzeit (Review PR #40): Reguläre Ausdrücke (`pattern`, `patternProperties`, `pattern` unter `propertyNames`) laufen in JDK-Syntax (nicht ECMA-262, siehe tech.md) über eine eigene `RegularExpressionFactory`, deren Eingabe nach `archiv.dokumentart.muster-zeitgrenze` (Standard 50 ms je Prüfung, zusammen für alle Muster eines Aufrufs) abbricht → 422 `ZUSATZ_UNGUELTIG`. Verweise (`$ref`, `$dynamicRef`, `$recursiveRef`) müssen `#` oder ein JSON Pointer `#/…` in dieses Schema sein; ein Kreis ohne Fortschritt in den Daten (über Verweise, `allOf`, `anyOf`, `oneOf`, `not`, `if`/`then`/`else`, `dependentSchemas`, z. B. `{"type":"object","$ref":"#"}`) ist ungültig; Rekursion über `properties` oder `items` bleibt erlaubt. Ein `StackOverflowError` beim Kompilieren oder Prüfen ergibt ebenfalls 422. Geprüfte und kompilierte Schemas hält ein kleiner Cache (32 Einträge, Schlüssel ist der Schema-Text).
  - Seed-Prüfung (Review PR #40): `MandantProvisionierung` prüft jede Vorlage des Seeds mit den Domain-Regeln von `Dokumentart` und dem `ZusatzSchemaPruefer` (`DokumentartPruefung`), bevor sie das Schema des Mandanten anlegt; ein Fehler bricht mit `SEED_UNGUELTIG` ab. `SeedPruefungTest` lässt alle Seeds unter `seed/*/` durchlaufen.
  - Eine gespeicherte Art, die die Domain-Regeln verletzt, ist ein Serverfehler (500), keine ungültige Anfrage.
  - Regeländerung (Req 9.10, entschieden von Fabian am 10.11.2026): `DokumentartVerwaltung.aendern` vergleicht Jahre, «ab» und `gesetzliche_pflicht` und ruft bei einer Änderung den Port `RegelProtokollPort` auf (Adapter `ZugriffRegelProtokoll` in `dokumentart/adapter/out/protokoll`: Benutzer und Rolle aus der Anmeldung, IP aus `X-Real-Ip`, Port `ZugriffProtokoll`). Er schreibt in der Transaktion des `PATCH`; scheitert der Eintrag, ist die Änderung zurückgerollt und die Antwort 500 (Req 10.8). Die Aktion `REGEL_AENDERN` kommt mit dem Changeset `004-protokoll-regel-aendern` in den Check `zugriff_protokoll_aktion_ck` (002 bleibt unverändert). Die Wirkung auf bestehende Dokumente (Neuberechnung, Sperren) ist Teil von 2.2/2.3; bis dahin ist 9.10 nur mit Zähler-Attrappe nachgewiesen (Teilnachweis).

#### Stammdaten der Dokumentarten (Req 9.2, P6)
Massgeblich ist der Seed `backend/src/main/resources/seed/urinet/dokumentarten.json`; die Tabelle
spiegelt ihn. `DokumentartSeedTest` liest die Tabelle zwischen den Markierungen und vergleicht sie
Feld für Feld mit dem Seed (Felder mit `_` am Anfang ausgenommen). Werte entschieden von Fabian am
10.11.2026. Alle Arten `aktiv`; `zusatz_schema` steht nur im Seed. `VERTRAG` bleibt `PERSONAL`, damit
Arbeitsverträge geschützt sind; einen Kundenvertrag stellt der `inhaber` bei der Bestätigung per A8 auf
`NORMAL`, wenn ihn Mitarbeitende sehen sollen (keine eigene Art für Kundenaufträge). Anpassungen, entschieden von
Fabian am 10.11.2026: `AUSGANGSRECHNUNG` ohne Pflichtfeld `faelligkeit` (F3); `BEHOERDE` mit Gruppe `finanz`, Betrag
optional (F5); `LOHNABRECHNUNG` auch für den jährlichen Lohnausweis, `zusatz.periode` als `JJJJ-MM` oder `JJJJ` (F7);
Prämienrechnungen von Versicherern (F4) und Mahnungen mit Mahngebühr oder Verzugszins (F6) sind `LIEFERANTENBELEG`.

<!-- seed:dokumentarten:start -->
| Code | Name | Gruppen | Pflichtfelder | Vertraulichkeit | Jahre | ab | Pflicht | Beschreibung |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `AUSGANGSRECHNUNG` | Ausgangsrechnung | finanz | titel, datum, partner, belegnummer, richtung, betrag, waehrung | INTERN | 10 | GESCHAEFTSJAHR | ja | Rechnung oder Gutschrift von Urinet an einen Kunden, Belegnummer wie 2026-003 (Altbestand R0001). Massgebend ist der Aussteller Urinet, nicht das Nummernformat. Rechnungen an Urinet = LIEFERANTENBELEG, Mahnungen = KORRESPONDENZ. |
| `OFFERTE` | Offerte | finanz, frei | titel, datum, partner, belegnummer, betrag, waehrung | NORMAL | 10 | GESCHAEFTSJAHR | nein | Angebot von Urinet an einen Kunden mit Betrag und Gültigkeit, Belegnummer wie O-2026-005. Bestellung oder Auftrag eines Kunden auf eine Offerte = VERTRAG. Offerten von Lieferanten und Auftragsbestätigungen = KORRESPONDENZ. |
| `LIEFERANTENBELEG` | Lieferantenbeleg | finanz | titel, datum, partner, richtung, betrag, waehrung | INTERN | 10 | GESCHAEFTSJAHR | ja | Rechnung oder Gutschrift eines Lieferanten an Urinet, auch bereits per Karte bezahlte Online-Rechnungen (Abo, Telefon, Hosting), Rechnungen öffentlicher Werke (Strom, Wasser), Prämienrechnungen von Versicherern und Mahnungen mit Mahngebühr oder Verzugszins. Rechnungen von Ämtern und Ausgleichskassen = BEHOERDE. |
| `QUITTUNG` | Quittung / Spesen | finanz | titel, datum, richtung, betrag, waehrung | INTERN | 10 | GESCHAEFTSJAHR | ja | Kassenzettel und Quittungen über bar oder mit Karte bezahlte Ausgaben, auch Spesenbelege wie Bahn, Parkplatz und Verpflegung. Eine Rechnung an Urinet, auch eine bereits bezahlte, = LIEFERANTENBELEG. |
| `LOHNABRECHNUNG` | Lohnabrechnung | finanz, frei | titel, datum, richtung, betrag, waehrung, zusatz.periode, zusatz.person | PERSONAL | 10 | GESCHAEFTSJAHR | ja | Monatliche Lohnabrechnung oder jährlicher Lohnausweis einer mitarbeitenden Person. Lohndeklarationen und Beitragsrechnungen = BEHOERDE (AHV, IV, EO, ALV, FAK, Quellensteuer) bzw. VERSICHERUNG (UVG, Krankentaggeld, BVG). |
| `VERTRAG` | Vertrag | vertrag | titel, datum, partner, gueltig_ab | PERSONAL | 10 | GUELTIG_BIS | ja | Befristeter oder unbefristeter Vertrag, etwa Arbeits-, Miet-, Leasing-, Kunden- oder Lieferantenvertrag, samt Nachträgen und Kündigungen, auch der Auftrag eines Kunden auf eine Offerte. Policen und Kündigungen von Versicherungsverträgen = VERSICHERUNG. |
| `VERSICHERUNG` | Versicherung | vertrag, finanz | titel, datum, partner, gueltig_ab | INTERN | 10 | GUELTIG_BIS | ja | Police, Nachtrag oder Kündigung eines Versicherungsvertrags (durch Urinet oder den Versicherer), darunter SUVA und andere UVG-Versicherer, Krankentaggeld, Pensionskasse (BVG) und Sachversicherungen. Prämienrechnungen = LIEFERANTENBELEG; AHV, IV, EO, ALV und FAK der Ausgleichskasse = BEHOERDE. |
| `BEHOERDE` | Behörde | finanz, frei | titel, datum, partner | INTERN | 10 | GESCHAEFTSJAHR | ja | Verfügung, Veranlagung, Rechnung oder Gutschrift eines Amtes, etwa Steuern, MWST, Handelsregister und Betreibungsamt, sowie AHV, IV, EO, ALV und FAK der Ausgleichskasse, auch Rückerstattungen; dazu eigene Eingaben wie Steuererklärung und MWST-Abrechnung. UVG- und Krankentaggeld-Versicherer = VERSICHERUNG. |
| `BANK` | Bank | frei | titel, datum, partner, zusatz.periode_von, zusatz.periode_bis | INTERN | 10 | GESCHAEFTSJAHR | ja | Kontoauszug, Zins- oder Gebührenabrechnung von Bank, PostFinance oder Kreditkarte für eine Periode, auch einzelne Belastungsanzeigen und Gutschriftsanzeigen der Bank (Zahlungseingang). Kredit- und Kontoverträge = VERTRAG, Rechnungen bleiben bei ihrer eigenen Art. |
| `PROJEKTDOKUMENT` | Projektdokument | – | titel, projekt | NORMAL | unbefristet | – | nein | Arbeitsunterlage zu einem Projekt ohne Buchungsbezug, etwa Plan, Konzept, Protokoll oder Foto. Offerten, Rechnungen und Verträge zum Projekt erhalten ihre eigene Art. |
| `KORRESPONDENZ` | Korrespondenz | frei | titel, datum, partner | INTERN | 10 | GESCHAEFTSJAHR | nein | Brief oder E-Mail ohne eigene Art, etwa Anfrage, Auftragsbestätigung, Mahnung ohne Gebühr oder Offerte eines Lieferanten. Passt ein Schreiben zu einer anderen Art (Rechnung, Vertrag, Verfügung), gilt diese. |
<!-- seed:dokumentarten:ende -->

Die Spalte «Beschreibung» (Aufgabe 2.1, entschieden von Fabian am 09.11.2026; Texte abgenommen von Fabian am 10.11.2026) nennt die Abgrenzungen zwischen den Arten (z. B. Sozialversicherungen AHV, IV, EO, ALV, FAK = `BEHOERDE`; UVG- und Krankentaggeld-Versicherer = `VERSICHERUNG`; Prämienrechnungen = `LIEFERANTENBELEG`) und fliesst in den KI-Prompt (3.2). Sie steht im Seed und in der Spalte `dokumentart.beschreibung` (Changeset `003-dokumentart-beschreibung`), ist ohne Personendaten und enthält kein `|`.

### Verarbeitung (`verarbeitung`)
- Tabelle `public.auftrag`; `AuftragPoller` (Quarkus Scheduler, alle 2 s) holt `LIMIT n FOR UPDATE SKIP LOCKED`, Parallelität `archiv.worker.parallel=2` (Stufe A: 1).
- Auftragstypen Phase 1: `VERARBEITEN`, `KI_VORSCHLAG`, `ARCHIVIEREN`, `CSV_VORSCHAU`, `CSV_UEBERNEHMEN`, `MANIFEST`, `PRUEFLAUF`.
- `VERARBEITEN`: Typ per Tika-Detektor → HEIC→JPEG (`heif-convert`) → Bilder→PDF (`img2pdf`; bei Handy-Erfassung aus den Arbeitsfassungen, Req 5.10) → `ocrmypdf --output-type pdfa-2 --rotate-pages --deskew -l deu+eng [--skip-text]` → Text via `pdftotext` (`dokument.volltext` behält `\f` als Seitentrenner von `pdftotext`; Seitenangaben und Auszugsprüfung der Wissensbasis beruhen darauf, Spec 04, Req 5.6) → Vorschaubilder (`pdftoppm -r 72`) → Swiss QR Code lesen (siehe «KI») → Folgeauftrag `KI_VORSCHLAG`, bei Generator-Dokumenten Status `ARCHIVIERUNG` und Auftrag `ARCHIVIEREN`.
- Digital signierte PDFs (Req 5.8): OCRmyPDF bricht ohne Freigabe ab; der Worker ruft es dann mit `--invalidate-digital-signatures` auf. Nur die PDF/A-Fassung verliert die Signatur, das Original bleibt unverändert.
- Keine PDF/A-Fassung möglich, z. B. verschlüsseltes PDF (Req 5.9): `pdfa_fehlt = true`, Fehlertext in `fehler`, Status `EINGANGSKORB`; den Text liest Tika, soweit möglich, und die KI läuft trotzdem.
- Zeitgrenzen je Auftragstyp (Req 5.6; Startwerte, konfigurierbar unter `archiv.auftrag.zeitgrenze.<typ>`, nach K5 zu prüfen):

| Typ | Zeitgrenze |
| --- | --- |
| `VERARBEITEN` | 10 min |
| `KI_VORSCHLAG` | 3 min |
| `ARCHIVIEREN` | 5 min |
| `CSV_VORSCHAU` | 5 min |
| `CSV_UEBERNEHMEN` | 10 min |
| `MANIFEST` | 15 min |
| `PRUEFLAUF` | 30 min je Teilauftrag (500 Dateien) |
| `WISSEN_INGEST` (Spec 04) | 3 min je Teilauftrag |
| `WISSEN_LINT` (Spec 04) | 3 min je Teilauftrag |
| `WISSEN_EMBEDDING` (Spec 04) | 10 min |
| `WISSEN_EMBEDDING_ALLE` (Spec 04) | 30 min je Teilauftrag (500 Seiten) |
| `WISSEN_EXPORT` (Spec 04) | 15 min |

- Externe Werkzeuge laufen per `ProcessBuilder` mit `waitFor(zeitgrenze)` und danach `destroyForcibly()`. Jede Ein- und Ausgabe in Java hat eine eigene Zeitgrenze unter der Zeitgrenze des Typs (JDBC `queryTimeout` bzw. `statement_timeout`, S3 `apiCallTimeout`), weil weder `orTimeout` noch ein Interrupt blockierende Ein- und Ausgabe sicher beendet (zweite Nachbesserung Review PR #41, Pflichten der Handler unter «Umsetzung»). Eine Überschreitung zählt als fehlgeschlagener Versuch.
- Lebenszeichen (Req 5.7): Der Worker schreibt während eines Auftrags alle 30 s `auftrag.lebenszeichen`. `AuftragAufraeumer` (jede Minute) gibt `LAEUFT`-Aufträge mit `lebenszeichen < now() - zeitgrenze(typ)` frei; das zählt als Fehlversuch nach Req 5.4: `versuche + 1`, `fehler = 'verwaist fehler-id=<uuid>'`, zurück auf `OFFEN` mit Wartezeit, nach dem dritten Versuch `FEHLER` (Aufgabe 2.5). Das Freigabe-UPDATE prüft das Alter des Lebenszeichens erneut.
- Wiederholung (Req 5.4): insgesamt 3 Versuche; nach dem n-ten Fehlschlag `nicht_vor = now() + 2^n Minuten`. Nach dem dritten: Auftrag `FEHLER`, Dokument `FEHLER` (bei `VERARBEITEN`) bzw. `ARCHIVIERUNG_FEHLER` (bei `ARCHIVIEREN`). `KI_VORSCHLAG` setzt nie `FEHLER`: ohne Antwort geht das Dokument ohne Vorschlag in den Eingangskorb.
- `wiederholen` legt einen neuen Auftrag mit `versuche = 0` an: aus `FEHLER` `VERARBEITEN` (Status `NEU`), aus `ARCHIVIERUNG_FEHLER` nur `ARCHIVIEREN` (Status `ARCHIVIERUNG`, Req 6.8).
- Umsetzung (Aufgabe 2.5):
  - `public.auftrag` per JDBC (`JdbcAuftragSchlange`), nicht als JPA-Entität: Die Hibernate-Session hängt am Mandanten, die Schlange gilt für alle. SQL immer mit `public.` qualifiziert, weil eine Verbindung in einer Transaktion auf dem Schema des Mandanten stehen kann; `einreihen` nimmt an der Transaktion des Aufrufers teil (z. B. Dokument und Auftrag `VERARBEITEN` zusammen).
  - `mandant` ist Pflicht (Changeset `005-auftrag-mandant-pflicht`, Req 1.6). `AuftragService.einreihen(typ, nutzlast)` nimmt den Mandanten aus `MandantKontext` oder der verifizierten Anmeldung, nie aus einem Parameter; ohne Mandant, bei Widerspruch oder für einen Typ ohne Handler bricht es ab. Die Nutzlast trägt nur UUIDs, Ganzzahlen und Wahrheitswerte (Req 16.7).
  - Holen in einer Anweisung (`with … for update of a skip locked` + `update … returning`), nur Typen mit Handler und nur Mandanten mit `aktiv`; Aufträge eines inaktiven Mandanten pausieren: Sie bleiben `OFFEN`, und eine fehlende Nachbearbeitung wird erst nachgeholt, wenn der Mandant wieder aktiv ist; verwaiste `LAEUFT`-Aufträge gibt der Aufräumer auch für inaktive Mandanten frei; geht dabei einer auf `FEHLER`, ruft er den Hook nur für einen aktiven Mandanten sofort, sonst holt die Nachbearbeitung ihn nach, sobald der Mandant wieder aktiv ist (dritte Nachbesserung Review PR #41). Jede spätere Änderung prüft den Anspruch (`id`, `versuche`, `LAEUFT`); wer ihn verloren hat (Aufräumer war schneller), ändert nichts und bricht ab.
  - Handler implementieren `AuftragHandler` (`typ()`, `ausfuehren(auftrag)`, optional `nachLetztemFehlversuch(auftrag, fehlerId)` für den Dokumentstatus nach dem dritten Versuch; der Hook erhält nur die Fehler-ID, keinen Meldungstext, muss idempotent sein und auf einen Interrupt reagieren). Ohne `archiv.auftrag.zeitgrenze.<TYP>` (Typ in Grossbuchstaben) startet das Archiv nicht.
  - `AuftragArbeiter` führt jeden Auftrag in einem eigenen Thread ohne Request-Kontext und ohne Transaktion in `MandantKontext.mit(mandant)` aus (der Scheduler aktiviert einen Request-Kontext, in dem `mit` abbricht); ein zweiter Thread je Auftrag schreibt das Lebenszeichen (`archiv.auftrag.lebenszeichen=30s`) und unterbricht den Handler bei der Zeitgrenze. Nachbesserung Review PR #41: Auftrag und Platz werden erst frei, wenn der Handler-Thread wirklich beendet ist. Bis dahin bleibt der Auftrag `LAEUFT`, und die Lebenszeichen laufen auch nach dem Abbruch weiter; erst danach speichert der Worker Abschluss oder Fehlversuch und ruft den Hook. Der Platz wird in einem `finally` erst nach dem Ende des Handlers frei, auch wenn das Speichern scheitert. So laufen nie mehr als `archiv.worker.parallel` Handler und Hooks (Speicherbudget Stufe A: je Platz zwei Threads, den Heap bestimmen die Handler). «Nie doppelt» gilt nur innerhalb einer JVM (zweite Nachbesserung Review PR #41): Der Worker führt die IDs, deren Handler- oder Hook-Thread noch lebt. Holt er eine solche ID erneut (der Aufräumer hat sie freigegeben), startet er sie nicht, sondern lässt sie ohne Lebenszeichen und ohne Platz `LAEUFT` («geparkt») und gibt sie erst nach dem Ende des Threads ohne Versuch frei. Geparkt statt sofort freigegeben, weil der Poller eine freigegebene ID alle 2 s wieder holen würde und weil ein Auftrag mit dauerhaft hängendem Thread so über «verwaist» nach dem dritten Versuch `FEHLER` wird (Req 5.4).
  - Hängender Handler: Endet ein Handler auch 2 × Zeitgrenze nach dem Start nicht (er ignoriert den Interrupt), schreibt der Worker einen Log-Eintrag `ERROR` mit Fehler-ID («hängend»), hört mit den Lebenszeichen auf und speichert nichts mehr; der Platz bleibt belegt, bis der Thread endet. Der Auftrag bleibt `LAEUFT`, bis der Aufräumer ihn eine Zeitgrenze nach dem letzten Lebenszeichen als verwaist freigibt (zählt als Fehlversuch). Der Worker selbst startet keinen neuen Versuch; in derselben JVM startet der nächste Versuch erst, wenn der hängende Thread endet (geparkt, siehe oben). Ein Worker in einer anderen JVM (z. B. Stufe B mit eigenem Worker-Container, Deploy mit Überlappung) kann den nächsten Versuch neben dem hängenden Thread starten, nach rund 3 × Zeitgrenze + Aufräumintervall + Wartezeit ab dem Start (2 × Zeitgrenze bis «hängend», eine weitere ohne Lebenszeichen bis «verwaist», bis zu einem Aufräumintervall bis zur Freigabe, dann die Wartezeit 2^n Minuten; frühestens 3 × Zeitgrenze + Wartezeit). Darum müssen Handler idempotent sein und die Pflichten unten einhalten. Ein dauerhaft hängender Handler belegt seinen Platz bis zum Neustart der JVM; mit `archiv.worker.parallel=1` (Stufe A) steht der Worker damit für alle Mandanten still. Signale dafür sind der `ERROR` «hängend» und die Meldung nach Req 16.5 (Dokumente bleiben in `VERARBEITUNG`); eine Kennzahl bzw. Detail-Health «hängende Threads» gehört zur Anbindung an das Monitoring in Welle 5 (dritte Nachbesserung Review PR #41).
  - Pflichten der Handler (ab Aufgabe 2.6, zweite Nachbesserung Review PR #41): (1) Jede Ein- und Ausgabe hat eine eigene Zeitgrenze unter der Zeitgrenze des Typs: JDBC `queryTimeout` bzw. `statement_timeout`, S3 `apiCallTimeout`, externe Prozesse `waitFor` und `destroyForcibly()`. (2) Ergebnisse schreibt ein Handler nur in einer Transaktion, die zuerst `AuftragService.anspruchSichern(auftrag)` aufruft: `select 1 from public.auftrag where id = ? and versuche = ? and status = 'LAEUFT' for update` sperrt die Zeile bis zum Commit; hält der Anspruch nicht mehr, wirft es `AuftragService.AnspruchVerloren`, und die Transaktion rollt zurück; ohne laufende Transaktion bricht es ab. Die Transaktion bleibt kurz (nur das Schreiben der Ergebnisse), weil Lebenszeichen und Aufräumer bis zum Commit auf die Sperre warten, höchstens 5 s (`JdbcAuftragSchlange.SPERR_WARTEZEIT_SEKUNDEN`, dritte Nachbesserung Review PR #41); danach gilt es wie «Datenbank kurz weg»: Das Lebenszeichen fällt für diesen Takt aus, der Aufräumer lässt den Auftrag für diesen Lauf aus und gibt die übrigen frei. Für den Hook `nachLetztemFehlversuch` gilt (1); er schreibt erst, wenn der Auftrag `FEHLER` ist, darum ohne Anspruch, aber idempotent. (3) Nach einer Unterbrechung kehrt ein Handler nie normal zurück, sondern wirft `InterruptedException` oder eine andere Ausnahme (dritte Nachbesserung Review PR #41); beim Herunterfahren wertet der Worker einen unterbrochenen Lauf ohnehin nie als Abschluss (siehe unten).
  - Verlorener Anspruch (Lebenszeichen trifft nicht mehr): Der Worker bricht den Handler ab und schreibt danach nichts mehr; der Platz bleibt belegt, bis der Handler endet.
  - Abschluss: Scheitert das Speichern von `FERTIG`, versucht der Worker es nach einem Lebenszeichen-Takt einmal erneut; scheitert es wieder, schreibt er `ERROR` mit Fehler-ID, der Auftrag bleibt `LAEUFT` und läuft nach dem Aufräumen erneut (Handler sind idempotent).
  - Nachbearbeitung dauerhaft (Changeset `006-auftrag-nachbearbeitung`, Spalte `nachbearbeitet`): Nach dem Hook setzt der Worker `nachbearbeitet = now()`. Fehlt der Vermerk bei einem `FEHLER`-Auftrag länger als die Zeitgrenze seines Typs nach `beendet` (Absturz zwischen Speichern und Hook, oder der Hook scheiterte), ruft der Aufräumer den Hook mit der Fehler-ID aus `fehler` erneut auf. Zweite Nachbesserung Review PR #41 (Changeset `007-auftrag-nachbearbeitung-versuche`, Klasse `Nachbearbeitung`): Der Hook belegt wie ein Handler einen Platz und läuft nicht, solange in dieser JVM ein Thread für die ID lebt. Der Worker ruft ihn im Platz des eben beendeten Handlers, der Aufräumer nur auf einem freien Platz (sonst ohne Zählung beim nächsten Aufräumen); beide warten höchstens die Zeitgrenze des Typs, Platz und ID bleiben belegt, bis der Hook-Thread endet (endet er eine weitere Zeitgrenze nach dem Abbruch nicht, `ERROR` mit Fehler-ID «hängend»). Scheitert der Hook (Ausnahme oder Zeitgrenze), erhöht der Worker `nachbearbeitung_versuche` und setzt `nachbearbeitung_nicht_vor = now() + 2^n Minuten`; vorher ruft ihn der Aufräumer nicht erneut. Nach dem fünften Fehlschlag gibt der Worker auf: genau ein `ERROR` mit Fehler-ID («Nachbearbeitung nach 5 Versuchen aufgegeben»), `nachbearbeitet` bleibt leer, und der Aufräumer holt den Auftrag nicht mehr; Req 16.5 meldet das Dokument weiter über seinen Status. Jedes neue `FEHLER` setzt den Zähler zurück. «Nie doppelt» gilt für den Hook wie für Handler nur innerhalb einer JVM: Ein Worker in einer anderen JVM kann ihn neben einem noch laufenden Hook-Thread erneut aufrufen (z. B. Deploy mit Überlappung); darum muss der Hook idempotent sein (dritte Nachbesserung Review PR #41).
  - `fehler` enthält nur Art und Fehler-ID: `fehlgeschlagen|zeitgrenze|verwaist fehler-id=<uuid>`; das Log nennt dieselbe ID und die Klassennamen der Ursachen, nie Meldungen (Req 16.7). Ein verwaister Auftrag zählt wie ein Fehlversuch mit Wartezeit und wird nach dem dritten Versuch `FEHLER` (Req 5.7 «als fehlgeschlagenen Versuch nach 5.4»).
  - Unlesbare Zeile: Holen und Aufräumen wandeln jede Zeile einzeln um. Lässt sich eine Nutzlast nicht lesen, geht nur dieser Auftrag auf `FEHLER` (`versuche + 1`, `fehler = 'unlesbar fehler-id=<uuid>'`, gleich `nachbearbeitet`, weil ohne Nutzlast kein Hook möglich ist), das Log nennt nur ID, Fehler-ID und Klassenname; die übrigen Zeilen laufen weiter. Scheitert schon das Speichern von `FEHLER` für diese Zeile, nennt das Log zusätzlich den SQLState, die Zeile bleibt, wie sie ist (das Aufräumen versucht es erneut), und die übrigen laufen ebenfalls weiter. Ein CHECK (`auftrag_nutzlast_ck`) lässt als Nutzlast nur ein JSON-Objekt (oder `null`) zu. Neue Aufträge entstehen nur über `AuftragService` mit `Nutzlast`; der JDBC-Weg liegt nur im Adapter (ArchUnit).
  - Poller (`archiv.worker.intervall=2s`) und Aufräumer (`archiv.auftrag.aufraeumen-intervall=1m`) laufen nur, wenn `archiv.rolle` `worker` enthält; im Profil `test` sind beide aus (`off`), die Tests rufen sie selbst. Beim geordneten Herunterfahren holt der Worker nichts mehr, unterbricht die Handler und wartet höchstens `archiv.worker.stopp-frist` (5 s). Endet ein Handler mit Fehler oder Abbruch in dieser Frist, geht sein Auftrag ohne Erhöhung von `versuche` zurück auf `OFFEN` (ein Deploy kostet keinen Versuch); endet er normal, ohne dass ihn der Stopp mitten in der Arbeit unterbrochen hat (er war schon fertig), wird er abgeschlossen. Kehrt ein unterbrochener Handler trotzdem normal zurück (er schluckt den Interrupt), gilt er nicht als fertig: Der Auftrag geht ebenfalls ohne Versuch zurück auf `OFFEN` (dritte Nachbesserung Review PR #41). Ein Handler, der nicht endet, lässt seinen Auftrag `LAEUFT`; der Aufräumer gibt ihn nach der Zeitgrenze frei.
- Überwachung (Req 16.5): `Ueberwachung` prüft alle 15 Minuten Dokumente, die länger als 1 Stunde in `VERARBEITUNG`, `FEHLER` oder `ARCHIVIERUNG_FEHLER` stehen, fehlende oder ungültige Manifeste (Req 15.8) und das Alter des letzten Sicherungs-Exports. Je Befund geht genau eine E-Mail an den Inhaber, nur mit IDs und Status (Tabelle `alarm`). Den Fehlschlag des Sicherungs-Workflows meldet zusätzlich der Workflow selbst.

### KI (`ki`)
- `MetadatenExtraktor` als LangChain4j-`@RegisterAiService` ohne Chat-Memory (`NoChatMemoryProviderSupplier`; sonst vermischen sich Belege im selben Request-Kontext), Antwort als Java-Record `MetadatenVorschlag` im JSON-Schema-Antwortformat (`response-format=json_schema`, `strict-json-schema=true`). Infomaniak lehnt den JSON-Modus (`json_object`) ab; einen Rückfall gibt es nicht, ohne gültige Antwort gilt Req 7.1 (ohne Vorschlag in den Eingangskorb). Spike 0.3: `docs/spikes/infomaniak-ai.md`.
- Driven Port `KiPort` mit Adapter gegen Infomaniak AI Services und Dev-Stub (feste Antworten); in Tests WireMock.
- Modell der Erfassung (Gate 0, entschieden von Fabian am 09.11.2026): `archiv.ki.erfassung-modell=google/gemma-4-31B-it`, getrennt von `archiv.ki.chat-modell` (Chat Spec 02, Standard für `archiv.wissen.modell` in Spec 04), weil Gate 0 nur den Metadaten-Vorschlag misst. Aufrufparameter wie im Gate-Lauf: `temperature` 0, `top_p` 1, `presence_penalty` und `frequency_penalty` 0, `json_schema` mit `strict`. Ein anderes Erfassungsmodell oder andere Parameter gelten erst nach einem neuen Golden-Lauf (Req 0.3, 0.9). `archiv.ki.erfassung-modell` ist ein Modellname, keine URL, und gehört nicht zu den URL-Schlüsseln der Startprüfung. gemma-4-31B-it nimmt bei Infomaniak höchstens 100'000 Eingabe-Tokens an (Modellliste 09.11.2026). Im Gate-Lauf brauchte ein Beleg im Mittel rund 1'300 Eingabe-Tokens, als Text (12'000 Zeichen) wie als Bild (Prototyp: alle Seiten in einem Seitenbild, lange Kante höchstens 2000 px, `Seitenbild`); sendet das Produkt mehrere Seitenbilder einzeln, ist die Grenze mit Aufgabe 3.2 neu zu prüfen.
- Genau ein Aufruf je Beleg (Req 7.1). Prompt (Deutsch): Rolle «Archivassistent für ein Schweizer KMU», Liste der Dokumentarten mit Beschreibung und Standard-Vertraulichkeit, Partner-Hinweis (Ordnerpfad, Absenderadresse), Werte aus dem Swiss QR Code, bis 5 Beispiele aus der Vorauswahl, Text gekürzt auf 12'000 Zeichen (Anfang + Ende).
- Feldregeln im Prompt (Fabian, 09.11.2026):
  - `art`: Sozialversicherungen (AHV-Ausgleichskassen, IV, EO, FAK; frz. caisse de compensation, ital. cassa di compensazione) sind BEHOERDE, auch bei Rückerstattungen und Gutschriften; UVG- und Krankentaggeld-Versicherer sind VERSICHERUNG. Prämienrechnungen von Versicherern und Mahnungen mit Mahngebühr oder Verzugszins sind LIEFERANTENBELEG, Mahnungen ohne Gebühr KORRESPONDENZ (entschieden von Fabian am 10.11.2026).
  - `zusatz.periode_von`/`zusatz.periode_bis` (BANK): Bei einzelnen Belastungs- und Gutschriftsanzeigen ist die Periode der Buchungstag (entschieden von Fabian am 10.11.2026; bisher in der Beschreibung von BANK).
  - `zusatz.periode` (LOHNABRECHNUNG): `JJJJ-MM` bei der monatlichen Lohnabrechnung, `JJJJ` beim jährlichen Lohnausweis (entschieden von Fabian am 10.11.2026; bisher «Periode im Format JJJJ-MM» in der Beschreibung).
  - `betrag`: Bei Offerten das einmalige Total; wiederkehrende Beträge (pro Monat oder Jahr) zählen nicht.
  - `belegnummer`: «Antrag» gehört zu den Beschriftungen der Belegnummer (Antrags-ID, Antragsnummer, z. B. bei Behördenschreiben).
- Bildweg (Req 7.1): Ist das Original ein Bild (JPEG, PNG, HEIC/HEIF, TIFF; Typ am Inhalt erkannt wie Req 3.3, nie an der Endung), geht statt des OCR-Texts das Bild ans Modell, auch bei der Nachfrage (Req 7.3). OCR läuft trotzdem für Volltext und Swiss QR Code. Im Prototyp (Golden-Lauf) gehen die von OCRmyPDF gerenderten Seiten ans Modell. Das Produkt sendet die gerenderten Seiten des PDF nach Req 5.1; bei der Handy-Erfassung entsteht es aus der Arbeitsfassung (Req 4.7, 5.10), sonst aus dem Original, auch bei mehreren Bildern als ein Dokument (Req 3.5). Der Prototyp hat ebenso die von OCRmyPDF gerenderten Seiten gemessen (ohne Zuschnitt); die Gate-Kennzahlen sind damit auf das Produkt übertragbar (entschieden von Fabian am 09.11.2026).
- Transport-Wiederholungen steuert das Produkt bewusst (eigener Client, Auftragsregel Req 5.4), nicht über `quarkus.langchain4j.openai.max-retries`: Dort bedeutet 1 in LangChain4j 1.19.3 eine Wiederholung (zwei Requests bei Timeout oder 5xx), 0 lehnt Quarkus ab. Der Prototyp nutzt deshalb ein eigenes `ChatModel` mit `maxRetries(0)` (`KiModell`).
- `MetadatenVorschlag(String art, String titel, String datum, String partnerName, String partnerUid, String partnerIban, String partnerEmail, String belegnummer, Richtung richtung, BigDecimal betrag, String waehrung, String faelligkeit, String qrReferenz, Vertraulichkeit vertraulichkeit, List<String> tags, Sicherheit sicherheit)`; `Sicherheit` ist ein Record mit je einem `Double` pro Feld.
  Spike 0.3: LangChain4j bildet `LocalDate` als Objekt `{year, month, day}` und `Map` als leeres Objekt ab; das Modell liess das Datum dann meist leer und `sicherheit` immer. Deshalb Datumsfelder als ISO-Text (`@Description("… als ISO-Datum JJJJ-MM-TT")`), Umwandlung in `LocalDate` in der Nachbearbeitung (ungültig → leer, Sicherheit 0).
- `BeispielVorauswahl` (Req 7.9, P10), deterministisch vor dem Aufruf, ohne zweiten KI-Aufruf:
  1. Merkmale aus Volltext und QR-Code: IBAN (Regex, Prüfziffer mod 97), UID (`CHE-\d{3}\.\d{3}\.\d{3}`), E-Mail-Adressen, Kreditor-IBAN des QR-Codes, Belegnummer-Muster (structure.md).
  2. `ki_beispiel` mit einem dieser Merkmale, sortiert nach Anzahl übereinstimmender Merkmale und dann nach `zeit` absteigend, höchstens 5.
  3. Ohne Treffer: die 5 neuesten Beispiele.
  Test Req 7.10: Korrektur bei Partner X speichern, danach enthält der Aufruf für einen Beleg mit der IBAN von X dieses Beispiel (WireMock prüft den Request).
- `SwissQrLeser` (Req 7.11, P11): Seiten mit `pdftoppm -r 300` rendern, QR mit ZXing decodieren, nur Payloads mit Kopf `SPC`; liest IBAN, Betrag, Währung, Referenz und Zahlungsempfänger. Diese Werte gehen in den Prompt und ersetzen nach der Antwort die KI-Werte (Sicherheit 1.0).
- Nachbearbeitung:
  - Belegnummer-Muster setzen oder bestätigen `art` nur, wenn der Mandant Aussteller ist: Die Kreditor-IBAN des QR-Codes oder eine UID oder IBAN im Briefkopf gehört zu `mandant.kennungen` (Req 7.5, 7.12; Testfall Lieferantenrechnung `2026-123` bleibt `LIEFERANTENBELEG`).
  - Betrag auf 2 Stellen; Belegdatum strikt nach dem Eingangsdatum → Sicherheit 0, der gleiche Tag nicht (Prototyp-Regel R3, entschieden von Fabian am 09.11.2026; ersetzt «Datum mehr als 1 Jahr in der Zukunft → Sicherheit 0»; Fälligkeiten nicht betroffen).
  - Prototyp-Regel E1, entschieden von Fabian am 09.11.2026: Liefert das Modell den Betrag 0 und gibt es keinen Betrag aus dem Swiss QR Code, wird der Betrag leer (Sicherheit unverändert); ein QR-Betrag, auch 0.00, bleibt vorrangig (Req 7.11).
  - Prototyp-Regel E2, entschieden von Fabian am 09.11.2026: Nach Belegnummer-Muster und QR-Umstufung wird `LIEFERANTENBELEG` zu `QUITTUNG` (Art-Sicherheit 0.5, Vertraulichkeit der End-Art), wenn die rohe Art und die Art nach diesen Regeln `LIEFERANTENBELEG` sind, kein Swiss QR Code gelesen wurde, `partnerIban` leer und die Fälligkeit leer ist. Hat das Muster die Art gesetzt, greift E2 nicht (Req 7.12 unberührt).
  - Produkt-Regel A1 (Auflage Gate 0, Fabian, 09.11.2026): Die Sicherheit der Belegnummer wird auf höchstens 0.7 begrenzt, damit sie unter der Schwelle (Startwert 0.8) immer «bitte prüfen» zeigt; ausgenommen, die Belegnummer entspricht einem Muster des Mandanten und der Mandant ist nach der Regel oben (`mandant.kennungen`) Aussteller (Req 7.5); bei Fremdbelegen im Muster des Mandanten (Req 7.12, `2026-123`) gilt die Begrenzung. Grund: In der Realitätsprüfung waren 7 von 20 Belegnummern falsch, alle mit hoher Sicherheit. Die Regel senkt nur Sicherheiten und kann keinen neuen Fehler ab der Schwelle erzeugen; sie gehört nicht zum gemessenen Stand `nb-2026-11-09`.
  - Fehlt die Vertraulichkeit, gilt der Standard der vorgeschlagenen Art (Req 7.2).
- Felder unter `archiv.ki.schwelle` (Startwert 0.8, nach Req 0.9 angehoben) markiert der Eingangskorb mit «bitte prüfen» (Req 7.6). Die Schwelle wirkt auf die Sicherheit nach der Nachbearbeitung (Req 0.9, 7.6).
- Korrektur (Req 7.7): Jede vom Vorschlag abweichende Bestätigung speichert ein `ki_beispiel` mit den Merkmalen aus Schritt 1. Die Korrekturquote je Feld (korrigierte / bestätigte Vorschläge, gleitend 90 Tage) ist Kennzahl unter `/q/metrics` (Req 16.4).
- `PartnerAbgleich`: exakte Treffer über E-Mail, UID, IBAN; sonst `similarity(name_normalisiert, :name) >= archiv.abgleich.aehnlichkeit` (Startwert 0.6, `pg_trgm`), bester Treffer; zwei Treffer mit Abstand < 0.1 → «unsicher».
- `KiVerbrauch` speichert Tokens (Ein/Aus) je Aufruf, Mandant, Zweck.

**Erkenntnisse aus dem Golden-Lauf (Aufgabe 0.5, `docs/spikes/golden-ergebnis.md`; Abstimmungsteil, vor Gate 0):**
- `art` als Enum der Dokumentart-Codes im Schema statt `String`: Mit `String` lieferte das Modell auch Werte wie `AUSGABE`.
- Der Prompt markiert die `eigene_arten` des Mandanten und nennt Regeln für fremde Belege; trotzdem stuft das Modell
  Lieferantenrechnungen oft als `AUSGANGSRECHNUNG` ein. Prototyp-Regeln: Nennt der Swiss QR Code eine IBAN ausserhalb
  `mandant.kennungen`, ist der Mandant nicht Aussteller, auch wenn Kopf oder Fuss seine Kennung zeigen. Eigene Art,
  Mandant nicht Aussteller → Sicherheit der Art 0 («bitte prüfen»), auch ohne Swiss QR Code. Bei fremdem
  QR-Zahlungsempfänger wird `AUSGANGSRECHNUNG` oder `OFFERTE` zu `LIEFERANTENBELEG` mit Sicherheit der Art 0.5
  («bitte prüfen») und der Standard-Vertraulichkeit von `LIEFERANTENBELEG` (der Wert des Modells gilt dann nicht);
  `LOHNABRECHNUNG` wird nie umgestuft. Das Belegnummer-Muster ändert die Art weiterhin nicht (Req 7.12). Entscheid
  Fabian, 0.5-Nachschärfen.
- Der QR-Zahlungsempfänger ersetzt `partnerName` und `partnerIban` nur bei fremden Belegen; bei eigenen Belegen ist er der Mandant.
- «Briefkopf» wurde im Prototyp vereinfacht als «UID oder IBAN des Mandanten irgendwo im Volltext» geprüft. **Nur Prototyp,
  für Welle 3 nicht übernehmen:** Lieferanten und Behörden drucken oft UID oder MWST-Nummer des Kunden; dann gälte der
  Mandant fälschlich als Aussteller und das Belegnummer-Muster würde einen fremden Beleg umstufen (verboten nach Req 7.12).
  Massgeblich sind der Absenderblock (Briefkopf) bzw. der Zahlungsempfänger im Swiss QR Code. Seit 0.5-Nachschärfen
  prüft der Prototyp enger: Ein Swiss QR Code mit fremder IBAN hat Vorrang (Mandant nicht Aussteller); sonst zählt nur
  der QR-Zahlungsempfänger mit Mandanten-IBAN oder eine Mandanten-IBAN/UID im Kopf- oder Fussbereich einer Seite
  (erste und letzte 10 nicht leere Zeilen; Seiten mit bis zu 20 nicht leeren Zeilen zählen ganz, eine Mandanten-IBAN
  als Gutschriftskonto in kurzen Briefen schliesst den Mandanten als Aussteller daher nicht aus), nicht mehr im
  Fliesstext. `mandant.kennungen.ibans` muss auch die
  QR-IBANs des Mandanten enthalten; sonst gelten eigene QR-Rechnungen mit QR-IBAN als «nicht Aussteller», auch bei
  eigener UID im Kopf, und werden zu `LIEFERANTENBELEG` umgestuft.
- OCR: `-l deu+fra+ita+eng` (fremdsprachige Belege); das offizielle OCRmyPDF-Image enthält weder `ita` noch poppler,
  daher ein abgeleitetes, versioniertes Image (`spikes/golden-lauf/ocr/Dockerfile`). Rohfotos liefern über Tesseract
  kaum brauchbaren Text; ein Seitenbild direkt an Mistral-Small-4 (K1) war bei Fotos deutlich besser (der Gate-Lauf mit
  gemma-4-31B-it nutzt denselben Bildweg für Fotos).
- Die Sicherheit je Feld trennt richtig und falsch kaum (falsche Felder oft 0.9–1.0); das Modell antwortet trotz
  `temperature=0` zwischen Läufen unterschiedlich (gemessen mit Mistral-Small-4). Mit gemma-4-31B-it waren vier Läufe
  auf dem Abstimmungsteil fast gleich; im Abstimmungsteil wie im Prüfteil blieb je genau ein falsches Feld, eine
  Belegnummer mit hoher Sicherheit (Abstimmungsteil 1.0, Prüfteil ≥ 0.8; `docs/spikes/golden-ergebnis.md`).

**Normalisierung** (`gemeinsam`, gilt für Gate-Vergleich Req 0.7, Partner-Abgleich und CSV-Import):
- Name: Unicode NFKC, Kleinschreibung, `ä→ae`, `ö→oe`, `ü→ue`, `ß→ss`, übrige Akzente entfernt (`é→e`), `&` → `und`, Satzzeichen → Leerzeichen, Rechtsformen als ganzes Wort entfernt (`ag`, `gmbh`, `sa`, `sarl`, `sagl`, `kg`, `klg`, `verein`, `genossenschaft`, `stiftung`, `ltd`, `inc`), Mehrfachleerzeichen zusammengefasst, getrimmt.
- PLZ nur Ziffern; E-Mail klein und getrimmt; IBAN ohne Leerzeichen, gross.

#### Profil `lokal` (Req 16.20–16.22, ADR-012)
Nur für den Entwicklerrechner: Das Sprachmodell läuft in LM Studio (OpenAI-kompatibel). Der Adapter
hinter `KiPort` bleibt derselbe wie für Infomaniak, es wechseln nur Basis-URL und Modellname.
Dev Services, Ablage und OCR laufen ebenfalls nur auf dem Entwicklerrechner (Req 16.7).

```properties
# erbt von dev: Dev Services, Stubs für Ablage und Integration
%lokal.quarkus.config.profile.parent=dev
# Dev-Stub von KiPort für das Sprachmodell aus (Schalter per Konfiguration, nicht per Build-Profil)
%dev.archiv.ki.stub=true
%lokal.archiv.ki.stub=false
# echter OCR-Adapter (OCRmyPDF als lokales Docker-Image wie in Aufgabe 0.5), damit das
# Sprachmodell den echten Volltext erhält (Req 7.1)
%dev.archiv.ocr.stub=true
%lokal.archiv.ocr.stub=false
# LM Studio, Port beim Start ausdrücklich gesetzt (lms server start --port 1234)
%lokal.archiv.ki.basis-url=http://localhost:1234/v1
# Name aus lms load <model_key> --identifier archiv-lokal
%lokal.archiv.ki.chat-modell=archiv-lokal
%lokal.archiv.ki.erfassung-modell=archiv-lokal
```

- Start: `mvn quarkus:dev -Dquarkus.profile=lokal`. Kein Infomaniak-Token, keine Produkt-ID.
  Ist in LM Studio die Authentifizierung eingeschaltet, liegt deren Token nur als Datei vor (Regel 7).
- Embeddings und Reranker: Stub ohne Vektoren (Spec 02). Vektoren aus einem anderen Modell kommen nie
  in eine Datenbank, die mit Infomaniak-Vektoren arbeitet.
- Modell: Startwert Mistral Small 3.2 24B Instruct (GGUF, Katalog `mistralai/mistral-small-3.2`),
  Alternative Ministral 3 14B Instruct (`mistralai/ministral-3-14b`). GGUF statt MLX und kein
  Reasoning-Modell, weil dort `json_schema` teils ignoriert wird oder das JSON in
  `reasoning_content` landet (ADR-012). Lokales Modell für die Erfassung seit dem Wechsel auf gemma-4-31B-it:
  [offen: lokales Modell für die Erfassung – Entscheid vor Welle 3] (ADR-012, Nachtrag 09.11.2026).
- Antwortformat wie bei Infomaniak (`json_schema`, `json_object` lehnt LM Studio ab). Wie streng
  LM Studio `strict` durchsetzt (auch für nullable/`anyOf` im Schema von `MetadatenVorschlag`), ist
  nicht dokumentiert; Aufgabe 1.11 hält es im README fest, die Validierung nach Req 7.3 gilt unverändert.
- Bilder: Laut Doku des Python-SDK unterstützt der Server JPEG, PNG und WebP; für die OpenAI-API nicht
  dokumentiert. PNG oder JPEG senden (WebP-Data-URI-Fehler #1752); HEIC vorher wandeln (Verarbeitung).

**Startprüfung `KiBasisUrlPruefung`** (Adapter `ki`, `@Observes StartupEvent`, vor dem ersten KI-Aufruf):
- `archiv.ki.basis-url` und `archiv.ki.reranker-url` (Spike 0.3) als URI lesen; für Embeddings gilt
  die Prüfung, sobald Spec 02 eigene Schlüssel einführt. Zulässig sind:

| Profil | Schema | Host |
| --- | --- | --- |
| alle | `https` | `api.infomaniak.com` (Pfad aus der Kontoübersicht, nicht geprüft) |
| `lokal` | `http` | `localhost`, `127.0.0.1` (Port frei) |
| `test` (WireMock) | `http` | `localhost`, `127.0.0.1`, nur mit dem Port, den die WireMock-Testressource beim Start setzt; Port 1234 (LM Studio) immer abgelehnt |

- Ist der zugehörige Stub aktiv (`archiv.ki.stub=true`, z. B. in `dev`; Reranker in `lokal`), entfällt
  die Prüfung dieser URL; eine leere URL ist dann zulässig.
- Hosts werden klein geschrieben und exakt verglichen, ohne Suffix-Treffer
  (`api.infomaniak.com.example.org` ist abgelehnt); URIs mit Benutzerangabe (`user@host`) sind abgelehnt.
- Alles andere bricht den Start ab (Req 16.21); die Meldung nennt nur den abgelehnten Host und das
  aktive Profil.
- `lokal` zusammen mit `prod` bricht den Start ab (Req 16.22).
- Die Prüflogik ist eine reine Funktion (Profilnamen und URI als Eingabe) und wird als Unit-Test ohne
  aktives Quarkus-Profil `lokal` geprüft; die CI aktiviert `lokal` nie.
- `GoldenLauf` und `GoldenDatensatzIT` laufen nur per HTTPS gegen `api.infomaniak.com`, sonst Abbruch
  vor dem ersten Beleg (Req 16.22, umgesetzt in Aufgabe 0.5).

**Umsetzung Aufgabe 1.11 (10.11.2026), Präzisierungen und Abweichungen** (vorsichtigere Auslegung, im PR unter
«Offen für Fabian»):
- `lokal` erbt per `%lokal.quarkus.config.profile.parent=dev`; Quarkus wertet das nur aus, wenn das Profil über
  `quarkus.profile` gesetzt ist (`-Dquarkus.profile=lokal`), zur Laufzeit belegt mit «Profiles lokal,dev activated».
- Eigene Stub-Schalter `archiv.ki.embedding-stub` und `archiv.ki.reranker-stub` (Standard `${archiv.ki.stub}`, in
  `lokal` `true`); der Reranker-Schalter steuert die Prüfung von `archiv.ki.reranker-url`. Die Ports selbst kommen mit
  Spec 02. In `lokal` sind `archiv.ki.product-id` und `archiv.ki.reranker-url` leer.
- Geprüft wird zusätzlich `quarkus.langchain4j.openai.base-url` (nur der Default-Client von LangChain4j), weil
  dieser Client sonst an der Prüfung vorbei einen Endpunkt erhalten könnte. Benannte Clients
  (`quarkus.langchain4j.openai."<name>".base-url`, Standardwert `https://api.openai.com/v1/`) prüft 1.11 nicht; es
  gibt noch keinen. Die Prüfung der wirksamen Konfiguration aller Clients folgt in Aufgabe 3.2.
- Schema und Host werden ohne Unterscheidung von Gross- und Kleinschreibung verglichen (`HTTPS://…` ist zulässig).
- Bei aktivem Stub entfällt die Prüfung nur für eine **leere** URL; eine gesetzte URL wird auch dann geprüft.
- Ein gepacktes Artefakt (Quarkus `LaunchMode.NORMAL`) gilt immer als `prod`; `-Dquarkus.profile=lokal` bricht dort
  ab (Req 16.22). Ist `test` aktiv, gilt die Regel von `test` (strenger als `lokal`).
- `localhost` und `127.0.0.1` nur per `http`; IPv6 (`[::1]`) ist abgelehnt.
- Port der WireMock-Testressource: Schlüssel `archiv.ki.wiremock-port` (in `test` fest `8089`, die Test-URLs
  verwenden ihn). Die Testressource setzt ihn ab Aufgabe 3.2 beim Start; bis dahin deckt der Unit-Test den Fall mit dem
  Port als Eingabe ab.
- OCR in `lokal`: Die Port-Auswahl aus Aufgabe 1.2 wirft erst beim ersten Zugriff, eine Startprüfung gibt es nicht.
  `lokal` startet darum mit `archiv.ocr.stub=false`, auch bevor der OCR-Adapter aus 2.6 besteht.

### Kontakt und Importe (`kontakt`, `importe`)
CSV-Import-Ablauf:

```mermaid
sequenceDiagram
  actor F as Fabian
  participant UI as PWA
  participant API
  participant W as Worker
  participant DB as Schema m_urinet
  F->>UI: CSV aus 21.CRM hochladen
  UI->>API: POST /importe/crm (art=kontakt)
  API->>API: SHA-256, Zeichensatz (ICU4J), Trennzeichen
  API->>DB: import_lauf (VORSCHAU_AUSSTEHEND)
  alt Spalten unbekannt
    API-->>UI: 200 {spalten, vorschlagZuordnung}
    F->>UI: Spalten zuordnen
    UI->>API: PUT /importe/crm/zuordnung
  end
  API->>W: Auftrag CSV_VORSCHAU
  W->>DB: Zeilen abgleichen (ID → E-Mail → Name+PLZ)
  W->>DB: import_zeile (NEU/GEAENDERT/UNVERAENDERT/DUBLETTE/FEHLER)
  UI->>API: GET /importe/{id}
  F->>UI: Dubletten entscheiden, «Übernehmen»
  UI->>API: POST /importe/{id}/uebernehmen {entscheide}
  API->>W: Auftrag CSV_UEBERNEHMEN
  W->>DB: eine Transaktion: insert/update kontakt, Protokoll
```

| Methode | Pfad | Zweck | Recht |
| --- | --- | --- | --- |
| POST | `/importe/crm?art=kontakt\|verkaufschance&vollexport=true\|false` | CSV hochladen; 422 `CSV_UNGUELTIG`, `CSV_LEER` | A12 |
| GET/PUT | `/importe/crm/zuordnung?art=…` | gespeicherte Spaltenzuordnung | A12 |
| GET | `/importe/{id}` | Lauf mit Zählern und Zeilen (paginiert) | A12 |
| POST | `/importe/{id}/uebernehmen` | `{dublettenEntscheide: {zeile: "NEU"\|"ZUSAMMENFUEHREN:<kontaktId>"\|"UEBERSPRINGEN"}}` | A12 |
| GET | `/importe` | Liste der Läufe | A12 |
| GET | `/kontakte?q=` | Kontakte suchen (Partnerauswahl) | A6 |
| POST/PATCH | `/kontakte` | Kontakt manuell anlegen/ändern | A12 |

Regeln des Abgleichs (Req 13), Normalisierung siehe «KI»:
- Kopfzeile: Hash der normalisierten Spaltennamen gegen `import_zuordnung.kopf_hash`; neu oder abweichend → Status `ZUORDNUNG_NOETIG` und Zuordnungsdialog (Req 13.2).
- Reihenfolge (Req 13.4): `crm_id` → `lower(email)` → (`name_normalisiert`, `plz`) exakt; ein exakter Treffer zählt als Treffer.
- «Mögliche Dublette» (Req 13.5): mehrere Treffer auf einer Stufe; Stufen widersprechen sich (z. B. `crm_id` → Kontakt A, E-Mail → Kontakt B); oder kein exakter Treffer, aber `similarity(name_normalisiert) >= archiv.abgleich.aehnlichkeit` (Startwert 0.6).
- Übernehmen (Req 13.6): Gefüllte CSV-Zellen gewinnen, leere Zellen überschreiben nichts; alles in einer Transaktion.
- Nur bei `vollexport=true` erhalten Kontakte mit `crm_id`, die im CSV fehlen, `nicht_im_export = true`; gelöscht wird nie (Req 13.7).
- Verkaufschancen (Req 13.9): Kontakt über die Spalte Kontakt-ID aus 21.CRM (`kontakt.crm_id`), Aktualisierung über `verkaufschance.crm_id`.
- «Fehlerhaft» (Req 13.3, 13.10), für alle Dateien: Spaltenzahl nach dem Parsen (RFC 4180) ≠ Kopfzeile oder `name` leer; die Zeile wird nicht übernommen und in der Vorschau gezählt.
- Ablehnung: keine Spalte für `name` (Profil «21.CRM»: `Name` oder `Organisation`) oder mehr als 10'000 Zeilen → 422 `CSV_UNGUELTIG` (Req 13.10); keine Datenzeilen → 422 `CSV_LEER` «keine Datenzeilen», kein Kontakt wird geändert oder gekennzeichnet (Req 13.11).

Profil «21.CRM» (K4, `docs/spikes/21crm-export.md`): Erkennt das Archiv die Kopfzeile des 21.CRM-Gesamtexports (`ADDRESS.csv`: Windows-1252, Semikolon, Felder nur bei Bedarf in Anführungszeichen nach RFC 4180, Datensätze mit LF, Spalte `Sekretär(in)` doppelt), schlägt der Zuordnungsdialog (Req 13.2) das Profil vor; nach einmaliger Bestätigung gilt es für den Mandanten wie jede gespeicherte Zuordnung. Regeln des Profils: `crm_id` = `GGUID`; `typ` aus `Kontakt ist eine Organisation`; `name` = `Organisation` bei Organisationen, sonst `Name`; `vorname` = `Vorname`; `name_normalisiert` bei Personen aus Vorname und Name; `strasse`/`plz`/`ort` aus den Feldern «(Geschäft)»; `email` gemäss Index `Standard-E-Mail` (1 Geschäft, 3 Privat, 5 Zentrale), sonst das erste gefüllte aus Geschäft, Zentrale, Geschäft 2, Privat; `telefon` gemäss `Standard-Telefon 1` (4 Geschäft, 10 Zentrale), sonst Geschäft, Zentrale, Mobil (Geschäft); Zeilen mit `Ist gelöscht = true` werden übersprungen; Zeitstempel `TT.MM.JJ HH:MM:SS` als Europe/Zurich. Verkaufschancen (`GWOPPORTUNITY.csv`): `crm_id` = `GGUID`, Kontakt über `ACCOUNTGUID`, `titel` = `Name`, `phase` = `Phase`, `betrag` = `Gesamt`, `datum` = `Beginn` [offen: Zuordnung der Verkaufschancen aus 21.CRM mit einem Nachexport mit Testdaten bestätigen – Entscheid vor Welle 4]. Die Zuordnung wird nach Spaltenposition gespeichert, weil Spaltennamen doppelt vorkommen können.

Zuordnungsvorschlag für andere CSV-Dateien: Spaltenüberschriften gegen Synonymliste (`Firma|Organisation|Name|Nachname → name`, `PLZ|Postleitzahl → plz`, `E-Mail|Mail → email`, `ID|GGUID|Nummer|Kontakt-Nr → crm_id` …).

### Suche (`suche`)
- Spalte `volltext_tsv tsvector GENERATED ALWAYS AS (setweight(to_tsvector('german', coalesce(titel,'')),'A') || setweight(to_tsvector('german', coalesce(partner_name,'')),'B') || setweight(to_tsvector('german', archiv_tags_text(tags)),'B') || setweight(to_tsvector('german', coalesce(volltext,'')),'C')) STORED`, GIN-Index. `archiv_tags_text(text[])` ist eine als `IMMUTABLE` deklarierte Hilfsfunktion (`array_to_string` ist nur `STABLE` und in generierten Spalten nicht erlaubt), Req 14.1.
- `websearch_to_tsquery('german', :q)`, Rang `ts_rank_cd`, Auszug `ts_headline` (max. 2 Fragmente).
- Belegnummer (Req 14.7): Entspricht `:q` exakt einer `dokument_finanz.belegnummer`, steht dieses Dokument vor den Volltexttreffern; Filter `belegnummer=` ist exakt.
- Filter als dynamische Kriterien (Panache/Criteria): Art, Partner, Zeitraum (Belegdatum), Betrag von–bis, Richtung, Tags, Status, Geschäftsjahr, Belegnummer (Req 14.2). Indizes: `dokument(art)`, `(partner_id)`, `(datum)`, `(geschaeftsjahr)`, `(status)`, `gin(tags)`, `dokument_finanz(betrag)`, `(richtung)`, `(belegnummer)`.
- Abschnitt «Wissen» (Spec 04 ergänzt, Spec 04, Req 9.4): Die Suchseite zeigt passende Wissensseiten mit Titel, Typ und Beschreibung in einem eigenen Abschnitt über den Dokumenttreffern; sie ruft dazu zusätzlich `GET /api/v1/wissensseiten?q=` auf, `GET /api/v1/dokumente?q=` bleibt unverändert.
- Jede Abfrage läuft durch `VertraulichkeitFilter` und den Mandanten-Kontext (Req 14.5). Leistungsziel 14.3 nur auf Stufe B (Req 16.16): [offen: Messbedingungen nach VPS-Grösse K5 – Entscheid vor Welle 5].

### Protokoll und Manifest (`protokoll`)
- `ZugriffProtokollInterceptor` (`@Protokolliert(aktion=…)`) an Resource-Methoden schreibt **synchron in derselben Transaktion** wie die Aktion. Scheitert das Schreiben, wird die Transaktion zurückgerollt und die Aktion nicht ausgeführt (Req 10.8). Bei Downloads wird geschrieben, bevor die vorsignierte URL ausgegeben wird.
- IP aus `X-Real-Ip` (von Traefik gesetzt, siehe «Betrieb auf der VPS»); fehlt der Header, bleibt `ip` leer.
- Protokollierte Endpunkte (Req 10.2, 10.7); Listen und Suche ohne Dokumentbezug werden nicht einzeln protokolliert:

| Aktion | Endpunkt bzw. Auslöser |
| --- | --- |
| `ERFASSEN` | `POST /dokumente`, `POST /dokumente/generator` |
| `ANZEIGEN` | `GET /dokumente/{id}`, `GET /dokumente/{id}/vorschau` |
| `HERUNTERLADEN` | `GET /dokumente/{id}/datei` |
| `AENDERN` | `PATCH /dokumente/{id}` |
| `BESTAETIGEN` | `POST /dokumente/{id}/bestaetigen` |
| `VERWERFEN` | `POST /dokumente/{id}/verwerfen` (mit SHA-256 und Grund) |
| `WIEDERHOLEN` | `POST /dokumente/{id}/wiederholen` |
| `EXPORTIEREN` | Jahresexport (`02-abgleich-chat`); OKF-Export der Wissensbasis (Spec 04, mit Export-ID und SHA-256) |
| `VERWEIGERT` | jede 403 oder 404 nach Req 1.4, 2.3 oder 2.6 an den obigen Endpunkten, an A11–A14 und an den Endpunkten der Wissensbasis (Spec 04), mit Fehlercode |
| `SPERRE_KONFLIKT` | Systemaktion bei Req 9.7 (altes und neues Datum) |
| `REGEL_AENDERN` | `PATCH /dokumentarten/{code}`, wenn sich Jahre, «ab» oder `gesetzliche_pflicht` ändern (Req 9.10, entschieden von Fabian am 10.11.2026); ohne Dokument-ID, mit Code der Art und alter und neuer Regel |
| `WISSEN_ANZEIGEN`, `WISSEN_ANNEHMEN`, `WISSEN_VERWERFEN`, `WISSEN_INGEST`, `WISSEN_LINT` | Endpunkte der Wissensbasis (Spec 04, design.md, «Zugriffsprotokoll»); `WISSEN_ANZEIGEN`, `WISSEN_ANNEHMEN`, `WISSEN_VERWERFEN` mit Seiten- bzw. Entwurfs-ID statt Dokument-ID, `WISSEN_INGEST` mit Dokument-ID |

- `zugriff_protokoll` und `dokument_version` sind INSERT-only: Trigger `BEFORE UPDATE OR DELETE → RAISE EXCEPTION`, und die DB-Rolle `archiv_app` hat auf beiden nur `INSERT` und `SELECT` (Req 10.3). Aufbewahrung: [offen: Aufbewahrungsfrist des Zugriffsprotokolls – Entscheid vor Welle 5].
- Umsetzung (Aufgabe 1.6; `dokument_version` folgt mit 2.2):
  - Changeset `db/mandant/changesets/002-zugriff-protokoll.xml`: Trigger `BEFORE UPDATE OR DELETE` je Zeile und
    `BEFORE TRUNCATE` je Anweisung mit «nur Einfügen erlaubt»; er gilt auch für den Eigentümer `archiv_owner`. Die
    Rechte entzieht die Provisionierung nach jeder Migration (`NUR_EINFUEGEN` in `LiquibaseMandantDatenbank`), also
    auch gegen die Default Privileges und für bestehende Schemas.
  - `@Protokolliert` (`protokoll.application`) an Methode oder Klasse; der Interceptor schreibt per JDBC über den Port
    `ZugriffProtokoll` vor der Aktion und tritt der laufenden Transaktion bei, sonst öffnet er eine eigene. `benutzer`
    ist der Principal (Keycloak-`sub`, bei API-Tokens ab Aufgabe 1.5 `token:<id>`), `rolle` die Archivrolle oder leer,
    `dokument_id` der Pfadparameter `{id}`.
  - `X-Real-Ip` gilt nur als einzelne IPv4- oder IPv6-Adresse (ohne Namensauflösung); sonst bleibt `ip` leer, die
    Anfrage läuft weiter.
  - `VERWEIGERT` schreibt `ProtokollVerweigerungMelder` (`sicherheit`) in einer eigenen Transaktion
    (`REQUIRES_NEW`), damit der Eintrag den Abbruch übersteht; `details` enthält `fehlercode` und als `grund` die
    Zeile der Rechtematrix (z. B. `A7`), falls bekannt. Bei `MANDANT_FEHLT` gibt es kein Mandanten-Schema: nur ein
    Log-Eintrag mit IDs und Fehlercode. Scheitert das Schreiben des `VERWEIGERT`-Eintrags, bleibt die Antwort
    403 bzw. 404.
  - `EndpunktWaechterTest` prüft, dass jeder Endpunkt mit einer Aktion aus der Tabelle die passende
    `@Protokolliert`-Aktion trägt (A2–A4 nur mit `{id}`, `…/datei` → `HERUNTERLADEN`).

**Manifest** (Req 15, P2):
- Lauf täglich um 03:30 Europe/Zurich (`@Scheduled(cron = "0 30 3 * * ?", timeZone = "Europe/Zurich")`) und einmal beim Start der API: je Mandant ein Auftrag `MANIFEST`.
- Der Auftrag erzeugt für jedes fehlende Datum D bis gestern ein Manifest, in Datumsreihenfolge, beginnend nach dem letzten vorhandenen bzw. beim Anlagedatum des Mandanten (Req 15.6). `manifest.datum` ist Primärschlüssel; ein vorhandenes Manifest wird nie neu erzeugt (idempotent).
- Fenster `[von, bis)`: `von` = `bis` des Vorgängers (erstes Manifest: `von = null`, `vorher_sha256 = null`), `bis` = Beginn von D+1 in Europe/Zurich. Die Tagesgrenzen kommen aus `ZonedDateTime`, Umstellungstage haben also 23 bzw. 25 Stunden; der Lauf um 03:30 liegt ausserhalb der Umstellungsstunde. Testfälle 29.03.2026 und 25.10.2026 (Req 15.7).
- Auch Tage ohne archivierte Dateien erhalten ein Manifest (Protokoll-Hash, lückenlose Kette).
- Inhalt (Req 15.1): alle Dateien (Originale, Arbeitsfassungen, PDF/A) der Dokumente mit `dokument.archiviert` im Fenster, dazu SHA-256 und Anzahl der Zeilen von `zugriff_protokoll` und `dokument_version` mit `zeit` im Fenster (Spec 04, Req 4.6 ergänzt unter `protokolle` die Tabellen `wissensseite_version` und `wissens_log`). Die Zeilen werden nach `id` sortiert, kanonisch serialisiert und gehasht.

```json
{ "mandant": "urinet", "datum": "2026-11-07", "von": "…Z", "bis": "…Z",
  "vorher_sha256": "…",
  "eintraege": [ {"dokument": "…", "variante": "original", "nr": 1, "sha256": "…", "s3_version": "…"},
                 {"dokument": "…", "variante": "pdfa", "nr": 1, "sha256": "…", "s3_version": "…"} ],
  "anzahl": 2,
  "protokolle": { "zugriff_protokoll": {"anzahl": 41, "sha256": "…"},
                  "dokument_version": {"anzahl": 3, "sha256": "…"} } }
```
- Kanonische Form nach RFC 8785 (JCS); Signatur Ed25519 mit `/run/secrets/archiv_manifest_key` (`.sig`); RFC-3161-Zeitstempel über SHA-256 von Manifest und Signatur (`.tsr`), **Pflicht** (Req 15.3). TSA nicht erreichbar → Wiederholung nach Auftragsregel; ohne Token gilt das Manifest als fehlend (Alarm Req 15.8). Anbieter: [offen: Schweizer TSA-Anbieter – Entscheid vor Welle 5].
- Startprüfung: Im Profil `prod` startet die API nicht ohne `archiv.tsa.url`, damit nie ohne Zeitstempel produktiv archiviert wird (Req 15.3).
- Ablage `manifest/<D>.json`, `.sig`, `.tsr` mit Object Lock im Modus COMPLIANCE (Req 6.11; Option B, ADR-007, Nachtrag 09.11.2026; Verlängern nach Req 15.9 bleibt möglich [T 4e]) bis zum spätesten `aufbewahren_bis` der enthaltenen Dateien; enthält es keine Datei mit Frist, 10 Jahre wie Req 6.9 (Req 15.4).
- Sperrmodus von `manifest/` per Konfiguration (Aufgabe 2.3; entschieden von Fabian am 09.11.2026): Standard COMPLIANCE. Ab Stufe B (Req 16.17; dort kommen echte Belege ins Archiv) gilt COMPLIANCE fest mit der Frist nach Req 15.4; API und Worker brechen den Start ab, falls dort ein anderer Modus oder eine kürzere Frist konfiguriert ist. Abweichen (GOVERNANCE oder kürzere Frist) dürfen nur Tests, die lokale Entwicklung und die Stufenwerte der Stufe A unter `deploy/infomaniak/` (nur Testdaten, Req 16.15), damit Test-Buckets löschbar bleiben (Req 6.11). Die Regel hängt nicht am Profil `prod`, weil Stufe A mit denselben Images, derselben `compose.yml` und demselben Deploy-Workflow läuft wie Stufe B. Die Stufe erkennt die Startprüfung am Konfigurationswert `archiv.stufe` (`A` | `B`) in den Stufenwerten unter `deploy/infomaniak/`; fehlt er, gilt `B`, damit COMPLIANCE im Zweifel erzwungen wird. Stufe A setzt ausdrücklich `archiv.stufe=A`, ebenso die Konfiguration der Tests und der lokalen Entwicklung.
- Buckets und Sperrfrist der Stufe A (entschieden von Fabian am 09.11.2026): Der Bucket-Name ist `<archiv.ablage.bucket-praefix><code>`, Standard `urinet-archiv-`, auf Stufe A `urinet-archiv-a-`; den produktiven Bucket `urinet-archiv-urinet` gibt es erst auf Stufe B (Aufgabe 5.4). Mit `archiv.stufe=A` sperrt das Archiv auch Dokumente unter `archiv/` höchstens 1 Tag (GOVERNANCE, `archiv.ablage.max-sperrfrist=P1D`, sonst nicht gesetzt), nach demselben Muster wie `manifest/` (Aufgabe 2.3). Ab Stufe B bricht der Start ab, wenn `archiv.ablage.max-sperrfrist` gesetzt ist oder der Präfix `urinet-archiv-a-` lautet (Req 6.11). Beim Wechsel (Aufgabe 5.11) werden frühestens 1 Tag nach dem letzten Upload alle Versionen in den Test-Buckets mit dem App-Schlüssel gelöscht, ohne Bypass-Header, danach die Buckets selbst; der Inhaber-Zugang wird dafür nicht gebraucht.
- **Prüflauf** monatlich (Auftrag `PRUEFLAUF` in Teilaufträgen):
  - Je archivierte Datei `GetObject` mit der gespeicherten Versions-ID, SHA-256 stromweise, Vergleich mit Datenbank und Manifest (Req 15.5).
  - `ListObjectVersions(prefix = archiv/)`: Versionen ohne Eintrag in der Datenbank und Löschmarken sind Abweichungen (Req 15.10).
  - Sperren unbefristeter Dokumente und von Manifesten mit weniger als 12 Monaten Restlaufzeit: +10 Jahre (Req 15.9).
  - Bericht an den Inhaber, nur IDs.
  - Massgeblich ist SHA-256. Nach einer Wiederherstellung in einen neuen Bucket werden die Versions-IDs neu erfasst, die Manifeste bleiben gültig.

### Sicherung und Wiederherstellung (Req 16.2, 16.3, 16.8, P4)
- `archiv-sicherung` auf der VPS (täglich, vor dem Abholfenster): `pg_dump` der Archiv- und der Keycloak-Datenbank, Realm-Export, inkrementelle Kopie des Buckets (Originale, Arbeitsfassungen, PDF/A, Manifeste; neue Objekte seit dem letzten Export, mit Liste der Versions-IDs) und Manifest-Schlüssel. Den restic-Schlüssel hält der Server nicht (siehe «Secrets»). Alles wird asymmetrisch an den öffentlichen Sicherungsschlüssel verschlüsselt (z. B. `age`); der Server hält nur den öffentlichen Schlüssel.
- Der zentrale Sicherungs-Workflow (maintenance-Muster) holt den Export per SSH und schreibt ihn mit restic zur Zweitkopie ([offen: zweiter Schweizer Backup-Anbieter – Entscheid vor Welle 5]); Aufbewahrung pro App 30 Tages-, 12 Monats- und 10 Jahresstände. `backup-gate.sh` prüft zusätzlich: beide Dumps vorhanden, Bucket-Export vollständig (Anzahl Objekte = Einträge in der Datenbank).
- Restore-Probe, automatisiert und wiederkehrend ([offen: Rhythmus der Restore-Probe – Entscheid vor Welle 5]): Sie stellt in einer getrennten Umgebung in der Schweiz (Compose-Projekt `archiv-probe` auf der VPS, Probe-Bucket im Public-Cloud-Projekt) Datenbanken und Dateien wieder her, meldet einen Testbenutzer an, öffnet ein Dokument und endet mit grünem Prüflauf. [offen: ob die Restore-Probe im Probe-Bucket Sperren setzt – Entscheid vor Welle 5]; läuft die Probe mit den Stufenwerten der Stufe B (Sperrmodus von `manifest/` fest COMPLIANCE, Aufgabe 2.3), wären Manifeste dort (Req 6.11, Option B) bis zum Ablauf ihrer Sperre nicht löschbar. Offen ist, wie der private Sicherungsschlüssel zur Probe kommt, ohne dass der Runner Klartext sieht: [offen: Schlüsselweg der Restore-Probe – Entscheid vor Welle 5].
- Stufe A (Req 16.19): keine echten Daten, deshalb nur die Restore-Probe als Funktionstest, keine Aufbewahrungspflicht. Auch `archiv-probe` zählt zum Speicherbudget; während der Probe ruht der Archiv-Stack der Stufe A.
- Anleitung `docs/wiederherstellung.md`, einmal vollständig in einer Testumgebung durchgespielt (Req 16.3, Nachweis manuell).

### Frontend
- Routen: `/erfassen`, `/eingangskorb`, `/suche`, `/dokument/:id`, `/importe`, `/importe/:id`, `/verwaltung/dokumentarten`, `/verwaltung/protokoll`.
- Dokumentansicht `/dokument/:id` (Spec 04 ergänzt): Abschnitt «In der Wissensbasis verwendet» (Spec 04, Req 15.4) und Routenparameter `/dokument/:id?seite=n`, der das Dokument auf PDF-Seite n öffnet (Spec 04, Req 16.2).
- Auslieferung über Quinoa im API-Artefakt unter `archiv.apps.urinet.ch`; die API liegt unter `/api/v1`.
- Erfassen: `getUserMedia` (Rückkamera) mit Fallback `<input type="file" accept="image/*" capture="environment">`; Kantenerkennung und Entzerrung mit **jscanify** (OpenCV.js, als Web Worker, lazy geladen).
  - Original (Req 4.7, P13): die unbearbeitete Aufnahme, beim Fallback die Datei der Kamera, sonst das volle Videobild als JPEG 95 %.
  - Arbeitsfassung: Zuschnitt und Entzerrung, JPEG 85 %, lange Kante 2'480 px (≈ 300 dpi für A4). Erkennt jscanify keine Kanten, ist die Arbeitsfassung das ganze Bild (Req 4.3).
- Offline-Warteschlange (Req 4.4–4.6, 4.8): IndexedDB (`idb`), Eintrag mit Seiten (Original + Arbeitsfassung), `fehlversuche` und `zustand`. Senden bei `online`-Event, beim Öffnen der App und über Service Worker Background Sync, wo verfügbar.
  - 202, auch mit `DUBLETTE` → Eintrag löschen.
  - Netzwerkfehler oder 5xx bei `navigator.onLine === true` → `fehlversuche + 1`, nach 3 «fehlerhaft» mit manueller Wiederholung. Versuche im Offline-Zustand zählen nicht.
  - 4xx ausser 401 → sofort «fehlerhaft» mit der Meldung des Servers; 401 → neu anmelden, der Eintrag bleibt.
- Eingangskorb: Kennzeichen für `pdfa_fehlt`, «Frist abgelaufen», Warnung `BELEGNUMMER_BEKANNT` und «bitte prüfen»; bei `FEHLER` Fehlertext mit «erneut verarbeiten» und «verwerfen», bei `ARCHIVIERUNG_FEHLER` «Archivierung wiederholen» (Req 8.7). Aktionen ohne Recht werden nicht angezeigt; die API prüft trotzdem.
- API-Client aus OpenAPI generiert (`openapi-generator`, typescript-angular).

## Data Models

### Schema `public`
```sql
-- Liquibase-Changelog db/public
mandant(code text pk, name text, schema_name text unique, bucket text, domain text,
        branding jsonb,
        geschaeftsjahr_ende char(5) default '12-31',   -- MM-TT (Req 9.6)
        eigene_arten text[],    -- Arten, die der Mandant selbst ausstellt (Req 8.9, 11.3)
        kennungen jsonb,        -- {uid, ibans[]} des Mandanten (Req 7.5)
        aktiv bool, erstellt timestamptz)
auftrag(id bigserial pk, mandant text fk not null, typ text, nutzlast jsonb, status text,   -- OFFEN|LAEUFT|FERTIG|FEHLER; not null ab 2.5
        versuche int default 0, nicht_vor timestamptz, fehler text,
        erstellt timestamptz, gestartet timestamptz, lebenszeichen timestamptz, beendet timestamptz,
        nachbearbeitet timestamptz,   -- Hook nach dem letzten Fehlversuch erledigt (006, Aufgabe 2.5)
        nachbearbeitung_versuche int not null default 0, nachbearbeitung_nicht_vor timestamptz)   -- gescheiterte Hooks, Wartezeit (007)
  index (status, nicht_vor), index (status, lebenszeichen),
  index (beendet) where status = 'FEHLER' and nachbearbeitet is null,
  check (nutzlast is null or jsonb_typeof(nutzlast) = 'object')
api_client(id text pk, mandant text fk, name text, scope text,   -- generator|import
           geheimnis_hash text, aktiv bool, erstellt timestamptz, zuletzt timestamptz)
```

### Schema je Mandant `m_<code>`
```sql
-- Liquibase-Changelog db/mandant
dokumentart(code text pk, name text, beschreibung text null,   -- Abgrenzung für den KI-Prompt (2.1)
            gruppen text[], pflichtfelder text[],
            zusatz_schema jsonb, vertraulichkeit text, aufbewahrung_jahre int null,  -- null = unbefristet
            aufbewahrung_ab text null,   -- GESCHAEFTSJAHR | GUELTIG_BIS; null bei unbefristet
            gesetzliche_pflicht bool not null,   -- Object Lock nur bei true (Req 6.3, 6.12)
            aktiv bool)

kontakt(id uuid pk, crm_id text unique null, typ text, name text, vorname text,
        name_normalisiert text, strasse text, plz text, ort text, email text, telefon text,
        uid text, iban text, kategorie text, quelle text, nicht_im_export bool,
        abaninja_uuid text null, erstellt timestamptz, geaendert timestamptz)
  index gin (name_normalisiert gin_trgm_ops), index (lower(email))

verkaufschance(id uuid pk, crm_id text unique, kontakt_id uuid fk, titel text, phase text,
               betrag numeric(12,2), datum date, import_lauf uuid)

dokument(id uuid pk, art text fk null, titel text, datum date, eingang timestamptz,
         quelle text,            -- SCAN|UPLOAD|GENERATOR|DRIVE|EMAIL|ABANINJA
         partner_id uuid fk null, partner_name text, projekt text, tags text[],
         vertraulichkeit text,   -- NORMAL|INTERN|PERSONAL; vor der Bestätigung wirkt PERSONAL (Req 2.21)
         person_benutzer text null,
         erstellt_von text,      -- Erfasser: Benutzer oder token:<id>
         status text,            -- NEU|VERARBEITUNG|EINGANGSKORB|ARCHIVIERUNG|ARCHIVIERUNG_FEHLER|ARCHIVIERT|FEHLER|VERWORFEN
         status_grund text, fehler text,
         bestaetigt timestamptz null, bestaetigt_von text null,
         geschaeftsjahr int, aufbewahren_bis date null, sperre_bis date null,
         frist_abgelaufen bool default false,
         archiviert timestamptz null,   -- Zeitpunkt der gesetzten Sperre bzw. des Wechsels auf ARCHIVIERT (Manifest)
         sha256_pdfa text null, s3_pdfa text null, s3_version_pdfa text null,
         pdfa_fehlt bool default false,  -- Req 5.9
         seiten int, volltext text, volltext_tsv tsvector generated stored,
         ki_vorschlag jsonb, ki_sicherheit jsonb, hinweis text,
         zusatz jsonb,           -- freie Zusatzfelder gemäss dokumentart.zusatz_schema
         version int, erstellt timestamptz, geaendert timestamptz)
  index gin(volltext_tsv), gin(tags), index(art), index(partner_id), index(datum),
  index(geschaeftsjahr), index(status), index(archiviert)

-- jede empfangene Datei (Req 6.1); PDF/A ist abgeleitet und steht in dokument
dokument_datei(dokument_id uuid fk, nr int,   -- Seite bzw. Reihenfolge
               rolle text,                    -- ORIGINAL | ARBEITSFASSUNG
               sha256 text, s3_schluessel text, s3_version text null,   -- Versions-ID nach der Archivierung
               mime text, groesse bigint, empfangen timestamptz,
               status text default 'AKTIV',   -- AKTIV | VERWORFEN, beim Verwerfen mitgesetzt
               primary key (dokument_id, rolle, nr))
  unique (sha256) where rolle = 'ORIGINAL' and status <> 'VERWORFEN'   -- Dublettenprüfung je Datei (Req 3.4)

dokument_finanz(dokument_id uuid pk fk, belegnummer text, richtung text,  -- EINNAHME|AUSGABE
                betrag numeric(12,2), waehrung char(3) default 'CHF', faelligkeit date,
                zahlstatus text null, bezahlt_am date null,   -- nur aus AbaNinja (Spec 02)
                qr_referenz text, konto text, abaninja_uuid text null)
  index(belegnummer), index(betrag), index(richtung)

-- Eindeutigkeit nur für eigene Arten je Mandant (Req 8.9, 11.3, P7); Fremdbelege nur Warnung (Req 8.10).
-- Reserviert beim Bestätigen bzw. beim Generator-Eingang. Bestätigte Dokumente sind nicht mehr verwerfbar
-- (Req 6.7); ein Generator-Dokument kann aber in FEHLER oder mit pdfa_fehlt im Eingangskorb landen. Verwerfen
-- löscht die Zeile in derselben Transaktion (Req 11.11). PATCH der Belegnummer ändert die Zeile ebenso.
belegnummer_eigen(art text, belegnummer text, dokument_id uuid fk unique,
                  primary key (art, belegnummer))

dokument_vertrag(dokument_id uuid pk fk, gueltig_ab date, gueltig_bis date,
                 kuendigungsfrist text, naechste_frist date)

dokument_version(id bigserial pk, dokument_id uuid fk, version int, daten jsonb,
                 geaendert_von text, zeit timestamptz, grund text)
  -- INSERT-only: Trigger BEFORE UPDATE OR DELETE → RAISE EXCEPTION; archiv_app nur INSERT/SELECT

ki_beispiel(id bigserial pk, partner_id uuid null, art text, eingabe_auszug text,
            korrektur jsonb, iban text null, uid text null, email text null, muster text null,
            zeit timestamptz)
  index(iban), index(uid), index(email), index(muster), index(zeit)
ki_verbrauch(id bigserial pk, zweck text, modell text, tokens_ein int, tokens_aus int, zeit timestamptz)
  -- zweck: Spec 04 ergänzt WISSEN_INGEST, WISSEN_LINT, WISSEN_EMBEDDING (Monatsbericht nach Zweck, Spec 03, 8.1;
  -- Monatsbudget der Wissensbasis, Spec 04, 13.6)

import_zuordnung(art text pk, spalten jsonb, kopf_hash text, geaendert timestamptz)
import_lauf(id uuid pk, art text, vollexport bool, dateiname text, sha256 text, zeichensatz text,
            trennzeichen char,
            zeilen int, neu int, geaendert int, unveraendert int, dubletten int, fehler int,
            status text,   -- VORSCHAU_AUSSTEHEND|ZUORDNUNG_NOETIG|VORSCHAU|UEBERNOMMEN|ABGEBROCHEN
            benutzer text, erstellt timestamptz, uebernommen timestamptz)
import_zeile(lauf_id uuid fk, nr int, daten jsonb, ergebnis text, kontakt_id uuid null,
             kandidaten uuid[], meldung text, primary key (lauf_id, nr))

zugriff_protokoll(id bigserial pk, zeit timestamptz, benutzer text, rolle text, aktion text,
                  dokument_id uuid null, ip inet null, details jsonb)   -- details: Grund, SHA-256, Fehlercode
  -- INSERT-only wie dokument_version

manifest(datum date pk, von timestamptz null, bis timestamptz, sha256 text,
         vorher_sha256 text null,   -- null nur beim ersten Manifest
         anzahl int, protokolle jsonb, s3_schluessel text, s3_version text,
         sperre_bis date, signatur text, tsa_token bytea not null)

alarm(schluessel text pk, erstmals timestamptz, gemeldet timestamptz, erledigt timestamptz null)
```

Generator-Metadaten (Beispiele, `POST /dokumente/generator`):
```json
{ "art": "AUSGANGSRECHNUNG", "titel": "Rechnung 2027-001 Wartung H1 2027",
  "datum": "2027-01-04", "vertraulichkeit": "INTERN",
  "partner": {"crmId": "K-1001", "name": "FC Schattdorf"},
  "finanz": {"belegnummer": "2027-001", "richtung": "EINNAHME", "betrag": "408.00",
             "waehrung": "CHF", "faelligkeit": "2027-02-03", "qrReferenz": "…"},
  "tags": ["Wartung"], "projekt": "FC Schattdorf Webseite" }
```
```json
{ "art": "LOHNABRECHNUNG", "titel": "Lohnabrechnung Oktober 2026", "datum": "2026-10-25",
  "vertraulichkeit": "PERSONAL", "personBenutzer": "<benutzername>",
  "finanz": {"richtung": "AUSGABE", "betrag": "…", "waehrung": "CHF", "konto": "5000"},
  "zusatz": {"periode": "2026-10", "person": "<name>"} }
```
`vertraulichkeit` und `personBenutzer` sind bei Arten mit Standard `PERSONAL` Pflicht; sonst setzt
das Archiv ohne Angabe den Standard der Art (Req 11.10).

## Error Handling
- Alle Fehler als Problem Details (`type`, `title` deutsch, `status`, `detail`, `instance`, `fehlercode`).
  Umsetzung (Aufgabe 1.6): `Content-Type: application/problem+json`; `type` ist `urn:urinet:archiv:fehler:<FEHLERCODE>`,
  `instance` die Fehler-ID `urn:uuid:<uuid>`, die auch im Log steht; `title` und `detail` sind feste deutsche Texte je
  Fehlercode (`gemeinsam.domain.Fehlercode`) ohne Eingabewerte, Inhalte oder Klassennamen. Fachfehler werfen
  `FachFehler` mit Fehlercode; die Abbildung liegt in `gemeinsam.adapter.in.rest` (`ProblemAbbildung`, für Fehler vor
  der Resource ein Failure-Handler am Router). Die Antwort 401 ohne Token entsteht nicht als Fehler, sondern als
  Challenge; unter `/api/` schreibt sie `ProblemAnmeldung` als Problem Details.
- Fehlercodes:

| HTTP | `fehlercode` | Wann |
| --- | --- | --- |
| 400 | `ANFRAGE_UNGUELTIG` | Anfrage formal ungültig (Pfad, Parameter, JSON), auch andere 4xx des Frameworks mit ihrem Status (z. B. 405, 406) |
| 401 | `NICHT_ANGEMELDET` | Token fehlt, ungültig oder abgelaufen, mit `WWW-Authenticate: Bearer` |
| 403 | `MANDANT_FEHLT` | kein gültiger oder inaktiver Mandant (Req 1.3) |
| 403 | `NICHT_ERLAUBT` | Rolle oder Token-Scope ohne Recht (Req 2.4–2.20) |
| 404 | `NICHT_GEFUNDEN` | fremder Mandant oder unsichtbar, vor 403 (Req 1.4, 2.3) |
| 409 | `STATUS_KONFLIKT` | Aktion im falschen Status (Req 6.7, 6.13, 8.8) |
| 409 | `BELEGNUMMER_VERGEBEN` | eigene Art, Belegnummer reserviert (Req 8.9, 11.3) |
| 409 | `FRIST_ABGELAUFEN` | Bestätigung mit Frist in der Vergangenheit (Req 9.8) |
| 409 | `DOKUMENTART_IN_GEBRAUCH` | Löschen einer benutzten Art (Req 9.4) |
| 409 | `DOKUMENTART_EXISTIERT` | Anlegen einer Art mit einem Code, den es im Mandanten schon gibt (Req 9.1; Review PR #40) |
| 412 | `VERSION_KONFLIKT` | `If-Match` passt nicht (Req 10.4) |
| 413 | `DATEI_ZU_GROSS` | Datei über 50 MB oder Anfrage über der Gesamtgrenze (Req 3.6) |
| 415 | `DATEITYP_UNBEKANNT` | Inhalt kein unterstützter Typ (Req 3.3) |
| 415 | `MEDIENTYP_FALSCH` | `Content-Type` der Anfrage passt nicht zum Endpunkt (Framework, z. B. Text statt JSON); nicht für den Dateityp eines Uploads |
| 422 | `DATEI_UNGUELTIG` | leere oder beschädigte Datei (Req 3.7) |
| 422 | `PFLICHTFELD_FEHLT` | mit Liste der Felder (Req 8.3, 11.6) |
| 422 | `PARTNER_UNBEKANNT` | unbekannte `crmId` (Req 11.7) |
| 422 | `ZUSATZ_UNGUELTIG` | Zusatzfelder oder Schema ungültig (Req 9.5) |
| 422 | `CSV_UNGUELTIG`, `CSV_LEER` | Req 13.10, 13.11 |
| 422 | `KEINE_WISSENSQUELLE` | Ingest für ein Dokument einer anderen Art als `WISSENSQUELLE` (Spec 04, Req 5.7) |
| 422 | `ENTWURF_BEFUND` | Änderung der Wissensbasis mit Befund, mit Liste der Befund-Codes (Spec 04, Req 6.3) |
| 422 | `VERTRAULICHKEIT_ZU_TIEF` | Vertraulichkeit einer Wissensseite unter der berechneten Stufe (Spec 04, Req 6.8) |
| 422 | `VERTRAULICHKEIT_UNGUELTIG` | `PERSONAL` für eine Wissensseite gewählt (Spec 04, Req 6.8) |
| 422 | `ANALYSE_NICHT_MOEGLICH` | Analyse-Vorschlag aus einem Gespräch mit anderen Werkzeugen als den lesenden für Dokumente und Wissen (Spec 04, Req 12.4) |
| 428 | `VERSION_FEHLT` | ändernde Anfrage ohne `If-Match` (Req 10.6) |
| 500 | `INTERNER_FEHLER` | unerwarteter Fehler, auch Protokoll nicht schreibbar (Req 10.8); Antwort nur mit Fehler-ID, Log mit Fehler-ID und den Klassen der Ursachen-Kette (bei `SQLException` mit SQLState), ohne Meldungen |
| 503 | `UEBERLASTET` | zu viele gleichzeitige Argon2-Rechnungen für API-Tokens (Prüfen und Anlegen, seit 2.1), mit `Retry-After: 1` (Aufgabe 1.5); kein Platz nach `archiv.api-token.wartezeit`, bei `0` ohne Warten |
| 503 | `SPEICHER_NICHT_ERREICHBAR` | Object Storage beim Upload nicht erreichbar, mit `Retry-After` |

- Eine Dublette ist kein Fehler, sondern ein Ergebnis je Datei (`status: "DUBLETTE"`, siehe «Upload»).
- Worker: Eine Ausnahme wird im Auftrag nur als Art und Fehler-ID gespeichert (`fehler = '<art> fehler-id=<uuid>'`, Art `fehlgeschlagen`, `zeitgrenze`, `verwaist` oder `unlesbar`), erst wenn der Handler beendet ist (siehe «Umsetzung (Aufgabe 2.5)»); Klassen der Ursachen und SQLState stehen nur im Log unter derselben Fehler-ID, nie Meldungen. Dokumentstatus `FEHLER` bzw. `ARCHIVIERUNG_FEHLER` erst nach dem dritten Versuch, bei Prüfsummen- oder Sperrfehlern sofort (Req 6.4).
- KI nicht erreichbar → Dokument ohne Vorschlag in den Eingangskorb (nie blockieren).
- S3 nicht erreichbar beim Upload → 503 mit `Retry-After`; die PWA behält die Datei in der Warteschlange und zählt das als Fehlversuch (5xx).
- Protokoll nicht schreibbar → Aktion wird zurückgerollt, 500 (Req 10.8).
- Logs, Benachrichtigungen und CI-Ausgaben enthalten nur IDs, Status und Fehlercodes, keine Dokumentinhalte, Beträge oder Personendaten (Req 16.7).
- Ursache im Log ohne Werte (Review PR #37): Zur Fehler-ID schreibt `ProblemAntwort` die Klassennamen der Ursachen-Kette (höchstens 10) und bei einer `SQLException` deren SQLState, nie Meldungen; Stufe `ERROR` bei 5xx, `INFO` bei abgelehnter Anmeldung mit Ursache (z. B. ungültige Signatur, Keycloak nicht erreichbar), sonst `DEBUG`. Die Drosselung der Kategorien von Hibernate, Narayana und OIDC (`application.properties`) bleibt: Ein `@LoggingFilter` sähe die Werte auch in den Meldungen der angehängten Ausnahmen und könnte sie nur samt Stacktrace verwerfen.

## Testing Strategy
Nachweis: jedes Kriterium ohne Vermerk «Nachweis: manuell [Fabian]», «Nachweis: Dokument» oder
«Nachweis: JUnit» als Cucumber-Szenario mit Tag `@req-<x.y>` (Req 16.14); Features unter
`backend/src/test/resources/features/<fachbereich>/`, je Datei auf Feature-Ebene genau ein Tag `@spec-NN`.
Testdaten im Repository sind ausschliesslich synthetisch (Req 16.7).

Reichweite der CI-Prüfung (Req 16.14, entschieden mit Aufgabe 1.1): Geprüft werden die Kriterien unter
`_Anforderungen_` abgehakter Aufgaben ab Welle 1; die Prüfung wächst mit jeder abgehakten Aufgabe. Optionale Aufgaben
(`O.n`) zählen nicht. Abgedeckt ist ein Kriterium durch ein `@req`-Tag oder einen Nachweis-Vermerk, jeweils innerhalb
derselben Spec. Die Kriterien der Welle 0 (u. a. 0.2, 0.7, 0.9) sind durch den Gate-Bericht nachgewiesen
(`docs/spikes/golden-ergebnis.md`, `docs/umsetzungsplan.md` Abschnitt 6).

Entwicklungsumgebung (Aufgabe 1.2): In `dev` und in Tests starten PostgreSQL 17 mit pgvector
(`pgvector/pgvector:0.8.7-pg17-trixie`) und Keycloak (`quay.io/keycloak/keycloak:26.8.2`, Realm aus
`deploy/keycloak/realm-test.json`) als Dev Services der Extensions, MinIO und Mailpit als Compose Dev Services aus
`backend/compose-devservices.yml` (statt Testcontainers, weil Compose dev und Tests mit einer Datei abdeckt; entschieden
von Fabian am 09.11.2026). MinIO veröffentlicht keine Images mehr auf Docker Hub; verwendet wird `cgr.dev/chainguard/minio` mit festem
Digest. Die Driven Ports `AblagePort`, `KiPort`, `OcrPort` (`verarbeitung`) und `IntegrationPort` (`integration`)
haben je einen Dev-Stub unter `adapter/out/stub/`; `adapter/out/<Name>Auswahl` wählt zur Laufzeit nach
`archiv.<port>.stub` (nur in `dev` an). Ohne Stub und ohne echten Adapter bricht die Auswahl ab; die Methoden und
Adapter folgen mit 2.3, 2.6, 3.2 und Spec 02/03. Readiness meldet DOWN, wenn die Datenbank nicht erreichbar ist
(Agroal) oder keine Datasource aktiv ist (`DatenbankBereitschaft`). Die API nimmt nur Tokens mit Audience
`archiv-api` an (`quarkus.oidc.token.audience`).

| Ebene | Werkzeug | Schwerpunkt |
| --- | --- | --- |
| Akzeptanz | Cucumber; Java-Runner mit REST-assured gegen `@QuarkusTest`, Browser-Szenarien mit Cucumber.js + Playwright | alle `@req`-Kriterien; Rechtematrix als Scenario Outline Rolle × A1–A14 (Req 2.6–2.20); Statusübergänge; Fehlercodes |
| Unit | JUnit (Jupiter 6, über die Quarkus-BOM), AssertJ | Normalisierung, Fristberechnung, Belegnummer-Muster, Swiss-QR-Parser, Beispielvorauswahl, CSV-Erkennung, Manifest-Fenster (Zeitumstellung), Kanonisierung und Signatur, `DokumentartSeedTest` |
| Integration | `@QuarkusTest`, Dev Services PostgreSQL + Keycloak, MinIO (Object Lock, Versionierung) und Mailpit als Compose Dev Services, WireMock (AI, TSA) | Mandantentrennung (immer zwei Mandanten), Dubletten-Index mit verworfenen Dokumenten, `belegnummer_eigen` (Generator-Dokument in `FEHLER` verwerfen, korrigiert neu senden → 202, Req 11.11), INSERT-only-Trigger, 404 vor 403, Protokoll in derselben Transaktion, Sperre setzen und verlängern, gesperrte Version ohne Bypass-Header weder löschbar noch verkürzbar, Manifest in COMPLIANCE auch mit Bypass-Header nicht löschbar (Test-Bucket ohne Bucket Policy, sonst greift deren Deny [T 6c]), kein S3-Aufruf der Anwendung sendet `x-amz-bypass-governance-retention` (Request-Interceptor, Req 6.5), Provisionierung setzt die Bucket Policy mit Deny auf `s3:BypassGovernanceRetention`, verwaiste Aufträge |
| Architektur | ArchUnit | Schichten `domain` → nichts, `application` → `domain`, `adapter` → `application`/`domain`; Fachbereiche nur über `application` (structure.md); kein Zugriff auf `public`-Tabellen aus Mandanten-Code ausser `auftrag`; keine `double`-Beträge; kein `System.getenv` für Secrets; keine Verwendung von `bypassGovernanceRetention` im Produktionscode (Testklassen ausgenommen, `ImportOption.DoNotIncludeTests`; ergänzend zum Request-Interceptor, Req 6.5) |
| Worker | Testcontainer mit Worker-Image | OCR auf 5 synthetischen PDFs/Fotos, signiertes und verschlüsseltes PDF, Arbeitsfassung als Quelle, Zeitgrenze, PDF/A-Validierung mit veraPDF |
| Leistung | Lastskript gegen Stufe B (K5-Grösse), Lauf in Aufgabe 5.11; in Stufe A nur Funktion (Req 16.16) | Req 3.2, 5.5 (p95), 14.3 (p95); [offen: Messbedingungen nach VPS-Grösse K5 – Entscheid vor Welle 5] |
| Speicherbudget Stufe A | Messung in Aufgabe 1.10 mit synthetischen Belegen | Spitzenverbrauch je Dienst gegen das Budget aus `tech.md`, Health und Speicher der anderen Apps (Req 16.18) |
| KI-Qualität | `GoldenDatensatzIT`, nur manuell gestartet, Daten aus `ARCHIV_GOLDEN_DIR` ausserhalb des Repos, echter AI-Aufruf | Bericht je Feld, getrennt nach Abstimmungs- (30) und Prüfteil (20); Gate ≥ 18/20 und Req 0.9 (Nachweis manuell) |
| Frontend | Vitest, Playwright (mobile Viewport 375 px) | Erfassen mit Testbild mit und ohne Kanten, Offline-Warteschlange (Dublette, 4xx, 5xx, offline), Eingangskorb bestätigen in ≤ 2 Tipps |
| Betrieb | Restore-Probe, automatisiert und wiederkehrend; in Stufe A nur als Funktionstest (Req 16.19) | Datenbanken und Dateien wiederherstellen, Anmeldung, Dokument öffnen, Prüflauf grün (Req 16.3) |

Umsetzung der Architekturregeln (Aufgabe 1.6, `ArchitekturTest`, Req 16.13):
- `domain` hängt nur vom JDK und von Domain-Typen ab. `application` hängt zusätzlich nur von `application`, CDI,
  Interceptor-Bindung, JTA, MicroProfile Config und JBoss Logging ab; REST, JPA, Vert.x, Quarkus und Jackson sind
  ausgeschlossen.
- Schichten je Fachbereich: `domain` → `domain`, `application` → `application`/`domain`, `adapter` → alles im
  eigenen Fachbereich. `gemeinsam` ist der gemeinsame Kern und darf in derselben Schicht von jedem Fachbereich
  genutzt werden, hängt aber selbst von keinem ab. Einen fremden Fachbereich erreicht `application` bzw. `adapter`
  nur über dessen `application` und die `domain`-Typen in dessen Signaturen, nie über dessen `adapter`.
  `ArchivMain` im Wurzelpaket ist ausgenommen.
- Driven Ports: Klassen unter `adapter.out` implementieren nur Interfaces aus `application`, und Implementierungen
  von `application`-Interfaces liegen in `application` oder unter `adapter.out`; Klassen mit `Stub` im Namen liegen
  unter `adapter.out`.
- Felder und Methoden mit Betragsnamen (`betrag`, `summe`, `preis`, `total`, `mwst`, `saldo`) sind nie `double` oder
  `float`; kein Aufruf von `System.getenv` und von `bypassGovernanceRetention`; JPA-Entitäten auf `public` nur für
  `auftrag`, dazu `mandant` im Fachbereich `mandant` und `api_client` im Fachbereich `sicherheit`. Zugriffe per
  JDBC-SQL erkennt ArchUnit nicht, sie bleiben Sache des Reviews.

## Open Points
- ~~Object Lock pro Objekt, Schlüssel ohne Bypass-Recht, COMPLIANCE bei Infomaniak~~ geklärt im Spike Object Storage (Aufgabe 0.2, `docs/spikes/s3-object-lock.md`, Live-Tests 08.11.2026): Sperre pro Objekt mit Versionierung, GOVERNANCE und COMPLIANCE funktionieren, Objekte ohne Retention (`eingang/`) bleiben löschbar; der Rückfall mit getrennten Buckets entfällt damit. Einen Schlüssel ohne Bypass-Recht gibt es im selben Projekt nicht (jeder Schlüssel kann eine schützende Bucket Policy löschen), darum gilt Option B (entschieden 09.11.2026; ADR-007, Nachtrag 09.11.2026): GOVERNANCE für Dokumente unter `archiv/`, COMPLIANCE für `manifest/` (Req 6.11). `x-amz-checksum-sha256` wurde nicht geprüft (Antwort beim Upload mit `ChecksumCRC64NVME`); bis zum Nachweis mit dem Java SDK in Aufgabe 2.3 gilt der Rückfall aus «Ablage» (Kopie über die Versions-ID neu lesen).
- ~~Strukturierte Antworten bei Infomaniak~~ geklärt im Spike AI Services (Aufgabe 0.3): JSON-Schema funktioniert, JSON-Modus wird abgelehnt (`docs/spikes/infomaniak-ai.md`).
- [offen: VPS-Grösse K5 – Entscheid vor Welle 5] (Stufe B; früher, falls 1.10 abbricht oder 16.18 später eintritt); danach Messbedingungen für Req 3.2, 5.5, 14.3 und die Zeitgrenzen der Aufträge prüfen.
- Speicherbudget der Stufe A (700 MiB, 0.75 CPU) ist ein Richtwert; Messung und Ergebnis in Aufgabe 1.10.
- Testobjekte der Stufe A im Object Storage: Gesperrte Dokumente lassen sich ohne Bypass-Header nicht löschen [T 3b]; jeder Schlüssel des Projekts, auch der App-Schlüssel, hat technisch das Bypass-Recht [T 6b–7g], die Anwendung setzt den Header aber nie (Req 6.5; Option B, ADR-007, Nachtrag 09.11.2026). Manifeste liegen in COMPLIANCE und lassen sich bis zum Ablauf ihrer Sperre (Req 15.4) mit keinem Schlüssel des Projekts löschen, auch nicht mit Bypass-Header [T 4b/4c]; deshalb darf der Sperrmodus von `manifest/` auf Stufe A per Konfiguration abweichen (GOVERNANCE oder kürzere Frist, Req 6.11, Aufgabe 2.3; entschieden von Fabian am 09.11.2026). Gesperrte Dokumente in Test-Buckets lassen sich erst nach Entfernen der Bucket Policy mit Bypass-Header löschen (Korrektur mit dem Inhaber-Zugang, siehe «Ablage»). `MandantProvisionierung` legt `<archiv.ablage.bucket-praefix><code>` an. Namen der Test-Buckets und Umgang mit gesperrten Testobjekten: entschieden von Fabian am 09.11.2026 (Option A): Präfix `urinet-archiv-a-` auf Stufe A, Sperrfrist der Dokumente dort höchstens 1 Tag (`archiv.ablage.max-sperrfrist=P1D`), Startabbruch ab Stufe B bei gesetzter Höchstfrist oder Präfix der Stufe A, Aufräumen beim Wechsel mit dem App-Schlüssel ohne Bypass-Header (siehe «Protokoll und Manifest», Sperrmodus).
- [offen: Zuordnung der Verkaufschancen aus 21.CRM mit einem Nachexport mit Testdaten bestätigen – Entscheid vor Welle 4] (`docs/spikes/21crm-export.md`).
- ~~Werte der 11 Dokumentarten~~ entschieden von Fabian am 10.11.2026 (Aufgabe 1.7): Werte wie im Seed-Entwurf, nur `OFFERTE` ohne `gesetzliche_pflicht` (kein Buchungsbeleg, 10 Jahre ohne Sperre, damit sich verworfene Offerten löschen lassen); `NORMAL` nur für `OFFERTE` und `PROJEKTDOKUMENT`; `VERTRAG` und `VERSICHERUNG` gesperrt; `VERTRAG` bleibt `PERSONAL`, Kundenverträge per A8 auf `NORMAL`, keine eigene Art für Kundenaufträge (siehe «Stammdaten der Dokumentarten»). Nachgeführt am 10.11.2026 (entschieden von Fabian, F1–F7): Req 9.7 präzisiert, Req 9.10 neu, `AUSGANGSRECHNUNG` ohne `faelligkeit`, `BEHOERDE` mit `finanz`, `LOHNABRECHNUNG` mit Lohnausweis, neue Beschreibungen.
- [offen: Schweizer TSA-Anbieter – Entscheid vor Welle 5].
- [offen: zweiter Schweizer Backup-Anbieter – Entscheid vor Welle 5].
- [offen: Rhythmus der Restore-Probe – Entscheid vor Welle 5]; [offen: Schlüsselweg der Restore-Probe – Entscheid vor Welle 5]; [offen: ob die Restore-Probe im Probe-Bucket Sperren setzt – Entscheid vor Welle 5] (siehe «Sicherung und Wiederherstellung»).
- [offen: Aufbewahrungsfrist des Zugriffsprotokolls – Entscheid vor Welle 5].
- [offen: Rechte der Geschäftsstelle A11–A14 – Entscheid in `03-zweiter-mandant`]; bis dahin 403.
- Löschfunktion (Req 9.9) und Wissensbasis: Konflikt «nur anfügende Seitenversionen vs. Löschung nach revDSG» (Spec 04, Req 15.5); die Spec zur Löschfunktion muss ihn lösen: [offen: Umgang mit Seitenversionen, die auf einem gelöschten Dokument beruhen (revDSG) – Entscheid in der Spec zur Löschfunktion].
- Startwerte zum Prüfen in Welle 1: Gesamtgrenze der Upload-Anfrage (500 MB), Ähnlichkeitsschwelle 0.6.
