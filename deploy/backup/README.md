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

Voraussetzungen sind Docker, Maven, `age`, `age-keygen` und die lokal vorhandenen Dev-Service-Images. Der Lauf prüft
`taeglich.sh` mit Docker-Attrappen und zwei temporären `age`-Identitäten. Anschliessend starten die Quarkus-Tests
PostgreSQL und MinIO lokal, legen synthetische Dokumente an, stellen den Bucket in einen neuen leeren
`urinet-archiv-p-`-Bucket wieder her und prüfen Dokument-IDs, Prüfsummen, abrufbare Dateien, die aktualisierten
Versionsreferenzen sowie ein lesbares Manifestobjekt. Die S3-Tests prüfen zusätzlich Inventar, Object Lock und
Sperrfristen. Die Tests entfernen ihre Wegwerf-Buckets und Dev Services selbst; das Skript verwendet keinen
`docker compose down -v`- oder `prune`-Befehl.

Der Lauf ist eine lokale Teilprobe, kein vollständiger Restore des täglichen Sicherungspakets: Der Shelltest führt
`taeglich.sh` mit Docker-Attrappen aus; sein erzeugter Export wird nicht an den S3-Roundtrip-Test übergeben. Der
Roundtrip prüft synthetische Dokumentzeilen und Dateien im Test-PostgreSQL sowie einen getrennten S3-Ziel-Bucket, aber
keinen `pg_dump`-/Keycloak-Realm-Restore in eine zweite Datenbank und keinen API-Start gegen diese zweite Datenbank.
Der vollständige manuelle Restore-Nachweis bleibt damit offen und ist Aufgabe 5.12 in `tasks.md` (Export entschlüsseln,
in neue Datenbank und neuen Bucket einspielen, Archiv dagegen starten, Dokumentliste vergleichen). Die Manifest-Versions-IDs
in der Datenbank (`manifest`, `manifest_zeitstempel`) bleiben nach dem Restore auf dem Stand der Quelle (siehe
`docs/wiederherstellung.md`); der Roundtrip-Test prüft deshalb nur das lesbare Manifestobjekt im Ziel.

Die Orchestrierung lässt sich ohne Docker und Maven mit Attrappen prüfen:

```sh
bash deploy/backup/test/restore-uebung-lokal-test.sh
```

Der lokale Lauf prüft die Sicherungs-Shell und den Restore-Kern gemeinsam, ersetzt aber nicht die spätere Restore-Probe
auf der VPS im Infra-Repository.
