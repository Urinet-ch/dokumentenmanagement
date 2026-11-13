# Requirements Document — 03 Zweiter Mandant VHF/GSK (Phase 3)

## Introduction
Diese Spec nimmt den Verband VHF/GSK als zweiten Mandanten (`vhf-gsk`) auf dieselbe
Plattform. Die Geschäftsstelle erfasst Bulletins, Merkblätter und Protokolle im Archiv;
die WordPress-Seite des Verbands zeigt Archivliste, Suche und Chatbot über ein Plugin, das die
Archiv-Schnittstelle serverseitig aufruft. WordPress bleibt Redaktionssystem für Seiten,
Mitglieder, Anmeldetool und Newsletter.

Erweiterung vom 13.11.2026 (Auftrag Fabian: «Archiv über eine API in eine andere Webseite einbinden», zuerst für die
VHF/GSK-Webseite; Entscheide E-A bis E-D, entschieden von Fabian am 13.11.2026): Die öffentliche Schnittstelle wird
zum lesenden Teil der **Webseiten-Schnittstelle**, eines versionierten, dokumentierten Vertrags für jede Webseite (Requirement 11); das
WordPress-Plugin ist ihr erster Nutzer. Neben dem Lesen (Requirement 5) kann eine Webseite über dieselbe Schnittstelle
Dokumente erfassen (Requirement 10), damit sie die einzige Oberfläche der Geschäftsstelle sein kann; die Web-App des
Archivs bleibt daneben bestehen. Dazu kommen Webseiten-Schlüssel (Requirement 12), drei Zugriffsstufen (3.7), eine
gemischte Dateiauslieferung (Requirement 13), ein Änderungs-Feed (Requirement 14) sowie Grenzen und ein einheitliches
Fehlerformat (Requirement 15). Leitentscheid: ADR-014.

Voraussetzungen: Gate Phase 2 (Hybridsuche und Chat-Agent vorhanden), Zuschlag VHF/GSK und
Konzept-Entscheid E4 (Archiv in Etappe 2 der Offerte O-2026-002 oder später). Die Offerte O-2026-002
wird durch diese Spec nicht verändert.

Schreibweise der Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag `@req-<x.y>`, Ausnahmen mit
Vermerk «Nachweis: manuell [Fabian]», «Nachweis: Dokument» bzw. «Nachweis: JUnit»), Problem Details und `[offen]`-Marker
wie in Spec 01. Rollen und Sichtbarkeit richten sich nach der Rechtematrix in Spec 01, Requirement 2.6.

## Glossary
- **Webseite** – eine Installation einer Webseite (z. B. die WordPress-Seite von VHF/GSK), die die Webseiten-Schnittstelle serverseitig aufruft; je Mandant mit einer Kennung (z. B. `vhf-gsk-web`), die in Schlüsseln, Referenzen und Protokoll steht.
- **Webseiten-Schnittstelle** – alle Endpunkte unter `/public/v<n>`: Lesen unter `/public/v1`, Erfassung unter `/public/v1/erfassung`; ein versionierter Vertrag (Requirement 11).
- **Öffentliche Schnittstelle** – nur der lesende Teil der Webseiten-Schnittstelle (`/public/v1` ohne `/public/v1/erfassung`, mit Lese-Schlüssel), über den Besucher der Webseite Dokumente sehen; die Erfassung gehört nicht dazu, sie sieht wie die Rolle `geschaeftsstelle` (Requirement 10). Wo diese Spec «öffentliche Schnittstelle» schreibt, gelten die Regeln nur für diesen Teil (präzisiert 13.11.2026; bisher gab es nur den lesenden Teil).
- **Webseiten-Schlüssel** – API-Token für genau einen Mandanten und eine Webseite (Bindung und Speicherung wie Spec 01, 2.5), mit einer höchsten Zugriffsstufe; ersetzt den bisherigen **Site-Key** mit Scope `wordpress` (geändert 13.11.2026, entschieden von Fabian, E-B). Zwei Arten:
  - **Lese-Schlüssel** – Scope `webseite-lesen`; erreicht nur die lesenden Endpunkte (jede Seitenansicht).
  - **Erfassungs-Schlüssel** – Scope `webseite-erfassung`; erreicht nur die Endpunkte der Erfassung (Verwaltung durch die Geschäftsstelle).
- **Zugriffsstufe** – `oeffentlich` (alle Besucher) < `mitglieder` (angemeldete Verbandsmitglieder) < `vorstand` (Vorstand); eine höhere Stufe sieht alles der tieferen (geändert 13.11.2026, entschieden von Fabian, E-C; bisher nur `oeffentlich` und `mitglieder`).
- **Gemeldete Stufe** – die höchste Zugriffsstufe der angemeldeten Person, die die Webseite je Anfrage meldet (Header `X-Archiv-Zugriff`); höchstens die Stufe des Schlüssels.
- **Bearbeiter** – die Kennung der Person, die auf der Webseite eine Erfassungsaktion auslöst (Header `X-Archiv-Bearbeiter`); steht mit der Schlüssel-ID im Zugriffsprotokoll und ist über das Benutzerverzeichnis (Spec 01, 10.10) einer Person zugeordnet (10.12).
- **Referenz** – die Kennung eines Dokuments auf der Webseite (z. B. die WordPress-ID), mit der ein Upload idempotent wird.
- **Änderungs-Feed** – Liste der Änderungen ab einem Cursor (`seit`), die die Webseite abfragt, statt vom Archiv aufgerufen zu werden.
- **Fundstelle** – die PDF-Seite, auf der ein Suchtreffer liegt, soweit sie bekannt ist.
- **Reihe** – Publikationsreihe des Verbands (Bulletin, Merkblatt, Protokoll, Jahresbericht).

---

### Requirement 1: Mandant VHF/GSK anlegen (M1, D6)
**User Story:** Als Betreiber möchte ich VHF/GSK ohne Programmänderung als Mandant anlegen,
damit die Plattform für weitere Verbände wiederverwendbar ist.

#### Acceptance Criteria
1. WENN der Betreiber `archiv mandant anlegen vhf-gsk` ausführt, DANN SOLL das Archiv nach Spec 01, Requirement 1.5 Schema `m_vhf_gsk` (Liquibase-Changelog `db/mandant`), Bucket `urinet-archiv-vhf-gsk` (im Object-Storage-Projekt des Archivs, Object Lock und Versionierung), Keycloak-Client und die Dokumentarten aus `backend/src/main/resources/seed/vhf-gsk/dokumentarten.json` anlegen. Spec 04, 1.1 ergänzt: Zusätzlich liest das Archiv den mandantenunabhängigen Seed-Teil `backend/src/main/resources/seed/_alle/dokumentarten-wissen.json` ein (Art `WISSENSQUELLE`; umgesetzt in Spec 04, Aufgabe 13.2). Spec 05, 1.6 ergänzt dieses Kriterium: An die Stelle von `seed/vhf-gsk/dokumentarten.json` tritt `backend/src/main/resources/seed/vhf-gsk/konfiguration.json` (umgesetzt in Spec 05, Aufgabe 17.7).
2. Die Dokumentarten SOLLEN mindestens Bulletin, Merkblatt, Protokoll und Jahresbericht umfassen, mit den Zusatzfeldern Reihe, Ausgabe-Nr., Erscheinungsjahr, Fachgebiet (Bad, Eis, Technik, Recht, Allgemein) und Zugriffsstufe (Pflicht), je Art mit Standard-Vertraulichkeit und `gesetzliche_pflicht` ([offen: `gesetzliche_pflicht` und Standard-Vertraulichkeit je Art VHF/GSK – Entscheid vor Welle 10]). Spec 04, 1.1 ergänzt die Art `WISSENSQUELLE` über ihren eigenen Seed-Teil; in `vhf-gsk` trägt sie mindestens die Zugriffsstufe (Pflicht, Standard `mitglieder` nach 3.3) und `veroeffentlicht` (Standard `false`, 3.4), weil eine Wissensquelle sonst nach Veröffentlichung für alle Besucher abrufbar wäre (umgesetzt in Spec 04, Aufgabe 13.2). Spec 05, 2.7 und 3.1 ergänzen dieses Kriterium: Das Fachgebiet ist die Klassifikation `fachgebiet` (mehrere Werte je Dokument, öffentlich filterbar); die Reihe ist die Dokumentart, das Zusatzfeld Reihe entfällt; Ausgabe-Nr. und Erscheinungsjahr bleiben Zusatzfelder mit den Regeln `EINDEUTIG` und `FOLGE`; Zugriffsstufe (`zugriff`) und `veroeffentlicht` sind reservierte Felder mit fester Definition (Spec 05 design.md, umgesetzt in Spec 05, Aufgaben 17.3, 17.5 und 17.6).
3. Der Mandant `vhf-gsk` SOLL keine Finanz-Dokumentarten und keine Finanzwerkzeuge erhalten. Spec 05, 8.8 ergänzt dieses Kriterium: Die Finanzwerkzeuge entfallen über `Funktionen.finanzVerfuegbar()`, weil `vhf-gsk` keine aktive Art mit der Gruppe `finanz` hat (umgesetzt in Spec 05, Aufgabe 18.2).
4. FALLS ein Benutzer des Mandanten `urinet` auf Daten von `vhf-gsk` zugreifen will oder umgekehrt, DANN SOLL das Archiv dies auf allen Wegen (UI, API, Chat, Webseiten-Schnittstelle mit Lesen und Erfassung, S3-Links) verhindern (Begriff präzisiert 13.11.2026; bisher «öffentliche Schnittstelle»). Spec 04, 11.5 ergänzt die Wege der Wissensbasis (Index, Seiten, Suche, Log, Entwürfe, Chat-Werkzeuge, Export; umgesetzt in Spec 04, Aufgabe 16.1).
5. WENN der Mandant angelegt ist, DANN SOLL die Anwendung auf seinen Bucket nur mit dem App-Schlüssel zugreifen und dabei nie den Header `x-amz-bypass-governance-retention` setzen (Spec 01, 6.5), und Schema und Bucket SOLLEN in Sicherung, Zweitkopie und Restore-Probe enthalten sein (Spec 01, 16.2, 16.3, 16.8).
6. Die Konfiguration von `vhf-gsk` SOLL die Klassifikation `verband` mit den Werten `vhf` und `gsk` führen (mehrere Werte je Dokument, «öffentlich filterbar» nach Spec 05, 3.1, Suchfilter ja, ohne KI-Vorschlag); ein Dokument beider Verbände trägt beide Werte, ein eigener Wert «beide» entfällt, damit der Filter `?verband=vhf` auch die gemeinsamen Dokumente findet (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).

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
1. Die Rolle `geschaeftsstelle` SOLL Dokumente hochladen, im Eingangskorb bestätigen, Metadaten ändern und Dokumente auf «nicht veröffentlicht» setzen können (Rechtematrix Spec 01, 2.6, Zeilen A1 bis A10). Spec 03, 10.1 ergänzt dieses Kriterium (13.11.2026, entschieden von Fabian, E-A): Dieselben Aktionen ausser Verwerfen stehen zusätzlich über die Erfassung einer Webseite zur Verfügung; die Web-App bleibt (umgesetzt in Aufgabe 11.10).
2. WENN eine Publikation verarbeitet wird, DANN SOLL die KI Reihe, Ausgabe-Nr., Erscheinungsjahr, Fachgebiet und Titel vorschlagen. Spec 05, 8.1, 8.4 und 8.9 ergänzen dieses Kriterium: Die Reihe schlägt die KI als Dokumentart vor, Ausgabe-Nr. und Erscheinungsjahr als `ki_felder` der Art, das Fachgebiet als Klassifikation mit KI-Vorschlag; Vorschläge gibt es für `vhf-gsk` erst nach der Abnahme nach Spec 05, 8.9 und der KI-Freigabe nach Spec 05, 8.4 (umgesetzt in Spec 05, Aufgabe 18.1).
3. Die Zugriffsstufe SOLL nie von der KI gesetzt werden; Standard ist `mitglieder`, bis die Geschäftsstelle eine andere Stufe (`oeffentlich` oder `vorstand`) wählt (geändert 13.11.2026, entschieden von Fabian, E-C; bisher «bis die Geschäftsstelle `oeffentlich` wählt»).
4. Nur Dokumente im Status `ARCHIVIERT` mit `veroeffentlicht = true` SOLLEN über die öffentliche Schnittstelle sichtbar sein. Spec 03, 10.4 ergänzt dieses Kriterium (13.11.2026, entschieden von Fabian, E-A): Es gilt für den lesenden Teil der Webseiten-Schnittstelle und den öffentlichen Chatbot; die Erfassung sieht wie die Rolle `geschaeftsstelle` auch unveröffentlichte Dokumente und den Eingangskorb (Glossar «Öffentliche Schnittstelle»).
5. Die Rolle `geschaeftsstelle` SOLL die Verwaltungsrechte A11 bis A14 der Rechtematrix (Spec 01, 2.6) nur erhalten, soweit festgelegt ([offen: Rechte der Geschäftsstelle A11 bis A14 – Entscheid vor Welle 10]); bis dahin SOLL das Archiv mit HTTP 403 antworten. Für die Wissensbasis gilt Spec 04, 12.1: FALLS die Geschäftsstelle A19 (Einstellungen, OKF-Export) ausführt, DANN SOLL das Archiv mit HTTP 403 antworten (entschieden von Fabian am 09.11.2026; umgesetzt in Spec 04, Aufgabe 13.5).
6. FALLS ein Dokument die Vertraulichkeit `INTERN` oder `PERSONAL` hat, DANN SOLL das Archiv es nie über die öffentliche Schnittstelle oder den öffentlichen Chatbot liefern, auch wenn `veroeffentlicht = true` ist. Spec 03, 10.4 ergänzt dieses Kriterium (13.11.2026, entschieden von Fabian, E-A): Die Erfassung gehört nicht zur öffentlichen Schnittstelle; ein Erfassungs-Schlüssel sieht nach 10.8 so viel wie die Rolle `geschaeftsstelle` (Spec 01, 2.6, A2 bis A4).
7. Das reservierte Feld `zugriff` SOLL genau die Werte `oeffentlich`, `mitglieder` und `vorstand` mit der Ordnung `oeffentlich` < `mitglieder` < `vorstand` haben (Pflicht, Standard `mitglieder`), `veroeffentlicht` ein Wahrheitswert (Pflicht, Standard `false`); Typ, Werte, Standard und Pflicht stehen an einer Stelle in design.md («Reservierte Felder»), gegen die Spec 05, 2.7 prüft. WENN eine Stufe gilt, DANN SOLL das Archiv alle Dokumente bis und mit dieser Stufe liefern (neu 13.11.2026, entschieden von Fabian am 13.11.2026, E-C).

### Requirement 4: Altbestand übernehmen
**User Story:** Als Geschäftsstelle möchte ich alle bisherigen Bulletins und Merkblätter übernehmen,
damit das Archiv vom ersten Tag an vollständig ist.

#### Acceptance Criteria
1. Das Drive-Import-Werkzeug (Spec 01, Requirement 12) SOLL mit einem API-Token vom Scope `import` des Mandanten `vhf-gsk` einen Ordner mit Unterordnern pro Reihe einlesen und Reihe und Jahr aus Ordner- und Dateinamen als Hinweis verwenden. Spec 05, 8.7 ergänzt dieses Kriterium: Die Reihe aus dem Ordnernamen ist die Dokumentart; das Fachgebiet übergibt das Werkzeug als Klassifikation mit Code (es gelten Spec 05, 3.5 und 5.2; umgesetzt in Spec 05, Aufgabe 18.2).
2. WENN der Import abgeschlossen ist, DANN SOLL das Archiv einen Bericht mit Anzahl pro Reihe und Jahr sowie Lücken in der Ausgabe-Nummerierung ausgeben. Spec 05, 5.4 ergänzt dieses Kriterium: Die Lücken kommen aus dem Lückenbericht nach der Regel `FOLGE` je Reihe und Jahr; der Bericht im Importlauf zählt serverseitig zusätzlich die Dokumente dieses Laufs im Eingangskorb (Spec 05 design.md, «Regeltypen»; umgesetzt in Spec 05, Aufgabe 17.6).
3. Alte Links der bisherigen Seite SOLLEN über eine Zuordnungstabelle (alte URL → Dokument-ID) auf die neuen Dokumentseiten umgeleitet werden können (Umleitung erfolgt in WordPress). Spec 03, 5.10 ergänzt dieses Kriterium (13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Die Zuordnung bleibt im Archiv, die Webseite liest sie über die Webseiten-Schnittstelle und leitet mit 301 um (umgesetzt in Aufgaben 10.5 und 11.2).

### Requirement 5: Öffentliche Schnittstelle (M3)
**User Story:** Als UriNet möchte ich der WordPress-Seite eine sichere Schnittstelle bieten,
damit Liste, Suche und Chatbot ohne Datenkopie in WordPress funktionieren. Ergänzt 13.11.2026 (E-B): Die
Schnittstelle gilt für jede Webseite, nicht nur WordPress; sie ist der lesende Teil der Webseiten-Schnittstelle.

#### Acceptance Criteria
1. Das Archiv SOLL unter `/public/v1` Endpunkte für Liste (gefiltert nach Reihe, Jahr, Fachgebiet), Suche (Hybridsuche), Dokumentdetails, Datei-Link und Chat anbieten. Spec 05, 8.6 ergänzt dieses Kriterium: Neben Reihe (Code der Dokumentart) und Jahr filtert die Liste nach den Klassifikationen mit «öffentlich filterbar» (z. B. `fachgebiet`), und nur diese Klassifikationen werden ausgegeben; der feste Sicherheitsfilter bleibt unverändert (umgesetzt in Spec 03, Aufgaben 11.2 und 11.5).
2. Jede Anfrage SOLL mit einem Lese-Schlüssel authentifiziert werden; der Mandant ergibt sich ausschliesslich aus dem Schlüssel (geändert 13.11.2026, entschieden von Fabian, E-B; bisher «Site-Key»).
3. WENN die Webseite für die Anfrage eine Zugriffsstufe meldet, die höchstens der Stufe des Lese-Schlüssels entspricht, DANN SOLL das Archiv alle Dokumente bis und mit dieser Stufe liefern (3.7); ohne gemeldete Stufe nur öffentliche (geändert 13.11.2026, entschieden von Fabian, E-C; bisher «WENN WordPress die Zugriffsstufe `mitglieder` für die Anfrage meldet, DANN SOLL das Archiv öffentliche und Mitglieder-Dokumente liefern; sonst nur öffentliche.»).
4. WENN ein Datei-Link angefordert wird, DANN SOLL das Archiv eine vorsignierte URL mit höchstens 5 Minuten Gültigkeit liefern und den Abruf (ohne Personendaten, mit gekürzter Client-IP) protokollieren. Spec 03, 13.1 und 13.2 schränken dieses Kriterium ein (geändert 13.11.2026, entschieden von Fabian, E-D; bisher galt die vorsignierte URL für jedes Dokument): Die vorsignierte URL gibt es nur für Dokumente der Stufe `oeffentlich`; Dokumente der Stufen `mitglieder` und `vorstand` streamt das Archiv (umgesetzt in Aufgabe 11.8).
5. FALLS ein Dokument die geforderte Zugriffsstufe übersteigt, DANN SOLL das Archiv mit HTTP 404 antworten (kein Hinweis auf seine Existenz).
6. Die öffentliche Schnittstelle SOLL CORS nicht freigeben (nur serverseitige Aufrufe durch den Server der Webseite); das gilt für die ganze Webseiten-Schnittstelle einschliesslich Erfassung, Stream und Änderungs-Feed (geändert 13.11.2026, entschieden von Fabian, E-B; bisher «durch WordPress»).
7. WENN eine Suche Treffer liefert, DANN SOLL das Archiv je Treffer die Fundstelle (PDF-Seite des besten Abschnitts) mitliefern, soweit der Text Seitentrenner hat; sonst fehlt die Angabe. Spec 03 ergänzt dafür Spec 02, 2.2 (Abschnitte mit Seitenbereich; umgesetzt in Spec 02, Aufgaben 6.3 und 6.4, und Aufgabe 11.13) (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
8. Das Archiv SOLL unter `/public/v1/filter` die Reihen (Code und Name der aktiven Arten) und die Klassifikationen mit «öffentlich filterbar» samt Werten liefern, jeweils nur, soweit der feste Filter bis zur Stufe der Anfrage (5.3) Dokumente dazu liefert, damit eine Webseite ihre Filter ohne feste Listen aufbauen kann und keine Reihe oder kein Wert einer höheren Stufe sichtbar wird; 5.9 gilt auch hier (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
9. FALLS die gemeldete Stufe über der Stufe des Lese-Schlüssels liegt, DANN SOLL das Archiv mit HTTP 403 `NICHT_ERLAUBT` antworten und nichts liefern; FALLS der Wert unbekannt ist, mit HTTP 400 `ANFRAGE_UNGUELTIG` (neu 13.11.2026, entschieden von Fabian am 13.11.2026, E-C).
10. WENN die Webseite zu einer alten URL (4.3) die Dokument-ID abfragt, DANN SOLL das Archiv die ID nur liefern, wenn das Dokument nach 5.3 bis zur Stufe der Anfrage sichtbar ist, sonst mit HTTP 404 antworten (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
11. Liste, Suche, Details und Chat-Quellen SOLLEN Dokumente nur mit `dokumentId` und den Angaben aus design.md («Antwortfelder») bezeichnen und nie eine URL einer Seite der Webseite; den Link auf die Dokumentseite bildet die Webseite selbst (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).

### Requirement 6: Öffentlicher Chatbot
**User Story:** Als Verbandsmitglied möchte ich Fachfragen stellen und die passenden Bulletins finden,
damit ich Merkblätter nicht durchblättern muss.

#### Acceptance Criteria
1. Der Chatbot SOLL nur die Werkzeuge `dokumente_suchen` und `dokument_zeigen` verwenden und dieselben Zugriffsstufen-Regeln wie Requirement 5.3 sowie 3.4 und 3.6 einhalten.
2. Jede Antwort SOLL auf die verwendeten Publikationen mit Titel, Reihe, Ausgabe und Link zur Dokumentseite verweisen. Spec 03, 5.11 ergänzt dieses Kriterium (13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Das Archiv liefert je Quelle `dokumentId`, Titel, Reihe und Ausgabe; den Link auf die Dokumentseite bildet die Webseite (umgesetzt in Aufgaben 11.3 und 11.5).
3. FALLS keine passende Publikation gefunden wird, DANN SOLL der Chatbot das sagen und auf die Geschäftsstelle (`gs@vhf-gsk.ch`) verweisen.
4. Das Archiv SOLL pro Client (gehashte IP, von der Webseite übermittelt) höchstens 20 Fragen pro Stunde und pro Mandant ein konfigurierbares monatliches Token-Budget zulassen; bei Überschreitung SOLL der Chatbot freundlich ablehnen (geändert 13.11.2026, entschieden von Fabian, E-B; bisher «von WordPress übermittelt»). Spec 03, 15.1 ergänzt (13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Zusätzlich gilt die Grenze je Lese-Schlüssel.
5. Der Chatbot SOLL keine Gesprächsinhalte mit Personenbezug speichern; gespeichert werden Frage, Quellen und Tokens für 90 Tage.

### Requirement 7: WordPress-Plugin (M4)
**User Story:** Als Redaktor der Verbandsseite möchte ich Archiv, Suche und Chatbot per Shortcode oder Block einfügen,
damit ich ohne Programmierung Seiten gestalten kann.

#### Acceptance Criteria
1. Das Plugin SOLL eine Einstellungsseite für Archiv-URL, Lese-Schlüssel, optional Erfassungs-Schlüssel, Cache-Dauer, die WordPress-Rollen für `mitglieder` und `vorstand` und, nur mit Erfassungs-Schlüssel, die WordPress-Rolle der Geschäftsstelle (7.10) bieten; die Schlüssel SOLLEN nie an den Browser gelangen (geändert 13.11.2026, entschieden von Fabian, E-B und E-C; bisher «für Archiv-URL, Site-Key und Cache-Dauer …; der Site-Key SOLL nie an den Browser gelangen»).
2. Das Plugin SOLL die Shortcodes `[urinet_archiv_liste reihe="" fachgebiet=""]`, `[urinet_archiv_suche]` und `[urinet_chatbot]` sowie gleichwertige Gutenberg-Blöcke anbieten. Spec 03, 5.11 ergänzt dieses Kriterium (13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Dazu kommt eine Dokumentseite (Route `/urinet-archiv/dokument/{id}` aus den Details nach 5.1), auf die Liste, Suche, Chat-Quellen und die Umleitungen alter Links (4.3) verweisen (umgesetzt in Aufgabe 11.5). Spec 05, 8.6 ergänzt dieses Kriterium: Die Filter der Liste sind neben Reihe und Jahr die Klassifikationen mit «öffentlich filterbar» (z. B. `fachgebiet`), und nur diese werden ausgegeben (umgesetzt in Spec 03, Aufgabe 11.5).
3. Das Plugin SOLL die Zugriffsstufe aus dem WordPress-Login ableiten (konfigurierbare Rollen für Mitglieder und Vorstand; hat eine Person beide, gilt die höhere) und serverseitig an das Archiv melden (geändert 13.11.2026, entschieden von Fabian, E-C; bisher «konfigurierbare Rolle für Mitglieder»).
4. Das Plugin SOLL Listen- und Suchergebnisse für die konfigurierte Dauer (Standard 10 Minuten) als Transient zwischenspeichern, getrennt nach Zugriffsstufe (drei Stufen nach 3.7; ergänzt 13.11.2026, entschieden von Fabian, E-C).
5. FALLS das Archiv nicht erreichbar ist, DANN SOLL das Plugin eine freundliche Meldung zeigen und die Seite nicht abbrechen.
6. Die Ausgabe SOLL WCAG 2.1 AA erfüllen und das Theme der Verbandsseite übernehmen (keine eigenen Schriften).
7. Das Plugin SOLL mit der beim Projektstart aktuellen WordPress-Version und PHP 8.2+ funktionieren und die WordPress Coding Standards einhalten.
8. WENN eine Person ein Dokument über die Download-Route des Plugins öffnet, DANN SOLL das Plugin bei der Stufe `oeffentlich` auf die vorsignierte URL umleiten (302) und bei den Stufen `mitglieder` und `vorstand` den Stream des Archivs selbst an den Browser durchreichen, nur wenn die Person die Stufe hat, mit `Cache-Control: private, no-store` und ohne die Datei in WordPress oder auf dem Webhosting abzulegen, auch nicht als temporäre Datei (neu 13.11.2026, entschieden von Fabian am 13.11.2026, E-D). FALLS das Archiv mit HTTP 429 antwortet, DANN SOLL das Plugin eine freundliche Meldung zeigen, es später nochmals zu versuchen (Präzisierung vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
9. Das Plugin SOLL den Änderungs-Feed (Requirement 14) regelmässig abfragen und betroffene Transients sofort verwerfen, damit ein zurückgezogenes Dokument nicht bis zum Ablauf der Cache-Dauer sichtbar bleibt (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
10. WO ein Erfassungs-Schlüssel eingetragen ist, SOLL das Plugin der Geschäftsstelle im WordPress-Admin Hochladen, Status, Bestätigen, Metadaten ändern sowie Veröffentlichen und Zurückziehen nach Requirement 10 anbieten, nur für die konfigurierte WordPress-Rolle der Geschäftsstelle, mit der Kennung der angemeldeten Person als Bearbeiter; die Datei SOLL nach der Übergabe nicht in der WordPress-Mediathek bleiben («entfernen» heisst zurückziehen, 10.7) (neu 13.11.2026, entschieden von Fabian am 13.11.2026, E-A). Ergänzt 13.11.2026 (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Dazu gehören die Liste der Dokumente des Mandanten (10.13), das Ansehen der Datei (10.14) und Wiederholen (10.8); Angaben, die die Person beim Hochladen erfasst, hält das Plugin je Referenz und belegt damit das Bestätigen vor, sobald der Feed `EINGANGSKORB` meldet; sie gehen dem KI-Vorschlag vor, und erst das Bestätigen durch die Person übernimmt sie.

### Requirement 8: Mandantendaten, Kosten und Vertragsende
**User Story:** Als UriNet möchte ich Daten und Kosten pro Mandant im Griff haben,
damit ich Auftragsbearbeitung (revDSG) und Abrechnung sauber erfülle.

#### Acceptance Criteria
1. Das Archiv SOLL pro Mandant und Monat einen Bericht mit Speicherbedarf, Anzahl Dokumente, KI-Tokens (nach Zweck) und Chat-Fragen erstellen.
2. WENN der Betreiber einen Vollexport für einen Mandanten auslöst, DANN SOLL das Archiv alle Dokumente (Original, Arbeitsfassung falls vorhanden und PDF/A), Metadaten als JSON und CSV sowie Manifeste als ZIP bereitstellen. Spec 04, 17.5 ergänzt: Der Vollexport enthält den OKF-Export der Wissensbasis (umgesetzt in Spec 04, Aufgabe 15.5). Spec 05 ergänzt dieses Kriterium: Der Vollexport enthält je Dokument die Klassifikationen mit Code und Name und die Konfigurationsversion sowie die aktuelle Konfigurationsdatei und alle Konfigurationsversionen des Mandanten (umgesetzt in Spec 03, Aufgabe 12.2).
3. WENN ein Mandant gekündigt und der Vollexport bestätigt übergeben ist, DANN SOLL das Archiv den Mandanten deaktivieren und nach einer Frist von 30 Tagen Schema, Keycloak-Client und Bucket-Inhalte löschen, soweit keine Object-Lock-Sperre entgegensteht; verbleibende gesperrte Objekte SOLLEN aufgelistet werden.
4. Das Repository SOLL eine Vorlage für den Auftragsbearbeitungsvertrag (Datenstandort Schweiz mit der Ausnahme nach Spec 01, 16.7; Unterauftragnehmer Infomaniak für Hosting, Object Storage und KI, Anbieter der Zweitkopie, Zeitstempeldienst sowie GitHub Actions als Transportweg ausschliesslich verschlüsselter Sicherungsdaten; Rückgabe und Löschung) unter `docs/vertrag/` enthalten. Nachweis: Dokument.
5. WENN ein Mandant nach 8.3 gelöscht wird, DANN SOLL das Archiv im Abbau-Bericht festhalten, bis wann seine Daten noch in den Sicherungsständen der Zweitkopie enthalten sind (Aufbewahrung nach Spec 01, 16.2). Für Datenbankstände gilt die Frist nach Spec 01, 16.2; die Zuwächse des Buckets werden dort nie ausgedünnt, für Dateien nennt der Bericht darum «unbefristet, bis die Spec zur Löschfunktion (Spec 01, 9.9) einen Weg regelt» (geändert 12.11.2026 mit Spec 01, Aufgabe 4.9; bisher nur «bis wann»; vorgeschlagen 12.11.2026, entschieden mit dem Merge von PR #86).

### Requirement 9: Abnahme Phase 3
#### Acceptance Criteria
1. Ein automatisierter Isolationstest SOLL mit Benutzern, Webseiten-Schlüsseln (Lese- und Erfassungs-Schlüssel) und Chat beider Mandanten nachweisen, dass kein Zugriff über Mandantengrenzen möglich ist (geändert 13.11.2026, entschieden von Fabian, E-B; bisher «Site-Keys»); er umfasst Lesen, Erfassung, Stream, Änderungs-Feed und Referenzen (ergänzt 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR). Spec 04, 11.5 erweitert den Test um die Wissensbasis (umgesetzt in Spec 04, Aufgabe 16.1).
2. Ein Sicherheitstest der öffentlichen Schnittstelle (OWASP API Top 10 als Prüfliste) SOLL ohne offenen Befund der Stufe hoch abgeschlossen sein; er umfasst die ganze Webseiten-Schnittstelle mit Erfassung, Stream, Änderungs-Feed, der Stufe `vorstand` und der Erschöpfung der Schlüsselprüfung durch fremde Anfragen (ergänzt 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR). Nachweis: Dokument (Prüfbericht unter `docs/abnahme/`).
3. WENN das VHF/GSK-Archiv auf der Verbandsseite online ist und die Geschäftsstelle eine neue Ausgabe selbständig veröffentlicht hat, DANN SOLL das Gate «Phase 3 bestanden» gelten. Nachweis: manuell [Fabian].
4. Ein automatisierter Test SOLL für zwei Schlüssel desselben Mandanten nachweisen: Ein Lese-Schlüssel mit Stufe `oeffentlich` sieht über keinen Weg (Liste, Suche, Details, Datei, Filter, Änderungs-Feed, Chat) ein Dokument der Stufe `mitglieder` oder `vorstand`; ein Lese-Schlüssel erreicht keinen Endpunkt der Erfassung und ein Erfassungs-Schlüssel keinen lesenden; Referenzen einer Webseite sind für eine andere Webseite nicht auffindbar (Statusabruf, Liste, Änderungs-Feed); ein widerrufener oder abgelaufener Schlüssel erhält 401. Ein Eintrag `NICHT_SICHTBAR` im Änderungs-Feed zu einem Dokument, das der Schlüssel vorher sehen durfte, ist erlaubt, weil er nur die schon bekannte ID und keine Stufe über dem Schlüssel nennt (14.1) (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).

### Requirement 10: Erfassung über eine Webseite (ohne Konzept-ID, erweitert M3 und M4)
**User Story:** Als Geschäftsstelle möchte ich Publikationen auf der Verbandsseite hochladen, bestätigen, ändern und
veröffentlichen, damit die Webseite meine einzige Oberfläche sein kann; die Web-App des Archivs bleibt daneben.
Entschieden von Fabian am 13.11.2026 (E-A, Erfassung «beides»).

#### Acceptance Criteria
1. WENN eine Webseite mit einem Erfassungs-Schlüssel eine Datei an `POST /public/v1/erfassung/dokumente` sendet, DANN SOLL das Archiv sie wie einen Upload nach Spec 01, Requirement 3 annehmen (202 mit `id`, `status` und `ETag`; Prüfungen und Fehlercodes nach Spec 01, 3.1 bis 3.10; Quelle `WEBSEITE`), genau eine Datei je Anfrage.
2. Das Archiv SOLL die Datei nur als Bytes im Teil `datei` einer `multipart/form-data`-Anfrage annehmen und nie eine URL, von der es eine Datei abholt; FALLS die Anfrage andere Teile als `datei`, `referenz` und `hinweis` enthält, DANN SOLL das Archiv mit HTTP 400 `ANFRAGE_UNGUELTIG` antworten und nichts speichern.
3. Jeder Upload SOLL eine Referenz der Webseite tragen (Pflicht). WENN dieselbe Webseite dieselbe Referenz mit derselben Datei (SHA-256) erneut sendet, DANN SOLL das Archiv mit HTTP 200 und dem bestehenden Dokument antworten; FALLS die Referenz schon zu einer anderen Datei gehört, DANN SOLL es mit HTTP 409 `REFERENZ_VERGEBEN` antworten; FALLS die Datei unter einer anderen Referenz schon besteht, DANN SOLL es mit HTTP 200, dem vorhandenen Dokument und `dublette = true` antworten (Spec 01, 3.4; ohne ID, wenn der Erfassungs-Schlüssel das Dokument nach Spec 01, 2.3 nicht sehen darf). Gleichzeitige Uploads derselben Referenz SOLLEN dasselbe Ergebnis wie nacheinander gesendete erhalten. Eine Referenz, deren Dokument verworfen ist, gilt als frei: Ein neuer Upload legt ein neues Dokument an und bindet die Referenz neu (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
4. Das Archiv SOLL je Dokument den Status (`NEU`, `VERARBEITUNG`, `EINGANGSKORB`, `FEHLER`, `ARCHIVIERUNG`, `ARCHIVIERUNG_FEHLER`, `ARCHIVIERT`, `VERWORFEN`), bei Fehlern einen stabilen Fehlercode ohne interne Details, die Metadaten, den KI-Vorschlag, `zugriff`, `veroeffentlicht` und das `ETag` liefern, vollständig über die ID und über die Referenz die ID mit den Statusfeldern, sowie den Eingangskorb des Mandanten (Spec 01, Requirement 8). Referenzen SOLLEN nur für die Webseite des Schlüssels auffindbar sein; eine Referenz einer anderen Webseite ergibt HTTP 404.
5. WENN die Webseite ein Dokument bestätigt (A7) oder Metadaten ändert (A6, Vertraulichkeit A8), DANN SOLL das Archiv dieselben Regeln wie die Web-App anwenden: `If-Match` mit dem `ETag` nach Spec 01, 10.4 und 10.6 (412 `VERSION_KONFLIKT`, 428 `VERSION_FEHLT`), Pflichtfelder nach Spec 01, 8.3, Status nach Spec 01, 8.8, Grund bei `ARCHIVIERT` nach Spec 01, 10.1; die Zugriffsstufe setzt nur die Person, nie die KI (3.3). `veroeffentlicht` SOLL die Erfassung nur über 10.6 setzen; FALLS Bestätigen oder Ändern der Erfassung `veroeffentlicht` in `zusatz` enthält, DANN SOLL das Archiv mit HTTP 400 `ANFRAGE_UNGUELTIG` antworten (Präzisierung vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
6. WENN die Webseite ein Dokument veröffentlicht oder zurückzieht, DANN SOLL das Archiv `veroeffentlicht` mit `If-Match` setzen, nur im Status `ARCHIVIERT` (sonst HTTP 409 `STATUS_KONFLIKT`), die Fassung in `dokument_version` mit festem Grund speichern und die Aktion `AENDERN` protokollieren.
7. Die Webseiten-Schnittstelle SOLL kein Löschen anbieten: «entfernen» auf der Webseite heisst zurückziehen (`veroeffentlicht = false`); physisches Löschen bleibt der Spec zur Löschfunktion vorbehalten (Spec 01, 9.9).
8. Ein Erfassungs-Schlüssel SOLL höchstens mit den Rechten A1 bis A10 der Spalte `geschaeftsstelle` der Rechtematrix (Spec 01, 2.6) handeln, nie mit A11 oder höher, und zwar nur mit einer festen Liste: A1 bis A8 und A10. Verwerfen (A9) bietet die Webseiten-Schnittstelle nicht an, weil es die Dateien unter `eingang/` löscht (Spec 01, 6.6) und Löschen nach 10.7 in der Web-App bzw. bei Spec 01, 9.9 bleibt; Wiederholen (A10) bietet sie für `FEHLER` und `ARCHIVIERUNG_FEHLER` an wie Spec 01, 8.7 (Wiederholen vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR; bisher auch ohne A10).
9. WENN eine Erfassungsaktion protokolliert wird (Spec 01, 10.2), DANN SOLL das Protokoll neben `token:<id>` den gemeldeten Bearbeiter enthalten; FALLS der Header `X-Archiv-Bearbeiter` bei einer protokollierten Aktion fehlt, ungültig ist oder einen Bearbeiter nennt, den die Webseite nicht nach 10.12 gemeldet hat, DANN SOLL das Archiv mit HTTP 400 `ANFRAGE_UNGUELTIG` antworten und nichts ausführen (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
10. WENN dasselbe Dokument in der Web-App und über die Webseite bearbeitet wird, DANN SOLL die Versionsprüfung (5) die spätere Änderung mit HTTP 412 abweisen; beide Wege arbeiten auf denselben Daten.
11. Die Erfassung SOLL den Dateinamen nie ausgeben (Spec 01, 3.13) und ein Feld `dateiname` in Bestätigen oder Ändern mit HTTP 400 ablehnen (Spec 01, 3.14).
12. WENN die Webseite einen Bearbeiter mit Anzeigename meldet, DANN SOLL das Archiv ihn als Kennung `<webseite>/<bearbeiter>` mit Name, Rolle `geschaeftsstelle` und gültig von in das Benutzerverzeichnis (Spec 01, 10.10) einfügen, sofern die Kennung neu ist oder sich der Name geändert hat, damit jede Erfassungsaktion während der Frist nach Spec 01, 10.9 einer Person zuordenbar bleibt, auch wenn die Webseite ihre Benutzer löscht oder Kennungen neu vergibt; das Verzeichnis bleibt nur anfügend (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
13. WENN die Webseite die Dokumente des Mandanten auflistet, DANN SOLL das Archiv sie wie Spec 01, Requirement 14 (Filter Status, Reihe, Jahr, `veroeffentlicht`, Suchbegriff; nur sichtbare nach Spec 01, 2.3) liefern, ohne Dateinamen und mit der Referenz nur für Dokumente der eigenen Webseite, damit auch Altbestand und Dokumente aus der Web-App über die Webseite veröffentlicht und geändert werden können (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
14. WENN die Webseite die Datei eines Dokuments zur Prüfung anfordert, DANN SOLL das Archiv sie für Dokumente, die der Erfassungs-Schlüssel sehen darf, in jedem Status ausser `VERWORFEN` nach Requirement 13 streamen (nie als Speicher-URL, Protokoll `HERUNTERLADEN` mit Bearbeiter), damit die Geschäftsstelle beim Bestätigen die Felder gegen das Dokument prüfen kann (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).

### Requirement 11: Vertrag und Versionierung der Webseiten-Schnittstelle (ohne Konzept-ID, erweitert M3)
**User Story:** Als UriNet möchte ich jeder Webseite einen stabilen, dokumentierten Vertrag bieten, damit eine
Einbindung nicht bei jeder Änderung des Archivs bricht. Entschieden von Fabian am 13.11.2026 (E-B, neutrale Server-API).

#### Acceptance Criteria
1. Die Webseiten-Schnittstelle SOLL für jede Webseite gleich sein und nichts voraussetzen, was nur WordPress bietet; das WordPress-Plugin SOLL sie nur über diesen Vertrag nutzen. Nachweis: Dokument (Vertrag `docs/schnittstelle/public-v1.yaml` ohne WordPress-Begriffe; Plugin-Tests gegen den Mock aus dieser Datei).
2. Das Repository SOLL je Hauptversion eine OpenAPI-Beschreibung der Webseiten-Schnittstelle enthalten (`docs/schnittstelle/public-v1.yaml`), und die CI SOLL abbrechen, wenn Code und Datei nicht übereinstimmen. Nachweis: JUnit (`PublicVertragTest`).
3. Innerhalb einer Hauptversion SOLLEN nur additive Änderungen erfolgen (neue Endpunkte, neue optionale Parameter, neue Antwortfelder, neue Werte nur bei Feldern, die der Vertrag als erweiterbar kennzeichnet); FALLS eine Änderung der Datei gegenüber `main` eine andere Änderung enthält, DANN SOLL die CI abbrechen. Nachweis: Dokument (CI-Schritt im Build-Job mit vollständiger Git-Historie wie «Bestehende Liquibase-Changesets unverändert»; geändert 13.11.2026, bisher JUnit).
4. WENN eine Änderung nicht additiv ist, DANN SOLL das Archiv sie nur unter einer neuen Hauptversion (`/public/v2`) anbieten und die alte Version mindestens 6 Monate ab Bereitstellung der neuen parallel betreiben (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR). Nachweis: Dokument (Vertrag und Betriebsplan der neuen Hauptversion).
5. WENN eine Hauptversion abgekündigt ist, DANN SOLL das Archiv jede Antwort dieser Version mit den Headern `Deprecation`, `Sunset` (Abschaltdatum) und `Link` auf die Nachfolgeversion versehen.
6. Der Vertrag SOLL festhalten, dass Nutzer unbekannte Antwortfelder ignorieren, und das Plugin SOLL das tun. Nachweis: Dokument (Vertrag; das Plugin prüft es in PHPUnit mit einem Mock, der zusätzliche Felder liefert).

### Requirement 12: Webseiten-Schlüssel (ohne Konzept-ID, erweitert M3)
**User Story:** Als Betreiber möchte ich je Webseite getrennte Schlüssel mit geringsten Rechten ausgeben, rotieren und
widerrufen, damit ein verlorener Schlüssel wenig Schaden anrichtet.

#### Acceptance Criteria
1. Ein Lese-Schlüssel (Scope `webseite-lesen`) SOLL nur die lesenden Endpunkte und ein Erfassungs-Schlüssel (Scope `webseite-erfassung`) nur die Endpunkte der Erfassung erreichen, sonst HTTP 403 `NICHT_ERLAUBT`; jede andere Anmeldung (Benutzer-Token, Scope `generator` oder `import`) SOLL unter `/public/` HTTP 403 erhalten. Bindung an den Mandanten, Speicherung (Argon2id) und Widerruf wie Spec 01, 2.5 (entschieden von Fabian am 13.11.2026, E-B; Trennung vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
2. Jeder Webseiten-Schlüssel SOLL genau einer Webseite (Kennung) zugeordnet sein und eine höchste Zugriffsstufe tragen; das Archiv SOLL nie mehr liefern als diese Stufe, unabhängig von der gemeldeten Stufe.
3. WENN der Betreiber einen Webseiten-Schlüssel per Kommando anlegt, DANN SOLL das Archiv den Wert genau einmal ausgeben und nur den Hash speichern; die Verwaltung `/api/v1/api-tokens` (A14) SOLL Webseiten-Schlüssel auflisten und widerrufen, aber nicht anlegen.
4. WENN der Betreiber einen Schlüssel rotiert, DANN SOLL das Archiv einen neuen Schlüssel mit denselben Angaben ausgeben und den alten bis zum Ende der angegebenen Überlappung (höchstens 30 Tage) weiter annehmen, danach mit HTTP 401 ablehnen.
5. WENN ein Schlüssel widerrufen wird, DANN SOLL das Archiv ihn ab der nächsten Anfrage mit HTTP 401 ablehnen.
6. WO für einen Schlüssel eine Absender-Einschränkung hinterlegt ist, SOLL das Archiv Anfragen anderer Absender mit HTTP 401 ablehnen; die Werte stehen nur in der Betriebskonfiguration, nie im Repository; fehlen sie für einen Schlüssel mit Einschränkung, lehnt das Archiv jede Anfrage ab (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
7. Logs und Kommandoausgaben auf stderr SOLLEN nur Schlüssel-ID, Mandant, Webseite und Scope nennen, nie den Schlüssel.
8. WENN fremde Anfragen mit formal gültigen, aber falschen Schlüsseln alle Plätze der Schlüsselprüfung (Spec 01, 2.5, `HashSperre`) belegen, DANN SOLL ein Webseiten-Schlüssel, den das Archiv kurz vorher erfolgreich geprüft hat, ohne neue Argon2id-Rechnung weiter angenommen werden, damit fremde Anfragen die Webseite nicht aussperren; Widerruf, Ablauf und Sperre des Mandanten SOLLEN trotzdem ab der nächsten Anfrage gelten (12.5) (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).

### Requirement 13: Dateiauslieferung (ohne Konzept-ID, erweitert M3)
**User Story:** Als Geschäftsstelle möchte ich, dass geschützte Dokumente nur über die Webseite ausgeliefert werden,
nie über eine direkte Datei-URL. Entschieden von Fabian am 13.11.2026 (E-D, «gemischt»).

#### Acceptance Criteria
1. WENN die Datei eines Dokuments der Stufe `oeffentlich` angefordert wird, DANN SOLL das Archiv eine vorsignierte URL nach 5.4 liefern (höchstens 5 Minuten).
2. WENN die Datei eines Dokuments der Stufe `mitglieder` oder `vorstand` angefordert wird, DANN SOLL das Archiv sie an den Webseitenserver streamen und für diese Dokumente nie eine vorsignierte oder andere direkte Speicher-URL ausgeben.
3. Das Archiv SOLL beim Streamen nie die ganze Datei im Speicher halten, sondern in Blöcken fester Grösse weitergeben. Nachweis: JUnit (`DateiStreamTest`).
4. FALLS bereits die Höchstzahl gleichzeitiger Streams läuft (gesamt oder je Schlüssel, Startwerte in design.md), DANN SOLL das Archiv mit HTTP 429 `ZU_VIELE_ANFRAGEN` und `Retry-After` antworten.
5. Die Antwort eines Streams SOLL Medientyp, Länge und `Cache-Control: no-store` tragen und keinen Dateinamen (Spec 01, 3.12).
6. Das Archiv SOLL jede Auslieferung (Link oder Stream) protokollieren, bevor sie beginnt, wie 5.4.
7. WENN der Webseitenserver einen Stream abbricht oder ein Zeitlimit überschreitet (Leerlauf ohne Fortschritt oder Gesamtdauer, Startwerte in design.md), DANN SOLL das Archiv die Verbindung zum Speicher und den Platz des Streams sofort freigeben.
8. Das Archiv SOLL das Protokoll einer Auslieferung (13.6) in einer Transaktion schreiben, die vor dem ersten Byte des Streams endet; der Stream selbst SOLL keine Datenbankverbindung halten (neu 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).

### Requirement 14: Änderungs-Feed (ohne Konzept-ID, erweitert M3 und M4)
**User Story:** Als Webseite möchte ich Statusänderungen und Veröffentlichungen abfragen, damit ich Status anzeigen
und meinen Cache gezielt verwerfen kann, ohne dass das Archiv mich aufruft (vorgeschlagen 13.11.2026, entschieden mit
dem Merge dieses PR).

#### Acceptance Criteria
1. WENN ein Lese-Schlüssel den Feed abfragt, DANN SOLL das Archiv ab dem Cursor die Änderungen liefern, durch die ein Dokument bis zur Stufe des Schlüssels sichtbar wurde, sich als sichtbares Dokument änderte oder nicht mehr sichtbar ist (mit ID, Art der Änderung, Stufe und Zeit, ohne Inhalt); «sichtbar» heisst sichtbar bis zur Stufe des Schlüssels. Die Stufe SOLL die tiefere der Stufen vor und nach der Änderung sein, soweit das Dokument dort sichtbar war bzw. ist, und nie eine Stufe über dem Schlüssel; ab dieser Stufe verwirft die Webseite ihren Cache (Präzisierung vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
2. WENN ein Erfassungs-Schlüssel den Feed abfragt, DANN SOLL das Archiv ab dem Cursor jede Status- und Metadatenänderung der Dokumente des Mandanten liefern (mit ID, Referenz, Status und Fehlercode); die Referenz nur für Dokumente der Webseite des Schlüssels, sonst leer.
3. Das Archiv SOLL jeden Eintrag genau einmal und keinen Eintrag einer beim Abruf noch offenen Transaktion vorzeitig liefern; ein Abruf liefert höchstens `max` Einträge (Standard 100, höchstens 500) und den nächsten Cursor. Nachweis: JUnit (`AenderungFeedTest`).
4. FALLS der Cursor älter als die Aufbewahrung des Feeds (90 Tage) ist, DANN SOLL das Archiv mit HTTP 410 `CURSOR_ABGELAUFEN` antworten; ohne Cursor liefert es nur den aktuellen Cursor.
5. Das Archiv SOLL keine Webseite aufrufen (keine ausgehenden Webhooks). Nachweis: JUnit (ArchUnit: kein HTTP-Client in `integration/webseite`).

### Requirement 15: Grenzen und Fehlerformat (ohne Konzept-ID, erweitert M3)
**User Story:** Als UriNet möchte ich, dass eine Webseite das Archiv nicht überlasten kann und Fehler immer gleich
aussehen, damit Einbindungen robust sind.

#### Acceptance Criteria
1. Das Archiv SOLL je Webseiten-Schlüssel eine Anfrage-Grenze durchsetzen (Startwerte in design.md) und bei Überschreitung mit HTTP 429 `ZU_VIELE_ANFRAGEN` und `Retry-After` antworten; die Grenze je Client im Chat (6.4) gilt zusätzlich (vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
2. Jede Fehlerantwort der Webseiten-Schnittstelle SOLL Problem Details mit stabilem `fehlercode` nach Spec 01 sein, ohne interne Details (keine Eingabewerte, Klassennamen oder Speicherangaben); das ersetzt die bisherige Antwort `{"meldung": …}` (geändert 13.11.2026, vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
3. WENN der Schlüssel fehlt, ungültig, abgelaufen oder widerrufen ist, DANN SOLL das Archiv mit HTTP 401 `NICHT_ANGEMELDET` und `WWW-Authenticate: Bearer` antworten, ohne zu verraten, welcher Fall vorliegt.
4. Jede Antwort der Webseiten-Schnittstelle SOLL `Cache-Control: no-store` tragen; zwischenspeichern darf nur der Webseitenserver nach seinen Regeln (7.4).

## Rückverfolgung Konzept → Spec
| Konzept | Requirement |
| --- | --- |
| M1 (zweiter Mandant) | 1 |
| M2 | 2 |
| M3 | 5, 6 |
| M4 | 7 |
| Rechtliches zweiter Mandant | 8 |
| Gate Phase 3 | 9 |
| ohne Konzept-ID (Auftrag Fabian 13.11.2026: Einbindung in eine andere Webseite über eine API, E-A bis E-D) | 10, 11, 12, 13, 14, 15; Ergänzungen 1.6, 3.7, 5.7–5.11, 7.8–7.10, 9.4 |
