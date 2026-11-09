# Requirements Document — 03 Zweiter Mandant VHF/GSK (Phase 3)

## Introduction
Diese Spec nimmt den Verband VHF/GSK als zweiten Mandanten (`vhf-gsk`) auf dieselbe
Plattform. Die Geschäftsstelle erfasst Bulletins, Merkblätter und Protokolle im Archiv;
die WordPress-Seite des Verbands zeigt Archivliste, Suche und Chatbot über ein Plugin, das die
Archiv-Schnittstelle serverseitig aufruft. WordPress bleibt Redaktionssystem für Seiten,
Mitglieder, Anmeldetool und Newsletter.

Voraussetzungen: Gate Phase 2 (Hybridsuche und Chat-Agent vorhanden), Zuschlag VHF/GSK und
Konzept-Entscheid E4 (Archiv in Etappe 2 der Offerte O-2026-002 oder später). Die Offerte O-2026-002
wird durch diese Spec nicht verändert.

Schreibweise der Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag `@req-<x.y>`, Ausnahmen mit
Vermerk «Nachweis: manuell [Fabian]», «Nachweis: Dokument» bzw. «Nachweis: JUnit»), Problem Details und `[offen]`-Marker
wie in Spec 01. Rollen und Sichtbarkeit richten sich nach der Rechtematrix in Spec 01, Requirement 2.6.

## Glossary
- **Site-Key** – API-Token mit Scope `wordpress` für genau einen Mandanten und eine WordPress-Installation (Bindung und Speicherung wie Spec 01, 2.5).
- **Zugriffsstufe** – `oeffentlich` (alle Besucher) oder `mitglieder` (nur angemeldete Verbandsmitglieder).
- **Reihe** – Publikationsreihe des Verbands (Bulletin, Merkblatt, Protokoll, Jahresbericht).

---

### Requirement 1: Mandant VHF/GSK anlegen (M1, D6)
**User Story:** Als Betreiber möchte ich VHF/GSK ohne Programmänderung als Mandant anlegen,
damit die Plattform für weitere Verbände wiederverwendbar ist.

#### Acceptance Criteria
1. WENN der Betreiber `archiv mandant anlegen vhf-gsk` ausführt, DANN SOLL das Archiv nach Spec 01, Requirement 1.5 Schema `m_vhf_gsk` (Liquibase-Changelog `db/mandant`), Bucket `urinet-archiv-vhf-gsk` (im Object-Storage-Projekt des Archivs, Object Lock und Versionierung), Keycloak-Client und die Dokumentarten aus `backend/src/main/resources/seed/vhf-gsk/dokumentarten.json` anlegen.
2. Die Dokumentarten SOLLEN mindestens Bulletin, Merkblatt, Protokoll und Jahresbericht umfassen, mit den Zusatzfeldern Reihe, Ausgabe-Nr., Erscheinungsjahr, Fachgebiet (Bad, Eis, Technik, Recht, Allgemein) und Zugriffsstufe (Pflicht), je Art mit Standard-Vertraulichkeit und `gesetzliche_pflicht` ([offen: `gesetzliche_pflicht` und Standard-Vertraulichkeit je Art VHF/GSK – Entscheid vor Welle 10]).
3. Der Mandant `vhf-gsk` SOLL keine Finanz-Dokumentarten und keine Finanzwerkzeuge erhalten.
4. FALLS ein Benutzer des Mandanten `urinet` auf Daten von `vhf-gsk` zugreifen will oder umgekehrt, DANN SOLL das Archiv dies auf allen Wegen (UI, API, Chat, öffentliche Schnittstelle, S3-Links) verhindern.
5. WENN der Mandant angelegt ist, DANN SOLL die Anwendung auf seinen Bucket nur mit dem App-Schlüssel zugreifen und dabei nie den Header `x-amz-bypass-governance-retention` setzen (Spec 01, 6.5), und Schema und Bucket SOLLEN in Sicherung, Zweitkopie und Restore-Probe enthalten sein (Spec 01, 16.2, 16.3, 16.8).

### Requirement 2: Branding und Domain (M2)
**User Story:** Als Geschäftsstelle möchte ich das Archiv unter dem Namen und Logo des Verbands sehen,
damit es für Vorstand und Mitglieder vertraut wirkt.

#### Acceptance Criteria
1. Das Archiv SOLL pro Mandant Name, Logo, Primärfarbe und Domain aus `mandant.branding` laden und die Web-App entsprechend darstellen.
2. WENN die Web-App unter der Domain eines Mandanten aufgerufen wird ([offen: Domain VHF/GSK – Entscheid vor Welle 10]; zusätzlich zu `archiv.apps.urinet.ch`, angebunden als weiterer Traefik-Router per Labels in `deploy/infomaniak/compose.yml`), DANN SOLL sie das Branding dieses Mandanten zeigen; der Mandant für Daten kommt trotzdem nur aus dem Token.
3. Die Keycloak-Anmeldeseite (`archiv-auth.apps.urinet.ch`) SOLL pro Mandant-Client ein eigenes Theme (Logo, Farbe) verwenden.

### Requirement 3: Erfassung durch die Geschäftsstelle
**User Story:** Als Geschäftsstelle möchte ich Publikationen per Browser oder Handy hochladen,
damit sie auf der Webseite erscheinen, ohne dass ich in WordPress Dateien verwalte.

#### Acceptance Criteria
1. Die Rolle `geschaeftsstelle` SOLL Dokumente hochladen, im Eingangskorb bestätigen, Metadaten ändern und Dokumente auf «nicht veröffentlicht» setzen können (Rechtematrix Spec 01, 2.6, Zeilen A1 bis A10).
2. WENN eine Publikation verarbeitet wird, DANN SOLL die KI Reihe, Ausgabe-Nr., Erscheinungsjahr, Fachgebiet und Titel vorschlagen.
3. Die Zugriffsstufe SOLL nie von der KI gesetzt werden; Standard ist `mitglieder`, bis die Geschäftsstelle `oeffentlich` wählt.
4. Nur Dokumente im Status `ARCHIVIERT` mit `veroeffentlicht = true` SOLLEN über die öffentliche Schnittstelle sichtbar sein.
5. Die Rolle `geschaeftsstelle` SOLL die Verwaltungsrechte A11 bis A14 der Rechtematrix (Spec 01, 2.6) nur erhalten, soweit festgelegt ([offen: Rechte der Geschäftsstelle A11 bis A14 – Entscheid vor Welle 10]); bis dahin SOLL das Archiv mit HTTP 403 antworten.
6. FALLS ein Dokument die Vertraulichkeit `INTERN` oder `PERSONAL` hat, DANN SOLL das Archiv es nie über die öffentliche Schnittstelle oder den öffentlichen Chatbot liefern, auch wenn `veroeffentlicht = true` ist.

### Requirement 4: Altbestand übernehmen
**User Story:** Als Geschäftsstelle möchte ich alle bisherigen Bulletins und Merkblätter übernehmen,
damit das Archiv vom ersten Tag an vollständig ist.

#### Acceptance Criteria
1. Das Drive-Import-Werkzeug (Spec 01, Requirement 12) SOLL mit einem API-Token vom Scope `import` des Mandanten `vhf-gsk` einen Ordner mit Unterordnern pro Reihe einlesen und Reihe und Jahr aus Ordner- und Dateinamen als Hinweis verwenden.
2. WENN der Import abgeschlossen ist, DANN SOLL das Archiv einen Bericht mit Anzahl pro Reihe und Jahr sowie Lücken in der Ausgabe-Nummerierung ausgeben.
3. Alte Links der bisherigen Seite SOLLEN über eine Zuordnungstabelle (alte URL → Dokument-ID) auf die neuen Dokumentseiten umgeleitet werden können (Umleitung erfolgt in WordPress).

### Requirement 5: Öffentliche Schnittstelle (M3)
**User Story:** Als UriNet möchte ich der WordPress-Seite eine sichere Schnittstelle bieten,
damit Liste, Suche und Chatbot ohne Datenkopie in WordPress funktionieren.

#### Acceptance Criteria
1. Das Archiv SOLL unter `/public/v1` Endpunkte für Liste (gefiltert nach Reihe, Jahr, Fachgebiet), Suche (Hybridsuche), Dokumentdetails, Datei-Link und Chat anbieten.
2. Jede Anfrage SOLL mit einem Site-Key authentifiziert werden; der Mandant ergibt sich ausschliesslich aus dem Site-Key.
3. WENN WordPress die Zugriffsstufe `mitglieder` für die Anfrage meldet, DANN SOLL das Archiv öffentliche und Mitglieder-Dokumente liefern; sonst nur öffentliche.
4. WENN ein Datei-Link angefordert wird, DANN SOLL das Archiv eine vorsignierte URL mit höchstens 5 Minuten Gültigkeit liefern und den Abruf (ohne Personendaten, mit gekürzter Client-IP) protokollieren.
5. FALLS ein Dokument die geforderte Zugriffsstufe übersteigt, DANN SOLL das Archiv mit HTTP 404 antworten (kein Hinweis auf seine Existenz).
6. Die öffentliche Schnittstelle SOLL CORS nicht freigeben (nur serverseitige Aufrufe durch WordPress).

### Requirement 6: Öffentlicher Chatbot
**User Story:** Als Verbandsmitglied möchte ich Fachfragen stellen und die passenden Bulletins finden,
damit ich Merkblätter nicht durchblättern muss.

#### Acceptance Criteria
1. Der Chatbot SOLL nur die Werkzeuge `dokumente_suchen` und `dokument_zeigen` verwenden und dieselben Zugriffsstufen-Regeln wie Requirement 5.3 sowie 3.4 und 3.6 einhalten.
2. Jede Antwort SOLL auf die verwendeten Publikationen mit Titel, Reihe, Ausgabe und Link zur Dokumentseite verweisen.
3. FALLS keine passende Publikation gefunden wird, DANN SOLL der Chatbot das sagen und auf die Geschäftsstelle (`gs@vhf-gsk.ch`) verweisen.
4. Das Archiv SOLL pro Client (gehashte IP, von WordPress übermittelt) höchstens 20 Fragen pro Stunde und pro Mandant ein konfigurierbares monatliches Token-Budget zulassen; bei Überschreitung SOLL der Chatbot freundlich ablehnen.
5. Der Chatbot SOLL keine Gesprächsinhalte mit Personenbezug speichern; gespeichert werden Frage, Quellen und Tokens für 90 Tage.

### Requirement 7: WordPress-Plugin (M4)
**User Story:** Als Redaktor der Verbandsseite möchte ich Archiv, Suche und Chatbot per Shortcode oder Block einfügen,
damit ich ohne Programmierung Seiten gestalten kann.

#### Acceptance Criteria
1. Das Plugin SOLL eine Einstellungsseite für Archiv-URL, Site-Key und Cache-Dauer bieten; der Site-Key SOLL nie an den Browser gelangen.
2. Das Plugin SOLL die Shortcodes `[urinet_archiv_liste reihe="" fachgebiet=""]`, `[urinet_archiv_suche]` und `[urinet_chatbot]` sowie gleichwertige Gutenberg-Blöcke anbieten.
3. Das Plugin SOLL die Zugriffsstufe aus dem WordPress-Login ableiten (konfigurierbare Rolle für Mitglieder) und serverseitig an das Archiv melden.
4. Das Plugin SOLL Listen- und Suchergebnisse für die konfigurierte Dauer (Standard 10 Minuten) als Transient zwischenspeichern, getrennt nach Zugriffsstufe.
5. FALLS das Archiv nicht erreichbar ist, DANN SOLL das Plugin eine freundliche Meldung zeigen und die Seite nicht abbrechen.
6. Die Ausgabe SOLL WCAG 2.1 AA erfüllen und das Theme der Verbandsseite übernehmen (keine eigenen Schriften).
7. Das Plugin SOLL mit der beim Projektstart aktuellen WordPress-Version und PHP 8.2+ funktionieren und die WordPress Coding Standards einhalten.

### Requirement 8: Mandantendaten, Kosten und Vertragsende
**User Story:** Als UriNet möchte ich Daten und Kosten pro Mandant im Griff haben,
damit ich Auftragsbearbeitung (revDSG) und Abrechnung sauber erfülle.

#### Acceptance Criteria
1. Das Archiv SOLL pro Mandant und Monat einen Bericht mit Speicherbedarf, Anzahl Dokumente, KI-Tokens (nach Zweck) und Chat-Fragen erstellen.
2. WENN der Betreiber einen Vollexport für einen Mandanten auslöst, DANN SOLL das Archiv alle Dokumente (Original, Arbeitsfassung falls vorhanden und PDF/A), Metadaten als JSON und CSV sowie Manifeste als ZIP bereitstellen.
3. WENN ein Mandant gekündigt und der Vollexport bestätigt übergeben ist, DANN SOLL das Archiv den Mandanten deaktivieren und nach einer Frist von 30 Tagen Schema, Keycloak-Client und Bucket-Inhalte löschen, soweit keine Object-Lock-Sperre entgegensteht; verbleibende gesperrte Objekte SOLLEN aufgelistet werden.
4. Das Repository SOLL eine Vorlage für den Auftragsbearbeitungsvertrag (Datenstandort Schweiz mit der Ausnahme nach Spec 01, 16.7; Unterauftragnehmer Infomaniak für Hosting, Object Storage und KI, Anbieter der Zweitkopie, Zeitstempeldienst sowie GitHub Actions als Transportweg ausschliesslich verschlüsselter Sicherungsdaten; Rückgabe und Löschung) unter `docs/vertrag/` enthalten. Nachweis: Dokument.
5. WENN ein Mandant nach 8.3 gelöscht wird, DANN SOLL das Archiv im Abbau-Bericht festhalten, bis wann seine Daten noch in den Sicherungsständen der Zweitkopie enthalten sind (Aufbewahrung nach Spec 01, 16.2).

### Requirement 9: Abnahme Phase 3
#### Acceptance Criteria
1. Ein automatisierter Isolationstest SOLL mit Benutzern, Site-Keys und Chat beider Mandanten nachweisen, dass kein Zugriff über Mandantengrenzen möglich ist.
2. Ein Sicherheitstest der öffentlichen Schnittstelle (OWASP API Top 10 als Prüfliste) SOLL ohne offenen Befund der Stufe hoch abgeschlossen sein. Nachweis: Dokument (Prüfbericht unter `docs/abnahme/`).
3. WENN das VHF/GSK-Archiv auf der Verbandsseite online ist und die Geschäftsstelle eine neue Ausgabe selbständig veröffentlicht hat, DANN SOLL das Gate «Phase 3 bestanden» gelten. Nachweis: manuell [Fabian].

## Rückverfolgung Konzept → Spec
| Konzept | Requirement |
| --- | --- |
| M1 (zweiter Mandant) | 1 |
| M2 | 2 |
| M3 | 5, 6 |
| M4 | 7 |
| Rechtliches zweiter Mandant | 8 |
| Gate Phase 3 | 9 |
