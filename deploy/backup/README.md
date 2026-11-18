# Täglicher Export

`taeglich.sh` erzeugt einen Lauf unter `ARCHIV_SICHERUNGSVERZEICHNIS/laeufe/`.
Erst wenn alle Exporte und Prüfsummen bereitstehen, wird das Verzeichnis atomar veröffentlicht.
Nur Läufe mit der Datei `FERTIG` sind für die Abholung bereit. Sie enthalten Chiffrate,
`SHA256SUMS` und den Marker; die SHA-256 jedes Chiffrats wird zusätzlich mit
`archiv sicherung festhalten` registriert. Das Shell-Skript implementiert weder einen eigenen
Download noch einen Transport fertiger Läufe. Der Bucket-Strom wird ausschliesslich durch den
vorhandenen `archiv sicherung zuwachs`-Befehl aus dem konfigurierten Object Storage gelesen;
Abruf der Läufe und restic gehören ins Infra-Repository.

## Konfiguration

Die Werte werden der Umgebung des Einstiegsskripts übergeben. Compose-Datei, Dienste,
Datenbanknamen und Speicherort sind konfigurierbar; die Dienstnamen unten sind die
Vorgaben der Compose-Datei dieses Repositories.

| Variable | Pflicht | Bedeutung |
| --- | --- | --- |
| `ARCHIV_COMPOSE_FILE` | ja | Compose-Datei |
| `ARCHIV_SICHERUNGSVERZEICHNIS` | ja | Eigenes Verzeichnis für Läufe und lokalen Zustand |
| `ARCHIV_MANDANTEN` | ja | Leerzeichengetrennte Mandantencodes |
| `ARCHIV_AGE_EMPFAENGER_SICHERUNG` | ja | Öffentlicher `age`-Empfänger des Sicherungsschlüssels (`age1…`, 62 Zeichen) |
| `ARCHIV_AGE_EMPFAENGER_PROBE` | ja | Öffentlicher `age`-Empfänger des Probe-Schlüssels, muss vom Sicherungsschlüssel verschieden sein |
| `ARCHIV_API_SERVICE` | nein | API-Dienst, Standard `api` |
| `ARCHIV_DB_SERVICE` | nein | Datenbankdienst, Standard `db` |
| `ARCHIV_KEYCLOAK_SERVICE` | nein | Keycloak-Dienst, Standard `kc` |
| `ARCHIV_DB_NAME` | nein | Archiv-Datenbank, Standard `archiv` |
| `ARCHIV_KEYCLOAK_DB_NAME` | nein | Keycloak-Datenbank, Standard `keycloak` |
| `ARCHIV_VOLLEXPORT` | nein | `1` erzwingt für diesen Lauf einen vollständigen Bucket-Export, Standard `0` |
| `ARCHIV_AUFBEWAHRUNG_TAGE` | nein | Aufbewahrung alter Läufe, Standard `3`, zulässig `1` bis `3650` |
| `ARCHIV_DOCKER_BIN` | nein | Docker-Programm, Standard `docker` |
| `ARCHIV_AGE_BIN` | nein | `age`-Programm, Standard `age` |

Die beiden Empfängerwerte sind öffentliche Schlüssel, keine Geheimnisse. Private Schlüssel
werden vom Server nicht benötigt oder gelesen. Die Zugangsdaten für die Datenbank und der
Manifest-Schlüssel bleiben Secret-Dateien in den Compose-Diensten; das Skript gibt ihre Inhalte
weder als Argument noch in Logs aus. Der Manifest-Schlüssel wird nur als Datei aus dem API-
Container kopiert und sofort verschlüsselt.

Der Ordner `.zustand/` ausserhalb von `laeufe/` enthält das letzte Inventar unverschlüsselt,
mit restriktiven Dateirechten. Es wird ausschliesslich als Vorinventar für den nächsten
`zuwachs`-Aufruf benötigt und gehört nicht zu den Dateien, die das Infra-Repository abholt.
Bei einem neuen Mandanten wird einmal bewusst ein vollständiger Bucket-Export erstellt.
Dasselbe gilt nach einem Wechsel des Probe-Schlüssels (Req 16.23): Der Zustand speichert die Prüfsumme des
Probe-Empfängers, bei Abweichung oder fehlendem Eintrag folgt ein Vollexport für alle Mandanten, sonst bliebe der
Zuwachs der Tage vor dem Wechsel nur für den alten Probe-Schlüssel lesbar. Von Hand erzwingt man ihn mit
`ARCHIV_VOLLEXPORT=1`. Das Vorinventar enthält Objektschlüssel, Versionen und Sperrfristen und liegt bewusst nur
mit Rechten 600/700 vor, nicht verschlüsselt: Zur Laufzeit gibt es keinen privaten Schlüssel zum Entschlüsseln.

## Ablauf

1. `pg_dump` sichert die Archiv-Datenbank.
2. Der Keycloak-Dienst wird für den konsistenten Realm-Export kurz angehalten. Das Skript
   exportiert Realm und Keycloak-Datenbank in einem Wegwerf-Container und startet den Dienst
   auch nach einem Fehler erneut. Währenddessen ist die Anmeldung vorübergehend nicht verfügbar.
3. Für jeden Mandanten liest `archiv sicherung inventar` zuerst alle Versionen und Sperren.
   Danach erzeugt `archiv sicherung zuwachs` den Bucket-Strom anhand des Inventars des letzten
   erfolgreichen Laufs. Beim ersten Lauf für einen Mandanten wird bewusst der Vollexport
   verwendet. Ein fehlerhafter oder unvollständiger Export wird verworfen.
4. Die Dumps, der Realm-Export, der Manifest-Schlüssel, jedes Inventar und jeder Bucket-Strom
   werden mit `age` an beide Empfänger verschlüsselt. SHA-256 wird je Chiffrat berechnet.
5. Die verschlüsselten Dateien und `SHA256SUMS` werden atomar unter
   `laeufe/lauf-<UTC-Zeit>-<Prozess-ID>/` veröffentlicht; ihre SHA-256-Werte werden im Register festgehalten.
   Erst nach erfolgreichem Registereintrag entsteht `FERTIG` und wird das Vorinventar für den
   nächsten Lauf aktualisiert. Alte Läufe werden erst danach innerhalb des konfigurierten
   Sicherungsverzeichnisses aufgeräumt.

Die Quarkus-Adminbefehle (`inventar`, `zuwachs`, `festhalten`) laufen wie in der README des Repositories unter
«Admin-Kommandos auf der VPS» in einem Wegwerf-Container (`docker compose run --rm --no-deps -T -l
traefik.enable=false api …`), nie per `exec` im laufenden API-Container: Eine zweite JVM sprengt dessen `mem_limit`.
Auf der VPS muss dafür kurz Platz für einen zweiten API-Container sein. Der Manifest-Schlüssel wird mit `compose cp`
als Datei aus dem laufenden Container kopiert; dabei startet keine JVM. Für den konsistenten Realm-Export wird
Keycloak kurz angehalten und ein Wegwerf-Container verwendet. Dessen Export schreibt in ein Bind-Mount unter dem
Arbeitsverzeichnis, weil `/tmp` des Dienstes ein 32-MiB-tmpfs ist, das mit dem Container verschwindet. Dieser Teil ist
nur gegen Attrappen getestet; der Lauf gegen die echte Compose-Umgebung ist Teil der Inbetriebnahme (Infra). Die Shell-Skripte transportieren keine
Exporte an einen Backup-Anbieter.

## Aufruf und Fehlercodes

`taeglich.sh` hat keine Positionsargumente. Fehlende oder ungültige Konfiguration führt zu
Exit-Code `2`; technische Fehler, ein belegter Lauf oder ein fehlgeschlagener Export führen zu
Exit-Code `3`; ein vollständig abgeschlossener Lauf endet mit `0`. Abbruch durch `SIGINT` und
`SIGTERM` liefert `130` beziehungsweise `143`. Fehler werden auf stderr gemeldet. Vor dem
erfolgreichen Registereintrag fehlt `FERTIG`; solche Läufe sind nicht zur Abholung freigegeben.
Bei einem Fehler in diesem Schritt bleibt das zuletzt erfolgreiche Vorinventar unverändert.

## Abbruch, Sperre und Aufräumen
Die Sperre ist das Verzeichnis `.taeglich.lock` mit der Prozess-ID des Laufs. Nach `SIGKILL`, OOM-Kill oder Neustart
läuft der Aufräum-Trap nicht. Der nächste Lauf erkennt eine Sperre, deren Prozess nicht mehr existiert, übernimmt sie
und meldet `Hinweis: Veraltete Sperre …`. Er startet dann Keycloak (falls der Abbruch zwischen Anhalten und Start
lag) und entfernt verwaiste `.arbeit.*`-Verzeichnisse, die Klartext enthalten können. Gehört die Prozess-ID einem
lebenden Prozess, bricht der Lauf mit Exit-Code `3` ab. Nach einem Neustart kann eine alte ID von einem fremden Prozess
belegt sein; dann hilft nur, `.taeglich.lock` von Hand zu entfernen, nachdem man geprüft hat, dass kein Lauf aktiv ist.

## Zeitsteuerung
Die tägliche Ausführung (Timer oder Cron auf der VPS, vor dem Abholfenster des Infra-Repositorys) ist nicht Teil dieses
Skripts und gehört zum Infra-Teil von Aufgabe 5.5.

Shell-Tests mit Attrappen für Docker und `age` sowie, wenn `age` und `age-keygen` installiert sind, einem Lauf mit
echtem `age` und zwei Wegwerf-Identitäten:

```sh
bash deploy/backup/test/taeglich-test.sh
```

Für diese Shell-Tests gibt es derzeit keinen CI-Job; sie müssen lokal ausgeführt werden.

## Lokale Restore-Übung

Der lokale Prüflauf erstellt keine Verbindung zu einem Sicherungsanbieter und verwendet keine Produktions-Secrets:

```sh
deploy/backup/restore-uebung-lokal.sh
```

Voraussetzungen sind Docker, Maven, `age`, `age-keygen`, OpenSSL, `curl` und die lokal vorhandenen Images für
PostgreSQL/pgvector, Keycloak, Java und MinIO. Das Skript erzeugt eigene Wegwerf-Schlüssel und synthetische Zugangsdaten,
startet einen Compose-Stack mit internem Servicenetz und einem getrennten Bridge-Netz für Loopback-Ports und führt den
echten `taeglich.sh`-Export aus. Die veröffentlichten Ports sind ausschliesslich an `127.0.0.1` gebunden. Es prüft `SHA256SUMS`,
weist einen falschen Schlüssel und eine manipulierte Prüfsumme zurück und entschlüsselt das Paket mit beiden
Wegwerf-Schlüsseln.

Der Lauf spielt den Archiv- und Keycloak-Dump in neue Datenbanken ein. Danach startet er Keycloak mit der
wiederhergestellten Datenbank und prüft den Realm. Der Bucket-Strom wird mit `archiv sicherung wiederherstellen`
in den neuen `urinet-archiv-p-`-Bucket eingespielt; eine absichtlich unvollständige Inventarliste ohne Manifest wird
vorher zurückgewiesen. Die Quarkus-Testanwendung startet gegen die wiederhergestellte Archivdatenbank und prüft
Dokumentanzahl, IDs, SHA-256 und Lesbarkeit der Dateien. `archiv manifest pruefen` validiert danach das signierte
Manifest im Ziel-Bucket anhand der Schlüssel und SHA-256, nicht anhand der alten S3-Versions-IDs. Die
Zeitstempelprüfung verwendet eine synthetische TSA-Attrappe ohne Netzaufruf.

Alle Daten liegen in einem temporären Verzeichnis. Beim Beenden entfernt das Skript nur den Compose-Stack mit seinem
eigenen Projektnamen und das temporäre Verzeichnis; es verwendet weder `down -v` noch `prune`. Die Übung ist ein
lokaler Funktionstest mit synthetischen Daten und ersetzt nicht die Restore-Probe auf der VPS im Infra-Repository.

Die Orchestrierung lässt sich ohne Docker und Maven mit Attrappen prüfen:

```sh
bash deploy/backup/test/restore-uebung-lokal-test.sh
```

Der lokale Lauf prüft die Sicherungs-Shell und den Restore-Kern gemeinsam, ersetzt aber nicht die spätere Restore-Probe
auf der VPS im Infra-Repository.
