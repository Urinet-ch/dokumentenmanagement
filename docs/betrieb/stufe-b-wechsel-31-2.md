# Vorbereitung des Wechsels zu Stufe B

Diese Änderungen werden erst beim Wechsel 31.7 gemergt und deployt. `web-archiv` wird in 31.10 im Infra-Repo
angelegt, nicht durch Compose. Fabian prüft, dass nur Traefik und Archivdienste angeschlossen sind.
Das Netz ist im Infra-Repo vorgesehen (Urinet-ch/infomaniak-vps#7; Werte nur dort, README «Netze»). Die GitHub-Variable
`ARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE` erhält dessen CIDR-Bereich; Fabian vergleicht ihn vor Freigabe mit dem
tatsächlich angelegten Netz; ein leerer Wert vertraut keinem Proxy. Die Netz-Policy erlaubt `web-archiv` nur für `archiv`, nie
zugleich mit `web`; Compose setzt bei `api` und `kc` das Label `traefik.docker.network: web-archiv`.
Die Übergabe der GitHub-Variable im Deploy-Workflow wurde durch #762 übernommen.

## Leerstart

Die Compose-Datei verwendet neue Volumes `archiv_daten_b` und `archiv_arbeit_b`. Dadurch werden weder Archiv- noch
Keycloak-Datenbank oder flüchtige Arbeitsdateien der Stufe A übernommen. Fabian prüft vor dem Wechsel, dass diese
Namen nicht bereits belegt sind. Erst nach erfolgreichem Wechsel und Prüfung nach 31.7 entfernt Fabian die alten
Volumes `archiv_daten` und `archiv_arbeit` gezielt; das ist endgültig. Test-Buckets und Testschlüsselpaar separat
nach 31.7 verwerfen. `deploy/backup/taeglich.sh` verwendet die über `ARCHIV_COMPOSE_FILE` angegebene deployte
Compose-Datei und sichert Archiv- und Keycloak-Datenbank über `pg_dump` im laufenden Dienst `db`. Dieser Dienst
hängt gemäss Compose `archiv_daten_b` ein; ein Volume-Eintrag in `backup-apps` ist dafür nicht vorgesehen.
Beim Wechsel die Anbindung des Sicherungslaufs an die deployte Compose-Datei und die Sicherung beider Datenbanken
prüfen. Das Arbeits-Volume bleibt von der Sicherung ausgenommen. Schlüssel und Zugangsdaten bleiben bestehen.

## Abbruch und Rückfall

Bei fehlgeschlagenem Deploy oder fehlendem Nachweis den Wechsel anhalten, keine echten Belege erfassen und den
Stand im Wechselprotokoll festhalten. Der automatische Rollback setzt nur Images zurück; Compose-Datei, Netz,
Volumes und Datenbank bleiben unverändert ([Deploy und Rollback](deploy-rollback.md)). Ein zurückgesetztes Image
stellt daher weder die Stufe-A-Konfiguration noch deren Daten wieder her. Fabian und Infra prüfen Deploy-Ergebnis,
Konfiguration, Netz, Volumes und Health; Korrekturen erfolgen über einen freigegebenen Deploy-Workflow.

Vor der Bereinigung kann Stufe A weiterhin nur mit Testdaten laufen; nach neuen Uploads beginnt die Wartefrist der
betroffenen Test-Buckets erneut. Nach dem Verwerfen der Test-Buckets in 31.7 sind deren Daten endgültig weg, auch
wenn die alten Datenbank-Volumes noch vorhanden sind; sie ergeben keinen vollständigen Stufe-A-Stand. Fabian
entscheidet über die Wiederholung des Wechsels oder eine neu eingerichtete, ausschliesslich synthetische Stufe A.
Nach Eingang echter Belege nicht auf Stufe A mit verkürzten Sperren zurückschalten und keine produktiven Daten
verwerfen; Eingänge stoppen und nach [Wiederherstellung](../wiederherstellung.md) vorgehen. Die ausführliche
Reihenfolge steht in [Wechsel von Stufe A auf Stufe B](wechsel-stufe-b.md#rückweg).

## Restore-Probe

Offene Infra-Voraussetzung vor dem Wechsel: `bin/probe-gate.sh` existiert noch nicht und muss nach Spec 01,
design.md «Schlüsselweg (4.9-E4)» mit den Befehlen `probe archiv` und `probe aufraeumen` erstellt werden. Es muss
vor der Probe `api` anhalten und bei Erfolg, Fehler, Abbruch und Zeitüberschreitung mittels `trap` wieder starten;
auch fehlgeschlagene Neustarts müssen als Fehler gemeldet und überwacht werden. Das Gate ist im Infra-Repo mit
Gegenproben zu prüfen. Die Probe belegt zusammen
1024 MiB und 0.6 CPU oder weniger; die hier gesetzten CPU-Limits sind Startwerte, deren Funktion und Laufzeit beim
synthetischen Betriebslauf 31.4 nachzuweisen sind. Der Betrieb ist währenddessen nicht erreichbar.

## Späterer SFTP-Eingang

Vor dem SFTP-Dienst (Spec 09, 28.3) braucht es eine eigene Infra-Aufgabe: einen TCP-EntryPoint und die
Policy-Ausnahme für den TCP-Router von Traefik. Der Zugang von aussen erfolgt nach Req 16.28 ausschliesslich über
diesen Router mit IP-Freigabe; ein direkt veröffentlichter Host-Port in Compose ist nicht zulässig. EntryPoint und
Policy-Ausnahme fehlen heute.
