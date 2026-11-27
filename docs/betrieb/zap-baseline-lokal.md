# ZAP-Baseline im lokalen Testmodus

Die Vorbereitung prüft ausschliesslich einen wegwerfbaren Teststack mit synthetischen Daten.
Sie ersetzt **nicht** die Staging-Prüfung durch Fabian nach Spec 03, Aufgabe 12.3.

## Voraussetzungen und Aufruf

Docker, Java 25 und Maven müssen lokal verfügbar sein. Der Maven-Cache muss die
Abhängigkeiten des Backends enthalten. Das Skript arbeitet im Testcontainer offline und liest
nur `~/.m2/repository`; es verwendet leere Maven-Einstellungen und kopiert keine vorhandenen
Einstellungen oder Zugangsdaten. Vor dem Containerstart löst es bei Bedarf mit leeren
Einstellungen das Linux-Brotli-Native für die Docker-Architektur auf (`native-linux-aarch64`
oder `native-linux-x86_64`). Das ist nötig, weil die App-Abhängigkeit auf dem Mac sonst das
macOS-Native auswählt, während der Testcontainer Linux ausführt. Nur für diese Vorauflösung
wird Maven Central vom Host kontaktiert; im Testcontainer bleibt Maven offline und der
Anwendungstest ohne externes Netzwerk. Andere fehlende Maven-Artefakte meldet Maven mit ihren
Koordinaten; sie können vor einem erneuten Lauf auf dem Host mit
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
Es startet PostgreSQL, ein Quarkus-Testprofil mit Ablage-, Fake-Keycloak- und
KI-Attrappen und ZAP in einem gemeinsamen Docker-Netzraum ohne externes Netzwerk.
Der Testserver lauscht ausschliesslich auf `127.0.0.1`; das Scan-Ziel wird nicht vom
Benutzer übernommen. ZAP importiert nacheinander die lokale App-OpenAPI-Beschreibung
und den separaten Webseiten-Vertrag `docs/schnittstelle/public-v1.yaml` und führt mit
`-S` nur den Baseline- beziehungsweise passiven Scan aus.

Zusätzlich zum unangemeldeten Scan prüft ZAP `/api/v1/*` mit einem kurzlebigen,
synthetisch signierten Fake-Keycloak-Token mit den Rollen `inhaber` und `mitarbeiter`.
Der Webseiten-Vertrag wird zusätzlich je einmal mit einem neu erzeugten Lese-Schlüssel
der Stufen `oeffentlich`, `mitglieder` und `vorstand` geprüft. Die Tokens und Schlüssel werden nur im wegwerfbaren Teststack erstellt, in einem temporären
Arbeitsverzeichnis ausserhalb des Repositories gespeichert und vor der Ausgabe der ZAP-Berichte
aus allen Berichten und Logs entfernt. Danach prüft das Skript automatisiert sämtliche
Berichts- und Logdateien erneut auf jeden synthetischen Zugang und bricht ab, falls einer
zurückgeblieben ist.
Die temporäre ZAP-Konfiguration wird mit Rechten 600 angelegt und über die
Standardeingabe an den Scanner übergeben; auch dort wird sie mit Rechten 600
als Scannerbenutzer angelegt.
Sie werden weder ausgegeben noch in Repository-Dateien übernommen. Das Arbeitsverzeichnis
wird beim Beenden des Skripts entfernt. Die Datenbank und die zugehörigen Container werden
anschliessend entfernt; der Testserver stoppt spätestens nach 40 Minuten.

Der Lauf ist ausschliesslich auf Loopback und das isolierte Docker-Netz beschränkt.
Proxy-Umgebungsvariablen werden im ZAP-Container geleert. Die Authentisierungs-Ersetzung ist
auf Loopback-Ziele des Testservers begrenzt und greift nicht auf ZAPs eigene API-Steueraufrufe.
Er darf nicht für VPS- oder Staging-Hosts umkonfiguriert werden und ersetzt nicht
Fabians Staging-Prüfung nach Spec 03, Aufgabe 12.3.
## Ergebnisse und Grenzen

Am 27.11.2026 entstanden in drei lokalen Versuchen keine vollständigen Baseline-Ergebnisse:
der authentisierte OpenAPI-Import brach jeweils ab, bevor alle sechs Scanläufe abgeschlossen waren.
Die automatische Token-Gegenprobe bestand in allen drei Läufen. Es gibt deshalb keine
Regelnummer-/Schweregrad-/Anzahl-Tabelle für diese Versuche.

`testserver.log` steht im Ausgabeverzeichnis. Die Unterverzeichnisse `app`,
`webseite`, `app-authentisiert`, `webseite-oeffentlich`,
`webseite-mitglieder` und `webseite-vorstand` enthalten jeweils `bericht.json`,
`bericht.md`, `bericht.html`, `zap.log` und `zap.out`. Berichte können synthetische
Anfrage- und Antwortdaten enthalten und gehören nicht ins Repo. Ergebnisse ausserhalb
der Berichte nur aggregiert festhalten: ZAP-Regelnummer, Schweregrad und Anzahl der
Befunde, ohne URLs mit IDs, Request-/Response-Inhalte, Zugangsdaten oder andere
Nutzdaten.

Exit-Code 0 bedeutet keine Warnung, 1 mindestens ein fehlgeschlagenes Kriterium,
2 mindestens eine Warnung und höhere Werte einen Ausführungsfehler.
Eine fehlende Berichtsdatei oder ein fehlerhafter Testserver gilt ebenfalls als Fehler.

Der unangemeldete Scan prüft geschützte Funktionen nicht vollständig. Die
authentisierten passiven Scans erfassen nur die Vertragsrouten, die in den importierten
OpenAPI-Beschreibungen stehen; sie führen keine aktiven Angriffe oder vollständige
fachliche Rechteprüfung aus. Der lokale Teststack ersetzt weder die Staging-Baseline
noch die Szenarien zur Isolation. Bekannte Fehlalarme stehen mit Regelnummer und Begründung in
`tools/sicherheit/zap-ausnahmen.conf`. Die zugehörigen Regeln bleiben aktiv, damit
andere Befunde derselben Regeln nicht pauschal ausgeblendet werden.
