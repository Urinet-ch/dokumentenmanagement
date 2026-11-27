# Entscheidungshilfe: Start mit echten Belegen

Diese Checkliste bereitet den Startentscheid vor; sie nimmt ihn nicht vorweg.
Die Anforderungen bleiben unverändert. Insbesondere gilt Req 16.15 weiter:
Auf Stufe A sind nur Test- und anonymisierte Daten zulässig. Eine Kopie eines
echten Belegs bleibt ein echter Beleg, auch wenn das Original anderswo führend
ist. Ein Häkchen ist erst gesetzt, wenn der verlinkte Betriebsnachweis vorliegt;
eine Spec, ein Test oder diese Checkliste allein belegt keinen erfolgreichen
Betrieb.

## Varianten

| Variante | Einordnung nach dem aktuellen Stand |
| --- | --- |
| **A – Pilot mit Kopien, Original führend anderswo** | Ein Pilot mit Kopien echter Belege auf der bestehenden VPS (Stufe A) ist nach Req 16.15 nicht zulässig. Ein Pilot mit echten Kopien wäre erst auf Stufe B und nach deren Start-Gates möglich; dort gelten dieselben Datenschutz-, Sicherungs- und Aufbewahrungspflichten wie für den Start nach Variante C. |
| **B – Bestehende VPS wird produktiv** | Nicht mit den unveränderten Anforderungen vereinbar: Die bestehende VPS ist Stufe A, und Req 16.15 schliesst echte Belege dort aus. Diese Checkliste erteilt keine Ausnahme. |
| **C – Stufe B nach Spec** | Der vorgesehene Weg: Stufe B einrichten, die Voraussetzungen unten belegen, Stufe A bereinigen und erst danach echte Belege übernehmen. |

Die aktuelle Compose-Datei unter `deploy/infomaniak/compose.yml` beschreibt
Stufe A (`ARCHIV_STUFE: A`). `ARCHIV_ABLAGE_MAX_SPERRFRIST: P1D` und
`ARCHIV_ABLAGE_MANIFEST_MAX_SPERRFRIST: P1D` begrenzen dort die Sperren der
Test-Buckets, damit diese beim Wechsel aufgeräumt werden können. Diese
Stufe-A-Werte dürfen nicht ungeprüft in die Produktion auf Stufe B übernommen
werden. Für Stufe B müssen die produktiven Sperrregeln gelten; die Spec verlangt
für `manifest/` COMPLIANCE und für pflichtige Dokumente die festgelegten
Sperren. Siehe Req 6.11 und 15.4 sowie Aufgabe 5.4.

## Gemeinsame Go/No-go-Checkliste

Für den Produktivstart (Variante C oder ein Pilot mit echten Belegen auf
Stufe B) müssen die folgenden Punkte geprüft und mit dem jeweils genannten
Beleg nachgewiesen werden. Die Checkboxen bleiben offen, bis Fabian den
Betriebsnachweis geprüft hat.

### Plattform, Daten und Deploy

- [ ] **Req 16.1 – Plattform:** Stufe B läuft vollständig aus der freigegebenen
  Compose-Konfiguration hinter Traefik; API und Keycloak sind unter den
  vorgesehenen Domains erreichbar. Beleg: Deploy-Protokoll; Vorgaben in
  [Req 16.1](../../.kiro/specs/01-archiv-kern/requirements.md),
  [Aufgaben 5.4 und 5.11](../../.kiro/specs/01-archiv-kern/tasks.md),
  [ADR-010](../adr/ADR-010-plattform-zwei-stufen.md).
- [ ] **Req 16.7–16.10 – Datenstandort, Schlüssel und Deploy:** Speicher,
  Datenbank und KI liegen bei Infomaniak; CI-Logs enthalten keine
  Dokumentinhalte; der Sicherungslauf transportiert nur serverseitig
  verschlüsselte Daten; Secrets liegen nur als Dateien unter `/run/secrets/`;
  der Deploy erfolgt ausschliesslich über den geprüften GitHub-Actions-Workflow.
  Beleg: freigegebene Konfiguration und Deploy-Lauf;
  [tech.md, Regeln 6–7](../../.kiro/steering/tech.md),
  [Aufgabe 5.4](../../.kiro/specs/01-archiv-kern/tasks.md),
  [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md) und
  [ADR-010](../adr/ADR-010-plattform-zwei-stufen.md).
- [ ] **Req 16.11, 16.13–16.14 – Migrationen, Architektur und Tests:**
  Migrationen bleiben mit dem Vorgänger kompatibel; die ArchUnit- und
  Req-Szenarien laufen in der CI grün. Beleg: CI für den deployten Commit und
  die Tests in [tech.md](../../.kiro/steering/tech.md).
- [ ] **Req 16.15 – keine echten Belege auf Stufe A:** Die Bestätigung im
  Mess- und Wechselprotokoll nennt nur zulässige Test- und anonymisierte Daten.
  Beleg: manuelle Bestätigung nach
  [Req 16.15](../../.kiro/specs/01-archiv-kern/requirements.md) und
  [Aufgaben 1.10 und 5.11](../../.kiro/specs/01-archiv-kern/tasks.md).
  Geschwärzte echte Belege zählen weiterhin als echt.

### Sicherung, Wiederherstellung und Betrieb

- [ ] **Req 16.2, 16.8 – Sicherung:** Der tägliche Export umfasst Archiv- und
  Keycloak-Datenbank, Realm, Bucket-Zuwachs und Manifest-Schlüssel; Chiffrate
  wird an die vorgesehenen Empfänger verschlüsselt. Die Aufbewahrung umfasst
  30 Tages-, 12 Monats- und 10 Jahresstände; Bucket-Zuwächse sind vom
  Ausdünnen ausgenommen. Beleg: erfolgreiche
  Sicherung, `backup-gate.sh` und Restore-Protokoll;
  [Aufgabe 5.5](../../.kiro/specs/01-archiv-kern/tasks.md) und
  [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md). Die Aufbewahrung nach
  Req 16.2 gilt erst auf Stufe B, nicht für Stufe A.
- [ ] **Req 16.3, 16.19 – Wiederherstellung:** Die Wiederherstellung ist in
  einer Testumgebung vollständig durchgespielt; die automatisierte Probe läuft
  monatlich und nach den in Req 16.3 genannten Änderungen und Auslösern. Auf
  Stufe A läuft die Sicherung nur als Funktionstest mit Testdaten und eigenem
  Testschlüsselpaar; die Aufbewahrungspflicht beginnt auf Stufe B. Beleg:
  Restore-Protokoll und
  [docs/wiederherstellung.md](../wiederherstellung.md);
  [Aufgabe 5.5](../../.kiro/specs/01-archiv-kern/tasks.md) und
  [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md).
- [ ] **Req 16.4–16.5 – Health, Kennzahlen und Alarme:** Interne Health- und
  Kennzahlen sowie Alarme für lang verarbeitete oder fehlerhafte Aufträge,
  fehlgeschlagene Sicherungen, fehlende Manifeste, endgültig gescheiterte
  Prüfläufe und JVM-Speicherschwellen sind auf Stufe B angebunden und geprüft.
  Beleg: Monitoring- und Alarmprotokoll;
  [Aufgaben 5.6 und 5.9](../../.kiro/specs/01-archiv-kern/tasks.md) und
  [docs/alarme.md](../alarme.md).
- [ ] **Req 16.6, 16.12 – Verfahren und Notfallunterlagen:** Die freigegebene
  Verfahrensbeschreibung und versiegelten Notfallunterlagen sind vorhanden;
  keine Zugangsdaten stehen im Repository. Beleg: freigegebene Dokumente und
  dokumentierte Prüfung; [Aufgaben 5.7 und 5.10](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Req 16.16–16.18 – Leistungsziele und Abbruchkriterien:** Die
  Leistungsziele werden auf Stufe B geprüft. Auf Stufe A gelten die Grenzen
  von 2048 MiB `mem_limit` und 1.5 `cpus`; ein nicht manueller
  Speicherabbruch oder die Health-Prüfung einer anderen App löst den
  Abbruch-/Wechselweg aus. Beleg: Mess- und Wechselprotokoll;
  [Aufgabe 1.10](../../.kiro/specs/01-archiv-kern/tasks.md),
  [Req 16.16–16.18](../../.kiro/specs/01-archiv-kern/requirements.md) und
  [Messanleitung Stufe A](./speichermessung-stufe-a.md).

### Wechsel und Übernahme

- [ ] **Req 16.17 – Stufe B vor echten Belegen:** Die eigene VPS ist eingerichtet,
  das Deploy-Ziel ist umgestellt, Stufe B startet leer und die Testdaten der
  Stufe A sind nach Ablauf der Sperren entfernt. Beleg: Wechselprotokoll mit
  Bestätigung der verwendeten Testdaten und der leeren Stufe B;
  [Aufgabe 5.11](../../.kiro/specs/01-archiv-kern/tasks.md) und
  [ADR-010](../adr/ADR-010-plattform-zwei-stufen.md).
- [ ] **Req 16.26 – Leistungsmessung vor dem Umstellen:** Falls die
  Leistungsziele noch nachzuweisen sind, erfolgt die Messung auf der neuen
  Stufe-B-VPS mit den dortigen Diensten und Limits, `archiv.stufe=A`, einem
  eigenen Mess-Mandanten und synthetischen Daten. Messdaten und Bucket werden
  danach verworfen; Stufe B startet leer. Beleg: Messbericht und
  Aufräumprotokoll; [Req 16.26](../../.kiro/specs/01-archiv-kern/requirements.md),
  [Aufgabe 5.11](../../.kiro/specs/01-archiv-kern/tasks.md) und
  [Messanleitung Stufe B](./stufe-b-messung.md).
- [ ] **Aufgabe 5.4 – Produktivbetrieb:** Stufe B, produktiver Bucket und
  Keycloak-Realm sind eingerichtet; TOTP ist aktiv, Schlüsselzugriffe sind
  korrekt getrennt und der Zeitstempeldienst ist angebunden. Beleg:
  Deploy-/Konfigurationsprotokoll; [Aufgabe 5.4](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Aufgabe 5.5 – Sicherung und Restore:** Die Sicherung und die
  Restore-Probe sind auf Stufe B eingerichtet und erfolgreich. Beleg:
  Sicherungs- und Restore-Protokoll; [Aufgabe 5.5](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Aufgabe 5.8 – Übernahme und Abnahme:** Erst nach abgeschlossenem Wechsel,
  angebundenem Zeitstempeldienst, grüner Sicherung/Restore-Probe und erneuter
  Realitätsprüfung werden die Belege übernommen. Die Stichprobe ist auffindbar,
  der Importbericht vollständig, der Prüflauf grün und die Mandantentrennung
  geprüft. Beleg: Abnahmeprotokoll; [Aufgabe 5.8](../../.kiro/specs/01-archiv-kern/tasks.md).
- [ ] **Aufgabe 5.11 – Wechsel:** Neue VPS, Deploy-Ziel, getrennte Rollen,
  Sicherung, Restore-Probe, Monitoring, Messung (falls noch offen), DNS und
  Aufräumen der Stufe A sind abgeschlossen. Beleg: Wechselprotokoll;
  [Aufgabe 5.11](../../.kiro/specs/01-archiv-kern/tasks.md).

## Nicht oder nur mit Aufwand umkehrbar

- Echte Belege auf einer Plattform können nicht dadurch «zurückgenommen» werden,
  dass das Original anderswo führend bleibt. Auf Stufe A ist ihre Verarbeitung
  durch Req 16.15 ausgeschlossen.
- Auf Stufe B verhinderte Object Lock das vorzeitige Löschen bzw. Verkürzen
  aktiver Sperren. Eine nachträgliche Entscheidung für einen anderen Umgang
  hebt bestehende Sperren nicht auf; die Zweitkopie kann Daten zusätzlich
  aufbewahren. Siehe [ADR-007](../adr/ADR-007-unveraenderbarkeit-governance.md)
  und [ADR-008](../adr/ADR-008-sicherung-zweitkopie.md).
- Die Bestellung bzw. Aufrüstung der Stufe-B-VPS verursacht Kosten; die
  8-GB-/160-GB-Aufrüstung ist laut Aufgabe 5.11 nicht umkehrbar. Der DNS-Wechsel
  ist technisch rückstellbar, macht aber keinen bereits begonnenen
  Produktivbetrieb rückgängig.
- Der Beschluss zur Stufe B von 22.11.2026 nennt 4 vCPU, 8 GB RAM und 160 GB
  Disk. Ältere Angaben von 4 GB/80 GB in ADR-010 und Req 16.1 sind damit
  historisch; die Spec-Stellen werden hier nicht geändert und müssen bei einer
  späteren Spec-Nachführung ausdrücklich abgeglichen werden.

## Offen für Fabian

Welche Variante soll gelten: **A**, **B** oder **C**? Bis zu diesem Entscheid
bleibt die Checkliste eine Entscheidungshilfe; die Anforderungen, insbesondere
Req 16.15, werden nicht geändert.
