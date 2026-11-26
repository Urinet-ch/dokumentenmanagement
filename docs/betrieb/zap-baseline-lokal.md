# ZAP-Baseline im lokalen Testmodus

Die Vorbereitung prüft ausschliesslich einen wegwerfbaren Teststack mit synthetischen Daten.
Sie ersetzt **nicht** die Staging-Prüfung durch Fabian nach Spec 03, Aufgabe 12.3.

## Voraussetzungen und Aufruf

Docker, Java 25 und Maven müssen lokal verfügbar sein. Der Maven-Cache muss die
Abhängigkeiten des Backends einschliesslich der Linux-Abhängigkeiten für die Architektur
des Docker-Hosts enthalten. Das Skript arbeitet im Testcontainer offline und liest nur
`~/.m2/repository`; es verwendet leere Maven-Einstellungen und kopiert keine vorhandenen
Einstellungen oder Zugangsdaten. Fehlt ein Artefakt,
meldet Maven dessen Koordinaten; dieses kann vor einem erneuten Lauf auf dem Host mit
`mvn -Dquarkus.http.test-port=0 dependency:get -Dartifact=<Koordinaten>` im Backend-Worktree
geladen werden.

Im Worktree ein neues, leeres Ausgabeverzeichnis angeben:

```sh
tools/sicherheit/zap-baseline-lokal.sh /absoluter/pfad/zum/leeren-verzeichnis
```

Das Ausgabeverzeichnis muss ausserhalb des Repositories liegen; das Skript prüft den
kanonischen Pfad und weist auch Symlinks ins Repository zurück. Das Ziel darf neu sein,
sein übergeordnetes Verzeichnis muss bereits existieren. Berichte werden nicht im
Repository abgelegt.

Das Skript lädt das nach Plattform per Digest gepinnte OWASP-ZAP-Image bei Bedarf.
Temurin-JRE, Temurin-JDK und pgvector sind ebenfalls unveränderlich per
Multi-Arch-Digest gebunden.
Es startet PostgreSQL, ein Quarkus-Testprofil mit Ablage-, Keycloak- und KI-Attrappen
und ZAP in einem gemeinsamen Docker-Netzraum ohne externes Netzwerk. Der Testserver
lauscht ausschliesslich auf `127.0.0.1`; das Scan-Ziel wird nicht vom Benutzer übernommen.
ZAP importiert nacheinander die lokale App-OpenAPI-Beschreibung und den separaten
Webseiten-Vertrag `docs/schnittstelle/public-v1.yaml` und führt mit `-S` nur den
Baseline- beziehungsweise passiven Scan aus. Die Datenbank und die zugehörigen Container werden
anschliessend entfernt. Der Testserver stoppt spätestens nach 15 Minuten.

## Ergebnisse und Grenzen

`testserver.log` steht im Ausgabeverzeichnis; die Unterverzeichnisse `app` und
`webseite` enthalten jeweils `bericht.json`, `bericht.md`, `bericht.html`, `zap.log`
und `zap.out`. Die ZAP-Berichte ordnen Befunde nach Schweregrad; sie können auch
synthetische Anfrage- und Antwortdaten enthalten und gehören nicht ungeprüft ins Repo.
Exit-Code 0 bedeutet keine Warnung, 1 mindestens ein fehlgeschlagenes Kriterium,
2 mindestens eine Warnung und höhere Werte einen Ausführungsfehler.
Eine fehlende Berichtsdatei oder ein fehlerhafter Testserver gilt ebenfalls als Fehler.

Der Scan läuft ohne Anmeldung und prüft deshalb geschützte Funktionen nicht vollständig.
Im dokumentierten Testlauf antworteten alle geprüften Webseiten-Endpunkte mit 401;
der Lauf belegt dort ausschliesslich das Verhalten ohne Schlüssel.
Der lokale Teststack ersetzt weder eine authentisierte Sicherheitsprüfung noch die
Staging-Baseline. Bekannte Fehlalarme stehen mit Regelnummer und Begründung in
`tools/sicherheit/zap-ausnahmen.conf`. Die zugehörigen Regeln bleiben aktiv, damit
andere Befunde derselben Regeln nicht pauschal ausgeblendet werden.
