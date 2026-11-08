# ADR-010: Plattform auf Infomaniak-VPS in zwei Stufen, Deploy nur über GitHub Actions

**Status:** Vorgeschlagen – Freigabe per Merge an Gate 0 (Aufgabe 0.6)

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
   - Budget: Summe `mem_limit` ≤ 700 MiB, `cpus` ≤ 0.75 (Richtwert, gemessen in 1.10).
   - API und Worker laufen in einem Container: Image `archiv-worker`, `archiv.rolle=api,worker`,
     Parallelität 1, OCR mit `--jobs 1`.
   - Leistungsziele gelten nicht (Req 16.16). Die Sicherung läuft nur als Funktionstest
     (Req 16.19, ADR-008).
   - Die Stufenwerte liegen versioniert unter `deploy/infomaniak/`.
5. **Stufe B** auf einer eigenen, grösseren Infomaniak-VPS nur für das Archiv:
   - Grösse: [offen: VPS-Grösse K5 – Entscheid vor Welle 5].
   - `archiv-api` und `archiv-worker` laufen getrennt. Hier gelten die Leistungsziele und laufen
     die Lasttests.
6. **Wechsel (Aufgabe 5.11, Req 16.17):**
   - Zeitpunkt: spätestens vor echten Belegen, also vor 5.4 und 5.8. Früher, falls 1.10 abbricht
     oder 16.18 eintritt.
   - Umfang: neue VPS nach dem Muster, Deploy-Ziel umstellen (Host und Zugang nur als
     GitHub-Secrets), DNS beider Domains umstellen, Stufe B leer starten, Archiv-Dienste auf
     Stufe A entfernen, Leistung messen.
   - Test-Buckets: [offen: Bucket-Namen der Stufe A und Umgang mit gesperrten Testobjekten beim
     Wechsel – Entscheid vor Welle 1].
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
- **Betrieb:** Die Restore-Probe zählt zum Speicherbudget. Monitoring auf Stufe B (5.9); die
  Übernahme der Belege 2026 (5.8) setzt den abgeschlossenen Wechsel voraus.

## Verweise
- Req 3.2, 5.5, 14.3, 16.1, 16.4, 16.7, 16.9–16.11, 16.15–16.19; P1 (geändert 08.11.2026), P5, P15, K5
- `tech.md` «Plattform und Betrieb»; design.md «Betrieb auf der VPS», «Stufen der Plattform»
- Aufgaben 0.7, 1.9, 1.10, 4.9, 5.8, 5.9, 5.11
- ADR-002, ADR-004, ADR-007, ADR-008, ADR-009
