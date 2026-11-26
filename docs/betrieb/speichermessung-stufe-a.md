# Messvorbereitung Stufe A: Speicher

Dieses Werkzeug ist keine Messung und kein Abnahmenachweis. Ausführung,
Beurteilung und Nachweis nach Aufgabe 1.10 und Req 16.18 bleiben bei Fabian.
Nur synthetische Belege im isolierten Messumfeld verwenden; keine produktiven
Daten, Zugangsdaten, Adressen oder Berichte in das Repository schreiben.
Fabian hat am 27.11.2026 eine erneute Messung nach dem früheren Abbruch
freigegeben; er führt sie selbst aus. Die Werkzeuge starten, stoppen oder
verändern keine Archiv-Dienste.

## Budget und Abbruch

Fabian bestätigte am 27.11.2026 das Budget nach der Erweiterung vom 23.11.2026:
Summe der `mem_limit` höchstens 2048 MiB und der `cpus` höchstens 1.5. Das
Werkzeug prüft diese Grenzen. Die älteren Angaben von 1000 MiB und 0.75 CPU in
Aufgabe 1.10 und Req 16.18 sind diesbezüglich überholt; der Nachzug des
Requirement-Texts erfolgt separat. Bei Überschreitung nicht mit weiterer Last
beginnen.

Nach Req 16.18 ist auch ein speicherbedingter Abbruch eines Containers auf dem
Host oder der Health-Ausfall einer anderen App ein Abbruchgrund. Ein durch
manuellen Eingriff verursachter Speicherabbruch zählt nicht, wird aber mit Datum
im Messbericht festgehalten. Bei einem Abbruch Archiv-Dienste auf Stufe A
stoppen, Fabian informieren und K5 sowie den Wechsel auf Stufe B vorziehen.
Das Werkzeug führt diese Schritte nicht selbst aus.

## Vorbereitung

- Messfreigabe, Ausgangswert des verfügbaren Hostspeichers und Health der
  anderen Apps vorab prüfen. Deren Health während und nach jeder Phase
  unabhängig beobachten; im Bericht nur Zahlen, keine Namen anderer Apps.
- Keine Admin-Kommandos gleichzeitig ausführen: Ein Wegwerfcontainer dafür
  belegt ein zweites `mem_limit`; `docker exec` einer zweiten JVM im laufenden
  Container ist nicht zulässig. Keine andere Last oder Messung parallel starten.
- Stichprobe in einem vertrauenswürdigen Wegwerfcontainer mit Python 3 und
  Docker-CLI ausführen; der Docker-Socket muss auf den Ziel-Daemon zeigen.
  Socket-Zugriff erlaubt volle Kontrolle über den Host: keinen fremden oder
  unkontrollierten Container dafür verwenden. Nur die drei Archiv-Container
  anhand ihrer IDs auswählen, Reihenfolge API, Keycloak, Datenbank. Die IDs
  erscheinen nicht in der CSV. `docker inspect` prüft die Compose-Labels
  für Projekt und Dienst und liest Speicher- und CPU-Limits;
  `docker stats --no-stream` liest den aktuellen Speicherverbrauch.
- Ein neues geschütztes Ausgabeverzeichnis ausserhalb des Repositorys nutzen
  (Rechte 700); jede CSV wird neu mit Rechten 600 angelegt und nie
  überschrieben. Bei Fehler oder unvollständiger Messung nicht als Nachweis
  verwenden. Die Skripte und der Ausgabepfad müssen im Wegwerfcontainer
  verfügbar sein. Fehlermeldungen nennen Aufruf und Fehlerart beziehungsweise
  `errno`, geben aber keine Docker-Fehlertexte, IDs, Pfade oder CSV-Werte aus.

## Ablauf

In jedem Aufruf die drei **Container-IDs** und einen neuen Ausgabepfad
einsetzen. Intervall und Anzahl sind Beispiele; sie müssen ausreichend viele
Stichproben während der jeweiligen Last ergeben. Die Ausgabe enthält nur
Zeitstempel, Phasen, Dienstnummern und Zahlen, keine IDs oder Inhalte.

```sh
python3 tools/last/speichermessung_stufe_a.py leerlauf /ausserhalb/leerlauf.csv \
  --anzahl 10 --intervall 2 --container <api-id> <kc-id> <db-id>
# Vor weiterer Last Budget und beobachtete Abbruchgründe prüfen:
python3 tools/last/speicherauswertung_stufe_a.py /ausserhalb/leerlauf.csv \
  --vorpruefung --oom unbekannt --health unbekannt
# Während des synthetischen Uploads:
python3 tools/last/speichermessung_stufe_a.py upload /ausserhalb/upload.csv \
  --anzahl 10 --intervall 2 --container <api-id> <kc-id> <db-id>
# Während der OCR eines mehrseitigen synthetischen Belegs:
python3 tools/last/speichermessung_stufe_a.py ocr /ausserhalb/ocr.csv \
  --anzahl 10 --intervall 2 --container <api-id> <kc-id> <db-id>
```

Leerlauf, Upload und OCR jeweils tatsächlich während der Stichproben
beobachten. `docker stats` ist gerundet und zeitlich diskret: kurze Spitzen
können fehlen. Die Summe der je Dienst beobachteten Spitzen ist eine
konservative Summe, keine gleichzeitig gemessene Hostlast. Zusätzlich den
verfügbaren Hostspeicher und den Vergleich zum Ausgangswert unabhängig messen.
Vor Upload und OCR jeweils Limits, OOM und Health prüfen; bei Abbruch oder
ungeklärter Prüfung keine weitere Last auslösen. Die Vorprüfung kann dazu mit
der neuesten einzelnen CSV wiederholt werden. Die Platzhalter `unbekannt` sind
nach tatsächlicher Prüfung durch die zutreffenden Angaben zu ersetzen.

Nach Prüfung auf OOM und Health die passenden Werte ausdrücklich einsetzen:

```sh
python3 tools/last/speicherauswertung_stufe_a.py \
  /ausserhalb/leerlauf.csv /ausserhalb/upload.csv /ausserhalb/ocr.csv \
  --oom unbekannt --health unbekannt
```

`--oom`: `nein`, `ja`, `manuell` oder `unbekannt`; `--health`: `ok`,
`fehler` oder `unbekannt`. `manuell` nur wählen, wenn alle beobachteten
OOM-Ausfälle nachweislich manuell verursacht wurden; sonst bei einem
nicht manuell verursachten OOM `ja` wählen. Unbekannte Angaben ergeben keinen
Entwarnungsnachweis. Exit `0`:
kein Abbruchkriterium festgestellt; `1`: Abbruchkriterium erfüllt; `2`:
ungeklärt oder fehlerhafte/unvollständige Eingabe. Das Programm erkennt OOM
und Health-Ausfälle **nicht selbst**. Fehlende Limits, geänderte Limits oder
unvollständige Stichproben werden abgelehnt.

Tabelle und Zahlen nach Prüfung in `docs/spikes/stufe-a-speicher.md` übertragen:
Dienstnummern 1, 2 und 3 entsprechen API, Keycloak und Datenbank. Der Bericht
enthält keine Namen anderer Apps. Fabian bestätigt dort die verwendeten
Testdaten; Rohdaten geschützt ausserhalb des Repositorys aufbewahren.

Synthetische Tests: `python3 -m unittest tools/last/test_speichermessung_stufe_a.py`.
