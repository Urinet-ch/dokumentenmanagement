# Vorbereitung des Wechsels zu Stufe B

Diese Änderungen werden erst beim Wechsel 31.7 gemergt und deployt. `web-archiv` wird in 31.10 im Infra-Repo
angelegt, nicht durch Compose. Fabian prüft, dass nur Traefik und Archivdienste angeschlossen sind.
Der Deploy braucht den tatsächlichen CIDR-Bereich dieses Netzes als GitHub-Variable
`ARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE`. Der beiliegende Workflow-Patch muss vor dem Deploy übernommen werden.
Fabian vergleicht den Wert vor Freigabe mit dem tatsächlichen Netz; ein leerer Wert vertraut keinem Proxy.

## Leerstart

Die Compose-Datei verwendet neue Volumes `archiv_daten_b` und `archiv_arbeit_b`. Dadurch werden weder Archiv- noch
Keycloak-Datenbank oder flüchtige Arbeitsdateien der Stufe A übernommen. Fabian prüft vor dem Wechsel, dass diese
Namen nicht bereits belegt sind. Erst nach erfolgreichem Wechsel und Prüfung nach 31.7 entfernt Fabian die alten
Volumes `archiv_daten` und `archiv_arbeit` gezielt; das ist endgültig. Test-Buckets und Testschlüsselpaar separat
nach 31.7 verwerfen. Der Sicherungs-Eintrag im Infra-Repo muss auf das neue Daten-Volume zeigen; das Arbeits-Volume
bleibt von der Sicherung ausgenommen. Schlüssel und Zugangsdaten bleiben gemäss Entscheid bestehen.

## Restore-Probe

Im Infra-Repo muss Fabian `probe-gate.sh` so ändern, dass es vor der Probe `api` anhält und bei Erfolg, Fehler,
Abbruch und Zeitüberschreitung mittels `trap` wieder startet; auch fehlgeschlagene Neustarts müssen als Fehler
gemeldet und überwacht werden. Die Änderung ist dort mit Gegenproben auszuführen. Die Probe belegt zusammen
1024 MiB und 0.6 CPU oder weniger; die hier gesetzten CPU-Limits sind Startwerte, deren Funktion und Laufzeit beim
synthetischen Betriebslauf 31.4 nachzuweisen sind. Der Betrieb ist währenddessen nicht erreichbar.
