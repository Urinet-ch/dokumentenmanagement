# ADR-003: Auftragsschlange in PostgreSQL statt Message-Broker

**Status:** Vorgeschlagen – Freigabe per Merge an Gate 0 (Aufgabe 0.6)

**Datum:** 08.11.2026

## Kontext
- Lange Arbeiten dürfen die API nicht blockieren: Verarbeitung mit OCR, KI-Vorschlag, Archivierung, CSV-Vorschau und -Übernahme, Manifest und Prüflauf. Der Upload antwortet deshalb mit 202 und reiht einen Auftrag ein (Aufgabe 2.4).
- Ein Auftrag muss einen Neustart und einen Rollback-Deploy überstehen. Er hat höchstens 3 Versuche mit wachsender Wartezeit (Req 5.4) und eine Zeitgrenze je Typ (Req 5.6). Verwaiste Aufträge werden wieder freigegeben (Req 5.7), und jeder Auftrag führt seinen Mandanten mit (Req 1.6).
- Das Volumen eines Kleinbetriebs ist gering, verlangt wird Verlässlichkeit, nicht Durchsatz.
- In Stufe A gilt ein Budget von höchstens 700 MiB und 0.75 CPU für alle Archiv-Dienste. API und Worker laufen in einem Container, mit Parallelität 1 (P1, ADR-010). Jeder zusätzliche Dienst müsste gesichert, überwacht und nach der Compose-Policy betrieben werden.
- Der Statuswechsel eines Dokuments und das Einreihen des Folgeauftrags gehören fachlich zusammen, etwa bei `NEU` und `VERARBEITEN` oder bei `ARCHIVIERUNG` und `ARCHIVIEREN`.

## Entscheid
1. Die Schlange ist die Tabelle `public.auftrag` mit den Spalten `id`, `mandant`, `typ`, `nutzlast` (jsonb), `status` (`OFFEN|LAEUFT|FERTIG|FEHLER`), `versuche`, `nicht_vor`, `fehler`, `erstellt`, `gestartet`, `lebenszeichen` und `beendet`, dazu die Indizes `(status, nicht_vor)` und `(status, lebenszeichen)`. Sie liegt im gemeinsamen Schema, und der Mandant steht als Spalte im Auftrag (ADR-002).
2. Aufträge werden über `AuftragService.einreihen(typ, nutzlast)` eingereiht. `AuftragPoller` (Quarkus Scheduler, alle 2 s) holt sie mit `LIMIT n FOR UPDATE SKIP LOCKED` ab. Die Parallelität ist `archiv.worker.parallel=2`, in Stufe A 1. Jeder Auftrag läuft im Mandantenkontext (`MandantKontext.mit`).
3. Die Auftragstypen in Phase 1 sind `VERARBEITEN`, `KI_VORSCHLAG`, `ARCHIVIEREN`, `CSV_VORSCHAU`, `CSV_UEBERNEHMEN`, `MANIFEST` und `PRUEFLAUF`, Letzterer in Teilaufträgen.
4. Jeder Typ hat eine Zeitgrenze, konfigurierbar unter `archiv.auftrag.zeitgrenze.<typ>`. Die Startwerte stehen in design.md und werden nach K5 geprüft. Externe Werkzeuge laufen mit `waitFor(zeitgrenze)` und danach `destroyForcibly()`, Java-Teile mit `orTimeout`. Eine Überschreitung zählt als fehlgeschlagener Versuch.
5. Während eines Auftrags schreibt der Worker alle 30 s ein Lebenszeichen. `AuftragAufraeumer` läuft jede Minute und setzt `LAEUFT`-Aufträge, deren Lebenszeichen älter als die Zeitgrenze ist, auf `OFFEN`, mit `versuche + 1` und `fehler = 'verwaist'`.
6. Es gibt insgesamt 3 Versuche. Nach dem n-ten Fehlschlag gilt `nicht_vor = now() + 2^n Minuten`. Danach steht der Auftrag auf `FEHLER` und das Dokument auf `FEHLER` (nach `VERARBEITEN`) bzw. `ARCHIVIERUNG_FEHLER` (nach `ARCHIVIEREN`).
   - Bei Prüfsummen- oder Sperrfehlern wechselt das Dokument sofort auf `ARCHIVIERUNG_FEHLER`, ohne weiteren Versuch (Req 6.4; Ablage und Sperre siehe ADR-004 und ADR-007).
   - `KI_VORSCHLAG` setzt nie `FEHLER`: Ohne Antwort geht das Dokument ohne Vorschlag in den Eingangskorb (ADR-006).
7. `wiederholen` legt einen neuen Auftrag mit `versuche = 0` an (Req 6.8).
8. Es gibt keinen Message-Broker und keinen zusätzlichen Dienst (tech.md, Konventionen Backend).

## Betrachtete Alternativen
- **Message-Broker** (z. B. RabbitMQ, Kafka, ActiveMQ Artemis): Ein Broker ist ein zusätzlicher Dienst mit eigenem Speicherbedarf (Budget der Stufe A), eigener Sicherung (ADR-008), eigener Überwachung und eigenem Eintrag in der Compose-Policy. Status in PostgreSQL und Nachricht im Broker lägen in zwei Systemen ohne gemeinsame Transaktion, für Verlässlichkeit bräuchte es zusätzlich ein Outbox-Muster. Den Durchsatz eines Brokers braucht das Archiv nicht.
- **Warteschlange in Redis:** hat dieselben Betriebskosten wie ein Broker. Dauerhaft gespeichert sind die Aufträge nur bei passender Konfiguration, und auch hier fehlt die gemeinsame Transaktion mit den Dokumentdaten.
- **Nur im Speicher** (Quarkus Scheduler ohne Tabelle, Event Bus): Aufträge gingen bei Neustart oder Rollback verloren, und es gäbe weder Versuchszähler noch Lebenszeichen noch eine Sicht für die Kennzahlen. Das widerspricht Req 5.4 und 5.7.
- **Quartz mit JDBC-Store:** speichert Aufträge dauerhaft, bringt aber ein eigenes Tabellenschema und eigene Cluster-Logik mit, für ein Problem, das eine Tabelle mit `SKIP LOCKED` löst.
- **`LISTEN/NOTIFY` statt Polling:** würde die Wartezeit von bis zu 2 s sparen, verlangt aber eine dauerhaft offene Verbindung je Worker. Polling im 2-Sekunden-Takt reicht für das Ziel von 30 s p95 (Req 5.5).

## Folgen
- **Positiv:** Es gibt keine zusätzliche Komponente: Die Schlange wird mit der Datenbank gesichert und wiederhergestellt (ADR-008).
- **Positiv:** Ein Auftrag kann in derselben Transaktion eingereiht werden wie die Statusänderung, die ihn auslöst. So entsteht kein Auftrag ohne Dokument und kein Dokument ohne Auftrag.
- **Positiv:** `SKIP LOCKED` verteilt Aufträge auch auf mehrere Worker ohne Doppelvergabe, etwa in Stufe B mit eigenem Dienst `archiv-worker` (ADR-010).
- **Positiv:** Der Zustand der Schlange lässt sich per SQL einsehen. Daraus kommen die Kennzahlen «Aufträge offen» und «Fehler» (Req 16.4) sowie die Überwachung (Req 16.5).
- **Negativ:** Das Polling erzeugt eine Grundlast (eine Abfrage alle 2 s), und ein Auftrag wartet bis zu 2 s, bevor er startet.
- **Negativ:** Der Durchsatz hängt an PostgreSQL. Gemessen wird er erst auf Stufe B (Aufgabe 5.11, Req 16.16).
- **Umsetzung:** `FOR UPDATE` gilt nur für das Abholen (Wechsel auf `LAEUFT`). Während der Arbeit schützen Lebenszeichen und Aufräumer, keine offene Transaktion. Darum müssen Handler bei einem erneuten Lauf korrekt arbeiten. Beispiele: `archivieren` übernimmt eine vorhandene, korrekte Kopie, damit unter `archiv/` keine fremden Versionen entstehen (design.md, Ablage; Req 15.10), und ein Manifest wird nie neu erzeugt (Req 15.6).
- **Tests:** Zwei Worker holen denselben Auftrag nie doppelt, und Zeitüberschreitung und verwaister Auftrag zählen als Fehlversuch (Aufgabe 2.5). Dazu kommt ein Integrationstest für verwaiste Aufträge (design.md, Testing Strategy).
- **Betrieb:** Die Tabelle wächst mit jedem Auftrag. Die Spec regelt das Aufräumen erledigter Aufträge noch nicht; Vorschlag für die Open Points in design.md.

## Verweise
- Req 1.6, 5.4, 5.5, 5.6, 5.7, 6.4, 6.8, 15.6, 15.10, 16.4, 16.5, 16.16
- tech.md, Konventionen Backend (Auftragsschlange) und Speicherbudget Stufe A
- P1, K5
- ADR-002, ADR-004, ADR-006, ADR-007, ADR-008, ADR-010
- Aufgaben 2.4, 2.5, 2.6, 3.4, 4.2, 4.3, 5.2, 5.3, 5.6
