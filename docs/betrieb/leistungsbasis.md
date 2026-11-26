# Lokale Leistungsbasis

Diese Messung dient dem lokalen Vergleich, nicht der Abnahme. Sie startet eine eigene Wegwerf-Datenbank im
Docker-Container, bindet sie nur an `127.0.0.1` und verwendet den Quarkus-Testmodus mit synthetischem Mandanten,
synthetischen Dokumenten, Fake-Keycloak und Chat-Attrappe. Sie greift nicht auf VPS, Staging, echte Belege oder ein
Sprachmodell zu. Der Object Storage ist ebenfalls eine Attrappe; die Laufzeiten enthalten keine echten
Keycloak-, S3- oder Modellaufrufe.

Voraussetzungen: Docker, Java 25, Maven, Python 3 und das bereits lokal vorhandene Image
`pgvector/pgvector:0.8.7-pg17-trixie`. Im eigenen Worktree ausführen:

```sh
bash tools/last/wissenssuche.sh --freigabe --leistungsbasis
```

Der Lauf legt 5'000 synthetische Dokumente an, davon 250 im Eingangskorb. Er misst je 20 gleichzeitig gestartete
HTTP-Anfragen für Volltextsuche, Eingangskorb, Dokumentmetadaten und Chat. Für den Chat zählen Zeit bis zum ersten
vollständig empfangenen Text-Ereignis und ein vollständig abgeschlossener SSE-Strom; das Sprachmodell ist eine
Attrappe. Container und dessen eigenes Volume werden nach dem Lauf entfernt. Andere Container oder Volumes werden
nicht gelöscht. Falls die Bereinigung scheitert, nennt das Skript die betroffenen IDs.

Das Skript schreibt `backend/target/leistungsbasis-roh.csv` mit Einzelproben sowie
`backend/target/leistungsbasis.csv` mit Anzahl, Fehlerzahl, Fehlerquote, p50/p95 und Vergleichsziel in Millisekunden. Die Ausgabe
auf der Konsole enthält dieselbe Zusammenfassung. Die Perzentile verwenden Nearest Rank und nur erfolgreiche
Anfragen; bei ausschliesslich fehlgeschlagenen Anfragen bleiben sie leer. Auch die Fehlerquote muss beurteilt werden:
Ein schneller Fehler ist keine schnelle erfolgreiche Antwort. HTTP-Fehler und fehlende SSE-Abschlüsse zählen als
Fehler. Bei einem fehlgeschlagenen Messlauf ist eine ältere Zusammenfassungsdatei kein Ergebnis des neuen Laufs.
Die Such- und Eingangskorbantworten müssen Treffer enthalten, die Dokumentantwort die erwartete ID.
Die 20 Testbenutzer haben je eigene Einträge im Eingangskorb. Die standardmässigen Gleichzeitigkeitsgrenzen
des Chats bleiben aktiv; HTTP 429 wird als Fehler gezählt.

## Einordnung der Anforderungen

| Messung | Vergleichsziel | Einschränkung |
| --- | --- | --- |
| Volltextsuche | Spec 01, 14.3: p95 ≤ 1 s | Gefordert sind 50'000 Dokumente, andere Datenverteilung und Last sowie Stufe B; hier nur 5'000, 20 parallele Anfragen und Clientzeit. |
| Chat, erster Text | Spec 02, 3.8: p95 ≤ 10 s | Messpunkt dort Server; hier Clientzeit und Attrappe statt Sprachmodell. |
| Eingangskorb, Dokumentmetadaten | Kein entsprechender p95-Grenzwert in diesen Anforderungen | Lokale Vergleichswerte, kein Bestehen oder Verfehlen eines Gates. |

Die Hybridsuche (Spec 02, 2.6: p95 ≤ 2 s) wird in diesem Lauf **nicht** gemessen. Keine der Zahlen ersetzt die
Abnahmemessungen nach den jeweiligen Anforderungen.
