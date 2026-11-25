# Lücken in der @req-Abdeckung – Stand 25.11.2026

## Prüfumfang und Methode

Geprüft wurde `origin/main` auf Stand `fdc2e355`. Alle nummerierten Akzeptanzkriterien der
freigegebenen Specs 01–07 wurden mit Cucumber-Szenarien abgeglichen; ein Szenario zählt nur,
wenn seine Feature-Datei den passenden `@spec-0n`-Tag und das passende `@req-x.y` trägt.
Zusätzlich wurden die Verweise auf JUnit-Tests in Spec-Aufgaben, Design und Testquellen geprüft.
Gleiche `@req`-Nummern aus anderen Specs zählen nicht. Kriterien mit einem «Nachweis: …»-Vermerk
sind ausgenommen. Spec 02, Requirements 8–12 und 14, entfällt nach den Entscheiden E1 und MCP
und wurde nicht mitgezählt.

Ohne fehlendes Szenario bleiben Spec 01: 199 Kriterien, davon drei ohne Nachweis; Spec 02:
51 Kriterien, davon eines ohne Nachweis; Spec 03: 89 Kriterien, davon drei ohne Nachweis;
Spec 04: 169 Kriterien, davon drei ohne Nachweis; Specs 05–07: alle gezählten Kriterien haben
ein passendes Szenario. Die taglosen Kriterien 02/13.1 und 03/1.2, 1.3 sowie 1.6 sind durch
die in den Aufgaben genannten Tests `PrueflisteSchemaTest`, `ChatPrueflisteIT`,
`VhfGskDokumentartSeedTest` und `KonfigurationSeedTest` belegt; sie sind deshalb keine Lücken.
Für Spec 04/18.1 nennt `tasks.md` einen `WissenPrueflisteIT`, im Repository fanden sich jedoch
weder dieser Test noch ein Wissens-Prüflisten-Schema.

Aufwandsschätzung: S = höchstens 30 Minuten, M = etwa 0.5–2 Arbeitstage, L = mehr als 2 Tage
oder externe Betriebs-/Fabian-Arbeit. Aufwand für externe Golden-Daten oder Stufe-B-Messungen
setzt deren separate Bereitstellung voraus.

| Kriterium | Spec | Lücke | Aufwand |
| --- | --- | --- | --- |
| `@req-0.2` | 01 | Der Prototyp-Vergleich samt Trefferbericht je Feld und getrennter Abstimmungs-/Prüf-Auswertung ist nicht nachgewiesen. Golden-Daten bleiben ausserhalb des Repos. | M |
| `@req-0.7` | 01 | Es fehlt ein referenzierter Test für die feldweisen Vergleichsregeln des Prototyps. | M |
| `@req-5.5` | 01 | Der p95-Leistungsnachweis für Foto/OCR fehlt; das Kriterium gilt erst auf Stufe B und braucht die dort festgelegte Messumgebung. | L – Stufe B |
| `@req-15.11` | 02 | Die zusätzlichen Tests für Fremdgespräch, Rollen/API-Token, vollständige Schwärzung, 5'000 Zeilen und Löschung sowie der Schematest sind nicht vollständig nachgewiesen. Die Szenarien 15.1–15.10 allein decken diese Ergänzungen nicht alle ab; Aufgabe 10.4 ist offen. | L |
| `@req-1.5` | 03 | Für VHF/GSK fehlt der Nachweis, dass App-Schlüssel/kein Bypass sowie Schema und Bucket in Sicherung, Zweitkopie und Restore-Probe enthalten sind. Betreiber-/Infra-Nachweis. | L – Fabian/Infra |
| `@req-2.3` | 03 | Es fehlt der Nachweis des eigenen Keycloak-Themes für einen manuell eingerichteten Mandanten-Client. | M – Fabian/Infra |
| `@req-4.2` | 03 | Für den Altbestand-Import fehlt ein Test/Nachweis des Berichts nach Reihe und Jahr samt Lücken der Ausgabe-Nummerierung. | M |
| `@req-4.7` | 04 | `SicherungPaketRoundtripTest` prüft den lokalen Datenbank-Roundtrip, nicht den wiederkehrenden Restore-Probe-Vergleich der Seiten- und Versionszahlen. Aufgabe 13.7 bleibt offen. | M – Fabian/Infra |
| `@req-18.1` | 04 | Der in `tasks.md` genannte Test `WissenPrueflisteIT` und das Wissens-Prüflisten-Schema fehlen im Repo; die Abnahme-Stichprobe bleibt extern. | M – Fabian |
| `@req-19.24` | 04 | Die Export-Oberfläche zeigt den Cloud-Regelhinweis nicht; `wissen_paket` gibt ihn aus, hat aber keinen gezielten Test für den lokalen Modellfall. PR #608 ergänzt den Export-Hinweis sowie CLI- und UI-Test, ist aber noch Draft. | S – PR #608 |

