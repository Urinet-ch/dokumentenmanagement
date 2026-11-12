# Requirements Document — 01 Archiv-Kern (Phase 0 und 1)

## Introduction
Diese Spec beschreibt den Archiv-Kern des UriNet Archivs: Dokumente aus Browser, Handy,
Generatoren, Google-Drive-Altbestand und CRM-CSV-Export werden erfasst, per OCR durchsuchbar
gemacht, von der KI mit Metadaten vorbelegt, im Eingangskorb bestätigt und nach GeBüV
unveränderbar abgelegt. Der Kern ist von Anfang an mandantenfähig (Schema pro Mandant),
auch wenn in Phase 1 nur der Mandant `urinet` produktiv ist.

Nicht Teil dieser Spec: E-Mail-Eingang, semantische Suche, Chat, AbaNinja, Jahresexport
(→ `02-abgleich-chat`), zweiter Mandant mit WordPress (→ `03-zweiter-mandant`).

Arbeitsweise: Die Spec folgt dem Kiro-Stil (Steering in `.kiro/steering/`, je Spec `requirements.md`,
`design.md`, `tasks.md`); umgesetzt wird mit Claude Code. Freigabe einer Spec heisst: Fabian merged
den Pull Request.

Schreibweise der Akzeptanzkriterien: EARS auf Deutsch.
- Immer: «Das Archiv SOLL …»
- Ereignis: «WENN …, DANN SOLL das Archiv …»
- Zustand: «SOLANGE …, SOLL das Archiv …»
- Unerwünscht: «FALLS …, DANN SOLL das Archiv …»
- Option: «WO …, SOLL das Archiv …»

Nachweis: Jedes Kriterium wird als Cucumber-Szenario mit Tag `@req-<x.y>` geprüft (BDD laut
Blueprint), Einzellogik zusätzlich mit JUnit. Ausnahmen tragen einen Vermerk:
- «Nachweis: manuell [Fabian]» – Fabian prüft mit echten Konten, Geräten oder Belegen und hält das Ergebnis im Spike- oder Abnahmebericht fest.
- «Nachweis: Dokument» – geprüft wird der Inhalt einer Datei im Repository.
- «Nachweis: JUnit» – geprüft durch benannte JUnit-Tests (z. B. ArchUnit), ohne Cucumber-Szenario.

Fehlerantworten sind Problem Details mit `fehlercode` (design.md); nennt ein Kriterium HTTP-Status
und Code, gelten beide. Noch festzulegende Werte stehen als «[offen: … – Entscheid vor Welle N]»;
das Kriterium ist erst nach diesem Entscheid umsetzbar.

## Glossary
- **Mandant** – abgeschlossener Datenbereich (eigenes DB-Schema, eigener Bucket), z. B. `urinet`.
- **Dokument** – ein erfasster Beleg oder Text mit Original-Datei, PDF/A-Fassung und Metadaten.
- **Dokumentdatei** – eine empfangene Datei eines Dokuments (Tabelle `dokument_datei`); ein mehrseitiges Dokument hat mehrere.
- **Original** – die Datei so, wie sie empfangen wurde (Foto, PDF, HEIC …); bei der Handy-Erfassung die unbearbeitete Rohaufnahme jeder Seite.
- **Arbeitsfassung** – zugeschnittene und entzerrte Fassung einer Handy-Aufnahme; Grundlage der PDF/A-Fassung, das Original bleibt daneben erhalten.
- **PDF/A** – durchsuchbare Archivfassung (PDF/A-2b) mit OCR-Textebene.
- **Status** – Lebenszyklus eines Dokuments: `NEU` (empfangen, Auftrag eingereiht) → `VERARBEITUNG` (PDF/A, OCR, KI) → `EINGANGSKORB` (wartet auf Bestätigung) → `ARCHIVIERUNG` (bestätigt, Kopie und Sperre laufen) → `ARCHIVIERT`. Fehlerzustände: `FEHLER` (Verarbeitung gescheitert), `ARCHIVIERUNG_FEHLER` (Archivierung gescheitert, bestätigte Metadaten bleiben). `VERWORFEN`: Dateien gelöscht, Datensatz bleibt.
- **Eingangskorb** – Liste der Dokumente mit KI-Vorschlägen, die noch bestätigt werden müssen, dazu Dokumente in `FEHLER` und `ARCHIVIERUNG_FEHLER`.
- **Archiviert** – Status `ARCHIVIERT`; gilt erst, wenn die Dateien unter `archiv/` liegen, die Prüfsummen verglichen und (bei gesetzlicher Pflicht) die Object-Lock-Sperre gesetzt ist.
- **Dokumentart** – Typ eines Dokuments (z. B. Ausgangsrechnung) mit Zusatzfeldern, Aufbewahrungsregel und gesetzlicher Pflicht.
- **Gesetzliche Pflicht** – Attribut `gesetzliche_pflicht` einer Dokumentart; nur bei «ja» gilt die unveränderbare Sperre (WORM) bis `aufbewahren_bis`.
- **Frist abgelaufen** – Kennzeichen eines Dokuments, dessen `aufbewahren_bis` bei der Bestätigung schon vorbei ist (Altbestand); der Inhaber entscheidet.
- **Vertraulichkeit** – `NORMAL`, `INTERN` oder `PERSONAL`; steuert die Sichtbarkeit nach der Rechtematrix (Requirement 2.6).
- **Erfasser** – Benutzer, der ein Dokument hochgeladen hat.
- **Zugeordnete Person** (`person_benutzer`) – Benutzer, dem ein `PERSONAL`-Dokument gehört, z. B. die eigene Lohnabrechnung.
- **Partner** – Kontakt (Kunde, Lieferant, Behörde), auf den sich ein Dokument bezieht.
- **Dublette** – Datei, deren SHA-256 im Mandanten schon zu einem nicht verworfenen Dokument gehört.
- **Generator** – eigenes Skript, das Dokumente erzeugt (`rechnung_gen.py`, `urinet_pdf.py`).
- **API-Token** – Zugang für Skripte, gebunden an einen Mandanten; Scope `generator` (Requirement 11) oder `import` (Requirement 12).
- **Swiss QR Code** – QR-Code des Zahlteils einer QR-Rechnung; wird deterministisch ausgelesen.
- **Golden-Datensatz** – 50 anonymisierte echte Belege mit erwarteten Metadaten zur Messung der KI; 30 zum Abstimmen, 20 zurückgehalten für das Gate. Liegt nur ausserhalb des Repositorys (`ARCHIV_GOLDEN_DIR`).
- **Manifest** – tägliche, signierte und zeitgestempelte Liste der Prüfsummen aller Dateien, die seit dem letzten Manifest archiviert wurden, verkettet mit dem vorherigen Manifest.
- **Zeitstempel** – RFC-3161-Zeitstempel über ein Manifest; belegt den Speicherzeitpunkt (Art. 9 GeBüV). Massgeblich ist bevorzugt der qualifizierte Zeitstempel nach ZertES des Hauptdienstes in der Schweiz (15.3); jedes Manifest trägt zusätzlich den Zeitstempel eines zweiten Dienstes (15.11).
- **Hauptdienst** – Zeitstempeldienst, dessen Stempel massgeblich ist: die TSA der Swiss Government PKI des BIT (ZertES-qualifiziert, in der Schweizer Trusted List), ohne Anmeldung und ohne formelle Anfrage genutzt (vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
- **Zweiter Zeitstempel** – Stempel desselben Hashes bei einem zweiten RFC-3161-Dienst aus einer konfigurierbaren Liste (z. B. FreeTSA); an ihn geht nur der Hash (15.11, 16.7).
- **App-Schlüssel** – S3-Zugangsdaten der Anwendung (Secret-Dateien `s3_app_zugang_id` und `s3_app_zugang_geheimnis`); sie liegen auf dem Server (16.9). Bei Infomaniak hat jeder S3-Schlüssel des Projekts technisch das Recht `s3:BypassGovernanceRetention` (Spike 0.2), die Anwendung nutzt es nie (6.5).
- **Inhaber-Zugang** – Zugangsdaten des Inhabers zum Public-Cloud-Projekt des Object Storage bei Infomaniak, mit denen S3-Schlüssel ausgestellt und Korrekturen an gesperrten Dateien ausgeführt werden; offline beim Inhaber, nie auf dem Server. Ersetzt den früheren «Bypass-Schlüssel», weil bei Infomaniak jeder S3-Schlüssel des Projekts das Recht `s3:BypassGovernanceRetention` hat (Spike 0.2).
- **Zweitkopie** – verschlüsselte Sicherung von Datenbanken und Bucket bei einem zweiten Schweizer Anbieter, unabhängig von Infomaniak (Hostpoint-Webhosting, 16.2); der tägliche Zugang kann nur anhängen. Sie schützt vor Verlust, ist aber kein unveränderbarer Datenträger nach GeBüV; die Unveränderbarkeit tragen Bucket und Manifeste (ADR-007).
- **Restore-Probe** – automatisierte, wiederkehrende Wiederherstellung der Sicherung in einer getrennten Umgebung auf der VPS der Stufe B (Compose-Projekt `archiv-probe`, 16.3, 16.23); in Stufe A nur als Funktionstest mit eigenem Testschlüssel (16.19).
- **Probe-Schlüssel** – zweiter Empfänger der Export-Verschlüsselung; sein privater Teil liegt nur auf der VPS der Stufe B, damit die Restore-Probe dort entschlüsseln kann (16.8, 16.23). Er ist ersetzbar und gehört nicht in die Notfallunterlagen.
- **Probe-Projekt** – eigenes Public-Cloud-Projekt bei Infomaniak nur für den Probe-Bucket, mit eigenem S3-Schlüssel ohne Zugriff auf das Projekt des Archivs (16.23).
- **Stufe P** – Stufenwerte der Restore-Probe (`archiv.stufe=P`): Sperren höchstens 1 Tag, Buckets mit Präfix `urinet-archiv-p-`, ohne Manifest, Zeitstempel, KI und Mails, nur in `deploy/probe/compose.yml` (16.24, 16.27).
- **Benutzerverzeichnis** – nur anfügende Tabelle, die jede Kennung `benutzer` des Zugriffsprotokolls einer Person zuordnet (10.10).
- **Notfallunterlagen** – versiegelte Zugangsdaten ausserhalb des Systems für den Notfall (Requirement 16.12).
- **Stufe A** – Betrieb ab Welle 1 bis zum Wechsel auf der bestehenden Infomaniak-VPS, nur mit Test- und anonymisierten Daten und im Speicherbudget aus `tech.md` (Requirement 16.15).
- **Stufe B** – Betrieb auf einer eigenen, grösseren Infomaniak-VPS (K5: VPS Lite 4 vCPU, 4 GB RAM, 80 GB, an Ort aufrüstbar, Requirement 16.1); Voraussetzung für echte Belege (Requirement 16.17).
- **Profil `lokal`** – Quarkus-Profil nur für den Entwicklerrechner: Das Sprachmodell läuft in LM Studio an `localhost`; nicht für Gate 0, Golden-Lauf, Abnahmen, CI oder Betrieb (Requirement 16.20–16.22, ADR-012).

---

## Phase 0 – Klären

### Requirement 0: Machbarkeit und Golden-Datensatz (Phase 0)
**User Story:** Als Inhaber möchte ich vor dem Bau der Oberfläche wissen, ob OCR und KI meine
Belege zuverlässig erkennen und ob Infomaniak alle nötigen Funktionen bietet, damit ich nicht
in eine Sackgasse investiere.

#### Acceptance Criteria
1. Das Archiv-Projekt SOLL einen Golden-Datensatz von 50 anonymisierten oder synthetischen (0.10) Belegen (genau 10 Quittungen, 10 Lieferantenrechnungen, 10 eigene Rechnungen/Offerten, 10 Behörden-/Versicherungsbriefe, 10 gemischte Handy-Fotos) mit einer Erwartungsdatei `erwartet.json` verwenden, der ausschliesslich ausserhalb des Repositorys unter `ARCHIV_GOLDEN_DIR` liegt; das Repository SOLL nur das Schema von `erwartet.json` und den Test enthalten. Nachweis: manuell [Fabian].
2. WENN der Prototyp den Golden-Datensatz verarbeitet, DANN SOLL er pro Beleg Art, Datum, Partner, Betrag und Belegnummer nach den Regeln aus 0.7 mit der Erwartung vergleichen und einen Bericht mit Trefferquote je Feld und gesamt ausgeben, getrennt nach Abstimmungs- und Prüfteil (0.8); der Lauf mit echten Belegen erfolgt nur manuell, nie in der CI.
3. WENN auf den 20 zurückgehaltenen Belegen (0.8) mindestens 18 in Art, Datum, Partner und Betrag nach den Regeln aus 0.7 korrekt sind und 0.9 erfüllt ist, DANN SOLL der Bericht das Gate «Phase 0 bestanden» ausweisen. Nachweis: manuell [Fabian].
4. Der Spike-Bericht SOLL für Infomaniak Object Storage (eigenes Public-Cloud-Projekt nur für den Object Storage) festhalten, ob Object Lock pro Objekt (`PutObjectRetention`, Modus GOVERNANCE) und Versionierung funktionieren, ob sich ein Schlüssel ohne Recht `s3:BypassGovernanceRetention` ausstellen lässt und ob der Modus COMPLIANCE verfügbar ist. Nachweis: manuell [Fabian].
5. Der Spike-Bericht SOLL für das bestehende Konto bei Infomaniak AI Services (Modelle verfügbar seit Prüfung 07.11.2026) festhalten, ob strukturierte JSON-Antworten und Werkzeugaufrufe (Tool Calling) mit Mistral-Small-4 funktionieren, welche Dimension die Embeddings haben, welche Form der Reranker-Aufruf hat und ob Bilder direkt gelesen werden können (Frage K1), sowie ob Infomaniak AI Eingaben speichert oder zum Training nutzt; der Auftragsverarbeitungsvertrag (AVV) SOLL unter `docs/vertrag/` referenziert sein. Nachweis: manuell [Fabian].
6. Der Spike-Bericht SOLL Spaltennamen, Trennzeichen und Zeichensatz eines echten CSV-Exports aus 21.CRM dokumentieren (Frage K4). Nachweis: manuell [Fabian].
7. WENN ein Vorschlag mit der Erwartung verglichen wird, DANN SOLL der Prototyp je Feld diese Regeln anwenden: Art und Datum exakt gleich, Betrag auf den Rappen gleich, Partner nach Normalisierung (design.md) gleich, Belegnummer exakt gleich nach Entfernen von Leerzeichen; fehlt ein Feld in der Erwartung, gilt es nur als korrekt, wenn auch der Vorschlag leer ist.
8. Der Golden-Datensatz SOLL fest geteilt sein in 30 Belege zum Abstimmen von Prompt und Schwellen und 20 zurückgehaltene Belege; die 20 zurückgehaltenen SOLLEN nie zum Abstimmen verwendet werden. Nachweis: manuell [Fabian].
9. FALLS im Prüfteil unter allen Feldern mit Sicherheit ab der Schwelle aus 7.6 (≥, Startwert 0.8) mehr als 1 Fehler steckt, DANN SOLL der Bericht das Gate als nicht bestanden ausweisen. Als Sicherheit gilt der Wert nach der deterministischen Nachbearbeitung (Swiss QR Code, Kennungen des Mandanten, Plausibilitätsregeln, Eingabeart), nicht allein die Selbsteinschätzung des Modells. Schwelle, Prompt, Modell und Regeln werden nur auf dem Abstimmungsteil festgelegt und vor dem Gate-Lauf eingefroren; nach dem Gate-Lauf werden sie für denselben Prüfteil nicht mehr geändert.
10. Der Golden-Datensatz DARF aus synthetischen Belegen bestehen, die ein reproduzierbarer Generator ohne echte Personen- oder Firmendaten erzeugt; WENN echte Belege vorliegen, DANN SOLL der Bericht sie zusätzlich als Realitätsprüfung getrennt ausweisen. Nachweis: manuell [Fabian].

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
5. WENN ein Mandant neu angelegt wird, DANN SOLL das Archiv das Mandanten-Schema über die versionierten Migrationen (Changelog `db/mandant`) auf den aktuellen Stand bringen, den Bucket mit Object Lock und Versionierung anlegen und die Standard-Dokumentarten aus dem Seed des Mandanten einspielen (für `urinet`: `backend/src/main/resources/seed/urinet/dokumentarten.json`). Spec 04, 1.1 ergänzt dieses Kriterium: Beim Anlegen jedes Mandanten liest das Archiv zusätzlich den mandantenunabhängigen Seed-Teil `backend/src/main/resources/seed/_alle/dokumentarten-wissen.json` ein (Dokumentart `WISSENSQUELLE`; umgesetzt in Spec 04, Aufgabe 13.2). Spec 05, 1.6 ergänzt dieses Kriterium: Der Seed des Mandanten ist `backend/src/main/resources/seed/<code>/konfiguration.json` (ersetzt `seed/<code>/dokumentarten.json`); das Archiv spielt ihn zusammen mit dem Seed-Teil `_alle` (Spec 04, 1.1) als Konfigurationsversion 1 mit Quelle `SEED` ein (umgesetzt in Spec 05, Aufgabe 17.7).
6. Aufträge in der Auftragsschlange SOLLEN den Mandanten mitführen, und der Worker SOLL jeden Auftrag im Kontext dieses Mandanten ausführen.
7. Das Archiv SOLL Datenbankschemas ausschliesslich über versionierte Migrationen ändern (Changelog `db/public` für gemeinsame Tabellen, `db/mandant` je Mandant) und beim Start nur prüfen, ob Schema und Entitäten übereinstimmen, ohne selbst Tabellen anzulegen oder zu ändern.

### Requirement 2: Anmeldung und Rollen (B3)
**User Story:** Als Inhaber möchte ich, dass nur berechtigte Personen Dokumente sehen,
damit Lohn- und Vertragsdaten geschützt sind.

#### Acceptance Criteria
1. Das Archiv SOLL die Anmeldung über Keycloak (OIDC mit PKCE) mit Zwei-Faktor (TOTP) für alle Rollen verlangen.
2. Das Archiv SOLL die Rollen `inhaber`, `mitarbeiter`, `treuhaender` und `geschaeftsstelle` kennen und gemäss der Rechtematrix (2.6) durchsetzen. Hat ein Benutzer mehrere dieser Rollen, gilt nur die stärkste in der Rangfolge `inhaber`, `geschaeftsstelle`, `mitarbeiter`, `treuhaender`; die Rechte werden nicht vereinigt (entschieden von Fabian am 10.11.2026).
3. FALLS ein Benutzer ein Dokument anfragt, das er nach den Zeilen A2 bis A4 der Rechtematrix nicht sehen darf, DANN SOLL das Archiv es weder in Listen noch in Suchtreffern noch in Zählern liefern und auf Direktzugriff mit HTTP 404 antworten.
4. FALLS ein Benutzer mit Rolle `treuhaender` eine schreibende Aktion ausführt, DANN SOLL das Archiv mit HTTP 403 antworten.
5. Das Archiv SOLL für Skripte API-Tokens mit Scope `generator` (Requirement 11) und `import` (Requirement 12) unterstützen, die an einen Mandanten gebunden sind, nur gehasht (Argon2id) gespeichert werden und widerrufbar sind; ein Token SOLL nur die Endpunkte seines Scopes erreichen, sonst HTTP 403.
6. Das Archiv SOLL die folgende Rechtematrix durchsetzen. «erlaubt»: Aktion wird ausgeführt; «403»: HTTP 403, nichts geändert; «404»: das Dokument gilt als nicht vorhanden (2.3). Ist ein Dokument für den Benutzer nach A2 bis A4 unsichtbar, gilt 404 vor 403.

| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |
| --- | --- | --- | --- | --- | --- |
| A1 | Dokumente erfassen (Upload, Handy) | erlaubt | erlaubt | 403 | erlaubt |
| A2 | `NORMAL` lesen, suchen, herunterladen | erlaubt | erlaubt | erlaubt | erlaubt |
| A3 | `INTERN` lesen, suchen, herunterladen | erlaubt | 404 | erlaubt | erlaubt |
| A4 | `PERSONAL` lesen, suchen, herunterladen | erlaubt | nur eigene ² | erlaubt (nur lesend) | erlaubt |
| A5 | Eingangskorb einsehen | erlaubt | nur selbst erfasste | 403 | erlaubt |
| A6 | Metadaten ändern, Vorschläge korrigieren | erlaubt | 403 | 403 | erlaubt |
| A7 | Bestätigen | erlaubt | 403 | 403 | erlaubt |
| A8 | Vertraulichkeit und `person_benutzer` setzen | erlaubt | 403 | 403 | erlaubt |
| A9 | Verwerfen | erlaubt | 403 | 403 | erlaubt |
| A10 | Verarbeitung oder Archivierung wiederholen | erlaubt | 403 | 403 | erlaubt |
| A11 | Dokumentarten verwalten | erlaubt | 403 | 403 | 403 |
| A12 | Kontakte ändern, CSV-Import | erlaubt | 403 | 403 | 403 |
| A13 | Zugriffsprotokoll einsehen | erlaubt | 403 | 403 | 403 |
| A14 | API-Tokens verwalten | erlaubt | 403 | 403 | 403 |

¹ `geschaeftsstelle` hat in Phase 1 keinen produktiven Benutzer (Mandant 2, `03-zweiter-mandant`); Dokumentrechte A1 bis A10 wie dort beschrieben. Verwaltungsrechte A11 bis A14: [offen: Rechte der Geschäftsstelle – Entscheid in `03-zweiter-mandant`], bis dahin 403.
² eigene: Die Art ist bestätigt und `person_benutzer` ist der Benutzer selbst, oder die Art ist noch nicht bestätigt und er hat das Dokument erfasst (2.21). Ein unbestätigtes Dokument, das ein anderer erfasst hat, ist nicht seines, auch wenn `person_benutzer` er selbst ist (entschieden von Fabian am 11.11.2026).

Die Zeilen A15 bis A19 (Wissensbasis) ergänzt Spec 04, Requirement 12.1; sie gehören zu dieser Matrix, mit derselben Bedeutung von «erlaubt», «403» und «404» und demselben Vorrang 404 vor 403. Massgeblich für ihren Wortlaut ist Spec 04, Requirement 12.1.

7. FALLS ein Benutzer mit Rolle `treuhaender` Dokumente erfassen will (A1), DANN SOLL das Archiv mit HTTP 403 antworten und nichts speichern.
8. WENN ein angemeldeter Benutzer des Mandanten ein Dokument mit Vertraulichkeit `NORMAL` anfragt (A2), DANN SOLL das Archiv es unabhängig von der Rolle liefern.
9. FALLS ein Benutzer mit Rolle `mitarbeiter` ein Dokument mit Vertraulichkeit `INTERN` anfragt (A3), DANN SOLL das Archiv nach 2.3 verfahren.
10. FALLS ein Benutzer mit Rolle `mitarbeiter` ein `PERSONAL`-Dokument anfragt, das nicht seines ist (A4, Fussnote ²), DANN SOLL das Archiv nach 2.3 verfahren.
11. FALLS ein Benutzer mit Rolle `treuhaender` den Eingangskorb abruft (A5), DANN SOLL das Archiv mit HTTP 403 antworten.
12. FALLS ein Benutzer mit Rolle `mitarbeiter` oder `treuhaender` Metadaten ändern oder einen Vorschlag korrigieren will (A6), DANN SOLL das Archiv mit HTTP 403 antworten und nichts ändern.
13. FALLS ein Benutzer mit Rolle `mitarbeiter` oder `treuhaender` ein Dokument bestätigen will (A7), DANN SOLL das Archiv mit HTTP 403 antworten und den Status nicht ändern.
14. FALLS ein Benutzer mit Rolle `mitarbeiter` oder `treuhaender` die Vertraulichkeit oder `person_benutzer` setzen will (A8), DANN SOLL das Archiv mit HTTP 403 antworten und nichts ändern.
15. FALLS ein Benutzer mit Rolle `mitarbeiter` oder `treuhaender` ein Dokument verwerfen will (A9), DANN SOLL das Archiv mit HTTP 403 antworten und keine Datei löschen.
16. FALLS ein Benutzer mit Rolle `mitarbeiter` oder `treuhaender` die Verarbeitung oder Archivierung wiederholen will (A10), DANN SOLL das Archiv mit HTTP 403 antworten.
17. FALLS ein Benutzer ohne Rolle `inhaber` Dokumentarten anlegen, ändern oder deaktivieren will (A11), DANN SOLL das Archiv mit HTTP 403 antworten.
18. FALLS ein Benutzer ohne Rolle `inhaber` Kontakte ändern oder einen CSV-Import starten oder übernehmen will (A12), DANN SOLL das Archiv mit HTTP 403 antworten.
19. FALLS ein Benutzer ohne Rolle `inhaber` das Zugriffsprotokoll abruft (A13), DANN SOLL das Archiv mit HTTP 403 antworten.
20. FALLS ein Benutzer ohne Rolle `inhaber` API-Tokens anlegen, anzeigen oder widerrufen will (A14), DANN SOLL das Archiv mit HTTP 403 antworten.
21. SOLANGE die Art eines Dokuments nicht bestätigt ist, SOLL das Archiv es als `PERSONAL` behandeln, sichtbar nur für Rollen mit Recht A7 und für den Erfasser.
22. WENN ein Dokument bestätigt wird, DANN SOLL das Archiv die bestätigte Vertraulichkeit übernehmen, vorbelegt mit dem KI-Vorschlag bzw. der Standard-Vertraulichkeit der Art (Requirement 7.2).
23. Das Archiv SOLL `vertraulichkeit` und `person_benutzer` nur über Aktion A8 oder über die Generator-Metadaten (Requirement 11.10) setzen.
24. WENN ein Benutzer mit Rolle `mitarbeiter` den Eingangskorb abruft (A5), DANN SOLL das Archiv nur die von ihm erfassten Dokumente liefern.
25. WO die Anmeldung mit Google eingerichtet ist (Option O.8, Auftrag Fabian 12.11.2026), SOLL das Archiv die Anmeldung über Google als Identity Provider in Keycloak nur für Benutzer erlauben, deren Keycloak-Benutzer schon mit diesem Google-Konto verknüpft ist; Rollen und `mandant` SOLLEN ausschliesslich aus dem Keycloak-Benutzer kommen, nie aus Angaben von Google. Google verarbeitet dabei Google-Konto, Zeitpunkt, IP-Adresse und Gerät der Anmeldung, nie Archivdaten; das ist eine Ausnahme zu 16.7. Ab welcher Stufe: [offen: Google-Anmeldung schon in Stufe A oder erst ab Stufe B – Entscheid in Aufgabe O.8].
26. FALLS sich jemand mit einem Google-Konto anmeldet, das mit keinem Keycloak-Benutzer des Realms verknüpft ist, DANN SOLL Keycloak die Anmeldung ablehnen, keinen Benutzer anlegen und kein Konto anhand der E-Mail-Adresse automatisch verknüpfen (keine Selbstregistrierung, design.md «Sicherheit»).
27. Das Archiv SOLL die Verknüpfung eines Google-Kontos nur zulassen [offen: Verknüpfung durch den Benutzer selbst im Kontoportal nach einer Anmeldung mit Passwort und TOTP (Vorschlag) oder nur durch den Admin – Entscheid in Aufgabe O.8]; WENN eine Verknüpfung angelegt oder gelöst wird, DANN SOLL Keycloak das im Ereignisprotokoll festhalten, und nach dem Lösen SOLLEN die Sitzungen des Benutzers enden.
28. WENN sich ein Benutzer über Google anmeldet, DANN SOLL Keycloak danach TOTP nach 2.1 verlangen (Post-Broker-Flow); Keycloak kann den zweiten Faktor von Google nicht prüfen. Ein Verzicht auf TOTP bei der Anmeldung über Google ginge nur mit einer Ausnahme in 2.1: [offen: Verzicht auf TOTP bei Google-Anmeldung – Entscheid in Aufgabe O.8]; bis dahin gilt 2.1 unverändert.
29. Das Archiv SOLL das Client-Geheimnis von Google nur als Secret wie die übrigen führen (GitHub-Secret `GOOGLE_CLIENT_SECRET`, Secret-Datei `google_client_secret`; nie im Realm-Export, im Repository oder in Logs); der Realm-Export SOLL mit O.8 höchstens den Identity Provider `google` mit Platzhalter für das Geheimnis enthalten (geprüft von `RealmExportTest` und `RealmExportKeycloakTest`).

### Requirement 3: Upload im Browser (D1)
**User Story:** Als Inhaber möchte ich PDFs und Bilder vom Mac per Drag & Drop hochladen,
damit digitale Dokumente ohne Umweg ins Archiv kommen.

#### Acceptance Criteria
1. Das Archiv SOLL Dateien der Typen PDF, JPEG, PNG, HEIC/HEIF und TIFF bis 50 MB pro Datei annehmen, auch mehrere in einem Vorgang.
2. WENN eine Datei hochgeladen wird, DANN SOLL das Archiv innert 2 Sekunden mit HTTP 202 und je Datei einem Ergebnis `{id, status}` antworten und die Verarbeitung als Auftrag einreihen (Messpunkt: Server, ab vollständig empfangener Anfrage; Eingabe und Last: 200 synthetische PDFs zu 200 KB mit 4 gleichzeitigen Uploads und 50 zu 10 MB mit 2 gleichzeitigen, der Worker verarbeitet die eingereihten Aufträge dabei mit; 20 Aufwärm-Uploads zählen nicht; Hardware und Ort nach 16.26; gilt erst ab Stufe B, 16.16) (geändert 12.11.2026: Messbedingungen bisher offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
3. FALLS der Dateityp nicht unterstützt ist (geprüft am Dateiinhalt, nicht an der Endung), DANN SOLL das Archiv die Datei mit HTTP 415 `DATEITYP_UNBEKANNT` und einer verständlichen Meldung ablehnen.
4. FALLS eine Datei mit identischer SHA-256-Prüfsumme im Mandanten bereits zu einem nicht verworfenen Dokument gehört (Prüfung je Datei), DANN SOLL das Archiv kein neues Dokument anlegen und für diese Datei das Ergebnis `{status: "DUBLETTE", vorhandenId}` liefern; darf der Benutzer das vorhandene Dokument nicht sehen (2.3), SOLL es nur «bereits vorhanden» ohne ID melden.
5. WO der Benutzer mehrere Bilder als «ein Dokument» markiert, SOLL das Archiv sie in der gewählten Reihenfolge zu einem mehrseitigen Dokument zusammenfassen.
6. FALLS eine Datei grösser als 50 MB ist, DANN SOLL das Archiv sie mit HTTP 413 `DATEI_ZU_GROSS` ablehnen.
7. FALLS eine Datei leer oder beschädigt ist, DANN SOLL das Archiv sie mit HTTP 422 `DATEI_UNGUELTIG` ablehnen.
8. WENN mehrere Dateien in einem Vorgang hochgeladen werden, DANN SOLL das Archiv je Datei ein eigenes Ergebnis liefern (angenommen, `DUBLETTE` oder Fehlercode nach 3.3, 3.6, 3.7) und gültige Dateien trotz abgelehnter Nachbarn annehmen.

### Requirement 4: Handy-Erfassung als Web-App (D2)
**User Story:** Als Inhaber möchte ich Post und Quittungen mit dem Handy fotografieren,
damit Papier sofort erfasst ist.

#### Acceptance Criteria
1. Die Web-App SOLL als installierbare PWA auf iOS (Safari) und Android (Chrome) funktionieren ([offen: Mindestversionen iOS und Android – Entscheid vor Welle 3]); Prüfumfang je Plattform auf einem echten Gerät: Installation und 4.2 bis 4.8. Nachweis: manuell [Fabian].
2. WENN der Benutzer «Erfassen» wählt, DANN SOLL die App die Rückkamera öffnen und mehrere Seiten nacheinander aufnehmen lassen.
3. WENN eine Seite aufgenommen ist, DANN SOLL die App die Dokumentkanten erkennen, einen Zuschnitt vorschlagen, die Perspektive entzerren und eine Vorschau zeigen, die der Benutzer anpassen oder verwerfen kann; erkennt sie keine Kanten, SOLL sie das ganze Bild verwenden.
4. SOLANGE das Handy offline ist, SOLL die App Aufnahmen in einer lokalen Warteschlange (IndexedDB) halten und den Zähler «wartet auf Upload» anzeigen.
5. WENN die Verbindung wieder besteht, DANN SOLL die App die Warteschlange automatisch hochladen und erfolgreich übertragene Einträge lokal löschen; meldet der Server eine Dublette (3.4), gilt der Eintrag als übertragen und wird ebenfalls gelöscht.
6. FALLS ein Upload dreimal wegen Netzwerkfehler oder HTTP 5xx fehlschlägt, DANN SOLL die App den Eintrag als fehlerhaft markieren und eine manuelle Wiederholung anbieten, ohne ihn zu löschen; Versuche im Offline-Zustand zählen nicht.
7. WENN eine Seite hochgeladen wird, DANN SOLL die App die unbearbeitete Rohaufnahme als Original und die zugeschnittene Fassung als Arbeitsfassung übertragen.
8. FALLS der Server einen Upload mit HTTP 4xx ablehnt (ausser 401, dann neu anmelden), DANN SOLL die App den Eintrag sofort mit der Meldung des Servers als fehlerhaft markieren, ohne ihn zu löschen.

### Requirement 5: Verarbeitung, OCR und PDF/A (D3)
**User Story:** Als Inhaber möchte ich, dass jedes Dokument durchsuchbar und langfristig lesbar ist,
damit ich es auch in zehn Jahren noch öffnen und finden kann.

#### Acceptance Criteria
1. WENN ein Verarbeitungsauftrag startet, DANN SOLL der Worker Bilder (inkl. HEIC) zu PDF wandeln, Seiten drehen und entzerren und mit OCRmyPDF (Sprachen `deu+eng`) ein PDF/A-2b erzeugen.
2. WO ein PDF bereits eine Textebene hat, SOLL der Worker kein neues OCR ausführen, aber trotzdem eine PDF/A-Fassung erzeugen (`--skip-text`).
3. Das Archiv SOLL den erkannten Volltext pro Dokument speichern und für die deutsche Volltextsuche indexieren.
4. FALLS die Verarbeitung fehlschlägt, DANN SOLL das Archiv sie mit wachsender Wartezeit erneut versuchen, insgesamt höchstens 3 Versuche, und danach den Status `FEHLER` mit Fehlertext setzen, ohne das Original zu verlieren.
5. Der Worker SOLL ein einseitiges Handy-Foto innert 30 Sekunden fertig verarbeiten, getestet als p95 vom Start des Auftrags bis zum Status `EINGANGSKORB`, ohne KI-Wartezeit (Eingabe und Last: synthetisches einseitiges JPEG mit 12 MP (4032×3024), 50 Läufe nacheinander bei sonst leerer Auftragsschlange, dazu dieselbe Aufnahme als HEIC und Läufe neben einem 20-seitigen Scan; 20 Aufwärmläufe zählen nicht; Hardware und Ort nach 16.26; gilt erst ab Stufe B, 16.16) (geändert 12.11.2026: Messbedingungen bisher offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
6. FALLS ein Auftrag die in design.md festgelegte Zeitgrenze seines Auftragstyps überschreitet, DANN SOLL der Worker ihn abbrechen und als fehlgeschlagenen Versuch nach 5.4 werten.
7. FALLS ein Auftrag im Zustand `LAEUFT` kein Lebenszeichen mehr gibt, DANN SOLL das Archiv ihn nach Ablauf der Zeitgrenze wieder freigeben und als fehlgeschlagenen Versuch nach 5.4 werten.
8. WO ein PDF digital signiert ist, SOLL der Worker die PDF/A-Fassung auch dann erzeugen, wenn die Signatur dabei verloren geht; das Original SOLL unverändert bleiben.
9. FALLS keine PDF/A-Fassung erzeugt werden kann (z. B. verschlüsseltes PDF), DANN SOLL das Archiv das Dokument mit dem Vermerk `pdfa_fehlt` und dem Fehlertext in den Eingangskorb legen; der Inhaber entscheidet, ob das Original allein archiviert oder verworfen wird.
10. WO ein Dokument aus der Handy-Erfassung stammt, SOLL der Worker die PDF/A-Fassung aus der Arbeitsfassung erzeugen und Original und Arbeitsfassung unverändert behalten.

### Requirement 6: Unveränderbare Ablage und Prüfsummen (B1)
**User Story:** Als Inhaber möchte ich die Vorgaben von Art. 958f OR und GeBüV erfüllen,
damit gescannte Belege als Buchungsbelege gelten.

#### Acceptance Criteria
1. WENN eine Datei empfangen wird, DANN SOLL das Archiv vor jeder weiteren Verarbeitung je empfangener Datei die SHA-256-Prüfsumme berechnen und mit Zeitpunkt in `dokument_datei` festhalten.
2. SOLANGE ein Dokument nicht archiviert ist, SOLL das Archiv seine Dateien unter dem Präfix `eingang/` ohne Sperre ablegen, damit Fehlerfassungen gelöscht werden können.
3. WENN der Auftrag `ARCHIVIEREN` läuft, DANN SOLL das Archiv Original, Arbeitsfassung (falls vorhanden) und PDF/A unter `archiv/` ablegen, die Prüfsummen erneut vergleichen, je Datei die S3-Versions-ID speichern, bei Arten mit `gesetzliche_pflicht` eine Object-Lock-Sperre (GOVERNANCE) bis `aufbewahren_bis` setzen und erst danach den Status `ARCHIVIERT` setzen.
4. FALLS die Prüfsumme nach dem Kopieren abweicht oder die Sperre nicht gesetzt werden kann, DANN SOLL das Archiv die Archivierung abbrechen, den Status `ARCHIVIERUNG_FEHLER` setzen, die bestätigten Metadaten behalten und den Inhaber benachrichtigen.
5. Das Archiv SOLL archivierte Dateien über keine Schnittstelle überschreiben oder löschen, und die Anwendung SOLL nur den App-Schlüssel erhalten; sie SOLL bei keinem S3-Aufruf den Header `x-amz-bypass-governance-retention` (AWS SDK: `bypassGovernanceRetention`) setzen und keine Sperre verkürzen (Nachweis: JUnit (Request-Interceptor in den Integrationstests prüft, dass kein S3-Aufruf der Anwendung den Header sendet; ArchUnit-Regel gegen `bypassGovernanceRetention` im Produktionscode, Testklassen ausgenommen, ergänzend; MinIO aus den Compose Dev Services: Löschen und Verkürzen einer gesperrten Version scheitern mit den Aufrufen der Anwendung)), weil der App-Schlüssel bei Infomaniak technisch das Recht `s3:BypassGovernanceRetention` hat (Spike 0.2; ADR-007, Nachtrag 09.11.2026) und GOVERNANCE die Dateien unter `archiv/` darum nur gegen Fehler der Anwendung und Versehen schützt.
6. FALLS ein Benutzer ein Dokument im Status `EINGANGSKORB` oder `FEHLER` verwirft, DANN SOLL das Archiv die Dateien löschen, den Vorgang mit Prüfsumme und Grund protokollieren und das Dokument als `VERWORFEN` behalten.
7. FALLS ein Benutzer ein Dokument im Status `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER` oder `ARCHIVIERT` verwerfen will, DANN SOLL das Archiv mit HTTP 409 antworten und keine Datei löschen.
8. WENN ein Benutzer mit Recht A10 `POST /api/v1/dokumente/{id}/wiederholen` für ein Dokument im Status `ARCHIVIERUNG_FEHLER` aufruft, DANN SOLL das Archiv den Status `ARCHIVIERUNG` setzen und nur die Archivierung erneut ausführen; die bestätigten Metadaten SOLLEN unverändert bleiben.
9. WENN ein unbefristetes Dokument (9.3) einer Art mit `gesetzliche_pflicht` archiviert wird, DANN SOLL das Archiv eine Sperre von 10 Jahren setzen, die der Prüflauf verlängert (15.9); unbefristete Dokumente anderer Arten bleiben nach 6.12 ohne Sperre.
10. Der Inhaber-Zugang (Zugangsdaten, mit denen S3-Schlüssel des Projekts ausgestellt und Korrekturen an gesperrten Dateien ausgeführt werden) SOLL weder auf dem Server noch in GitHub-Secrets liegen, sondern nur offline beim Inhaber (Notfallunterlagen, 16.12). Nachweis: manuell [Fabian].
11. Das Archiv SOLL Manifeste unter `manifest/` im Modus COMPLIANCE sperren, weil der App-Schlüssel bei Infomaniak das Recht `s3:BypassGovernanceRetention` hat (Spike 0.2; Option B nach ADR-007, Nachtrag 09.11.2026) und eine Manipulation der Dateien unter `archiv/` nur über ein Manifest auffällt, dessen Sperre kein Schlüssel des Projekts aufheben kann, auch nicht mit Bypass-Header [T 4b/4c] (15.5). SOLANGE das Archiv in Tests, in der lokalen Entwicklung oder in Stufe A (nur Testdaten, 16.15) läuft, SOLL das Archiv per Konfiguration einen abweichenden Sperrmodus für `manifest/` zulassen (GOVERNANCE oder COMPLIANCE mit kürzerer Frist), damit Test-Buckets löschbar bleiben. In Stufe A SOLL das Archiv zusätzlich die Sperrfrist der Dokumente unter `archiv/` auf höchstens 1 Tag begrenzen (GOVERNANCE), und die Buckets SOLLEN den Präfix `urinet-archiv-a-` tragen, damit die Test-Buckets beim Wechsel (16.17) ohne Bypass-Header löschbar sind (entschieden von Fabian am 09.11.2026). FALLS ab Stufe B (16.17) für `manifest/` ein anderer Modus als COMPLIANCE oder eine kürzere Frist als nach 15.4, eine Höchstfrist für Dokumente unter `archiv/` oder der Bucket-Präfix der Stufe A konfiguriert ist, DANN SOLL das Archiv den Start abbrechen (Nachweis: JUnit (Konfigurationstest aus Aufgabe 2.3)) (entschieden von Fabian am 09.11.2026). Dasselbe gilt ab Stufe B für den Bucket-Präfix der Stufe P `urinet-archiv-p-`; die Werte der Restore-Probe regeln 16.24 und 16.27 (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
12. WO eine Dokumentart `gesetzliche_pflicht = nein` hat, SOLL das Archiv ihre Dateien unter `archiv/` ohne Object-Lock-Sperre ablegen; Prüfsummen, Manifest und 6.5 gelten trotzdem.
13. FALLS `wiederholen` für ein Dokument aufgerufen wird, das weder in `FEHLER` noch in `ARCHIVIERUNG_FEHLER` steht, DANN SOLL das Archiv mit HTTP 409 antworten.

### Requirement 7: KI-Vorschlag der Metadaten (D4)
**User Story:** Als Inhaber möchte ich, dass die KI Art, Datum, Partner und Betrag vorschlägt,
damit ich nur noch bestätigen muss.

#### Acceptance Criteria
1. WENN der Volltext vorliegt, DANN SOLL das Archiv das Sprachmodell einmal mit Text bzw., wenn das Original ein Bild ist (Typ nach Inhalt), mit den gerenderten Seiten des Dokuments (Fassung: design.md «KI», Bildweg), Liste der Dokumentarten, den Werten aus dem Swiss QR Code (7.11) und bis zu 5 Korrekturbeispielen aus der Vorauswahl nach 7.9 aufrufen und eine Antwort nach festem JSON-Schema verlangen. Spec 05, 2.5 und 8.1 ergänzen dieses Kriterium: Die Liste der Dokumentarten enthält nur Arten mit `ki_vorschlag = ja`; Prompt und Antwortschema bildet das Archiv aus der Konfiguration des Mandanten (umgesetzt in Spec 05, Aufgabe 18.1).
2. Der Vorschlag SOLL mindestens enthalten: Dokumentart, Titel, Belegdatum, Partnername, Belegnummer, Richtung (Einnahme/Ausgabe), Betrag, Währung, Fälligkeit, Vertraulichkeit (vorbelegt mit der Standard-Vertraulichkeit der vorgeschlagenen Art), Tags und pro Feld eine Sicherheit von 0 bis 1. Spec 05, 8.1 ergänzt dieses Kriterium: Zusätzlich enthält der Vorschlag die `ki_felder` der Arten und die Klassifikationen mit «KI darf vorschlagen»; Werte, die nicht in der Konfiguration stehen, gelten als «kein Vorschlag» für dieses Feld; `zugriff` und `veroeffentlicht` schlägt die KI nie vor (umgesetzt in Spec 05, Aufgabe 18.1).
3. FALLS die Antwort nicht dem Schema entspricht, DANN SOLL das Archiv einmal mit Fehlerhinweis nachfragen und sonst das Dokument ohne Vorschlag in den Eingangskorb legen.
4. WENN ein Partnername vorgeschlagen wird, DANN SOLL das Archiv ihn mit den Kontakten abgleichen (E-Mail, UID, IBAN, sonst Namensähnlichkeit ≥ 0.6) und den besten Treffer verknüpfen oder «neuer Partner?» markieren.
5. WENN eine Belegnummer einem bekannten Muster entspricht (siehe `structure.md`) und der Mandant selbst Aussteller des Belegs ist, DANN SOLL das Archiv die Dokumentart entsprechend setzen oder bestätigen. Spec 05, 2.3 und 8.2 ergänzen dieses Kriterium: Die Muster liest das Archiv aus `belegnummer_muster` der Dokumentarten des Mandanten, ob der Mandant Aussteller ist, über `selbst_ausgestellt` und `kennungen`; die Muster in `structure.md` sind die Werte des Mandanten `urinet` (umgesetzt in Spec 05, Aufgaben 17.3 und 18.1).
6. Das Archiv SOLL Felder mit Sicherheit unter der Schwelle (Startwert 0.8, angehoben nach 0.9) im Eingangskorb als «bitte prüfen» markieren.
7. WENN der Benutzer einen Vorschlag korrigiert, DANN SOLL das Archiv die Korrektur als Beispiel mit den Merkmalen der Vorauswahl (7.9) speichern und die Korrekturquote je Feld als Kennzahl fortschreiben (16.4).
8. Das Archiv SOLL die Anzahl Tokens pro KI-Aufruf und Mandant speichern.
9. WENN ein Beleg für den KI-Aufruf vorbereitet wird, DANN SOLL das Archiv die Korrekturbeispiele vorher deterministisch über IBAN, UID, E-Mail, Swiss QR Code und Belegnummer-Muster auswählen, ohne Treffer die 5 neuesten Beispiele mitgeben und keinen zweiten KI-Aufruf für die Auswahl ausführen. Spec 05, 2.3 und 2.4 ergänzen dieses Kriterium: Die Belegnummer-Muster kommen aus den Dokumentarten des Mandanten und werden nur mit der Zeitgrenze `archiv.dokumentart.muster-zeitgrenze` ausgewertet; ein Muster, das sie überschreitet, gilt als «trifft nicht zu» und wird protokolliert (umgesetzt in Spec 05, Aufgabe 18.1).
10. WENN eine Korrektur bei Partner X gespeichert ist, DANN SOLL der nächste Beleg mit der IBAN von X dieses Beispiel im KI-Aufruf enthalten.
11. WO ein Beleg einen Swiss QR Code enthält, SOLL das Archiv ihn deterministisch auslesen und dessen Werte (IBAN, Betrag, Währung, Referenz, Zahlungsempfänger) vor den Werten der KI verwenden.
12. FALLS ein Fremdbeleg eine Belegnummer im Muster des Mandanten trägt (Testfall: Lieferantenrechnung `2026-123`), DANN SOLL das Archiv die Art nicht aufgrund des Musters ändern. Spec 05, 2.1, 2.3 und 8.2 ergänzen dieses Kriterium: «Muster des Mandanten» sind die `belegnummer_muster` seiner Dokumentarten; die Umstufung eines Fremdbelegs richtet sich nach `fremdbeleg_art` der vorgeschlagenen Art (umgesetzt in Spec 05, Aufgabe 18.1).

### Requirement 8: Eingangskorb (D5)
**User Story:** Als Inhaber möchte ich neue Dokumente schnell prüfen und bestätigen,
auch auf dem Handy, damit nichts liegen bleibt.

#### Acceptance Criteria
1. Der Eingangskorb SOLL alle Dokumente im Status `EINGANGSKORB`, `FEHLER` und `ARCHIVIERUNG_FEHLER`, soweit Requirement 2.3 und Zeile A5 der Rechtematrix es erlauben, neueste zuerst zeigen, mit Vorschaubild, Vorschlägen und markierten unsicheren Feldern.
2. WENN ein Benutzer mit Recht A7 «Bestätigen» wählt, DANN SOLL das Archiv die Pflichtfelder der Dokumentart prüfen, `aufbewahren_bis` berechnen (9.3), den Status auf `ARCHIVIERUNG` setzen und den Auftrag `ARCHIVIEREN` einreihen; den Status `ARCHIVIERT` setzt erst Requirement 6.3.
3. FALLS Pflichtfelder fehlen, DANN SOLL das Archiv die Bestätigung mit HTTP 422 `PFLICHTFELD_FEHLT` verweigern und die fehlenden Felder nennen.
4. Der Eingangskorb SOLL auf einem Handy mit 375 px Breite ohne horizontales Scrollen bedienbar sein; Bestätigen eines korrekt vorgeschlagenen Dokuments SOLL höchstens zwei Tipps brauchen.
5. WENN ein Benutzer mit Recht A9 «Verwerfen» wählt, DANN SOLL das Archiv nach Rückfrage und Angabe eines Grundes Requirement 6.6 ausführen.
6. Die App SOLL die Anzahl Dokumente im Eingangskorb in der Navigation anzeigen.
7. SOLANGE ein Dokument im Status `FEHLER` steht, SOLL der Eingangskorb den Fehlertext zeigen und «erneut verarbeiten» (`POST /api/v1/dokumente/{id}/wiederholen`, Status zurück auf `NEU`) und «verwerfen» anbieten; bei `ARCHIVIERUNG_FEHLER` SOLL er «Archivierung wiederholen» (6.8) anbieten.
8. FALLS ein Benutzer ein Dokument bestätigt, das nicht mehr im Status `EINGANGSKORB` ist, DANN SOLL das Archiv mit HTTP 409 antworten und nichts ändern.
9. FALLS beim Bestätigen einer Art, die der Mandant selbst ausstellt (für `urinet`: Ausgangsrechnung, Offerte, Lohnabrechnung), die Belegnummer für diese Art im Mandanten schon vergeben ist, DANN SOLL das Archiv die Bestätigung mit HTTP 409 verweigern. Spec 05, 2.2 ergänzt dieses Kriterium: «Art, die der Mandant selbst ausstellt» heisst Art mit `selbst_ausgestellt = ja`, nicht mehr Eintrag in `public.mandant.eigene_arten`; für `urinet` sind es nach der Migration dieselben Arten (Spec 05, 9.1; umgesetzt in Spec 05, Aufgabe 17.3).
10. WENN bei einem Fremdbeleg bereits ein Dokument mit gleicher Belegnummer, gleichem Partner und gleicher Art existiert, DANN SOLL der Eingangskorb eine Warnung mit Verweis auf das vorhandene Dokument zeigen, die Bestätigung aber zulassen.

### Requirement 9: Dokumentarten, Zusatzfelder und Aufbewahrung (D6)
**User Story:** Als Inhaber möchte ich Dokumentarten und ihre Felder selbst pflegen,
damit auch VHF/GSK eigene Arten ohne Programmänderung erhält.

#### Acceptance Criteria
1. Das Archiv SOLL pro Mandant Dokumentarten mit Code, Name, Zusatzfeld-Gruppen (`finanz`, `vertrag`, `frei`), Pflichtfeldern, Standard-Vertraulichkeit, Aufbewahrungsregel und `gesetzliche_pflicht` (ja/nein, Wirkung nach 6.3 und 6.12) verwalten. Spec 05, 2.1 und 3.4 ergänzen dieses Kriterium: Jede Dokumentart trägt zusätzlich `selbst_ausgestellt`, `belegnummer_muster`, `fremdbeleg_art`, `ohne_zahlungsdaten_art`, `ki_vorschlag`, `ki_felder`, `ki_hinweise`, `reihenfolge` und ihre Klassifikationen (umgesetzt in Spec 05, Aufgaben 17.3 und 17.5).
2. Der Mandant `urinet` SOLL mit den 11 Dokumentarten des Konzepts aus dem Seed `backend/src/main/resources/seed/urinet/dokumentarten.json` starten (Ausgangsrechnung, Offerte, Lieferantenbeleg, Quittung/Spesen, Lohnabrechnung, Vertrag, Versicherung, Behörde, Bank, Projektdokument, Korrespondenz); ein Test SOLL den Seed genau gegen die Stammdaten-Tabelle in design.md prüfen (Werte entschieden von Fabian am 10.11.2026). Spec 04, 1.1 ergänzt in jedem Mandanten die Art `WISSENSQUELLE` über einen eigenen Seed-Teil; die Zahl 11 und der Test gelten nur für diesen Seed. Spec 05, 6.7 ergänzt dieses Kriterium: Der Seed heisst `backend/src/main/resources/seed/urinet/konfiguration.json`; `KonfigurationSeedTest` ersetzt `DokumentartSeedTest` und gilt für jeden Seed `seed/*/konfiguration.json`, zusammengeführt mit dem Seed-Teil `_alle` (umgesetzt in Spec 05, Aufgabe 17.7).
3. WENN ein Dokument bestätigt wird, DANN SOLL das Archiv `aufbewahren_bis` berechnen: Ende des bestätigten Geschäftsjahres plus Jahre der Regel (Standard 10); das Geschäftsjahr wird aus dem Belegdatum vorgeschlagen, ohne Belegdatum aus dem Eingangsdatum; bei Verträgen ab `gueltig_bis`, ein Vertrag ohne `gueltig_bis` gilt als unbefristet; bei «unbefristet» kein Datum und keine Löschung (Sperre nach 6.9). Spec 05, 4.1 bis 4.4 ergänzen dieses Kriterium um die Fristbezüge `BELEGDATUM` (Belegdatum plus Jahre der Regel, ohne Belegdatum das Eingangsdatum) und `FELD` (ein Datumsfeld der Zusatzfelder der Art, das Pflichtfeld sein muss; ist es leer, gilt das Dokument als unbefristet, mit Warnung) (umgesetzt in Spec 05, Aufgabe 17.4).
4. FALLS eine Dokumentart noch Dokumente hat, DANN SOLL das Archiv ihre Löschung mit HTTP 409 `DOKUMENTART_IN_GEBRAUCH` verweigern und nur «inaktiv» erlauben.
5. Freie Zusatzfelder SOLLEN als JSON Schema definiert und beim Speichern validiert werden; ungültige Werte SOLL das Archiv mit HTTP 422 `ZUSATZ_UNGUELTIG` ablehnen.
6. Das Archiv SOLL das Geschäftsjahresende pro Mandant einstellbar führen.
7. WENN Belegdatum, Art, Geschäftsjahr oder `gueltig_bis` eines Dokuments geändert werden, DANN SOLL das Archiv `aufbewahren_bis` neu berechnen und eine bestehende Sperre nur verlängern, nie verkürzen; ergibt die Neuberechnung bei einer Art mit `gesetzliche_pflicht` oder einem Dokument mit Sperre ein früheres Datum oder aus «unbefristet» ein Datum, SOLL es Frist und Sperre belassen und den Konflikt protokollieren; bei einer Art ohne `gesetzliche_pflicht` und einem Dokument ohne Sperre SOLL es das neue Ergebnis übernehmen, auch wenn es früher liegt (entschieden von Fabian am 10.11.2026). Spec 05, 4.5 ergänzt dieses Kriterium: Die Neuberechnung gilt auch, wenn sich das Bezugsdatum nach den Fristbezügen `BELEGDATUM` oder `FELD` ändert (umgesetzt in Spec 05, Aufgabe 17.4).
8. FALLS `aufbewahren_bis` bei der Bestätigung bereits in der Vergangenheit liegt (Altbestand), DANN SOLL das Archiv das Dokument als «Frist abgelaufen» kennzeichnen und erst nach Entscheid des Inhabers archivieren oder verwerfen, statt es in `FEHLER` zu setzen.
9. WENN `aufbewahren_bis` eines Dokuments erreicht ist, DANN SOLL das Archiv das Dokument in dieser Spec unverändert behalten; Löschfunktion, Sperrvermerk und Auskunft nach revDSG folgen in einer späteren Spec (die erste Frist läuft 2036 ab), bis dahin deckt die Suche mit Partnerfilter (14.2) die Auskunft ab. Die Spec zur Löschfunktion SOLL auch die nur anfügenden Seitenversionen der Wissensbasis regeln, die auf einem gelöschten Dokument beruhen (Spec 04, 15.5; design.md, Open Points). Sie SOLL zudem den Löschweg für Einträge des Zugriffsprotokolls regeln, deren Frist nach 10.9 abgelaufen ist (eigene DB-Rolle, Nachweis der Löschung im Manifest) (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
10. WENN der Inhaber die Aufbewahrungsregel (Jahre, «ab») oder `gesetzliche_pflicht` einer Dokumentart ändert, auch wenn sie schon Dokumente hat, DANN SOLL das Archiv die Änderung erlauben, die neue Regel auf neue Dokumente und auf Neuberechnungen nach 9.7 anwenden, bestehende Sperren nur verlängern, nie verkürzen, und die Änderung mit den alten und neuen Werten der Regel (nicht mit Werten von Dokumenten) im Zugriffsprotokoll festhalten (entschieden von Fabian am 10.11.2026). Das gilt auch, wenn die Änderung die Regel einer Art mit `gesetzliche_pflicht` senkt (`gesetzliche_pflicht` auf «nein» oder weniger als 10 Jahre); das Archiv verhindert das nicht, es protokolliert die Änderung nur (entschieden von Fabian am 11.11.2026).

### Requirement 10: Versionierung der Metadaten und Zugriffsprotokoll (B2)
**User Story:** Als Inhaber möchte ich nachweisen können, wer wann was gesehen oder geändert hat,
damit das Archiv einer Prüfung standhält.

#### Acceptance Criteria
1. WENN Metadaten eines archivierten Dokuments geändert werden, DANN SOLL das Archiv die vorherige Fassung mit Benutzer, Zeit und Grund in `dokument_version` speichern; ein Grund ist Pflicht.
2. Das Archiv SOLL jedes Anzeigen, Herunterladen, Ändern, Bestätigen, Verwerfen und Exportieren an den in design.md aufgelisteten Endpunkten mit Zeit, Benutzer, Aktion, Dokument-ID und IP (Header `X-Real-Ip` von Traefik) im Zugriffsprotokoll festhalten, synchron in derselben Transaktion wie die Aktion.
3. FALLS jemand versucht, Einträge im Zugriffsprotokoll oder in `dokument_version` zu ändern oder zu löschen, DANN SOLL die Datenbank dies verweigern (nur Einfügen erlaubt).
4. WENN zwei Benutzer dasselbe Dokument gleichzeitig ändern, DANN SOLL das Archiv die zweite Änderung mit HTTP 412 `VERSION_KONFLIKT` ablehnen (optimistisches Locking über `If-Match`).
5. Der Inhaber SOLL das Zugriffsprotokoll nach Zeitraum, Benutzer und Dokument filtern können.
6. FALLS eine ändernde Anfrage auf ein Dokument ohne Header `If-Match` eintrifft, DANN SOLL das Archiv mit HTTP 428 antworten und nichts ändern.
7. FALLS das Archiv einen Zugriff nach Requirement 1.4, 2.3 oder der Rechtematrix (2.6) verweigert, DANN SOLL es den Versuch mit Aktion `VERWEIGERT` protokollieren.
8. FALLS der Protokolleintrag nicht geschrieben werden kann, DANN SOLL das Archiv die Aktion nicht ausführen.
9. Das Archiv SOLL jeden Eintrag des Zugriffsprotokolls mindestens bis zum späteren dieser beiden Daten aufbewahren: (a) `aufbewahren_bis` des betroffenen Dokuments, nur wenn es bestätigt ist und seine Art `gesetzliche_pflicht` hat oder es gesperrt ist; ist ein solches Dokument unbefristet, solange das Dokument besteht; (b) Ende des Geschäftsjahres des Eintrags plus 10 Jahre. Einträge ohne Dokument (z. B. `REGEL_AENDERN`, `KONFIGURATION_AENDERN`, Seiten- und Entwurfs-IDs der Wissensbasis) und Einträge zu allen anderen Dokumenten fallen nur unter (b). `dokument_version` bewahrt das Archiv so lange auf wie das Dokument. In dieser Spec löscht und pseudonymisiert das Archiv keine Einträge (10.3); den Löschweg regelt die Spec zur Löschfunktion (9.9). Nachweis: JUnit (Trigger und Rechte nur Einfügen, kein Löschpfad; Aufgabe 5.1) und Dokument (`docs/verfahrensbeschreibung.md`, Abschnitt Zugriffe, 16.6; Aufgabe 5.7). (geändert 12.11.2026: Frist bisher offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86; Grundlage Art. 8 GeBüV und Art. 958f OR. Auf Art. 4 DSV stützt sich die Frist nicht: Dessen Protokollierung mit Aufbewahrung getrennt vom bearbeitenden System gilt nur für Bearbeitungen nach Art. 4 Abs. 1 DSV, und das Zugriffsprotokoll liegt in derselben Datenbank.)
10. Das Archiv SOLL ein Benutzerverzeichnis führen, das jede Kennung im Feld `benutzer` des Zugriffsprotokolls (Keycloak-`sub`, `token:<id>`) mit Name, Rolle, gültig von und gültig bis einer Person zuordnet, damit die Zuordnung während der ganzen Frist nach 10.9 möglich bleibt, auch wenn Keycloak neu aufgebaut wird; das Verzeichnis erlaubt wie 10.3 nur Einfügen, eine Änderung (z. B. Austritt) ist ein neuer Eintrag (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).

### Requirement 11: Schnittstelle für Generatoren (I1)
**User Story:** Als Inhaber möchte ich, dass meine Skripte Rechnungen, Offerten und Lohnabrechnungen
direkt ablegen, damit ich sie nicht von Hand erfassen muss.

#### Acceptance Criteria
1. Das Archiv SOLL einen Endpunkt anbieten, der ein PDF plus Metadaten (JSON, inkl. `vertraulichkeit` und `personBenutzer`) mit einem API-Token vom Scope `generator` annimmt. Spec 05, 8.7 ergänzt dieses Kriterium: Die Metadaten können Klassifikationswerte mit ihrem Code übergeben (`klassifikationen`); es gelten Spec 05, 3.5 und 5.2 (umgesetzt in Spec 05, Aufgabe 18.2).
2. WENN alle Pflichtfelder der Art vorhanden und gültig sind, DANN SOLL das Archiv das Dokument ohne KI-Vorschlag verarbeiten (Prüfsumme, Dublettenprüfung, PDF/A, Volltext, Vorschau), `aufbewahren_bis` berechnen und nach Requirement 6.3 archivieren (über `ARCHIVIERUNG` nach `ARCHIVIERT`); ob die Antwort 201 nach Abschluss oder 202 lautet, legt design.md fest.
3. FALLS bei einer Art, die der Mandant selbst ausstellt, bereits ein Dokument mit derselben Belegnummer und Art im Mandanten existiert und die SHA-256 abweicht, DANN SOLL das Archiv mit HTTP 409 antworten und nichts überschreiben. Spec 05, 2.2 ergänzt dieses Kriterium: «Art, die der Mandant selbst ausstellt» heisst Art mit `selbst_ausgestellt = ja`, nicht mehr Eintrag in `public.mandant.eigene_arten`; für `urinet` sind es nach der Migration dieselben Arten (Spec 05, 9.1; umgesetzt in Spec 05, Aufgabe 17.3).
4. Das Repository SOLL einen Python-Client `tools/archiv_client.py` enthalten, den `rechnung_gen.py` und `urinet_pdf.py` mit einer Zeile aufrufen können.
5. FALLS das Archiv nicht erreichbar ist, DANN SOLL der Client das PDF in einem lokalen Ordner `archiv-ausstehend/` ablegen und beim nächsten Aufruf erneut senden.
6. FALLS Pflichtfelder der Art fehlen, DANN SOLL das Archiv mit HTTP 422 `PFLICHTFELD_FEHLT` antworten und nichts anlegen.
7. FALLS die Metadaten eine unbekannte `crmId` enthalten, DANN SOLL das Archiv mit HTTP 422 antworten und nichts anlegen.
8. WENN ein Generator ein Dokument erneut sendet, dessen SHA-256 einem vorhandenen Dokument entspricht, DANN SOLL das Archiv mit HTTP 200 und der vorhandenen ID antworten (idempotent).
9. FALLS das Archiv mit HTTP 409 antwortet, DANN SOLL der Client die Datei nach `archiv-konflikt/` verschieben und nicht erneut senden.
10. WO die Standard-Vertraulichkeit der Art `PERSONAL` ist, SOLLEN `vertraulichkeit` und `personBenutzer` Pflichtfelder sein (sonst 11.6); fehlt `vertraulichkeit` bei anderen Arten, SOLL das Archiv die Standard-Vertraulichkeit der Art setzen.
11. WENN ein Generator-Dokument verworfen wird (6.6, aus `FEHLER` oder `EINGANGSKORB`), DANN SOLL das Archiv die Reservation seiner Belegnummer in derselben Transaktion freigeben, sodass ein korrigiertes Neusenden derselben Belegnummer angenommen wird.

### Requirement 12: Einmaliger Import des Google-Drive-Altbestands (I2)
**User Story:** Als Inhaber möchte ich die bisherigen Dokumente aus `UriNet/` übernehmen,
damit das Archiv vollständig ist.

#### Acceptance Criteria
1. Das Repository SOLL ein Kommandozeilen-Werkzeug enthalten, das einen lokalen Ordner (Google Drive für Desktop) rekursiv liest und jede Datei mit ihrem Ordnerpfad als Hinweis hochlädt; es meldet sich mit einem API-Token vom Scope `import` an (Requirement 2.5). Spec 05, 8.7 ergänzt dieses Kriterium: Das Werkzeug kann Klassifikationswerte mit ihrem Code übergeben; es gelten Spec 05, 3.5 und 5.2 (umgesetzt in Spec 05, Aufgabe 18.2).
2. WENN ein Ordnerpfad bekannte Muster enthält (`Kunden/<Kunde>/Rechnungen`, `Offerten`, `Buchhaltung 2026`), DANN SOLL das Archiv daraus Partner- und Art-Hinweise an die KI geben.
3. Das Werkzeug SOLL einen Trockenlauf anbieten, der nur zählt und auflistet.
4. Das Werkzeug SOLL wiederholbar sein: Dateien, die das Archiv als `DUBLETTE` meldet (Requirement 3.4), gelten als erfolgreich übertragen und werden im Bericht als «schon vorhanden» gezählt.
5. Google-Docs-Dateien (`.gdoc`, `.gsheet`) SOLL das Werkzeug überspringen und in einer Liste «bitte als PDF exportieren» ausgeben.
6. Office-Dateien sowie `.eml`- und ZIP-Dateien SOLL das Werkzeug in Phase 1 nicht übertragen; sie und jede vom Archiv abgelehnte Datei SOLLEN mit Pfad und Grund unter «nicht übernommen» erscheinen.
7. WENN ein Lauf endet, DANN SOLL der Schlussbericht aufgehen: gefunden = übertragen + schon vorhanden + übersprungen + abgelehnt.
8. WENN eine Datei importiert wird, DANN SOLL das Archiv sie wie einen Upload verarbeiten und in den Eingangskorb legen, nie direkt archivieren.

### Requirement 13: CSV-Import der CRM-Daten aus 21.CRM (I6)
**User Story:** Als Inhaber möchte ich meine Kontakte aus 21.CRM als CSV einlesen,
damit Dokumente von Anfang an den richtigen Partnern zugeordnet werden.

#### Acceptance Criteria
1. Das Archiv SOLL CSV-Dateien mit Trennzeichen Semikolon oder Komma und Zeichensatz UTF-8 (mit oder ohne BOM) oder Windows-1252 annehmen und Trennzeichen und Zeichensatz selbst erkennen.
2. WENN eine Datei erstmals hochgeladen wird oder ihre Kopfzeile von der gespeicherten Zuordnung abweicht, DANN SOLL das Archiv einen Zuordnungsdialog zeigen (Spalte → Feld) und die Zuordnung pro Mandant und Importart speichern.
3. WENN eine Datei mit bekannter Zuordnung hochgeladen wird, DANN SOLL das Archiv eine Vorschau mit den Zahlen «neu», «geändert», «unverändert», «mögliche Dublette» und «fehlerhaft» sowie den einzelnen Zeilen zeigen, ohne etwas zu übernehmen.
4. Das Archiv SOLL Kontakte in dieser Reihenfolge abgleichen: Kontakt-ID aus 21.CRM, sonst E-Mail (ohne Gross-/Kleinschreibung), sonst Name + PLZ (normalisiert, ein exakter Treffer zählt als Treffer).
5. FALLS eine Zeile mehrere bestehende Kontakte treffen könnte, sich die Stufen aus 13.4 widersprechen oder sie nur über Namensähnlichkeit ab der Schwelle aus design.md passt, DANN SOLL das Archiv sie als «mögliche Dublette» markieren und nicht automatisch überschreiben.
6. WENN der Benutzer die Vorschau übernimmt, DANN SOLL das Archiv neue Kontakte anlegen, geänderte aktualisieren (Feldwerte aus dem CSV gewinnen, leere Zellen überschreiben nichts), Dubletten nur gemäss Auswahl des Benutzers behandeln und alles in einer Transaktion ausführen.
7. Das Archiv SOLL Kontakte, die im CSV fehlen, nie löschen; nur bei einem als Vollexport markierten Import SOLL es fehlende Kontakte mit `crm_id` als «nicht mehr im CRM-Export» kennzeichnen.
8. Das Archiv SOLL jeden Import mit Dateiname, Prüfsumme, Benutzer, Zeit und Zählern protokollieren und die Originaldatei unter `importe/` ablegen.
9. WO die Datei Verkaufschancen enthält (Importart `verkaufschance`), SOLL das Archiv sie mit Titel, Phase, Betrag und Datum über die Kontakt-ID aus 21.CRM dem passenden Kontakt zuordnen und bestehende Verkaufschancen über ihre `crm_id` aktualisieren.
10. FALLS keine Spalte dem Feld Name zugeordnet ist (beim 21.CRM-Export `Name` oder `Organisation`, weil Organisationen dort keinen `Name` haben) oder die Datei über 10'000 Zeilen hat, DANN SOLL das Archiv den Import mit einer verständlichen Meldung ablehnen; eine Zeile ohne Namen SOLL als «fehlerhaft» gelten.
11. FALLS eine Datei keine Datenzeilen enthält, DANN SOLL das Archiv den Import mit der Meldung «keine Datenzeilen» ablehnen und keinen Kontakt ändern oder kennzeichnen.

### Requirement 14: Volltextsuche mit Filtern (S1)
**User Story:** Als Inhaber möchte ich Dokumente über Text und Metadaten finden,
damit ich jede Rechnung in Sekunden habe.

#### Acceptance Criteria
1. Das Archiv SOLL eine Suche über Volltext, Titel, Partner und Tags (deutsche Stammformen; Tags sind Teil des Volltextindex) anbieten. Spec 05, 8.5 ergänzt dieses Kriterium: Die Suche findet auch die Namen der Klassifikationswerte eines Dokuments im Volltext (umgesetzt in Spec 05, Aufgabe 18.2).
2. Die Suche SOLL Filter für Dokumentart, Partner, Zeitraum (Belegdatum), Betrag von–bis, Richtung, Tags, Status und Geschäftsjahr kombinierbar unterstützen. Spec 05, 8.5 ergänzt dieses Kriterium: Zusätzlich filtert die Suche nach jeder Klassifikation mit «Suchfilter» (ODER innerhalb einer Klassifikation, UND zwischen Klassifikationen) und liefert je Wert die Zahl der Treffer, nur über Dokumente, die der Benutzer sehen darf (14.5; umgesetzt in Spec 05, Aufgabe 18.2).
3. WENN eine Suche ausgeführt wird, DANN SOLL das Archiv bei bis zu 50'000 Dokumenten pro Mandant innert 1 Sekunde (p95, Messpunkt Server) antworten, sortiert nach Relevanz oder Datum (Eingabe: 50'000 synthetische Dokumente im Mandanten, im Mittel rund 1'500 Zeichen Volltext, 5 % mit über 20'000 Zeichen, ein Drittel mit Finanzdaten, nach `VACUUM ANALYZE`; Last: 500 Suchen mit 4 gleichzeitigen je Rolle (Inhaber, Mitarbeiter, Treuhänder), einmal ohne und einmal mit laufendem OCR-Auftrag; 20 Aufwärmsuchen zählen nicht; Hardware und Ort nach 16.26; gilt erst ab Stufe B, 16.16) (geändert 12.11.2026: Messbedingungen bisher offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
4. Suchtreffer SOLLEN eine Textstelle mit hervorgehobenem Suchbegriff zeigen.
5. Die Suche SOLL Requirement 2.3 (Vertraulichkeit) und Requirement 1.2 (Mandant) einhalten.
6. WENN der Benutzer ein Dokument öffnet, DANN SOLL das Archiv eine zeitlich begrenzte Download-Adresse (höchstens 5 Minuten gültig) für die gespeicherte Versions-ID der PDF/A-Fassung ausgeben und den Zugriff protokollieren.
7. WENN ein Suchbegriff einer Belegnummer entspricht, DANN SOLL die Suche das Dokument mit genau dieser Belegnummer liefern; die Belegnummer SOLL zudem als exakter Filter verfügbar sein.

### Requirement 15: Tägliches Manifest (B1)
**User Story:** Als Inhaber möchte ich nachweisen können, dass seit der Archivierung nichts verändert wurde,
damit Integrität und Speicherzeitpunkt belegt sind (Art. 9 GeBüV).

#### Acceptance Criteria
1. WENN der tägliche Lauf um 03:30 Europe/Zurich startet, DANN SOLL das Archiv pro Mandant ein Manifest erzeugen mit allen Dateien, deren Archivierung seit dem letzten Manifest abgeschlossen wurde (massgeblich: Zeitpunkt der gesetzten Sperre, bei Arten ohne `gesetzliche_pflicht` der Statuswechsel auf `ARCHIVIERT`), je Datei Dokument-ID, Variante, SHA-256 und als Zusatz die S3-Versions-ID, sowie mit dem Hash der Zeilen von `zugriff_protokoll` und `dokument_version` des Vortags. Spec 04, 4.6 ergänzt das Manifest um Anzahl und SHA-256 der Zeilen von `wissensseite_version` und `wissens_log` im Fenster (umgesetzt in Spec 04, Aufgabe 13.3).
2. Jedes Manifest SOLL die Prüfsumme des vorherigen Manifests enthalten (Hash-Kette; das erste Manifest trägt `vorher_sha256 = null`) und mit Ed25519 signiert werden. Zusätzlich SOLL es die SHA-256 von Signatur (`.sig`), Zeitstempeln (`.tsr`) und abgelegten Prüfdaten der Zeitstempel (Zertifikatskette der TSA, OCSP- oder CRL-Antwort) des vorherigen Manifests enthalten, damit jeder neue Zeitstempel die früheren erneuert (RFC 4998) (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
3. Das Archiv SOLL jedes Manifest mit einem RFC-3161-Zeitstempel versehen; massgeblich ist bevorzugt der qualifizierte Zeitstempel nach ZertES eines Zeitstempeldienstes in der Schweiz (Hauptdienst: TSA der Swiss Government PKI), und fällt dieser Dienst weg, wird der zweite Zeitstempel nach 15.11 massgeblich. Dienste und Endpunkte sind Konfiguration (design.md). Produktives Archivieren SOLL erst beginnen, wenn der Zeitstempeldienst angebunden ist, weil sich ein Zeitstempel nicht rückwirkend nachholen lässt. (geändert 12.11.2026: bisher «Zeitstempel eines Zeitstempeldienstes in der Schweiz», Anbieter offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
4. Das Archiv SOLL das Manifest mit Object Lock im Modus COMPLIANCE (6.11) unter `manifest/` ablegen, gesperrt bis zum spätesten `aufbewahren_bis` der enthaltenen Dateien (unbefristete nach 6.9), und in der Datenbank referenzieren.
5. WENN der monatliche Prüflauf startet, DANN SOLL das Archiv für alle archivierten Dateien die gespeicherte Versions-ID lesen, die SHA-256 neu berechnen, mit Datenbank und Manifest vergleichen und Abweichungen dem Inhaber melden; massgeblich ist SHA-256, die Versions-ID ist Zusatz, damit Manifeste auch nach einer Wiederherstellung (16.3) gültig bleiben.
6. WENN der Lauf startet und Manifeste früherer Tage fehlen, DANN SOLL das Archiv sie in Datumsreihenfolge nachholen und pro Datum höchstens ein Manifest erzeugen (idempotent).
7. Das Archiv SOLL an den Tagen der Zeitumstellung (Testfälle 29.03.2026 und 25.10.2026) genau ein Manifest pro Mandant erzeugen.
8. FALLS für einen Tag mit archivierten Dateien nach dem Lauf kein gültiges Manifest vorliegt, DANN SOLL das Archiv den Inhaber alarmieren (16.5).
9. WENN der monatliche Prüflauf eine Sperre eines unbefristeten Dokuments oder Manifests findet, die in weniger als 12 Monaten abläuft, DANN SOLL er sie um 10 Jahre verlängern.
10. FALLS der Prüflauf unter `archiv/` fremde Versionen oder Löschmarken findet, DANN SOLL er sie als Abweichung melden.
11. WENN ein Manifest signiert ist, DANN SOLL das Archiv denselben Hash (SHA-256 von Manifest und Signatur) beim Hauptdienst und zusätzlich bei einem zweiten RFC-3161-Dienst aus einer konfigurierbaren Liste stempeln lassen (z. B. FreeTSA, dessen Bedingungen die gewerbliche Nutzung erlauben; die nach eIDAS qualifizierte TSA von Sectigo als Option nach Prüfung ihrer Bedingungen) und jeden Token als eigenes `.tsr` mit seinen Prüfdaten ablegen, Signatur, Tokens und Prüfdaten mit derselben Sperre wie das Manifest (15.4), weil die Erneuerung nach 15.2 auf sie angewiesen ist; an den zweiten Dienst geht nur dieser Hash (Ausnahme zu 16.7). Liegt nach der Auftragsregel kein Zeitstempel vor, gilt das Manifest als fehlend (15.8); fehlt nur einer, gilt es mit dem anderen (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
12. WENN das Prüfwerkzeug (`archiv manifest pruefen`) Manifeste prüft, DANN SOLL es Signatur, Hash-Kette, jeden Zeitstempel (Imprint, Kette bis zur abgelegten Zertifikatskette) und die Erneuerung nach 15.2 prüfen, eine Lücke in der Kette als Fehler melden, einen fehlenden Zeitstempel eines der Dienste nennen und einen Zeitstempel, dessen Zeit (`genTime`) mehr als eine konfigurierbare Schwelle nach dem Ende des Fensters liegt, als Verspätung melden. Nachweis: JUnit (Tests des Prüfwerkzeugs). (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)

### Requirement 16: Betrieb, Sicherung und Dokumentation (ohne Konzept-ID, nicht funktional)
**User Story:** Als Betreiber möchte ich das Archiv einfach betreiben und wiederherstellen können,
damit kein Dokument verloren geht.

#### Acceptance Criteria
1. Das Archiv SOLL in Stufe A auf der bestehenden Infomaniak-VPS und ab Stufe B auf einer eigenen Infomaniak-VPS (K5: VPS Lite mit 4 vCPU, 4 GB RAM und 80 GB Disk; verfehlt die Leistungsmessung nach 16.26 die Ziele, wird sie an Ort auf 8 GB RAM und 160 GB aufgerüstet; geändert 12.11.2026, Grösse bisher offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86) aus `deploy/infomaniak/compose.yml` vollständig starten (API mit Java 25 und Quarkus, die PWA über Quinoa im selben Artefakt; Worker, in Stufe A im selben Container wie die API; PostgreSQL; Keycloak) und hinter Traefik (Labels) unter `archiv.apps.urinet.ch` (App) und `archiv-auth.apps.urinet.ch` (Keycloak) erreichbar sein; der Object Storage liegt in einem eigenen Public-Cloud-Projekt bei Infomaniak. Nachweis: manuell [Fabian] (Deploy-Protokoll).
2. Das Archiv SOLL täglich eine verschlüsselte Sicherung von Archiv-Datenbank, Keycloak-Datenbank und Realm, Bucket (Originale, Arbeitsfassungen, PDF/A, Manifeste) und Manifest-Schlüssel als Zweitkopie bei einem zweiten Schweizer Anbieter schreiben (bestehendes Webhosting der Urinet bei Hostpoint, restic über SSH mit einem Zugang, der nur anhängen kann; lehnt Hostpoint die Nutzung ab, gilt als Rückfall cloudscale.ch Object Storage mit Object Lock) und für Datenbanken, Realm und Manifest-Schlüssel 30 Tages-, 12 Monats- und 10 Jahresstände aufbewahren; die Zuwächse des Buckets SOLL die Sicherung mit eigenem Tag schreiben und vom Ausdünnen ausnehmen, damit keine gesicherte Datei aus der Zweitkopie verschwindet. Ausgedünnt (`forget`, `prune`) wird nur von Hand mit einem zweiten Schlüssel aus den Notfallunterlagen. Der restic-Schlüssel liegt nur beim Sicherungs-Workflow und in den Notfallunterlagen (16.12). Nachweis: manuell [Fabian] (Sicherungsprotokoll). (geändert 12.11.2026: Anbieter bisher offen, Aufbewahrung bisher für alle Stände gleich; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
3. Das Repository SOLL eine Anleitung zur Wiederherstellung enthalten, die einmal in einer Testumgebung vollständig durchgespielt wurde (Nachweis: manuell [Fabian]); zusätzlich SOLL eine automatisierte Restore-Probe monatlich und zusätzlich von Hand nach jeder Änderung an Export oder Wiederherstellung, nach jedem Wechsel der Hauptversion von PostgreSQL oder Keycloak, in Aufgabe 5.11 auf der neuen VPS vor dem Umstellen von Deploy-Ziel und DNS (mit den synthetischen Daten der Messung nach 16.26 und dem dort erzeugten Probe-Schlüssel) sowie vor der Übernahme der Belege 2026 (Aufgabe 5.8) Datenbanken und Dateien wiederherstellen, die Sperren nach 16.27 neu setzen und prüfen, die Anmeldung prüfen und mit einem grünen Prüflauf (15.5) enden (geändert 12.11.2026: Rhythmus bisher offen; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
4. Das Archiv SOLL `/q/health` (Liveness/Readiness) und Kennzahlen (Aufträge offen, Fehler, Eingangskorb-Grösse, Korrekturquote je Feld) bereitstellen; öffentlich erreichbar SOLL nur ein schlanker Health-Pfad sein, die Kennzahlen nur für das Monitoring der VPS.
5. WENN ein Dokument länger als 1 Stunde in `VERARBEITUNG`, `FEHLER` oder `ARCHIVIERUNG_FEHLER` steht, die Sicherung fehlschlägt oder ein Manifest fehlt (15.8), DANN SOLL das Archiv dem Inhaber eine E-Mail senden.
6. Das Repository SOLL die Verfahrensbeschreibung «Erfassen und Archivieren» (`docs/verfahrensbeschreibung.md`, höchstens 2 Seiten) enthalten, mindestens mit Eingängen, Scan-Regeln (ganze Seite, lesbar, ≥ 300 dpi), Prüfung im Eingangskorb, Sperre, Manifest, Zugriffen, Umgang mit Papier und dem Notfallablauf für den Treuhänder; jede freigegebene Fassung SOLL mit «gültig ab» als PDF/A im Archiv abgelegt werden. Spec 04, 1.6 ergänzt die Verfahrensbeschreibung um den Satz «Wissensquellen und Wissensseiten sind keine Buchungsbelege oder Geschäftsbücher nach GeBüV und nicht aufbewahrungspflichtig» (umgesetzt mit Spec 04, Aufgabe 13.2). Nachweis: Dokument.
7. Das Archiv SOLL alle echten Daten ausschliesslich in Rechenzentren in der Schweiz speichern und verarbeiten (Infomaniak; Zweitkopie und Zeitstempeldienst bei Schweizer Anbietern; KI nur über Infomaniak AI gemäss AVV, Requirement 0.5); synthetische Testdaten (design.md, Testing Strategy) dürfen in der CI verarbeitet werden; CI-Logs und Benachrichtigungen SOLLEN nur IDs, keine Dokumentinhalte enthalten. Ausnahmen: Der Sicherungs-Workflow (GitHub Actions) transportiert ausschliesslich vom Server verschlüsselte Daten (16.8); der zweite Zeitstempeldienst (15.11) erhält nur den Hash eines Manifests und darf ausserhalb der Schweiz liegen (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86); im Profil `lokal` speichert und verarbeitet das Archiv auf dem Entwicklerrechner (Dev Services, Ablage, OCR, Sprachmodell in LM Studio) Daten, die diesen Rechner nicht verlassen (16.20–16.22).
8. Das Archiv SOLL über den GitHub-Actions-Sicherungsworkflow des Betriebs (maintenance-Muster) gesichert werden; der Server SOLL die Daten vorher asymmetrisch verschlüsseln und nur den öffentlichen Schlüssel halten, sodass der Runner nur verschlüsselte Daten sieht; einzige Ausnahmen sind der private Probe-Schlüssel, der nur auf der VPS der Stufe B liegt (16.23), und in Stufe A das Testschlüsselpaar des Funktionstests nach 16.19 (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86); Sicherungsprüfung (`backup-gate.sh`) und Restore-Probe SOLLEN PostgreSQL und den Bucket umfassen. Nachweis: manuell [Fabian] (Sicherungs- und Restore-Protokoll).
9. Das Archiv SOLL Secrets nur als Dateien unter `/run/secrets/` lesen, befüllt aus GitHub-Secrets, nie über `environment` oder `env_file`, weil Umgebungsvariablen über die Log-Ansicht lesbar sind; Manifest- und Datenschlüssel (`archiv_data_key`) SOLLEN zusätzlich im Passwort-Manager liegen, der öffentliche Manifest-Schlüssel unter `docs/`.
10. Das Archiv SOLL nur über einen GitHub-Actions-Workflow deployt werden (SSH-Bundle, danach Health-Prüfung); die Images von PostgreSQL und Keycloak SOLLEN gemäss Compose-Policy der VPS unter dem App-Namen umgetaggt und dort eingetragen sein. Nachweis: Dokument (`.github/workflows/infomaniak-deploy.yml`, `deploy/infomaniak/compose.yml`).
11. Jede Datenbank-Migration SOLL rückwärtskompatibel zum Vorgänger-Release sein, damit ein Rücksprung auf das vorherige Image ohne Rückbau der Datenbank möglich ist.
12. Für das Archiv SOLLEN versiegelte Notfallunterlagen an einem in der Verfahrensbeschreibung genannten Ort ausserhalb des Systems bestehen: Keycloak-Admin-Zugang, TOTP-Backup-Codes, restic-Schlüssel, privater Sicherungsschlüssel (Export-Verschlüsselung, 16.8), S3-Zugang des Inhabers (Inhaber-Zugang, 6.10), öffentlicher Manifest-Schlüssel und der zweite Schlüssel für das Ausdünnen der Zweitkopie (16.2); der Probe-Schlüssel (16.23) gehört nicht dazu, er ist ersetzbar (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86). Nachweis: manuell [Fabian].
13. Das Backend SOLL je Fachbereich in `domain`, `application` und `adapter` gegliedert sein und Ablage, KI, OCR und Integration über Driven Ports mit Dev-Stubs anbinden; ArchUnit-Tests SOLLEN die Abhängigkeitsregeln des Blueprints prüfen. Nachweis: JUnit (ArchUnit-Tests).
14. Jedes Kriterium dieser Spec ohne Nachweis-Vermerk («manuell», «Dokument» oder «JUnit») SOLL durch mindestens ein Cucumber-Szenario mit Tag `@req-<x.y>` abgedeckt sein; Unit-Tests laufen mit JUnit.
15. SOLANGE das Archiv in Stufe A läuft, SOLL es nur Test- und anonymisierte Daten enthalten, keine echten Belege und keine Personal- oder Lohndaten, weil die bestehende VPS das Netz «web» mit anderen Apps teilt und keine Reserven hat. Anonymisiert heisst ohne Bezug zu realen Personen oder Firmen (z. B. die anonymisierten Beispielzeilen aus Aufgabe 0.4); geschwärzte echte Belege, auch der Golden-Datensatz (0.1), gelten als echte Belege. Nachweis: manuell [Fabian] (Bestätigung im Messbericht der Aufgabe 1.10 und im Wechselprotokoll der Aufgabe 5.11, welche Testdaten auf Stufe A geladen wurden).
16. SOLANGE das Archiv in Stufe A läuft, SOLLEN die Leistungsziele 3.2, 5.5 und 14.3 nicht gelten, sondern nur die Funktion; die Leistungsziele SOLLEN mit Lasttests auf Stufe B nachgewiesen werden, unter den Messbedingungen nach 16.26. Nachweis: manuell [Fabian] (Messbericht Stufe B). (geändert 12.11.2026: Verweis auf 16.26 ergänzt; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
17. Das Archiv SOLL auf Stufe B laufen, bevor echte Belege ins Archiv kommen (Übernahme und Produktivbetrieb in Welle 5); der Wechsel umfasst die neue VPS, die Umstellung des Deploy-Ziels, das Verwerfen der Testdaten aus Stufe A und die Leistungsmessung nach 16.16 unter den Messbedingungen nach 16.26. Nachweis: manuell [Fabian] (Wechselprotokoll). (geändert 12.11.2026: Verweis auf 16.26 ergänzt; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
18. FALLS in Stufe A die Summe der `mem_limit` des Archivs 900 MiB oder `cpus` zusammen 0.75 überschreiten müsste, ein Container auf der VPS wegen Speichermangel beendet wird oder die Health-Prüfung einer anderen App der VPS fehlschlägt, DANN SOLL das Archiv auf Stufe A gestoppt werden und vorzeitig auf Stufe B wechseln (16.17). Nachweis: manuell [Fabian] (Messbericht Stufe A aus Aufgabe 1.10 und Prüfung nach jedem Deploy aus Aufgabe 1.9). (geändert 10.11.2026, Entscheid Fabian: Budget 700 → 900 MiB nach der Messung von 820 MiB in Aufgabe 1.9) Ein Speicherabbruch, den ein manueller Eingriff auslöst (z. B. eine zweite JVM per `docker exec` im laufenden Container wie am 12.11.2026), löst das Kriterium nicht aus; der Eingriff wird mit Datum im Messbericht der Aufgabe 1.10 festgehalten. «Stufe A» meint hier die Plattform (die bestehende VPS), nicht den Konfigurationswert `archiv.stufe`; die Messkonfiguration nach 16.26 auf der neuen VPS fällt nicht unter dieses Kriterium (geändert 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).
19. SOLANGE das Archiv in Stufe A läuft, SOLL die Sicherung nur als Funktionstest mit der Restore-Probe (16.3) laufen; die Aufbewahrung nach 16.2 SOLL erst ab Stufe B gelten. Für diesen Funktionstest darf ein eigenes Testschlüsselpaar auf der VPS der Stufe A liegen; es verschlüsselt nur Testdaten und wird beim Wechsel (16.17) verworfen (geändert 12.11.2026: Testschlüssel ergänzt; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86). Nachweis: manuell [Fabian] (Restore-Protokoll).
20. WO das Profil `lokal` aktiv ist, SOLL das Archiv das Sprachmodell nur über den LM-Studio-Server auf demselben Rechner aufrufen (`archiv.ki.basis-url` `http://localhost:<port>/v1` oder `http://127.0.0.1:<port>/v1`), Embeddings und Reranker durch Stubs ohne Vektoren ersetzen und kein Infomaniak-Token lesen; echte, ungeschwärzte Belege dürfen dabei verarbeitet werden, solange sie den Entwicklerrechner nicht verlassen (ADR-012). Nachweis: JUnit (Konfigurationstest aus Aufgabe 1.11).
21. FALLS `archiv.ki.basis-url` beim Start weder per HTTPS auf den Host `api.infomaniak.com` noch – nur in den Profilen `lokal` und `test` – auf `localhost` oder `127.0.0.1` zeigt (in `test` nur mit dem Port der WireMock-Testressource, nie Port 1234), DANN SOLL das Archiv den Start abbrechen und in der Meldung nur den abgelehnten Host und das aktive Profil nennen. Nachweis: JUnit (Konfigurationstest aus Aufgabe 1.11).
22. FALLS das Profil `lokal` zusammen mit dem Profil `prod` aktiv ist oder der Golden-Lauf (0.2) mit einer Basis-URL ausserhalb von `api.infomaniak.com` startet, DANN SOLL das Archiv bzw. der Golden-Lauf abbrechen, weil Gate 0, Golden-Lauf und Abnahmen nur gegen Infomaniak gemessen werden. Nachweis: JUnit (Test der Hostprüfung aus Aufgabe 0.5, Konfigurationstest aus Aufgabe 1.11).
23. Ab Stufe B SOLL die Restore-Probe (16.3) als eigenes Compose-Projekt `archiv-probe` auf der VPS der Stufe B laufen (in Stufe A gilt 16.19), nur in einem internen Docker-Netz ohne Verbindung nach aussen (`internal: true`), ohne Route in Traefik und mit eigenem Hostnamen und Issuer für Keycloak, damit keine Anfrage der Probe die Produktion erreicht: Der Server verschlüsselt jeden Export zusätzlich an den öffentlichen Probe-Schlüssel, dessen privater Teil nur auf dieser VPS liegt (nur für root lesbar, nie in GitHub oder auf dem Runner, in keinen Container eingehängt); entschlüsselt wird nur in einem eng begrenzten Schritt als root, der den Klartext an die Wiederherstellung der Probe streamt; der Runner holt das Chiffrat mit restic und streamt es über einen eigenen SSH-Schlüssel, dessen Forced Command nur Chiffrat annimmt, dessen SHA-256 die Sicherung auf der VPS festgehalten hat; der restic-Schlüssel kommt nie auf die VPS. Die Probe nutzt pro Lauf neue Wegwerf-Passwörter statt Zugangsdaten der Produktion (auch für den Testbenutzer, gesetzt über einen temporären Admin der Probe), spielt die Dumps nicht als Superuser ein, legt den Probe-Bucket in einem eigenen Public-Cloud-Projekt mit eigenem S3-Schlüssel an, ruft keinen Zeitstempeldienst und keine KI auf, sendet keine Mails und erzeugt keine Manifeste (umgesetzt über Stufe P, 16.24), und ihr Status enthält nur IDs und Zähler (16.7). Wird der Probe-Schlüssel gewechselt, folgt ein neuer Vollexport. Nachweis: manuell [Fabian] (Restore-Protokoll). (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
24. WO das Archiv mit `archiv.stufe=P` (Restore-Probe) läuft, SOLL es `manifest/` im Modus COMPLIANCE und Dokumente unter `archiv/` im Modus GOVERNANCE je höchstens 1 Tag sperren, Buckets nur mit dem Präfix `urinet-archiv-p-` nutzen und den Start abbrechen, wenn der Präfix fehlt, eine der Höchstfristen fehlt oder über 1 Tag liegt oder der Modus von `manifest/` nicht COMPLIANCE ist. In Stufe P SOLL das Archiv keinen Auftrag `MANIFEST` planen oder beim Start einreihen, die Startprüfung der Zeitstempeldienste aussetzen, im Worker nur Aufträge `PRUEFLAUF` starten (wiederhergestellte Aufträge anderer Typen bleiben liegen), ohne KI-Token und ohne SMTP-Zugang starten und im Prüflauf Sperren nach 15.9 nicht verlängern, sondern nur zählen. Stufe P SOLL nur in `deploy/probe/compose.yml` zulässig sein; die Stufenwerte unter `deploy/infomaniak/` SOLLEN sie nie nennen. Nachweis: JUnit (Konfigurationstest der Stufe P, `DeployStufeTest`). (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
25. FALLS die Restore-Probe fehlschlägt oder ihr letzter grüner Lauf älter als 35 Tage ist, DANN SOLL der Betrieb den Inhaber alarmieren (Benachrichtigung des Sicherungs-Workflows und Regel im Monitoring; nicht über 16.5, weil die App vom Ergebnis nichts erfährt). Nachweis: manuell [Fabian] (Workflow und Alarmregel). (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
26. Das Archiv SOLL die Leistungsziele 3.2, 5.5 und 14.3 auf der VPS der Stufe B mit den Diensten und Limits der Stufe B nachweisen, aber vor dem Umstellen mit `archiv.stufe=A` und einem eigenen Mess-Mandanten mit löschbarem Bucket `urinet-archiv-a-last`, nur mit synthetischen Daten und nie im produktiven Mandanten oder in einem Test-Bucket der laufenden Stufe A; danach werden Messdaten und Bucket verworfen, und Stufe B startet leer (16.17). `archiv.stufe=A` bestimmt hier nur Buckets und Sperren; Speicherbudget und Abbruchkriterium richten sich nach der Plattform (Zielhost), nicht nach `archiv.stufe`, darum gelten für die Messung das Budget der Stufe B und nicht 16.18. Der Messbericht nennt Commit, Grösse und gemessenes `MemTotal` der VPS und die Messbedingungen. Nachweis: manuell [Fabian] (Messbericht Stufe B). (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)
27. WENN das Wiederherstellungswerkzeug Dateien in einen Bucket zurückschreibt, DANN SOLL es je Objekt die Sperre des Originals aus dem Inventar des Exports (Modus und Datum; massgeblich, weil der Prüflauf Sperren ohne neue Version verlängert) neu setzen und nur bei einem Ziel-Bucket mit Präfix `urinet-archiv-p-` auf das Frühere aus dieser Sperre und dem folgenden Tag kappen; verlangt ein Lauf die Kappung bei einem anderen Präfix, SOLL es abbrechen. Die Restore-Probe SOLL unabhängig davon die Soll-Sperre aus der wiederhergestellten Datenbank nach den Regeln von 6.9, 6.12 und 15.4 berechnen und prüfen, dass die Sperre des Originals mindestens der Soll-Sperre entspricht und den richtigen Modus hat, jedes Objekt mit Sperrpflicht eine aktive Sperre trägt und Löschen ohne Bypass-Header bei einer Stichprobe scheitert; Abweichungen SOLL sie melden. Der Betrieb SOLL den Probe-Bucket frühestens 25 Stunden nach dem Ende des Probe-Laufs ohne Bypass-Header leeren. Nachweis: JUnit (Tests des Wiederherstellungswerkzeugs: Kappung nur beim Präfix `urinet-archiv-p-`) und manuell [Fabian] (Restore-Protokoll). (neu 12.11.2026, vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86)

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
| ohne Konzept-ID | 16 |
| D8 | gestrichen (entschieden von Fabian am 09.11.2026: keine native App, nur Web-App; die Handy-Erfassung deckt die PWA ab, D2 → Requirement 4, ADR-005) |

I4, I5 und C3 sind in `02-abgleich-chat` zugeordnet.
