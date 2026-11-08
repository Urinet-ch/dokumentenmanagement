# Requirements Document — 01 Archiv-Kern (Phase 0 und 1)

## Introduction
Diese Spec beschreibt den Archiv-Kern des UriNet Archivs: Dokumente aus Browser, Handy,
Generatoren, Google-Drive-Altbestand und CRM-CSV-Export werden erfasst, per OCR durchsuchbar
gemacht, von der KI mit Metadaten vorbelegt, im Eingangskorb bestätigt und nach GeBüV
unveränderbar abgelegt. Der Kern ist von Anfang an mandantenfähig (Schema pro Mandant),
auch wenn in Phase 1 nur der Mandant `urinet` produktiv ist.

Nicht Teil dieser Spec: E-Mail-Eingang, semantische Suche, Chat, AbaNinja, Jahresexport
(→ `02-abgleich-chat`), zweiter Mandant mit WordPress (→ `03-zweiter-mandant`).

Schreibweise der Akzeptanzkriterien: EARS auf Deutsch.
- Immer: «Das Archiv SOLL …»
- Ereignis: «WENN …, DANN SOLL das Archiv …»
- Zustand: «SOLANGE …, SOLL das Archiv …»
- Unerwünscht: «FALLS …, DANN SOLL das Archiv …»
- Option: «WO …, SOLL das Archiv …»

## Glossary
- **Mandant** – abgeschlossener Datenbereich (eigenes DB-Schema, eigener Bucket), z. B. `urinet`.
- **Dokument** – ein erfasster Beleg oder Text mit Original-Datei, PDF/A-Fassung und Metadaten.
- **Original** – die Datei so, wie sie empfangen wurde (Foto, PDF, HEIC …).
- **PDF/A** – durchsuchbare Archivfassung (PDF/A-2b) mit OCR-Textebene.
- **Eingangskorb** – Liste der Dokumente mit KI-Vorschlägen, die noch bestätigt werden müssen.
- **Archiviert** – bestätigtes Dokument; Dateien sind mit Object Lock gesperrt.
- **Dokumentart** – Typ eines Dokuments (z. B. Ausgangsrechnung) mit Zusatzfeldern und Aufbewahrungsregel.
- **Partner** – Kontakt (Kunde, Lieferant, Behörde), auf den sich ein Dokument bezieht.
- **Generator** – eigenes Skript, das Dokumente erzeugt (`rechnung_gen.py`, `urinet_pdf.py`).
- **Golden-Datensatz** – 50 anonymisierte echte Belege mit erwarteten Metadaten zur Messung der KI.
- **Manifest** – tägliche, signierte Liste der Prüfsummen aller neu archivierten Dateien.

---

## Phase 0 – Klären

### Requirement 0: Machbarkeit und Golden-Datensatz (Phase 0)
**User Story:** Als Inhaber möchte ich vor dem Bau der Oberfläche wissen, ob OCR und KI meine
Belege zuverlässig erkennen und ob Infomaniak alle nötigen Funktionen bietet, damit ich nicht
in eine Sackgasse investiere.

#### Acceptance Criteria
1. Das Archiv-Projekt SOLL einen Golden-Datensatz von 50 anonymisierten Belegen (mindestens 10 Quittungen, 10 Lieferantenrechnungen, 10 eigene Rechnungen/Offerten, 10 Behörden-/Versicherungsbriefe, 10 gemischte Handy-Fotos) mit einer Erwartungsdatei `erwartet.json` enthalten.
2. WENN der Prototyp den Golden-Datensatz verarbeitet, DANN SOLL er pro Beleg Art, Datum, Partner, Betrag und Belegnummer mit der Erwartung vergleichen und einen Bericht mit Trefferquote ausgeben.
3. Das Gate «Phase 0 bestanden» SOLL gelten, WENN mindestens 45 von 50 Belegen in Art, Datum, Partner und Betrag (soweit vorhanden) korrekt sind.
4. Der Spike-Bericht SOLL für Infomaniak Object Storage festhalten, ob Object Lock pro Objekt (`PutObjectRetention`, Modus GOVERNANCE) und Versionierung funktionieren.
5. Der Spike-Bericht SOLL für das bestehende Konto bei Infomaniak AI Services (Modelle verfügbar seit Prüfung 07.11.2026) festhalten, ob strukturierte JSON-Antworten und Werkzeugaufrufe (Tool Calling) mit Mistral-Small-4 funktionieren, welche Dimension die Embeddings haben, welche Form der Reranker-Aufruf hat und ob Bilder direkt gelesen werden können (Frage K1).
6. Der Spike-Bericht SOLL Spaltennamen, Trennzeichen und Zeichensatz eines echten CSV-Exports aus 21.CRM dokumentieren (Frage K4).

---

## Phase 1 – Archiv-Kern

### Requirement 1: Mandanten-Kontext (M1)
**User Story:** Als Betreiber möchte ich mehrere Mandanten auf einer Installation betreiben,
damit VHF/GSK später ohne Umbau dazukommen kann.

#### Acceptance Criteria
1. Das Archiv SOLL für jeden Mandanten ein eigenes PostgreSQL-Schema und einen eigenen S3-Bucket verwenden.
2. WENN eine Anfrage eintrifft, DANN SOLL das Archiv den Mandanten ausschliesslich aus dem verifizierten Token (Claim `mandant`) oder aus dem verwendeten API-Token bestimmen.
3. FALLS eine Anfrage keinen gültigen Mandanten enthält oder der Mandant inaktiv ist, DANN SOLL das Archiv mit HTTP 403 antworten und keine Daten liefern.
4. FALLS ein Benutzer eine Dokument-ID eines anderen Mandanten anfragt, DANN SOLL das Archiv mit HTTP 404 antworten.
5. WENN ein Mandant neu angelegt wird, DANN SOLL das Archiv das Schema per Flyway migrieren, den Bucket mit Object Lock und Versionierung anlegen und die Standard-Dokumentarten einspielen.
6. Aufträge in der Auftragsschlange SOLLEN den Mandanten mitführen, und der Worker SOLL jeden Auftrag im Kontext dieses Mandanten ausführen.

### Requirement 2: Anmeldung und Rollen (B3)
**User Story:** Als Inhaber möchte ich, dass nur berechtigte Personen Dokumente sehen,
damit Lohn- und Vertragsdaten geschützt sind.

#### Acceptance Criteria
1. Das Archiv SOLL die Anmeldung über Keycloak (OIDC mit PKCE) mit Zwei-Faktor (TOTP) für alle Rollen verlangen.
2. Das Archiv SOLL die Rollen `inhaber`, `mitarbeiter`, `treuhaender` und `geschaeftsstelle` kennen und gemäss `product.md` durchsetzen.
3. FALLS ein Benutzer ohne Rolle `inhaber` ein Dokument mit Vertraulichkeit `personal` anfragt, das nicht ihm selbst zugeordnet ist (`person_benutzer`), DANN SOLL das Archiv es weder in Listen noch in Suchtreffern noch per Direktzugriff liefern.
4. FALLS ein Benutzer mit Rolle `treuhaender` eine schreibende Aktion ausführt, DANN SOLL das Archiv mit HTTP 403 antworten.
5. Das Archiv SOLL für Generatoren API-Tokens mit Scope `generator` unterstützen, die nur gehasht (Argon2id) gespeichert werden und widerrufbar sind.

### Requirement 3: Upload im Browser (D1)
**User Story:** Als Inhaber möchte ich PDFs und Bilder vom Mac per Drag & Drop hochladen,
damit digitale Dokumente ohne Umweg ins Archiv kommen.

#### Acceptance Criteria
1. Das Archiv SOLL Dateien der Typen PDF, JPEG, PNG, HEIC/HEIF und TIFF bis 50 MB pro Datei annehmen, auch mehrere in einem Vorgang.
2. WENN eine Datei hochgeladen wird, DANN SOLL das Archiv innert 2 Sekunden mit HTTP 202 und der Dokument-ID antworten und die Verarbeitung als Auftrag einreihen.
3. FALLS der Dateityp nicht unterstützt ist (geprüft am Dateiinhalt, nicht an der Endung), DANN SOLL das Archiv die Datei mit einer verständlichen Meldung ablehnen.
4. FALLS eine Datei mit identischer SHA-256-Prüfsumme im Mandanten bereits existiert, DANN SOLL das Archiv kein neues Dokument anlegen und auf das vorhandene verweisen.
5. WO der Benutzer mehrere Bilder als «ein Dokument» markiert, SOLL das Archiv sie in der gewählten Reihenfolge zu einem mehrseitigen Dokument zusammenfassen.

### Requirement 4: Handy-Erfassung als Web-App (D2)
**User Story:** Als Inhaber möchte ich Post und Quittungen mit dem Handy fotografieren,
damit Papier sofort erfasst ist.

#### Acceptance Criteria
1. Die Web-App SOLL als installierbare PWA auf iOS (Safari) und Android (Chrome) funktionieren.
2. WENN der Benutzer «Erfassen» wählt, DANN SOLL die App die Rückkamera öffnen und mehrere Seiten nacheinander aufnehmen lassen.
3. WENN eine Seite aufgenommen ist, DANN SOLL die App die Dokumentkanten erkennen, einen Zuschnitt vorschlagen, die Perspektive entzerren und eine Vorschau zeigen, die der Benutzer anpassen oder verwerfen kann.
4. SOLANGE das Handy offline ist, SOLL die App Aufnahmen in einer lokalen Warteschlange (IndexedDB) halten und den Zähler «wartet auf Upload» anzeigen.
5. WENN die Verbindung wieder besteht, DANN SOLL die App die Warteschlange automatisch hochladen und erfolgreich übertragene Einträge lokal löschen.
6. FALLS ein Upload dreimal fehlschlägt, DANN SOLL die App den Eintrag als fehlerhaft markieren und eine manuelle Wiederholung anbieten, ohne ihn zu löschen.

### Requirement 5: Verarbeitung, OCR und PDF/A (D3)
**User Story:** Als Inhaber möchte ich, dass jedes Dokument durchsuchbar und langfristig lesbar ist,
damit ich es auch in zehn Jahren noch öffnen und finden kann.

#### Acceptance Criteria
1. WENN ein Verarbeitungsauftrag startet, DANN SOLL der Worker Bilder (inkl. HEIC) zu PDF wandeln, Seiten drehen und entzerren und mit OCRmyPDF (Sprachen `deu+eng`) ein PDF/A-2b erzeugen.
2. WO ein PDF bereits eine Textebene hat, SOLL der Worker kein neues OCR ausführen, aber trotzdem eine PDF/A-Fassung erzeugen (`--skip-text`).
3. Das Archiv SOLL den erkannten Volltext pro Dokument speichern und für die deutsche Volltextsuche indexieren.
4. FALLS die Verarbeitung fehlschlägt, DANN SOLL das Archiv sie bis zu dreimal mit wachsender Wartezeit wiederholen und danach den Status `FEHLER` mit Fehlertext setzen, ohne das Original zu verlieren.
5. Der Worker SOLL ein einseitiges Handy-Foto in 95 % der Fälle innert 30 Sekunden fertig verarbeiten (ohne KI-Wartezeit).

### Requirement 6: Unveränderbare Ablage und Prüfsummen (B1)
**User Story:** Als Inhaber möchte ich die Vorgaben von Art. 958f OR und GeBüV erfüllen,
damit gescannte Belege als Buchungsbelege gelten.

#### Acceptance Criteria
1. WENN eine Datei empfangen wird, DANN SOLL das Archiv vor jeder weiteren Verarbeitung ihre SHA-256-Prüfsumme berechnen und mit Zeitpunkt protokollieren.
2. SOLANGE ein Dokument nicht archiviert ist, SOLL das Archiv seine Dateien unter dem Präfix `eingang/` ohne Sperre ablegen, damit Fehlerfassungen gelöscht werden können.
3. WENN ein Dokument archiviert wird, DANN SOLL das Archiv Original und PDF/A unter `archiv/` ablegen, die Prüfsummen erneut vergleichen und eine Object-Lock-Sperre (GOVERNANCE) bis `aufbewahren_bis` setzen.
4. FALLS die Prüfsumme nach dem Kopieren abweicht, DANN SOLL das Archiv die Archivierung abbrechen, den Status `FEHLER` setzen und den Inhaber benachrichtigen.
5. Das Archiv SOLL archivierte Dateien über keine Schnittstelle überschreiben oder löschen.
6. FALLS ein Benutzer ein noch nicht archiviertes Dokument verwirft, DANN SOLL das Archiv die Dateien löschen, den Vorgang mit Prüfsumme und Grund protokollieren und das Dokument als `VERWORFEN` behalten.

### Requirement 7: KI-Vorschlag der Metadaten (D4)
**User Story:** Als Inhaber möchte ich, dass die KI Art, Datum, Partner und Betrag vorschlägt,
damit ich nur noch bestätigen muss.

#### Acceptance Criteria
1. WENN der Volltext vorliegt, DANN SOLL das Archiv das Sprachmodell mit Text, Liste der Dokumentarten und bis zu 5 passenden Korrekturbeispielen aufrufen und eine Antwort nach festem JSON-Schema verlangen.
2. Der Vorschlag SOLL mindestens enthalten: Dokumentart, Titel, Belegdatum, Partnername, Belegnummer, Richtung (Einnahme/Ausgabe), Betrag, Währung, Fälligkeit, Tags und pro Feld eine Sicherheit von 0 bis 1.
3. FALLS die Antwort nicht dem Schema entspricht, DANN SOLL das Archiv einmal mit Fehlerhinweis nachfragen und sonst das Dokument ohne Vorschlag in den Eingangskorb legen.
4. WENN ein Partnername vorgeschlagen wird, DANN SOLL das Archiv ihn mit den Kontakten abgleichen (E-Mail, UID, IBAN, sonst Namensähnlichkeit ≥ 0.6) und den besten Treffer verknüpfen oder «neuer Partner?» markieren.
5. WENN eine Belegnummer einem bekannten Muster entspricht (siehe `structure.md`), DANN SOLL das Archiv die Dokumentart entsprechend setzen oder bestätigen.
6. Das Archiv SOLL Felder mit Sicherheit unter 0.8 im Eingangskorb als «bitte prüfen» markieren.
7. WENN der Benutzer einen Vorschlag korrigiert, DANN SOLL das Archiv die Korrektur als Beispiel speichern und bei künftigen Vorschlägen mit gleichem Partner oder gleicher Art verwenden.
8. Das Archiv SOLL die Anzahl Tokens pro KI-Aufruf und Mandant speichern.

### Requirement 8: Eingangskorb (D5)
**User Story:** Als Inhaber möchte ich neue Dokumente schnell prüfen und bestätigen,
auch auf dem Handy, damit nichts liegen bleibt.

#### Acceptance Criteria
1. Der Eingangskorb SOLL alle Dokumente im Status `EINGANGSKORB` neueste zuerst zeigen, mit Vorschaubild, Vorschlägen und markierten unsicheren Feldern.
2. WENN der Benutzer «Bestätigen» wählt, DANN SOLL das Archiv die Pflichtfelder der Dokumentart prüfen, `aufbewahren_bis` berechnen, den Status auf `ARCHIVIERT` setzen und die Archivierung (Requirement 6.3) auslösen.
3. FALLS Pflichtfelder fehlen, DANN SOLL das Archiv die Bestätigung verweigern und die fehlenden Felder nennen.
4. Der Eingangskorb SOLL auf einem Handy mit 375 px Breite ohne horizontales Scrollen bedienbar sein; Bestätigen eines korrekt vorgeschlagenen Dokuments SOLL höchstens zwei Tipps brauchen.
5. WENN der Benutzer «Verwerfen» wählt, DANN SOLL das Archiv nach Rückfrage Requirement 6.6 ausführen.
6. Die App SOLL die Anzahl Dokumente im Eingangskorb in der Navigation anzeigen.

### Requirement 9: Dokumentarten, Zusatzfelder und Aufbewahrung (D6)
**User Story:** Als Inhaber möchte ich Dokumentarten und ihre Felder selbst pflegen,
damit auch VHF/GSK eigene Arten ohne Programmänderung erhält.

#### Acceptance Criteria
1. Das Archiv SOLL pro Mandant Dokumentarten mit Code, Name, Zusatzfeld-Gruppen (`finanz`, `vertrag`, `frei`), Pflichtfeldern, Standard-Vertraulichkeit und Aufbewahrungsregel verwalten.
2. Der Mandant `urinet` SOLL mit den 11 Dokumentarten des Konzepts starten (Ausgangsrechnung, Offerte, Lieferantenbeleg, Quittung/Spesen, Lohnabrechnung, Vertrag, Versicherung, Behörde, Bank, Projektdokument, Korrespondenz).
3. WENN ein Dokument bestätigt wird, DANN SOLL das Archiv `aufbewahren_bis` berechnen: Ende des Geschäftsjahres des Belegdatums plus Jahre der Regel (Standard 10); bei Verträgen ab `gueltig_bis`; bei «unbefristet» kein Datum und keine Löschung.
4. FALLS eine Dokumentart noch Dokumente hat, DANN SOLL das Archiv ihre Löschung verweigern und nur «inaktiv» erlauben.
5. Freie Zusatzfelder SOLLEN als JSON Schema definiert und beim Speichern validiert werden.

### Requirement 10: Versionierung der Metadaten und Zugriffsprotokoll (B2)
**User Story:** Als Inhaber möchte ich nachweisen können, wer wann was gesehen oder geändert hat,
damit das Archiv einer Prüfung standhält.

#### Acceptance Criteria
1. WENN Metadaten eines archivierten Dokuments geändert werden, DANN SOLL das Archiv die vorherige Fassung mit Benutzer, Zeit und Grund in `dokument_version` speichern; ein Grund ist Pflicht.
2. Das Archiv SOLL jedes Anzeigen, Herunterladen, Ändern, Bestätigen, Verwerfen und Exportieren mit Zeit, Benutzer, Aktion, Dokument-ID und IP im Zugriffsprotokoll festhalten.
3. FALLS jemand versucht, Einträge im Zugriffsprotokoll zu ändern oder zu löschen, DANN SOLL die Datenbank dies verweigern.
4. WENN zwei Benutzer dasselbe Dokument gleichzeitig ändern, DANN SOLL das Archiv die zweite Änderung mit HTTP 412 ablehnen (optimistisches Locking).
5. Der Inhaber SOLL das Zugriffsprotokoll nach Zeitraum, Benutzer und Dokument filtern können.

### Requirement 11: Schnittstelle für Generatoren (I1)
**User Story:** Als Inhaber möchte ich, dass meine Skripte Rechnungen, Offerten und Lohnabrechnungen
direkt ablegen, damit ich sie nicht von Hand erfassen muss.

#### Acceptance Criteria
1. Das Archiv SOLL einen Endpunkt anbieten, der ein PDF plus vollständige Metadaten (JSON) mit einem API-Token vom Scope `generator` annimmt.
2. WENN die Metadaten vollständig und gültig sind, DANN SOLL das Archiv das Dokument ohne KI-Vorschlag direkt archivieren (Status `ARCHIVIERT`).
3. FALLS bereits ein Dokument mit derselben Belegnummer und Art existiert, DANN SOLL das Archiv mit HTTP 409 antworten und nichts überschreiben.
4. Das Repository SOLL einen Python-Client `tools/archiv_client.py` enthalten, den `rechnung_gen.py` und `urinet_pdf.py` mit einer Zeile aufrufen können.
5. FALLS das Archiv nicht erreichbar ist, DANN SOLL der Client das PDF in einem lokalen Ordner `archiv-ausstehend/` ablegen und beim nächsten Aufruf erneut senden.

### Requirement 12: Einmaliger Import des Google-Drive-Altbestands (I2)
**User Story:** Als Inhaber möchte ich die bisherigen Dokumente aus `UriNet/` übernehmen,
damit das Archiv vollständig ist.

#### Acceptance Criteria
1. Das Repository SOLL ein Kommandozeilen-Werkzeug enthalten, das einen lokalen Ordner (Google Drive für Desktop) rekursiv liest und jede Datei mit ihrem Ordnerpfad als Hinweis hochlädt.
2. WENN ein Ordnerpfad bekannte Muster enthält (`Kunden/<Kunde>/Rechnungen`, `Offerten`, `Buchhaltung 2026`), DANN SOLL das Archiv daraus Partner- und Art-Hinweise an die KI geben.
3. Das Werkzeug SOLL einen Trockenlauf anbieten, der nur zählt und auflistet.
4. Das Werkzeug SOLL wiederholbar sein: bereits übertragene Dateien (gleiche Prüfsumme) werden übersprungen und im Bericht als «schon vorhanden» gezählt.
5. Google-Docs-Dateien (`.gdoc`, `.gsheet`) SOLL das Werkzeug überspringen und in einer Liste «bitte als PDF exportieren» ausgeben.

### Requirement 13: CSV-Import der CRM-Daten aus 21.CRM (I6)
**User Story:** Als Inhaber möchte ich meine Kontakte aus 21.CRM als CSV einlesen,
damit Dokumente von Anfang an den richtigen Partnern zugeordnet werden.

#### Acceptance Criteria
1. Das Archiv SOLL CSV-Dateien mit Trennzeichen Semikolon oder Komma und Zeichensatz UTF-8 (mit oder ohne BOM) oder Windows-1252 annehmen und Trennzeichen und Zeichensatz selbst erkennen.
2. WENN eine Datei erstmals mit unbekannten Spalten hochgeladen wird, DANN SOLL das Archiv einen Zuordnungsdialog zeigen (Spalte → Feld) und die Zuordnung pro Mandant und Importart speichern.
3. WENN eine Datei mit bekannter Zuordnung hochgeladen wird, DANN SOLL das Archiv eine Vorschau mit den Zahlen «neu», «geändert», «unverändert», «mögliche Dublette» und «fehlerhaft» sowie den einzelnen Zeilen zeigen, ohne etwas zu übernehmen.
4. Das Archiv SOLL Kontakte in dieser Reihenfolge abgleichen: Kontakt-ID aus 21.CRM, sonst E-Mail (ohne Gross-/Kleinschreibung), sonst Name + PLZ (normalisiert).
5. FALLS eine Zeile mehrere bestehende Kontakte treffen könnte oder nur über Namensähnlichkeit passt, DANN SOLL das Archiv sie als «mögliche Dublette» markieren und nicht automatisch überschreiben.
6. WENN der Benutzer die Vorschau übernimmt, DANN SOLL das Archiv neue Kontakte anlegen, geänderte aktualisieren (Feldwerte aus dem CSV gewinnen), Dubletten nur gemäss Auswahl des Benutzers behandeln und alles in einer Transaktion ausführen.
7. Das Archiv SOLL Kontakte, die im CSV fehlen, nie löschen, sondern höchstens als «nicht mehr im CRM-Export» kennzeichnen.
8. Das Archiv SOLL jeden Import mit Dateiname, Prüfsumme, Benutzer, Zeit und Zählern protokollieren und die Originaldatei unter `importe/` ablegen.
9. WO die Datei Verkaufschancen enthält (Importart `verkaufschance`), SOLL das Archiv sie mit Titel, Phase, Betrag und Datum dem passenden Kontakt zuordnen.
10. FALLS Pflichtspalten (Name) fehlen oder die Datei über 10'000 Zeilen hat, DANN SOLL das Archiv den Import mit einer verständlichen Meldung ablehnen.

### Requirement 14: Volltextsuche mit Filtern (S1)
**User Story:** Als Inhaber möchte ich Dokumente über Text und Metadaten finden,
damit ich jede Rechnung in Sekunden habe.

#### Acceptance Criteria
1. Das Archiv SOLL eine Suche über Volltext, Titel, Partner und Tags (deutsche Stammformen) anbieten.
2. Die Suche SOLL Filter für Dokumentart, Partner, Zeitraum (Belegdatum), Betrag von–bis, Richtung, Tags, Status und Geschäftsjahr kombinierbar unterstützen.
3. WENN eine Suche ausgeführt wird, DANN SOLL das Archiv bei bis zu 50'000 Dokumenten pro Mandant innert 1 Sekunde (p95) antworten, sortiert nach Relevanz oder Datum.
4. Suchtreffer SOLLEN eine Textstelle mit hervorgehobenem Suchbegriff zeigen.
5. Die Suche SOLL Requirement 2.3 (Vertraulichkeit) und Requirement 1.2 (Mandant) einhalten.
6. WENN der Benutzer ein Dokument öffnet, DANN SOLL das Archiv eine zeitlich begrenzte Download-Adresse (höchstens 5 Minuten gültig) für die PDF/A-Fassung ausgeben und den Zugriff protokollieren.

### Requirement 15: Tägliches Manifest (B1)
**User Story:** Als Inhaber möchte ich nachweisen können, dass seit der Archivierung nichts verändert wurde,
damit Integrität und Speicherzeitpunkt belegt sind (Art. 9 GeBüV).

#### Acceptance Criteria
1. WENN der Tag endet (02:00 Europe/Zurich), DANN SOLL das Archiv pro Mandant ein Manifest mit allen am Vortag archivierten Dateien (Dokument-ID, Variante, SHA-256, S3-Versions-ID) erzeugen.
2. Jedes Manifest SOLL die Prüfsumme des vorherigen Manifests enthalten (Hash-Kette) und mit Ed25519 signiert werden.
3. WO ein Zeitstempeldienst (RFC 3161) konfiguriert ist, SOLL das Archiv das Manifest zusätzlich zeitstempeln.
4. Das Archiv SOLL das Manifest mit Object Lock im Bucket ablegen und in der Datenbank referenzieren.
5. WENN der monatliche Prüflauf startet, DANN SOLL das Archiv für alle archivierten Dateien die Prüfsumme neu berechnen, mit Datenbank und Manifest vergleichen und Abweichungen dem Inhaber melden.

### Requirement 16: Betrieb, Sicherung und Dokumentation (B, nicht funktional)
**User Story:** Als Betreiber möchte ich das Archiv einfach betreiben und wiederherstellen können,
damit kein Dokument verloren geht.

#### Acceptance Criteria
1. Das Archiv SOLL mit `docker compose up -d` aus `deploy/` vollständig starten (Caddy, API, Worker, PostgreSQL, Keycloak).
2. Das Archiv SOLL täglich eine verschlüsselte Datenbanksicherung nach Swiss Backup schreiben und 30 Tage, 12 Monate und 10 Jahresstände aufbewahren.
3. Das Repository SOLL eine Anleitung zur Wiederherstellung enthalten, die in einer Testumgebung erfolgreich durchgespielt wurde.
4. Das Archiv SOLL `/q/health` (Liveness/Readiness) und Kennzahlen (Aufträge offen, Fehler, Eingangskorb-Grösse) bereitstellen.
5. WENN Aufträge länger als 1 Stunde im Status `FEHLER` sind oder die Sicherung fehlschlägt, DANN SOLL das Archiv dem Inhaber eine E-Mail senden.
6. Das Repository SOLL die Verfahrensbeschreibung «Erfassen und Archivieren» (`docs/verfahrensbeschreibung.md`, höchstens 2 Seiten) enthalten.
7. Das Archiv SOLL alle Daten ausschliesslich in Rechenzentren in der Schweiz (Infomaniak) speichern und verarbeiten.

---

## Rückverfolgung Konzept → Spec
| Konzept | Requirement |
| --- | --- |
| D1 | 3 |
| D2 | 4 |
| D3 | 5 |
| D4 | 7 |
| D5 | 8 |
| D6 | 9 |
| I1 | 11 |
| I2 | 12 |
| I6 | 13 |
| S1 | 14 |
| M1 | 1 |
| B1 | 6, 15 |
| B2 | 10 |
| B3 | 2 |
| Phase 0 | 0 |
