# Architekturentscheide (ADR)

Je Entscheid eine Datei `ADR-<nnn>-<stichwort>.md` (Status, Kontext, Entscheid, Alternativen,
Folgen, Verweise). Quelle der Liste: `.kiro/specs/01-archiv-kern/design.md`, Abschnitt Overview;
ADR-013: `.kiro/specs/04-wissensbasis/design.md`.
Freigabe: per Merge im Rahmen von Aufgabe 0.6 (Gate 0).

| ADR | Entscheid | Status |
| --- | --- | --- |
| [ADR-001](ADR-001-eigenbau-statt-paperless-ngx.md) | Das Archiv wird als eigener Dienst (Quarkus, Angular-PWA, PostgreSQL, Keycloak, Infomaniak) gebaut statt mit Paperless-ngx, bewährte Open-Source-Bausteine übernehmen OCR, Texterkennung und Anmeldung. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-002](ADR-002-schema-pro-mandant-liquibase.md) | Jeder Mandant erhält ein eigenes PostgreSQL-Schema `m_<code>` und einen eigenen Bucket, Schemaänderungen laufen nur über Liquibase. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-003](ADR-003-auftragsschlange-postgresql.md) | Lange Arbeiten laufen über die Tabelle `public.auftrag` mit `FOR UPDATE SKIP LOCKED`, Lebenszeichen, Zeitgrenzen und 3 Versuchen statt über einen Message-Broker. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-004](ADR-004-zweistufige-ablage.md) | Ein Bucket je Mandant ohne Standard-Aufbewahrung: `eingang/` bleibt ungesperrt, beim Archivieren wird nach `archiv/` kopiert, geprüft und nur bei `gesetzliche_pflicht` gesperrt. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-005](ADR-005-handy-zuerst-als-pwa.md) | Die Handy-Erfassung ist Teil der einen Angular-PWA mit Kamera, Kantenerkennung und Offline-Warteschlange; eine native Hülle ist nicht vorgesehen (D8 gestrichen 09.11.2026). | Angenommen (Gate 0, 09.11.2026) |
| [ADR-006](ADR-006-ki-nur-fuer-vorschlaege.md) | Die KI liefert nur Vorschläge, archiviert wird erst nach Bestätigung durch einen Menschen mit Recht A7, Generator-Dokumente laufen ohne KI. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-007](ADR-007-unveraenderbarkeit-governance.md) | Dokumente werden im Modus GOVERNANCE, Manifeste im Modus COMPLIANCE gesperrt (Option B); die Anwendung setzt nie den Bypass-Header, der Inhaber-Zugang liegt offline, und jedes tägliche Manifest trägt Signatur, Hash-Kette und RFC-3161-Zeitstempel. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-008](ADR-008-sicherung-zweitkopie.md) | Datenbank und Buckets werden als verschlüsselte Zweitkopie bei einem zweiten Schweizer Anbieter über den zentralen Sicherungs-Workflow gesichert, der Server kann sie weder entschlüsseln noch löschen. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-009](ADR-009-rollen-rechtematrix.md) | Vier eng gefasste Rollen nach der Rechtematrix A1–A14, eine zentrale Sichtbarkeitsregel je Dokument und 404 vor 403 bei unsichtbaren Dokumenten. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-010](ADR-010-plattform-zwei-stufen.md) | Das Archiv läuft zuerst auf der bestehenden Infomaniak-VPS (Stufe A, Speicherbudget 700 MiB), vor echten Belegen auf einer eigenen VPS (Stufe B), Deploy nur über GitHub Actions. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-011](ADR-011-hexagonale-struktur-archunit.md) | Jeder Fachbereich ist hexagonal aufgebaut (Domain, Application, Adapter mit Dev-Stubs), ArchUnit-Tests erzwingen die Abhängigkeitsregeln. | Angenommen (Gate 0, 09.11.2026) |
| [ADR-012](ADR-012-lokales-modell-entwicklung.md) | Ein lokales Sprachmodell über LM Studio ist nur im Profil `lokal` auf dem Entwicklerrechner erlaubt, nur an `localhost`, nie für Gate 0, Golden-Lauf, Abnahmen, CI oder Betrieb; das Archiv prüft die KI-Basis-URL beim Start. | Vorgeschlagen (Freigabe per eigenem Merge, nicht Teil von 0.6) |
| [ADR-013](ADR-013-wissensbasis-nach-llm-wiki-muster.md) | Die Wissensbasis folgt dem LLM-Wiki-Muster (OKF), liegt im Mandanten-Schema, und die KI schreibt nur Entwürfe, die ein Mensch mit Recht A17 freigibt. | Vorgeschlagen (Freigabe per eigenem Merge mit Spec 04) |
