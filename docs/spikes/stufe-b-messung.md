# Stufe B: Messbericht (Aufgabe 5.11)

Vorlage – noch keine Messung und kein Nachweis. Die Vorprobe auf einer
Public-Cloud-Instanz ersetzt weder die Messung auf der neuen VPS noch die
Restore-Probe vor dem Umstellen (Req 16.3, 16.26).

## Rahmen

- Art der Messung (Vorprobe / VPS): _
- Datum und Dauer: _
- Commit: _
- Instanzgrösse (vCPU, RAM, Platte): _
- Gemessenes `MemTotal` und Swap: _
- Mess-Mandant und Bucket: _
- Nur synthetische Daten und kein produktiver Mandant: _
- `archiv.stufe` und Limits der Dienste: _
- Abweichungen von den Messbedingungen und ihre Auswirkung: _

Für den Nachweis auf der VPS laufen die Dienste und Limits der Stufe B mit
`archiv.stufe=A`, Mandant `last` und eigenem Bucket `urinet-archiv-a-last`.
Nach Messung und Restore-Probe werden die Messdaten verworfen; Stufe B startet
leer. Die Buckets werden frühestens einen Tag nach dem letzten Upload ohne
Bypass-Header aufgeräumt.

## Leistung

Messpunkt ist der Server. Zeiten der Lastskripte enthalten Netz- bzw.
Abfragezeit und sind als obere Schranken gesondert auszuweisen.
Je Zeile: p50 / p95 / Maximum in Sekunden, Zahl erfolgreicher Läufe und Fehler.

### Upload (Req 3.2)

Ziel: Antwort HTTP 202 innerhalb von 2 s ab vollständig empfangener Anfrage;
Worker verarbeitet parallel. Je Variante 20 Aufwärm-Uploads.

| Variante | p50 / p95 / Maximum | Erfolgreich / Fehler | Ergebnis |
| --- | --- | --- | --- |
| 200 PDFs à 200'000 Bytes, parallel 4 | _ | _ | _ |
| 50 PDFs à 10'000'000 Bytes, parallel 2 | _ | _ | _ |

### Foto und Bildweg (Req 5.5)

Ziel: p95 höchstens 30 s vom Auftragsstart bis `EINGANGSKORB`, ohne
KI-Wartezeit. 50 Läufe nacheinander, 20 Aufwärmläufe; synthetisches
12-MP-Foto (4032 × 3024), zusätzlich HEIC und Läufe neben einem 20-seitigen
Scan.

| Variante | p50 / p95 / Maximum | Erfolgreich / Fehler | Ergebnis |
| --- | --- | --- | --- |
| JPEG, sonst leere Auftragsschlange | _ | _ | _ |
| HEIC | _ | _ | _ |
| Neben 20-seitigem Scan | _ | _ | _ |

- Messpunkt und Behandlung der KI-Wartezeit: _
- Dreiseitiges farbiges Foto-Dokument über den Bildweg: _
- `archiv.ki.render-zeitgrenze`: _
- Seitenbild erzeugt (ja/nein) und Renderdauer: _

### Suche (Req 14.3; Spec 02, Req 2.6)

50'000 synthetische Dokumente, im Mittel rund 1'500 Zeichen Volltext, 5 %
über 20'000 Zeichen, ein Drittel mit Finanzdaten; nach `VACUUM ANALYZE`.
Je Rolle und Bedingung 500 Suchen, parallel 4, mit 20 Aufwärmsuchen. Auch
Begriffe messen, die nur Dateinamen treffen.

| Modus / Rolle | OCR | p50 / p95 / Maximum | Erfolgreich / Fehler | Ergebnis |
| --- | --- | --- | --- | --- |
| Volltext / Inhaber | ohne | _ | _ | _ |
| Volltext / Inhaber | mit | _ | _ | _ |
| Volltext / Mitarbeiter | ohne | _ | _ | _ |
| Volltext / Mitarbeiter | mit | _ | _ | _ |
| Volltext / Treuhänder | ohne | _ | _ | _ |
| Volltext / Treuhänder | mit | _ | _ | _ |
| Hybrid / Inhaber | ohne | _ | _ | _ |
| Hybrid / Inhaber | mit | _ | _ | _ |
| Hybrid / Mitarbeiter | ohne | _ | _ | _ |
| Hybrid / Mitarbeiter | mit | _ | _ | _ |
| Hybrid / Treuhänder | ohne | _ | _ | _ |
| Hybrid / Treuhänder | mit | _ | _ | _ |

- Ziel Volltext: p95 höchstens 1 s; Hybrid: p95 höchstens 2 s.
- Bestand und Verteilung tatsächlich geprüft: _
- Dateinamen-Suche tatsächlich geprüft: _
- KI-, Embedding- und Reranker-Konfiguration und Aussagekraft: _

## Ressourcen und Restore-Probe

Startwerte: API 640 MiB / 1 CPU, Worker 1'024 MiB / 2 CPU,
Datenbank 768 MiB / 0.75 CPU, Keycloak 500 MiB / 0.5 CPU, Sicherung
256 MiB / 0.25 CPU. Summe höchstens 3'200 MiB und 4.5 CPU;
Reserve anhand des gemessenen `MemTotal` prüfen. Worker-Parallelität 1,
API-Pool 16, Worker-Pool 8, Datenbank `max_connections=50`.

| Dienst | Limit | Spitze Leerlauf | Spitze Upload | Spitze OCR |
| --- | --- | --- | --- | --- |
| API | _ | _ | _ | _ |
| Worker | _ | _ | _ | _ |
| PostgreSQL | _ | _ | _ | _ |
| Keycloak | _ | _ | _ | _ |
| Sicherung | _ | _ | _ | _ |

- Messintervall, Host-Speicher belegt/verfügbar und Tiefstwert verfügbar: _
- Verbrauch und Reserve für Betriebssystem, Docker, Traefik und Monitoring: _
- Speicherabbrüche, Neustarts und Health: _
- Restore-Probe neben den übrigen Diensten, Worker gestoppt; Limits und
  Spitze (Anteil höchstens 1'024 MiB): _
- Wiederherstellung, Anmeldung, Sperren und Prüflauf: _
- Beurteilung aller Ziele und Reserve; gegebenenfalls Aufrüstung auf
  4 vCPU / 8 GB / 160 GB und erneute Messung: _
- Freigabe und nächste Schritte [Fabian]: _
