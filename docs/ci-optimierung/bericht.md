# CI- und Testzeit: Messung vom 20.11.2026

## Umfang und Ergebnis

Ausgewertet wurden die 30 jüngsten Läufe von `ci.yml` auf `main` und PR-Branches
sowie die 30 jüngsten Läufe von `Secret-Scan`. Laufzeiten der CI sind vom ersten
Jobstart bis zum Ende des letzten Jobs gemessen; damit ist Runner-Wartezeit
enthalten. Job- und Schrittzeiten stammen aus den Actions-Berichten.

| Stichprobe | Läufe | Median | P90 | Spannweite |
| --- | ---: | ---: | ---: | ---: |
| `ci.yml`, vollständig beendet | 19 | 18,0 min | 25,6 min | 10,1–38,3 min |
| `Secret-Scan`, Workflowdauer | 30 | 16 s | 83 s | 10–110 s |

Von 30 CI-Läufen waren 10 übersprungen oder abgebrochen, einer lief noch.
Vier der 19 beendeten vollständigen Läufe waren rot. Zwei davon waren zweite
Versuche und scheiterten an den Surefire-Berichten; zwei scheiterten an der
Seed-Frist für die Mandantenkonfiguration. Der Seed-Fehler und die
Versuchsnummern-Kollision wurden am selben Tag mit #365 behoben. Die danach
beobachteten Läufe waren grün. Im betrachteten Zeitraum gab es keinen
nachgewiesenen Fehler wegen Maven Central oder eines Docker-Netzwerk-Pools.
Secret-Scan hatte keine fehlgeschlagenen Läufe.

## Kritischer Pfad

Der längste reguläre CI-Job ist `Backend-Gruppe (gruppe-4)`. In einem
repräsentativen grünen Lauf (44771811210) dauerte der gesamte Lauf 11,1 min;
Gruppe 4 lief 9,6 min. `Backend-Tests vollständig` startete rund 2 s nach dem
Ende der Gruppen. Auch der Browser-Job startete im Beispiel direkt nach dem
Build-Artefakt. `needs` verursacht in diesem Lauf somit kaum Leerlauf.

Ein langsamer Lauf (44764394051) dauerte 38,3 min. Gruppe 4 startete erst
24,6 min nach dem ersten Job, obwohl sie nur von «Änderungsart» abhängt und
diese nach 0,3 min fertig war. Das spricht für Runner-Warteschlange und nicht
für eine notwendige Abhängigkeit. Die Actions-Zeitstempel erlauben keine
zuverlässige Trennung von Runner-Queue und sonstiger Scheduling-Verzögerung.
Der P90 wird daher nicht allein durch kürzere Tests sinken.

| Job in `ci.yml` | n | Median | P90 |
| --- | ---: | ---: | ---: |
| Backend-Gruppe (gruppe-4) | 22 | 9,6 min | 13,4 min |
| Backend-Gruppe (gruppe-3) | 22 | 8,5 min | 11,5 min |
| Backend-Gruppe (gruppe-1) | 22 | 8,0 min | 8,3 min |
| Backend-Gruppe (gruppe-2) | 22 | 6,9 min | 7,7 min |
| Backend-Tests (JUnit, ArchUnit, SpotBugs) | 30 | 6,2 min | 8,3 min |
| Browser-Szenarien (Cucumber.js, Playwright) | 30 | 5,5 min | 6,6 min |
| Build (Quinoa, OpenAPI, Liquibase, Drive-Import) | 30 | 1,9 min | 2,2 min |
| Spec-Prüfungen (nur Spec) | 30 | 0,9 min | 1,2 min |
| Frontend (ng test) | 30 | 0,8 min | 0,9 min |
| Docker-Image (nur bauen) | 30 | 0,7 min | 0,8 min |
| Backend-Tests vollständig | 29 | 0,1 min | 0,3 min |
| Änderungsart (nur Spec?) | 30 | 0,1 min | 0,2 min |

`Secret-Scan` hat zwei unabhängige Jobs: `gitleaks` (Median 10 s, P90 13 s)
und `leitplanken` (Median 5 s, P90 8 s). Die längere Workflowdauer stammt
überwiegend aus Wartezeit zwischen Workflow-Erstellung und Jobabschluss.
`CI-Fehler` ist ein nachgelagerter Meldeworkflow und kein Teil des grünen
Prüfpfads.

## Schritte und Caches

| Schritt | Median | Messhinweis |
| --- | ---: | --- |
| Checkout | 5 s | über die beobachteten Jobstarts |
| Maven-Cache wiederherstellen | 1–4 s | Cache-Hit im repräsentativen vollständigen Lauf |
| `mvn verify` ohne Gruppen | 6,0 min | JUnit, ArchUnit, Abdeckung und SpotBugs |
| `mvn test`, Gruppe 4 / 3 / 1 / 2 | 9,4 / 8,2 / 7,7 / 6,6 min | je 21 Messwerte |
| Quinoa-Build | 58 s | derselbe Produktionsbuild wird als Artefakt weitergereicht |
| Drive-Import `mvn verify` | 14 s | Teil des Build-Jobs |
| `npm ci` im Frontend-Testjob | 7 s | npm-Cache-Treffer |
| `npm ci` plus Playwright-Installation | 54,5 s | ein gemeinsamer Schritt im Browser-Job |
| BrowserBackendTest starten | 1,4 min | Dev Services, Mandant und Datenbank |
| `npx cucumber-js` | 3,0 min | Browser-Szenarien |
| Docker-Image bauen | 29 s | verwendet das Quarkus-Artefakt aus dem Build |
| Surefire-Artefakte hochladen | rund 2 s | kein kritischer Pfad |

`setup-java` und `setup-node` haben Maven- beziehungsweise npm-Caches bereits
aktiviert. Im Referenzlauf meldeten alle Cache-Schritte einen Treffer. `npm ci`
läuft in den separaten Jobs weiterhin, aber die Paketauflösung ist nicht der
Engpass. Der Browser-Schritt kombiniert Installation und Playwright-Aufruf;
seine 54,5 s sind deshalb nicht die reine Browser-Downloadzeit.

Es gibt keinen doppelten Produktionsbuild: Quinoa baut einmal, Browser- und
Docker-Job verwenden dasselbe Artefakt. `ng test` kompiliert den separaten
Frontend-Testlauf. Die PR-Läufe mit identischem SHA waren Draft-Skip gefolgt von
Ready-Lauf; im Stichprobenfenster wurde kein doppelter vollständiger
`push`-/`pull_request`-Lauf desselben SHA gefunden. Draft-Skip und Abbruch
veralteter Läufe desselben PR sind bereits aktiv. `nur-spec.py` deckt reine
Markdown-Änderungen unter `.kiro/` und `docs/` ab; zusätzliche `paths-ignore`
würden diese strenge Positivliste nicht sicherer machen.

## Backend-Tests

Im erfolgreichen Referenzlauf 44771811210 enthielten die Surefire-Artefakte
414 Berichte mit 4'293 Testfällen. Die beiden Szenario-Runner verarbeiteten
766 und 776 Szenarien in je rund 3,5 min; ihre Verteilung ist ausgewogen. Die
43 Startgruppen-Tags liegen in den Quelltests mit 7 / 7 / 14 / 15 Klassen auf
`start-1` bis `start-4` (ohne die Gegenproben in `StartGruppenTest`).

Die CI-Logs desselben Laufs enthalten 33 Quarkus-Startmarker (`Installed
features`), 70 Compose-Netzwerk-Erstellungszeilen und 280
Container-Erstellungszeilen. Das sind Logereignisse, keine Zählung eindeutiger
Ressourcen. `RealmExportKeycloakTest` ist der Testcontainers-basierte
Keycloak-Test; die übrigen Testdienste laufen über Compose Dev Services. Im
Surefire-Lauf ist die Differenz zwischen Testsuite- und Testfallzeit in Gruppe
4 rund 149 s, gegenüber 101–123 s in den anderen Gruppen. Sie umfasst
Klassen-Lifecycle und Testframework, nicht nur Quarkus-Start.

Die Logs enthalten 171 erfolgreiche Liquibase-Update-Zusammenfassungen. Sie
lassen sich nicht belastbar einzelnen Starts zuordnen, da mehrere Schemas und
parallel laufende Jobs ineinander protokollieren; eine genaue
Liquibase-Dauer je Start ist mit den vorhandenen Berichten nicht messbar.
Eine Änderung, welche Migrationen oder Teststarts überspringt, ist darum nicht
empfohlen.

Sichtbare Zeitabhängigkeiten und Sleeps wurden gesichtet. Die längsten
Einzelklassen im Referenzlauf waren `VerarbeitenZeitgrenzeTest` (47 s
Testfallzeit, 71 s Testsuitezeit), `WebseitenDateiMessfallTest` (45 s),
`MandantKommandoTest` (44 s) und `MailKommandoStartTest` (40 s). Die Sleeps
und Wartebedingungen prüfen dort Zeitgrenzen, Drosselung oder Nebenläufigkeit;
sie werden nicht pauschal entfernt. Sicherheits-, Rechte-, Mandanten-,
Lösch- und Archivierungsszenarien bleiben vollständig erhalten.

## Massnahmen, nach Einsparung pro Aufwand

| Massnahme | Einsparung | Risiko | Wer setzt um |
| --- | --- | --- | --- |
| Verteilung der Starttests aus Gruppe 4 neu ausbalancieren | geschätzt 0,8–1,2 min vom normalen kritischen Pfad; Queue-P90 unverändert | niedrig: nur Gruppen-Tags, gleiche Tests; mit mehreren Vorher-/Nachher-Läufen messen | Copilot |
| Veraltete `main`-CI-Läufe bei neuem `main`-Push abbrechen | keine garantierte Grundzeit; kann unter Queue-Druck bis zu 24 min beobachtete Wartezeit vermeiden | mittel: ältere SHA haben dann keinen eigenen grünen Push-Lauf; aktueller `main`-Kopf muss weiterhin vollständig grün sein | Fabian |
| Doppelte Spec-Testausführung im vollen Lauf auslassen, Spec-Job aber erfolgreich lassen | rund 0,7 Runner-Minuten; Wandzeit nur bei Queue-Druck | mittel: `NurSpecTest` anpassen und Deploy-Nachweis für Jobabschluss prüfen | Fabian |
| Playwright-Browser cachen | nicht empfohlen; Einsparung nicht belegt | Cache-Restore kann so lange dauern wie Download, Linux-Systemabhängigkeiten bleiben uncachebar | — |

Die Workflow-Vorschläge samt Diff stehen in
[workflow-vorschlaege.md](./workflow-vorschlaege.md). Es wurden keine
Workflowdateien geändert.

## Zeitabschätzung

Gemessener Ausgangswert: **18,0 min Median**, **25,6 min P90**. Die
Tag-Umverteilung ergibt für Läufe ohne Scheduler-Queue einen vorsichtigen
Zielwert von **rund 17 min Median**. Für den P90 ist ohne Umsetzung und neue
Messreihe keine belastbare Zahl möglich; die beobachtete Queue kann die
Einsparung vollständig überdecken. Das Abbrechen überholter `main`-Läufe könnte
den P90 stärker beeinflussen, ist aber aus der vorliegenden Stichprobe nicht
seriös quantifizierbar.
