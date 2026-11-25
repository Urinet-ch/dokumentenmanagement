# Kalibrierung der Webseiten-Grenzen

Aufgabe 11.11 setzt Startwerte für Limits je Webseiten-Schlüssel. Die Werte in
`backend/src/main/resources/application.properties` sind Startwerte, keine
Lastgarantie. Die Messung erfolgt erst beim Livegang durch Fabian; dieses
Dokument und das Auswertungsskript enthalten keine Produktivmessungen.

## Umfang und aktuelle Startwerte

| Grenze | Startwert | Bucket-Spitze |
| --- | ---: | ---: |
| Lesen je Lese-Schlüssel | 20/s | 100 |
| Suche je Lese-Schlüssel | 2/s | 10 |
| Erfassung je Erfassungs-Schlüssel | 5/s | 20 |
| Uploads je Erfassungs-Schlüssel | 120/h | 120 |
| Prüfdatei je Erfassungs-Schlüssel | 1/s | 2 |

Die Suche beansprucht zusätzlich den Lese-Bucket; die Prüfdatei zusätzlich den
Erfassungs-Bucket. Chat-Limits je Client-Hash, Monatsbudget, Stream-Grenzen und
der Schlüssel-Prüf-Cache sind eigene Mechanismen und werden mit diesem Skript
nicht kalibriert.

## Messung am Livegang

1. Erfasse die tatsächlich wirksamen Rate- und Spitzenwerte je Grenze. Nicht
   annehmen, dass die Repository-Defaults im Betrieb unverändert gelten.
2. Beobachte pro Schlüssel getrennte Zeitfenster für Dauerlast und Stösse.
   Verwende für `dauer` repräsentative Zeitfenster; bei `uploads` muss das
   Fenster die Stundenrate abbilden. Für `spitze` notiere die Zahl der
   Anfragen im einzelnen Stoss.
3. Notiere je Zeitfenster den Alias, die Grenze, die Fensterdauer, die
   Gesamtanfragen und die HTTP-429-Antworten. Nutze ausschliesslich erfundene
   Aliase wie `K01`; weder Schlüsselwerte noch Schlüssel-IDs gehören in die
   CSV, Konsole, Doku oder PR.
4. Die Kennzahl `archiv_webseite_grenze_abgelehnt_total` ist nur nach
   `grund` (`ANFRAGEN`, `SUCHE`, `UPLOADS`) aufgeteilt und enthält absichtlich
   keine Schlüssel-ID. Sie belegt daher keine Messung je Schlüssel. Ordne
   Ablehnungen nur dann einem Alias zu, wenn ein Beobachtungsfenster tatsächlich
   genau diesem Schlüssel zugeordnet werden kann. Die Prüfdatei-Ablehnung
   erscheint als `ANFRAGEN`; unterscheide sie von anderen Anfragen anhand des
   Endpunkts, nicht anhand eines neuen Metrik-Tags.
5. Speichere Rohdaten und eine mögliche Zuordnung von Alias zu Schlüssel nur
   ausserhalb des Repositories. Verwende keine zusammengeführten Zeitfenster,
   um daraus Aussagen je Schlüssel abzuleiten.

CSV-Spalten:

```text
alias,grenze,modus,fenster_sekunden,anfragen,abgelehnt_429,startwert,spitze
K01,lesen,dauer,60,480,0,20,100
K01,lesen,spitze,1,90,0,20,100
K01,suche,dauer,60,48,0,2,10
K01,uploads,dauer,3600,72,0,120,120
```

`grenze` ist `lesen`, `suche`, `erfassung`, `uploads` oder `datei`;
`modus` ist `dauer` oder `spitze`. `startwert` und `spitze` sind die
tatsächlich wirksamen Konfigurationswerte. Bei `uploads` werden die
Dauerlast-Raten pro Stunde, bei den anderen Grenzen pro Sekunde ausgewertet.
Die Beispieldaten sind synthetisch und keine Empfehlung für Produktivwerte.

Auswertung:

```sh
python3 tools/webseiten_grenzen_auswertung.py messungen.csv
```

Das Skript gibt TSV mit Stichprobenzahl, Anfragen, Nicht-429-Antworten, 429-Anzahl und
-Anteil, P95 der angebotenen Dauerlast beziehungsweise grösstem beobachteten
Stoss sowie der Auslastung relativ zum mitgelieferten Startwert oder zur Spitze
aus. Nicht-429-Antworten gelten nicht automatisch als erfolgreiche Antworten.
Das Skript ändert keine Konfiguration und trifft keinen Entscheid. P95 aus wenigen
Stichproben ist nur eine Beschreibung dieser Stichprobe, keine belastbare
Lastgarantie.

429 sind ein erwünschter Schutz bei Überlast oder missbräuchlichen Anfragen.
Eine Erhöhung ist nur zu prüfen, wenn Ablehnungen bei erwarteter legitimer Last
auftreten und die Ressourcen-/Kostenfolgen geklärt sind. Eine Messung ohne 429
beweist umgekehrt nicht, dass die Grenze unter künftiger Last genügt. Die
Entscheidung und allfällige Änderung der Startwerte bleiben bei Fabian.

Synthetische Prüfung des Auswertungsskripts:

```sh
python3 -m unittest tools/test_webseiten_grenzen_auswertung.py
```
