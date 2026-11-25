# CI-Fehlerstatistik (November 2026)

Stichtag: 25.11.2026, 06:35 UTC. Auswertung der 30 jüngsten Läufe des
GitHub-Actions-Workflows `CI` auf `main` (`gh run list --branch main
--workflow CI --limit 30`). Zeitraum: 24.11.2026, 13:37 UTC bis
25.11.2026, 06:35 UTC. 13 Läufe fehlgeschlagen, 17 erfolgreich.
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
Ursachen oder eine Wiederholbarkeit. Es wurden keine Stabilisierungspatches
vorgenommen.
