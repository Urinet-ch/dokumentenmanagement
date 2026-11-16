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
| `ARCHIV_AGE_EMPFAENGER_SICHERUNG` | ja | Öffentlicher `age`-Empfänger des Sicherungsschlüssels (`age1…`) |
| `ARCHIV_AGE_EMPFAENGER_PROBE` | ja | Öffentlicher `age`-Empfänger des Probe-Schlüssels (`age1…`) |
| `ARCHIV_API_SERVICE` | nein | API-Dienst, Standard `api` |
| `ARCHIV_DB_SERVICE` | nein | Datenbankdienst, Standard `db` |
| `ARCHIV_KEYCLOAK_SERVICE` | nein | Keycloak-Dienst, Standard `kc` |
| `ARCHIV_DB_NAME` | nein | Archiv-Datenbank, Standard `archiv` |
| `ARCHIV_KEYCLOAK_DB_NAME` | nein | Keycloak-Datenbank, Standard `keycloak` |
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
   nächsten Lauf aktualisiert.

Die Quarkus-Adminbefehle laufen mit `docker compose exec` im vorhandenen API-Container und
bleiben damit innerhalb seines Speicherlimits. Für den konsistenten Realm-Export wird Keycloak
kurz angehalten und ein Wegwerf-Container verwendet. Die Shell-Skripte transportieren keine
Exporte an einen Backup-Anbieter.

## Aufruf und Fehlercodes

`taeglich.sh` hat keine Positionsargumente. Fehlende oder ungültige Konfiguration führt zu
Exit-Code `2`; technische Fehler, ein belegter Lauf oder ein fehlgeschlagener Export führen zu
Exit-Code `3`; ein vollständig abgeschlossener Lauf endet mit `0`. Abbruch durch `SIGINT` und
`SIGTERM` liefert `130` beziehungsweise `143`. Fehler werden auf stderr gemeldet. Vor dem
erfolgreichen Registereintrag fehlt `FERTIG`; solche Läufe sind nicht zur Abholung freigegeben.
Bei einem Fehler in diesem Schritt bleibt das zuletzt erfolgreiche Vorinventar unverändert.

Shell-Tests mit Attrappen für Docker und `age`:

```sh
bash deploy/backup/test/taeglich-test.sh
```

Für diese Shell-Tests gibt es derzeit keinen CI-Job; sie müssen lokal ausgeführt werden.
