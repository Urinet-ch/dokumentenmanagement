# ADR-008: Sicherung als verschlüsselte Zweitkopie bei einem zweiten Schweizer Anbieter über den zentralen Sicherungs-Workflow

**Status:** Angenommen – freigegeben per Merge an Gate 0 (Aufgabe 0.6, PR #23, Fabian, 09.11.2026)

**Datum:** 08.11.2026

## Kontext
- Object Lock (ADR-007) schützt vor Löschen und Überschreiben (Dokumente unter GOVERNANCE nur gegen Fehler der App und Versehen, siehe ADR-007, Nachtrag 09.11.2026), nicht vor dem Ausfall des Anbieters, dem Verlust des Kontos oder einer beschädigten Datenbank. Ohne Datenbank fehlen die Metadaten, ohne Bucket die Belege.
- Die Belege müssen während der ganzen Aufbewahrungsfrist verfügbar und lesbar bleiben (Req 6, 15; Leitsatz in product.md).
- Alle echten Daten bleiben in der Schweiz (tech.md Regel 6, Req 16.7). Der Betrieb sichert seine Apps aber über einen zentralen Workflow in GitHub Actions (maintenance-Muster), dessen Runner ausserhalb der Schweiz laufen.
- Ein kompromittierter Server soll die Sicherung weder lesen noch löschen können.
- Zur Wiederherstellung gehören auch Anmeldung (Keycloak-Datenbank und Realm, P5) und der Manifest-Schlüssel, sonst lassen sich nach einem Verlust keine gültigen Manifeste mehr erzeugen.
- In Stufe A liegen nur Test- und anonymisierte Daten (Req 16.15, ADR-010).

## Entscheid
1. **Umfang** (P4, Req 16.2): täglich `pg_dump` der Archiv- und der Keycloak-Datenbank, Realm-Export, inkrementelle Kopie des Buckets (Originale, Arbeitsfassungen, PDF/A, Manifeste; neue Objekte seit dem letzten Export, mit Liste der Versions-IDs) und Manifest-Schlüssel. Den Export erzeugt der Dienst `archiv-sicherung`, nur im internen Netz des Archivs.
2. **Verschlüsselung auf dem Server:** Der Export wird asymmetrisch an den öffentlichen Sicherungsschlüssel verschlüsselt (z. B. `age`). Der Server hält nur den öffentlichen Schlüssel; der private Schlüssel liegt nur in den Notfallunterlagen (Req 16.8, 16.12).
3. **Transport und Zweitkopie:** Der zentrale Sicherungs-Workflow holt das Chiffrat per SSH und schreibt es mit restic zu einem zweiten Schweizer Anbieter: [offen: zweiter Schweizer Backup-Anbieter – Entscheid vor Welle 5]. Der restic-Schlüssel liegt nur als GitHub-Secret dieses Workflows und in den Notfallunterlagen, nie auf dem Server. Das ist die einzige zulässige Ausnahme von Req 16.7.
4. **Aufbewahrung** je App: 30 Tages-, 12 Monats- und 10 Jahresstände.
5. **Prüfung:** `backup-gate.sh` prüft zusätzlich, dass beide Dumps vorliegen und der Bucket-Export vollständig ist (Anzahl Objekte = Einträge in der Datenbank). Die Überwachung meldet einen zu alten Export, der Workflow seinen eigenen Fehlschlag (Req 16.5).
6. **Restore-Probe**, automatisiert und wiederkehrend ([offen: Rhythmus der Restore-Probe – Entscheid vor Welle 5]): in einer getrennten Umgebung in der Schweiz (Compose-Projekt `archiv-probe`, Probe-Bucket im Public-Cloud-Projekt) Datenbanken und Dateien wiederherstellen, Testbenutzer anmelden, ein Dokument öffnen, mit grünem Prüflauf enden (Req 16.3). Wie der private Schlüssel zur Probe kommt, ohne dass der Runner Klartext sieht: [offen: Schlüsselweg der Restore-Probe – Entscheid vor Welle 5].
7. **Anleitung** `docs/wiederherstellung.md`, einmal vollständig in einer Testumgebung durchgespielt (Req 16.3).
8. **Stufe A:** nur die Restore-Probe als Funktionstest, keine Aufbewahrungspflicht (Req 16.19). `archiv-probe` zählt zum Speicherbudget; während der Probe ruht der Archiv-Stack.

## Betrachtete Alternativen
- **Nur Sicherungen oder Replikation beim selben Anbieter:** abgelehnt (P4). Ausfall des Anbieters oder Verlust des Kontos träfe Original und Kopie zugleich.
- **Server schreibt selbst zum Backup-Anbieter:** abgelehnt. Der Server bräuchte den restic-Schlüssel und könnte bei einem Einbruch die Sicherung löschen oder lesen; zudem wiche das Archiv vom zentralen maintenance-Muster des Betriebs ab.
- **Verschlüsselung erst im Runner bzw. nur durch restic:** abgelehnt. Der Runner ausserhalb der Schweiz sähe Klartext; das verletzt Req 16.7 und tech.md Regel 6.
- **Nur Datenbanken sichern, weil Object Lock die Dateien schützt:** abgelehnt (P4 «Sicherung Belegdateien»). Object Lock hilft nicht gegen den Verlust des Buckets.
- **Backup-Anbieter ausserhalb der Schweiz:** abgelehnt (Req 16.7).

## Folgen
- **Positiv:** Ein Verlust von VPS, Bucket oder Konto ist aus der Zweitkopie behebbar; der Runner und der Backup-Anbieter sehen nur Chiffrat. Der Server kann die Sicherung weder entschlüsseln noch löschen.
- **Positiv:** Manifeste bleiben nach einer Wiederherstellung gültig, weil SHA-256 massgeblich ist; der Prüflauf erfasst die Versions-IDs im neuen Bucket neu (Req 15.5, ADR-007).
- **Negativ:** Drei Schlüssel mit getrennter Verwahrung (öffentlicher Sicherungsschlüssel auf dem Server, privater offline, restic-Schlüssel im Workflow). Ohne die Notfallunterlagen ist keine Wiederherstellung möglich (Aufgabe 5.10).
- **Negativ:** Kosten für den zweiten Anbieter und Platz auf der VPS für den täglichen Export; der Bucket-Export ist deshalb inkrementell.
- **Umsetzung:** Export unter `deploy/backup/` im Repository; Workflow, `backup-gate.sh` und Restore-Probe im Infra-Repo der VPS (Aufgabe 5.5); Alarm (Aufgabe 5.6). Die Aufbewahrung nach Req 16.2 gilt erst ab Stufe B (Aufgabe 5.11).
- **Tests:** Nachweis manuell über Sicherungs- und Restore-Protokoll (Req 16.2, 16.8); die Restore-Probe ist der wiederkehrende Betriebstest (design.md, Testing Strategy).
- **Betrieb:** Die Zweitkopie trägt keine Object-Lock-Sperren. Nach einer Wiederherstellung in einen neuen Bucket sind die Sperren nach ADR-004 und ADR-007 neu zu setzen; die Spec regelt das noch nicht ausdrücklich (zu klären in `docs/wiederherstellung.md`, Aufgabe 5.5). Für die Restore-Probe: [offen: ob die Restore-Probe im Probe-Bucket Sperren setzt – Entscheid vor Welle 5]; läuft die Probe mit den Stufenwerten der Stufe B (Sperrmodus von `manifest/` fest COMPLIANCE, Aufgabe 2.3), wären Manifeste dort (Req 6.11, Option B) bis zum Ablauf ihrer Sperre nicht löschbar.

## Verweise
- Req 15.5, 16.2, 16.3, 16.5, 16.7, 16.8, 16.12, 16.15, 16.19
- P4, P5, P17
- Aufgaben 4.9, 5.3, 5.5, 5.6, 5.10, 5.11
- design.md: «Sicherung und Wiederherstellung», «Secrets und Konfiguration»; tech.md Regel 6 und «Plattform und Betrieb»
- ADR-004 (Bucket und Präfixe), ADR-007 (Sperren, Manifest), ADR-010 (Plattform und Stufen)
