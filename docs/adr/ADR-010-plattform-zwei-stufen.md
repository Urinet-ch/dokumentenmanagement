# ADR-010: Plattform auf Infomaniak-VPS in zwei Stufen, Deploy nur über GitHub Actions

**Status:** Angenommen – freigegeben per Merge an Gate 0 (Aufgabe 0.6, PR #23, Fabian, 09.11.2026)

**Datum:** 08.11.2026

## Kontext
- Daten, Datenbank und Speicher bleiben in der Schweiz, nur bei Infomaniak (Regel 6, Req 16.7).
  Zu betreiben sind API mit PWA, Worker mit OCR, PostgreSQL mit `pgvector`, Keycloak und eine
  tägliche Sicherung – reproduzierbar, ohne Handarbeit auf dem Server, mit Rücksprung bei Fehlern.
- Es gibt bereits eine kleine Infomaniak-VPS: Lite, 1 vCPU, 2 GB RAM ohne Swap, davon am
  08.11.2026 rund 1'140 MB verfügbar. Sie hat Traefik und ein Bundle-Deploy über GitHub Actions.
  Andere Apps teilen sich dort das Netz «web», Reserven gibt es keine.
- Die ursprüngliche Fassung von P1 sah eine eigene VPS ab Welle 1 vor, mit dem Grössenentscheid K5
  vor Welle 1. Fabian hat P1 am 08.11.2026 geändert: «Zuerst mit der vorhandenen VPS starten».
- Die Leistungsziele (Req 3.2, 5.5, 14.3) brauchen eine passend dimensionierte Maschine; echte
  Belege, Personal- und Lohndaten gehören nicht auf eine geteilte VPS ohne Reserven.

## Entscheid
1. **Gleiches Muster in beiden Stufen:** Docker Compose aus `deploy/infomaniak/compose.yml` hinter
   dem Traefik der VPS (Let's Encrypt, Docker-Labels, kein eigener Proxy, Client-IP aus
   `X-Real-Ip`). Compose-Policy der VPS: Limits, `cap_drop: ALL`, `no-new-privileges`, uid ≠ 0;
   Fremd-Images umgetaggt als `archiv-db:<version>` und `archiv-keycloak:<version>`. Secrets nur
   als Dateien unter `/run/secrets/` (Req 16.9). Object Storage in beiden Stufen unverändert im
   eigenen Public-Cloud-Projekt (Ablage: ADR-004, ADR-007).
2. **Netz:** Am Netz «web» hängen nur `archiv-api` und `archiv-keycloak`; Datenbank, Worker und
   Sicherung bleiben intern. Öffentlich nur `archiv.apps.urinet.ch` (ohne `/q/*` ausser
   `/q/health/live`) und `archiv-auth.apps.urinet.ch` (nur `/realms/`, `/resources/`) (P15).
3. **Deploy nur über `.github/workflows/deploy.yml`:** `mvn verify`, Images bauen, Bundle per SSH
   an den Deploy-Befehl der VPS, Health-Prüfung; schlägt sie fehl, Rollback auf das
   Vorgänger-Image ohne Rückbau der Datenbank (Migrationen rückwärtskompatibel, Req 16.11,
   ADR-002). Kein manuelles `docker compose up`.
4. **Stufe A** (ab Welle 1 bis zum Wechsel) auf der bestehenden VPS:
   - Nur Test- und anonymisierte Daten (Req 16.15). Geschwärzte echte Belege und der
     Golden-Datensatz gelten als echt und sind ausgeschlossen.
   - Budget: Summe `mem_limit` ≤ 700 MiB, `cpus` ≤ 0.75 (Richtwert, gemessen in 1.10; ab 10.11.2026 900 MiB, siehe Nachtrag unten).
   - API und Worker laufen in einem Container: Image `archiv-worker`, `archiv.rolle=api,worker`,
     Parallelität 1, OCR mit `--jobs 1`.
   - Leistungsziele gelten nicht (Req 16.16). Die Sicherung läuft nur als Funktionstest
     (Req 16.19, ADR-008).
   - Die Stufenwerte liegen versioniert unter `deploy/infomaniak/`.
5. **Stufe B** auf einer eigenen, grösseren Infomaniak-VPS nur für das Archiv:
   - Grösse (K5): VPS Lite 4 vCPU, 4 GB RAM, 80 GB, an Ort aufrüstbar auf 4/8/160 (Nachtrag 12.11.2026).
   - `archiv-api` und `archiv-worker` laufen getrennt. Hier gelten die Leistungsziele und laufen
     die Lasttests.
6. **Wechsel (Aufgabe 5.11, Req 16.17):**
   - Zeitpunkt: spätestens vor echten Belegen, also vor 5.4 und 5.8. Früher, falls 1.10 abbricht
     oder 16.18 eintritt.
   - Umfang: neue VPS nach dem Muster, Leistung auf der neuen VPS messen (vor dem Umstellen,
     Req 16.26), Deploy-Ziel umstellen (Host und Zugang nur als GitHub-Secrets), DNS beider
     Domains umstellen, Stufe B leer starten, Archiv-Dienste auf Stufe A entfernen (Reihenfolge
     nachgeführt 12.11.2026, bisher «… Archiv-Dienste auf Stufe A entfernen, Leistung messen»).
   - Test-Buckets: entschieden von Fabian am 09.11.2026, siehe Nachtrag.
7. **Keycloak bleibt (P5)**, weil der externe Treuhänder und Mandant 2 eigene Zugänge brauchen. Es
   läuft auf der VPS der jeweiligen Stufe und wird nach ADR-008 gesichert.

## Betrachtete Alternativen
- **Eigene VPS ab Welle 1:** Das war die bisherige Fassung von P1. Fabian hat sie am 08.11.2026
  zugunsten eines Starts auf der vorhandenen VPS geändert. K5 kann so mit mehr Wissen vor Welle 5
  fallen (Aufgabe 4.9).
- **Dauerhaft auf der bestehenden VPS:** Abgelehnt, weil das geteilte Netz und die fehlenden
  Reserven echte Daten und die Leistungsziele ausschliessen (Req 16.15, 16.16).
- **Plattform ausserhalb von Infomaniak:** Abgelehnt nach Regel 6. Speicher, Datenbank und KI
  liegen nur bei Infomaniak.
- **Manuelles Deploy auf dem Server:** Abgelehnt. Es ist nicht reproduzierbar, und Health-Prüfung
  und Rollback hängen am Workflow (Req 16.10).
- **Eigener Reverse Proxy:** Abgelehnt. Traefik der VPS stellt TLS und Routing schon bereit, ein
  zweiter Proxy würde um dieselben Ports konkurrieren.
- **Testdaten von Stufe A nach Stufe B übernehmen:** Abgelehnt. Stufe B startet leer, damit kein
  Teststand ins produktive Archiv gelangt.

## Folgen
- **Positiv:** Die Entwicklung startet ohne neue Server-Bestellung. Der Wechsel ändert nur das
  Deploy-Ziel und die Stufenwerte, nicht das Muster. K5 stützt sich auf Erfahrungen aus Stufe A.
- **Negativ:** Die Leistungsziele sind erst spät nachgewiesen. Ist Stufe B zu klein, verfehlt sie
  die Leistungsziele, und OCR blockiert die API. Stufe A kann andere Apps der VPS belasten; ob
  sie das Budget hält, zeigt erst die Messung in 1.10 (Abbruchkriterium Req 16.18). Infra-Einträge
  entstehen zweimal und müssen auf Stufe A zurückgebaut werden; gesperrte Testobjekte bleiben offen.
- **Umsetzung:** Stufe A in 0.7 vorbereitet, in 1.9 deployt, in 1.10 gemessen (Bericht
  `docs/spikes/stufe-a-speicher.md`, nur Zahlen); nach jedem Deploy Speicherabbrüche und Health
  der anderen Apps prüfen (Req 16.18).
- **Tests:** Lasttests (2.4, 2.6, 4.5) nur auf Stufe B (5.11), auf Stufe A nur Funktion.
- **Betrieb:** Die Restore-Probe zählt zum Speicherbudget; auf Stufe B läuft sie auf derselben
  VPS nachts mit angehaltenem Worker (Nachtrag 12.11.2026). Monitoring auf Stufe B (5.9); die
  Übernahme der Belege 2026 (5.8) setzt den abgeschlossenen Wechsel voraus.

## Nachtrag 09.11.2026 (Buckets der Stufe A)
Entschieden von Fabian am 09.11.2026 (Option A): Der Bucket heisst `<archiv.ablage.bucket-praefix><code>`, Standard
`urinet-archiv-`, auf Stufe A `urinet-archiv-a-`; den produktiven Bucket `urinet-archiv-urinet` gibt es erst auf Stufe B
(Aufgabe 5.4). Mit `archiv.stufe=A` sperrt das Archiv auch Dokumente unter `archiv/` höchstens 1 Tag (GOVERNANCE,
`archiv.ablage.max-sperrfrist=P1D`). Ab Stufe B bricht der Start ab, wenn eine Höchstfrist gesetzt ist oder der Präfix
der Stufe A gilt (Req 6.11). Beim Wechsel (5.11) löscht der App-Schlüssel frühestens 1 Tag nach dem letzten Upload alle
Versionen der Test-Buckets ohne Bypass-Header, danach die Buckets; der Inhaber-Zugang wird nicht gebraucht.

## Nachtrag 10.11.2026 (Speicherbudget der Stufe A)
Entschieden von Fabian am 10.11.2026: Das Speicherbudget der Stufe A steigt von 700 auf **900 MiB** (Summe `mem_limit`);
`cpus` bleibt bei höchstens 0.75. Grund: Die erste Messung in Aufgabe 1.9 (lokal, ganzer Stack) ergab 820 MiB, vor
allem wegen Keycloak (rund 318 MiB im Leerlauf); mit tieferen Limits beendete der cgroup-OOM Keycloak. Die VPS hat
rund 1'140 MB frei. Das Abbruchkriterium aus Req 16.18 bleibt: Ein Speicherabbruch auf der VPS oder eine kranke andere
App beendet den Deploy, das Archiv wird auf Stufe A gestoppt und Stufe B (5.11) vorgezogen. Punkt 4 «Budget» oben gilt
mit dem neuen Wert; nachgeführt in Req 16.18, `tech.md`, design.md, tasks.md (1.9, 1.10) und im Deploy-Workflow.

## Nachtrag 13.11.2026 (Speicherbudget der Stufe A, zweite Anhebung)
Entschieden von Fabian am 13.11.2026: Das Speicherbudget der Stufe A steigt von 900 auf **1000 MiB** (Summe `mem_limit`),
die API bekommt 500m statt 320m; `cpus` bleibt bei höchstens 0.75. Anlass: Nach dem Deploy von 3.2 (KI-Extraktor) belegte
die API schon im Leerlauf rund 300 von 320 MiB, und der cgroup-OOM beendete sie bei der ersten Verarbeitung viermal
(ohne manuellen Eingriff). Nach Req 16.18 hätte das den vorzeitigen Wechsel auf Stufe B ausgelöst; Fabian hat stattdessen
das Kriterium angepasst, weil auf Stufe A nur Testdaten liegen und die VPS rund 490 MiB frei hat. Der Wechsel auf Stufe B
bleibt bei 5.11. Nachgeführt in Req 16.18, `tech.md`, design.md, tasks.md (1.10), compose.yml und im Deploy-Workflow.

## Nachtrag 12.11.2026 (K5: Grösse der VPS für Stufe B)
Vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86 (Aufgabe 4.9, Entscheid 4.9-E7; Spec 01, Req 16.1, 16.16–16.18, 16.26):
- **Grösse:** eigene Infomaniak VPS Lite mit 4 vCPU, 4 GB RAM und 80 GB Disk nur für das Archiv, CHF 9.00/Mt. bei
  Jahresbindung (CHF 108 im Voraus, Stand 12.11.2026). Bestellt wird erst zu Beginn von Aufgabe 5.11; vorher laufen die
  Lastskripte einige Stunden auf einer stundenweise abgerechneten Infomaniak-Public-Cloud-Instanz ähnlicher Grösse.
- **Aufrüsten:** Verfehlt die Messung die Ziele, wird die VPS an Ort auf 4 vCPU / 8 GB / 160 GB aufgerüstet
  (CHF 18.00/Mt.; nicht umkehrbar, darum erst nach der Messung). Wechsel auf VPS Cloud (an Ort aufrüstbar, SLA, Snapshot),
  wenn auch 4/8 ein Ziel verfehlt, die Vektoren aus Spec 02/04 mehr Speicher brauchen, Spec 03 (öffentliche Schnittstelle,
  zweiter Mandant) die Last erhöht oder Fabian SLA, Snapshots oder Support will.
- **Speicherbudget Stufe B:** Startwerte je Dienst für 4 GB in design.md «Stufen der Plattform» (Summe `mem_limit`
  höchstens 3'200 MiB, `cpus` höchstens 4.5), mit Reserve für Betriebssystem, Traefik und Monitoring, bezogen auf das
  gemessene `MemTotal`; die Restore-Probe läuft nachts mit angehaltenem Worker in dessen Anteil (ADR-008, Nachtrag
  12.11.2026). Der Worker läuft vorerst mit Parallelität 1 (ADR-003). Das Budget gilt je Plattform (Zielhost), nicht je
  `archiv.stufe`; `pruefe-speicherbudget.py` wird in Aufgabe 5.11 entsprechend erweitert.
- **Messung:** auf der neuen VPS vor dem Umstellen, mit den Diensten und dem Budget der Stufe B, aber `archiv.stufe=A`
  und einem eigenen Mess-Mandanten (Bucket `urinet-archiv-a-last`), nur synthetisch, nicht im produktiven Mandanten und
  nicht in Test-Buckets der laufenden Stufe A; Messpunkt Server; Eingaben in Req 3.2, 5.5 und 14.3. Bei vorgezogenem
  Wechsel als eigenes Compose-Projekt auf derselben VPS, solange dort noch keine echten Belege liegen (Aufgabe 5.11).
- **Abbruchkriterium Stufe A:** Speicherabbrüche durch manuelle Eingriffe (z. B. eine zweite JVM per `docker exec` wie
  am 12.11.2026) lösen Req 16.18 nicht aus. «Stufe A» meint dort die Plattform; die Messkonfiguration auf der neuen VPS
  fällt nicht darunter.
- **Betrachtete Alternativen:** VPS Lite 4/8/160 sofort (doppelter Preis auf Verdacht, Aufrüsten bleibt möglich);
  bestehende VPS an Ort aufrüsten (echte Belege auf demselben Host wie andere Apps, widerspricht Punkt 5 und Req 16.15);
  VPS Lite 2/4/60 (nur 2 Kerne für API und OCR); eigene Probe-VPS (Mehrkosten, ADR-008); Stufe B ohne Aufrüstung auf der
  bestehenden VPS (Speicher reicht nicht); Stufe B aufschieben (keine echten Belege, Ziel von Phase 1 fällt weg).
- Restrisiko VPS Lite: keine SLA, keine Snapshots, ausgehend kein Port 25; Infomaniak empfiehlt sie für Projekte, die
  noch nicht geschäftskritisch sind. Vertretbar, weil Object Lock, Zweitkopie und Restore-Probe die Daten tragen.

## Verweise
- Req 3.2, 5.5, 14.3, 16.1, 16.4, 16.7, 16.9–16.11, 16.15–16.19, 16.26; P1 (geändert 08.11.2026), P5, P15, K5
- `tech.md` «Plattform und Betrieb»; design.md «Betrieb auf der VPS», «Stufen der Plattform»
- Aufgaben 0.7, 1.9, 1.10, 4.9, 5.8, 5.9, 5.11
- ADR-002, ADR-004, ADR-007, ADR-008, ADR-009
