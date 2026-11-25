# CI-Fehlerstatistik (November 2026)

Stand: 25.11.2026, 07:23:45 UTC. Auswertung der 30 jüngsten abgeschlossenen
Läufe des GitHub-Actions-Workflows `CI` auf `main` (`gh run list --branch main
--workflow CI --status completed --limit 30`). Zeitraum: 24.11.2026,
13:37:32 UTC bis 25.11.2026, 06:35:09 UTC. 13 Läufe fehlgeschlagen,
17 erfolgreich. Lauf [45567057968](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45567057968)
war zum Stichtag noch in Arbeit und ist nicht gezählt.
Ausgewertet wurden fehlgeschlagene Jobs und deren Logs (`gh run view --log-failed`).

## Häufigkeit

- `Browser-Szenarien (Cucumber.js, Playwright)`: in 12 der 30 Läufe
  fehlgeschlagen. In 11 Läufen scheiterte `npx cucumber-js`; in einem Lauf
  scheiterte nur der nachgelagerte Cache-Schritt.
- Backend-/Build-Jobs: 5 fehlgeschlagene Jobs in 3 Läufen; alle protokollieren
  HTTP 429 beim Abruf von Abhängigkeiten über Maven Central.
- Insgesamt: 17 fehlgeschlagene Jobs in 13 fehlgeschlagenen CI-Läufen. Manche
  Läufe hatten mehrere fehlgeschlagene Jobs oder Szenarien.

## Browser-Szenarien

Die Logs belegen folgende 13 Szenariofehler in 11 Läufen. Das sind beobachtete
Timeouts bzw. ein durch Navigation zerstörter Ausführungskontext; sie belegen
nicht, ob die Ursache ein Flake oder eine Produktregression ist.

| Szenario | Häufigkeit | In den Logs beobachtet |
|---|---:|---|
| «Eine neue App-Version und ihre Anleitung werden gemeinsam aktiv» | 5 | Viermal `page.waitForFunction` nach 30 s bei der PWA-Installation; einmal wurde der Playwright-Ausführungskontext während einer Navigation zerstört. |
| «Die Anleitung wird vorab geladen und ist offline vollständig» | 4 | Dreimal `locator.click` nach 10 s ohne sichtbaren Link «Mit Passkey anmelden»; einmal `page.waitForFunction` nach 30 s bei der PWA-Installation. |
| «Abgemeldet zeigt die Offline-Erfassung ihre Hilfe» | 1 | `page.waitForFunction` nach 30 s bei der PWA-Installation. |
| «Offline und ohne Anmeldung erfassen; die Aufnahmen bleiben auf dem Gerät» | 1 | `page.waitForLoadState` nach 30 s beim Schritt «Und das Handy offline ist». |
| «Das Access-Token nach Passkey-Anmeldung enthält nur vorgesehene Claims» | 1 | `locator.waitFor` nach 30 s; der virtuelle Passkey wurde nicht sichtbar. |
| «Passkey erneuert auth_time für den Step-up» | 1 | `locator.waitFor` nach 30 s; der virtuelle Passkey wurde nicht sichtbar. |

Belege:

- Neue App-Version/Anleitung (5): [45463659941](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45463659941), [45445633239](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445633239), [45445209404](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445209404), [45445148455](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445148455), [45445116829](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445116829).
- Anleitung offline (4): [45439238870](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45439238870), [45433451323](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45433451323), [45425625683](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45425625683), [45416110882](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45416110882).
- Abgemeldete Offline-Hilfe: [45445116829](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445116829).
- Offline-Erfassung ohne Anmeldung: [45556027064](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45556027064).
- Passkey-Claims: [45425625683](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45425625683); Step-up: [45414870299](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45414870299).

## Weitere belegte Ursachen

- **Maven Central HTTP 429, 5 Jobs in 3 Läufen:** Abhängigkeits-Downloads
  wurden mit Status 429 abgewiesen. Betroffen waren Build/Quinoa,
  Backend-Tests sowie Backend-Gruppe 2 und Gruppe 4. Belege: [45445229923](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445229923),
  [45445209404](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445209404),
  [45445148455](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45445148455).
- **Cache-Post-Schritt, 1 Lauf:** `hashFiles('**/pom.xml')` meldete, Dateien
  unter dem Runner-Arbeitsverzeichnis nicht hashen zu können. Der
  `npx cucumber-js`-Schritt dieses Laufs war erfolgreich; fehlgeschlagen ist
  der nachgelagerte Cache-Schritt. Beleg: [45481147071](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45481147071).

## Einordnung

Die Logs belegen die genannten Fehlerbilder, aber nicht deren weitergehende
Ursachen oder eine Wiederholbarkeit. Die folgenden Nachträge halten belegte
Fixes fest und trennen sie von weiterhin ungeklärten historischen Timeouts.

## Nachtrag 25.11.2026: Offline-Erfassung

Im Lauf [45556027064](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45556027064)
scheiterte das Szenario «Offline und ohne Anmeldung erfassen; die Aufnahmen
bleiben auf dem Gerät» im Schritt «das Handy offline ist» am
`page.waitForLoadState('networkidle')`. Das Log belegt den abgelaufenen Wait,
nicht, welche Anfrage das Netzwerk aktiv hielt.

Das Installations-Given lädt bereits alle App-Dateien in den Service-Worker-Cache
und prüft nach nötigen Reloads, dass der Service Worker die Seite kontrolliert.
Für dieses Szenario ersetzt deshalb ein eigener Schritt den zusätzlichen
`networkidle`-Wait durch die Prüfung dieses Controllers. Nach dem Offline-Schalten
wartet er zudem auf den Offline-Hinweis der App, bevor die Seite erneut geöffnet
wird. Die anderen Erfassungsszenarien behalten ihren bisherigen Wait. Am
Produktcode ändert sich nichts.

Bei der Kalibrierung scheiterte lokal ein Durchlauf danach noch beim Link-Klick
«Ohne Verbindung erfassen». Das belegt einen fehlenden Synchronisationspunkt,
nicht die tiefergehende Ursache. Mit dem zusätzlichen Wait auf den Offline-Hinweis
lief das Szenario zehnmal hintereinander erfolgreich.

Nachweis: Frontend-Build und App-Build für den Browser-Test waren erfolgreich.
Die Wiederholungen belegen die Stabilität dieses Testpfads, nicht die Ursache der
ursprünglichen Netzaktivität.

## Weitere Nachträge (25.11.2026)

- **Hilfe-Leiste, PR #633:** Der frühere Timeout beim Klick auf «Mit Passkey
  anmelden» ist im Log von [45439238870](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45439238870)
  als fehlender sichtbarer Navigationslink nach 10 Sekunden belegt; das Log
  erklärt nicht, weshalb der Link unsichtbar war. Davon getrennt liess sich
  lokal ein Strict-Mode-Fehler reproduzieren: Der bisherige Überschriften-
  Locator traf sowohl die Abschnittsüberschrift `h3` als auch eine gleich
  benannte Markdown-Überschrift im Artikel. PR #633 grenzt den Locator auf
  `.hilfe-abschnitt h3` ein. `@req-8.1` bestand lokal zehnmal, vor und nach
  dem Abgleich mit `origin/main`; `@req-8.3` bestand je einmal. Das belegt
  den Locator-Fix, aber nicht die Ursache des historischen Klick-Timeouts.
- **PWA-Installation und Versionswechsel:** Der Log von
  [45463659941](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45463659941)
  belegt einen während der Navigation zerstörten Playwright-Ausführungskontext.
  Die Installations-Timeouts zeigen nicht, ob Service Worker, Manifest oder
  Cache-Bedingung ausblieb. Für diese Fehlerbilder ist keine Ursache
  nachgewiesen; PR #633 ändert sie nicht.
- **Virtueller Passkey:** Lauf
  [45625316316](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45625316316)
  wartete beim Einrichten 30 Sekunden erfolglos auf den sichtbaren virtuellen
  Passkey. PR #631 verlängert genau diese Locator-Frist auf 60 Sekunden. Das
  betroffene Szenario bestand zehnmal lokal; der vollständige CI-Lauf
  [45629899185](https://github.com/Urinet-ch/dokumentenmanagement/actions/runs/45629899185)
  einschliesslich Browser-Szenarien und Gitleaks bestand.
- **Jüngere Fehlerlogs:** In Lauf 45575435751 fand sich kein Eintrag zu
  `hilfe-offline.feature`; Lauf 45585871929 scheiterte an einer ungültigen
  Anforderungsreferenz in `ci.feature`, Lauf 45589578420 erneut am virtuellen
  Passkey. Diese Läufe belegen keine neue Ursache für den historischen
  Hilfe-Leisten-Klick- oder Installations-Timeout.
