# Abnahme Phase 4: Wissensbasis

Vorlage für Aufgabe 16.2 und Requirements 18.1–18.7. Der Gate-Lauf erfolgt
ausschliesslich durch Fabian auf Stufe B, nie in der CI. Diese Vorlage und der
automatisierte Prüfteil sind kein Abnahmenachweis.

## Vorbereitung und Ablauf

1. Fünf unterschiedliche Wissensquellen auswählen. Vor jeder Bearbeitung
   zufällig 50 Fussnoten aus den vorgeschlagenen Änderungen ziehen; den
   Vergleich mit der angegebenen PDF-Seite von Hand durchführen.
2. 15 Fragen mit erwarteter Antwort, Quelldokument und PDF-Seite vorbereiten.
   Die Datei `wissen-pruefliste.yaml` und der vollständige Bericht bleiben
   ausschliesslich unter `ARCHIV_GOLDEN_DIR` ausserhalb des Repositorys.
   Das Schema liegt unter
   [`backend/src/test/resources/abnahme/wissen-pruefliste.schema.json`](../../backend/src/test/resources/abnahme/wissen-pruefliste.schema.json).
   `quellen` enthält fünf nummerierte Dokument-UUIDs; `fussnoten` enthält
   50 nummerierte Einträge mit Quellennummer, PDF-Seite und Fundstelle;
   `fragen` enthält 15 nummerierte Fragen mit erwarteter Antwort,
   Quellennummer und PDF-Seite. Nummern beginnen je Liste bei 1.
3. Die Basisadresse der Stufe B und den Pfad zur Token-Datei als
   `ARCHIV_WISSEN_PRUEFLISTE_URL` und `ARCHIV_WISSEN_PRUEFLISTE_TOKEN_DATEI`
   setzen. Die Token-Datei muss Rechte 600 haben; das Token selbst gehört
   nicht in eine Umgebungsvariable. `ARCHIV_GOLDEN_DIR` muss auf das
   bestehende externe Prüfverzeichnis zeigen.
4. Im Verzeichnis `backend/` den manuellen Lauf starten:

   ```sh
   mvn -ntp test -Dtest=WissenPrueflisteIT -Darchiv.wissen-pruefliste=true \
     -Dquarkus.http.test-port=0
   ```

   Für jede Frage wird ein neues Gespräch im Modus `WISSEN` angelegt.
   Der vollständige Bericht wird neu und mit Rechten 600 im Prüfverzeichnis
   angelegt. Bei einem Fehler bricht der Lauf ab; er ist kein Ergebnis.
5. Fabian beurteilt jede Antwort und ihre Quellen von Hand. Auch die
   Fussnoten, entfernte Aussagen und der Anteil unverändert angenommener
   Änderungen sind manuell zu prüfen; der Lauf bewertet sie nicht.
   Nur Nummern, Ergebnisse und Zahlen in diese Vorlage übernehmen, nie
   Fragen, Antworten, Fussnoten, Titel, Dokument-IDs oder andere Inhalte.

## Fussnoten

Ergebnis je Nummer: `belegt`, `nicht belegt` oder `offen`.

| Nr. | Ergebnis | Nr. | Ergebnis | Nr. | Ergebnis | Nr. | Ergebnis | Nr. | Ergebnis |
| ---: | --- | ---: | --- | ---: | --- | ---: | --- | ---: | --- |
| 1 | | 11 | | 21 | | 31 | | 41 | |
| 2 | | 12 | | 22 | | 32 | | 42 | |
| 3 | | 13 | | 23 | | 33 | | 43 | |
| 4 | | 14 | | 24 | | 34 | | 44 | |
| 5 | | 15 | | 25 | | 35 | | 45 | |
| 6 | | 16 | | 26 | | 36 | | 46 | |
| 7 | | 17 | | 27 | | 37 | | 47 | |
| 8 | | 18 | | 28 | | 38 | | 48 | |
| 9 | | 19 | | 29 | | 39 | | 49 | |
| 10 | | 20 | | 30 | | 40 | | 50 | |

## Fragen

Ergebnis je Nummer: `richtig mit Seite und Quelldokument`, `nicht richtig`
oder `offen`. Alle drei Aspekte bestätigt Fabian.

| Nr. | Ergebnis | Nr. | Ergebnis | Nr. | Ergebnis |
| ---: | --- | ---: | --- | ---: | --- |
| 1 | | 6 | | 11 | |
| 2 | | 7 | | 12 | |
| 3 | | 8 | | 13 | |
| 4 | | 9 | | 14 | |
| 5 | | 10 | | 15 | |

## Gesamt

| Prüfung | Soll | Ergebnis |
| --- | --- | --- |
| Wissensquellen | 5 | |
| Fussnoten belegt | mindestens 47 von 50 | |
| Fragen richtig mit Seite und Quelldokument | mindestens 14 von 15 | |
| Belegte Aussagen ohne Widerspruchsblock oder Begründung entfernt | 0 | |
| Isolationstest nach 18.5 | bestanden | |
| Unverändert angenommene Änderungen | Anteil = Anzahl unverändert / Anzahl angenommen | |
| Gate Phase 4, manuelle Bestätigung | bestanden | |
