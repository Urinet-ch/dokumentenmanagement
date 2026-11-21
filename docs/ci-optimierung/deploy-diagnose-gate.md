# Vorschlag: `diagnose archiv` im Deploy-Gate

Der Workflow-Diff aus #432 ist noch nicht angewendet. Dieser Vorschlag ändert
weder das Infra-Repo noch einen Workflow. Claude setzt ihn im Infra-Repo um und
prüft ihn dort vor der Anwendung des Workflow-Diffs.

## Vertrag

Der bestehende appgebundene Forced Command `bin/deploy-app.sh` erhält den
Unterbefehl `diagnose archiv`. Die vorhandene Prüfung der Schlüsselbindung und
der Argumentzahl gilt unverändert; zusätzlich ist `diagnose` ausschliesslich
für die gebundene App `archiv` zulässig. Keine weiteren Argumente, kein
Shell-Ausdruck und keine vom Aufrufer gewählten Container oder Filter.

Bei Erfolg gibt der Befehl ausschliesslich diese Zeilen aus, in dieser
Reihenfolge und mit tatsächlich ermittelten Werten:

```text
api OOMKilled=false ExitCode=0 RestartCount=0
db OOMKilled=false ExitCode=0 RestartCount=0
kc OOMKilled=false ExitCode=0 RestartCount=0
fehlercode INTERNER_FEHLER 2
```

Die drei Dienstnamen sind feste Literale; `OOMKilled` ist `true` oder `false`,
die übrigen Werte sind nichtnegative Dezimalzahlen. Fehlercode-Zeilen stehen
lexikografisch nach Code sortiert und nur für gezählte Codes mit Anzahl
grösser null. Keine Container-IDs, Zeitstempel, Meldungen, Datenbankwerte oder
anderen Felder ausgeben. Keine Codes bedeutet keine Fehlercode-Zeilen; das ist
nicht die Aussage, dass keine anderen Fehler aufgetreten sind.

Bei einem Fehler gibt der Befehl auf stderr nur `Diagnose nicht verfügbar` aus
und beendet sich ungleich null. Er gibt dann keine Teilergebnisse und keine
ursprüngliche Fehlermeldung aus. Der Workflow behandelt dies als Warnung, nicht
als erfolgreichen Diagnosenachweis.

## Ermittlung

1. Wie bei Deploy und Rollback die bestehende App-Sperre verwenden, damit die
   Container während der Ermittlung nicht durch einen gleichzeitigen Deploy
   ersetzt werden. Die gesamte Diagnose hat eine Frist von 30 Sekunden; auch eine
   fehlgeschlagene Sperre liefert nur die generische Fehlermeldung.
2. Für jeden festen Dienst `api`, `db`, `kc` Container über
   `docker ps -aq --filter label=com.docker.compose.project=archiv --filter
   label=com.docker.compose.service=<dienst>` ermitteln. Die Dienstnamen stammen
   aus der bestehenden Compose-Datei. Je Dienst muss genau eine gültige
   Container-ID gefunden werden; fehlt ein Container oder gibt es mehrere,
   schlägt die Diagnose fehl. Auch gestoppte Container berücksichtigen.
3. Für jede ermittelte ID `docker inspect` aufrufen und ausschliesslich
   `.State.OOMKilled`, `.State.ExitCode` und `.RestartCount` übernehmen. Typ und
   Wertebereich vor der Ausgabe prüfen. Fehlende Felder oder ein fehlgeschlagenes
   `inspect` sind ein Fehler, keine Nullwerte.
4. Nur für den API-Container `docker logs --since 10m --tail 2000 <id>` lesen.
   Beide Ausgabeströme bleiben innerhalb des Diagnoseprozesses; weder Rohzeilen
   noch Docker-stderr gelangen auf stdout oder stderr. Zeit und eingelesene
   Bytes sind zusammen auf 4 MiB zu begrenzen. Ein fehlgeschlagener, abgebrochener
   oder wegen der Byte-Grenze abgeschnittener Abruf ist ein Fehler, keine
   Nullzählung.
5. Die API schreibt in `prod` JSON-Logeinträge. Nur ein JSON-Objekt mit
   Zeichenkettenfeld `message` auswerten, darin ein einzelnes vollständiges
   `fehlercode=<CODE>` erkennen und zählen: Das Token muss vor und nach dem
   Wert durch den Textanfang bzw. das Textende oder ein Zeichen ausserhalb von
   `A–Z`, `a–z`, `0–9` und `_` begrenzt sein. Ein Code wird nur ausgegeben, wenn
   er in einer fest gepflegten Positivliste steht: Enum-Namen aus `Fehlercode`
   des ausgerollten Archiv-Stands sowie ausdrücklich aufgeführte weitere feste
   Betriebs-Fehlercodes. Die Liste wird zusammen mit der Gate-Implementierung
   versioniert; unbekannte Werte werden nicht ausgegeben. Ein blosses
   Grossbuchstabenmuster genügt nicht, da ein Logtext fremde Daten enthalten
   kann. Ungültige JSON-Zeilen und Zeilen ohne zulässigen Code werden nicht
   ausgegeben oder gezählt. Mehrere Code-Tokens in einer Meldung werden nicht
   gezählt, statt deren Herkunft zu raten.
6. Sämtliche Ergebnisse zuerst intern sammeln und prüfen, erst danach die
   erlaubten Zeilen ausgeben. Kein Fallback auf `status`: Dieser bestehende
   Unterbefehl gibt Rohlogs aus.

Die Zählung umfasst höchstens die letzten 2'000 API-Logzeilen der letzten zehn
Minuten. Sie ist keine vollständige Fehlerstatistik und enthält keine Fehler
aus DB- oder Keycloak-Logs. Die Positivliste muss bei neuen Fehlercodes
nachgezogen werden; ein Versionsabgleich im Infra-Test soll fehlende und
entfernte Codes anzeigen.

## Prüfung im Infra-Repo

Den vorhandenen isolierten Test des Deploy-Gates erweitern: korrekte Werte für
laufende und gestoppte Container, OOM und Neustarts, Zählung bekannter Codes
und deterministische Sortierung; fehlender und mehrfach vorhandener Dienst,
fehlgeschlagenes `inspect`, unlesbare Logs, Timeout und Byte-Grenze liefern
Fehler statt Erfolg. Fremde App-Bindung, zusätzliche Argumente und ein
beliebiger Shell-Ausdruck werden abgelehnt. Synthetische Logzeilen und
Fehlermeldungen mit Kennwörtern, personenbezogenen Angaben, unbekannten Codes,
mehreren Code-Tokens und ungültigem JSON dürfen an keiner Stelle in der Ausgabe
auftauchen. Ausserdem die erlaubten Ausgabezeilen vollständig gegen den Vertrag
prüfen.
