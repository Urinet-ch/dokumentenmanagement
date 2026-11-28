# Dienst `sftp` – Scan-Eingang (Prototyp, Aufgabe 27.3)
Eingang für den Netzwerkscanner nach Spec 09, design.md Abschnitt 1 und
[ADR-016](../../../docs/adr/ADR-016-scan-eingang-sftp.md): ein kleines Java-Programm auf Basis von Apache MINA SSHD,
ohne Quarkus, ohne Root, ohne Shell. Der Scanner legt über SFTP ein PDF ab; alles Weitere (Trennen, OCR, Erfassung)
macht später der Worker des Archivs (Spec 09, Welle 28).

Dieser Stand ist der **Prototyp für den Spike 27.4**: Er dient dazu, am Gerät herauszufinden, welche SSH-Verfahren,
Dateinamen und SFTP-Operationen der Scanner braucht. Er läuft nur lokal auf dem Mac im Heimnetz und gehört noch
nicht in `deploy/infomaniak/compose.yml` (Aufgaben 28.2 bis 28.4).

## Was der Prototyp kann
- Anmeldung nur mit öffentlichem Schlüssel; Passwort, Keyboard-Interactive, GSS und Host-basierte Anmeldung sind aus
  (Requirement 1.2).
- Nur das Subsystem SFTP: keine Shell, kein Befehl (`exec`), keine Weiterleitung von Ports, kein Agent, kein X11
  (Requirement 1.3).
- Je Benutzer `scan-<mandant>` eine eigene Wurzel `<wurzel>/scan-<mandant>` mit den Unterordnern `stapel`,
  `einzeln`, `glas`, beim Start angelegt; ein Pfad ausserhalb der Wurzel ist nicht erreichbar.
- Host-Schlüssel nur aus der Datei; fehlt sie, startet der Dienst nicht (er erzeugt nie selbst einen Schlüssel).
- Log als eine JSON-Zeile je Ereignis auf stdout (Requirement 1.7): Start mit den angebotenen Verfahren, je
  Verbindung die ausgehandelten Verfahren (oder bei fehlender Einigung das Angebot des Geräts), jede Anmeldung, jede
  geschriebene Datei mit Unterordner, Dateiname und Grösse, und jede SFTP-Operation, die den Eingang verändert.
  Inhalte von Dateien und Schlüssel kommen nie ins Log. Meldungen von MINA SSHD selbst (ab Warnung) gehen auf stderr.

**Noch nicht (Aufgabe 28.2):** Im Prototyp darf der Benutzer in seiner eigenen Wurzel alle SFTP-Operationen ausführen
(auch umbenennen, löschen, Verzeichnisse anlegen); das Log zeigt, welche der Scanner tatsächlich braucht. Die
Einschränkung nach Requirement 1.4, die Sperre je Quelladresse, Sitzungs- und Grössengrenzen (1.6) und der Healthcheck
kommen nach dem Spike.

## Verfahren
Standard sind die Voreinstellungen von MINA SSHD (unter anderem Ed25519, ECDSA, RSA mit SHA-2, curve25519,
AES-GCM, ChaCha20) ohne die Verfahren mit SHA-1, die MINA aus Verträglichkeit noch anbietet (`ssh-rsa`, `hmac-sha1`,
`hmac-sha1-etm@openssh.com`). Ältere Verfahren kommen nur über die Liste `ARCHIV_SFTP_AELTERE_VERFAHREN` hinzu
(kommagetrennt, Standard leer), jeweils mit der niedrigsten Priorität. Ein Name, den MINA SSHD nicht kennt, bricht
den Start ab. Welche Namen nötig sind, hält der Spike-Bericht aus Aufgabe 27.4 (`09-scanner-sftp.md` unter `docs/spikes/`) fest; nur diese
gehören später in die Konfiguration des Deploys, je mit Begründung in diesem README (Requirement 1.5).

## Konfiguration
Nur Pfade und Schalter als Umgebungsvariablen, nie Schlüssel (tech.md Regel 7):

| Variable | Standard | Zweck |
| --- | --- | --- |
| `ARCHIV_SFTP_PORT` | `2222` | Port im Container, muss über 1024 liegen |
| `ARCHIV_SFTP_WURZEL` | `/srv/eingang` | Wurzel des Eingangs (später Volume `archiv_eingang`) |
| `ARCHIV_SFTP_HOSTSCHLUESSEL` | `/run/secrets/archiv_sftp_hostkey` | privater Host-Schlüssel; mehrere Schlüssel (z. B. Ed25519 und RSA) hintereinander in einer Datei |
| `ARCHIV_SFTP_SCHLUESSEL` | `/run/secrets/archiv_sftp_schluessel` | autorisierte Schlüssel, je Zeile `scan-<mandant> <typ> <schlüssel> [kommentar]` |
| `ARCHIV_SFTP_AELTERE_VERFAHREN` | leer | ausdrücklich erlaubte ältere Verfahren |

## Bauen und testen
```sh
mvn -B -ntp -f deploy/infomaniak/sftp/pom.xml verify
# ergibt deploy/infomaniak/sftp/target/scan-eingang-sftp.jar (Java 25), Tests mit dem MINA-Client in-process
docker build -t archiv-sftp:lokal deploy/infomaniak/sftp
```
Die Szenarien `features/produktivimport/scan-eingang.feature` des Backends starten denselben Code in-process
(Quellen über `build-helper` wie beim Drive-Import); `ScanEingangVersionTest` hält die Version von MINA SSHD in
beiden Maven-Projekten gleich.

## Lokal auf dem Mac im Heimnetz starten (Spike 27.4)
Nur mit Testseiten, nie mit echten Belegen (Requirement 0.4). Schlüssel und Eingang liegen in einem Ordner ausserhalb
des Repos und werden nie committet.

1. Testschlüssel erzeugen (Docker Desktop teilt den Benutzerordner mit den Containern):
   ```sh
   mkdir -p ~/archiv-sftp-spike/eingang && cd ~/archiv-sftp-spike
   ssh-keygen -q -t ed25519 -N "" -C archiv-sftp-spike -f host-ed25519
   ssh-keygen -q -t rsa -b 3072 -N "" -C archiv-sftp-spike -f host-rsa   # falls das Gerät kein Ed25519 kann
   cat host-ed25519 host-rsa > hostkey
   ssh-keygen -lf host-ed25519.pub; ssh-keygen -lf host-rsa.pub        # Fingerabdrücke für das Profil am Gerät
   ```
2. Den öffentlichen Schlüssel des Geräts (am Scanner erzeugt und exportiert) als Zeile eintragen; für einen Test ohne
   Scanner einen eigenen Schlüssel `ssh-keygen -q -t ed25519 -N "" -f geraet` erzeugen:
   ```sh
   echo "scan-urinet $(cat geraet.pub)" > schluessel
   ```
3. Starten, Port nur an der Adresse des Macs im Heimnetz (nicht `0.0.0.0`, keine Weiterleitung im Router):
   ```sh
   docker run -d --name archiv-sftp-spike \
     -p "$(ipconfig getifaddr en0):2222:2222" \
     -v ~/archiv-sftp-spike/eingang:/srv/eingang \
     -v ~/archiv-sftp-spike/hostkey:/run/secrets/archiv_sftp_hostkey:ro \
     -v ~/archiv-sftp-spike/schluessel:/run/secrets/archiv_sftp_schluessel:ro \
     --read-only --tmpfs /tmp:size=16m --cap-drop ALL --security-opt no-new-privileges \
     --memory 96m --cpus 0.1 archiv-sftp:lokal
   docker logs -f archiv-sftp-spike
   ```
   `en0` ist das WLAN bzw. die erste Netzwerkschnittstelle; bei Ethernet über einen Adapter ggf. `en1` usw.
4. Gegenprobe vom Mac aus: `sftp -P 2222 -i geraet scan-urinet@<adresse-des-macs>`, dann `put test.pdf /stapel/`.
5. Am Scanner ein Profil «Archiv Stapel» anlegen: Ziel die Adresse des Macs, Port `2222`, Benutzer `scan-urinet`,
   Ordner `/stapel`, Host-Schlüssel bzw. Fingerabdruck aus Schritt 1. Testscan mit Testseiten senden.
6. Scheitert die Verbindung, zeigt das Ereignis `verfahren` mit `"ergebnis":"keine Einigung"` das Angebot des
   Geräts. Dann gezielt nur das fehlende Verfahren freigeben und neu starten, z. B.
   `-e ARCHIV_SFTP_AELTERE_VERFAHREN=ssh-rsa` (Signatur RSA mit SHA-1) oder `diffie-hellman-group14-sha1`.
7. Auswerten: `docker logs archiv-sftp-spike 2>/dev/null | jq -c 'select(.ereignis=="sftp" or .ereignis=="datei")'`
   zeigt Dateiname beim Schreiben, Umbenennen und weitere Operationen; `select(.ereignis=="verfahren")` die
   ausgehandelten Verfahren.
8. Aufräumen: `docker rm -f archiv-sftp-spike`, danach `~/archiv-sftp-spike` löschen (Testschlüssel).
