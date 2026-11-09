# UriNet Archiv

Repository `Urinet-ch/dokumentenmanagement`. Mandantenfähiges Dokumentenarchiv mit KI-Erfassung und Chat-Suche für Urinet Aschwanden
(Mandant 1) und den Verband VHF/GSK (Mandant 2). Java/Quarkus, Angular-PWA, PostgreSQL,
Infomaniak (Schweiz).

## Spec-driven Development im Kiro-Stil, umgesetzt mit Claude Code
Die Specs folgen dem Kiro-Format; umgesetzt wird mit Claude Code, nicht mit dem Werkzeug Kiro.
- `CLAUDE.md` – Einstieg und Arbeitsweise für Claude Code und GitHub Copilot (bindet die Steering-Dateien ein, Parallelbetrieb)
- `AGENTS.md` – Verweis für GitHub Copilot auf `CLAUDE.md`
- `.kiro/steering/` – Produkt, Technik, Struktur (in jeder Sitzung aktiv)
- `.kiro/specs/01-archiv-kern/` – Phase 0 und 1
- `.kiro/specs/02-abgleich-chat/` – Phase 2
- `.kiro/specs/03-zweiter-mandant/` – Phase 3
- `docs/umsetzungsplan.md` – Wellen, Crew, Gates, Zeitplan, Risiken

Eine Spec ist freigegeben, wenn Fabian den PR auf `main` merged. Start: `docs/umsetzungsplan.md`, Abschnitt 3.

## Bauen und Testen
Voraussetzungen: Java 25, Maven 3.9, Node 22 mit npm, Docker (nur für das Image).

```bash
# Frontend-Abhängigkeiten (einmalig bzw. nach Änderungen an package-lock.json)
cd frontend && npm ci && cd ..

# Backend: Cucumber-Szenarien, JUnit, ArchUnit, Abdeckung @req; baut die PWA über Quinoa ins Artefakt
mvn -f backend/pom.xml verify

# Frontend-Unit-Tests
cd frontend && npx ng test --watch=false && cd ..

# OpenAPI-Client neu erzeugen (nach jedem Backend-Build, der die API ändert; Ergebnis committen)
cd frontend && npm run api:generieren && cd ..

# Browser-Szenarien (@browser) gegen den laufenden Build
java -jar backend/target/quarkus-app/quarkus-run.jar &
cd frontend && npx playwright install chromium && npx cucumber-js && cd ..

# Docker-Image (eines für beide Laufarten, archiv.rolle)
docker build -f backend/src/main/docker/Dockerfile.jvm -t urinet-archiv backend

# Bestehende Liquibase-Changesets unverändert gegenüber main
.github/scripts/pruefe-liquibase.sh origin/main
```

Die CI (`.github/workflows/ci.yml`) führt dieselben Schritte bei jedem PR aus, ohne Secrets, ohne echte KI und
ohne Golden-Lauf. Die Szenario-Prüfung «Abdeckung» verlangt für jedes Kriterium unter `_Anforderungen_` einer
abgehakten Aufgabe ab Welle 1 ein Szenario `@req-x.y` oder einen Nachweis-Vermerk in `requirements.md`.
