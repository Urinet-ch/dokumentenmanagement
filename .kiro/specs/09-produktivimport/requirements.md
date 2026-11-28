# Requirements Document — 09 Produktivimport

## Introduction
Diese Spec bringt das UriNet Archiv in den Produktivbetrieb: Ab dem Stichtag läuft jeder neue Beleg über das
Archiv, und der Bestand 2026 wird vollständig übernommen. Neu sind ein Scan-Eingang per SFTP für den
Netzwerkscanner (`<scanner>`), der Stapelscan mit QR-Trennblättern, das Entfernen leerer Seiten, das
Stapel-Original als prüfbares Archivobjekt, ein Quellenregister mit Quellläufen, der Gmail-Import über ein Label,
ein Mac-Eingangsordner, Dateinamen-Regeln, fachliche Dubletten mit Hauptfassung und ein Vollständigkeitsbericht.

Grundlage ist die «Konzepterweiterung Produktivimport» vom 28.11.2026 (Claude-Dokument, für Claude-Code-Sitzungen
nicht erreichbar; massgeblich ist diese Spec) und der Auftrag von Fabian vom 28.11.2026. Die Konzept-IDs der
Erweiterung (D9 bis D13, I7 bis I10, Q1 bis Q3, B5, B6) stehen in Klammern hinter der Anforderung. Die Spec wurde
zuerst ohne Einblick ins Repo geschrieben und danach gegen den Stand von `main` (Commit `ba3cf6cfa`) angepasst; die
Bestandsaufnahme steht in `docs/spikes/09-bestandsaufnahme.md`.

Die Spec baut auf Spec 01 (Upload 3, Verarbeitung 5, Ablage 6, KI-Vorschlag 7, Eingangskorb 8, Generatoren 11,
Drive-Import 12, CSV-Import 13, Manifest 15, Betrieb 16), Spec 02 (E-Mail-Eingang 1, Chat-Werkzeug
`vollstaendigkeit` 3.6, Jahresexport 7), Spec 05 (Dokumentarten, Regeln `FOLGE` und `ERWARTET`, Lückenbericht 5.4)
und Spec 06 (Mandanten-Einstellungen, Einstellungsinventar) auf. Wo diese Spec Verhalten anderer Specs erweitert,
steht das als «Spec 09 ergänzt …»; die Folgeänderungen listet design.md, Abschnitt «Folgeänderungen». Sie werden mit
den genannten Aufgaben nachgeführt, nicht mit diesem Spec-PR.

Schreibweise der Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag `@req-<x.y>`, Ausnahmen mit Vermerk
«Nachweis: manuell [Fabian]», «Nachweis: Dokument» bzw. «Nachweis: JUnit»), Problem Details und `[offen]`-Marker wie
in Spec 01. Die Szenarien liegen unter `features/produktivimport/` mit dem Tag `@spec-09`. «Spec 0n, x.y»
bezeichnet ein Akzeptanzkriterium, «Spec 0n, Aufgabe x.y» eine Aufgabe.

Betriebs- und Geschäftsangaben (Gerät, Adressen, Netzwerk, Belegnummern, Namen von Quellen) stehen in diesem
öffentlichen Repo nur als Platzhalter `<…>`; die Platzhalter-Tabelle steht in design.md. Testfälle und Fixtures sind
synthetisch.

Einordnung: Wellen 27 bis 30. Echte Belege kommen erst ins Archiv, wenn Fabian über den Start mit Echtbelegen
entschieden hat (Entscheidungshilfe PR #731, Varianten A, B und C) und die dann geltenden Voraussetzungen erfüllt
sind; bis dahin gilt Spec 01, 16.15 und 16.17 unverändert (Requirement 16).

## Glossary
- **Scan-Eingang** – der SFTP-Dienst, in den der Scanner Dateien schreibt, und das Verzeichnis, aus dem der Worker sie abholt.
- **Stapel** – eine PDF-Datei, die der Scanner in einem Durchgang liefert (bis 50 Blatt, beidseitig, also bis 100 Seiten).
- **Stapel-Original** – die Stapeldatei unverändert, so wie sie empfangen wurde.
- **Trennblatt** – Blatt mit QR-Code `URINET-TRENN;v=1[;art=<CODE>]`, beidseitig bedruckt; beginnt ein neues Dokument.
- **Ordner-Deckblatt** – Blatt mit QR-Code `URINET-ORDNER;v=1;titel=<Text>`; gibt allen folgenden Dokumenten bis zum nächsten Deckblatt einen Ordner-Hinweis.
- **Leerseite** – Seite ohne relevanten Inhalt, typischerweise die leere Rückseite beim beidseitigen Scan.
- **Seitenbilanz** – Seiten gesamt = Inhaltsseiten + Trennblattseiten + Deckblattseiten + Leerseiten.
- **Scanprofil** – Profil am Scanner; bestimmt den Unterordner im Scan-Eingang (`stapel`, `einzeln`, `glas`).
- **Quelle** – Herkunft von Dokumenten im Quellenregister (z. B. Generatoren, Drive, Mac-Ordner, Gmail, CRM-CSV, Papier).
- **Quelllauf** – ein Import aus einer Quelle mit Zählern, als Trockenlauf oder echt.
- **Fachliche Dublette** – derselbe Beleg in einer anderen Datei (andere Prüfsumme).
- **Hauptfassung** – die Fassung eines Belegs, die in Kennzahlen, Lückenbericht, Vollständigkeit und Export zählt.
- **Stichtag** – Tag, ab dem jeder neue Beleg über das Archiv läuft; Richtwert 01.01.2027 (E7), [offen: Stichtag – Entscheid #731 und E7].

---

### Requirement 0: Bestandsaufnahme und Spikes (K7, K10)
**User Story:** Als Entwickler möchte ich vor dem Bau wissen, welche Bausteine schon bestehen und ob der Scanner per
SFTP liefern kann, damit ich nichts doppelt baue und nicht in eine Sackgasse laufe.

#### Acceptance Criteria
1. Die Bestandsaufnahme SOLL für jeden vorausgesetzten Baustein festhalten, ob er umgesetzt, teilweise umgesetzt oder offen ist, mit Pfad im Repo und Folge für diese Spec. Nachweis: Dokument (`docs/spikes/09-bestandsaufnahme.md`).
2. Der SFTP-Spike SOLL nachweisen, dass `<scanner>` mit dem Profil «Archiv Stapel» eine Datei in einen Test-Eingang nach Requirement 1 schreiben kann, und die dafür nötigen SSH-Verfahren (Schlüsseltyp, Host-Key-Typ, Schlüsseltausch, Verschlüsselung, MAC) festhalten. Nachweis: manuell [Fabian] (Spike-Bericht).
3. Der Spike SOLL festhalten, mit welchem Dateinamen der Scanner schreibt (direkt endgültig oder temporär mit Umbenennung), welche SFTP-Operationen er dafür braucht und dass der Dateiname keinen Inhalt der Vorlage enthält (nur Präfix, Datum, Zähler). Nachweis: manuell [Fabian] (Spike-Bericht).
4. Der Spike SOLL nur mit Testseiten ohne echte Belege laufen und den Test-Eingang entweder lokal im Heimnetz oder über einen Deploy des Workflows bereitstellen; kein Agent greift dafür auf die VPS zu. Nachweis: manuell [Fabian] (Spike-Bericht).
5. FALLS der Spike scheitert oder der Weg nach 1.6 nicht freigegeben wird, DANN SOLL der Spike-Bericht den Rückfall nach Entscheid E6 benennen (Scan per E-Mail an den bestehenden E-Mail-Eingang, Spec 02, Requirement 1, oder Netzwerkordner auf dem Mac mit dem Mac-Eingang nach Requirement 8) und die betroffenen Aufgaben dieser Spec nennen. Nachweis: Dokument (Spike-Bericht und Nachtrag in design.md).

### Requirement 1: SFTP-Eingang absichern (B5)
**User Story:** Als Betreiber möchte ich einen Eingang für den Scanner, der nur Dateien annimmt und sonst nichts
kann, damit ein von aussen erreichbarer Port kein Risiko für das Archiv ist.

#### Acceptance Criteria
1. Das Archiv SOLL den Scan-Eingang als eigenen Dienst `sftp` in `deploy/infomaniak/compose.yml` betreiben, getrennt vom SSH-Zugang des Servers und vom Dienst `api` (API und Worker); der Dienst SOLL keine Secrets der API (Datenbank, Keycloak, Object Storage, KI, Datenschlüssel) erhalten und nur am internen Netz des Archivs bzw. am für E10 bestimmten Eingang hängen.
2. Der Scan-Eingang SOLL pro Mandant mit Scan-Eingang genau einen Benutzer `scan-<mandant>` haben, der sich nur mit öffentlichem Schlüssel anmelden kann; Passwort- und Keyboard-Interactive-Anmeldung SOLLEN abgeschaltet sein.
3. Der Benutzer SOLL nur das Subsystem SFTP nutzen können (keine Shell, kein Befehl, keine Weiterleitung von Ports, Agent oder X11) und auf sein Eingangsverzeichnis mit den Unterordnern `stapel`, `einzeln`, `glas` beschränkt sein (Wurzel des Benutzers, kein Pfad ausserhalb).
4. Der Benutzer SOLL keine Dateien lesen, die er nicht selbst in der laufenden Sitzung geschrieben hat, keine Verzeichnisse anlegen oder löschen, keine Links anlegen und keine Dateien löschen; Umbenennen SOLL nur innerhalb desselben Unterordners und nur erlaubt sein, wenn der Spike (0.3) es verlangt.
5. WO der Spike ältere SSH-Verfahren verlangt, SOLL das Archiv sie ausschliesslich in diesem Dienst und nur in der Liste aus dem Spike-Bericht erlauben und im README des Dienstes begründen; der SSH-Zugang der VPS bleibt unverändert.
6. Der Scan-Eingang SOLL von aussen nur über den Weg erreichbar sein, den Fabian im Infra-Repo der VPS freigibt, und nur für `<heim-adresse>`; die Compose-Datei des Archivs SOLL dafür keinen Port selbst veröffentlichen, solange die Compose-Policy der VPS das nicht erlaubt; WO keine feste Adresse vorhanden ist (K6), SOLL der Dienst die Anmeldeversuche je Quelladresse begrenzen und nach wiederholtem Fehlschlag sperren. Nachweis: manuell [Fabian] (Freigabe im Infra-Repo) und JUnit (Sperre je Quelladresse). [offen: Weg nach aussen – Entscheid E10]
7. Der Scan-Eingang SOLL jede Anmeldung (Zeit, Benutzer, Quelladresse, Ergebnis) und jede geschriebene Datei (Zeit, Benutzer, Unterordner, Grösse, vom Gerät vergebener Dateiname) in seinem Log festhalten; Inhalte der Dateien SOLLEN nie ins Log gelangen.
8. Der Dienst SOLL die Härtung der übrigen Dienste erfüllen (`cap_drop: ALL`, `no-new-privileges`, numerische uid ungleich 0, `read_only`, `mem_limit`, `cpus`, Healthcheck, Image per Digest bzw. eigenes Image aus dem Workflow) und von `DeployHaertungTest` und `tools/pruefe_compose_konsistenz.py` geprüft werden. Nachweis: JUnit (`DeployHaertungTest`).
9. Die Limits des Dienstes SOLLEN im geltenden Speicherbudget der Plattform liegen (Spec 01, 16.18, `DeployHaertungTest`, `.github/scripts/pruefe-speicherbudget.py`); FALLS das Budget dafür nicht reicht, DANN SOLL der Dienst erst nach einem Entscheid über das Budget in die Compose-Datei kommen. [offen: Budget Stufe A für den Dienst `sftp` – Entscheid E11]
10. Host-Schlüssel und autorisierte Schlüssel der Scan-Benutzer SOLLEN als Secret-Dateien unter `/run/secrets/` bereitgestellt werden (Quelle GitHub-Secrets, tech.md Regel 7), nicht im Image und nicht in Umgebungsvariablen.

### Requirement 2: Übernahme aus dem Scan-Eingang (D9)
**User Story:** Als Inhaber möchte ich, dass jeder Scan ohne weiteres Zutun im Archiv ankommt, damit ich nach dem
Tastendruck am Gerät nichts mehr tun muss.

#### Acceptance Criteria
1. WENN eine Datei im Scan-Eingang eine konfigurierbare Zeit lang (Standard 30 Sekunden) unverändert ist (Grösse und Änderungszeit), DANN SOLL der Worker sie übernehmen.
2. Der Worker SOLL Dateien ignorieren, deren Name mit einem Punkt beginnt oder einem konfigurierbaren Muster für temporäre Namen entspricht.
3. WENN der Worker eine Datei übernimmt, DANN SOLL er zuerst die SHA-256-Prüfsumme berechnen und protokollieren (tech.md Regel 3), die Datei als Stapel-Original ungesperrt unter `eingang/` im Bucket des Mandanten ablegen und sie erst danach aus dem Scan-Eingang löschen.
4. Das Archiv SOLL den Unterordner als Profil übernehmen: `stapel` → Stapelverarbeitung mit Trennung; `einzeln` und `glas` → Stapelverarbeitung ohne Trennung (ein Dokument, Leerseiten entfernt).
5. Das Archiv SOLL jedem daraus entstehenden Dokument die Quelle `SCAN`, das Profil und den Stapel als Hinweis mitgeben; den vom Gerät vergebenen Dateinamen SOLL es nicht als Dateiname des Dokuments übernehmen (Spec 01, 3.9 und 3.12: der Dateiname bleibt leer).
6. FALLS die Datei kein gültiges PDF ist oder grösser als die konfigurierte Grenze (Standard 200 MB) ist, DANN SOLL der Worker sie nach `fehler/` im Scan-Eingang verschieben, den Grund mit Stapel-Kennung protokollieren und den Inhaber benachrichtigen (ohne Inhalt und ohne Dateinamen).
7. FALLS eine Datei mit derselben Prüfsumme im Mandanten bereits als Stapel existiert, DANN SOLL das Archiv sie nicht erneut verarbeiten, sie aus dem Scan-Eingang löschen und den Vorgang als Dublette protokollieren.
8. Der Worker SOLL den Mandanten ausschliesslich aus der vom Betreiber konfigurierten Zuordnung Benutzerverzeichnis → Mandant ableiten, nie aus Dateiname oder Inhalt (tech.md Regel 1); ein Benutzer `scan-<mandant>` SOLL nie Dokumente in einem anderen Mandanten erzeugen.
9. Der Worker SOLL Stapel seitenweise verarbeiten und Arbeitsdateien im Volume `archiv_arbeit` halten, sodass ein Stapel mit 100 Seiten das Speicherbudget der API nicht überschreitet. Nachweis: manuell [Fabian] (Messung im Abnahmebericht der Aufgabe 29.10).
10. FALLS der E-Mail-Eingang als Rückfall gewählt ist (E6), DANN SOLL das Archiv Anhänge eines als Scanner zugelassenen Absenders statt als Einzeldokument nach 2.3 bis 2.8 als Stapel übernehmen (Profil `einzeln`, wenn der Betreff «einzeln» enthält, sonst `stapel`). Spec 09 ergänzt damit Spec 02, 1.2.

### Requirement 3: Trennblätter erkennen und Stapel trennen (D10)
**User Story:** Als Inhaber möchte ich einen ganzen Stapel Post mit Trennblättern in einem Durchgang scannen, damit
ich nicht jedes Dokument einzeln scannen muss.

#### Acceptance Criteria
1. WENN ein Stapel analysiert wird, DANN SOLL das Archiv auf jeder Seite nach einem QR-Code mit Präfix `URINET-TRENN;` oder `URINET-ORDNER;` suchen, auch wenn die Seite um 90, 180 oder 270 Grad gedreht ist; andere QR-Codes (z. B. Swiss QR) SOLLEN die Seite nicht zum Trennblatt machen.
2. WENN eine Seite einen Trennblatt-Code trägt, DANN SOLL das Archiv dort ein neues Dokument beginnen und die Seite nicht in ein Dokument übernehmen.
3. WENN mehrere Trennblatt- oder Deckblattseiten direkt aufeinander folgen (Vorder- und Rückseite), DANN SOLL das Archiv sie als ein Trennereignis behandeln und kein leeres Dokument erzeugen.
4. WO ein Trennblatt-Code eine Art enthält (`art=<CODE>`), SOLL das Archiv diese Art dem folgenden Dokument als vorbelegte Art mitgeben; FALLS der Code keiner aktiven Dokumentart des Mandanten entspricht, DANN SOLL das Archiv die Art ignorieren und dies im Stapelprotokoll vermerken.
5. WENN eine Seite einen Deckblatt-Code trägt, DANN SOLL das Archiv den Titel als Ordner-Hinweis an alle folgenden Dokumente bis zum nächsten Deckblatt geben.
6. Seiten vor dem ersten Trennblatt SOLLEN ein eigenes Dokument mit dem Vermerk «ohne Trennblatt» bilden, sofern sie nach dem Entfernen der Leerseiten nicht leer sind.
7. WENN die Seitenbilanz aufgeht und jedes Dokument mindestens eine Inhaltsseite hat, DANN SOLL das Archiv die Dokumente seitengenau aus dem Stapel-Original herauslösen (ohne Neuberechnung der Bilder) und für jedes den bestehenden Erfassungsweg (Prüfsumme, Dublettenprüfung nach Spec 01, 3.4, Verarbeitung, KI-Vorschlag, Eingangskorb) starten.
8. FALLS die Seitenbilanz nicht aufgeht oder ein herausgelöstes Dokument grösser als 50 MB wäre (Spec 01, 3.6), DANN SOLL das Archiv den Stapel mit Status `ANGEHALTEN` stoppen, keine Dokumente erzeugen und den Inhaber im Eingangskorb darauf hinweisen.
9. Das Archiv SOLL einen angehaltenen Stapel auf Anforderung eines Benutzers mit Recht A26 erneut analysieren oder als ein einziges Dokument übernehmen.
10. Das Archiv SOLL einen Stapel mit 100 Seiten in 95 % der Fälle innert 3 Minuten analysiert und getrennt haben (ohne OCR und KI der Einzeldokumente); das Ziel gilt wie Spec 01, 16.16 erst ab Stufe B. Nachweis: manuell [Fabian] (Messbericht).

### Requirement 4: Leerseiten entfernen (D11)
**User Story:** Als Inhaber möchte ich beidseitig scannen können, ohne dass leere Rückseiten im Archiv landen, damit
die Dokumente sauber sind.

#### Acceptance Criteria
1. Das Archiv SOLL eine Seite als leer einstufen, WENN der Anteil dunkler Pixel innerhalb des Seitenbereichs ohne einen Rand von 5 % unter dem Schwellwert (Standard 0.5 %) liegt; Seiten mit Trennblatt- oder Deckblatt-Code SOLLEN nie als leer gelten.
2. Der Schwellwert SOLL je Mandant in den Mandanten-Einstellungen (Spec 06) einstellbar sein, innerhalb fester Grenzen (0.1 % bis 3 %).
3. WENN eine Seite als leer eingestuft wird, DANN SOLL das Archiv sie aus dem Dokument entfernen und mit Seitennummer und gemessenem Anteil im Stapelprotokoll festhalten.
4. Das Archiv SOLL entfernte Leerseiten nie löschen: Sie bleiben im Stapel-Original erhalten.
5. FALLS alle Seiten eines Dokuments als leer eingestuft werden, DANN SOLL das Archiv kein Dokument erzeugen und dies im Stapelprotokoll vermerken.

### Requirement 5: Stapel-Original und Stapelprotokoll (D12)
**User Story:** Als Inhaber möchte ich nachweisen können, aus welchem Scan ein Dokument stammt, damit die Trennung
prüfbar bleibt (GeBüV).

#### Acceptance Criteria
1. Das Archiv SOLL für jeden Stapel ein Stapelprotokoll führen mit: Empfangszeit, Profil, Prüfsumme, Seiten gesamt, Inhalts-, Trennblatt-, Deckblatt- und Leerseiten, erzeugten Dokumenten mit Seitenbereich und Meldungen; das Protokoll SOLL keine Inhalte und keine Dateinamen enthalten.
2. Jedes aus einem Stapel erzeugte Dokument SOLL auf Stapel und Seitenbereich im Stapel-Original verweisen.
3. SOLANGE nicht alle Dokumente eines Stapels archiviert oder verworfen sind, SOLL das Stapel-Original unter `eingang/` ohne Sperre liegen.
4. WENN alle Dokumente eines Stapels entschieden sind und mindestens eines archiviert ist, DANN SOLL das Archiv das Stapel-Original nach `archiv/` kopieren, die Prüfsumme vergleichen und es, WO mindestens eines seiner archivierten Dokumente einer Art mit `gesetzliche_pflicht` angehört, im Modus GOVERNANCE bis zum spätesten `aufbewahren_bis` dieser Dokumente sperren (tech.md Regel 2, ADR-004).
5. WENN alle Dokumente eines Stapels verworfen sind, DANN SOLL das Archiv das Stapel-Original löschen und den Vorgang mit Prüfsumme protokollieren.
6. Das Tages-Manifest (Spec 01, Requirement 15) SOLL archivierte Stapel-Originale wie andere archivierte Dateien enthalten; der Prüflauf (Spec 01, 15.6) SOLL sie mitprüfen. Spec 09 ergänzt damit Spec 01, 15.1.
7. Die Web-App SOLL Stapel mit Status, Protokoll und Vorschau der Seiten nur Benutzern mit Recht A25 zeigen; vom Dokument SOLL ein Benutzer mit Recht A25 zu seinem Stapel springen können.
8. Das Archiv SOLL das archivierte Stapel-Original nie verändern, auch nicht, um Seiten verworfener Dokumente zu entfernen (tech.md Regel 2); solche Seiten bleiben bis zum Ende der Sperre im Original, und die Verfahrensbeschreibung SOLL das nennen (Requirement 14). [offen: Umgang mit verworfenen Seiten im gesperrten Stapel-Original – Entscheid E13]

### Requirement 6: Trennblatt-Generator (D13)
**User Story:** Als Inhaber möchte ich Trennblätter und Ordner-Deckblätter selbst drucken, damit ich jederzeit genug
davon habe.

#### Acceptance Criteria
1. Die Web-App SOLL Benutzern mit Recht A1 eine Seite «Trennblätter» bieten, auf der sie Trennblätter ohne Art, Trennblätter je aktiver Dokumentart und Ordner-Deckblätter mit freiem Titel (höchstens 60 Zeichen) als druckfertiges PDF erzeugen.
2. Jedes Trennblatt SOLL auf beiden Seiten denselben QR-Code (mindestens 40 mm Kantenlänge, Fehlerkorrektur Stufe M oder höher) sowie die Art bzw. den Titel in grosser Schrift tragen.
3. Das PDF SOLL für beidseitigen Druck auf A4 aufgebaut sein und den Hinweis enthalten, farbiges Papier zu verwenden.
4. WENN ein erzeugtes Trennblatt in einen Stapel gemischt und analysiert wird, DANN SOLL Requirement 3 es erkennen (Rundlauf-Test mit synthetischem Stapel).
5. Der Titel eines Deckblatts SOLL im QR-Code URL-kodiert (UTF-8) gespeichert werden; FALLS der Titel Steuer- oder Formatzeichen nach Spec 01, 3.10 enthält, DANN SOLL das Archiv mit HTTP 400 `TRENNBLATT_UNGUELTIG` antworten.

### Requirement 7: Gmail-Import über ein Label (I7)
**User Story:** Als Inhaber möchte ich Rechnungen aus meinem Gmail-Postfach einmal gesammelt übernehmen, damit ich
sie nicht einzeln herunterladen muss.

#### Acceptance Criteria
1. Das Archiv SOLL eine Quelle vom Typ `GMAIL_LABEL` mit Server, Benutzer, App-Passwort, Label und Startdatum unterstützen; das App-Passwort SOLL es wie das Passwort des E-Mail-Eingangs mit dem Datenschlüssel (AES-GCM, Secret-Datei `archiv_data_key`) verschlüsselt speichern und nie zurückgeben.
2. WENN ein Quelllauf für diese Quelle startet, DANN SOLL das Archiv den Label-Ordner per IMAP über TLS ausschliesslich lesend öffnen und keine Nachricht verändern, verschieben oder als gelesen markieren.
3. Das Archiv SOLL jeden Anhang vom Typ PDF, JPEG, PNG, HEIC oder TIFF als eigenes Dokument (Quelle `GMAIL`) über den bestehenden Erfassungsweg erfassen und Absender, Betreff und Datum als Hinweis an die KI geben, wie Spec 02, 1.2.
4. Das Archiv SOLL Bilder unter 20 KB (Signaturen, Logos) überspringen und im Quelllauf als «übersprungen» zählen.
5. WO in der Quelle «Nachrichten ohne Anhang erfassen» aktiviert ist, SOLL das Archiv solche Nachrichten als Korrespondenz erfassen wie Spec 02, 1.3; sonst SOLL es sie überspringen.
6. Das Archiv SOLL bereits erfasste Nachrichten über die Message-ID erkennen, gemeinsam mit den Message-IDs des E-Mail-Eingangs (`mail_eingang`); ein wiederholter Lauf SOLL keine neuen Dokumente aus denselben Nachrichten erzeugen.
7. FALLS die Anmeldung scheitert, DANN SOLL der Quelllauf mit der Meldung «Anmeldung bei Gmail fehlgeschlagen: App-Passwort ungültig oder Zwei-Faktor nicht aktiv.» und dem Code `QUELLE_ANMELDUNG` abbrechen, ohne das Passwort oder Inhalte zu protokollieren.
8. Der Import SOLL nur Nachrichten ab dem Startdatum und höchstens die konfigurierte Zahl Nachrichten je Lauf verarbeiten und einen abgebrochenen Lauf ohne Dubletten fortsetzen können.

### Requirement 8: Mac-Eingangsordner (I8)
**User Story:** Als Inhaber möchte ich heruntergeladene Belege einfach in einen Ordner legen, damit sie ohne
weiteren Schritt im Archiv landen.

#### Acceptance Criteria
1. Das Repository SOLL einen Hintergrunddienst für macOS enthalten, der einen Eingangsordner (Standard `~/UriNet-Archiv-Eingang`) und seine Unterordner beobachtet und per launchd beim Anmelden startet.
2. WENN eine Datei konfigurierbar lange (Standard 60 Sekunden) unverändert im Ordner liegt, DANN SOLL der Dienst sie mit einem API-Token vom Scope `import` hochladen, den Namen des Unterordners als Hinweis und die zugeordnete Quelle mitgeben; den Dateinamen sendet er nur als Name des Datei-Teils (Spec 01, 3.9), nie im Hinweis.
3. WENN das Archiv den Upload annimmt oder die Datei als `DUBLETTE` meldet (Spec 01, 3.4), DANN SOLL der Dienst sie nach `hochgeladen/JJJJ-MM/` verschieben.
4. FALLS das Archiv die Datei ablehnt (4xx ausser 429), DANN SOLL der Dienst sie nach `fehler/` verschieben und daneben eine Textdatei mit Code und Meldung des Archivs anlegen.
5. FALLS das Archiv nicht erreichbar ist oder mit 429 oder 5xx antwortet, DANN SOLL der Dienst die Datei unverändert liegen lassen und es später erneut versuchen.
6. Der Dienst SOLL das API-Token aus einer Datei mit Rechten 600 lesen (`~/.config/urinet-archiv/token` oder Pfad aus `ARCHIV_TOKEN_DATEI`, tech.md Regel 7), nie das Token selbst aus einer Umgebungsvariable, und es nie in Dateien, Logs oder Fehlermeldungen schreiben.
7. Der Dienst SOLL nie Dateien löschen; er verschiebt nur nach `hochgeladen/` oder `fehler/`.

### Requirement 9: Dateinamen-Regeln und Nachlauf der Generatoren (I9)
**User Story:** Als Inhaber möchte ich, dass Belegnummern, die schon im Dateinamen stehen, nicht von der KI geraten
werden, damit eigene Dokumente und Downloads exakt zugeordnet sind.

#### Acceptance Criteria
1. Das Archiv SOLL beim Upload über ein Token vom Scope `import` (Drive-Import, Mac-Eingang) den gespeicherten Dateinamen gegen eine je Mandant konfigurierte, geordnete Regelliste prüfen; die erste passende Regel gewinnt. [offen: Dateinamen-Regeln setzen Vorschlagsfelder – Entscheid E12]
2. WENN eine Regel greift, DANN SOLL das Archiv die erkannten Felder (Art, Belegnummer, Periode, Partner-Hinweis) mit Sicherheit 1.0 und Herkunft «Dateiname» in den Vorschlag übernehmen; der Dateiname SOLL weiterhin nicht in Prompt, Text oder Bild des KI-Vorschlags gelangen (Spec 01, 3.12), und die KI ergänzt nur die übrigen Felder.
3. FALLS eine aus dem Dateinamen erkannte Belegnummer nach der OCR nicht im Volltext vorkommt, DANN SOLL das Archiv das Feld im Eingangskorb als «bitte prüfen» markieren.
4. Das Archiv SOLL Regelmuster mit der JDK-Regex und der Zeitgrenze `archiv.dokumentart.muster-zeitgrenze` auswerten (tech.md, Konventionen Backend); eine Regel mit ungültigem Muster SOLL die Konfiguration mit HTTP 422 `KONFIGURATION_UNGUELTIG` ablehnen.
5. Der Python-Client `tools/archiv_client.py` SOLL einen Befehl `nachlauf` anbieten, der alle Rechnungen aus dem JSON-Ausgabeordner von `rechnung_gen.py` mit ihrem PDF über die Generator-Schnittstelle (Spec 01, Requirement 11) sendet; HTTP 409 `BELEGNUMMER_VERGEBEN` und die idempotente Antwort nach Spec 01, 11.8 SOLLEN als «schon vorhanden» zählen, ohne die Datei nach `archiv-konflikt/` zu verschieben.
6. Der Befehl `nachlauf` SOLL einen Trockenlauf anbieten, der nur auflistet, was gesendet würde, und dafür die Prüfsummen-Abfrage nach 10.6 nutzt.

### Requirement 10: Quellenregister und Quellläufe (I10)
**User Story:** Als Inhaber möchte ich sehen, welche Quellen schon vollständig importiert sind, damit ich beim
Stichtag nichts vergesse.

#### Acceptance Criteria
1. Das Archiv SOLL pro Mandant ein Quellenregister führen mit Code, Name, Typ, Konfiguration, Status (`OFFEN`, `LAEUFT`, `ABGESCHLOSSEN`, `LAUFEND`), Bemerkung und letztem Lauf.
2. Der Mandant `urinet` SOLL mit den allgemeinen Quellen starten (Generatoren, Drive, Mac-Ordner, Gmail, CRM-CSV, Papier); weitere Quellen legt der Inhaber im Register an.
3. Das Archiv SOLL für jeden Import einen Quelllauf mit Zählern führen: gefunden, neu, Dublette nach Prüfsumme, fachliche Dublette, übersprungen, Fehler; die Zähler SOLLEN aufgehen (gefunden = neu + Dublette nach Prüfsumme + übersprungen + Fehler).
4. Jedes importierte Dokument SOLL auf seinen Quelllauf verweisen.
5. WENN ein Quelllauf als Trockenlauf gestartet wird, DANN SOLL das Archiv dieselben Zähler ohne «fachliche Dublette» ermitteln, ohne Dokumente anzulegen.
6. Das Archiv SOLL einen Endpunkt anbieten, der für eine Liste von höchstens 1'000 SHA-256-Prüfsummen meldet, welche im Mandanten schon zu einem nicht verworfenen Dokument oder Stapel gehören; ohne IDs, damit keine unsichtbaren Dokumente erkennbar werden (wie Spec 01, 3.4).
7. Die Werkzeuge (Drive-Import, Mac-Eingang, `archiv_client.py`) SOLLEN ihre Uploads einem Quelllauf zuordnen; der Drive-Import SOLL seinen Schlussbericht (Spec 01, 12.7) zusätzlich als Zähler des Quelllaufs melden.
8. Die Web-App SOLL das Quellenregister mit Status und Zählern Benutzern mit Recht A27 zeigen; Status, Bemerkung, Konfiguration und Geheimnis einer Quelle SOLL nur ein Benutzer mit Recht A28 ändern und einen Lauf starten können.
9. Ein API-Token vom Scope `import` SOLL zusätzlich zu `POST /dokumente` nur die Prüfsummen-Abfrage (10.6) und Start, Meldung und Abschluss von Quellläufen erlauben; Spec 09 ergänzt damit Spec 01, 2.5.

### Requirement 11: Fachliche Dubletten und Hauptfassung (Q1)
**User Story:** Als Inhaber möchte ich, dass derselbe Beleg aus Gmail, Drive und einem Download nur einmal zählt,
damit Einnahmen und Ausgaben stimmen.

#### Acceptance Criteria
1. WENN für ein Dokument der KI-Vorschlag vorliegt oder ein Generator-Dokument angenommen wird, DANN SOLL das Archiv es gegen die Dokumente desselben Mandanten in `EINGANGSKORB`, `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER` und `ARCHIVIERT` auf fachliche Dubletten prüfen.
2. Das Archiv SOLL drei Stufen unterscheiden: `SICHER` (gleiche Art, gleiche Belegnummer nach Alias-Auflösung, Partner gleich oder auf einer Seite leer), `WAHRSCHEINLICH` (gleicher Partner, gleiches Belegdatum, gleicher Betrag und Währung) und `MOEGLICH` (Textähnlichkeit nach design.md mindestens 90 %, nur gegen Dokumente gleicher Art oder gleichen Partners).
3. Das Archiv SOLL Aliasse von Belegnummern je Mandant führen (z. B. Altbestand-Nummer → heutige Nummer), die nur ein Benutzer mit Recht A11 pflegt.
4. Das Archiv SOLL für jede gefundene Dublette einen Eintrag mit Stufe und Regel anlegen und sie im Eingangskorb mit beiden Dokumenten nebeneinander anzeigen, nur soweit der Benutzer beide Dokumente sehen darf (Spec 01, 2.3); sonst «Dublette eines Dokuments, das du nicht sehen darfst» ohne ID.
5. Das Archiv SOLL einen Vorschlag nach der Rangfolge der Quellen machen: Generator, dann PDF vom Aussteller (E-Mail, Gmail), dann Download und Drive und Mac-Ordner, dann Scan.
6. Das Archiv SOLL eine Dublette nie selbständig verwerfen; ein Benutzer mit Recht A7 entscheidet zwischen «als weitere Fassung verknüpfen», «als unterschriebene Fassung verknüpfen», «als Anhang verknüpfen», «kein Duplikat» und «Hauptfassung wechseln», ein Benutzer mit Recht A9 zusätzlich «verwerfen» (über den bestehenden Ablauf, Spec 01, 6.6).
7. Das Archiv SOLL pro Beleg genau eine Hauptfassung führen; verknüpfte Fassungen und Anhänge SOLLEN `hauptfassung = false` tragen, und eine Verknüpfung SOLL eine neue Metadatenversion mit Grund erzeugen (Spec 01, Requirement 10).
8. Kennzahlen und Chat-Werkzeuge (Spec 02, 3.1), Lückenbericht (Spec 05, 5.4), Regel `EINDEUTIG` (Spec 05, 5.1), Vollständigkeitsbericht und Jahresexport-Belegliste (Spec 02, 7.1) SOLLEN nur Hauptfassungen zählen; Anhänge und Fassungen SOLLEN beim Hauptdokument sichtbar sein und im Jahresexport unter `beilagen/` mit Verweis auf das Hauptdokument liegen.
9. WENN eine besser gerankte Fassung nach der Hauptfassung eintrifft, DANN SOLL das Archiv vorschlagen, die Hauptfassung zu wechseln; der Wechsel SOLL als Metadatenversion beider Dokumente mit Grund gespeichert werden.
10. SOLANGE für ein Dokument eine Dublette der Stufe `SICHER` offen ist, SOLL das Archiv die Bestätigung mit HTTP 409 `DUBLETTE_OFFEN` verweigern und auf die Dublette hinweisen.

### Requirement 12: Vollständigkeitsbericht (Q2)
**User Story:** Als Inhaber möchte ich vor dem Stichtag und vor jedem Jahresabschluss sehen, was fehlt, damit ich
Lücken schliessen oder begründen kann.

#### Acceptance Criteria
1. Das Archiv SOLL einen Vollständigkeitsbericht pro Geschäftsjahr erstellen, der die Lücken des Lückenberichts (Spec 05, 5.4, Regeln `FOLGE` und `ERWARTET`) mit den Prüfungen «Quellen» (alle Quellen `ABGESCHLOSSEN` oder `LAUFEND`), «Eingangskorb» (leer) und «Stapel» (keine angehaltenen Stapel) zusammenführt; neue Regeltypen in Spec 05 braucht es dafür nicht.
2. Der Mandant `urinet` SOLL zusätzlich zu den bestehenden Regeln `FOLGE` für Ausgangsrechnungen und `ERWARTET` für Lohnabrechnungen und Bankbelege Regeln `FOLGE` für Offerten (je Geschäftsjahr) und für Lieferantenbelege (Zeitraum `GESAMT`) erhalten, mit Beginn und Startmonaten aus `<startwerte-vollstaendigkeit>`. [offen: Startwerte – K11]
3. Jede Lücke SOLL entweder offen oder mit Text, Benutzer und Zeit begründet sein; begründete Lücken SOLLEN den Bericht nicht als unvollständig markieren; begründen und eine Begründung zurückziehen SOLL nur ein Benutzer mit Recht A29, beides protokolliert.
4. Der Bericht SOLL nur für Rollen verfügbar sein, die den Lückenbericht sehen dürfen (Spec 05, 5.4), sonst HTTP 403.
5. Die Web-App SOLL den Bericht als Seite zeigen; der Jahresexport (Spec 02, Requirement 7) SOLL ihn als `vollstaendigkeit.json` und `vollstaendigkeit.pdf` enthalten. Spec 09 ergänzt damit Spec 02, 7.1.
6. Das Chat-Werkzeug `vollstaendigkeit` (Spec 02, 3.6) SOLL denselben Dienst verwenden und begründete Lücken als begründet nennen.
7. Der Bericht SOLL nur IDs, Belegnummern, Zeiträume und Zähler enthalten, keine Inhalte der Dokumente.

### Requirement 13: Scanqualität prüfen (Q3, Option)
**User Story:** Als Inhaber möchte ich auf schlechte Scans hingewiesen werden, damit kein unlesbarer Beleg
archiviert wird.

#### Acceptance Criteria
1. WO die Option aktiv ist, SOLL das Archiv für jede Inhaltsseite die effektive Auflösung aus Bildgrösse und Seitengrösse berechnen und Seiten unter 290 dpi markieren.
2. WO die Option aktiv ist, SOLL das Archiv Seiten mit weniger als 20 erkannten Zeichen, die nicht leer sind, als «Seite prüfen» markieren.
3. Markierte Seiten SOLLEN im Eingangskorb mit Vorschau und Grund erscheinen; die Bestätigung des Dokuments SOLL trotzdem möglich sein.

### Requirement 14: Verfahrensbeschreibung ergänzen (B6)
**User Story:** Als Inhaber möchte ich die neuen Abläufe dokumentiert haben, damit der Treuhänder sie prüfen kann und
Papier vernichtet werden darf (E5).

#### Acceptance Criteria
1. Die Verfahrensbeschreibung «Erfassen und Archivieren» (`docs/verfahrensbeschreibung-erfassen-archivieren.md`, Spec 01, 16.6) SOLL um Scanprofile, Trennblätter, Stapelprotokoll und Seitenbilanz, Leerseiten, fachliche Dubletten und Umgang mit Papier ergänzt sein. Nachweis: Dokument.
2. Die Verfahrensbeschreibung SOLL die Längengrenze nach Spec 01, 16.6 einhalten; Einzelheiten zum Scan-Eingang SOLLEN in einem Anhang stehen. Nachweis: Dokument.
3. Die Verfahrensbeschreibung SOLL festhalten, dass unterschriebene Verträge, Policen und die Jahresrechnung im Original aufbewahrt werden, und den Umgang mit verworfenen Seiten im Stapel-Original nach E13 nennen. Nachweis: Dokument.

### Requirement 15: Rechte (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich, dass Stapel, Quellen und Begründungen nur sehen und steuern kann, wer
ohnehin alle Dokumente sehen darf, damit der Scan-Eingang keine Hintertür zu fremden Dokumenten ist.

#### Acceptance Criteria
1. Das Archiv SOLL die Rechtematrix (Spec 01, 2.6, Spec 04, 12.1 und Spec 06, 4.1) um die folgenden Zeilen ergänzen; Bedeutung von «erlaubt» und «403» und Vorrang von 404 vor 403 wie dort. Die bestehenden Zeilen bleiben unverändert; die Ergänzung steht nur in dieser Spec (Spec 01 verweist darauf) und kommt mit Aufgabe 27.2 in das Enum `Aktion` und in `RechtematrixSpecTest`. Trennblätter erzeugen gehört zu A1; Dubletten entscheiden zu A7, verwerfen zu A9. Nachweis: JUnit (`RechtematrixSpecTest`).

| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |
| --- | --- | --- | --- | --- | --- |
| A25 | Stapel ansehen (Liste, Protokoll, Seitenvorschau) | erlaubt | 403 | erlaubt (nur lesend) | erlaubt |
| A26 | Angehaltenen Stapel neu analysieren oder als ein Dokument übernehmen | erlaubt | 403 | 403 | erlaubt |
| A27 | Quellenregister und Quellläufe ansehen | erlaubt | 403 | erlaubt (nur lesend) | erlaubt |
| A28 | Quellen pflegen (Status, Bemerkung, Konfiguration, Geheimnis, Lauf starten) | erlaubt | 403 | 403 | 403 |
| A29 | Lücken begründen und Begründung zurückziehen | erlaubt | 403 | 403 | 403 |

   ¹ Für `geschaeftsstelle` gelten bei A28 und A29 403, bis Spec 03, 3.5 die Verwaltungsrechte entscheidet. A25 und
   A26 folgen A3 und A4: Ein Stapel enthält Seiten aller Vertraulichkeitsstufen, darum sieht ihn nur, wer alle Stufen
   sieht. Sind die Nummern A25 bis A29 beim Merge schon vergeben, verschiebt Aufgabe 27.1 sie fortlaufend.
2. FALLS ein Benutzer ohne das Recht einer Aktion A25 bis A29 sie ausführt, DANN SOLL das Archiv mit HTTP 403 `NICHT_ERLAUBT` antworten und `VERWEIGERT` protokollieren (Spec 01, 10.7).

### Requirement 16: Probelauf, Stichtag und Produktivimport (Abnahme)
**User Story:** Als Inhaber möchte ich die Produktivstellung in prüfbaren Schritten machen, damit der Bestand 2026
vollständig und korrekt im Archiv ist.

#### Acceptance Criteria
1. WENN ein synthetischer Probestapel mit 20 Blatt, 5 Trennblättern (davon 2 mit Art), 1 Ordner-Deckblatt und leeren Rückseiten mit dem Profil «Archiv Stapel» in einen Testmandanten gescannt wird, DANN SOLL das Archiv genau die erwarteten Dokumente mit den erwarteten Seiten erzeugen und die Seitenbilanz bestätigen (Gate «Probelauf»). Nachweis: manuell [Fabian] (Abnahmebericht).
2. Der Probelauf mit echten Belegen, die Umstellung zum Stichtag (16.5) und der Import des Bestands (16.3, 16.4) SOLLEN erst beginnen, wenn Fabian über den Start mit Echtbelegen entschieden hat und die dann geltenden Voraussetzungen erfüllt sind (Spec 01, 16.15 und 16.17 oder deren Änderung durch diesen Entscheid); bis dahin SOLLEN Stufe A und alle Tests nur synthetische Daten enthalten. Nachweis: manuell [Fabian] (Gate-Bericht). [offen: Start mit Echtbelegen – Entscheid #731]
3. Der Produktivimport SOLL in dieser Reihenfolge laufen: CRM-CSV, Generatoren (`nachlauf`), Downloads aus der Buchhaltung, Gmail, Drive und Mac-Ordner, weitere Quellen des Registers, Papier. Nachweis: manuell [Fabian] (Quellenregister).
4. Jede Quelle SOLL zuerst als Trockenlauf und danach echt importiert werden; die Zähler beider Läufe SOLLEN im Quellenregister stehen. Nachweis: manuell [Fabian] (Quellenregister).
5. Ab dem Stichtag SOLLEN Generatoren, E-Mail-Eingang, Mac-Eingangsordner und Scan-Eingang (oder der Rückfall nach E6) für den Mandanten `urinet` aktiv sein. Nachweis: manuell [Fabian] (Umstellungsprotokoll).
6. Das Gate «Abschluss» SOLL gelten, WENN der Vollständigkeitsbericht 2026 keine offene Lücke zeigt, der Eingangskorb leer ist, der monatliche Prüflauf ohne Abweichung war und der Jahresexport 2026 erstellt ist. Nachweis: manuell [Fabian] (Abnahmebericht).
7. Der Abschluss nach 16.6 SOLL zugleich den Nachweis der Übernahme der Belege 2026 nach Spec 01, Aufgabe 5.8 liefern, damit der Bestand nicht zweimal übernommen wird. Nachweis: manuell [Fabian] (Abnahmebericht).

---

## Rückverfolgung Konzept → Spec
| Konzept | Requirement |
| --- | --- |
| K7, K10 | 0 |
| B5 | 1 |
| D9 | 2 |
| D10 | 3 |
| D11 | 4 |
| D12 | 5 |
| D13 | 6 |
| I7 | 7 |
| I8 | 8 |
| I9 | 9 |
| I10 | 10 |
| Q1 | 11 |
| Q2 | 12 |
| Q3 | 13 |
| B6 | 14 |
| (ohne Konzept-ID) | 15 |
| Ablauf der Produktivstellung | 16 |
