# Drive-Import (Aufgabe 4.8)

Einmaliger Import des Google-Drive-Altbestands ins UriNet Archiv (Spec 01, Requirement 12). Das Werkzeug liest einen
lokalen Ordner von «Google Drive für Desktop» rekursiv und lädt jede Datei über `POST /api/v1/dokumente` hoch, mit
ihrem Ordnerpfad als Hinweis. Das Archiv behandelt die Dateien wie jeden Upload: Prüfsumme, Dublettenprüfung,
Verarbeitung und Eingangskorb. Es archiviert nie direkt (Req 12.8).

## Bauen

```sh
mvn -B -ntp -f tools/drive_import/pom.xml verify
# ergibt tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar (Java 25)
```

## Token

Der `inhaber` stellt ein API-Token mit Scope `import` aus (`POST /api/v1/api-tokens`). Das Werkzeug liest
das Token nur aus einer Datei, nie aus einer Umgebungsvariable, und schreibt es nie in die Ausgabe.

```sh
mkdir -p ~/.config/urinet-archiv
# Token in die Datei schreiben, dann:
chmod 600 ~/.config/urinet-archiv/token
```

Gesucht wird der Reihe nach: `--token-datei <pfad>`, dann der Pfad aus `ARCHIV_TOKEN_DATEI`, dann
`~/.config/urinet-archiv/token`. Das Werkzeug bricht ab, wenn die Datei nicht dem aktuellen Benutzer gehört, ein
symbolischer Link ist, Rechte für Gruppe oder andere hat oder kein gültiges Token enthält.

## Aufruf

```sh
# Trockenlauf: zählt und listet auf, liest kein Token, überträgt nichts (Req 12.3)
java -jar tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar --trockenlauf "$HOME/Google Drive/UriNet"

# Import
java -jar tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar "$HOME/Google Drive/UriNet"
```

| Option | Bedeutung |
| --- | --- |
| `-n`, `--trockenlauf` | nur zählen und auflisten |
| `--archiv URL` | Adresse des Archivs (Standard `https://archiv.apps.urinet.ch`; `http` nur für `localhost`) |
| `--token-datei DATEI` | Datei mit dem API-Token |
| `-h`, `--hilfe` | Hilfe |

Exit-Codes: `0` alles übertragen oder schon vorhanden, `1` mindestens eine Datei abgelehnt, `2` Aufruf oder
Token-Datei ungültig, `3` Lauf abgebrochen (Token abgelehnt, 401/403).

Ein zweiter Lauf über denselben Ordner ist unschädlich: Dateien, die schon im Archiv liegen, zählen als «schon
vorhanden» (Req 12.4). Antwortet das Archiv mit 503 oder 429, wartet das Werkzeug (`Retry-After`, höchstens 60 s) und
versucht es bis zu fünfmal.

## Hinweis aus dem Ordnerpfad (Req 12.2)

Jede Datei bekommt den Hinweis `Drive-Import; Ordner: <pfad>`, dazu erkannte Muster. Die KI wertet den Hinweis aus
(ab Aufgabe 3.2); das Werkzeug setzt selbst keine Metadaten.

| Ordner | Zusatz im Hinweis |
| --- | --- |
| `Kunden/<Kunde>/Rechnungen/…` | `Partner: <Kunde>; Art: AUSGANGSRECHNUNG` |
| `…/Offerten/…` (irgendwo im Pfad) | `Art: OFFERTE` |
| `Kunden/<Kunde>/…` | `Partner: <Kunde>` |
| `Buchhaltung <Jahr>/…` | `Bereich: Buchhaltung; Jahr: <Jahr>` |

## Was nicht übertragen wird (Req 12.5, 12.6)

- Google-Formate (`.gdoc`, `.gsheet`, `.gslides` u. a.): Liste «Bitte als PDF exportieren».
- Office-Dateien (`.docx`, `.xlsx`, `.pptx`, `.odt`, `.rtf` u. a.), E-Mails (`.eml`, `.msg`) und Archive (`.zip`,
  `.7z`, `.rar` u. a.): in Phase 1 nicht übernommen.
- Versteckte Dateien und Systemdateien (`.DS_Store`, `desktop.ini`, `~$…`) sowie symbolische Links.

Alles, was nicht übertragen wurde, steht mit Pfad und Grund unter «Nicht übernommen», auch Dateien, die das Archiv
abgelehnt hat (z. B. zu gross, Dateityp unbekannt).

## Schlussbericht (Req 12.7)

```
Gefunden:        7
Übertragen:      1
Schon vorhanden: 2
Übersprungen:    3
Abgelehnt:       1
Kontrolle: 7 = 1 + 2 + 3 + 1 (geht auf)
```

## Tests

- Unit-Tests im Werkzeug: `mvn -B -ntp -f tools/drive_import/pom.xml verify`.
- Szenarien gegen das echte Archiv (Dev Services): `backend/src/test/resources/features/importe/drive-import.feature`.
  Das Backend kompiliert die Quellen des Werkzeugs dafür als Testquellen mit (`build-helper-maven-plugin`).
