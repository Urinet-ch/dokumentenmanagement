# Leistungsmessung Stufe B (31.6)

Vorbereitung für Fabian bis 22.12.2026, **kein Betriebsnachweis**. Die Checkbox
31.6 bleibt offen. Grundlage: Spec 01, 3.2, 5.5, 14.3, 16.16, 16.26 und 16.30,
ADR-020 sowie die weiterhin gültigen Messbedingungen von Aufgabe 5.11.
Nur synthetische Daten; keine VPS-Adressen, Schlüssel oder Rohberichte ins Repo.

## Isoliertes Messprojekt

`deploy/last/compose.yml` definiert `archiv-last` ohne veröffentlichte Ports,
ohne Traefik und ohne externe oder produktive Volumes. `db` und `kc` bleiben
im projektspezifischen internen Netz, nur `api` besitzt einen eigenen Ausgang
zu Infomaniak. API und Worker teilen einen Container, Parallelität 1.
Limits: API 1024 MiB/0.6 CPU, Keycloak 768 MiB/0.6 CPU, DB 256 MiB/0.3 CPU.
Zusammen 2048 MiB/1.5 CPU; 96 MiB/0.1 CPU sind für `sftp` reserviert.
Der aktuelle SFTP-Prototyp wird hier nicht gestartet: Nach Aufgabe 28.3 muss
Fabian dessen freigegebene Konfiguration im selben Budget ergänzen, wenn er
zur gemessenen Stufe-B-Konfiguration gehört. Kein Zusatzbudget für Hilfscontainer.

Mandant `last` wird aus `seed/last/konfiguration.json` provisioniert, Bucket
`urinet-archiv-a-last` aus `LAST_BUCKET_PRAEFIX=urinet-archiv-a-` plus Mandant
`last`. Die Anwendung konfiguriert den Präfix, nicht den vollständigen Namen;
für diese Messung diesen Wert behalten. `archiv.stufe=A`, Sperren höchstens einen Tag,
Manifest-Modus COMPLIANCE. KI-Stub ist aktiv, OCR und S3 sind echt.

Vorbereitung durch Fabian im freigegebenen Betriebsweg:

1. Freigegebenen Commit und Images mit Digest festhalten. Alle Archiv-Dienste
   des bisherigen Projekts im Wartungsfenster anhalten; keine parallele
   Sicherung, Restore-Probe oder andere Last. Ausgangsspeicher und Health der
   anderen Apps prüfen. Projekte, Volumes und Bucket dürfen noch nicht bestehen.
2. Im geschützten Mess-Bundle ausserhalb des Repos Variablen setzen:
   `LAST_API_IMAGE`, `LAST_DB_IMAGE`, `LAST_KC_IMAGE`, `LAST_S3_ENDPOINT`,
   `LAST_S3_REGION`, `LAST_SECRETS` (nur Dateipfad), `LAST_BUCKET_PRAEFIX`.
   Secrets nur als einzelne Dateien, niemals Werte in Umgebungsvariablen.
   Eigene Mess-Zugänge, Datenbankpasswörter und SCRAM-Verifier; S3-Zugang nur
   zum Mess-Bucket. Optionale Mail-Secrets bleiben leer.
3. Bundle mit `docker compose -f deploy/last/compose.yml config --quiet`
   prüfen. Der Start auf der VPS erfolgt **nur über den Deploy-Workflow mit
   eigenem Projektziel**, nicht mit einem manuellen `compose up`.
   Der bestehende Archiv-Workflow hat noch kein Messprojekt-Ziel; dessen
   Freigabe/Einbindung ist ein Schritt für Fabian im Infra-/Pipeline-Betriebsweg,
   nicht durch diese Vorlage erledigt. Keine Produktionsroute auf das Projekt legen.
4. API für die Provisionierung noch nicht parallel starten. Im Anteil der API
   einen kurzlebigen Kommando-Container mit derselben Messkonfiguration und
   `archiv mandant anlegen last` ausführen, danach entfernen und erst dann
   den API-Dienst starten. Kein `docker exec` einer zweiten JVM in der API.
   Bucket und aktive Mandanten unabhängig prüfen: ausschliesslich `last` und
   `urinet-archiv-a-last`. Das Init des leeren DB-Volumes legt nur Messdatenbanken an.
5. Im eigenen Keycloak ausschliesslich synthetische Benutzer anlegen:
   `last-inhaber`, `last-mitarbeiter`, `last-treuhaender`; Token-Claim `mandant=last`,
   jeweils die entsprechende Rolle. Beim Generator den tatsächlichen Subject-
   Claim des synthetischen Mitarbeiters mit `--mitarbeiter-sub` einsetzen.
   Tokens als Dateien mit Rechten 600.
   Issuer ist `http://last-kc:8080/realms/urinet-archiv`, nicht der produktive Issuer.
6. Lastclient im internen Netz des Messprojekts mit Adresse `http://api:8080`
   ausführen, niemals im Netz einer anderen App. Das Konto und der Issuer müssen
   zusammenpassen. Hilfsprozesse in das Hostbudget einrechnen; keinen Docker-Socket
   in einen unkontrollierten Container einhängen.

Die Vorlage benutzt die vorhandenen gehärteten DB-/Keycloak-Images. Der Keycloak-
Wrapper benötigt `ARCHIV_BASIS_URL`; `https://archiv-last.invalid` ist absichtlich
nicht erreichbar, keine neue öffentliche Route. Eine Browseranmeldung ist damit
nicht vorbereitet; die Mess-Tokens stellt Fabian über den internen Testclient aus.

## 50'000 synthetische Suchdokumente

Vor Uploads und Fotos bei leerer Auftragsschlange und leerem Dokumentbestand:

```sh
umask 077
python3 tools/last/bestand.py /ausserhalb/bestand.sql --mitarbeiter-sub <synthetischer-subject>
# Nur im eindeutig identifizierten Mess-DB-Container:
docker compose -p archiv-last -f /messbundle/compose.yml exec -T db \
  psql -U postgres -d archiv -q < /ausserhalb/bestand.sql
python3 tools/last/bestand.py /ausserhalb/pruefung.sql --nur-pruefung
```

Keine Betriebswerte in diese Beispielpfade einsetzen und committen. SQL bricht ab,
wenn nicht ausschliesslich der Messmandant registriert ist oder Bestand/Aufträge
bereits vorhanden sind. Es verändert keine Migration. Es erzeugt deterministische
IDs, Texte, Dateinamen, Datums- und Finanzfelder und führt `VACUUM ANALYZE` aus.
Prüfung: 50'000 Dokumente, 2500 mit 20'025 Zeichen, übrige mit 525 Zeichen,
Mittel genau 1500 Zeichen; 16'666 Finanzdatensätze (ein Drittel abgerundet).
Dateinamen enthalten «Scan», «Kassenzettel», «Fotobeleg», «Ablage» und
«Einzahlung», die nicht im Volltext stehen. Die drei Vertraulichkeitsstufen
sind verteilt; Mitarbeiter sieht NORMAL und seine eigenen PERSONAL-Dokumente.

Das ist eine **Suchfixture**, kein archivierter Dateibestand: Keine Dateien,
Manifeste oder Sperren werden für diese SQL-Dokumente vorgetäuscht. Nur Suche
darauf ausführen, keinen Prüflauf oder Export als Integritätsnachweis.
Für Foto/Upload anschliessend echte synthetische Dateien über die API senden.
**Reihenfolge:** erst die Suchblöcke auf der frischen Fixture, dann Upload- und
Fotoblöcke. Für eine erneute Suchmessung ein leeres Messprojekt neu provisionieren,
statt hunderte zusätzliche Upload-Dokumente als 50'000er-Bestand auszugeben.
Vor Suchmessungen dieselbe Prüfung erneut ausführen, bevor weitere Dateien
hinzugefügt werden; danach zusätzliche OCR-Dokumente separat dokumentieren.

## Servermessung: HTTP und Durchsatz

Die vorhandenen Micrometer-HTTP-Timer liefern Serverzeit, aber ohne feste
Ziel-Buckets keinen belastbaren p95-Nachweis aus zwei Stichproben.
`ARCHIV_LAST_HISTOGRAMME=true` aktiviert deshalb ausschliesslich für den
vorhandenen Timer `http.server.requests` mit URI `/api/v1/dokumente` feste
Grenzen von 1 und 2 Sekunden. Keine neuen Labels oder Dokumentinhalte.
Standard ausserhalb des Messprojekts bleibt aus.

Nach dem Aufwärmen und unmittelbar nach jedem einzelnen Lastblock intern
`GET http://api:8080/q/metrics` speichern (neue Dateien, Rechte 600).
Zwischen den beiden Abrufen keine fremden Uploads oder Suchen. Methoden und
HTTP-Status trennt die Auswertung. Der Timer kann auch Empfang/Framework-Anteile
enthalten: Eine erfolgreiche Grenzprüfung ist konservativ; ein verfehlter Wert
ist kein Beweis, dass allein die Fachlogik zu langsam ist.

```sh
# Jeweils 20 Aufwärm-Uploads, Worker läuft mit:
python3 tools/last/upload.py --url http://api:8080 --anzahl 20 --parallel 4 \
  --groesse 200000 --json
# Jetzt vorher.prom intern speichern; Messblock ohne weiteres Aufwärmen:
python3 tools/last/upload.py --url http://api:8080 --anzahl 200 --parallel 4 \
  --groesse 200000 --json
# Jetzt nachher.prom speichern, tatsächlich beobachtete Fensterdauer einsetzen:
python3 tools/last/serverauswertung.py upload-klein /ausserhalb/vorher.prom \
  /ausserhalb/nachher.prom --fenster-s <gemessene-sekunden>
```

Zweiter Uploadblock: 20 Aufwärm-Uploads, dann 50 PDFs zu 10'000'000 Bytes
mit zwei parallelen Uploads; Auswertemodus `upload-gross`. Ziel Req 3.2:
alle angenommenen Uploads im 2-s-Bucket (die Spec nennt hier kein p95).
Auftragsfehler nach den Uploads ebenfalls prüfen; HTTP 202 allein ist keine
erfolgreiche Verarbeitung.

Suche je Rolle einzeln und einmal ohne, einmal mit tatsächlich laufendem
OCR-Auftrag: zunächst 20 Aufwärmsuchen, dann 500 Suchen mit vier parallelen
Anfragen:

```sh
python3 tools/last/suche.py --url http://api:8080 --modus volltext \
  --anzahl 20 --parallel 4 --json
# Histogramm vorher aufnehmen:
python3 tools/last/suche.py --url http://api:8080 --modus volltext \
  --anzahl 500 --parallel 4 --json
# Histogramm nachher aufnehmen:
python3 tools/last/serverauswertung.py suche /ausserhalb/vorher.prom \
  /ausserhalb/nachher.prom --fenster-s <gemessene-sekunden>
```

Ziel Req 14.3: mindestens 475 der 500 Antworten im 1-s-Bucket.
`serverauswertung.py` berichtet p95 als **Bucket-Obergrenze**, nicht als exakten
Messwert; Durchsatz ist Anzahl geteilt durch die beobachtete Fensterdauer,
ohne erfundenen Grenzwert. Exit 0: Ziel erfüllt, 1: verfehlt, 2: ungültiger
Nachweis (Fehlerantwort, falsche Anzahl, fehlende Buckets, Zählerreset).
Clientwerte und deren Exit-Code sind nur Begleitinformationen.
Vor jedem Rollenblock `ARCHIV_TOKEN_DATEI` auf die zugehörige geschützte Token-Datei
setzen, etwa `export ARCHIV_TOKEN_DATEI=/ausserhalb/last-inhaber.token`, danach
entsprechend auf `last-mitarbeiter.token` und `last-treuhaender.token`.
Die Umgebungsvariable enthält nur den Pfad, niemals das Token.

## Fotos, HEIC, Scan und Bildweg

Bestehende Generator-Abhängigkeiten aus `tools/golden_synth/requirements.txt`
in isolierter Python-Umgebung installieren (einschliesslich `pillow_heif`).

```sh
python3 tools/last/bilder_stufe_b.py /ausserhalb/messbilder-neu
```

Erzeugt 12-MP-JPEG, HEIC derselben Aufnahme, 20-seitigen Bildscan und
dreiseitiges farbiges Bild-PDF. Kein Upload, kein Netzwerk. Neuer Ordner
ausserhalb des Repos, Rechte 700, Dateien 600; unvollständigen Ordner nicht verwenden.
HEIC ist verlustbehaftet, hat aber dieselben Masse und denselben Bildinhalt.

Je JPEG und HEIC sowie JPEG neben einem noch nicht hochgeladenen 20-seitigen
Scan 20 Aufwärmläufe und 50 Messläufe nacheinander:

```sh
python3 tools/last/verarbeitung_foto.py --basis-url http://api:8080 \
  --foto /ausserhalb/messbilder-neu/handyfoto-12mp.jpg --anzahl 50 --aufwaermen 20 \
  --csv /ausserhalb/jpeg-client.csv --ids /ausserhalb/jpeg-ids.json
# HEIC: gleicher Aufruf mit .heic und neuen Ausgabepfaden.
# Begleitlast: zusätzlich --begleit-pdf <neuer-20-seiten-scan>.
```

Vor jedem normalen Fotolauf Auftragsschlange leer. Bei Begleitlast muss der
Scan tatsächlich in `VERARBEITUNG` beobachtet werden; das Fotowerkzeug prüft
mindestens eine Überlappung, nicht dauerhafte Überlappung aller 50 Läufe.
Für Suchlast den Scanstatus vor und während des Lastblocks unabhängig
beobachten und Zeiten dokumentieren; fehlt beobachtete OCR, Block wiederholen
mit neuem Scan. Keine künstliche Verzögerung als Nachweis echter OCR.

Serverzeit aus vorhandenen DB-Zeitstempeln: vom Start des `VERARBEITEN`-
Auftrags bis zum Ende des `KI_VORSCHLAG`-Auftrags. Bei aktivem KI-Stub ist keine
KI-Netzwartezeit enthalten; die Zwischenwarteschlange und Abschlussarbeiten
zählen konservativ mit. Kein Abzug aus `ki_verbrauch` (enthält keine Dauer).

```sh
# Zuerst tatsächliche Laufzeitkonfiguration prüfen: KI-Stub true, OCR-Stub false.
python3 tools/last/auftrags_export.py /ausserhalb/jpeg-ids.json \
  /ausserhalb/jpeg-export.sql --ki-stub-bestaetigt
docker compose -p archiv-last -f /messbundle/compose.yml exec -T db \
  psql -U postgres -d archiv -q < /ausserhalb/jpeg-export.sql > /ausserhalb/jpeg-server.csv
python3 tools/last/auftragsauswertung.py /ausserhalb/jpeg-server.csv
```

Nur die 50 Messfoto-IDs, keine Aufwärm- oder Scan-IDs. Export und Auswertung
verlangen zwei erfolgreiche Aufträge ohne Fehlversuch und Status EINGANGSKORB.
Ziel Req 5.5: p95 höchstens 30 Sekunden. Die Bestätigung des KI-Stubs ist eine
Selbstauskunft nach tatsächlicher Prüfung, kein automatischer Konfigurationsnachweis.
Für isolierte OCR-/Renderzeit liefern diese DB-Werte **keinen** separaten Messpunkt.

Das dreiseitige PDF zusätzlich über die API erfassen und mit echtem KI-Bildweg
bei Infomaniak verarbeiten lassen: KI-Stub dafür separat deaktivieren und
den Mess-Token als Datei einhängen. Nicht in die Foto- oder Suchmessung mischen.
Seitenbild ja/nein, Renderdauer und `archiv.ki.render-zeitgrenze` im Bericht
festhalten. **Offen:** automatisierte Erhebung der Renderdauer/des Bildweg-
Ergebnisses und Hybridsuche mit echten Embeddings/Reranker (Spec 02, 2.6);
die reine KI-Stub-Konfiguration belegt diese nicht.

## Abbruch, Bericht und Rückbau

Vor und während jeder Phase Limits, verfügbaren Hostspeicher, Speicherabbrüche
auf der VPS und Health anderer Apps unabhängig prüfen. Überschreitung von
2144 MiB/1.6 CPU, nicht manuell verursachter OOM oder kranke andere App:
Req 16.18, Messlast und Archiv auf Stufe A stoppen, Fabian informieren.
Wechsel erst nach seinem Entscheid über Budget/Plattform. Manuell verursachten
OOM mit Datum dokumentieren. Ungeklärte Health/Isolation: keine weitere Last.

**Verfehltes Leistungsziel allein:** Ergebnis Fabian als Entscheid vorlegen,
kein automatischer Stopp und keine Aufrüstung (16.17, 16.26).
Werkzeuge starten/stoppen keine Archiv-Dienste. Bericht: Commit, Zeitpunkt,
VPS-Grösse, gemessenes `MemTotal`, Dienste/Limits, Datenverteilung, Rollen,
KI-Modus, Warm-up, Messfenster, OCR-Überlappung, p95/Grenzbuckets, Durchsatz,
Fehler, Abbrüche und Bildweg. Rohdaten geschützt ausserhalb des Repos.

Nach Messende im freigegebenen Betriebsweg nur `archiv-last` stoppen und seine
Container entfernen. Aufräumen der eigenen Volumes `archiv_last_daten`,
`archiv_last_arbeit` und projektspezifischen Netze anhand vorher geprüfter
Zuordnung; keine Wildcards, kein Docker-Prune. Mess-Bucket frühestens einen
Tag nach letztem Upload von allen Versionen leeren und löschen, ohne
Bypass-Header und ohne Verkürzen der Sperren. Mess-Secrets und Tokens sicher
verwerfen; normalen Archiv-Stack im Betriebsweg wieder starten, Health und
andere Apps prüfen. Rückbau im Messbericht bestätigen.

Tests ohne VPS:
`python3 -m unittest discover -s tools/last -p 'test_*.py'`.
Mit `LAST_TEST_DB_IMAGE=<lokal-vorhandenes-pgvector-pg17-image>` läuft zusätzlich
der SQL-Trockenlauf: eigene PostgreSQL-Instanz ohne Netz und veröffentlichte
Ports, 50'000er-Bestand samt Prüfung und Foto-Auftragsexport. Er entfernt nur
seinen Container und dessen anonymes Datenvolume; kein Image-Pull oder Prune.
Compose wird nur normalisiert, HEIC dekodiert und das dreiseitige PDF gerendert.
Das ersetzt keinen vollständigen API-/S3-/Keycloak-Messlauf; dafür fehlen lokal
ein freigegebenes isoliertes Deploy-Ziel und dessen separate Mess-Zugänge.

`docs/betrieb/leistungsmessung-ci.diff` ist ein **nicht angewendeter** Vorschlag:
Der bestehende CI-Schritt startet nur `test_last.py`. Der Patch lässt zusätzlich
die neuen Python-Tests samt vorhandenen Generator-Abhängigkeiten in einer eigenen
Umgebung laufen; der optionale PostgreSQL-Trockenlauf bleibt lokal.
Workflow-Dateien werden durch diese Vorbereitung nicht geändert.
