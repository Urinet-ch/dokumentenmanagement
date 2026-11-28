# Drive-Import (Aufgabe 4.8)

Einmaliger Import eines lokalen Ordners ins UriNet Archiv (Spec 01, Requirement 12). Das Werkzeug liest Dateien
rekursiv und lädt jede über `POST /api/v1/dokumente` hoch, mit ihrem Ordnerpfad als Hinweis. Jede Datei wird einem
Quelllauf zugeordnet. Das Archiv behandelt sie wie jeden Upload: Prüfsumme, Dublettenprüfung, Verarbeitung und
Eingangskorb. Es archiviert nie direkt (Req 12.8; Spec 09, Req 10.7).

## Bauen

```sh
mvn -B -ntp -f tools/drive_import/pom.xml verify
# ergibt tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar (Java 25)
```

## Token

Der `inhaber` stellt ein API-Token mit Scope `import` aus (`POST /api/v1/api-tokens`). Sowohl der echte Import als
auch der Trockenlauf brauchen das Token: Der Trockenlauf fragt den Archivbestand nach Prüfsummen ab und meldet den
Quelllauf samt Zählern zurück. Das Werkzeug liest das Token nur aus einer Datei, nie aus einer Umgebungsvariable,
und schreibt es nie in die Ausgabe.

```sh
mkdir -p ~/.config/urinet-archiv
# Token in die Datei schreiben, dann:
chmod 600 ~/.config/urinet-archiv/token
```

Gesucht wird der Reihe nach: `--token-datei <pfad>`, dann der Pfad aus `ARCHIV_TOKEN_DATEI`, dann
`~/.config/urinet-archiv/token`. Das Werkzeug bricht ab, wenn die Datei nicht dem aktuellen Benutzer gehört, ein
symbolischer Link ist, andere Rechte als 600 oder 400 hat (auch Ausführen) oder kein gültiges Token enthält.

## Aufruf

```sh
# Trockenlauf: fragt Prüfsummen ab und zählt; überträgt keine PDFs (Req 12.3, Spec 09, Req 10.7)
java -jar tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar --trockenlauf \
  --quelle <quellcode> "PFAD_ZUM_ORDNER"

# Import
java -jar tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar \
  --quelle <quellcode> "PFAD_ZUM_ORDNER"

# Synthetisches Beispiel für VHF/GSK; Fachgebiet als Klassifikationscode übergeben
java -jar tools/drive_import/target/drive-import-0.1.0-SNAPSHOT.jar --altbestand \
  --klassifikation fachgebiet=bad "$HOME/Altbestand-Synthetisch"
```

| Option | Bedeutung |
| --- | --- |
| `-n`, `--trockenlauf` | Prüfsummen abfragen, zählen und auflisten; keine PDFs übertragen |
| `--quelle CODE` | Quellcode aus dem Register (Standard: `drive`) |
| `--altbestand` | Reihenordner als Dokumentart verwenden; nur für VHF/GSK |
| `--archiv URL` | Adresse des Archivs (Standard `https://archiv.apps.urinet.ch`; `http` nur für `localhost`) |
| `--token-datei DATEI` | Datei mit dem API-Token |
| `--klassifikation CODE=WERT` | Klassifikationswert mit Code; wiederholbar, beim Import zusammen mit `--altbestand` erforderlich |
| `--art CODE` | Dokumentart für die Vorbelegung; nicht zusammen mit `--altbestand` |
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
- Versteckte Dateien und Systemdateien (`.DS_Store`, `desktop.ini`, `~$…`), alle Dateien in versteckten Ordnern
  (z. B. `.tmp.drivedownload`, `.tmp.driveupload` von Google Drive für Desktop) sowie symbolische Links; Links folgt
  das Werkzeug nie.

Alles, was nicht übertragen wurde, steht mit Pfad und Grund unter «Nicht übernommen», auch Dateien, die das Archiv
abgelehnt hat (z. B. zu gross, Dateityp unbekannt).

Jeder Aufruf startet einen Quelllauf. Beim Trockenlauf berechnet das Werkzeug lokale SHA-256-Prüfsummen und fragt
das Archiv in Batches von höchstens 1'000 ab; es lädt keine Dokumentdateien hoch. Der Schlussbericht enthält die
Quelllauf-ID und die vom Archiv bestätigten Zähler. Ein echter Lauf übergibt die Quelllauf-ID bei jedem Upload und
meldet beim Abschluss übersprungene Dateien und Meldungen.

## VHF/GSK-Altbestand (Aufgabe 10.5)

`--altbestand` verwendet den ersten Unterordner als Reihe und sendet den dazugehörigen Dokumentart-Code als
unbestätigte Vorbelegung. Erkannt werden `Bulletin`, `Merkblatt`, `Protokoll` und `Jahresbericht`, unabhängig von
Gross- und Kleinschreibung. Pro Datei muss genau ein Jahr von 1900 bis 2099 im Pfad oder Dateinamen vorkommen;
mehrdeutige oder fehlende Angaben sowie unbekannte Reihen werden übersprungen und begründet.

Eine Ausgabenummer erkennt das Werkzeug nur bei einem Dateinamen wie `3.pdf`, `Ausgabe-3.pdf`, `3-2026.pdf` oder
`2026-3.pdf`. Andere Namen werden weiterhin hochgeladen, aber im Bericht als «Ausgabe-Nr. nicht erkannt» gezählt.
Die Klassifikation wird mit ihrem Code übergeben, zum Beispiel `--klassifikation fachgebiet=bad`; für mehrere Werte
die Option wiederholen. `--art` ist mit `--altbestand` nicht zulässig. Im Trockenlauf ist keine Klassifikation nötig.

Der Laufbericht zählt die Dateien je Dokumentart und Jahr im Quellordner. Die Lückenprüfung ist ausdrücklich nicht
lokal nachgebildet: Massgeblich ist der serverseitige Lückenbericht. Das Import-Token darf nur `POST /dokumente`
aufrufen; die geschützte Berichtsschnittstelle verlangt eine Archiv-Rolle. Der CLI-Import gibt deshalb keine
Lückenfreiheit vor. Ein Bericht, der die Dokumente dieses konkreten Importlaufs im Eingangskorb einbezieht, bleibt
Teil der offenen Abnahme von Aufgabe 10.5.

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
