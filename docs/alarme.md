# Alarme und Kennzahlen der Überwachung

Diese Seite erklärt, welche Alarm-Mails das Archiv an den Inhaber eines Mandanten schickt, welche Kennzahlen es
bereitstellt und was man bei einem Alarm zuerst prüft. Sie beschreibt den Stand des Codes (`Ueberwachung`,
`SpeicherUeberwachung`, `SicherungUeberwachungAdapter`, `ManifestBefunde`, `MicrometerKennzahlen`); Betrieb und
Einrichtung des Mailversands stehen im [README](../README.md#benachrichtigungen-per-mail-spec-01-aufgabe-56).

## Grundregeln

- **Nur IDs und Status.** Betreff und Text nennen Dokument-ID, Datum, Sicherungscode oder Speicherbereich, nie
  Dateinamen, Metadaten oder Inhalte (Req 16.7). Die Speicher-Mail nennt zusätzlich die Auslastung in Prozent und MiB.
- **Einmal je Auslöser und Stufe.** Ein Befund wird in der Tabelle `alarm` festgehalten; je Schlüssel
  (Quelle, Gegenstand, Status) geht genau eine Mail, auch wenn der Befund viele Läufe lang bestehen bleibt. Ist er
  behoben, wird der Alarm erledigt (ohne Mail). Wechselt ein Dokument den Status und steht wieder zu lange, ist das ein
  neuer Befund und damit eine neue Mail. Scheitert der Versand, versucht es der nächste Lauf wieder.
- **Takt.** Die Überwachung läuft im Worker alle 15 Minuten (`archiv.ueberwachung.intervall`, `off` schaltet sie ab).
  Die Speicherprüfung läuft in jeder JVM für sich (`archiv.ueberwachung.speicher.intervall`, Standard `PT5M`).
- **Ohne Empfänger keine Mail.** Fehlt der Empfänger des Mandanten, steht nur eine Log-Zeile mit der ID im Log, und der
  nächste Lauf versucht es erneut. Ausnahme Speicher-Mail: Ohne Empfänger des Betriebs-Mandanten schreibt sie keine
  eigene Log-Zeile (es bleibt die WARN-Zeile «Speicher … knapp»), und der Versuch wiederholt sich im Takt der
  Speicherprüfung (Standard 5 Minuten).

## Alarm-Mails

| Alarm (Betreff beginnt mit «UriNet Archiv: …») | Auslöser | Schwelle und Einstellung | Mail-Inhalt | Was prüfen |
| --- | --- | --- | --- | --- |
| `Dokument <id> hängt in <STATUS>` | Dokument steht länger als die Grenze (strikt) in `VERARBEITUNG`, `FEHLER` oder `ARCHIVIERUNG_FEHLER`; je Mandant geprüft | `archiv.ueberwachung.grenze` (Standard `1h`, muss grösser als 0 sein) | Mandant, Dokument-ID, Status | Im Eingangskorb «Wiederholen» wählen; bei `VERARBEITUNG` im Log nach der Dokument-ID suchen, ob der Auftrag noch läuft oder hängt |
| `Manifest <JJJJ-MM-TT> für <mandant> FEHLT` | Das erste fällige Tages-Manifest fehlt: Das Tagesende und das zuletzt erstellte Manifest liegen beide länger als die Frist zurück, oder ein Auftrag `MANIFEST` ist nach dem Fälligwerden des Tages (Tagesende plus Nachlauf) und nach dem letzten Fortschritt (letztes Manifest, ohne Manifest die Anlage des Mandanten) in `FEHLER` geendet; ein neuerer Folgeauftrag in `OFFEN` oder `LAEUFT` hebt den Befund nicht auf, erst ein neues Manifest. Höchstens ein Befund je Mandant | `archiv.ueberwachung.manifest-frist` (Standard `PT6H`, muss grösser als 0 sein) | Mandant, Datum, Befund `FEHLT`; Hinweis, dass der Nachweis für diesen und alle folgenden Tage fehlt | Erreichbarkeit der Zeitstempeldienste und des Object Storage prüfen, im Log nach dem Datum suchen, Auftrag `MANIFEST` prüfen; der nächste tägliche Lauf holt fehlende Tage nach |
| `Sicherung <code> FEHLT` oder `ZU_ALT` | Für eine Sicherungsart ist kein Export protokolliert (`FEHLT`) oder der jüngste ist älter als die Frist (`ZU_ALT`). Arten ohne Mandant: `db`, `keycloak`, `realm`, `schluessel`; je aktivem Mandant: `bucket-<mandant>`, `inventar-<mandant>` | `archiv.ueberwachung.sicherung-frist` (Standard `PT36H`), `archiv.ueberwachung.sicherung-aktiv` (Standard `false`: ohne Einschalten keine Prüfung, nur einmal eine Warnung im Log; Ausschalten erledigt offene Sicherungsalarme nicht, sie bleiben stehen); die Prüfung braucht einen aktiven Betriebs-Mandanten, sonst steht bei jedem Lauf eine Warnung im Log; Empfänger gehört dem Mandanten aus `archiv.ueberwachung.betrieb-mandant` (Standard `urinet`) | Mandant, Sicherungscode, Befund | Sicherungs-Export und Sicherungs-Workflow prüfen (Lauf, Fehlermeldung) |
| `Speicher der <API/Worker/API und Worker> knapp` bzw. `kritisch (<Bereich> <n> %)` | Auslastung eines Bereichs (`Metaspace`, `Compressed Class Space`, `Heap` nach der letzten Garbage Collection) erreicht die Schwelle. Ohne Maximum (z. B. kein `-XX:MaxMetaspaceSize`) gibt es keine Schwelle, nur eine Log-Zeile | `archiv.ueberwachung.speicher.warn-prozent` (Standard 85) und `archiv.ueberwachung.speicher.kritisch-prozent` (Standard 95); es gilt `0 < warn < kritisch <= 100`, sonst bricht der Start ab | Rolle, Bereich, Prozent, benutzte und maximale MiB; Empfänger ist der Betriebs-Mandant (`archiv.ueberwachung.betrieb-mandant`) | Limit des betroffenen Bereichs in den JVM-Optionen prüfen und bei Bedarf erhöhen; läuft ein Bereich voll, beendet sich die JVM und startet neu. Messung und Einordnung: [speicherbudget.md](speicherbudget.md) |
| Jahresexport mit Status `FEHLER` | Der Auftrag `EXPORT_JAHR` ist nach dem letzten Fehlversuch gescheitert | Zeitgrenze `archiv.auftrag.zeitgrenze.EXPORT_JAHR` | **Keine Mail** (so in Spec 02 festgelegt); der Export zeigt Status `FEHLER`, die Fehlerart und die Fehler-ID, im Log steht «Export … gescheitert» | Fehlerart lesen: `VERWAIST` (Worker hat sich nicht mehr gemeldet, z. B. JVM beendet), `ZEITGRENZE` (Lauf zu lang), `FEHLGESCHLAGEN` (anderer Fehler, im Log nach der Fehler-ID suchen); danach den Export neu anfordern |

Zur Stufe des Speicher-Alarms: Pro Bereich geht je eine Mail für `warn` und für `kritisch`. Erneut meldet das Archiv
erst, nachdem der Wert wieder unter die Schwelle gefallen ist; der Zustand liegt nur im Speicher, nach einem Neustart
kann dieselbe Stufe also noch einmal gemeldet werden. Springt der Wert gleich auf `kritisch`, entfällt die Mail für
`warn`.

### Weitere Mails (keine Alarme der Überwachung)

| Mail | Wann |
| --- | --- |
| `Archivierung von Dokument <id> gescheitert` | Archivierung scheitert endgültig; Dokument steht in `ARCHIVIERUNG_FEHLER` |
| `Prüflauf <id> mit <n> Abweichungen` bzw. `abgebrochen` | Ergebnis des Prüflaufs; je Lauf eine Mail, ohne Eintrag in `alarm` (ob der Prüflauf eine Quelle des Alarmmodells wird, lässt `design.md` offen) |
| `Mail-Eingang für <mandant> gestört` | Abruf des Postfachs scheitert mehrfach in Folge |
| `Jahresexport <id> bereit` | Export fertig (nicht in Stufe P) |

## Kennzahlen unter `/q/metrics`

`/q/metrics` ist nur intern erreichbar (nicht öffentlich) und enthält nur Zähler und Feldnamen, keine Inhalte. Die
Werte der Auftrags- und Dokumentkennzahlen erhebt ein eigener Takt (`archiv.kennzahlen.intervall`, Standard `1m`), nicht
der Abruf; liefert ein Mandant keinen Stand, verschwinden seine Reihen, statt einen alten Wert zu zeigen.

| Name | Art | Tags | Bedeutung |
| --- | --- | --- | --- |
| `archiv_auftraege` | Gauge | `mandant`, `status` (`OFFEN`, `LAEUFT`, `FEHLER`) | Offene, laufende und fehlgeschlagene Aufträge; ein dauerhaft hoher Wert für `FEHLER` oder `OFFEN` heisst, dass die Schlange nicht abgearbeitet wird |
| `archiv_dokumente` | Gauge | `mandant`, `status` (`NEU`, `VERARBEITUNG`, `EINGANGSKORB`, `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER`, `FEHLER`) | Dokumente je Status ohne die Endzustände `ARCHIVIERT` und `VERWORFEN`; `FEHLER` und `ARCHIVIERUNG_FEHLER` sind die Fehler, die der Dokument-Alarm nach der Grenze meldet |
| `archiv_eingangskorb` | Gauge | `mandant` | Dokumente im Eingangskorb, also Status `EINGANGSKORB`, `FEHLER` und `ARCHIVIERUNG_FEHLER` zusammen |
| `archiv_jvm_speicher_prozent` | Gauge | `pool` (`Metaspace`, `Compressed Class Space`, `Heap`) | Auslastung des Bereichs in Prozent des Maximums, gemessen im Takt der Speicherprüfung; ohne Maximum keine Reihe. Grundlage des Speicher-Alarms, siehe [speicherbudget.md](speicherbudget.md) |
| `archiv_ki_feld_bestaetigt_total`, `archiv_ki_feld_korrigiert_total` | Zähler | `feld`, `mandant` | Wie oft ein KI-Vorschlag für das Feld unverändert bestätigt bzw. korrigiert wurde; die Reihen aller Felder entstehen beim ersten Zählen eines Mandanten mit 0 |
| `archiv_ki_feld_korrekturquote` | Gauge | `feld`, `mandant` | korrigiert geteilt durch bestätigt seit dem Start des Prozesses (0 ohne Bestätigung); die Quote über einen längeren Zeitraum rechnet das Monitoring aus den Zählern (`increase(…[90d])`) |

Die Kennzahlen des Speichers sind je JVM (API und Worker getrennt), die Zähler der KI-Felder gehören dem Prozess, der
den Vorschlag bestätigt.

## Abweichungen zwischen Spec und Code

Stand dieser Seite gegen `design.md` «Überwachung» (Spec 01) und Spec 02:

- **Manifest:** `design.md` nennt «fehlende oder ungültige Manifeste». Die Überwachung meldet nur `FEHLT`; ob ein
  vorhandenes Manifest gültig ist, prüfen Prüfwerkzeug und Prüflauf (Mail «Prüflauf … Abweichungen»).
- **Sicherung:** `design.md` nennt «das Alter des letzten Sicherungs-Exports». Der Code prüft jede Sicherungsart einzeln
  und ist ab Werk ausgeschaltet (`archiv.ueberwachung.sicherung-aktiv=false`).
- **Dokument-Mail:** Der Text nennt «Standard 1 Stunde», auch wenn `archiv.ueberwachung.grenze` anders gesetzt ist.
- **Export:** Ein gescheiterter Jahresexport löst keine Mail aus (Spec 02: «ohne Mail»); er ist nur über Status, Fehlerart
  und Log sichtbar.
- **Speicher-Mail:** Req 16.7 schliesst Dokumentinhalte aus; die Speicher-Mail nennt Bereich, Prozent und MiB. Es sind
  Betriebswerte ohne Bezug zu Dokumenten oder Personen, also keine Verletzung (Req 16.5 nennt Stufen und Bereich selbst).
