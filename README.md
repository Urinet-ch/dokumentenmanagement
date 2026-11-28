# UriNet Archiv

Repository `Urinet-ch/dokumentenmanagement`. Mandantenfähiges Dokumentenarchiv mit KI-Erfassung und Chat-Suche für Urinet Aschwanden
(Mandant 1) und den Verband VHF/GSK (Mandant 2). Java/Quarkus, Angular-PWA, PostgreSQL,
Infomaniak (Schweiz).

## Spec-driven Development im Kiro-Stil, umgesetzt mit Claude Code
Die Specs folgen dem Kiro-Format; umgesetzt wird mit Claude Code, nicht mit dem Werkzeug Kiro.
- `CLAUDE.md` – Einstieg und Arbeitsweise für Claude Code und GitHub Copilot (bindet die Steering-Dateien ein, Parallelbetrieb)
- `AGENTS.md` – Verweis für GitHub Copilot auf `CLAUDE.md`
- `.kiro/steering/` – Produkt, Technik, Struktur (in jeder Sitzung aktiv)
- `.kiro/specs/01-archiv-kern/` – Phase 0 und 1
- `.kiro/specs/02-abgleich-chat/` – Phase 2
- `.kiro/specs/03-zweiter-mandant/` – Phase 3
- `docs/umsetzungsplan.md` – Wellen, Crew, Gates, Zeitplan, Risiken
- [Handbuch für Entwickler von Webseiten](docs/schnittstelle/handbuch.md) – serverseitige Anbindung an die Webseiten-Schnittstelle

Eine Spec ist freigegeben, wenn Fabian den PR auf `main` merged. Start: `docs/umsetzungsplan.md`, Abschnitt 3.

## Bauen und Testen
Voraussetzungen: Java 25, Maven 3.9, Node 22 mit npm, Docker mit `docker compose` (Dev Services in Tests und dev).

```bash
# Frontend-Abhängigkeiten (einmalig bzw. nach Änderungen an package-lock.json)
cd frontend && npm ci && cd ..

# Backend: Cucumber-Szenarien, JUnit, ArchUnit, Abdeckung @req; baut die PWA über Quinoa ins Artefakt
mvn -f backend/pom.xml verify

# Frontend-Unit-Tests
cd frontend && npx ng test --watch=false && cd ..

# OpenAPI-Client neu erzeugen (nach jedem Backend-Build, der die API ändert; Ergebnis committen)
cd frontend && npm run api:generieren && cd ..

# Browser-Szenarien (@browser) gegen den laufenden Build
java -jar backend/target/quarkus-app/quarkus-run.jar &
cd frontend && npx playwright install chromium && npx cucumber-js && cd ..

# Docker-Image (eines für beide Laufarten, archiv.rolle)
docker build -f backend/src/main/docker/Dockerfile.jvm -t urinet-archiv backend

# Bestehende Liquibase-Changesets unverändert gegenüber main
.github/scripts/pruefe-liquibase.sh origin/main
```

Die CI (`.github/workflows/ci.yml`) führt dieselben Schritte bei jedem PR aus (ausser bei reinen Spec-Anpassungen, siehe unten), ohne Secrets, ohne echte KI und
ohne Golden-Lauf. Die Backend-Tests verteilt sie auf fünf parallele Jobs: «Backend-Tests» (alles ohne Gruppe, ohne eigenen
Quarkus-Start, dazu ArchUnit, Abdeckung und SpotBugs) und vier «Backend-Gruppe» (`gruppe-1` bis `gruppe-4`): Klassen mit
`@TestProfile` oder `@QuarkusMainTest` je mit `@Tag("start-1")` bis `@Tag("start-4")` (erzwungen durch `StartGruppenTest`),
dazu die Cucumber-Szenarien, die zwei Runner `SzenarienTest` und `SzenarienRestTest` über Tags teilen (siehe `SzenarienShards`,
bewiesen durch `SzenarienShardTest`; Zuordnung der Gruppen in der Matrix von `ci.yml`). Der Job «Backend-Tests vollständig» prüft aus den
Surefire-Berichten, dass jede Testklasse in genau einem Job lief (`.github/scripts/pruefe-testaufteilung.py`). Das Artefakt für Browser-Szenarien und Docker-Image baut der Job «Build»
ohne Tests; dort laufen auch die Liquibase-, OpenAPI- und Drive-Import-Prüfungen. Eine neue Klasse mit eigenem Start
bekommt die Startgruppe mit der kleinsten Laufzeit-Summe. Lokal deckt `mvn verify` weiterhin alles in einem Lauf ab.
Ändert ein PR oder Push nur `.md`-Dateien unter `.kiro/` oder `docs/` (`.github/scripts/nur-spec.py`), laufen statt
dieser Jobs nur die «Spec-Prüfungen» (Tests, die Specs und Docs lesen, ohne Quarkus-Start; tech.md «Tests und
Qualität»).

Die Szenario-Prüfung «Abdeckung» verlangt für jedes Kriterium unter `_Anforderungen_` einer
abgehakten Aufgabe ab Welle 1 ein Szenario `@req-x.y` oder einen Nachweis-Vermerk in `requirements.md`.

## Entwicklung lokal

Für das Nachmessen des API-JVM-Speicherbudgets nach Quarkus- oder Abhängigkeitsupdates siehe [Speicherbudget und Metaspace-Messung](docs/speicherbudget.md).

```bash
cd frontend && npm ci && cd ..
cd backend && mvn quarkus:dev
```
- Startet über Dev Services PostgreSQL 17 mit pgvector, Keycloak mit dem Realm `urinet-archiv` aus
  `deploy/keycloak/realm-test.json` sowie MinIO (Object Lock) und Mailpit aus `backend/compose-devservices.yml`.
  Die Adressen zeigt die Dev UI unter `http://localhost:8080/q/dev-ui`. Mailpit läuft auf einem zufälligen Host-Port:
  Weboberfläche über `archiv.entwicklung.mailpit-port` (Dev UI, Configuration) oder
  `docker compose -p urinet-archiv-dev port mailpit 8025`.
- Im Profil `dev` laufen Ablage, KI, OCR und Integration als Dev-Stubs (`archiv.<port>.stub=true`).
- Profil `lokal` (nur Entwicklerrechner, ADR-012): Sprachmodell über LM Studio auf `127.0.0.1`, Start mit
  `mvn quarkus:dev -Dquarkus.profile=lokal`. Anleitung und Verbote: [backend/README.md](backend/README.md),
  «Lokales Modell (LM Studio)».
- Testbenutzer (nur im Test-Realm, nie im Export für Stufe A und B), Passwort `Test-Passwort-1`, TOTP mit dem
  Test-Secret aus `realm-test.json` (`urinet-archiv-test-otp-nur-lokal`; für eine Authenticator-App als Base32
  `OVZGS3TFOQWWC4TDNBUXMLLUMVZXILLPORYC23TVOIWWY33LMFWA`):

  | Benutzer | Rolle | Mandant |
  | --- | --- | --- |
  | `inhaber-urinet` | `inhaber` | `urinet` |
  | `mitarbeiter-urinet` | `mitarbeiter` | `urinet` |
  | `treuhaender-urinet` | `treuhaender` | `urinet` |
  | `inhaber-vhf-gsk` | `inhaber` | `vhf-gsk` |
  | `geschaeftsstelle-vhf-gsk` | `geschaeftsstelle` | `vhf-gsk` |

- Secrets liegen nur als Dateien vor (`/run/secrets/`, tech.md Regel 7), nie in Umgebungsvariablen oder im Repo. Die
  festen Zugangswerte der Dev-Container und des Test-Realms sind keine Secrets und gelten nur lokal.

## Mandant anlegen
```bash
java -jar quarkus-run.jar mandant anlegen <code> [--name <name>] [--geschaeftsjahr-ende MM-TT] [--rollen <rolle,…>]
```
- Das Kommando startet keinen HTTP-Server und keine Start-Migration, braucht aber die Secret-Dateien `archiv_db_app`,
  `archiv_db_owner`, `s3_app_zugang_id`, `s3_app_zugang_geheimnis` und `infomaniak_ai_token` unter `/run/secrets/`; fehlt eine oder ist sie
  leer, bricht der Start ab. Seed: `seed/<code>/konfiguration.json`. Ohne `--geschaeftsjahr-ende` gilt das
  Geschäftsjahresende aus dem Seed (`einstellungen.geschaeftsjahrEnde`, für `urinet` und `vhf-gsk` `12-31`).
- `--rollen` setzt die vergebbaren Verwaltungsrollen als kommagetrennte Liste aus `inhaber`, `mitarbeiter`,
  `treuhaender` und `geschaeftsstelle`; ohne Option gelten die Rollen der Mandantenkonfiguration.
- Der Mandant wird erst nach Seed, Schema-Prüfung und Bucket aktiv. Bricht ein Lauf ab, bleibt er inaktiv im Register;
  derselbe Aufruf noch einmal setzt fort (mit abweichendem Namen, Geschäftsjahresende oder Rollensatz:
  `MANDANT_EXISTIERT`). Ohne `--rollen` bleibt der eingetragene Rollensatz erhalten.
- Exit-Code 0 angelegt, 1 fachlicher Fehler (`MandantFehler`, z. B. `MANDANT_EXISTIERT`), 2 falscher Aufruf (geprüft
  vor dem Start, mit Aufrufhilfe), 3 technischer Fehler, auch ein abgebrochener Start (design.md, «Mandant»).
- Ein neuer Mandant hat den Bereich Wissen nicht (Spec 04, Req 22.1); freigeschaltet wird er danach mit
  `mandant wissen <code> ein` (nächster Abschnitt).

## Wissen je Mandant freischalten
```bash
java -jar quarkus-run.jar mandant wissen <code> [ein|aus]
```
- Schaltet den Bereich Wissen (Wissensbasis, Wissenschat, Wissensquellen) für einen Mandanten ein oder aus (Spec 04,
  Requirement 22, Zusatzleistung). Ohne `ein` oder `aus` gibt das Kommando nur den Zustand aus. Auf der VPS als
  Admin-Kommando (unten); ein anderer Weg (Oberfläche, REST, API-Token, Konfigurationsdatei, Einstellungen) existiert
  nicht, auch nicht für die Rolle `inhaber`.
- Die laufende App übernimmt die Änderung ohne Deploy und ohne Neustart nach spätestens 60 Sekunden. Ausgeschaltet
  ist der Bereich in der App unsichtbar, die Schnittstelle antwortet auf Wissenspfade mit 403
  `WISSEN_NICHT_FREIGESCHALTET`, der öffentliche Wissenschat ist aus, und die Wissensbasis ruft keine KI auf; laufende
  und wartende Aufträge enden mit dem Grund `WISSEN_AUSGESCHALTET`. Es werden keine Daten gelöscht: Seiten, Versionen,
  Entwürfe, Log, Markierungen, Einstellungen und Webfreigaben bleiben und sind nach dem Einschalten wieder sichtbar.
  Nach dem Einschalten startet nichts von selbst: einen Ingest von Hand wiederholen, der Lint läuft zum nächsten Termin.
- Jede Änderung steht im Zugriffsprotokoll des Mandanten mit Akteur `betreiber` und Aktion `WISSEN_FREIGESCHALTET`
  bzw. `WISSEN_ABGESCHALTET`, ohne Inhalte. Entspricht der Zustand schon dem Wunsch, ändert und protokolliert das
  Kommando nichts.
- Exit-Code 0 ausgeführt oder unverändert, 1 Mandant unbekannt oder nicht aktiv, 2 falscher Aufruf (geprüft vor dem
  Start, mit Aufrufhilfe), 3 technischer Fehler; ausser bei 0 ändert sich nichts.
- Bei der Einführung (Migration) behält `urinet` Wissen, jeder andere bestehende Mandant hat es nicht.

### Admin-Kommandos auf der VPS
Nie per `docker exec` im laufenden API- oder Keycloak-Container: Eine zweite JVM sprengt dessen `mem_limit`
(Keycloak wurde so zweimal beendet, Erkenntnis 12.11.2026). Admin-Kommandos laufen in einem Wegwerf-Container mit den
Einstellungen der API (Image, Secrets, `JAVA_OPTS_APPEND`, `mem_limit`), ohne abhängige Dienste zu starten und ohne
Anmeldung bei Traefik (`traefik.enable=false`):
```bash
sudo docker compose --project-directory /srv/apps/archiv -f /srv/apps/archiv/compose.yml \
  run --rm --no-deps -T -l traefik.enable=false api \
  sh -c 'exec java $JAVA_OPTS_APPEND -jar /deployments/quarkus-run.jar <kommando>'
```
- `<kommando>` z. B. `mandant anlegen urinet --name "UriNet Aschwanden"` oder
  `mandant konfiguration einspielen <code> <datei> --wer <name> --grund <text>`; der Exit-Code des Kommandos ist der
  von `docker compose run`. Der Name geht in den Prompt des KI-Vorschlags ein: Nur mit genau «UriNet Aschwanden» (und
  den Seed-Arten) entspricht die Prompt-Kennung der von Gate 0 (`v6-7a39224f2f5e`), sonst warnt das Log bei jedem
  Aufruf.
- Riskante Einstellungsänderungen beim Einspielen mit `--bestaetigt <schluessel,…>` ausdrücklich bestätigen; ohne
  Bestätigung wird nichts geändert und das Kommando endet mit einem Fehler. Das Betreiberkommando verlangt kein
  Benutzer-Step-up.
- Dateien der Konfiguration: Der Wegwerf-Container sieht keine Dateien des Hosts. `exportieren <code>` ohne `<datei>`
  schreibt auf die Standardausgabe (Logs gehen auf stderr), also `… <kommando> > konfiguration.json`. Für `pruefen`,
  `vergleichen` und `einspielen` die Datei nur lesend einbinden, z. B. `run … -v "$PWD/konfiguration.json:/arbeit/konfiguration.json:ro" …`
  und `/arbeit/konfiguration.json` als `<datei>` angeben. Gelesen wird höchstens 1 MiB; ein Export in eine Datei
  überschreibt nie und legt sie mit Rechten 600 an.
- Der Wegwerf-Container belegt für einige Sekunden ein zweites `mem_limit` der API; nicht während einer Messung des
  Speicherbudgets (Aufgabe 1.10) starten.
- Keycloak-Vorlagen mit `kcadm.sh` (Passkey-Realm 24.3, Verwaltungsclient und SMTP 19.3) laufen im selben Muster, aber mit dem
  Dienst `kc` und `--entrypoint bash` (kopierbare Aufrufe, `jq` und die Anmeldung ohne Passwort im Terminal:
  `deploy/infomaniak/keycloak/README.md`, «Passkey-Realm nachrüsten»).

## Embeddings nachberechnen
```bash
java -jar quarkus-run.jar embedding nachberechnen <code> [--nur-fehlende]
```
- Reiht für den Mandanten den Auftrag `EMBEDDING_ALLE` ein; der Worker zerlegt danach jedes archivierte Dokument neu in
  Abschnitte und bettet sie ein (Spec 02, Req 2.4). Mit `--nur-fehlende` nur Dokumente ohne Abschnitte oder mit
  Abschnitten ohne Embedding (z. B. nach einem Lauf mit Stub oder ohne Token). Auf der VPS als Admin-Kommando (oben).
- Läuft für den Mandanten noch eine Nachberechnung, reiht das Kommando nichts ein und meldet «läuft bereits» mit der
  Auftrags-ID; ein erneuter Aufruf ist also gefahrlos.
- Exit-Code 0 eingereiht oder läuft bereits, 1 Mandant nicht angelegt oder nicht aktiv, 2 falscher Aufruf,
  3 technischer Fehler.

## API-Token für Skripte (Generator-Client, Drive-Import)
Ein API-Token `ua_<id>_<geheimnis>` gilt für genau einen Mandanten und einen Scope (`generator` oder `import`) und
erreicht nur die Endpunkte dieses Scopes (Req 2.5). Das Archiv speichert nur den Argon2id-Hash; das Token wird nur beim
Anlegen ein einziges Mal angezeigt. Anlegen, Auflisten und Widerrufen darf nur der `inhaber` (A14, Req 2.20): in der
Verwaltung (`/api/v1/api-tokens`) oder per Kommando (auf der VPS im Wegwerf-Container wie `mandant anlegen`, siehe
«Admin-Kommandos auf der VPS»; stdout in eine neue Datei mit Rechten 600 umleiten und erst bei Erfolg an ihren Platz
schieben, damit ein Fehlschlag kein gültiges Token überschreibt):
```bash
mkdir -p ~/.config/urinet-archiv
(umask 077; java -jar quarkus-run.jar token erstellen <mandant> <name> <generator|import> > token.neu) \
  && mv token.neu ~/.config/urinet-archiv/token
```
Auf der VPS (das Token entsteht dort in `token.neu` und geht danach wie unten beschrieben an den Client):
```bash
(umask 077; sudo docker compose --project-directory /srv/apps/archiv -f /srv/apps/archiv/compose.yml \
  run --rm --no-deps -T -l traefik.enable=false api \
  sh -c 'exec java $JAVA_OPTS_APPEND -jar /deployments/quarkus-run.jar token erstellen <mandant> <name> <generator|import>' \
  > token.neu)
```
- Das Token steht nur auf stdout; die Zeile mit ID, Mandant und Scope und das Log gehen auf stderr. Exit-Codes wie bei
  `mandant anlegen` (0 angelegt, 1 fachlicher Fehler, 2 falscher Aufruf, 3 technischer Fehler); nach einem Fehler
  `token.neu` löschen.
- Entsteht das Token nicht auf dem Client-Rechner (Kommando auf dem Server, Anzeige in der Verwaltung), kommt es nur
  über einen sicheren Kanal dorthin (z. B. Passwort-Manager oder `scp`), nie per E-Mail, Chat oder Ticket; auf dem
  Client wie oben mit `umask 077` in `token.neu` schreiben und verschieben; `token.neu` auf dem Server danach löschen.
- Lokale Clients lesen das Token aus `~/.config/urinet-archiv/token` (Rechte 600) oder aus der Datei, deren Pfad
  `ARCHIV_TOKEN_DATEI` nennt – **nie** das Token selbst aus einer Umgebungsvariable (tech.md Regel 7), nie im Repo.
- Ein verlorenes oder offengelegtes Token sofort widerrufen (`DELETE /api/v1/api-tokens/{tokenId}`) und neu anlegen.

## Mail-Eingang einrichten (Spec 02, Aufgabe 6.2)
Der Worker ruft je Mandant alle 10 Minuten ein Postfach per IMAP über TLS ab (Spec 02, Requirement 1). Host, Port,
Benutzer und Zulassungsliste stehen in `integration_mail` des Mandanten, das Passwort nur verschlüsselt mit dem
Datenschlüssel `archiv_data_key` (AES-256-GCM). Ohne Postfach oder Schlüssel ruht der Mail-Eingang und sagt das einmal
im Log.

1. Datenschlüssel erzeugen (32 Byte, Base64, z. B. `openssl rand -base64 32`), im Passwort-Manager ablegen und als
   GitHub-Secret `ARCHIV_DATA_KEY` des Repos setzen (Einstellungen → Secrets and variables → Actions); der nächste
   Deploy legt ihn als Secret-Datei `archiv_data_key` ab (tech.md Regel 7). Den Schlüssel nicht wechseln, solange ein
   Postfach eingerichtet ist: Mit einem anderen Schlüssel lässt sich das Passwort nicht mehr entschlüsseln, das
   Postfach muss dann neu eingerichtet werden.
2. Nach dem Deploy das Postfach im Wegwerf-Container einrichten (wie «Admin-Kommandos auf der VPS»). Das Passwort
   kommt als erste Zeile über die Standardeingabe, nie als Argument; IMAP-Server und Port aus den Angaben des
   Mail-Anbieters (TLS, Standard 993), danach die zugelassenen Absender:
   ```bash
   IFS= read -rs MAILPASSWORT
   printf '%s\n' "$MAILPASSWORT" | sudo docker compose --project-directory /srv/apps/archiv \
     -f /srv/apps/archiv/compose.yml run --rm --no-deps -T -l traefik.enable=false api \
     sh -c 'exec java $JAVA_OPTS_APPEND -jar /deployments/quarkus-run.jar mail einrichten <mandant> <imap-host> 993 <benutzer> <absender> [<absender> …]'
   echo "exit=$?"
   unset MAILPASSWORT
   ```
   `IFS= read -rs MAILPASSWORT` allein in einer Zeile eingeben und erst danach das Passwort tippen (es erscheint
   nicht); ob es angekommen ist, zeigt `echo ${#MAILPASSWORT}` (die Länge, nicht das Passwort). `IFS=` gehört dazu:
   Ohne schneidet die Shell Leerzeichen am Anfang und Ende ab, die zum Passwort gehören, und die angezeigte Länge
   stimmt dann nicht mehr mit dem Passwort überein. Das Kommando liest die Zeile gleich beim Aufruf, bevor die
   Anwendung startet; ohne Passwort oder mit mehr als 1024 Zeichen endet es sofort mit Exit 1 («Passwort fehlt» bzw.
   «Passwort zu lang»).
   Exit-Codes wie bei `token erstellen` (0 eingerichtet, 1 fachlicher Fehler, z. B. Mandant nicht aktiv oder
   Datenschlüssel fehlt, 2 falscher Aufruf, 3 technischer Fehler). Erneut aufgerufen ersetzt das Kommando Postfach und
   Zulassungsliste; `mail abschalten <mandant>` stoppt den Abruf.
- Das Archiv legt die Ordner `Archiviert` und `Quarantaene` bei Bedarf an; verarbeitete Mails liegen in `Archiviert`,
  abgewiesene in `Quarantaene`, beide Ordner werden abonniert und Webmail zeigt sie gegebenenfalls unter dem
  Posteingang an; keine Mail wird gelöscht.
- Lag eine Mail in `Quarantaene`, weil ihr Absender fehlte: Absender mit `mail einrichten` ergänzen und die Mail im
  Mailprogramm zurück in den Posteingang schieben; der nächste Abruf erfasst sie.
- Auf Stufe A gilt die Datenregel aus `tech.md`: nur Testmails mit synthetischen Belegen, echte Belege erst ab Stufe B.

## Benachrichtigungen per Mail (Spec 01, Aufgabe 5.6)
Alarme der Überwachung (Dokument hängt, Manifest fehlt, Sicherung), gescheiterte Archivierung, Prüflauf, gestörter
Mail-Eingang und fertiger Jahresexport gehen per Mail an den Inhaber des Mandanten; Betreff und Text nennen nur IDs
(Req 16.7). Versandt wird über Hostpoint mit dem Postfach `archiv@urinet.ch` (`asmtp.mail.hostpoint.ch`, Port 587,
STARTTLS, Anmeldung; Entscheid Fabian 14.11.2026). Host, Benutzer und Absender stehen in `application.properties`;
Fabian setzt nur zwei Werte in den Einstellungen des Repos (Secrets and variables → Actions), nie im Repo selbst:
```sh
# Passwort des Postfachs archiv@urinet.ch (dasselbe wie bei der Anmeldung am Postfach); gh fragt verdeckt danach
gh secret set ARCHIV_SMTP_PASSWORT -R Urinet-ch/dokumentenmanagement
# Empfänger der Benachrichtigungen für den Mandanten urinet: genau eine Adresse
gh variable set ARCHIV_ALARM_EMPFAENGER_URINET -R Urinet-ch/dokumentenmanagement --body '<adresse>'
```
- Der nächste Deploy legt das Passwort als Secret-Datei `archiv_smtp_passwort` ab (tech.md Regel 7) und den Empfänger
  in die `.env` neben `compose.yml`; `compose.yml` reicht ihn als `ARCHIV_ALARM_EMPFAENGER_URINET` an die API weiter.
  Ist die Variable keine einzelne Adresse, bricht der Deploy vor dem Bundle ab (`.github/scripts/schreibe-env.sh`).
- Das Passwort kommt nur aus der Datei: Steht `ARCHIV_SMTP_PASSWORT` oder `QUARKUS_MAILER_PASSWORD` in der Umgebung
  der API (auch leer), bricht der Start ab (`SmtpPasswortPruefung`, wie beim Datenschlüssel).
- Fehlt einer der Werte, startet das Archiv trotzdem: Ohne Passwort steht beim Start einmal «Mail-Versand:
  Secret-Datei archiv_smtp_passwort leer oder nicht vorhanden …» im Log. Ohne Empfänger steht bei jeder Meldung nur
  «… kein Empfänger konfiguriert» mit der ID im Log, ohne Passwort scheitert der Versand mit «… nicht versandt
  (<Ausnahme>)». Einen Alarm versucht die Überwachung im nächsten Takt (15 Minuten) wieder.
- Prüfen: Beim nächsten Alarm steht im Log der API «Alarm <schlüssel> (Mandant urinet) an den Inhaber versandt» (bzw.
  «Inhaber benachrichtigt: …») statt einer der Warnungen oben, und die Mail kommt beim Empfänger an.
- **Passwort von `archiv@urinet.ch` gewechselt:** Versand und Mail-Eingang nutzen dasselbe Postfach, darum beides
  nachführen: das Secret `ARCHIV_SMTP_PASSWORT` neu setzen und neu deployen (Versand) **und** das Postfach mit
  `mail einrichten urinet …` neu einrichten (Eingang, Passwort verschlüsselt in der DB; siehe «Mail-Eingang
  einrichten»). Bis beides nachgeführt ist, kann auch die Meldung «Mail-Eingang gestört» nicht ankommen, weil sie
  über dasselbe Konto geht; dann steht der Ausfall nur im Log.

Welche Alarme es gibt, was sie bedeuten und welche Kennzahlen unter `/q/metrics` stehen, beschreibt
[docs/alarme.md](docs/alarme.md).

### Frühwarnung vor knappem Speicher
Die API prüft alle fünf Minuten Metaspace, Compressed Class Space und den Heap (nach der letzten Garbage Collection).
Über der Schwelle steht eine WARN-Zeile «Speicher … knapp» im Log, und der Inhaber erhält je Bereich und Stufe eine
Mail (erneut erst, wenn der Wert wieder unter die Schwelle fiel); die Kennzahl heisst `archiv_jvm_speicher_prozent`.
Ohne Maximum (kein `-XX:MaxMetaspaceSize`) gibt es keine Schwelle. Einstellungen (`application.properties`):
- `archiv.ueberwachung.speicher.intervall` (Standard `PT5M`, `off` schaltet den Takt ab)
- `archiv.ueberwachung.speicher.warn-prozent` (Standard 85) und `archiv.ueberwachung.speicher.kritisch-prozent`
  (Standard 95); es gilt `0 < warn < kritisch <= 100`, sonst bricht der Start ab.

## Benutzerverwaltung: normaler Weg
Benutzer und Rollen werden im Normalfall in der Verwaltungsoberfläche gepflegt. Den ersten Inhaber eines neuen Mandanten
lädt der Betreiber über das Admin-Kommando ein:
```bash
mandant inhaber-einladen <code> --benutzer <name> --email <adresse> \
  --vorname <vorname> --nachname <nachname> --wer <betreiber>
```
Das Kommando legt den Inhaber ohne Passwort an und lässt Keycloak den Einmal-Link versenden; das Passwort und der Link
erscheinen nie im Terminal.

Der Abgleich ist standardmässig schreibend nur im Benutzerverzeichnis. Mit `--pruefen` wird nichts übernommen; Keycloak
wird in beiden Fällen nie geändert:
```bash
benutzer abgleichen <code> [--pruefen] [--json]
```
Exit-Code 0 bedeutet keine Abweichung, 1 mindestens eine Abweichung, 2 falscher Aufruf und 3 technischer Fehler.

Nach der Deaktivierung eines Mandanten deaktiviert dieses Kommando dessen Keycloak-Benutzer und beendet ihre Sitzungen.
Ab 30 Tagen nach der Deaktivierung löscht ein erneuter Aufruf die Keycloak-Benutzer; Zugriffsprotokoll und
Benutzerverzeichnis bleiben bis zum Mandantenabbau erhalten:
```bash
mandant benutzer-sperren <code> [--json]
```

Diese Admin-Kommandos laufen im Wegwerf-Container wie unter «Admin-Kommandos auf der VPS» beschrieben.

## Notfallweg: Benutzer in Keycloak per kcadm.sh anlegen
Nur wenn das Dienstkonto oder der Mailversand nicht bereit ist, kann der Betreiber Benutzer im Realm `urinet-archiv`
mit `kcadm.sh` in einem Wegwerf-Container aus dem Keycloak-Image anlegen, nie per `docker exec` im laufenden `kc`.
Der Container hängt nur im internen Netz `archiv_intern`, erreicht Keycloak unter `http://kc:8080` (die Admin-Konsole
bleibt nicht öffentlich) und liest das Admin-Passwort aus der Secret-Datei. Platzhalter im Kopf des Skripts ersetzen:
```bash
sudo docker run --rm -i --network archiv_intern -m 256m --cpus 0.5 --cap-drop ALL \
  --security-opt no-new-privileges -e KC_OPTS="-Xmx96m -XX:+UseSerialGC" \
  -v /srv/apps/archiv/secrets/keycloak_admin:/run/secrets/keycloak_admin:ro \
  --entrypoint bash archiv:kc-current <<'EOF'
set -euo pipefail
BENUTZER=<benutzer>; MANDANT=<urinet|vhf-gsk>; ROLLE=<inhaber|mitarbeiter|treuhaender|geschaeftsstelle>
EMAIL=<e-mail>; VORNAME=<vorname>; NACHNAME=<nachname>
kcadm=/opt/keycloak/bin/kcadm.sh
$kcadm config credentials --server http://kc:8080 --realm master --user archiv-admin \
  --password "$(cat /run/secrets/keycloak_admin)"
$kcadm create users -r urinet-archiv -s username="$BENUTZER" -s enabled=true \
  -s email="$EMAIL" -s firstName="$VORNAME" -s lastName="$NACHNAME" \
  -s "attributes.mandant=[\"$MANDANT\"]" -s 'requiredActions=["CONFIGURE_TOTP","UPDATE_PASSWORD"]'
$kcadm add-roles -r urinet-archiv --uusername "$BENUTZER" --rolename "$ROLLE"
PASSWORT=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
$kcadm set-password -r urinet-archiv --username "$BENUTZER" --new-password "$PASSWORT" --temporary
echo "Temporäres Passwort für $BENUTZER: $PASSWORT"
$kcadm get users -r urinet-archiv -q username="$BENUTZER" -q exact=true -q briefRepresentation=false \
  --fields 'username,attributes(*),requiredActions'
$kcadm get-roles -r urinet-archiv --uusername "$BENUTZER" --fields name
EOF
```
- **Mandant:** Das Attribut `mandant` wird zum Token-Claim `mandant` (tech.md Regel 1); es ist Pflicht im
  Benutzerprofil, ohne Attribut weist Keycloak den Benutzer ab (`error-user-attribute-required`). Den Mandanten vorher
  im Archiv anlegen («Mandant anlegen»).
- **Rolle:** genau eine Realm-Rolle nach der Rechtematrix (Spec 01, Requirement 2); `geschaeftsstelle` nur im Mandanten
  `vhf-gsk`.
- **Temporäres Passwort:** zufällig erzeugt, erscheint nur einmal im Terminal; nur über einen sicheren Kanal
  (Passwort-Manager) übergeben, nie per E-Mail, Chat oder Ticket. Bei der ersten Anmeldung setzt der Benutzer ein
  eigenes Passwort (`UPDATE_PASSWORD`).
- **TOTP-Pflicht:** `CONFIGURE_TOTP` verlangt die Einrichtung bei der ersten Anmeldung; der Browser-Flow
  `browser-totp-pflicht` fordert TOTP danach bei jeder Anmeldung.
- `kcadm.sh` liest die JVM-Optionen aus `KC_OPTS`, nicht aus `JAVA_OPTS`. Attribute zeigt die Benutzerliste nur mit
  `briefRepresentation=false` und dem Feldfilter `attributes(*)`.

## Leitplanken
Die Leitplanken stammen aus der Harness-Vorlage der Skill-Werkstatt (`~/git/ai-entwicklung/vorlagen/harness`) und
werden nur mit deren Werkzeug ausgerollt, nie von Hand geändert (ausser `.claude/harness.yml`, die dem Repo gehört).

```bash
brew install gitleaks          # der pre-commit-Hook bricht ohne gitleaks ab
git config core.hooksPath      # muss «.githooks» melden (setzt harness.sh)
ZIEL=$PWD ~/git/ai-entwicklung/werkzeuge/harness.sh --diff   # Drift-Check: überall «identisch»
```

- `pre-commit` prüft jeden Commit mit gitleaks; ein Commit mit Secret wird abgewiesen.
- `pre-push` sperrt `main` lokal: Branch-Schutz gibt es im GitHub-Free-Plan für private Repos nicht, darum geht
  alles über PRs (Entscheid Fabian 09.11.2026).
- Der Workflow «Secret-Scan» prüft jeden Push auf allen Branches mit gitleaks, «CI-Fehler» legt bei rotem `main` ein
  Issue an.
- Claude-Code-Hooks (`.claude/settings.json`) fragen beim Bearbeiten mit Edit/Write nach, bevor Leitplanken,
  Secret-Dateien oder geschützte Pfade aus `.claude/harness.yml` (Liquibase-Changesets, Realm-Export,
  `.github/scripts/`) geändert werden. Änderungen per Bash prüft der Hook nicht; die eigentliche Sperre ist die CI.
- Dependabot (`.github/dependabot.yml`) hält Actions (per SHA gepinnt), Docker-Images, npm und Maven wöchentlich aktuell.
