# Alarme und Kennzahlen der Überwachung

Diese Seite erklärt, welche Alarm-Mails das Archiv an den Inhaber eines Mandanten schickt, welche Kennzahlen es
bereitstellt und was man bei einem Alarm zuerst prüft. Sie beschreibt den Stand des Codes (`Ueberwachung`,
`SpeicherUeberwachung`, `SicherungUeberwachungAdapter`, `ManifestBefunde`, `MicrometerKennzahlen`); Betrieb und
Einrichtung des Mailversands stehen im [README](../README.md#benachrichtigungen-per-mail-spec-01-aufgabe-56).

## Betriebsnachweis für Fabian (Aufgabe 5.6)

Diese Checkliste ist eine Vorbereitung, kein durchgeführter Nachweis. Es wird nichts auf der VPS geändert;
der Wechsel auf Stufe B ist nicht beauftragt. Ergebnisse ohne Geheimnisse, Adressen oder Dokumentdaten festhalten.

- [ ] Mailweg und Empfänger anhand des README prüfen; mit einem freigegebenen synthetischen Test einen Alarm
  auslösen und Zustellung sowie Inhalt (nur IDs und zulässige Betriebswerte) nachweisen.
- [ ] Öffentlichen schlanken Health-Pfad und die Abschirmung von Detail-Health und Kennzahlen prüfen; intern
  Auftrags-, Dokument-, Eingangskorb-, Korrekturquoten- und Speicherkennzahlen kontrollieren.
- [ ] Nach einer allfälligen Freigabe und Aktivierung des Sicherungs-Workflows die Sicherungsüberwachung einschalten
  und fehlende bzw. zu alte Exporte sowie die Zustellung eines Alarms nachweisen. Bis dahin bleibt sie deaktiviert.
- [ ] Für die Restore-Probe den Alarm bei Fehlschlag **und** bei einem letzten grünen Lauf älter als 35 Tage
  separat im Sicherungs-Workflow und Monitoring nachweisen (Req 16.25). Die App-Überwachung kennt dieses Ergebnis
  nicht; ein Sicherungsalarm allein ist kein Nachweis dafür.
- [ ] Alarm für das Kontingent der Zweitkopie einrichten bzw. nachweisen; eine geeignete Schwelle und Zustellung
  prüfen. Eine Schwelle wird hier nicht vorgegeben.
- [ ] Nachweise mit Datum, geprüftem Stand und Ergebnis ablegen; offene Punkte dokumentieren. Aufgabe 5.6 bleibt
  bis zur Erfüllung der Infrastruktur- und Betriebsnachweise offen.

## Vorbereitung der Monitoring-Anbindung (Aufgabe 5.9)

Nur Checkliste, keine ausgeführte Anbindung; Stufe B bleibt zurückgestellt. Keine Betriebsadressen, Geheimnisse oder
Dokumentdaten in Nachweisen ablegen. Die Tabelle «Kennzahlen unter `/q/metrics`» enthält die vorhandenen Namen und Tags.

- [ ] Internen Zugang zu Detail-Health und `/q/metrics` im Monitoring einrichten; prüfen, dass beide nicht öffentlich
  erreichbar sind. Den öffentlichen Liveness-Pfad getrennt überwachen.
- [ ] Erreichbarkeit und Health von API und Worker sowie deren Ausfall mit einem freigegebenen Test nachweisen;
  Alarmweg und Empfänger prüfen.
- [ ] `archiv_auftraege{mandant,status="FEHLER"}` je aktivem Mandanten überwachen. Fehlende Messreihen und einen
  ausgefallenen Abruf separat als Ausfall behandeln, nicht als Wert null; eine Schwelle und Wartezeit festlegen.
- [ ] Restore-Probe bei fehlgeschlagenem Lauf **und** bei einem letzten grünen Lauf älter als 35 Tage alarmieren
  (Req 16.25). Die Ergebnisse kommen aus dem Status-Artefakt der Probe, nicht aus den App-Kennzahlen; auch ein
  fehlendes oder veraltetes Artefakt darf nicht als Erfolg gelten.
- [ ] Zeilenzahl von `ki_beispiel` je Mandant erfassen. Ab 1'000 Zeilen die Dauer von
  `JdbcKiBeispiel.kandidaten` am betreffenden Bestand messen; ab 100 ms je Abfrage oder 10'000 Zeilen je Mandant
  den Rang bzw. Index prüfen (Spec 01, Design «Offen»). Die App stellt dazu `archiv_ki_beispiele{mandant}` und ab
  1'000 Zeilen `archiv_ki_beispiel_kandidaten_seconds_*{mandant}` bereit; Schwellenprüfung, Alarm und Anbindung
  bleiben offen.
- [ ] Regeln und Zuständigkeit im zuständigen Infra-Repository dokumentieren; Tests, Zeitpunkt und Ergebnis
  festhalten. Aufgabe 5.9 erst nach Anbindung und Betriebsnachweisen abschliessen.

## Grundregeln

- **Nur IDs und Status.** Betreff und Text nennen Dokument-ID, Datum, Sicherungscode oder Speicherbereich, nie
  Dateinamen, Metadaten oder Inhalte (Req 16.7). Die Speicher-Mail nennt zusätzlich die Auslastung in Prozent und MiB.
- **Einmal je Auslöser und Stufe.** Ein Befund wird in der Tabelle `alarm` festgehalten; je Schlüssel
  (Quelle, Gegenstand, Status) geht genau eine Mail, auch wenn der Befund viele Läufe lang bestehen bleibt. Ist er
  behoben, wird der Alarm erledigt (ohne Mail). Wechselt ein Dokument den Status und steht wieder zu lange, ist das ein
  neuer Befund und damit eine neue Mail. Scheitert der Versand, versucht es der nächste Lauf wieder.
- **Verwaltungsalarme nach einem Abbruch.** Ihre Versandansprüche laufen nach zehn Minuten ab. Ist die Mail bereits
  versandt, aber ihre Bestätigung noch nicht gespeichert, kann sie beim Wiederanlauf nochmals verschickt werden;
  andernfalls würde ein Absturz den Alarm verlieren. Ein verschwundener Abgleichbefund bleibt bis zum Versand offen.
  Je Lauf bearbeitet die Überwachung höchstens 200 ausstehende Verwaltungsalarme pro Mandant; weitere folgen im nächsten
  Takt.
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
| `Alarm der Benutzerverwaltung` | Jede wirksame Inhabervergabe, 10 Verwaltungsaktionen oder 5 Verwaltungsverweigerungen innerhalb von 10 Minuten, endgültiges HTTP 401 oder 403 des Keycloak-Dienstkontos | Schwellen je Mandant; ein Alarm beim Erreichen der jeweiligen Schwelle. Eine 401-Antwort, die nach Erneuerung des Tokens erfolgreich ist, löst keinen Alarm aus | Aktion, Anzahl, Zeit und Ereignis-ID, ohne Benutzernamen oder Eingaben | Im Verwaltungsprotokoll nach dem Vorgang suchen; bei einer Dienstkonto-Ablehnung dessen Einrichtung und das Secret `KEYCLOAK_VERWALTUNG_SECRET` prüfen ([Rotation](geheimnisse-rotieren.md)); Notweg bei Ausfall: [Anleitung, Notweg](anleitung-verwaltung.md#notweg-benutzer-per-kcadmsh) |
| `Abweichung in der Benutzerverwaltung` | Der Abgleich findet einen offenen Vorgang oder eine Abweichung zwischen Keycloak und Verwaltungsstand | Beim Worker-Start und täglich um 01:20 Uhr Europe/Zurich; Meldung im nächsten Überwachungstakt | `BENUTZER_ABWEICHUNG`, Anzahl, Zeit, Kennung und Abweichungscode | Verwaltungsprotokoll und Keycloak prüfen; unklare Vorgänge durch den Betreiber klären; `archiv benutzer abgleichen <code> --pruefen` zeigt die Abweichungen, ohne etwas zu ändern ([Anleitung](anleitung-verwaltung.md#normaler-weg-admin-kommando)) |
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
| `archiv_ki_beispiele` | Gauge | `mandant` | Zeilen in `ki_beispiel` je aktivem Mandanten, erhoben im Takt von `archiv.kennzahlen.intervall` |
| `archiv_ki_beispiel_kandidaten_seconds_*` | Timer | `mandant` | Dauer von `JdbcKiBeispiel.kandidaten`; wird nur erfasst, wenn der zuletzt erhobene Bestand mindestens 1'000 Zeilen hat |
| `archiv_webseite_grenze_ausgefallen_total` | Zähler | – | Prüfung einer Grenze je Webseiten-Schlüssel ist fehlgeschlagen; die Anfrage wurde wie vorgesehen durchgelassen |
| `archiv_oeffentlicher_chat_nutzung_schreibfehler_total` | Zähler | – | Tokenverbrauch oder `ki_verbrauch` des öffentlichen Chats konnte nicht geschrieben werden; die Antwort wurde trotzdem ausgegeben |

Die Kennzahlen des Speichers sind je JVM (API und Worker getrennt), die Zähler der KI-Felder gehören dem Prozess, der
den Vorschlag bestätigt.

### Alarmvorschlag: Fehler der Webseiten-Grenzen

Vorschlag für das externe Monitoring: Alarm, wenn `increase(archiv_webseite_grenze_ausgefallen_total[5m]) > 0`.
Der Zähler meldet einen internen Fehler der Pro-Schlüssel-Prüfung; diese lässt Anfragen bewusst durch, damit ein
Limiterfehler die Webseite nicht abschaltet. Bei einem Alarm den Fehlergrund im Log prüfen und die Prüfung beheben.
Das Archiv versendet dafür keine Alarm-Mail.

### Alarmvorschlag: Nutzung des öffentlichen Chats nicht geschrieben

Vorschlag für das externe Monitoring: Alarm, wenn
`increase(archiv_oeffentlicher_chat_nutzung_schreibfehler_total[5m]) > 0`. Die Antwort bleibt bei einem Schreibfehler
fail-open, da sie bereits gestreamt sein kann; in diesem Fall ist die monatliche Budgetobergrenze nicht nachgewiesen.
Bei einem Alarm den Fehlergrund im Log prüfen und die Nutzungsbuchung wiederherstellen.

## Abweichungen zwischen Spec und Code

Stand dieser Seite gegen `design.md` «Überwachung» (Spec 01) und Spec 02:

- **Manifest:** `design.md` nennt «fehlende oder ungültige Manifeste». Die Überwachung meldet nur `FEHLT`; ob ein
  vorhandenes Manifest gültig ist, prüfen Prüfwerkzeug und Prüflauf (Mail «Prüflauf … Abweichungen»).
- **Sicherung:** `design.md` nennt «das Alter des letzten Sicherungs-Exports». Der Code prüft jede Sicherungsart einzeln
  und ist ab Werk ausgeschaltet (`archiv.ueberwachung.sicherung-aktiv=false`).
- **Export:** Ein gescheiterter Jahresexport löst keine Mail aus (Spec 02: «ohne Mail»); er ist nur über Status, Fehlerart
  und Log sichtbar.
- **Speicher-Mail:** Req 16.7 schliesst Dokumentinhalte aus; die Speicher-Mail nennt Bereich, Prozent und MiB. Es sind
  Betriebswerte ohne Bezug zu Dokumenten oder Personen, also keine Verletzung (Req 16.5 nennt Stufen und Bereich selbst).
