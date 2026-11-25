# Aufgabenstand der Specs (24.11.2026)

## Umfang und Ergebnis

Abgleich gegen `origin/main` bei `180516365` (`fix(e2e): hilfe-offline:31 stabilisieren`, PR #596). In sechs
Taskdateien stehen 51 offene Checkboxen; Spec 05 hat keine. Keine Checkbox wurde geändert. Ein gemergter Teil-PR
allein gilt nicht als Abschluss, wenn die Taskdatei noch Abnahme, Betrieb, Messung oder Folgearbeiten nennt.

**Faktisch vollständig erledigt:** keine der 51 offenen Checkboxen konnte anhand der Taskdateien und der darin
verlinkten PR-/Testnachweise als vollständig abgeschlossen bestätigt werden. Wo ein gemergter PR nur einen Teil
abdeckt, ist er als solcher aufgeführt. Die Entfallen- und Übernahmeentscheide sind separat ausgewiesen und keine
erledigten Umsetzungen.

## Einzelabgleich

### Spec 01 – Archiv-Kern (13)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 0.7 | PR #18 belegt die Zugangsvorbereitung, nicht den Abschluss der gesamten Stufe A. | Fabian / Infra |
| 1.10 | Messbericht #462 hält ein erreichtes Abbruchkriterium fest; kein positiver Abschlussnachweis. | Infra; Entscheid zum weiteren Vorgehen |
| 2.6 | Teilumsetzungen in #46, #54, #266 und #568; Gesamtaufgabe bleibt offen. | Backend / Infra |
| 3.7 | PWA und Eingabegrenzen in #123 und #472; die Abnahme bleibt ausstehend. | Fabian / Frontend |
| 5.4 | Kein vollständiger Produktiv- und Stufe-B-Nachweis in den geprüften Belegen. | Fabian / Infra; abhängig von 5.11 |
| 5.5 | Export-, Restore- und lokale Probe-Teile in #169, #174, #194, #200, #209, #219, #222 und #542; VPS-Workflow und vollständige Betriebsprobe offen. | Infra / Backend / Fabian |
| 5.6 | App-Alarme und Kennzahlen in #91, #114, #191 und #566; Aktivierung und Betriebsnachweise bleiben offen. | Backend / Infra |
| 5.7 | Entwurf #283 ist keine Freigabe; Gegenlesen und Freigabe sind laut Taskdatei offen. | Fabian |
| 5.8 | Abnahme-Gate und Belegübernahme offen; Voraussetzungen aus 5.9 und 5.11 fehlen. | Fabian / QA; blockiert |
| 5.9 | Checkliste in #569; App-Messung ist Gegenstand des Draft-PR #597, noch nicht auf `main`. VPS-Anbindung und Nachweise bleiben offen. | Infra |
| 5.10 | Entwurf #284 ist keine geprüfte Notfallunterlage. | Fabian |
| 5.11 | Kein vollständiger Wechsel- und Betriebsnachweis im geprüften Stand. | Fabian / Infra |
| O.8 | Kein Umsetzungs- oder Entscheidungsnachweis gefunden. | Fabian / Infra / Backend |

### Spec 02 – Abgleich-Chat (12)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 6.2 | Kein Einrichtungsnachweis gefunden. | Fabian |
| 8.1–8.6 | Alle sechs sind mit Entscheid vom 15.11.2026 ausdrücklich als **entfallen** markiert; laut Welle 8 nicht umsetzen und nicht abhaken. | Kein Umsetzungsauftrag |
| 9.1 | PR #156 liefert Gerüst und Schema, nicht die manuelle Abnahme mit echtem Modell. | Fabian / QA |
| 10.4 | Die geforderten Isolations- und Abnahmetests sind offen; Abhängigkeit 10.3. | QA |
| O.1–O.2 | Mit Entscheid vom 15./16.11.2026 ausdrücklich **entfallen**. | Kein Umsetzungsauftrag |
| O.3 | Mit Entscheid vom 18.11.2026 ausdrücklich **entfallen**; nicht umsetzen. | Kein Umsetzungsauftrag |

### Spec 03 – Zweiter Mandant (10)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 10.1 | Vertrag und Domain nicht als abgeschlossen belegt. | Fabian |
| 10.2 | PR #473 belegt Rollen-Seed und Tests, nennt aber selbst den fehlenden Seed ohne `WISSENSQUELLE` sowie ausstehende Isolationsnachweise. | Backend; Teilaufgabe offen |
| 10.3 | Branding-Teile in #228, #498, #541 und Betriebscheckliste #567; kein vollständiger Abschlussnachweis. | Frontend / Infra; parallele Arbeit nicht duplizieren |
| 10.5 | Importwerkzeug in #504; der PR ordnet weitere Req-4.2-Arbeit Spec 05 zu. Keine abgeschlossene Übernahme und Abnahme der Altbestände belegt. | Backend / Fabian |
| 10.6 | Repo-Teile in #331 und #497; vollständiger Backup-Nachweis für Mandant 2 bleibt offen. | Infra |
| 11.4 | Plugin-Grundgerüst #478, kein vollständiger Abschlussnachweis. | Frontend |
| 11.6 | PR #496 belegt wesentliche Vertrags- und Versionierungskriterien; der aktuelle Draft #594 führt die Aufgabe weiter. Checkbox bleibt bis zum Abschluss der laufenden Arbeit offen. | Backend; parallele Arbeit nicht duplizieren |
| 11.11 | #495 belegt Repo-Umsetzung und Tests; laut Taskdatei fehlen Messung der Startwerte am Livegang und deren Nachweis. | Backend; Messung beim Livegang |
| 12.3 | #235, #287, #359 und #490 liefern Teilnachweise; Taskdatei hält ausdrücklich fest, dass Gesamtisolation und OWASP-Nachweis fehlen. | QA; parallele Arbeit nicht duplizieren |
| 12.4 | Abnahme nicht belegt. | Fabian |

### Spec 04 – Wissensbasis (8)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 13.1 | Entscheide und Folgeänderungen offen. | Fabian |
| 13.7 | Kein betrieblicher Restore-Zählvergleich belegt. | Infra |
| 16.2 | Prüfliste und Gate nicht abgenommen. | Fabian / QA |
| 18.3 | PR #576 ist offen und nicht gemergt. | Backend / Frontend |
| 18.4 | Darf erst nach Merge von #576 beginnen; aktuell blockiert. | QA; blockiert durch #576 |
| O.4 | Keine Umsetzung belegt. | Backend |
| O.5 | Laut Taskdatei in 15.6–15.8 übernommen; nicht doppelt umsetzen. | In den übernommenen Aufgaben nachverfolgen |
| O.6 | Mit Entscheid vom 18.11.2026 ausdrücklich **entfallen**. | Kein Umsetzungsauftrag |

### Spec 06 – Verwaltung, Einstellungen, Benutzer (7)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 19.2 | Export und Realm-Tests in #385; laut Taskdatei folgen vollständige Szenarien und Betriebsnachweise. | Infra; Teilaufgabe offen |
| 19.3 | #506 und die Taskdatei belegen Teil der Einrichtung; Notfallunterlagen und genannte Betriebsnachweise bleiben offen. | Fabian |
| 20.6 | Benutzeroberfläche und Nachbesserungen in #505, #514, #543 und #556; kein vollständiger Abnahmenachweis. | Frontend / Fabian; laufende Arbeit nicht duplizieren |
| 21.2 | Migrationsvorbereitung und Betreibertests in #560, #565 und #574; Migration der heutigen Benutzer bleibt offen. | Fabian |
| 21.5 | Vorbereitung #544; aktueller Abgleich-Draft #595 ist noch nicht gemergt. | Backend; parallele Arbeit nicht duplizieren |
| 21.6 | Abnahme Verwaltung nicht belegt. | Fabian |
| 24.3 | Anleitung und Inbetriebnahme nicht abgenommen. | Fabian |

### Spec 07 – Einführung und Hilfe (1)

| Aufgabe | Stand auf `main` / Beleg | Zuständig bzw. nächster Schritt |
|---|---|---|
| 23.6 | Vorbereitende Nachweise in #549, #550 und #551; die Abnahme bleibt bei Fabian. | Fabian |

## Grenzen des Abgleichs

- Offene Drafts zählen nicht als Nachweis auf `main`: #594, #595 und #597.
- #576 ist offen, nicht gemergt; deshalb ist 18.4 weiterhin blockiert.
- Die parallel bearbeiteten Aufgaben 03 10.3/11.6/12.3, 02 9.1/10.4 und 06 20.6/21.5 wurden nicht verändert.
- Betriebsdetails und Zugangsdaten wurden nicht erhoben oder in diesen Bericht übernommen.
