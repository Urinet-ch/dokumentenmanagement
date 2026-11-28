# Start mit echten Belegen: Go/No-go

Diese Checkliste war die Entscheidungshilfe für den Start mit echten Belegen (PR #731). Seit dem Entscheid vom
28.11.2026 ist sie die Go/No-go-Liste für Variante B (Spec 01, Aufgabe 31.8). Eine Kopie eines echten Belegs bleibt
ein echter Beleg, auch wenn das Original anderswo führend ist. Ein Häkchen ist erst gesetzt, wenn der verlinkte
Betriebsnachweis vorliegt; eine Spec, ein Test oder diese Checkliste allein belegt keinen erfolgreichen Betrieb.
Betriebswerte (Hostnamen, Adressen, Kapazitäten) gehören nicht in diese Datei.

## Entscheid 28.11.2026: Variante B

Entschieden von Fabian am 28.11.2026: **Variante B – die bestehende VPS wird produktiv.** Eine zweite VPS (bisher
«Stufe B» als eigene Maschine) wird nicht bestellt. Der Stichtag bleibt: Ab 01.01.2027 läuft jeder neue Beleg über
das Archiv, der Bestand 2026 ist bis 31.01.2027 importiert (Spec 09, E7). Grundlage:
[ADR-020](../adr/ADR-020-produktiv-auf-bestehender-vps.md), Nachtrag 28.11.2026 in
[ADR-010](../adr/ADR-010-plattform-zwei-stufen.md).

Geänderte und neue Kriterien in [Spec 01](../../.kiro/specs/01-archiv-kern/requirements.md):

- Req 16.1, 16.3, 16.15, 16.17, 16.18 und 16.26 geändert: Stufe B ist der Produktivbetrieb auf derselben VPS; der
  Wechsel verwirft die Testdaten der Stufe A und setzt die Stufenwerte der Stufe B, statt eine neue VPS einzurichten.
- Req 16.28 bis 16.31 neu: Netze und Ports, Geheimnisse nur im Archiv, Limits und Alarm, Wiederaufbau auf einer
  neuen VPS.
- Gates als [Aufgaben 31.1 bis 31.9](../../.kiro/specs/01-archiv-kern/tasks.md); Aufgabe 5.11 ist dadurch ersetzt,
  5.4, 5.5, 5.8 und 5.9 sind ergänzt.
- [Spec 09](../../.kiro/specs/09-produktivimport/requirements.md): Req 16.2 und Aufgabe 30.1 verweisen auf diese
  Gates; E7 ist entschieden.

| Variante | Stand |
| --- | --- |
| **A – Pilot mit Kopien, Original führend anderswo** | Nicht gewählt. |
| **B – Bestehende VPS wird produktiv** | **Gewählt am 28.11.2026.** Zulässig nach den geänderten Kriterien, erst nach den Gates unten. |
| **C – Stufe B auf eigener VPS nach Spec** | Nicht gewählt. |

Die aktuelle Compose-Datei unter `deploy/infomaniak/compose.yml` beschreibt Stufe A (`ARCHIV_STUFE: A`).
`ARCHIV_ABLAGE_MAX_SPERRFRIST: P1D` und `ARCHIV_ABLAGE_MANIFEST_MAX_SPERRFRIST: P1D` begrenzen dort die Sperren der
Test-Buckets, damit diese beim Wechsel aufgeräumt werden können. Für Stufe B gelten die produktiven Sperrregeln
(Req 6.11, 15.4): Stufe `B`, Präfix `urinet-archiv-`, keine Höchstfristen, `manifest/` im Modus COMPLIANCE. Die
Werte kommen mit Aufgabe 31.2 ins Repo und werden erst im Wechsel (31.7) deployt.

## Go/No-go-Checkliste

Die Punkte müssen vor dem ersten echten Beleg (Spec 09, Aufgabe 30.1) mit dem genannten Beleg nachgewiesen sein. Die
Checkboxen bleiben offen, bis Fabian den Betriebsnachweis geprüft hat.

### Entscheide

- [ ] **Aufgabe 31.1 – offene Punkte entschieden:** Leistungsmessung vor oder nach dem ersten echten Beleg
  (Req 16.17, 16.26), Weg gegen Zugriffe an Traefik vorbei (16.28), Ersatz der Zugangsdaten der Stufe A (16.29),
  Budget der Restore-Probe (16.30), Zielzeit für den Wiederanlauf (16.31); Spec 09, E11 ist entschieden (28.11.2026). Beleg: datierte Einträge in
  der Spec.

### Plattform, Daten und Deploy

- [ ] **Req 16.1 – Plattform:** Stufe B läuft auf der bestehenden VPS vollständig aus der freigegebenen
  Compose-Konfiguration hinter Traefik; API und Keycloak sind unter den vorgesehenen Domains erreichbar. Beleg:
  Deploy-Protokoll; [Aufgaben 31.2 und 31.7](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 6.11, 15.4 – produktive Sperren:** Stufe `B`, Präfix `urinet-archiv-`, keine Höchstfristen; produktiver
  Bucket mit Versionierung, Object Lock und Bucket Policy (Deny auf `s3:BypassGovernanceRetention`). Beleg:
  Deploy- und Konfigurationsprotokoll; [Aufgabe 31.7](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 15.3 – Zeitstempeldienst angebunden:** vor dem ersten produktiven Archivieren. Beleg: erstes Manifest mit
  Zeitstempeln; [Aufgabe 31.7](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 16.7–16.10 – Datenstandort, Schlüssel und Deploy:** Speicher, Datenbank und KI liegen bei Infomaniak;
  CI-Logs enthalten keine Dokumentinhalte; der Sicherungslauf transportiert nur serverseitig verschlüsselte Daten;
  Secrets liegen nur als Dateien unter `/run/secrets/`; der Deploy erfolgt ausschliesslich über den geprüften
  GitHub-Actions-Workflow. Beleg: freigegebene Konfiguration und Deploy-Lauf;
  [tech.md, Regeln 6–7](../../.kiro/steering/tech.md), [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md).
- [ ] **Req 16.11, 16.13–16.14 – Migrationen, Architektur und Tests:** Migrationen bleiben mit dem Vorgänger
  kompatibel; die ArchUnit- und Req-Szenarien laufen in der CI grün. Beleg: CI für den deployten Commit.

### Schutzmassnahmen der geteilten VPS

- [ ] **Req 16.28 – Netze und Ports:** Am Netz «web» hängen nur `api` und `kc`; in den internen Netzen des Archivs
  hängen nur Container des Archivs; kein veröffentlichter Port ausser dem nach Spec 09, 1.6 freigegebenen Eingang;
  kein Container einer anderen App erreicht einen Dienst des Archivs an Traefik vorbei (Weg nach 31.1). Beleg:
  Netzprüfung auf der VPS im Wechselprotokoll; [Aufgabe 31.3](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 16.29 – Geheimnisse nur im Archiv:** Secret-Dateien nur in den Diensten des Archivs, die sie brauchen;
  keine geteilten Zugänge mit anderen Apps; Zugangsdaten der Stufe A nach dem Entscheid aus 31.1 ersetzt; privater
  Probe-Schlüssel nur root-lesbar. Beleg: Wechselprotokoll (nur Namen); Test der Compose-Datei aus 31.2.
- [ ] **Req 16.18, 16.30 – Ressourcen:** Speichermessung aus 1.10 mit 2144 MiB / 1.6 CPU inklusive `sftp` abgeschlossen
  (Entscheid Fabian 28.11.2026, Spec 09, E11), ohne Speicherabbruch und ohne kranke andere App; Budget eingehalten; Alarm bei
  Speicherabbruch oder kranker anderer App eingerichtet. Beleg: Messbericht und Alarmprotokoll;
  [Messanleitung Stufe A](./speichermessung-stufe-a.md), [Aufgaben 31.3 und 31.5](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 16.31 – Wiederaufbau:** Die Anleitung beschreibt den Wiederaufbau auf einer neuen VPS. Beleg:
  [docs/wiederherstellung.md](../wiederherstellung.md).

### Sicherung, Wiederherstellung und Betrieb

- [ ] **Req 16.2, 16.8 – Sicherung:** Der tägliche Export umfasst Archiv- und Keycloak-Datenbank, Realm,
  Bucket-Zuwachs und Manifest-Schlüssel; Chiffrate werden an die vorgesehenen Empfänger verschlüsselt und mit restic
  auf die Zweitkopie geschrieben; der tägliche Zugang kann nur anhängen. Die Aufbewahrung (30 Tages-, 12 Monats-
  und 10 Jahresstände, Bucket-Zuwächse vom Ausdünnen ausgenommen) gilt ab dem Wechsel. Beleg: Sicherungsprotokoll,
  `backup-gate.sh`; [Aufgabe 31.4](../../.kiro/specs/01-archiv-kern/tasks.md),
  [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md).
- [ ] **Req 16.3, 16.23–16.25, 16.27 – Restore-Probe:** Die Wiederherstellung ist in einer Testumgebung vollständig
  durchgespielt; die Restore-Probe lief auf der VPS vor dem Wechsel mit synthetischen Daten grün (Zählvergleich nach
  Spec 04, 13.7), Alarm bei rotem oder zu altem Lauf. Beleg: Restore-Protokoll und
  [docs/wiederherstellung.md](../wiederherstellung.md); [Aufgabe 31.4](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 16.4–16.5 – Health, Kennzahlen und Alarme:** Interne Health und Kennzahlen sowie Alarme für lang
  verarbeitete oder fehlerhafte Aufträge, fehlgeschlagene Sicherungen, fehlende Manifeste, endgültig gescheiterte
  Prüfläufe und JVM-Speicherschwellen sind angebunden und geprüft. Bekannte Lücke nach Entscheid 28.11.2026: keine
  eigene externe Prüfung des Anmelde-Hosts. Beleg: Monitoring- und Alarmprotokoll;
  [Aufgaben 5.6, 5.9 und 31.5](../../.kiro/specs/01-archiv-kern/tasks.md), [docs/alarme.md](../alarme.md).
- [ ] **Req 16.6, 16.12 – Verfahren und Notfallunterlagen:** Die freigegebene Verfahrensbeschreibung und
  versiegelte Notfallunterlagen sind vorhanden; keine Zugangsdaten stehen im Repository. Beleg: freigegebene
  Dokumente und dokumentierte Prüfung; [Aufgaben 5.7 und 5.10](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 16.16, 16.26 – Leistungsziele:** gemessen auf der VPS als eigenes Compose-Projekt mit synthetischen
  Daten, falls Fabian die Messung in 31.1 vor den ersten echten Beleg legt; sonst als offener Punkt nach dem Stichtag
  vermerkt. Beleg: Messbericht; [Aufgabe 31.6](../../.kiro/specs/01-archiv-kern/tasks.md),
  [Messanleitung Stufe B](./stufe-b-messung.md).

### Wechsel und Übernahme

- [ ] **Req 16.15, 16.17, 16.19 – Testdaten verworfen:** Test-Buckets der Stufe A frühestens 1 Tag nach dem letzten
  Upload geleert und gelöscht, Archiv- und Keycloak-Datenbank leer neu (Testbenutzer weg), Testschlüsselpaar
  verworfen; bestätigt, welche Testdaten auf Stufe A lagen. Beleg: Wechselprotokoll;
  [Aufgabe 31.7](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Aufgabe 31.8 – Go:** alle Punkte oben abgehakt; Ergebnis im Wechselprotokoll.
- [ ] **Aufgabe 31.9 – Restore-Probe auf Stufe B:** nach dem Probelauf mit echten Belegen und vor dem Import des
  Bestands grün. Beleg: Restore-Protokoll.
- [ ] **Aufgabe 5.8 – Übernahme und Abnahme:** über Spec 09, Welle 30; Realitätsprüfung erneut gelaufen, Stichprobe
  auffindbar, Prüflauf grün, Mandantentrennung geprüft. Beleg: Abnahmeprotokoll (Spec 09, 30.5).

## Zeitplan (Richtwerte, rückwärts vom Stichtag)

| Bis | Schritt |
| --- | --- |
| 02.12.2026 | 31.1 Entscheide |
| 11.12.2026 | 31.2 Stufenwerte und Prüfungen im Repo; 31.3 Speichermessung und Schutzmassnahmen |
| 18.12.2026 | 31.4 Sicherung und Restore-Probe; 31.5 Überwachung |
| 22.12.2026 | 31.6 Leistungsmessung, falls vor dem Wechsel |
| 28.12.2026 | synthetischer Probelauf auf Stufe A (Spec 09, 29.10), danach keine Uploads mehr auf Stufe A |
| 29.–30.12.2026 | 31.7 Wechsel |
| 31.12.2026 | 31.8 Go/No-go; Probelauf mit echten Belegen (Spec 09, 30.1) |
| 01.01.2027 | Stichtag (Spec 09, 30.2) |
| 08.01.2027 | 31.9 Restore-Probe auf Stufe B vor dem Bestandsimport |
| 31.01.2027 | Bestand 2026 importiert (Spec 09, 30.3, 30.4) |

## Nicht oder nur mit Aufwand umkehrbar

- Echte Belege auf der VPS lassen sich nicht dadurch «zurücknehmen», dass das Original anderswo führend bleibt.
- Object Lock verhindert das vorzeitige Löschen bzw. Verkürzen aktiver Sperren im produktiven Bucket. Eine spätere
  Entscheidung für einen anderen Umgang hebt bestehende Sperren nicht auf; die Zweitkopie kann Daten zusätzlich
  aufbewahren. Siehe [ADR-007](../adr/ADR-007-unveraenderbarkeit-governance.md) und
  [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md).
- Das Verwerfen der Testdaten der Stufe A (Buckets, Datenbanken) ist endgültig; es betrifft nur Testdaten.
- Ein späterer Umzug auf eine eigene VPS bleibt möglich (Wiederaufbau nach Req 16.31), ist aber ein eigener Entscheid.
- Die Angaben zu einer eigenen Stufe-B-VPS (4/4/80, 4/8/160) in älteren Spec-Stellen sind seit dem Entscheid vom
  28.11.2026 historisch.
