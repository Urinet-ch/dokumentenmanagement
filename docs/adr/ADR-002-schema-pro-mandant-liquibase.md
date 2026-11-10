# ADR-002: Schema pro Mandant, Schemaänderungen nur über Liquibase

**Status:** Angenommen – freigegeben per Merge an Gate 0 (Aufgabe 0.6, PR #23, Fabian, 09.11.2026)

**Datum:** 08.11.2026

## Kontext
- Das Archiv ist von Anfang an mandantenfähig (Konzept M1): In Phase 1 ist nur `urinet` produktiv, in Phase 3 kommt `vhf-gsk` dazu. «Ein neuer Mandant ist Konfiguration, kein neues Projekt» (product.md).
- Die Daten enthalten Personal- und Lohndaten (`PERSONAL`). Ein Leck zwischen Mandanten wäre der schwerste denkbare Fehler. Der Mandant stammt nur aus dem verifizierten Token, nie aus Benutzereingaben (tech.md Regel 1, Req 1.2).
- Einige Daten sind gemeinsam: Mandantenregister, Auftragsschlange (ADR-003) und API-Clients.
- Das Schema wächst über die Specs 02 und 03. Ein Rollback auf das Vorgänger-Image darf keinen Rückbau der Datenbank verlangen (Req 16.11, Deploy mit Rollback nach ADR-010).
- Es gibt eine PostgreSQL-17-Instanz auf einer kleinen VPS. In Stufe A gilt ein Speicherbudget von höchstens 700 MiB für alle Archiv-Dienste (P1).
- Zwei Agenten arbeiten parallel am Code (CLAUDE.md). Schemaänderungen müssen nachvollziehbar sein und dürfen sich nicht gegenseitig überschreiben.

## Entscheid
1. Jeder Mandant erhält ein eigenes PostgreSQL-Schema `m_<code>` und einen eigenen Bucket (Req 1.1; Aufbau der Ablage siehe ADR-004). Hibernate läuft mit `quarkus.hibernate-orm.multitenant=SCHEMA`.
2. Die gemeinsamen Tabellen liegen im Schema `public`: `mandant`, `auftrag` und `api_client`.
3. `MandantTenantResolver` liest `mandant` aus der `SecurityIdentity` (OIDC-Claim oder Principal des API-Tokens). Fehlt der Mandant oder ist er inaktiv, antwortet das Archiv mit 403 `MANDANT_FEHLT` (Req 1.3). Der Worker setzt den Mandanten des Auftrags über `MandantKontext.mit(mandant, …)` (Req 1.6, ADR-003).
4. Schemaänderungen laufen nur über Liquibase, mit zwei Changelogs: `db/public` für die gemeinsamen Tabellen und `db/mandant/changelog.xml` je Mandant, ausgeführt mit `defaultSchemaName=m_<code>`. Hibernate prüft nur (`validate`) und legt nichts an (Req 1.7).
5. Beim Start migriert die API mit der DB-Rolle `archiv_owner` zuerst `db/public`, dann `db/mandant` für jedes aktive Mandanten-Schema; dabei laufen nur neue Changesets. Danach prüft Hibernate mit `validate`. Der Worker migriert nie und meldet sich erst bereit, wenn der erwartete Changelog-Stand erreicht ist. Zur Laufzeit arbeitet die Anwendung mit der Rolle `archiv_app`, `archiv_owner` dient nur Liquibase.
6. `MandantProvisionierung` (Admin-Kommando `archiv mandant anlegen <code>`) legt das Schema an, führt `db/mandant` aus, erstellt den Bucket mit Object Lock und Versionierung und spielt die Dokumentarten aus `seed/<code>/dokumentarten.json` ein (Req 1.5).
7. Bestehende Changesets werden nie geändert, neue werden nur angehängt. Jede Migration bleibt rückwärtskompatibel zum Vorgänger-Release (tech.md Regel 11, Req 16.11).

## Betrachtete Alternativen
- **Mandantenspalte in einem gemeinsamen Schema:** abgelehnt (design.md: «statt Mandantenspalte»). Jede Abfrage, jeder Index und jede Eindeutigkeitsregel (Dublette je SHA-256, Belegnummer je Art) müsste den Mandanten mitführen. Ein vergessener Filter würde fremde Daten liefern. Mit eigenem Schema wirkt die Trennung schon auf Ebene der Verbindung.
- **Mandantenspalte mit Row-Level Security in PostgreSQL:** senkt das Risiko eines Lecks. Dafür müssen Policies für jede Tabelle gepflegt und getestet werden, und jede Verbindung muss den Mandanten als Sitzungswert setzen. Einen einzelnen Mandanten zu exportieren, wiederherzustellen oder zu entfernen bliebe Arbeit an einzelnen Zeilen. Gegenüber dem eingebauten Schema-Modus von Hibernate bringt das keinen Vorteil.
- **Eigene Datenbank oder Instanz je Mandant:** trennt am stärksten, braucht aber je Mandant einen eigenen Verbindungspool, eine eigene Sicherung (ADR-008) und mehr Speicher. Das passt nicht ins Speicherbudget der Stufe A und bringt bei zwei geplanten Mandanten keinen Mehrwert. Ausserdem liesse sich ein Auftrag in `public.auftrag` nicht mehr in derselben Transaktion wie die Daten des Mandanten schreiben.
- **Schema durch Hibernate erzeugen (`update`, `drop-and-create`):** abgelehnt nach Req 1.7. Änderungen wären weder versioniert noch prüfbar, eine Rückwärtskompatibilität liesse sich nicht planen, und Datenmigrationen wären nicht steuerbar.
- **Flyway statt Liquibase:** für versionierte Migrationen technisch gleichwertig. Liquibase ist im Stack festgelegt (tech.md), und ein Wechsel brächte keinen Vorteil.

## Folgen
- **Positiv:** Die Trennung steckt in der Struktur: Code eines Mandanten sieht nur dessen Schema. Eine Dokument-ID eines fremden Mandanten existiert dort nicht und führt zu 404 (Req 1.4; Reihenfolge 404 vor 403 siehe ADR-009).
- **Positiv:** Ein neuer Mandant entsteht durch Provisionierung, ohne neuen Code (VHF/GSK, Spec 03).
- **Positiv:** Ein Mandant lässt sich einzeln sichern, wiederherstellen oder entfernen (Schema und Bucket).
- **Positiv:** Der Stand des Schemas ist reproduzierbar. Die CI prüft, dass bestehende Changesets unverändert bleiben (Aufgabe 1.1), und ein Claude-Code-Hook blockiert das Bearbeiten bestehender Changesets unter `db/**` (Aufgabe 1.8).
- **Negativ:** Auswertungen über alle Mandanten brauchen eine Schleife über die aktiven Mandanten, so wie der Manifest-Lauf je Mandant einen eigenen Auftrag einreiht (ADR-003).
- **Negativ:** Start und Migration dauern mit jedem Mandanten länger, weil Liquibase je Schema einmal läuft. Bei zwei Mandanten ist das unkritisch.
- **Umsetzung:** Rückwärtskompatible Migrationen verlangen schrittweises Vorgehen: Eine Spalte wird zuerst hinzugefügt, entfernt wird sie frühestens ein Release später, wenn das Vorgänger-Image sie nicht mehr braucht.
- **Umsetzung:** Code eines Mandanten greift auf `public` nur über `auftrag` zu. ArchUnit prüft das (ADR-011).
- **Tests:** Jeder Repository-Test läuft mit zwei Mandanten (tech.md). Der Provisionierungstest legt zwei Mandanten an und prüft, dass Schemas und Buckets getrennt sind (Aufgabe 1.3).
- **Betrieb:** Welchen Bucket `MandantProvisionierung` in Stufe A anlegt: entschieden von Fabian am 09.11.2026, siehe Nachtrag.

## Nachtrag 09.11.2026 (Buckets der Stufe A)
Entschieden von Fabian am 09.11.2026 (Option A): Der Bucket heisst `<archiv.ablage.bucket-praefix><code>`, Standard
`urinet-archiv-`, auf Stufe A `urinet-archiv-a-`; den produktiven Bucket `urinet-archiv-urinet` gibt es erst auf Stufe B
(Aufgabe 5.4). Mit `archiv.stufe=A` sperrt das Archiv auch Dokumente unter `archiv/` höchstens 1 Tag (GOVERNANCE,
`archiv.ablage.max-sperrfrist=P1D`). Ab Stufe B bricht der Start ab, wenn eine Höchstfrist gesetzt ist oder der Präfix
der Stufe A gilt (Req 6.11). Beim Wechsel (5.11) löscht der App-Schlüssel frühestens 1 Tag nach dem letzten Upload alle
Versionen der Test-Buckets ohne Bypass-Header, danach die Buckets; der Inhaber-Zugang wird nicht gebraucht.

## Verweise
- Req 1.1–1.7, 16.11
- tech.md Regeln 1 und 11, Stack «Persistenz»; structure.md (`db/public`, `db/mandant`)
- Konzept M1; P1 (Speicherbudget Stufe A)
- ADR-003, ADR-004, ADR-008, ADR-009, ADR-010, ADR-011
- Aufgaben 1.1, 1.3, 1.4, 1.8, 1.9, 2.2
