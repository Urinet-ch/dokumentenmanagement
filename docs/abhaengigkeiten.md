# Prüfung der Abhängigkeiten

Stand: 17.11.2026

## Vorgehen

- Frontend: `npm audit --omit=dev`, `npm audit` und `npm outdated`.
- Backend: `mvn versions:display-dependency-updates`; die Quarkus-BOM und gemeldete Schwachstellen wurden zusätzlich anhand der veröffentlichten Hinweise geprüft.
- Nur belegte Sicherheitsupdates ohne erwarteten API-Wechsel wurden übernommen. Gewöhnliche verfügbare Updates wurden nicht vorgezogen.

## Befunde

| Paket und Version | Befund | Ausnutzbarkeit im Archiv | Empfehlung |
| --- | --- | --- | --- |
| `@openapitools/openapi-generator-cli` 2.42.2 | Patchupdate von 2.42.1 (direkter Pin); behebt die veralteten transitiven `glob`- und `minimatch`-Versionen. Im Lockfile ziehen dadurch transitiv Hauptversionen nach: `glob` 13.0.6 → 14.0.4, `minimatch` 10.2.6 → 11.0.9 und `path-scurry` 2.0.2 → 3.0.0. Neu kommt das transitive Paket `@isaacs/nonrecursive-glob-match` 1.1.0 hinzu (von `minimatch` 11 verlangt), und die Node-Untergrenze von `minimatch` steigt von «18 \|\| 20 \|\| >=22» auf «20 \|\| >=22» (CI und Deploy-Workflow nutzen Node 22). Alles betrifft nur das Entwicklungswerkzeug `api:generieren`; `npm run api:generieren` erzeugt den Client unverändert (kein Diff in `src/app/gemeinsam/api`). Damit sind [GHSA-3g3m-2fjr-6pw4](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4) und [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249) behoben. | Entwicklungswerkzeug, aufgerufen durch `api:generieren`; kein Produktions-Endpunkt. | Auf 2.42.2 bleiben. Hinweis für Fabian: Die transitiven Hauptversionssprünge und das neue transitive Paket sind nur Dev-Werkzeug; bitte beim Merge bewusst akzeptieren. |
| `basic-ftp` 5.3.1, über `get-uri` 8.0.1, `pac-proxy-agent` 9.1.0 und `proxy-agent` 8.0.2 | Drei Denial-of-Service-Hinweise: [GHSA-c475-qrg2-pj4r](https://github.com/advisories/GHSA-c475-qrg2-pj4r) (Fix 6.2.1), [GHSA-5pm8-86m8-9m6r](https://github.com/advisories/GHSA-5pm8-86m8-9m6r) (Fix 6.2.4) und [GHSA-r4jh-29pc-gfqr](https://github.com/advisories/GHSA-r4jh-29pc-gfqr) (Fix 6.2.3). `npm audit` zählt fünf verwundbare Paketeinträge aus dieser Kette als hoch; der Patch des OpenAPI-Generators behebt sie nicht. `npm audit --omit=dev` findet keine Schwachstellen. | Ausschliesslich Entwicklungswerkzeug. Die FTP-Pfade setzen eine passende Proxy-/Netzwerkkonfiguration des Generators voraus; ein Laufzeitpfad im Archiv wurde nicht festgestellt. | Nicht mit `npm audit fix --force` aktualisieren: npm bietet dafür nur eine als SemVer-Änderung ausserhalb des bisherigen Bereichs markierte Generator-Version an. Bei einer kompatiblen Aktualisierung des Generator-Abhängigkeitsbaums erneut prüfen. |
| `io.opentelemetry:opentelemetry-api` 1.62.0 | [GHSA-ccrj-gw8r-854m / CVE-2026-118259](https://github.com/advisories/GHSA-ccrj-gw8r-854m): moderater Denial-of-Service durch übermässigen CPU-Aufwand bei manipuliertem `tracestate`; behoben ab 1.67.0. Die Version kommt zur Laufzeit transitiv über `quarkus-scheduler`. | Laufzeit-Abhängigkeit, aber die konkrete Anwendungspfad-Verarbeitung von `tracestate` ist nicht bestätigt. Falls aktiv, wäre die Eingabe nicht authentifiziert. | Die Quarkus-BOM 3.40.1.2 verwaltet 1.62.0. Kein abgestimmtes neueres BOM mit mindestens 1.67.0 war am Prüfdatum verfügbar. Keine einzelne OTel-Version ausserhalb der BOM erzwingen; mit einem passenden BOM erneut prüfen. |
| `com.google.guava:guava` 33.7.2-jre | [GHSA-xxph-c9ww-hj94 / CVE-2026-102554](https://github.com/advisories/GHSA-xxph-c9ww-hj94): hohe Schwere; ein manipulierter Java-Serialisierungsstrom kann unkontrollierte Speicherbelegung verursachen. Behoben ab 33.7.2. Sicherheitsupdate über `dependencyManagement`; die verwaltete Version der Quarkus-BOM war 33.6.0-jre. | Ausschliesslich Test-Scope über `quarkus-junit`; kein Produktionspfad festgestellt. | Auf 33.7.2-jre aktualisiert. Der Override fügt keine Runtime-Abhängigkeit hinzu. |
| Angular 22.2.5/22.2.6; `jsdom` 31.0.0; `playwright` 1.65.0; `prettier` 3.9.10 | `npm outdated` meldet: Angular 22.2.5 → 22.2.6 (`common`, `compiler`, `compiler-cli`, `core`, `forms`, `platform-browser`, `router`, `service-worker`) sowie 22.2.6 → 22.2.7 (`build`, `cdk`, `cli`, `material`); `jsdom` → 31.0.1, `playwright` → 1.65.1 und `prettier` → 3.10.1. Kein passender Sicherheitsbefund in `npm audit`. | Laufzeit oder Entwicklungswerkzeuge; die Audit-Prüfung ergab keine ausnutzbare Schwachstelle in diesen Paketen. | Nicht aktualisiert, da die Aufgabe Sicherheitsupdates und keine allgemeinen Versionspflege verlangt. |
| `@techstark/opencv-js` 4.12.0-release.1; TypeScript 6.0.3 | `npm outdated` meldet OpenCV 5.0.0-release.1 und TypeScript 7.0.2. Das sind Hauptversionswechsel; für TypeScript ist der Wechsel zusammen mit Angular zu entscheiden. Kein passender Sicherheitsbefund in `npm audit`. | Kein Sicherheitsbefund festgestellt. | Nicht aktualisiert; Entscheid zu Hauptversionen bleibt bei Fabian. |
| `quarkus-cucumber` 1.5.0, Quinoa 2.9.3, WireMock 3.13.2 | `mvn versions:display-dependency-updates` meldet `quarkus-cucumber` 2.0.0 und `quarkus-quinoa` 4.0.0. Beides sind Hauptversionssprünge; WireMock bleibt auf 3.13.2. Der Versionslauf ist kein Schwachstellenscanner. | Cucumber und WireMock sind Test-Abhängigkeiten; Quinoa gehört zur Frontend-Integration. | Cucumber und Quinoa nicht automatisch aktualisieren; als separate Kompatibilitätsarbeit mit gezielten Tests prüfen. Keine Version geändert. |

## Prüfergebnisse

- `npm audit --omit=dev`: keine Schwachstellen.
- Nach dem Generator-Patch meldet `npm audit` weiterhin fünf hoch eingestufte verwundbare Paket-Einträge aus der `basic-ftp`-Entwicklungskette; sie betreffen die drei oben aufgeführten Advisorys.
- `npm test`: 567 Tests in 56 Dateien bestanden.
- `npm run build`: erfolgreich; das Initial-Bundle überschreitet das Budget um 61.43 kB, identisch auf `origin/main` (Vergleichslauf), also nicht durch diese Änderung verursacht.
- `mvn dependency:tree -Dincludes=com.google.guava:guava` (ohne Scope-Filter) zeigt `guava:33.7.2-jre` nur mit Scope `test`.
- Der Pin in `backend/pom.xml` trägt einen Kommentar mit Ausstiegsbedingung; Wiedervorlage, sobald die Quarkus-BOM Guava >= 33.7.2 verwaltet.
- Backend-Tests, SpotBugs und Browser-Szenarien laufen in der CI dieses PR (Jobs `mvn verify` und `browser`); der Nachweis sind die grünen PR-Checks. Der Pin ändert nur die Testbibliothek Guava; ein lokaler `mvn verify` war wegen erschöpfter Docker-Netzwerkadressen nicht möglich.

## Nachprüfung (26.11.2026)

Diese Nachprüfung vergleicht `npm audit --omit=dev`, die Maven-Update-Vorschläge und offene Dependabot-PRs mit den Befunden vom 17.11.2026. Es wurden nur Bericht und Empfehlungen aktualisiert, keine Paketversionen geändert.

- `npm audit --omit=dev`: keine Schwachstellen in den Produktionsabhängigkeiten.
- `mvn versions:display-dependency-updates`: erfolgreich. Neben den beiden Hauptversionssprüngen oben meldet der Lauf zahlreiche neuere, Quarkus-BOM-verwaltete Versionen. Darunter `io.opentelemetry:opentelemetry-api` 1.62.0 → 1.68.0; das liegt über der in der bestehenden GHSA-Bewertung genannten Fix-Version 1.67.0. Der Vorschlag belegt jedoch kein dazu passendes Quarkus-BOM-Update. Die Runtime-Version deshalb weiterhin nicht einzeln überschreiben; mit einem kompatiblen Quarkus-BOM erneut prüfen.
- Die Versionsliste bietet auch Quarkus-Module von 3.40.1.2 → 4.0.1 an. Das ist ein Plattform-Hauptversionssprung und kein sicherer Einzelupdate-Kandidat; eine Umstellung braucht einen eigenen Entscheid und Migrationsprüfungen.
- Die Versionsliste enthält auch Patch-/Minor-Vorschläge für verwaltete Bibliotheken, zum Beispiel PostgreSQL JDBC 42.7.13 → 42.7.15 und Jackson Databind 2.21.8 → 2.23.0. Sie sind nicht als einzeln sichere Änderungen freigegeben; Updates über das abgestimmte BOM und die regulären Tests beurteilen.
- `gh pr list --state open --author app/dependabot`: keine offenen Dependabot-PRs. Damit gibt es aktuell weder einen sicherheitsrelevanten noch einen blossen Major-Update-PR zum Übernehmen.
