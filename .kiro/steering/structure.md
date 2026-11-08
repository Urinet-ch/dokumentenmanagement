---
inclusion: always
---

# Struktur und Namen

## Repository `Urinet-ch/dokumentenmanagement`
```
dokumentenmanagement/
├── .kiro/
│   ├── steering/            product.md, tech.md, structure.md
│   └── specs/
│       ├── 01-archiv-kern/  requirements.md, design.md, tasks.md
│       ├── 02-abgleich-chat/
│       └── 03-zweiter-mandant/
├── backend/                 Quarkus (API + Worker)
│   └── src/main/java/ch/urinet/archiv/
│       ├── mandant/         Mandant, TenantResolver, Provisionierung
│       ├── sicherheit/      Rollen, API-Tokens, Verschlüsselung
│       ├── dokument/        Dokument, Metadaten, Status, Versionen, REST
│       ├── dokumentart/     Dokumentarten, Zusatzfelder, Aufbewahrung
│       ├── ablage/          S3-Zugriff, Object Lock, Prüfsummen
│       ├── verarbeitung/    Auftragsschlange, Normalisieren, OCR, Indexieren
│       ├── ki/              Metadaten-Extraktor, Beispiele, Embeddings, Chat (Spec 02)
│       ├── kontakt/         Kontakte, Verkaufschancen, Partner-Abgleich
│       ├── importe/         CSV-Import CRM, Drive-Import
│       ├── suche/           Volltext, Filter, später Hybridsuche
│       ├── protokoll/       Zugriffsprotokoll, Tages-Manifest
│       ├── integration/     abaninja/, mail/, wordpress/ (Spec 02/03)
│       └── gemeinsam/       Fehler (Problem Details), Zeit, Geld
│   └── src/main/resources/db/
│       ├── public/          Flyway: Mandantenregister, Aufträge, API-Clients
│       └── mandant/         Flyway: Schema je Mandant
├── frontend/                Angular PWA
│   └── src/app/
│       ├── erfassen/  eingangskorb/  suche/  dokument/  importe/  chat/  verwaltung/
│       └── gemeinsam/       Auth, API-Client (aus OpenAPI generiert), Offline-Warteschlange
├── tools/
│   ├── archiv_client.py     Client für rechnung_gen.py und urinet_pdf.py
│   └── drive_import/        Java-CLI (Picocli) für den Drive-Altbestand
├── wordpress/urinet-archiv-plugin/   (Spec 03)
├── deploy/                  docker-compose.yml, Caddyfile, keycloak/realm-export.json, backup/
└── docs/
    ├── umsetzungsplan.md
    ├── verfahrensbeschreibung.md   «Erfassen und Archivieren» (GeBüV)
    └── adr/                 Architekturentscheide (ADR-001 …)
```

## Namen
- **Fachbegriffe auf Deutsch** in Klassen, Tabellen und REST-Pfaden: `Dokument`, `Dokumentart`, `Kontakt`, `Mandant`, `Eingangskorb`, `Auftrag`, `ImportLauf`, `ZugriffProtokoll`, `Manifest`.
- Technische Begriffe englisch: `Repository`, `Resource`, `Service`, `Config`, `Dto`.
- Klassen: `DokumentResource`, `DokumentService`, `DokumentRepository`, `DokumentDto`.
- Tabellen und Spalten `snake_case`, Singular: `dokument`, `dokument_finanz`, `import_lauf`.
- Enums als Text speichern: `status IN ('NEU','VERARBEITUNG','EINGANGSKORB','ARCHIVIERT','FEHLER')`.
- REST: Plural, Kleinschreibung: `/api/v1/dokumente`, `/api/v1/importe/crm`.
- Branches `feature/<spec>-<aufgabe>` (z. B. `feature/01-3.2-ocr`), Commits nach Conventional Commits, Text deutsch: `feat(verarbeitung): OCR mit OCRmyPDF (A5)`.

## Belegnummern (Erkennung und Validierung)
| Art | Muster | Beispiel |
| --- | --- | --- |
| Ausgangsrechnung | `^\d{4}-\d{3}$` | `2026-003` |
| Altbestand AbaNinja | `^R\d{4}$` | `R0001` |
| Offerte | `^O-\d{4}-\d{3}$` | `O-2026-005` |
| Lieferantenbeleg (AbaNinja) | `^F\d{4}$` | `F0003` |
