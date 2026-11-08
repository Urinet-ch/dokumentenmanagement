# Architekturentscheide (ADR)

Je Entscheid eine Datei `ADR-<nnn>-<stichwort>.md` (Status, Kontext, Entscheid, Alternativen,
Folgen, Verweise). Quelle der Liste: `.kiro/specs/01-archiv-kern/design.md`, Abschnitt Overview.
Freigabe: per Merge im Rahmen von Aufgabe 0.6 (Gate 0).

| ADR | Entscheid | Status |
| --- | --- | --- |
| [ADR-001](ADR-001-eigenbau-statt-paperless-ngx.md) | Das Archiv wird als eigener Dienst (Quarkus, Angular-PWA, PostgreSQL, Keycloak, Infomaniak) gebaut statt mit Paperless-ngx, bewährte Open-Source-Bausteine übernehmen OCR, Texterkennung und Anmeldung. | Vorgeschlagen |
| [ADR-002](ADR-002-schema-pro-mandant-liquibase.md) | Jeder Mandant erhält ein eigenes PostgreSQL-Schema `m_<code>` und einen eigenen Bucket, Schemaänderungen laufen nur über Liquibase. | Vorgeschlagen |
| [ADR-003](ADR-003-auftragsschlange-postgresql.md) | Lange Arbeiten laufen über die Tabelle `public.auftrag` mit `FOR UPDATE SKIP LOCKED`, Lebenszeichen, Zeitgrenzen und 3 Versuchen statt über einen Message-Broker. | Vorgeschlagen |
| [ADR-004](ADR-004-zweistufige-ablage.md) | Ein Bucket je Mandant ohne Standard-Aufbewahrung: `eingang/` bleibt ungesperrt, beim Archivieren wird nach `archiv/` kopiert, geprüft und nur bei `gesetzliche_pflicht` gesperrt. | Vorgeschlagen (Vorbehalt Spike 0.2) |
| [ADR-005](ADR-005-handy-zuerst-als-pwa.md) | Die Handy-Erfassung ist Teil der einen Angular-PWA mit Kamera, Kantenerkennung und Offline-Warteschlange, Capacitor folgt später. | Vorgeschlagen |
| [ADR-006](ADR-006-ki-nur-fuer-vorschlaege.md) | Die KI liefert nur Vorschläge, archiviert wird erst nach Bestätigung durch einen Menschen mit Recht A7, Generator-Dokumente laufen ohne KI. | Vorgeschlagen (Vorbehalt Spike 0.3, Golden-Lauf 0.5) |
| [ADR-007](ADR-007-unveraenderbarkeit-governance.md) | Sperren laufen im Modus GOVERNANCE mit einem App-Schlüssel ohne Bypass-Recht, der einzige Bypass-Schlüssel liegt offline, und jedes tägliche Manifest trägt Signatur, Hash-Kette und RFC-3161-Zeitstempel. | Vorgeschlagen (Vorbehalt Spike 0.2) |
| [ADR-008](ADR-008-sicherung-zweitkopie.md) | Datenbank und Buckets werden als verschlüsselte Zweitkopie bei einem zweiten Schweizer Anbieter über den zentralen Sicherungs-Workflow gesichert, der Server kann sie weder entschlüsseln noch löschen. | Vorgeschlagen |
| [ADR-009](ADR-009-rollen-rechtematrix.md) | Vier eng gefasste Rollen nach der Rechtematrix A1–A14, eine zentrale Sichtbarkeitsregel je Dokument und 404 vor 403 bei unsichtbaren Dokumenten. | Vorgeschlagen |
| [ADR-010](ADR-010-plattform-zwei-stufen.md) | Das Archiv läuft zuerst auf der bestehenden Infomaniak-VPS (Stufe A, Speicherbudget 700 MiB), vor echten Belegen auf einer eigenen VPS (Stufe B), Deploy nur über GitHub Actions. | Vorgeschlagen |
| [ADR-011](ADR-011-hexagonale-struktur-archunit.md) | Jeder Fachbereich ist hexagonal aufgebaut (Domain, Application, Adapter mit Dev-Stubs), ArchUnit-Tests erzwingen die Abhängigkeitsregeln. | Vorgeschlagen |
