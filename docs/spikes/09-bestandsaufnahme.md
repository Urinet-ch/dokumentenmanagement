# Bestandsaufnahme für Spec 09 Produktivimport (Requirement 0.1)

Stand 28.11.2026, `main` bei Commit `ba3cf6cfa`. Die Vorlage der Spec entstand ohne Einblick ins Repo; diese
Aufnahme prüft jeden vorausgesetzten Baustein gegen den Code und hält fest, was die Spec deshalb anders macht.
Bestätigt mit dem Merge von #745 am 28.11.2026 (Aufgabe 27.1).
Pfade: J = `backend/src/main/java/ch/urinet/archiv/`, M = `backend/src/main/resources/db/mandant/`,
P = `backend/src/main/resources/db/public/`.

## Ergebnis in Kürze
- **Umgesetzt und wiederverwendet:** Upload mit SHA-256 und Dublettenergebnis, interner Erfassungsweg, Auftragsschlange,
  OCR/PDF-A, KI-Vorschlag und Eingangskorb, Generator-Schnittstelle und `tools/archiv_client.py`, Drive-Import mit
  Trockenlauf und Token-Datei, CSV-Import mit `import_lauf`, Manifest und Prüflauf, E-Mail-Eingang D7 (`mail_eingang`),
  Datenschlüssel AES-GCM (`archiv_data_key`), Jahresexport, Chat-Werkzeug `vollstaendigkeit`, Lückenbericht mit den
  Regeln `FOLGE` und `ERWARTET`, Benachrichtigung des Inhabers, PDFBox, ZXing, Angus Mail, GreenMail, Testcontainers.
- **Teilweise:** Vollständigkeit (Lücken ja, Quellen, Eingangskorb, Stapel und Begründungen nein), Prüfsummen-Abfrage
  (nur als Ergebnis des Uploads), Token-Scope für den Mac-Eingang (`import` passt, braucht zwei Endpunkte mehr).
- **Fehlt:** SFTP-Dienst, Stapel und Trennblätter, Quellenregister und Quellläufe, Gmail-Import (nur lesend),
  Dateinamen-Regeln, `belegnummer_alias`, `dokument_beziehung` und Hauptfassung, Textähnlichkeit, Testmandant,
  Apache MINA SSHD.
- **Neue Hürden, die die Vorlage nicht kannte:** Speicherbudget Stufe A ist voll belegt (E11); `ports:` ist in der
  Compose-Datei verboten und die Compose-Policy der VPS erlaubt nur die EntryPoints `web` und `websecure` (E10);
  OpenSSH mit Chroot verträgt die Härtung nicht (ADR-016); der Dateiname darf nicht in Logs und Prompt (Spec 01, 3.12);
  echte Belege erst nach Entscheid #731 (Spec 01, 16.15 und 16.17).

## Bausteine im Einzelnen
| # | Baustein | Stand | Pfad | Befund | Folge für Spec 09 |
| --- | --- | --- | --- | --- | --- |
| 1 | Upload mit SHA-256 und Dublette (Spec 01, 3.4) | umgesetzt | J `dokument/adapter/in/rest/DokumentUploadResource.java`, J `dokument/application/DokumentErfassen.java` | Antwort 202 mit `status` `NEU`, `DUBLETTE` oder `ABGELEHNT` je Datei, kein 409; Feld `hinweis` (höchstens 1'000 Zeichen); `archiv.upload.max-datei` 50 MB | Mac-Eingang wertet `status` aus (Vorlage: 409 `DUBLETTE`) |
| 2 | Interner Erfassungsweg | umgesetzt | J `dokument/application/DokumentErfassen.java` (`imKontextErfassen`, `korrespondenzErfassen`), genutzt von J `integration/application/mail/MailErfassen.java` | Erfasser mit Quelle; kein eigener Weg nötig | Vorlage: `DokumentService.erfassenIntern` gibt es nicht |
| 3 | Herkunft `dokument.quelle` | umgesetzt | M `changesets/` (Constraint `dokument_quelle_ck`) | Werte `SCAN, UPLOAD, GENERATOR, DRIVE, EMAIL, ABANINJA, WEBSEITE`; Token ergibt `DRIVE` | neu nur `GMAIL`, `ORDNER` (Vorlage: `SCAN` neu, `MAC`) |
| 4 | Auftragsschlange | umgesetzt | P `changesets/`, J `verarbeitung/application/` | `AuftragTyp` als Record mit Muster, 3 Versuche mit Wartezeit | neue Typen nach design.md |
| 5 | OCR, PDF/A, Bibliotheken | umgesetzt | J `verarbeitung/adapter/out/ocr/OcrMyPdfAdapter.java`, `backend/pom.xml` | PDFBox 3.0.8 (Mail-Text als PDF), ZXing 3.5.4 (Swiss QR, J `ki/adapter/out/qr/ZxingQrLeser.java`), Angus Mail, GreenMail, Testcontainers; MINA SSHD fehlt | nur MINA SSHD neu |
| 6 | KI-Vorschlag, Eingangskorb, Statusmodell | umgesetzt | J `dokument/` | Status `NEU` bis `VERWORFEN`, Bestätigen A7, Verwerfen A9 | Dubletten-Entscheid über A7 und A9 statt neuer Rechte |
| 7 | Generator-Schnittstelle, Client | umgesetzt | J `dokument/adapter/in/rest/`, `tools/archiv_client.py` | 409 `BELEGNUMMER_VERGEBEN`, 200 bei gleicher SHA-256; Client mit `--nachsenden`, kein `nachlauf` | `nachlauf` neu, wertet 409 und 200 als «schon vorhanden» |
| 8 | Drive-Import | umgesetzt | `tools/drive_import/` | Java, Trockenlauf, Token-Datei mit Rechten 600, Ordner-Hinweis, Wiederholung bei 429/503; Zähler nur im Bericht; keine Prüfsummen-Abfrage | Mac-Eingang als Unterbefehl dieses Werkzeugs (Vorlage: neues Werkzeug mit Java 21 und Schlüsselbund) |
| 9 | CSV-Import 21.CRM | umgesetzt | J `importe/` | `import_lauf` mit Zählern und Status | Quelle `crm-csv` verweist auf `import_lauf` |
| 10 | Manifest, Prüflauf, Ablage | umgesetzt | J `protokoll/application/ManifestErzeugen.java`, J `ablage/domain/DateiSchluessel.java`, J `ablage/adapter/out/s3/S3Ablage.java` | Präfixe `eingang/`, `archiv/`, `manifest/`; Sperre über `putObjectRetention`; Manifest aus `dokument_datei` | Stapel-Original braucht Erweiterung der Manifest-Einträge |
| 11 | E-Mail-Eingang D7 (Spec 02, 1) | umgesetzt | M `changesets/` (`mail_eingang`, `integration_mail`), J `integration/adapter/in/job/MailTakt.java` | IMAP-Abruf mit Verschieben nach `Archiviert`/`Quarantaene`, Zulassungsliste, `archiv.mail.max-groesse` | Gmail nutzt den Adapter nur lesend und `mail_eingang`; Rückfall E6 per E-Mail ohne neuen Dienst |
| 12 | `belegnummer_alias` | fehlt | – | nur in Spec 02, design.md (AbaNinja, entfallen) | Tabelle in Spec 09 |
| 13 | Datenschlüssel AES-GCM | umgesetzt | J `sicherheit/adapter/out/krypto/` | AES-256-GCM, Secret `archiv_data_key`, genutzt für das Mail-Passwort | App-Passwort Gmail darüber |
| 14 | Jahresexport (Spec 02, 7) | umgesetzt | J `export/` | ZIP mit PDF/A, `belegliste.csv`, Prüfsummen, Manifeste | `vollstaendigkeit.*` und `beilagen/` als Ergänzung |
| 15 | Chat-Werkzeuge, Lückenbericht | umgesetzt | J `ki/application/chat/WerkzeugAuswahl.java`, J `ki/adapter/in/rest/LueckenberichtResource.java` | Werkzeug `vollstaendigkeit`, `GET /berichte/luecken` | Vollständigkeitsbericht baut darauf auf |
| 16 | API-Token-Scopes | umgesetzt | J `sicherheit/domain/ApiTokenScope.java` | `generator`, `import`, `webseite-lesen`, `webseite-erfassung`; `import` darf nur `POST /dokumente` | kein Scope `upload`; `import` erhält Prüfsummen und Quellläufe |
| 17 | Dokumentbeziehungen, Hauptfassung | fehlt | – | nur `dokument_version`, mehrere Originale je Dokument | `dokument_beziehung`, `hauptfassung` neu |
| 18 | Textähnlichkeit | fehlt | – | `pg_trgm` nur für Kontaktnamen | SimHash neu |
| 19 | Regeln `FOLGE`, `ERWARTET` (Spec 05) | umgesetzt | J `konfiguration/domain/Regel.java`, `backend/src/main/resources/seed/urinet/konfiguration.json` | Seed `urinet`: Ausgangsrechnungen `FOLGE`, Lohnabrechnungen und Bankbelege `ERWARTET` | keine neuen Regeltypen (Vorlage: `NUMMERNFOLGE`, `MONATLICH`, `FORTLAUFEND`); zwei Regeln `FOLGE` ergänzen |
| 20 | Benachrichtigung des Inhabers | umgesetzt | J `verarbeitung/application/InhaberBenachrichtigung.java` | Mail-Adapter vorhanden | neue Anlässe |
| 21 | Scheduler im Worker | umgesetzt | J `integration/adapter/in/job/MailTakt.java` | Rolle `worker`, Überspringen vor der Migration | `ScanEingangTakt` nach diesem Muster |
| 22 | Compose und Härtung | umgesetzt | `deploy/infomaniak/compose.yml`, `backend/src/test/java/ch/urinet/archiv/gemeinsam/DeployHaertungTest.java`, `tools/pruefe_compose_konsistenz.py` | Dienste `api` (1024m, 0.6), `db` (256m, 0.3), `kc` (768m, 0.6) = 2048 MiB und 1.5 CPU, also Budget voll; `ports`, `cap_add`, `privileged` verboten; Volumes `archiv_daten`, `archiv_arbeit` | E10, E11; Dienst `sftp` ohne Root mit MINA SSHD (Vorlage: OpenSSH und eine Compose-Datei `docker-compose.yml` unter `deploy/`) |
| 23 | Rechtematrix | umgesetzt | J `sicherheit/domain/Aktion.java` | höchste Zeile A24 (Spec 01, 2.6) | neue Zeilen A25 bis A29 |
| 24 | Testmandant | fehlt | `backend/src/main/resources/seed/` | nur `urinet` und `vhf-gsk` | Probelauf in eigenem Testmandanten (Aufgabe 29.10) |
| 25 | Frontend | umgesetzt | `frontend/src/app/` | Seiten u. a. `importe`, `eingangskorb`, `verwaltung` (ohne Stapel, Quellen, Trennblätter) | neue Seiten nach Gestaltungsleitfaden |
| 26 | SFTP, Stapel, Trennblatt, Quellläufe | fehlt | – | Begriff «Stapel» nur als Losgrösse des CSV-Imports | alles neu |

## Weitere Abweichungen der Vorlage vom Repo
- Spec-Nummer `04` ist vergeben (`04-wissensbasis`), `08` durch den offenen PR #728; neu `09`.
- Wellen 13 bis 16 sind vergeben, auf `main` gibt es Wellen bis 26; neu 27 bis 30.
- ADR-007 bis ADR-010 sind vergeben; neu ADR-016 bis ADR-019.
- Migrationen mit Liquibase (`db/mandant`, `db/public`), nicht Flyway.
- Paketstruktur hexagonal nach `structure.md` und ArchUnit; Fachbereiche `stapel/`, `vollstaendigkeit/` neu,
  Quellen in `importe/`, Dubletten in `dokument/`, Gmail in `integration/` (Vorlage: `scan`, `trennblatt`, `quelle`,
  `dublette` als eigene Pakete).
- Verfahrensbeschreibung liegt unter `docs/verfahrensbeschreibung-erfassen-archivieren.md` und ist nach Spec 01, 16.6
  auf 2 Seiten begrenzt (Vorlage: `verfahrensbeschreibung.md`, 3 Seiten).
- Token-Datei statt macOS-Schlüsselbund (tech.md Regel 7).
- Konfigurationsschlüssel stehen in Specs nur als Text, bis es sie im Code gibt (`EinstellungsInventarTest`).
- Rechte: Die Vorlage gab die Stapel-Ansicht auch `mitarbeiter`; ein Stapel enthält aber Seiten aller
  Vertraulichkeitsstufen, darum folgt A25 den Zeilen A3 und A4.
- Geschäfts- und Betriebsangaben (Gerät, Adressen, Belegnummern, Namen von Quellen) stehen nur als Platzhalter.

## Vorgeschlagene Reihenfolge bei fehlenden Bausteinen
Kein zwingender Baustein muss aus einer anderen Spec vorgezogen werden. Vor dem Dienst `sftp` in der Compose-Datei
brauchen es die Entscheide E10 und E11; der Spike (Aufgabe 27.4) läuft lokal im Heimnetz und braucht beides nicht.
Scheitert der Spike, ist der E-Mail-Eingang ohne neuen Dienst als Rückfall nutzbar (Aufgabe 28.11).
