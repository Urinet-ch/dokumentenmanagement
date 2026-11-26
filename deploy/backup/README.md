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

Für die Sicherung beider Mandanten muss `ARCHIV_MANDANTEN` die Codes `urinet` und `vhf-gsk`
enthalten; `taeglich.sh` gleicht dazu vor dem Export alle Mandantenschemas `m_*` der Datenbank mit der Liste ab
und bricht mit Exit 2 ab, wenn ein Mandant fehlt (kein stilles Überspringen). `taeglich.sh` erstellt Inventar und Bucket-Export je Mandant; die lokale Restore-Übung
stellt und prüft beide Mandanten (die Mandantenliste der Übung ist bewusst fest verdrahtet: Sie prüft genau
die zwei Mandanten der Sollausstattung, nicht die Konfiguration des Betriebs). Transport der Läufe, Zweitkopie und `backup-gate.sh` gehören
zum Infra-Repository und sind nicht Teil dieser Skripte.

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
Arbeitsverzeichnis, weil `/tmp` des Dienstes ein 32-MiB-tmpfs ist, das mit dem Container verschwindet. Ab Keycloak 26.8
bricht `kc.sh export` ab, wenn `KC_BOOTSTRAP_ADMIN_USERNAME` ohne Passwort gesetzt ist; der Wegwerf-Container entfernt
die Variable darum vor dem Export. Der Realm-Export läuft in der lokalen Restore-Übung echt; der Lauf gegen die
Compose-Umgebung der VPS ist Teil der Inbetriebnahme (Infra). Die Shell-Skripte transportieren keine
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

Voraussetzungen sind Docker, Maven, `age`, `age-keygen`, OpenSSL, `curl` und die lokal vorhandenen Images. Das Skript
liest die erwarteten Images aus `compose config --images` und den Dockerfiles unter `deploy/infomaniak/` (PostgreSQL
per Digest wie im Deploy); fehlt eines, bricht es mit Exit-Code `2` und dem passenden `docker pull` ab. Es erzeugt eigene
Wegwerf-Schlüssel und synthetische Zugangsdaten und führt den echten `taeglich.sh`-Export aus.

Der Compose-Stack (`deploy/backup/test/restore-compose.yml`) hat zwei Netze. Quell-Keycloak und API hängen nur am Netz
`intern` (`internal: true`) und veröffentlichen keine Ports; ihre HTTP-Prüfungen laufen per `compose exec` aus dem
Datenbank-Container. PostgreSQL und MinIO hängen zusätzlich am Netz `hostzugang` und sind an `127.0.0.1` veröffentlicht,
weil Maven-Test und Wiederherstellungskommando vom Host aus auf sie zugreifen. Das wiederhergestellte Keycloak erhält
ebenfalls einen zufälligen Loopback-Port, damit der Maven-Test mit einem Token aus dem wiederhergestellten Realm die
geschützte API aufrufen kann. Vor dem Export legt die Übung im temporären Quell-Realm dafür einen synthetischen
Dienstkonto-Client mit Mandanten-Mapper und Rolle an. Seine JSON-Dateien werden mit `jq` erzeugt und vor dem
Keycloak-Aufruf geprüft. Der Client und sein Dienstkonto bleiben ausschliesslich in den Wegwerf-Datenbanken;
Realm-Dateien, TOTP-Pflicht und Produktionsflows werden nicht geändert. Die OIDC-Issuer-URL des Ziel-Realm ist an den
Loopback-Port gebunden. Die Daten von PostgreSQL und MinIO liegen in tmpfs; es entstehen keine Volumes. Keycloak und
die API haben ein `mem_limit` von 768 MiB, Keycloak dazu eine Heap-Grenze; ohne sie beendet der OOM-Killer einer
knappen Docker-VM Keycloak.

Das temporäre Verzeichnis entsteht unter `${TMPDIR:-/tmp}`. Läuft Docker in einer VM, die nur das Home-Verzeichnis
einbindet (z. B. Colima unter macOS), muss `TMPDIR` auf ein Verzeichnis darunter zeigen:

```sh
TMPDIR="$HOME/tmp" deploy/backup/restore-uebung-lokal.sh
```

Der Lauf prüft `SHA256SUMS` (`--strict`, jede Datei genau einmal),
weist einen falschen Schlüssel und eine manipulierte Prüfsumme zurück und entschlüsselt das Paket mit beiden
Wegwerf-Schlüsseln.

Der Lauf spielt den Archiv- und Keycloak-Dump in neue Datenbanken ein. Danach startet er Keycloak mit der
wiederhergestellten Datenbank und prüft den Realm über den Issuer seiner OIDC-Konfiguration. Der Maven-Test fordert
ein `client_credentials`-Token für das Dienstkonto im wiederhergestellten Realm an und ruft damit einen geschützten
API-Endpunkt auf. Der Passwort-Grant ist für diesen synthetischen Test ungeeignet, weil der Direct-Grant-Flow TOTP
verlangt; darum verwendet der Funktionstest ein Dienstkonto, ohne TOTP-Pflicht oder Produktionsflows zu ändern. Der
Bucket-Strom wird mit `archiv sicherung wiederherstellen`
in den neuen `urinet-archiv-p-`-Bucket eingespielt; eine absichtlich unvollständige Inventarliste ohne Manifest wird
vorher zurückgewiesen. Die Quarkus-Testanwendung startet gegen die wiederhergestellte Archivdatenbank und prüft
Dokument-, Seiten- und Versionszahlen gegen die Quelle, IDs, SHA-256 und Lesbarkeit der Dateien. Zusätzlich prüft
sie, dass weder das Dokument noch Versionen oder Löschmarken seines Originals und PDF/A aus dem anderen Mandanten
im jeweiligen Ziel vorhanden sind. Dies wird für beide Mandanten geprüft.
`archiv manifest pruefen` validiert danach das signierte
Manifest im Ziel-Bucket anhand der Schlüssel und SHA-256, nicht anhand der alten S3-Versions-IDs, und gleicht es mit
dem in der Datenbank festgehaltenen Hash ab; eine zusätzliche, veränderte Fassung im Ziel-Bucket lässt die Prüfung
scheitern. Die
Zeitstempelprüfung verwendet eine synthetische TSA-Attrappe ohne Netzaufruf.

Alle Daten liegen in einem temporären Verzeichnis. Beim Beenden entfernt das Skript nur den Compose-Stack und die
Testimage-Tags mit seinem eindeutigen Projektnamen sowie das temporäre Verzeichnis; es verwendet weder `down -v`
noch `prune`. Generierte OpenAPI-Dateien werden ebenfalls im temporären Verzeichnis statt im Arbeitsbaum abgelegt.
Die Übung ist ein
lokaler Funktionstest mit synthetischen Daten und ersetzt nicht die Restore-Probe auf der VPS im Infra-Repository.

Die Orchestrierung lässt sich ohne Docker und Maven mit Attrappen prüfen:

```sh
bash deploy/backup/test/restore-uebung-lokal-test.sh
```

Der lokale Lauf prüft die Sicherungs-Shell und den Restore-Kern gemeinsam, ersetzt aber nicht die spätere Restore-Probe
auf der VPS im Infra-Repository.

## Vorschlag für den betrieblichen Zählvergleich

`docs/betrieb/restore-zaehlvergleich-workflow.diff` ist ein noch nicht integrierter Vorschlag für den Infra-Workflow.
Er vergleicht pro Mandant die Zahlen für `seiten`, `versionen` und `dokumente` und bricht bei fehlenden, ungültigen
oder abweichenden Daten ab. Erwartet werden zwei JSON-Objekte der Form
`{"mandant": {"seiten": 1, "versionen": 2, "dokumente": 3}}` unter `out/archiv-quelle.json` und
`out/archiv-restore.json`.

Der bestehende Infra-Workflow stellt noch kein Archiv wieder her und erzeugt diese Dateien nicht. Vor der Integration
muss eine separate Archiv-Restore-Probe die Quellzahlen aus dem Sicherungszeitpunkt und die Zielzahlen aus der
unabhängig wiederhergestellten Datenbank liefern; zwei Dateien allein beweisen diese Herkunft nicht. Der Vorschlag ist
deshalb zunächst nur über die manuelle Option aktivierbar und schlägt ohne diese Voraussetzung fehl. Für den
wiederkehrenden Betrieb müssen Probe, Zählquellen und Zeitsteuerung ergänzt und auf Stufe B nachgewiesen werden;
Spec 04, Aufgabe 13.7 bleibt offen.
