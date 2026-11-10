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
Voraussetzungen: Java 25, Maven 3.9, Node 22 mit npm, Docker mit `docker compose` (Dev Services in Tests und dev).

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

## Entwicklung lokal
```bash
cd frontend && npm ci && cd ..
cd backend && mvn quarkus:dev
```
- Startet über Dev Services PostgreSQL 17 mit pgvector, Keycloak mit dem Realm `urinet-archiv` aus
  `deploy/keycloak/realm-test.json` sowie MinIO (Object Lock) und Mailpit aus `backend/compose-devservices.yml`.
  Die Adressen zeigt die Dev UI unter `http://localhost:8080/q/dev-ui`. Mailpit läuft auf einem zufälligen Host-Port:
  Weboberfläche über `archiv.entwicklung.mailpit-port` (Dev UI, Configuration) oder
  `docker compose -p urinet-archiv-dev port mailpit 8025`.
- Im Profil `dev` laufen Ablage, KI, OCR und Integration als Dev-Stubs (`archiv.<port>.stub=true`).
- Profil `lokal` (nur Entwicklerrechner, ADR-012): Sprachmodell über LM Studio auf `127.0.0.1`, Start mit
  `mvn quarkus:dev -Dquarkus.profile=lokal`. Anleitung und Verbote: [backend/README.md](backend/README.md),
  «Lokales Modell (LM Studio)».
- Testbenutzer (nur im Test-Realm, nie im Export für Stufe A und B), Passwort `Test-Passwort-1`, TOTP mit dem
  Test-Secret aus `realm-test.json` (`urinet-archiv-test-otp-nur-lokal`; für eine Authenticator-App als Base32
  `OVZGS3TFOQWWC4TDNBUXMLLUMVZXILLPORYC23TVOIWWY33LMFWA`):

  | Benutzer | Rolle | Mandant |
  | --- | --- | --- |
  | `inhaber-urinet` | `inhaber` | `urinet` |
  | `mitarbeiter-urinet` | `mitarbeiter` | `urinet` |
  | `treuhaender-urinet` | `treuhaender` | `urinet` |
  | `inhaber-vhf-gsk` | `inhaber` | `vhf-gsk` |
  | `geschaeftsstelle-vhf-gsk` | `geschaeftsstelle` | `vhf-gsk` |

- Secrets liegen nur als Dateien vor (`/run/secrets/`, tech.md Regel 7), nie in Umgebungsvariablen oder im Repo. Die
  festen Zugangswerte der Dev-Container und des Test-Realms sind keine Secrets und gelten nur lokal.

## Mandant anlegen
```bash
java -jar quarkus-run.jar mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT]
```
- Im laufenden Container per `exec`; das Kommando startet keinen HTTP-Server und keine Start-Migration, braucht aber
  die Secret-Dateien `archiv_db_app`, `archiv_db_owner`, `s3_app_zugang_id` und `s3_app_zugang_geheimnis` unter
  `/run/secrets/`; fehlt eine oder ist sie leer, bricht der Start ab. Seed: `seed/<code>/dokumentarten.json`.
- Exit-Code 0 angelegt, 1 fachlicher Fehler (`MandantFehler`, z. B. `MANDANT_EXISTIERT`), 2 falscher Aufruf (geprüft
  vor dem Start, mit Aufrufhilfe), 3 technischer Fehler, auch ein abgebrochener Start (design.md, «Mandant»).

## API-Token für Skripte (Generator-Client, Drive-Import)
Ein API-Token `ua_<id>_<geheimnis>` gilt für genau einen Mandanten und einen Scope (`generator` oder `import`) und
erreicht nur die Endpunkte dieses Scopes (Req 2.5). Das Archiv speichert nur den Argon2id-Hash; das Token wird nur beim
Anlegen ein einziges Mal angezeigt. Anlegen, Auflisten und Widerrufen darf nur der `inhaber` (A14, Req 2.20): in der
Verwaltung (`/api/v1/api-tokens`) oder per Kommando (im Container per `exec`, wie `mandant anlegen`; stdout in eine
neue Datei mit Rechten 600 umleiten und erst bei Erfolg an ihren Platz schieben, damit ein Fehlschlag kein gültiges
Token überschreibt):
```bash
mkdir -p ~/.config/urinet-archiv
(umask 077; java -jar quarkus-run.jar token erstellen <mandant> <name> <generator|import> > token.neu) \
  && mv token.neu ~/.config/urinet-archiv/token
```
- Das Token steht nur auf stdout; die Zeile mit ID, Mandant und Scope und das Log gehen auf stderr. Exit-Codes wie bei
  `mandant anlegen` (0 angelegt, 1 fachlicher Fehler, 2 falscher Aufruf, 3 technischer Fehler); nach einem Fehler
  `token.neu` löschen.
- Entsteht das Token nicht auf dem Client-Rechner (Kommando auf dem Server, Anzeige in der Verwaltung), kommt es nur
  über einen sicheren Kanal dorthin (z. B. Passwort-Manager oder `scp`), nie per E-Mail, Chat oder Ticket; auf dem
  Client wie oben mit `umask 077` in `token.neu` schreiben und verschieben.
- Lokale Clients lesen das Token aus `~/.config/urinet-archiv/token` (Rechte 600) oder aus der Datei, deren Pfad
  `ARCHIV_TOKEN_DATEI` nennt – **nie** das Token selbst aus einer Umgebungsvariable (tech.md Regel 7), nie im Repo.
- Ein verlorenes oder offengelegtes Token sofort widerrufen (`DELETE /api/v1/api-tokens/{tokenId}`) und neu anlegen.

## Leitplanken
Die Leitplanken stammen aus der Harness-Vorlage der Skill-Werkstatt (`~/git/ai-entwicklung/vorlagen/harness`) und
werden nur mit deren Werkzeug ausgerollt, nie von Hand geändert (ausser `.claude/harness.yml`, die dem Repo gehört).

```bash
brew install gitleaks          # der pre-commit-Hook bricht ohne gitleaks ab
git config core.hooksPath      # muss «.githooks» melden (setzt harness.sh)
ZIEL=$PWD ~/git/ai-entwicklung/werkzeuge/harness.sh --diff   # Drift-Check: überall «identisch»
```

- `pre-commit` prüft jeden Commit mit gitleaks; ein Commit mit Secret wird abgewiesen.
- `pre-push` sperrt `main` lokal: Branch-Schutz gibt es im GitHub-Free-Plan für private Repos nicht, darum geht
  alles über PRs (Entscheid Fabian 09.11.2026).
- Der Workflow «Secret-Scan» prüft jeden Push auf allen Branches mit gitleaks, «CI-Fehler» legt bei rotem `main` ein
  Issue an.
- Claude-Code-Hooks (`.claude/settings.json`) fragen beim Bearbeiten mit Edit/Write nach, bevor Leitplanken,
  Secret-Dateien oder geschützte Pfade aus `.claude/harness.yml` (Liquibase-Changesets, Realm-Export,
  `.github/scripts/`) geändert werden. Änderungen per Bash prüft der Hook nicht; die eigentliche Sperre ist die CI.
- Dependabot (`.github/dependabot.yml`) hält Actions (per SHA gepinnt), Docker-Images, npm und Maven wöchentlich aktuell.
