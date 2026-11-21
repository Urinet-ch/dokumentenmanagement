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
766 und 776 JUnit-Testfälle in je rund 3,5 min; das entspricht 721 und 731
Cucumber-Szenarien. Ihre Verteilung ist ausgewogen. Die
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
| Verteilung der Starttests aus Gruppe 4 neu ausbalancieren | 0,7–1,0 min im Gruppenpfad; zwei Nachher-Läufe | niedrig: nur Gruppen-Tags, gleiche Tests; Gesamtmedian/P90 bleibt wegen kleiner Stichprobe offen | Copilot |
| Veraltete `main`-CI-Läufe bei neuem `main`-Push abbrechen | keine garantierte Grundzeit; kann unter Queue-Druck bis zu 24 min beobachtete Wartezeit vermeiden | mittel: ältere SHA haben dann keinen eigenen grünen Push-Lauf; aktueller `main`-Kopf muss weiterhin vollständig grün sein | Fabian |
| Doppelte Spec-Testausführung im vollen Lauf auslassen, Spec-Job aber erfolgreich lassen | rund 0,7 Runner-Minuten; Wandzeit nur bei Queue-Druck | mittel: `NurSpecTest` anpassen und Deploy-Nachweis für Jobabschluss prüfen | Fabian |
| Playwright-Browser cachen | nicht empfohlen; Einsparung nicht belegt | Cache-Restore kann so lange dauern wie Download, Linux-Systemabhängigkeiten bleiben uncachebar | — |

Die Workflow-Vorschläge samt Diff stehen in
[workflow-vorschlaege.md](./workflow-vorschlaege.md). Es wurden keine
Workflowdateien geändert.

## Zeitabschätzung

Gemessener Ausgangswert: **18,0 min Median**, **25,6 min P90**. Die
Tag-Umverteilung sollte für Läufe ohne Scheduler-Queue rund eine Minute sparen.
Der Zielwert von **rund 17 min Median** bleibt eine Schätzung, nicht eine neue
Messung des vollständigen CI-Laufs. Für den P90 ist keine belastbare Zahl
möglich; die beobachtete Queue kann die Einsparung vollständig überdecken. Das
Abbrechen überholter `main`-Läufe könnte den P90 stärker beeinflussen, ist aber
aus der vorliegenden Stichprobe nicht seriös quantifizierbar.

## Nachher-Messung der Startgruppen (PR #372)

Nach der Verschiebung von `VerarbeitenZeitgrenzeTest` und `MandantKommandoTest`
von `start-4` nach `start-2` wurden zwei vollständige CI-Läufe desselben Commits
gemessen. Die Werte sind Jobdauern einschliesslich Einrichtung:

| Gruppe | Vorher Median (n=22) | Nachher Lauf 1 | Nachher Lauf 2 | Nachher Median (n=2) |
| --- | ---: | ---: | ---: | ---: |
| 1 | 8,0 min | 9,0 min | 7,9 min | 8,4 min |
| 2 | 6,9 min | 8,5 min | 8,7 min | 8,6 min |
| 3 | 8,5 min | 8,2 min | 7,8 min | 8,0 min |
| 4 | 9,6 min | 7,6 min | 8,9 min | 8,2 min |

Der längste Gruppenjob dauerte 9,0 bzw. 8,9 Minuten; der bisher langsamste
Job hatte einen Median von 9,6 Minuten. Die zwei Nachher-Läufe deuten auf rund
0,7–1,0 Minuten weniger im Gruppenpfad und eine ausgeglichenere Verteilung hin.
Sie reichen nicht, den Median oder P90 der vollständigen CI-Laufzeit neu zu
bestimmen. Zwischen den beiden vollständigen Messläufen wurde nur der wegen
Maven Central HTTP 429 fehlgeschlagene Backend-Verify-Job erneut ausgeführt;
der Retry war erfolgreich und zählt nicht als zusätzlicher Gruppenlauf.

## Vertiefung: Starts und Netzwerke (21.11.2026)

Ausgangswerte: die grünen `main`-Läufe 44899529866 und 44898942261.
Beide protokollieren in den Gruppen 1 bis 4 jeweils **5 / 7 / 11 / 12**
erstellte Compose-Netze. Gezählt sind `Network … Created`-Meldungen, nicht
gleichzeitig vorhandene Netze. Es gibt im Testcode keinen Aufruf von
`Network.newNetwork()`; Quarkus erzeugt die Compose-Projekte für MinIO und
Mailpit, PostgreSQL und Keycloak werden an deren Standardnetz angeschlossen.

Ein lokaler Ausgangslauf von `VerarbeitenZeitgrenzeTest` war grün und erzeugte
ein Compose-Netz; das Netz war unmittelbar nach Maven-Ende noch sichtbar, beim
nächsten Blick nicht mehr. Die Gesamtzahl anderer Netze änderte sich während
des Laufs durch den gemeinsam genutzten Docker-Host stark; daraus lässt sich
kein eigener Vorher-Nachher-Wert für die Gesamtzahl ableiten.

**Nicht umgesetzter Versuch:** `network_mode: bridge` für beide Compose-Dienste
erzeugte kein Compose-Netz, aber PostgreSQL und Keycloak scheiterten beim Start:
Quarkus erwartet das projektspezifische Standardnetz. Der Versuch wurde
zurückgenommen. `quarkus.compose.devservices.reuse-project-for-tests=true`
würde laut Quarkus-Dokumentation auch ein bestehendes Entwicklungsprojekt
wiederverwenden; auf dem gemeinsam genutzten Rechner wäre Testisolation damit
nicht belegt. Ein JVM-eigenes Projekt mit sicherer Lebensdauer und Aufräumung
ist noch zu untersuchen. Keine Netzwerke anderer Läufe wurden entfernt.

Ein späterer lokaler `mvn verify` protokollierte bis zum Abbruch 25 verschiedene
Ryuk-Container. Ein zum Lauf gehörender Keycloak-Container endete mit Exit 137;
Docker meldete `OOMKilled=true`. Danach scheiterten Szenarien an nicht
erreichbaren Testdiensten. Der Lauf wurde abgebrochen, nicht als Messung eines
vollständigen Laufs gewertet. Nach dem Abbruch waren die drei anhand ihrer IDs
geprüften Testcontainer entfernt. Ob die vielen Ryuk-Container den
Speicherabbruch verursachten, ist nicht nachgewiesen.

### Weitere Messungen

Browserprozess-Wiederverwendung (#407): Die Browserjobs der beiden
Ausgangsläufe dauerten 5 min 37 s und 5 min 53 s. Im grünen Lauf 44903211310
dauerten Versuch 1 und 2 nach der Änderung 4 min 45 s und 5 min 22 s. Die
Mittelwerte der je zwei Stichproben sind 5 min 45 s und 5 min 3,5 s; die
Differenz beträgt 41,5 s. Cucumber benötigte vorher 186,5 s und 200,7 s,
nachher 166,652 s und 172,126 s. In beiden Nachher-Versuchen waren alle
154 Szenarien und 2806 Schritte grün. Die kleinen Stichproben belegen keine
Langzeitstreuung.

Kommando-Starttests (#409): In denselben beiden `main`-Läufen dauerten die
beiden Klassen zusammen 99,31 s und 107,88 s. In den zwei Versuchen des Laufs
44903693361 dauerte die gebündelte Klasse 101,4 s und 68,32 s; alle vier
Testmethoden waren grün. Die Mittelwerte sind 103,60 s und 84,86 s, bei
deutlicher Streuung. Gruppe 4 meldete vorher in beiden Läufen 12 und nachher
in beiden Versuchen 11 Compose-Netzerstellungen. Beide vollständigen
Nachher-Versuche waren grün.

Ein lokaler Vergleich der vollständigen Startgruppe 4 erzeugte im Standardlauf
12 Compose-Netze und mit einem eigens gewählten, zufälligen Projektnamen sowie
`quarkus.compose.devservices.reuse-project-for-tests=true` eines. Beide Läufe
hatten 38 grüne Tests; das eigene Netz war 20 Sekunden nach Ende des
Wiederverwendungslaufs entfernt. Der Standardlauf zeigte 4 Netze vorher, 16
unmittelbar nachher und 4 nach 20 Sekunden auf dem gemeinsam genutzten Host.
Ein weiterer Wiederverwendungslauf der Gruppe 1 mit 779 Tests einschliesslich
766 Cucumber-Szenarien war grün, erzeugte ein Compose-Netz und hatte es
15 Sekunden nach Laufende entfernt. Das sind lokale Einzelmessungen, keine
CI-Nachweise. Bei einer separaten kontrollierten Abbruchprobe mit aktiviertem
Ryuk waren das eigene Netz und fünf vorher anhand ihrer IDs identifizierte
Container nach sieben Sekunden entfernt; die Probe belegt nicht jede mögliche
Abbruchart.

**Nicht umgesetzt:** Ein eindeutiger Projektname vermeidet die Wiederverwendung
fremder Projekte, aber MinIO und Mailpit behalten damit Daten zwischen
verschiedenen Quarkus-Profilen. Die grünen Gruppen 1 und 4 belegen nicht die
Isolation aller Tests. Die Option wird deshalb nicht generell aktiviert; ein
isolierter Lebenszyklus für Testdaten bleibt zu klären.

Die erfolgreichen Startmeldungen im Lauf 44899529866 ergeben je Gruppe
6 / 8 / 7 / 12 Meldungen. Deren gemeldete Zeiten in Sekunden sind:

| Gruppe | Zeiten je Meldung |
| --- | --- |
| 1 | 53,884; 1,291; 2,571; 1,179; 2,552; 2,472 |
| 2 | 70,423; 6,420; 3,410; 2,730; 2,870; 2,703; 53,616; 0,843 |
| 3 | 81,158; 3,863; 3,330; 2,199; 3,313; 3,280; 88,612 |
| 4 | 103,338; 4,760; 4,162; 4,287; 4,186; 3,967; 3,974; 3,993; 65,530; 40,424; 1,131; 0,869 |

Dies sind nur erfolgreiche Quarkus-Logmeldungen, **nicht** alle Startversuche:
absichtlich fehlschlagende Starttests haben keine solche Meldung. Die angegebene
Zeit ist nicht gleich der gesamten Klassenzeit; Augmentation und Testvorbereitung
können ausserhalb der jeweiligen Meldung liegen.

Das Tag-Inventar am untersuchten Stand umfasst folgende Klassen; nicht jede
getaggte Klasse wird im Surefire-Lauf tatsächlich ausgeführt:

- Gruppe 1: `AblageStufeApiStartTest`, `BereitschaftOhneDatasourceTest`,
  `BereitschaftOhneDatenbankTest`, `SecretDateienTest`, `WebseitenDateiMessfallTest`,
  `MandantPraefixTest`.
- Gruppe 2: `AblageStufePAdminStartTest`, `AblageStufePApiStartTest`,
  `SecretDateiFehltTest`, `JobsVorMigrationTest`, `MandantKommandoTest`,
  `MigrationBeimStartTest`, `ApiTokenUeberlastTest`, `KeycloakVerwaltungSpikeTest`,
  `SicherungPaketRoundtripTest`, `VerarbeitenZeitgrenzeTest`.
- Gruppe 3: `GoogleAnmeldungSzenarienTest`, `AblageStufePWorkerStartTest`,
  `OidcBereitschaftTest`, `PortAuswahlStartTest`, `WissenssucheLeistungIT`,
  `MandantBerichtSzenarienTest`, `MandantKommandoStartfehlerTest`,
  `MigrationWorkerTest`, `ManifestKommandoOhneDatenbankTest`, `KeinPlatzStartTest`,
  `RealmExportKeycloakTest`, `TokenKommandoStartTest`, `WartezeitNegativStartTest`,
  `SecretEigentuemerFehltTest`, `SicherungFristNegativStartTest`,
  `VerarbeitenOhneKiHandlerTest`.
- Gruppe 4: `AblageStufeWorkerStartTest`, `BypassVerbotStartTest`,
  `OidcFalschKonfiguriertTest`, `OidcNichtErreichbarTest`,
  `AlteLinksKommandoStartTest`, `MailKommandoStartTest`,
  `LangChain4jKiAdapterWissenTimeoutTest`, `SperrkonfliktAbbildungTest`,
  `MandantEigentuemerTest`, `AuftragSchemaNachFehlerTest`,
  `SicherungFristNullStartTest`, `SicherungUeberwachungSzenarienTest`,
  `UeberwachungGrenzeStartTest`, `OkfExportDatenTest`.

Die Profile unterscheiden sich vielfach bewusst, etwa bei der Prüfung
ungültiger Grenzwerte und Startkonfigurationen. Unterschiedliche Werte dürfen
nicht zu einem einzigen Start zusammengefasst werden, weil dann nicht mehr
nachgewiesen wäre, welcher Wert den Abbruch verursacht. Eine sichere
Zusammenlegung mit gemessener Zeitersparnis ist noch nicht nachgewiesen.

## Zwanzig langsamste Klassen im Ausgangslauf

Surefire-Suitezeiten in Sekunden, nach Lauf 44899529866 sortiert; die zweite
Spalte mit Zeiten zeigt dieselbe Klasse im unabhängigen Lauf 44898942261.
Die Szenario-Runner umfassen viele Szenarien, nicht nur einen Testfall.

| Klasse | Gruppe | Lauf 1 | Lauf 2 |
| --- | --- | ---: | ---: |
| `SzenarienRestTest` | 2 | 221,8 | 239,0 |
| `SzenarienTest` | 1 | 216,5 | 229,1 |
| `WebseitenDateiMessfallTest` | 1 | 65,0 | 70,8 |
| `VerarbeitenOhneKiHandlerTest` | 3 | 62,3 | 52,3 |
| `MailKommandoStartTest` | 4 | 54,4 | 59,4 |
| `TokenKommandoStartTest` | 3 | 45,2 | 34,2 |
| `VerarbeitenZeitgrenzeTest` | 2 | 45,1 | 47,1 |
| `AlteLinksKommandoStartTest` | 4 | 44,9 | 48,5 |
| `MandantKommandoTest` | 2 | 43,6 | 45,4 |
| `UeberwachungGrenzeStartTest` | 4 | 40,8 | 44,4 |
| `SicherungFristNullStartTest` | 4 | 40,4 | 42,9 |
| `MandantEigentuemerTest` | 4 | 40,1 | 42,4 |
| `ManifestKommandoOhneDatenbankTest` | 3 | 39,6 | 30,4 |
| `GoogleAnmeldungSzenarienTest` | 3 | 39,0 | 33,8 |
| `SperrkonfliktAbbildungTest` | 4 | 38,4 | 39,4 |
| `OidcFalschKonfiguriertTest` | 4 | 37,2 | 40,6 |
| `SicherungUeberwachungSzenarienTest` | 4 | 37,2 | 40,7 |
| `OkfExportDatenTest` | 4 | 37,1 | 40,2 |
| `AuftragSchemaNachFehlerTest` | 4 | 36,0 | 37,8 |
| `KeinPlatzStartTest` | 3 | 34,8 | 26,0 |

## Browser-Parallelisierung

Läufe 44899529866 und 44898942261 führten je 154 Browser-Szenarien in
186,5 bzw. 200,7 s aus; die Browser-Jobs insgesamt dauerten 5 min 37 s bzw.
5 min 53 s. Die Anwendung wird dabei nicht je
Szenario neu gestartet, wohl aber der Browser. Ein unbedachter paralleler
Aufruf ist nicht isoliert: Der echte Test-Realm akzeptiert denselben TOTP je
Benutzer nur einmal; der Token-Cache ist pro Prozess und die Datei mit den
zuletzt verwendeten Schritten hat keine prozessübergreifende Sperre. Ausserdem
teilen sich Szenarien Testdaten und Backend. Shards erfordern daher erst
nachgewiesene Daten- und Anmeldeisolation; sie werden nicht als sichere
Beschleunigung ausgegeben.
