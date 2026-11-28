# Übergabe: Spec 09 Produktivimport

Stand 28.11.2026 · Auftraggeber Fabian · Repo `Urinet-ch/dokumentenmanagement`

## Auftrag
Die Spec `.kiro/specs/09-produktivimport/` wird Welle für Welle (27 bis 30) nach dem Crew-Modell aus
`docs/umsetzungsplan.md` umgesetzt. Ziel: Ab dem Stichtag (Richtwert 01.01.2027) läuft jeder neue Beleg über das
UriNet Archiv, und der Bestand 2026 ist bis 31.01.2027 vollständig übernommen. Papier kommt über den Netzwerkscanner
als Stapel mit QR-Trennblättern ins Archiv. Welle 30 mit echten Belegen beginnt erst nach dem Entscheid zum Start mit
Echtbelegen (PR #731).

## Grundlagen
| Dokument | Inhalt |
| --- | --- |
| Konzepterweiterung Produktivimport (Claude-Dokument, für Sitzungen nicht erreichbar) | Warum und was: Quellen, Scanner, Trennblätter, Ablauf, Entscheide |
| `.kiro/specs/09-produktivimport/requirements.md` | 17 Requirements (0 bis 16) in EARS mit Konzept-IDs D9 bis D13, I7 bis I10, Q1 bis Q3, B5, B6 |
| `.kiro/specs/09-produktivimport/design.md` | Komponenten, Schnittstellen, Datenmodell, Platzhalter, Bedrohungsmodell, Folgeänderungen |
| `.kiro/specs/09-produktivimport/tasks.md` | Aufgaben 27.1 bis 30.5 mit Rollen und Rückverweisen |
| `docs/spikes/09-bestandsaufnahme.md` | Stand der vorausgesetzten Bausteine im Repo (Requirement 0.1) |
| `docs/adr/ADR-016-scan-eingang-sftp.md` bis `docs/adr/ADR-019-dubletten-hauptfassung.md` | neue Architekturentscheide |

## Reihenfolge und erste Schritte
1. Nach dem Merge zuerst 27.1 (Entscheide, Fabian) und 27.2 (Folgeänderungen, Rechte A25 bis A29).
2. 27.3 und 27.4 früh: Der Spike läuft mit einem lokal gestarteten Prototyp im Heimnetz und braucht Fabian am Gerät;
   ohne erfolgreichen Spike gilt der Rückfall E6 (E-Mail-Eingang oder Mac-Netzwerkordner).
3. 27.5 und 27.6 laufen parallel und hängen nicht vom Spike ab.
4. Danach Wellen 28 und 29 nach `tasks.md`; Welle 30 führt Fabian nach Aufgabe 30.1 durch.

## Regeln, die nicht verhandelbar sind
- Alle Regeln aus `.kiro/steering/tech.md` gelten weiter (Mandant nur aus dem Token bzw. der Zuordnung des Deploys,
  Dateien unveränderbar, SHA-256 zuerst, Daten nur in der Schweiz, KI nur über Infomaniak, keine Geheimnisse im Code).
- **Keine echten Belege vor dem Entscheid #731.** Stufe A und alle Tests nur mit synthetischen Daten (Spec 01, 16.15).
- **Das Archiv verwirft nie selbständig.** Dubletten werden vorgeschlagen, der Mensch entscheidet.
- **Stapel-Original zuerst sichern, dann trennen.** Geht die Seitenbilanz nicht auf, wird nichts getrennt.
- **Gmail nur lesend.** Keine Nachricht wird verändert, verschoben oder als gelesen markiert.
- **Der Mac-Eingang löscht nie.** Er verschiebt nur nach `hochgeladen/` oder `fehler/`.
- **Ältere SSH-Verfahren** höchstens im Dienst `sftp`, nie für den Server-Zugang.
- **Kein Agent an VPS, Infra-Repo, Secrets oder Workflows.** Firewall, TCP-Eingang, Compose-Policy, Secrets und der
  Deploy-Workflow sind Aufgaben von Fabian; Agenten liefern Patches oder Anleitungen.
- **Öffentliches Repo.** Gerät, Adressen, Belegnummern und Namen von Quellen nur als Platzhalter (design.md, «Platzhalter»).
- Texte für Benutzer und Dokumentation: Deutsch (Schweiz), ss statt ß, Guillemets «…».

## Was Fabian liefert
| Wann | Was |
| --- | --- |
| Welle 27 | Entscheide E8 bis E13, Bestätigung der Bestandsaufnahme, Bedienung des Scanners für den Spike, Entscheid E6 nach dem Spike |
| Welle 28 | Weg nach aussen im Infra-Repo der VPS (E10), feste Adresse oder DNS-Name des Heimanschlusses (K6), Übernahme des Workflow-Patches, GitHub-Secrets für Host-Schlüssel und Scanner-Schlüssel, Einrichtung der Scanprofile, Druck der Trennblätter auf farbiges Papier |
| Welle 29 | Gmail-App-Passwort und Label (K9, in der App gesetzt), Bestätigung des JSON-Formats von `rechnung_gen.py`, Startwerte der Vollständigkeit (K11), Ergänzung der Verfahrensbeschreibung, synthetischer Probelauf |
| Welle 30 | Entscheid #731 und dessen Voraussetzungen, Umstellung zum Stichtag, Download-Ordner der Buchhaltungsbelege, Aliasse des Altbestands, Papierordner, Abarbeiten des Eingangskorbs, Begründungen der Lücken, Entscheid E5 (Papier) |

## Offene Entscheide und Klärungen
| # | Thema | Wirkung auf die Umsetzung |
| --- | --- | --- |
| #731 | Start mit Echtbelegen (Varianten A, B, C) | Gate für Welle 30; Zeitpunkt des Stichtags |
| E6 | Übertragungsweg Scanner | SFTP nach Spike; sonst E-Mail-Eingang (Aufgabe 28.11) oder Mac-Netzwerkordner |
| E7 | Stichtag und Ende des Bestandsimports | Zeitrahmen, gebunden an #731 |
| E8 | Private Zahlungsquittungen als Anhang | Aktion «als Anhang verknüpfen» |
| E9 | Projektdokumente nur als freigegebene PDF | Unterordner des Mac-Eingangs |
| E10 | Weg des Scan-Eingangs nach aussen | Aufgaben 28.3 und 28.4 |
| E11 | Speicherbudget Stufe A für den Dienst `sftp` | Aufgabe 28.3; sonst Spec 01, 16.18 |
| E12 | Dateinamen-Regeln setzen Vorschlagsfelder | Aufgabe 29.3 |
| E13 | Verworfene Seiten im gesperrten Stapel-Original | Aufgaben 28.9, 29.9 |
| K6 | Feste Adresse oder DNS-Name | IP-Freigabe |
| K7 | SSH-Verfahren des Geräts | Spike 27.4 |
| K8 | Umfang Papierbestand | Planung 30.4 |
| K9 | Gmail-Zugang | Aufgabe 29.4, Lauf in 30.3 |
| K11 | Startwerte und erwartete Lücken | Aufgaben 29.8 und 30.5 |

K10 (Stand im Repo) ist mit der Bestandsaufnahme erledigt.

## Rückmeldung
- Pro Aufgabe ein Pull Request mit den erfüllten Akzeptanzkriterien und Tests (CLAUDE.md).
- Am Ende jeder Welle eine kurze Zusammenfassung an Fabian: erledigt, offen, Abweichungen von der Spec, nächste Schritte.
- Gates (Probelauf, Echtbelege, Abschluss) mit Ergebnis in `docs/abnahme/`.
