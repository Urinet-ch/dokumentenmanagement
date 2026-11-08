---
inclusion: always
---

# Struktur und Namen

## Repository `Urinet-ch/dokumentenmanagement`
```
dokumentenmanagement/
├── CLAUDE.md                Einstieg für Agenten (bindet die Steering-Dateien ein, Parallelbetrieb)
├── AGENTS.md                Verweis für GitHub Copilot auf CLAUDE.md (keine eigenen Regeln)
├── .kiro/
│   ├── steering/            product.md, tech.md, structure.md
│   └── specs/
│       ├── 01-archiv-kern/  requirements.md, design.md, tasks.md
│       ├── 02-abgleich-chat/
│       └── 03-zweiter-mandant/
├── backend/                 Quarkus (API + Worker), liefert die PWA über Quinoa aus
│   └── src/main/java/ch/urinet/archiv/
│       ├── mandant/         Mandant, TenantResolver, Provisionierung
│       ├── sicherheit/      Rollen, API-Tokens, Verschlüsselung
│       ├── dokument/        Dokument, Metadaten, Status, Versionen
│       ├── dokumentart/     Dokumentarten, Zusatzfelder, Aufbewahrung
│       ├── ablage/          S3-Zugriff, Object Lock, Prüfsummen
│       ├── verarbeitung/    Auftragsschlange, Normalisieren, OCR, Indexieren
│       ├── ki/              Metadaten-Extraktor, Beispiele, Embeddings, Chat (Spec 02)
│       ├── kontakt/         Kontakte, Verkaufschancen, Partner-Abgleich
│       ├── importe/         CSV-Import CRM, Drive-Import
│       ├── suche/           Volltext, Filter, später Hybridsuche
│       ├── protokoll/       Zugriffsprotokoll, Tages-Manifest
│       ├── export/          Jahresexport (Spec 02)
│       ├── integration/     abaninja/, mail/, wordpress/ (Spec 02/03)
│       └── gemeinsam/       Fehler (Problem Details), Zeit, Geld
│   └── src/main/resources/
│       ├── db/public/       Liquibase: Mandantenregister, Aufträge, API-Clients
│       ├── db/mandant/      Liquibase: Schema je Mandant
│       └── seed/urinet/dokumentarten.json   Stammdaten der Dokumentarten
│   └── src/test/resources/features/<fachbereich>/   Cucumber-Features (@req-<x.y>)
├── frontend/                Angular PWA (Build über Quinoa)
│   └── src/app/
│       ├── erfassen/  eingangskorb/  suche/  dokument/  importe/  chat/  verwaltung/
│       └── gemeinsam/       Auth, API-Client (aus OpenAPI generiert), Offline-Warteschlange
├── tools/
│   ├── archiv_client.py     Client für rechnung_gen.py und urinet_pdf.py
│   ├── drive_import/        Java-CLI (Picocli) für den Drive-Altbestand
│   └── golden_synth/        Python-Generator für synthetische Golden-Belege (Req 0.10); Ausgabe nur ausserhalb des Repos
├── wordpress/urinet-archiv-plugin/   (Spec 03)
├── deploy/
│   ├── infomaniak/compose.yml   Produktion: Dienste mit Traefik-Labels, Secrets als Dateien
│   ├── keycloak/realm-export.json
│   └── backup/              Verschlüsselung und Export für die zentrale Sicherung
├── .github/workflows/       CI, Deploy (SSH-Bundle, Health-Prüfung)
└── docs/
    ├── umsetzungsplan.md
    ├── verfahrensbeschreibung.md   «Erfassen und Archivieren» (GeBüV)
    ├── wiederherstellung.md        Anleitung zur Wiederherstellung (Req 16.3)
    ├── spikes/              Spike-Berichte aus Welle 0
    ├── vertrag/             Verweise auf AVV (Infomaniak, KI)
    └── adr/                 Architekturentscheide (ADR-001 …)
```

## Paketstruktur je Fachbereich (hexagonal)
Jeder Fachbereich unter `ch/urinet/archiv/<fachbereich>/` gliedert sich gleich:
```
<fachbereich>/
├── domain/          Records, Invarianten, Domain-Services – ohne Quarkus, JPA oder Jackson
├── application/     Anwendungsfälle und Ports (in: Use Cases, out: z. B. AblagePort, KiPort, OcrPort)
└── adapter/
    ├── in/rest/     DokumentResource, DTOs
    ├── in/job/      Auftrags-Handler des Workers
    └── out/         persistence/, s3/, ki/, ocr/, … – Implementierungen der Ports, je mit Dev-Stub
```
ArchUnit prüft: `domain` hängt von nichts ab, `application` nur von `domain`, `adapter` von `application` und `domain`; Fachbereiche greifen nur über `application` aufeinander zu.

## Namen
- **Fachbegriffe auf Deutsch** in Klassen, Tabellen und REST-Pfaden: `Dokument`, `Dokumentart`, `Kontakt`, `Mandant`, `Eingangskorb`, `Auftrag`, `ImportLauf`, `ZugriffProtokoll`, `Manifest`.
- Technische Begriffe englisch: `Repository`, `Resource`, `Service`, `Port`, `Adapter`, `Config`, `Dto`.
- Klassen: `DokumentResource`, `DokumentService`, `DokumentRepository`, `DokumentDto`, `AblagePort`.
- Tabellen und Spalten `snake_case`, Singular: `dokument`, `dokument_datei`, `dokument_finanz`, `import_lauf`.
- Enums als Text speichern: `status IN ('NEU','VERARBEITUNG','EINGANGSKORB','ARCHIVIERUNG','ARCHIVIERUNG_FEHLER','ARCHIVIERT','FEHLER','VERWORFEN')`; `vertraulichkeit IN ('NORMAL','INTERN','PERSONAL')`.
- REST: Plural, Kleinschreibung: `/api/v1/dokumente`, `/api/v1/dokumente/{id}/wiederholen`, `/api/v1/importe/crm`.
- API-Token-Scopes: `generator`, `import` (Spec 01), `wordpress` (Site-Key, Spec 03).
- Branches `feature/<spec>-<aufgabe>-<stichwort>` (z. B. `feature/01-2.6-ocr`), Commits nach Conventional Commits, Text deutsch, Verweis auf das Kriterium als `req-<x.y>`: `feat(verarbeitung): OCR mit OCRmyPDF (req-5.1)`. `A1`–`A14` bezeichnen nur die Zeilen der Rechtematrix (Spec 01, Requirement 2.6).

## Belegnummern (Erkennung und Validierung)
| Art | Muster | Beispiel |
| --- | --- | --- |
| Ausgangsrechnung | `^\d{4}-\d{3}$` | `2026-003` |
| Altbestand AbaNinja | `^R\d{4}$` | `R0001` |
| Offerte | `^O-\d{4}-\d{3}$` | `O-2026-005` |
| Lieferantenbeleg (AbaNinja) | `^F\d{4}$` | `F0003` |

Die Muster ändern die Art nur, wenn Urinet der Aussteller ist (eine Lieferantenrechnung `2026-123` bleibt Lieferantenrechnung).
