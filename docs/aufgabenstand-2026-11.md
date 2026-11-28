# Aufgabenstand der Specs (28.11.2026)

## Umfang und Ergebnis

Abgleich gegen `origin/main` bei `b1112c4ca` nach den Merges #674, #725, #730, #732, #733 und #735. In den sieben
Taskdateien stehen 41 offene Checkboxen; Spec 05 und Spec 07 haben keine offenen Checkboxen. Keine Checkbox wurde
geändert. Ein gemergter Teil-PR
allein gilt nicht als Abschluss, wenn die Taskdatei noch Abnahme, Betrieb, Messung oder Folgearbeiten nennt.

**Faktisch vollständig erledigt:** keine der 41 offenen Checkboxen konnte anhand der Taskdateien und der darin
verlinkten PR-/Testnachweise als vollständig abgeschlossen bestätigt werden. Wo ein gemergter PR nur einen Teil
abdeckt, ist er als solcher aufgeführt. Die Entfallen- und Übernahmeentscheide sind separat ausgewiesen und keine
erledigten Umsetzungen.

## Einzelabgleich

### Spec 01 – Archiv-Kern (13)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 0.7 | PR #18 belegt die Zugangsvorbereitung, nicht den Abschluss der gesamten Stufe A. | Fabian / Infra |
| 1.10 | Messbericht #462 hält ein erreichtes Abbruchkriterium fest; #695 ergänzt die statische Compose-Prüfung und Rollback-Grenzen, keinen Messnachweis. Entscheid Fabian (27.11.2026): Budget 2048 MiB / 1.5 CPU und erneute Messung. Der Generator #727 und der lokale Speichermessungs-Trockenlauf #730 sind gemergt; sie belegen weder VPS-Messung noch Health-/OOM-Prüfung. | Fabian / Infra; Messung und Nachweis |
| 2.6 | Teilumsetzungen in #46, #54, #266, #568, #638 sowie den am 25.11. gemergten #644 und #645; Stufe-B-Leistungsnachweis fehlt. | Backend / Infra |
| 3.7 | PWA und Eingabegrenzen in #123 und #472; die Abnahme bleibt ausstehend. | Fabian / Frontend |
| 5.4 | Kein vollständiger Produktiv- und Stufe-B-Nachweis in den geprüften Belegen. | Fabian / Infra; abhängig von 5.11 |
| 5.5 | Export-, Restore- und lokale Probe-Teile in #169, #174, #194, #200, #209, #219, #222 und #542; VPS-Workflow und vollständige Betriebsprobe offen. | Infra / Backend / Fabian |
| 5.6 | App-Alarme und Kennzahlen in #91, #114, #191, #566 und #639; Aktivierung und Betriebsnachweise bleiben offen. | Backend / Infra |
| 5.7 | Entwurf #283 ist keine Freigabe; Gegenlesen und Freigabe sind laut Taskdatei offen. | Fabian |
| 5.8 | Abnahme-Gate und Belegübernahme offen; Voraussetzungen aus 5.9 und 5.11 fehlen. | Fabian / QA; blockiert |
| 5.9 | Die externe Erreichbarkeitsprüfung des Anwendungs-Hosts und die Meldung bei fehlgeschlagenem Deploy aus #724 sind im Infra-Repo umgesetzt. Fabian entschied am 28.11.2026, den Anmelde-Host nicht extern zu überwachen; die Prüfung wurde entfernt, da die Prüfung des Anwendungs-Hosts genügt. Fällt nur der Anmeldedienst aus, bleibt die Health der App grün. Kein Betriebsnachweis für den Anmelde-Host offen; die übrigen Schritte bleiben offen. | Fabian / Infra; übrige Schritte |
| 5.10 | Entwurf #284 ist keine geprüfte Notfallunterlage. | Fabian |
| 5.11 | Kein vollständiger Wechsel- und Betriebsnachweis im geprüften Stand. | Fabian / Infra |
| O.8 | Kein Umsetzungs- oder Entscheidungsnachweis gefunden. | Fabian / Infra / Backend |

### Spec 02 – Abgleich-Chat (11)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 6.2 | Kein Einrichtungsnachweis gefunden. | Fabian |
| 8.1–8.6 | Alle sechs sind mit Entscheid vom 15.11.2026 ausdrücklich als **entfallen** markiert; laut Welle 8 nicht umsetzen und nicht abhaken. | Kein Umsetzungsauftrag |
| 9.1 | PR #156 liefert Gerüst und Schema, nicht die manuelle Abnahme mit echtem Modell. | Fabian / QA |
| O.1–O.2 | Mit Entscheid vom 15./16.11.2026 ausdrücklich **entfallen**. | Kein Umsetzungsauftrag |
| O.3 | Mit Entscheid vom 18.11.2026 ausdrücklich **entfallen**; nicht umsetzen. | Kein Umsetzungsauftrag |

### Spec 03 – Zweiter Mandant (8)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 10.1 | Vertrag und Domain nicht als abgeschlossen belegt. | Fabian |
| 10.2 | Seeds und Testumstellung sind gemäss #615 nachgeführt; Produktiv-Provisionierung mit Object Lock/Bucket Policy und manuelle Keycloak-Einrichtung bleiben offen. S3-Isolation gehört zu 12.3. | Fabian / Infra |
| 10.3 | Branding-Teile in #228, #498, #541 und Betriebscheckliste #567; kein vollständiger Abschlussnachweis. | Frontend / Infra; parallele Arbeit nicht duplizieren |
| 10.5 | Importwerkzeug in #504; der PR ordnet weitere Req-4.2-Arbeit Spec 05 zu. Keine abgeschlossene Übernahme und Abnahme der Altbestände belegt. | Backend / Fabian |
| 10.6 | Repo-Teile in #331 und #497; vollständiger Backup-Nachweis für Mandant 2 bleibt offen. | Infra |
| 11.11 | #495 belegt Repo-Umsetzung und Tests; laut Taskdatei fehlen Messung der Startwerte am Livegang und deren Nachweis. | Backend; Messung beim Livegang |
| 12.3 | Lokaler ZAP-Lauf #719 mit sechs erfolgreichen Scans; HSTS-Reihenfolge #722 und lokaler Router-Rauchtest #723 sind gemergt. Die Staging-Baseline bleibt ausstehend; deshalb bleibt die Checkbox offen. | QA / Fabian / Infra |
| 12.4 | Abnahme nicht belegt. | Fabian |

### Spec 04 – Wissensbasis (8)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 13.1 | Entscheide und Folgeänderungen offen. | Fabian |
| 13.7 | #688 ergänzt den lokalen synthetischen Restore-Zählvergleich je Mandant; die wiederkehrende betriebliche Restore-Probe ist damit nicht nachgewiesen. | Infra |
| 16.2 | Prüfliste und Gate nicht abgenommen. | Fabian / QA |
| 18.3 | PR #576 ist gemergt; Plugin-Hinweis und vollständige Abnahme fehlen laut Taskdatei. | Backend / Frontend |
| 19.6 | #702 enthält die Checkliste; Test-Ingest und Auswertung bleiben offen, Voraussetzungen 19.1–19.5 sind umgesetzt. | QA / Fabian |
| O.4 | Keine Umsetzung belegt. | Backend |
| O.5 | Laut Taskdatei in 15.6–15.8 übernommen; nicht doppelt umsetzen. | In den übernommenen Aufgaben nachverfolgen |
| O.6 | Mit Entscheid vom 18.11.2026 ausdrücklich **entfallen**. | Kein Umsetzungsauftrag |

### Spec 06 – Verwaltung, Einstellungen, Benutzer (1)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 21.6 | #684 dokumentiert den Zwischenstand; die Speichermessung auf Stufe A und damit die Abnahme bleiben offen. | Fabian |

## Gemergte PRs seit dem letzten Stand

Die Merges #674, #725, #730, #732, #733 und #735 ergänzen QA-, Deploy- und
Messvorbereitungsnachweise. Sie schliessen keine der 41 offenen Checkboxen
vollständig ab; die nötigen Abnahmen und Betriebsnachweise bleiben gemäss
Taskdateien offen.

## Seit dem letzten Abgleich abgeschlossene Checkboxen

- Spec 02 10.4 (#602); Spec 03 11.4 (#616) und 11.6 (#594); Spec 04 18.4 (#605).
- Spec 06 19.2 (Betriebsnachweise bleiben bei 19.3), 21.5 (#595 und #641) und 24.3
  (Abnahme und Ereignisnachweis belegt, #627 und #637).
- Spec 06 19.3, 20.6 und 21.2 (#686; 20.6 mit Nachtrag #685); Spec 07 23.6 (#683, Abnahme vom 26.11.).
- Spec 04 19.2 (#687, Tabellen und Seitenbilder).
- Spec 04 19.3 (#682, Fragen, Optionen und Bedingungen), 19.4 (#707, Abschnittsbefunde) und
  19.5 (#714, Tabellen-Gruppierung und Vertraulichkeitsvermerk); 19.6 bleibt offen.

## Grenzen des Abgleichs

- Offene PRs zum Zeitpunkt dieses Abgleichs: Ready #728, #729, #731, #734, #736 und #737. #728 und #731 warten auf einen Entscheid; offene PRs zählen nicht als Nachweis auf `main`.
- Der Stand der Taskdateien und offenen PRs ist eine Momentaufnahme; die Checkboxen wurden nicht verändert.
- Dieser Bericht enthält keine Betriebsdetails oder Zugangsdaten.
