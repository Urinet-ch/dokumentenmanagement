# CI-Laufzeit und Stabilität – November 2026

Erhebung: 25.11.2026, 23:46 UTC. Quelle: die 30 jüngsten abgeschlossenen
Läufe des Workflows `CI` über alle Branches
(`gh run list --workflow CI --status completed --limit 30`) und die Job- und
Schrittzeiten aus `gh run view <lauf> --json jobs`. Das sind Workflow-Läufe,
nicht 30 verschiedene PRs; Entwurfs- und abgebrochene Läufe sind enthalten.

## Laufzeit und Ergebnisse

| Messung | Ergebnis |
| --- | ---: |
| Abgeschlossene Workflow-Läufe | 30 |
| Erfolgreich | 10 |
| Fehlgeschlagen | 2 |
| Übersprungen | 17 |
| Abgebrochen | 1 |
| Vollständige Browser-Läufe | 9 (7 erfolgreich, 2 fehlgeschlagen) |

Für die vollständigen Läufe ist die Zeitspanne vom frühesten gestarteten bis
zum letzten beendeten Job gemessen: Minimum 13.78 min, Median 15.28 min,
Mittelwert 15.44 min, Maximum 17.02 min. Die Annahme «20–25 Minuten je PR»
wird in diesem Snapshot nicht bestätigt. Spezifikationsläufe und
Entwurfs-PRs, deren CI übersprungen wurde, sind nicht in dieser Laufzeit
enthalten.

## Dauer je Job

Job-Zeiten in Minuten; Median und Maximum über tatsächlich ausgeführte Jobs
innerhalb des Snapshots. Übersprungene Jobs sind ausgeschlossen.

| Job | n | Median | Mittelwert | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Backend-Gruppe (gruppe-2) | 9 | 14.17 | 14.51 | 16.55 |
| Browser-Szenarien (Cucumber.js, Playwright) | 9 | 11.80 | 11.51 | 12.43 |
| Backend-Gruppe (gruppe-3) | 9 | 11.60 | 12.06 | 14.53 |
| Backend-Tests (JUnit, ArchUnit, SpotBugs) | 9 | 9.65 | 9.63 | 11.28 |
| Backend-Gruppe (gruppe-4) | 9 | 9.62 | 9.93 | 12.08 |
| Backend-Gruppe (gruppe-1) | 9 | 8.87 | 9.03 | 10.87 |
| Build (Quinoa, OpenAPI, Liquibase, Drive-Import) | 9 | 2.65 | 2.62 | 3.03 |
| WordPress-Plugin (PHP 8.2) | 9 | 2.38 | 2.44 | 3.20 |
| Spec-Prüfungen (nur Spec) | 12 | 1.53 | 1.77 | 2.53 |
| Docker-Images (nur bauen) | 9 | 1.07 | 1.13 | 2.07 |
| Frontend (ng test) | 10 | 0.93 | 0.94 | 1.17 |
| Backend-Tests vollständig (jede Klasse genau einmal) | 9 | 0.17 | 0.17 | 0.27 |
| Änderungsart (nur Spec?) | 13 | 0.17 | 0.16 | 0.22 |

## Längste Schritte

| Schritt | n | Median | Mittelwert | Maximum |
| --- | ---: | ---: | ---: | ---: |
| `mvn test` gruppe-2 (`start-2,szenarien-2`) | 9 | 13.92 | 14.27 | 16.35 |
| `mvn test` gruppe-3 (`start-3`) | 9 | 11.35 | 11.78 | 14.25 |
| `mvn verify` ohne Gruppen | 9 | 9.35 | 9.32 | 10.92 |
| `mvn test` gruppe-4 (`start-4`) | 9 | 9.27 | 9.65 | 11.87 |
| `mvn test` gruppe-1 (`start-1,szenarien-1`) | 9 | 8.63 | 8.75 | 10.68 |
| `npx cucumber-js` | 9 | 8.62 | 8.65 | 9.42 |
| Echtes Archiv für Upload-Szenarien starten | 9 | 1.52 | 1.69 | 2.45 |
| Build über Quinoa | 9 | 1.30 | 1.33 | 1.88 |
| `npm ci` im Browser-Job | 9 | 0.63 | 0.64 | 0.97 |

## Wiederholungen und fehlgeschlagene Läufe

Sechs Commit-SHAs kommen jeweils zweimal in den 30 Workflow-Einträgen vor.
In fünf Paaren steht einem erfolgreichen Lauf ein übersprungener Lauf
gegenüber; das sechste Paar besteht aus einem abgebrochenen und einem
übersprungenen Lauf. Kein SHA hat in diesem Snapshot zwei ausgeführte
vollständige Läufe. Die Duplikate sind daher keine belegten Wiederholungen
eines fehlgeschlagenen Testlaufs.

Beide fehlgeschlagenen vollständigen Läufe scheiterten im Browser-Job:

- [45714785683](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45714785683):
  «Offline und ohne Anmeldung erfassen; die Aufnahmen bleiben auf dem Gerät»;
  `locator.click` lief nach 30 Sekunden ab.
- [45691792909](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45691792909):
  «Passwort bleibt auch mit Passkey an TOTP gebunden»; `locator.waitFor` lief
  nach 60 Sekunden ab, danach meldete die Testbereinigung HTTP 401.

Die Fehlerursache ist durch diese Logs nicht belegt. Der erste Fehler ist
ein neuer Fehlerort im Offline-Erfassungsszenario; der frühere Bericht
([`ci-flakes-2026-11.md`](./ci-flakes-2026-11.md)) dokumentiert einen
anderen Timeout in demselben Szenario und den dazugehörigen Testschritt-Fix.
Der zweite Fehler tritt trotz der zuvor verlängerten Passkey-Wartefrist
erneut auf. Das sind beobachtete Fehler, aber keine Bestätigung, dass es
Flakes statt Produkt- oder Testregressionen sind.

Der referenzierte Flake-Bericht wertete zuvor 30 Läufe nur auf `main` aus
und fand 12 fehlgeschlagene Browser-Läufe. Der aktuelle Snapshot umfasst
alle Branches, spätere Änderungen und übersprungene Entwürfe; die Quoten
sind deshalb nicht direkt vergleichbar.

## Empfehlungen und Abgrenzung

1. **Gruppenbalance zuerst prüfen:** gruppe-2 hat mit 14.17 Minuten Median
   den längsten Job; gruppe-1 liegt bei 8.87 und gruppe-4 bei 9.62 Minuten.
   Die Differenz ist belegt. Eine neue Verteilung sollte erst mit
   Laufzeiten je Testklasse oder Szenario dimensioniert werden. Die
   vorhandene Einteilung verknüpft `start-2` mit `szenarien-2`; ohne solche
   Messwerte wäre eine Umverteilung geraten. Es wurde keine Gruppen- oder
   Workflow-Änderung vorgenommen.
2. **Keine weitere Maven-/npm-Cache-Empfehlung:** `ci.yml` richtet bereits
   Maven-Caches je Job sowie npm-Caches in den Frontend- und Browser-Jobs
   ein. `npm ci` dauert im Browser-Job im Median 0.63 Minuten. Die Messung
   belegt keinen zusätzlichen Cache-Schritt mit relevantem Einsparpotenzial.
3. **Keine pauschale Parallelisierung:** Die Jobs laufen bereits parallel;
   der Browser-Job hängt vom Build ab. Mehr Parallelität oder eine weitere
   CI-Gruppe erfordert eine Änderung der Workflow-Orchestrierung. Sie wird
   hier nicht umgesetzt, weil Workflow-Dateien ausserhalb des Auftrags
   liegen und für eine konkrete neue Gruppierung noch keine feineren
   Laufzeitdaten vorliegen.
4. **Fehler gezielt weiter beobachten:** Bei den beiden Browser-Fehlern
   fehlen unabhängige erfolgreiche Wiederholungsläufe derselben SHA.
   Zuerst sollten die Szenarien mit den bestehenden Logs und
   Testschritten weiter beobachtet werden; ein Retry würde die Ursache
   nicht belegen.

An `.github/workflows/` wurde nichts geändert. Da keine konkrete
Workflow-Änderung vorgeschlagen oder umgesetzt wird, gibt es in diesem PR
keinen Workflow-Diff unter `docs/`.
