# Stufe A: Speichermessung und Abbruch (Aufgabe 1.10)

Stand 22.11.2026. Nur Zahlen, keine Namen anderer Apps. Alle Werte stammen von der VPS der Stufe A (Host mit 1973 MiB Speicher, kein Swap) und wurden von Fabian erhoben.

## Ergebnis
**Das Abbruchkriterium aus 1.10 ist erfüllt (Req 16.18).** Am 22.11.2026 um 08:12:57 UTC beendete der Kernel die JVM von `archiv-api` wegen Speichermangels (Memory-Cgroup-Limit des Containers). Auslöser war ein normaler Upload eines Dokuments mit 15.6 MB, kein manueller Eingriff. Folge laut Aufgabe: Archiv-Dienste auf Stufe A stoppen, Fabian informieren, K5 (4.9) und Wechsel auf Stufe B (5.11) vorziehen.

## Messwerte im Leerlauf (22.11.2026)
| Container | Verbrauch | Limit | Anteil |
|---|---|---|---|
| `archiv-api` | 413.3 MiB | 500 MiB | 82.7 % |
| `archiv-kc` (Keycloak) | 338.3 MiB | 550 MiB | 61.5 % |
| `archiv-db` | 49.3 MiB | 100 MiB | 49.3 % |
| Archiv zusammen | 801 MiB | 1150 MiB | 69.7 % |

Host (`free -m`): total 1973, belegt 1523, frei 67, Cache 567, **verfügbar 450 MiB**, Swap 0. Alle Container zusammen belegen rund 1075 MiB; rund 450 MiB gehen auf Betriebssystem, Docker und weitere Prozesse. Fabian meldete am 20.11.2026 beim Host 542 MiB verfügbar.

## Vorfall
- 08:12:57 UTC: `oom-kill` mit Einschränkung der Memory-Cgroup des `archiv-api`-Containers, Prozess `java`, `anon-rss` 409'076 kB, `total-vm` 774'600 kB.
- 08:13:04 UTC: Docker startet `archiv-api` neu (`RestartCount=1`); das Feld `OOMKilled` steht danach wieder auf `false` und belegt den Vorfall nicht, `dmesg` schon.
- `archiv-kc` und `archiv-db` laufen seit dem Deploy vom 21.11.2026 um 18:00 UTC ohne Neustart. Die Neustartzähler aller anderen Container der VPS stehen auf 0 mit unveränderten Startzeiten: Die Health anderer Apps war nicht betroffen.
- Auslöser: Upload einer Datei mit 15.6 MB (Limit der API 50 MB). API und Worker laufen auf Stufe A in einem Container (`archiv.rolle=api,worker`), die OCR-Werkzeuge zählen zum Speicher dieses Containers.

## Bewertung
- Die Reserve von `archiv-api` ist im Leerlauf mit 87 MiB zu klein für die Verarbeitung eines normalen mehrseitigen Dokuments.
- Das Anheben des Limits ist nur mit Abstrichen bei Keycloak oder der Datenbank oder einem höheren Budget möglich; auf dem Host bleiben nur 450 MiB verfügbar, ohne Swap.
- Stufe B trennt Worker und API und hat 4 GB Speicher; der Wechsel ist der vorgesehene Weg (5.11).

## Nicht gemessen
Spitzenverbrauch beim Upload und bei OCR mehrseitiger Belege je Dienst; die Messung auf Stufe A entfällt wegen des Abbruchs. Der Spitzenverbrauch wird auf Stufe B in 5.11 gemessen (Messbedingungen Req 3.2, 5.5, 14.3, 16.26).

## Bestätigung Testdaten [Fabian]
Welche Testdaten auf Stufe A geladen wurden (Req 16.15): _wird von Fabian ergänzt_.
