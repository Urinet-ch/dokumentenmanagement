# Requirements Document — 06 Einstellungen, Benutzer und Berechtigungen verwalten

## Introduction
Diese Spec gibt dem Inhaber eines Mandanten drei Möglichkeiten in der Web-App, die heute nur der Betreiber von Hand
hat: (1) Einstellungen seines Mandanten im Betrieb anpassen, (2) Benutzer einrichten, sperren, deaktivieren und
löschen und (3) Berechtigungen verteilen, indem er Benutzern eine der vier Archiv-Rollen zuweist. Heute entstehen
Benutzer per `kcadm.sh` in einem Wegwerf-Container (README, «Benutzer in Keycloak anlegen») mit einem temporären
Passwort, das im Terminal erscheint, und die meisten Einstellungen stehen in `application.properties` oder in
Umgebungsvariablen und brauchen ein Deployment.

Leitgedanken (Begründung in design.md):
- **Keycloak bleibt die Identitätsquelle.** Das Archiv ruft dessen Admin-REST-API mit einem eigenen Dienstkonto ohne
  Realm-Admin-Rechte auf; es speichert keine Passwörter. Die Trennung der Mandanten erzwingt das Archiv (Attribut
  `mandant`, Regel 1 in `tech.md`). Entscheid und Alternativen: ADR-015.
- **Rollen und Rechtematrix bleiben die Quelle im Code.** Die Oberfläche weist Rollen zu, nie einzelne Rechte.
- **Eine Mechanik für Einstellungen.** Alles, was ein Mandant selbst ändern darf, lebt in der Konfiguration des
  Mandanten nach Spec 05 (Versionen, `If-Match`, Konfigurationsdatei, Protokoll `KONFIGURATION_AENDERN`); es gibt keine
  zweite Versionsfolge.
- **Nichts wird ungefragt gelöscht.** Deaktivieren ist der Standard; Löschen nur nach Frist, mit Begründung, ohne die
  Protokolle anzutasten.

Nicht Teil dieser Spec: Selbstregistrierung (bleibt aus), mandantenübergreifende Verwaltung (bleibt beim Betreiber),
einzelne Rechte oder eigene Rollen, Verwaltung von API-Tokens und Webseiten-Schlüsseln (A14, Spec 01, 2.5 und
Spec 03, Requirement 12), Änderung der Rechtematrix A1 bis A19, Geheimnisse und Infrastruktur-Einstellungen.

Einordnung: Wellen 19 bis 21, nach Gate Phase 1. Welle 19 (Benutzer, Rollen) hängt nur von Spec 01 ab und kann parallel
zu den Wellen 17 und 18 laufen; Welle 20 (Einstellungen) baut auf Spec 05, Aufgabe 17.7 auf. Schreibweise der
Akzeptanzkriterien, Nachweis (Cucumber-Szenario mit Tag `@req-<x.y>`, Ausnahmen mit den Vermerken «manuell [Fabian]»,
«Dokument» bzw. «JUnit»), Problem Details und `[offen]`-Marker wie in Spec 01. Die Szenarien liegen unter
`features/verwaltung/` mit dem Tag `@spec-06`. «Spec 0n, x.y» bezeichnet ein Akzeptanzkriterium, «Spec 0n, Aufgabe x.y»
eine Aufgabe.

## Glossary
- **Verwaltung** – die Seiten und Endpunkte unter `/verwaltung/…` bzw. `/api/v1/…`, mit denen der Inhaber Einstellungen,
  Benutzer und Rollen seines Mandanten pflegt (ohne die schon bestehenden Seiten für Dokumentarten, Protokoll und
  API-Tokens).
- **Einstellung** – ein benannter Wert mit Typ, Grenzen und Standard, der das Verhalten des Archivs steuert. Drei
  **Klassen** (Requirement 1): (a) im Betrieb in der App änderbar, je Mandant; (b) nur über Deployment oder
  Konfiguration änderbar; (c) Geheimnisse.
- **Einstellungskatalog** – die eine Liste aller Einstellungen der Klasse (a) im Code (Schlüssel, Gruppe, Typ, Grenzen,
  Standard, Risiko, Recht); aus ihr entstehen Prüfung, Schema-Teil, Vorschau und Oberfläche.
- **Riskante Einstellung** – eine Einstellung der Klasse (a), deren Änderung Daten oder Fristen betrifft, die sich nicht
  einfach zurückstellen lassen (z. B. kürzere Aufbewahrung von Chat-Fragen); sie braucht eine ausdrückliche Bestätigung.
- **Dienstkonto** – der Keycloak-Client `archiv-verwaltung` im Realm `urinet-archiv` (Client-Credentials), mit dem das
  Archiv die Admin-REST-API aufruft; hat nur die Rechte nach Requirement 5.
- **Einladung** – Einmal-Link, den Keycloak per E-Mail an die hinterlegte Adresse sendet; der Link führt durch
  E-Mail bestätigen, Passwort setzen und TOTP einrichten. Das Archiv sieht den Link nie.
- **Benutzerstatus** – `EINGELADEN` (angelegt, Einladung offen), `AKTIV`, `GESPERRT` (vorübergehend, umkehrbar),
  `DEAKTIVIERT` (Austritt, umkehrbar bis zum Löschen), `GELOESCHT` (nur noch im Protokoll und im Benutzerverzeichnis).
- **Step-up** – erneute Anmeldung mit Passwort und TOTP kurz vor einer kritischen Aktion (Requirement 7.2).
- **Token-Widerruf** – Zeitpunkt je Benutzer, vor dem ausgestellte Access-Tokens das Archiv nicht mehr annimmt
  (Requirement 4.9); er schliesst die Lücke zwischen Sperren in Keycloak und Ablauf des JWT.
- **Vorgang** – eine Verwaltungsaktion mit Wirkung in Keycloak; sie wird als Absicht und Abschluss protokolliert
  (Requirement 6.4).
- **Abgleich** – Vergleich der Benutzer und Rollen in Keycloak mit dem Verwaltungsstand und dem Benutzerverzeichnis
  (Requirement 6.5, 9.4).
- **Betreiber** – Fabian in der Rolle des Anbieters (Mandanten anlegen, erster Inhaber, Notfall); handelt nur über
  Admin-Kommando und Keycloak-Admin, nie über die Oberfläche. Es gibt keine Archiv-Rolle `plattform`.

---

### Requirement 1: Einstellungen – Klassen und Mechanik (ohne Konzept-ID)
**User Story:** Als Betreiber möchte ich je Einstellung festlegen, wer sie wo ändern darf, damit ein Mandant seine
eigenen Werte pflegen kann, ohne die Instanz, die Sicherheit oder die Kosten der anderen zu gefährden.

#### Acceptance Criteria
1. Das Archiv SOLL jede Einstellung genau einer von drei Klassen zuordnen: (a) im Betrieb in der App änderbar, je
   Mandant; (b) nur über Deployment oder Konfigurationsdatei änderbar; (c) Geheimnis. Die Zuordnung mit Begründung steht
   in design.md, «Inventar der Einstellungen». Nachweis: JUnit (`EinstellungsInventarTest`: jeder Schlüssel `archiv.*` in
   `application.properties` ausser `%test` hat über seinen Präfix eine Klasse im Inventar; ein Schlüssel ohne Klasse
   macht den Test rot).
2. Das Archiv SOLL in Klasse (a) nur Einstellungen führen, die (i) nur den eigenen Mandanten betreffen, (ii) keine
   Geheimnisse und keine Personendaten enthalten (ausser den Kennungen nach Spec 05, 1.1), (iii) einen Standardwert und
   feste Grenzen haben und (iv) weder Sicherheitsgrenzen noch Ressourcen-Budgets der Instanz (Speicher, Zeitgrenzen,
   Raten je Quelladresse) noch Werte bestimmen, an die Gate 0 oder der Golden-Lauf gebunden sind (Modelle, Prompt).
3. Das Archiv SOLL die Einstellungen der Klasse (a) im Abschnitt `einstellungen` der Konfiguration des Mandanten führen
   (Spec 05, Requirement 1) und dafür weder eine zweite Versionsfolge noch eine zweite Protokollform noch eine zweite
   Datei- oder Prüfmechanik einführen. Die Konfigurationsdatei (Spec 05, Requirement 6) und das Schema
   `konfiguration.schema.json` SOLLEN den erweiterten Abschnitt enthalten.
4. WENN für eine Einstellung der Klasse (a) kein Wert gesetzt ist, DANN SOLL der Standardwert aus dem
   Einstellungskatalog gelten. Wo Spec 03 oder Spec 04 heute einen Startwert aus `application.properties` vorsehen,
   SOLL dieser Startwert als Standard dienen, bis die Migration (1.5) den Wert in die Konfiguration übernommen hat.
5. WENN die Migration dieser Spec läuft, DANN SOLL das Archiv für jeden bestehenden Mandanten den bisher wirksamen Wert
   jeder Einstellung der Klasse (a) (Eigenschaft, Tabelle oder Standard) in die Konfiguration übernehmen und eine
   Version mit Quelle `MIGRATION` anlegen, damit sich das Verhalten nicht ändert. Nachweis: JUnit
   (`EinstellungenMigrationTest`, Schema im Stand vor dieser Spec, zwei Mandanten).
6. Das Archiv SOLL Werte der Klasse (b) nie über Oberfläche, API oder Konfigurationsdatei lesbar oder änderbar machen
   (Ausnahme: reine Anzeige von Verbrauch und Budget nach 2.2). Nachweis: JUnit (`EinstellungskatalogTest`: der Katalog
   enthält nur Schlüssel der Klasse (a); kein Endpunkt der Verwaltung nimmt einen anderen Schlüssel an).
7. Das Archiv SOLL Geheimnisse (Klasse (c)) nie in Antworten, Logs, Protokoll, Konfigurationsabbild, Fehlermeldungen oder
   Oberfläche ausgeben, auch nicht als Status «gesetzt/nicht gesetzt»; ob ein Geheimnis fehlt, meldet nur die
   Startprüfung (Spec 01, `SecretPruefung`) im Log mit dem Dateinamen. Für jedes Geheimnis SOLL `docs/` einen
   definierten Rotationsweg nennen. Nachweis: Dokument (Abschnitt «Geheimnisse rotieren», Aufgabe 21.5).
8. Das Archiv SOLL die Alarm-Empfänger (E-Mail-Adressen sind Personendaten) und das Monatsbudget des öffentlichen Chats
   (Kostenhoheit des Betreibers) der Klasse (b) zuordnen; die Oberfläche zeigt das Budget und den Verbrauch des
   laufenden Monats nur lesend (2.2).

### Requirement 2: Einstellungen anzeigen und ändern (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich die Einstellungen meines Mandanten in der App sehen und ändern, mit klaren
Grenzen, einer Vorschau der Auswirkung und einem Verlauf, damit ich dafür keinen Betreiber brauche und nichts
unbemerkt verändere.

#### Acceptance Criteria
1. Das Archiv SOLL den Einstellungskatalog mit den aktuellen Werten über `GET /mandant/einstellungen` liefern (je
   Einstellung: Schlüssel, Gruppe, Name, Beschreibung, Typ, Einheit, Grenzen, Standard, aktueller Wert, Kennzeichen
   «eigener Wert» oder «Standard», `riskant`, «wirksam ab»). Das Recht richtet sich nach der Gruppe: Mandant, Kennungen,
   Chat und Aufbewahrung A20; Wissensbasis A19 (Spec 04, 12.1); Dokumentarten, Klassifikationen, Regeln, KI-Texte A11
   (Spec 05, 7.1). Ein Benutzer sieht nur die Gruppen, für die er das Recht hat.
2. Das Archiv SOLL Rollen ohne das Recht A20 keine Einstellung des Katalogs lesen lassen (HTTP 403 `NICHT_ERLAUBT`).
   Ausgenommen bleiben die schon öffentlichen oder für alle Rollen lesbaren Antworten (`GET /branding?domain=`, Spec 03,
   2.1; `GET /mandant/funktionen`, Spec 05, 8.8; Dokumentarten und Klassifikationen, Spec 05, 7.2). Mit A20 zeigt
   die Antwort zusätzlich einen Block `betrieb` nur zum Lesen: Monatsbudget und Verbrauch des öffentlichen Chats (aus
   `public.mandant_budget`, Spec 03) und das Budget der Wissensbasis.
3. FALLS ein Wert den Typ, die Grenzen oder die Formregeln des Katalogs verletzt, einen unbekannten Schlüssel nennt oder
   ein Textfeld Steuerzeichen enthält, DANN SOLL das Archiv mit HTTP 422 `KONFIGURATION_UNGUELTIG` und `befunde[]`
   (Pfad, Meldung) antworten und nichts ändern; unbekannte Felder (u. a. `mandant`) SOLL es nie annehmen
   (Spec 05, 6.6).
4. Das Archiv SOLL Eingaben begrenzen: Zahlen auf den Bereich des Katalogs, Texte auf die dort genannte Länge, Listen
   auf höchstens 20 Einträge, den Anfragekörper auf höchstens 16 KiB (darüber HTTP 413).
5. WENN `PATCH /mandant/einstellungen` für eine Einstellung `null` sendet, DANN SOLL der Standard wieder gelten (der
   eigene Wert entfällt); eine Gruppe lässt sich auf Standard setzen, und die App SOLL `einstellungen.*` auf den Stand
   einer früheren Konfigurationsversion zurücksetzen können (neue Version mit den `einstellungen.*` jener Version, alles
   andere unverändert).
6. WENN eine Änderung eine riskante Einstellung (Kennzeichen im Katalog) betrifft und die Bestätigung fehlt
   (`bestaetigt` mit dem Schlüssel), DANN SOLL das Archiv mit HTTP 409 `BESTAETIGUNG_NOETIG` und der Auswirkung
   (Text und Zahl der betroffenen Datensätze) antworten und nichts ändern; mit Bestätigung gilt Step-up (7.2).
7. Das Archiv SOLL über `POST /mandant/einstellungen/vorschau` dieselbe Prüfung und Auswirkung wie die Änderung liefern,
   ohne etwas zu ändern, ohne neue Version und ohne Protokolleintrag.
8. FALLS eine Änderung der Einstellungen ohne `If-Match` mit der aktuellen Konfigurationsversion eintrifft, DANN SOLL
   das Archiv mit HTTP 428 `VERSION_FEHLT` antworten, bei abweichender Version mit HTTP 412 `VERSION_KONFLIKT`
   (Spec 05, 7.6); jede Antwort trägt die Version als `ETag`.
9. WENN eine Änderung wirksam ist, DANN SOLL das Archiv in derselben Transaktion eine neue Konfigurationsversion mit
   Quelle `APP` anlegen und die Änderung mit Version und Pfaden im Zugriffsprotokoll festhalten (Aktion
   `KONFIGURATION_AENDERN`, Spec 05, 1.3), ohne Werte der Kennungen (nur Feldname und «geändert»); eine Änderung ohne
   Unterschied SOLL keine Version erzeugen. Eine eigene Aktion `EINSTELLUNG_AENDERN` gibt es nicht.
10. WENN die Konfigurationsdatei oder das Admin-Kommando (Spec 05, 6.1) Einstellungen der Klasse (a) ändern, DANN SOLL
    das Archiv dieselben Prüfungen und Grenzen anwenden und riskante Änderungen nur mit ausdrücklicher Bestätigung
    übernehmen (API: Parameter `bestaetigt`; Kommando: Option `--bestaetigt <schluessel,…>`), sonst HTTP 409
    `BESTAETIGUNG_NOETIG` bzw. Exit-Code ungleich 0.
11. Das Archiv SOLL Einstellungen nur des eigenen Mandanten lesen und ändern; der Mandant ergibt sich nur aus dem Token.
    Eine Änderung SOLL nie die Einstellungen, Daten oder Vorschläge eines anderen Mandanten verändern.
12. Das Archiv SOLL für jede Einstellung im Katalog angeben, ab wann die Änderung wirkt (Branding nach höchstens
    60 Sekunden, Aufbewahrung beim nächsten Aufräumlauf, Zeitplan beim nächsten Planungslauf nach höchstens 5 Minuten,
    Budgets bei der nächsten Prüfung) und eine Änderung nie rückwirkend auf bereits archivierte Dokumente, Fristen oder
    Sperren anwenden (Spec 01, Regel 2).
13. Das Archiv SOLL die Einstellungen der Wissensbasis (A19: Budget je Ingest, Budget des Lint, Monatsbudget,
    Lint-Zeitplan) nach derselben Mechanik führen; der Pfad `/wissensbasis/einstellungen` und das Recht A19 bleiben
    (Spec 04, design.md). [offen: Überführung der Tabelle `wissens_einstellung` in die Konfiguration – Entscheid Fabian
    im PR dieser Spec; bis dahin gilt Spec 04 unverändert.]
14. FALLS eine Anfrage an die Einstellungen mehrere Fehler zugleich hätte, DANN SOLL das Archiv die Reihenfolge von
    Spec 01 einhalten: 401, 403, 428/412, 409 `BESTAETIGUNG_NOETIG`, 422.

### Requirement 3: Benutzer einrichten und verwalten (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich Personen meines Mandanten einladen, sperren, deaktivieren und löschen, ohne
dass ein Passwort durch meine Hände oder durch das Archiv geht, damit ich Zugänge selbst und sicher verwalten kann.

#### Acceptance Criteria
1. WENN ein Benutzer mit Recht A21 die Benutzer abruft, DANN SOLL das Archiv genau die Benutzer seines Mandanten liefern
   (Benutzername, Vorname, Nachname, E-Mail, Rolle, Status, TOTP eingerichtet, Google verknüpft, Einladung offen seit,
   letzte Aktion nach dem Zugriffsprotokoll), seitenweise mit höchstens 50 Einträgen; Benutzer anderer Mandanten und
   Benutzer ohne `mandant` SOLLEN nie erscheinen.
2. WENN ein Benutzer mit Recht A22 und A23 einen Benutzer anlegt (Benutzername, E-Mail, Vorname, Nachname, Rolle),
   DANN SOLL das Archiv ihn in Keycloak ohne Passwort anlegen, mit dem Attribut `mandant` aus dem Token des Akteurs, mit
   genau der gewählten Archiv-Rolle (4.4) und den erforderlichen Aktionen `VERIFY_EMAIL`, `UPDATE_PASSWORD` und
   `CONFIGURE_TOTP`, und Keycloak den Einmal-Link per E-Mail senden lassen (Gültigkeit Standard 24 Stunden,
   `archiv.verwaltung.einladung-gueltigkeit`, höchstens 7 Tage; der Link gilt einmal). Status danach `EINGELADEN`.
3. Das Archiv SOLL nie ein Passwort oder einen Einmal-Code setzen, erzeugen, empfangen, anzeigen oder protokollieren und
   nie einen Einladungslink kennen. Nachweis: JUnit (der Adapter ruft weder `reset-password` noch einen Endpunkt
   auf, der einen Link zurückgibt; ArchUnit auf die erlaubten Aufrufe).
4. FALLS Benutzername, E-Mail, Vorname, Nachname oder Grund die Formregeln verletzen (Benutzername 3 bis 40 Zeichen aus
   `a–z`, `0–9`, `.`, `_`, `-`; E-Mail höchstens 254 Zeichen und genau ein `@`; Namen 1 bis 60 Zeichen; Grund 10 bis
   500 Zeichen; keine Steuerzeichen), DANN SOLL das Archiv mit HTTP 422 `KONFIGURATION_UNGUELTIG` und `befunde[]`
   antworten. FALLS Benutzername oder E-Mail im Realm schon vergeben sind oder der Benutzername je im
   Benutzerverzeichnis einer anderen Kennung gehörte, DANN SOLL das Archiv mit HTTP 409 `BENUTZER_EXISTIERT`
   antworten, ohne zu nennen, welches Feld oder welcher Mandant betroffen ist.
5. FALLS ein Mandant die Höchstzahl der Benutzer erreicht hat (`archiv.verwaltung.max-benutzer`, Standard 25, nicht
   gelöschte), DANN SOLL das Archiv das Anlegen mit HTTP 409 `STATUS_KONFLIKT` ablehnen.
6. WENN ein Benutzer mit Recht A22 die Einladung eines Benutzers im Status `EINGELADEN` erneut sendet, DANN SOLL das
   Archiv Keycloak den Link erneut senden lassen; höchstens 3 Mal je Benutzer in 24 Stunden (6.6). Frühere Links bleiben
   bis zu ihrem Ablauf gültig (Restrisiko, design.md «Bedrohungsmodell»).
7. FALLS Keycloak die Einladung nicht senden kann, DANN SOLL das Archiv den in diesem Vorgang neu angelegten
   Benutzer wieder entfernen (Kompensation) und mit HTTP 502 `IDENTITAETSDIENST_FEHLER` antworten, damit kein
   halbfertiger Zugang bleibt (5.7).
8. WENN ein Benutzer mit Recht A22 einen Benutzer sperrt (Grund Pflicht), DANN SOLL das Archiv ihn in Keycloak
   deaktivieren, alle seine Sitzungen beenden und den Token-Widerruf setzen (4.9); Status `GESPERRT`. WENN er ihn
   entsperrt, DANN SOLL das Archiv ihn wieder aktivieren und eine allfällige Sperre durch fehlgeschlagene Anmeldungen
   in Keycloak aufheben; Status `AKTIV` (bzw. `EINGELADEN`, falls er noch nie angemeldet war).
9. WENN ein Benutzer mit Recht A22 einen Benutzer deaktiviert (Austritt, Grund Pflicht), DANN SOLL das Archiv ihn wie
   beim Sperren sperren, in derselben Transaktion eine Zeile `AUSTRITT` im Benutzerverzeichnis schreiben (Spec 01,
   10.10) und seine Rolle, Dokumente, `erstellt_von`- und `person_benutzer`-Verweise unverändert lassen; Status
   `DEAKTIVIERT`. Deaktivieren ist der Standardweg beim Ausscheiden einer Person.
10. WENN ein Benutzer mit Recht A22 einen deaktivierten Benutzer reaktiviert, DANN SOLL das Archiv ihn aktivieren
    (Step-up, 7.2) und die Einladung erneut auslösen (neues Passwort und TOTP, weil die Zugangsdaten nach dem
    Austritt als verbraucht gelten); Status `EINGELADEN`.
11. WENN ein Benutzer mit Recht A22 einen Benutzer löscht, DANN SOLL das Archiv dies nur zulassen, wenn der Benutzer
    mindestens `archiv.verwaltung.loeschfrist` (Standard 30 Tage, mindestens 7 Tage) im Status `DEAKTIVIERT` ist, eine
    Begründung vorliegt, Step-up gilt (7.2), der Benutzer nicht der Akteur und nicht der letzte Inhaber ist (4.7);
    sonst HTTP 409 `STATUS_KONFLIKT` bzw. `LETZTER_INHABER`. Es SOLL vorher die Zahl der Protokolleinträge und der
    Dokumente mit Verweis auf den Benutzer anzeigen (Vorschau, ohne Änderung).
12. WENN ein Benutzer gelöscht wird, DANN SOLL das Archiv ihn samt E-Mail, Namen, Zugangsdaten und Verknüpfungen in
    Keycloak löschen; Zugriffsprotokoll, Dokumentversionen, Benutzerverzeichnis und Verweise an Dokumenten bleiben
    mit Kennung (`sub`), Benutzername und Rolle unverändert bestehen (Spec 01, Regel 4 und 10.9). Das Archiv SOLL in
    dieser Spec keinen Eintrag des Zugriffsprotokolls oder des Benutzerverzeichnisses ändern, löschen oder
    pseudonymisieren; den Löschweg nach Ablauf der Frist regelt die Löschfunktion (Spec 01, 9.9). Nachweis: JUnit
    (`KeinLoeschpfadTest` erfasst die neuen Pakete).
13. WENN ein Benutzer mit Recht A22 den Zugang zurücksetzt (Passwort vergessen, Gerät verloren; Step-up), DANN SOLL das
    Archiv die erforderlichen Aktionen `UPDATE_PASSWORD` und `CONFIGURE_TOTP` setzen, ein vorhandenes TOTP-Gerät
    entfernen, alle Sitzungen beenden und Keycloak den Einmal-Link an die hinterlegte Adresse senden lassen; das Archiv
    zeigt nie ein neues Passwort. Weil der Realm kein «Passwort vergessen» anbietet (Spec 01, design.md «Sicherheit»),
    ist dies der einzige Weg.
14. WENN ein Benutzer mit Recht A22 die E-Mail-Adresse eines Benutzers ändert (Step-up), DANN SOLL das Archiv die neue
    Adresse prüfen, die erforderliche Aktion `VERIFY_EMAIL` setzen (Bestätigung an die neue Adresse) und eine Mitteilung
    an die bisherige Adresse senden (aus dem Postfach des Archivs, ohne Namen Dritter); Benutzername und `mandant`
    SOLL nie jemand über die Verwaltung ändern.
15. WO die Anmeldung mit Google eingerichtet ist (Spec 01, 2.25 bis 2.29, Option O.8), SOLL die Verwaltung den Status
    «Google verknüpft» zeigen und einem Benutzer mit Recht A22 das Lösen der Verknüpfung erlauben (Step-up, danach enden
    die Sitzungen, Spec 01, 2.27); das Archiv SOLL nie selbst ein Google-Konto verknüpfen und nie eine Kennung von
    Google entgegennehmen. Das Verknüpfen selbst bleibt nach dem Entscheid in O.8 (Kontoportal oder Admin).
16. Das Archiv SOLL nur diese Statusübergänge zulassen: `EINGELADEN` → `AKTIV` (durch den Benutzer, nicht das
    Archiv), `AKTIV` → `GESPERRT` → `AKTIV`, `AKTIV` oder `GESPERRT` oder `EINGELADEN` → `DEAKTIVIERT` → `EINGELADEN`,
    `DEAKTIVIERT` → `GELOESCHT`; jeder andere Übergang ergibt HTTP 409 `STATUS_KONFLIKT`. Nachweis: JUnit
    (`BenutzerStatusTest`, alle Paare).
17. Das Archiv SOLL Passwort, TOTP und Vorname und Nachname weiterhin jeden Benutzer selbst im Kontoportal von Keycloak
    pflegen lassen; E-Mail-Adresse, Benutzername und `mandant` SOLLEN nur über die Verwaltung bzw. den Betreiber
    änderbar sein (Realm-Export, User Profile, Spec 01, design.md «Sicherheit»). Nachweis: JUnit (`RealmExportTest`).

18. WENN ein Benutzer mit Recht A22 oder A23 die E-Mail-Adresse, den Namen oder die Rolle eines Benutzers ändert, DANN SOLL
    das Archiv `If-Match` mit dem `ETag` des Benutzers verlangen (Hash über Status, Rolle, E-Mail, Vorname, Nachname und
    erforderliche Aktionen aus der letzten Abfrage): ohne Header HTTP 428 `VERSION_FEHLT`, bei abweichendem Stand HTTP 412
    `VERSION_KONFLIKT` (wie Spec 01, 10.4 und 10.6).

### Requirement 4: Rollen und Rechte verteilen (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich jeder Person eine Rolle geben und sehen, was sie damit darf, damit niemand mehr
Rechte hat als nötig und ich keine Rechtetabelle pflegen muss.

#### Acceptance Criteria
1. Das Archiv SOLL die Rechtematrix (Spec 01, 2.6 und Spec 04, 12.1) um die folgenden Zeilen ergänzen; Bedeutung von
   «erlaubt» und «403» und Vorrang von 404 vor 403 wie dort. Die Zeilen A1 bis A19 bleiben unverändert; die Ergänzung
   steht nur in dieser Spec (Spec 01 verweist darauf) und kommt mit Aufgabe 19.5 in das Enum `Aktion`.

| Nr. | Aktion | `inhaber` | `mitarbeiter` | `treuhaender` | `geschaeftsstelle` ¹ |
| --- | --- | --- | --- | --- | --- |
| A20 | Mandanten-Einstellungen ansehen und ändern (Katalog, Gruppen Mandant, Kennungen, Chat und Aufbewahrung) | erlaubt | 403 | 403 | 403 |
| A21 | Benutzer, Rollen und Rechte-Übersicht ansehen | erlaubt | 403 | 403 | 403 |
| A22 | Benutzer verwalten (einladen, sperren, entsperren, deaktivieren, reaktivieren, Zugang zurücksetzen, E-Mail ändern, Google-Verknüpfung lösen, löschen) | erlaubt | 403 | 403 | 403 |
| A23 | Rollen zuweisen | erlaubt | 403 | 403 | 403 |

   ¹ Für `geschaeftsstelle` gilt 403, bis Spec 03, 3.5 die Rechte A11 bis A14 entscheidet; ob die Geschäftsstelle von
   `vhf-gsk` A21 bis A23 erhält, entscheidet derselbe Schritt [offen: Benutzerverwaltung im Mandanten `vhf-gsk` –
   Entscheid Fabian im PR dieser Spec; Empfehlung: Betreiber verwaltet die Benutzer von `vhf-gsk` per Admin-Kommando].
   Ein Benutzer braucht für das Anlegen A22 und A23, weil das Anlegen eine Rolle vergibt.
2. FALLS ein Benutzer ohne das Recht einer Aktion A20 bis A23 sie ausführt, DANN SOLL das Archiv mit HTTP 403
   `NICHT_ERLAUBT` antworten und `VERWEIGERT` protokollieren (Spec 01, 10.7). Nachweis: Szenarien je Rolle und Zeile.
3. Das Archiv SOLL Berechtigungen ausschliesslich durch Zuweisen genau einer der vier Archiv-Rollen vergeben; es SOLL
   keinen Endpunkt, kein Feld und keine Oberfläche für einzelne Rechte oder eigene Rollen anbieten und andere Realm-Rollen
   (auch die von Keycloak) nie lesen, setzen oder entfernen. Nachweis: JUnit (`RollenGrenzeTest`).
4. Das Archiv SOLL je Mandant einen festen Satz vergebbarer Rollen führen (Spalte `public.mandant.verwaltung_rollen`,
   gesetzt vom Betreiber beim Anlegen oder per Admin-Kommando, nie über die Oberfläche; Standard `inhaber`,
   `mitarbeiter`, `treuhaender`; `vhf-gsk`: `geschaeftsstelle`). FALLS ein Aufruf eine andere Rolle verlangt, DANN SOLL
   das Archiv mit HTTP 422 `KONFIGURATION_UNGUELTIG` antworten.
5. WENN ein Benutzer mit Recht A23 die Rolle eines Benutzers ändert (Step-up), DANN SOLL das Archiv in Keycloak die
   neue Rolle setzen und alle anderen der vier Archiv-Rollen entfernen und den Token-Widerruf setzen (4.9); hat ein
   Benutzer in Keycloak mehrere Archiv-Rollen (von Hand gesetzt), SOLL die Verwaltung das als Abweichung
   (`ROLLENKONFLIKT`) anzeigen und nur das Setzen genau einer Rolle zulassen.
6. FALLS ein Benutzer eine Rolle vergeben will, die in der Rangfolge `inhaber`, `geschaeftsstelle`, `mitarbeiter`,
   `treuhaender` (Spec 01, 2.2) über seiner eigenen steht oder nicht zum Satz des Mandanten gehört, seine eigene Rolle
   ändert oder sich selbst sperrt, deaktiviert, löscht oder dessen Zugang zurücksetzt, DANN SOLL das Archiv mit HTTP
   403 `ESKALATION_VERBOTEN` antworten, nichts ändern und `VERWEIGERT` mit dem Fehlercode protokollieren.
7. FALLS ein Vorgang den Mandanten ohne aktiven Inhaber (Status `AKTIV` mit Rolle `inhaber`) liesse (Rolle ändern,
   sperren, deaktivieren, löschen), DANN SOLL das Archiv mit HTTP 409 `LETZTER_INHABER` antworten und nichts ändern. Das
   Archiv SOLL gleichzeitige Verwaltungsvorgänge eines Mandanten nacheinander ausführen (eine Vorgangssperre mit Ablauf
   nach höchstens 15 Sekunden; ein zweiter Vorgang erhält HTTP 409 `STATUS_KONFLIKT`), damit zwei Inhaber sich nicht
   gleichzeitig entfernen. Nachweis: Szenario mit gleichzeitigen Aufrufen und JUnit (`LetzterInhaberTest`).
8. Das Archiv SOLL den Mandanten nie aus Pfad, Query, Header oder Body übernehmen: Der Mandant stammt aus dem
   verifizierten Token; jeder Zielbenutzer SOLL vor jeder Aktion (nicht nur in Listen) auf genau diesen Wert des
   Attributs `mandant` geprüft werden, sonst HTTP 404 `NICHT_GEFUNDEN` (wie Spec 01, 1.4). Das Archiv SOLL das Attribut
   `mandant` eines bestehenden Benutzers nie ändern. Nachweis: Szenarien und `EndpunktWaechterTest` (kein Parameter
   `mandant` an Verwaltungsendpunkten).
9. WENN ein Benutzer gesperrt, deaktiviert, gelöscht, sein Zugang zurückgesetzt oder seine Google-Verknüpfung gelöst
   wird (3.8, 3.9, 3.11, 3.13, 3.15), DANN SOLL das Archiv dessen Sitzungen in Keycloak beenden und den Token-Widerruf
   setzen; WENN seine Rolle sich ändert (4.5), DANN genügt der Token-Widerruf (die Sitzung bleibt, das erneuerte Token
   trägt die neue Rolle). Ein vor dem Widerruf ausgestelltes Access-Token SOLL das Archiv danach spätestens nach 5
   Sekunden mit HTTP 401 `NICHT_ANGEMELDET` ablehnen. Ohne Widerruf (z. B. Änderung von E-Mail oder Namen) wirken
   Änderungen spätestens bei der nächsten Token-Erneuerung; die Lebensdauer des Access-Tokens ist im Realm ausdrücklich
   auf 5 Minuten gesetzt (Realm-Export, `RealmExportTest`).
10. Das Archiv SOLL keine Archiv-Rolle `plattform` oder eine Betreiber-Rolle kennen; Mandanten anlegen, den ersten
    Inhaber einrichten und Notfälle SOLLEN nur über das Admin-Kommando und den Keycloak-Admin gehen, nie über die
    Oberfläche. Nachweis: JUnit (`RollenGrenzeTest`: das Archiv schreibt nie eine Rolle ausserhalb der vier).
11. WO ein Mandant mindestens zwei aktive Inhaber hat, SOLL die Vergabe der Rolle `inhaber` und das Löschen eines
    Benutzers (Vier-Augen) erst wirksam werden, wenn ein zweiter aktiver Inhaber, nicht der Antragsteller, mit Step-up
    bestätigt (Antrag verfällt nach `archiv.verwaltung.antrag-gueltigkeit`, Standard 72 Stunden); WO nur ein Inhaber
    aktiv ist, SOLL Step-up und die Alarm-Mail nach 6.7 genügen.
12. WENN ein Benutzer mit Recht A21 die Rechte-Übersicht eines Benutzers (oder einer Rolle) abruft, DANN SOLL das Archiv
    für jede Aktion der Rechtematrix (A1 bis A23) das Ergebnis der Rolle liefern (erlaubt, 403, 404, nur eigene, nur
    lesend, nur selbst erfasste), mit den Fussnoten, direkt aus der Domain-Regel `Rechtematrix` und ohne eigene Kopie.
    Nachweis: JUnit (`RechteUebersichtTest` vergleicht jede Zelle mit `Rechtematrix`).
13. Das Archiv SOLL API-Tokens und Webseiten-Schlüssel nicht über die Benutzerverwaltung anzeigen oder ändern; sie
    bleiben bei A14 (Seite `/verwaltung/api-tokens`). Die Benutzerseite SOLL darauf verweisen und beim Sperren oder
    Deaktivieren darauf hinweisen, dass Tokens gültig bleiben.

### Requirement 5: Anbindung an Keycloak und Mandantentrennung (ohne Konzept-ID, nicht funktional)
**User Story:** Als Betreiber möchte ich, dass die Benutzerverwaltung die Identitätsquelle nur mit den nötigen Rechten
und nur im Mandanten des Inhabers anfasst, damit ein Fehler oder ein Einbruch in die API nicht das ganze System öffnet.

#### Acceptance Criteria
1. Das Archiv SOLL Keycloak als einzige Quelle für Benutzer, Zugangsdaten, Rollen und das Attribut `mandant` behalten
   und über Spec 01, 10.10 hinaus keine Kopie von Personendaten führen (keine E-Mail, keine Vor- und Nachnamen in
   Tabellen des Archivs).
2. Das Archiv SOLL die Admin-REST-API von Keycloak nur über das Dienstkonto `archiv-verwaltung` im Realm
   `urinet-archiv` aufrufen (Client-Credentials, nur Dienstkonto, kein Browser-Fluss, kein Direct Grant). Das Dienstkonto
   SOLL nur die Rechte `query-users`, `view-users` und `manage-users` des Clients `realm-management` tragen, keines
   der Rechte `realm-admin`, `manage-realm`, `manage-clients`, `manage-identity-providers`, `manage-authorization`,
   `manage-events`, `impersonation` und keine Rolle des Realms `master`. Nachweis: JUnit (`RealmExportTest` prüft Client
   und Rollen, `ExportKeycloak` prüft sie gegen echtes Keycloak).
3. Das Archiv SOLL die Admin-REST-API nur über das interne Netz erreichen (Adresse `archiv.verwaltung.keycloak.basis-url`,
   in `prod` `http://kc:8080`); der öffentliche Router von Keycloak SOLL nur `/realms/` und `/resources/` weiterleiten
   (Spec 01, Plattform und Betrieb). Nachweis: JUnit (`ComposeRoutenTest`-Muster aus Spec 01, Aufgabe 1.9).
4. Das Archiv SOLL das Geheimnis des Dienstkontos nur als Secret-Datei `keycloak_verwaltung_secret` führen (GitHub-Secret
   `KEYCLOAK_VERWALTUNG_SECRET`; nie im Realm-Export, im Repository, in Umgebungsvariablen, Logs oder Antworten); die
   Startprüfung `SecretPruefung` SOLL die Datei in `prod` für die Laufart `api` und das Admin-Kommando verlangen.
   Nachweis: JUnit (`SecretPruefungTest` erweitert, `RealmExportTest`).
5. Das Archiv SOLL Verbindungsaufbau und Anfrage zu Keycloak begrenzen (Standard 2 und 5 Sekunden,
   `archiv.verwaltung.keycloak.verbindungs-zeitgrenze` und `….zeitgrenze`), höchstens 2 Aufrufe gleichzeitig zulassen
   (Wartezeit 200 Millisekunden, danach HTTP 503 `UEBERLASTET` mit `Retry-After: 1`), schreibende Aufrufe nie
   automatisch wiederholen, lesende höchstens einmal, und nach 3 Fehlern in Folge innerhalb von 30 Sekunden für 30
   Sekunden ohne Aufruf mit HTTP 503 `IDENTITAETSDIENST_NICHT_ERREICHBAR` antworten (Schutzschalter, kein Wiederholungssturm).
6. FALLS Keycloak nicht erreichbar ist, die Zeit überschreitet oder mit 5xx antwortet, DANN SOLL das Archiv mit HTTP
   503 `IDENTITAETSDIENST_NICHT_ERREICHBAR` und `Retry-After` antworten; FALLS Keycloak mit einem unerwarteten 4xx
   (insbesondere 401 oder 403 für das Dienstkonto) antwortet, DANN SOLL es mit HTTP 502 `IDENTITAETSDIENST_FEHLER`
   antworten und einen Alarm auslösen (6.7). Antworten und Logs SOLLEN keine Meldungen, Namen oder Werte von
   Keycloak enthalten, nur Fehler-ID, Klasse und Status.
7. Das Archiv SOLL mehrstufige Vorgänge (Anlegen, Rolle setzen, Einladung senden) so ausführen, dass entweder alles
   gelingt oder der Zustand vor dem Vorgang wiederhergestellt wird (Kompensation, z. B. den eben angelegten Benutzer
   entfernen); gelingt die Kompensation nicht, SOLL der Vorgang als `UNKLAR` gelten und der Abgleich (6.5) ihn melden.
   Kein Vorgang SOLL ohne Ergebnis im Protokoll bleiben.
8. SOLANGE Keycloak für die Verwaltung nicht erreichbar ist, SOLL das Archiv Anmeldung, Dokumente, Einstellungen und alle
   übrigen Endpunkte unverändert bedienen; nur die Endpunkte der Benutzer und Rollen antworten nach 5.5 und 5.6. Die
   Readiness der API ändert sich nicht (Spec 01, Plattform und Betrieb); der Zustand des Dienstkontos erscheint im
   internen Detail-Health.
9. Das Archiv SOLL bei jeder Keycloak-Antwort mit Benutzern nur solche weiterverarbeiten, deren Attribut `mandant` genau
   ein Wert ist und dem Mandanten des Akteurs entspricht (Prüfung im Archiv, nicht nur über die Suchanfrage).
10. Das Archiv SOLL das Speicherbudget der Stufe A einhalten: Die Anbindung nutzt den JDK-HTTP-Client und Jackson ohne
    Keycloak-Admin-Bibliothek, Antworten sind auf höchstens 50 Benutzer je Aufruf und 1 MiB begrenzt, der Heap der
    API wächst dadurch um höchstens 4 MiB. Nachweis: manuell [Fabian] (Messung wie Spec 01, Aufgabe 1.10, mit
    Abgleich und Liste von 25 Benutzern).

### Requirement 6: Protokoll, Grenzen und Alarme (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich nachvollziehen, wer wann welchen Zugang oder welche Rolle geändert hat, und bei
auffälliger Häufung gewarnt werden, damit ein Missbrauch auffällt.

#### Acceptance Criteria
1. Das Archiv SOLL die Verwaltung mit folgenden Aktionen im Zugriffsprotokoll festhalten: `BENUTZER_ANLEGEN`,
   `BENUTZER_AENDERN` (mit `details.vorgang` = `SPERREN`, `ENTSPERREN`, `DEAKTIVIEREN`, `REAKTIVIEREN`,
   `ZUGANG_ZURUECKSETZEN`, `EINLADUNG_ERNEUT`, `EMAIL_AENDERN`, `GOOGLE_LOESEN`), `BENUTZER_LOESCHEN`, `ROLLE_ZUWEISEN`,
   `BENUTZER_ANTRAG` (Vier-Augen, 4.11) und als Systemaktion `BENUTZER_ABWEICHUNG` (Abgleich). Änderungen der
   Einstellungen brauchen keine neue Aktion (`KONFIGURATION_AENDERN`, 2.9); Verweigerungen schreiben `VERWEIGERT`.
   Die Aktionen und Fehlercodes kommen mit der jeweiligen Aufgabe in Spec 01 und in die Enums (`FehlercodeSpecTest`).
2. Das Archiv SOLL je Eintrag den Akteur (`benutzer` = Keycloak-`sub`, Rolle), die Zeit, die IP und im Feld `details`
   die `vorgangId`, das Ziel nur als Keycloak-`sub` (`ziel`), den Vorgang, bei Rollenwechsel die Rollennamen alt und neu,
   bei E-Mail, Vorname und Nachname nur Feldname und «geändert» und den Grund (höchstens 500 Zeichen) enthalten, nie
   E-Mail-Adressen, Vor- und Nachnamen, Passwörter, Links, Codes oder Tokens; `dokument_id` bleibt leer. Nachweis: Szenarien
   und JUnit (`VerwaltungProtokollTest` sucht in `details` nach E-Mail-Mustern, Namen aus dem Test und Link-Mustern).
3. WENN das Archiv einen Benutzer anlegt oder bei der Übernahme (9.4) erfasst, DANN SOLL es in derselben Transaktion eine
   Zeile im Benutzerverzeichnis mit Benutzername, Rolle und Anlass `ANLAGE` bzw. `UEBERNAHME` schreiben (Spec 01, 10.10;
   neue Werte des Feldes `anlass` mit eigenem Changeset); bei Deaktivieren eine Zeile `AUSTRITT` (3.9). Das Verzeichnis
   bleibt nur anfügend.
4. WENN ein Vorgang mit Wirkung in Keycloak beginnt, DANN SOLL das Archiv vor dem Aufruf einen Eintrag mit
   `details.phase = ANGEFRAGT` in einer eigenen, abgeschlossenen Transaktion schreiben, nach dem Aufruf einen Eintrag mit
   derselben `vorgangId` und `phase` = `ABGESCHLOSSEN`, `FEHLGESCHLAGEN` oder `UNKLAR`. FALLS der Eintrag `ANGEFRAGT` nicht
   geschrieben werden kann, DANN SOLL das Archiv Keycloak nicht aufrufen und mit HTTP 500 antworten (Spec 01, 10.8).
   Dies präzisiert Spec 01, 10.2 für Aktionen mit Wirkung ausserhalb der Datenbank; reine Datenbankaktionen
   (Einstellungen) bleiben in einer Transaktion.
5. Das Archiv SOLL täglich und beim Start des Workers die Benutzer und Rollen in Keycloak mit dem Verwaltungsstand und
   dem Benutzerverzeichnis abgleichen und Abweichungen melden: Vorgang ohne Abschluss länger als 5 Minuten; Benutzer mit
   Archiv-Rolle ohne `mandant` oder mit mehreren Werten; Benutzer mit mehreren Archiv-Rollen; Benutzer der Verwaltung,
   die in Keycloak fehlen; Benutzer mit einer Rolle `inhaber`, die nicht über die Verwaltung oder das Admin-Kommando
   vergeben wurde. Jede Abweichung SOLL als `BENUTZER_ABWEICHUNG` protokolliert und gemeldet werden (6.7); das
   Archiv SOLL dabei nie etwas in Keycloak ändern.
6. Das Archiv SOLL Verwaltungsaktionen begrenzen (Zählung aus dem Zugriffsprotokoll): je Akteur höchstens 30
   schreibende Aktionen in 60 Minuten, je Mandant höchstens 10 `BENUTZER_ANLEGEN` in 60 Minuten, je Zielbenutzer höchstens
   3 E-Mails (Einladung, erneute Einladung, Zugang zurücksetzen) in 24 Stunden, je Mandant höchstens 60
   Einstellungsänderungen in 60 Minuten (Konfiguration `archiv.verwaltung.grenzen.*`); darüber HTTP 429
   `ZU_VIELE_ANFRAGEN` mit `Retry-After` und `grund = VERWALTUNG`.
7. Das Archiv SOLL bei folgenden Ereignissen eine Alarm-Mail an die Empfänger des Mandanten senden (einmal je Auslöser,
   Muster von `docs/alarme.md`): jede Vergabe der Rolle `inhaber`; mindestens 10 Aktionen der Verwaltung in 10 Minuten;
   mindestens 5 `VERWEIGERT` an Endpunkten der Verwaltung in 10 Minuten; jede `BENUTZER_ABWEICHUNG`; ein Fehler
   `IDENTITAETSDIENST_FEHLER` mit 401 oder 403 des Dienstkontos. Die Mail SOLL nur Aktion, Zahl, Zeit und IDs nennen.
8. Das Archiv SOLL die Protokolleinträge der Verwaltung wie alle anderen anfügend und ohne Änderungs- und Löschpfad
   führen (Spec 01, 10.3, 10.9); der Inhaber SOLL sie unter `/verwaltung/protokoll` nach Aktion, Zeitraum und Benutzer
   filtern können (A13). Nachweis: JUnit (`KeinLoeschpfadTest`, Rechte der DB-Rolle unverändert).
9. Der Realm SOLL Ereignisse und Admin-Ereignisse (ohne Darstellungen der Objekte) aufzeichnen und 400 Tage aufbewahren
   (Spec 01, design.md «Sicherheit», Aufbewahrung der Ereignisse; vorgeschlagen 18.11.2026, entschieden mit dem Merge
   dieses PR); sie dienen als zweite Spur für den Abgleich nach 6.5. Nachweis: JUnit (`RealmExportTest`).

### Requirement 7: Sicherheit der Verwaltung (ohne Konzept-ID)
**User Story:** Als Betreiber möchte ich, dass die Verwaltung nicht zum Weg für Rechteausweitung, Übernahme von Konten
oder Missbrauch wird, damit der Schutz der Dokumente nicht an einer Verwaltungsfunktion scheitert.

#### Acceptance Criteria
1. Das Archiv SOLL in design.md einen Abschnitt «Bedrohungsmodell» führen, der jede der genannten Bedrohungen
   (Rechteausweitung, Mandantenüberschreitung, Übernahme über Einladungslinks, Übernahme über die E-Mail-Adresse,
   fehlender zweiter Faktor, Dienstkonto-Geheimnis, Enumeration, Kosten und Überlast, CSRF und Clickjacking,
   Protokollintegrität) mit Massnahme und Nachweis aufführt. Nachweis: Dokument.
2. WENN eine Aktion der Liste «Step-up» (design.md) eintrifft und die letzte Anmeldung des Akteurs (`auth_time`)
   länger als `archiv.verwaltung.step-up-gueltigkeit` (Standard 5 Minuten, höchstens 15) zurückliegt oder `auth_time` fehlt,
   DANN SOLL das Archiv mit HTTP 401 `STEP_UP_NOETIG` und `WWW-Authenticate: Bearer error="insufficient_user_authentication"`
   samt `max_age` antworten und nichts ändern; die erneute Anmeldung SOLL Passwort und TOTP verlangen, nicht nur den
   Cookie der Sitzung (Nachweis gegen echtes Keycloak: `ExportKeycloak`).
3. Das Archiv SOLL Verwaltungsaktionen nur für Tokens einer Benutzeranmeldung im Realm zulassen; API-Tokens und
   Webseiten-Schlüssel SOLLEN dort mit HTTP 403 `NICHT_ERLAUBT` scheitern (Scope-Bindung, Spec 01, 2.5).
4. Das Archiv SOLL die Einladung ohne Passwort ausstellen, den Link nur an die in Keycloak hinterlegte Adresse senden
   lassen, nach Ablauf (3.2) den Status `EINGELADEN` mit Kennzeichen «abgelaufen» zeigen und die erforderliche Einrichtung
   von TOTP im selben Ablauf erzwingen (ein Benutzer ohne TOTP kann sich nie anmelden, Spec 01, 2.1). Der Realm SOLL für
   Passwörter eine Richtlinie führen (mindestens 12 Zeichen, nicht Benutzername und nicht E-Mail-Adresse). Nachweis:
   JUnit (`RealmExportTest`) und Szenario gegen Keycloak mit Mailpit.
5. Das Archiv SOLL beim Lesen und Ändern von Benutzern nicht verraten, ob eine Kennung in einem anderen Mandanten
   existiert: Fremde oder unbekannte IDs ergeben denselben Status 404; die Suche (`q`, höchstens 40 Zeichen) sucht nur im
   Mandanten; die Meldung bei `BENUTZER_EXISTIERT` nennt weder Feld noch Mandant (Restrisiko: ein Inhaber kann durch
   Versuche ermitteln, ob ein Name im Realm vergeben ist; begrenzt durch 6.6 und protokolliert).
6. Das Archiv SOLL Kosten und Überlast begrenzen: Höchstzahl der Benutzer (3.5), Raten (6.6), Anfragekörper höchstens
   8 KiB, Keycloak-Antworten höchstens 50 Benutzer und 1 MiB (5.10), keine Massenfunktionen (kein Import von
   Benutzerlisten).
7. Das Archiv SOLL Antworten der Verwaltung mit `Cache-Control: no-store` ausliefern, die Web-App mit
   `Content-Security-Policy: frame-ancestors 'none'` und `X-Frame-Options: DENY`, und schreibende Verwaltungsendpunkte nur mit
   `Content-Type: application/json` und `Authorization: Bearer` annehmen (keine Cookies, daher kein CSRF; sonst HTTP
   415 `MEDIENTYP_FALSCH` bzw. 401). Nachweis: Szenarien und JUnit (`SicherheitsHeaderTest`).
8. Das Archiv SOLL Eingaben (Namen, E-Mail, Grund) nie unmaskiert in Logs schreiben, in Oberfläche und Mails nur als Text
   ausgeben und nie in Betreffzeilen setzen. Nachweis: JUnit (`LogHygieneTest`-Muster aus Spec 01, 16.7).
9. Das Archiv SOLL die Rolle des Akteurs bei jedem Aufruf aus dem Token und der Rechtematrix bestimmen und nie über
   den Ablauf des Tokens hinaus zwischenspeichern.

### Requirement 8: Oberfläche (ohne Konzept-ID)
**User Story:** Als Inhaber möchte ich Einstellungen und Benutzer am Handy verständlich pflegen und vor jedem
folgenreichen Schritt gewarnt werden, damit ich nichts versehentlich auslöse.

#### Acceptance Criteria
1. Die App SOLL die Seiten `/verwaltung/einstellungen`, `/verwaltung/benutzer` und `/verwaltung/benutzer/:id` anbieten
   (neben `/verwaltung/dokumentarten`, `/verwaltung/protokoll`, `/verwaltung/api-tokens`), mit Menüpunkten nach Recht
   (A20 bzw. A19 und A11, A21). Ohne Recht SOLL der Menüpunkt fehlen und ein Direktaufruf die Seite «Nicht gefunden»
   zeigen (kein Hinweis auf fehlende Rechte); die API prüft in jedem Fall.
2. Die Seiten SOLLEN ab 375 Pixel Breite ohne waagrechtes Scrollen bedienbar sein (Karten statt Tabellen),
   WCAG 2.1 AA erfüllen (Tastatur, Fokus, Kontrast, Statusmeldungen als `role="status"`, Fehler am Feld) und Status nie nur
   über Farbe zeigen. Nachweis: Szenarien (Playwright mit axe) und manuell [Fabian] auf dem Handy.
3. Alle Texte SOLLEN in `texte.de-CH.ts` stehen; Zeiten in `Europe/Zurich`, Zahlen mit Apostroph.
4. Die App SOLL Bestätigungsdialoge zeigen für: Sperren, Deaktivieren, Löschen (mit Eingabe des Benutzernamens, Anzahl der
   Protokolleinträge und Dokumente aus der Vorschau, Hinweis auf bleibende Protokolle), Rolle ändern (mit der
   Rechte-Übersicht vorher und nachher), Zugang zurücksetzen, Zurücksetzen auf Standard und riskante Einstellungen (mit
   Auswirkung aus der Vorschau). Jeder Dialog nennt, was geschieht und was nicht rückgängig zu machen ist.
5. Die App SOLL Fehler nach einer Matrix behandeln: 401 führt zur Anmeldung mit Rückkehr an dieselbe Stelle; 401
   `STEP_UP_NOETIG` führt zur erneuten Anmeldung und sendet die Aktion danach genau einmal erneut (die erste wurde nicht
   ausgeführt); 403 zeigt «Nicht erlaubt»; 404 «Nicht gefunden»; 409 je Code eine eigene Meldung (`LETZTER_INHABER`,
   `BENUTZER_EXISTIERT`, `STATUS_KONFLIKT`, `BESTAETIGUNG_NOETIG` öffnet den Dialog der Auswirkung); 412 «Die Daten haben
   sich geändert» mit Neu-Laden und erhaltenen Eingaben; 422 mit den Befunden am Feld; 429 mit der Wartezeit; 5xx und
   503 `IDENTITAETSDIENST_NICHT_ERREICHBAR` mit «Später erneut versuchen»; Netzwerkfehler wie 5xx. Verwaltungsaktionen
   werden nie in die Offline-Warteschlange gestellt.
6. Die App SOLL nie ein Passwort, einen Einladungslink oder ein Geheimnis anzeigen; nach dem Einladen steht, dass die
   Person eine E-Mail erhält, und der Status zeigt «Einladung offen seit …».
7. Die Seite der Einstellungen SOLL die Gruppen des Katalogs, je Einstellung Grenzen, Standard, «wirksam ab» und
   «auf Standard», eine Vorschau vor dem Speichern und den Verlauf der Versionen mit Vergleich (nur die Pfade unter
   `einstellungen.*`, Kennungen ohne Werte) zeigen; die Seite der Benutzer SOLL die Rechte-Übersicht je Benutzer und je
   Rolle in verständlichen Gruppen zeigen (Dokumente lesen, erfassen, bestätigen, verwalten).
   Nachweis: Szenarien und manuell [Fabian].

### Requirement 9: Inbetriebnahme, Migration, Tests und Abnahme (ohne Konzept-ID, nicht funktional)
**User Story:** Als Inhaber von Urinet möchte ich, dass die heutigen Zugänge unverändert weiterlaufen und neue Mandanten
einen ersten Inhaber ohne Passwort im Terminal erhalten, damit Betrieb und Abnahme gültig bleiben.

#### Acceptance Criteria
1. WENN der Betreiber `archiv mandant inhaber-einladen <code> --benutzer <name> --email <adresse> --vorname <v> --nachname <n>`
   ausführt (Admin-Kommando, Weg wie `mandant anlegen`, kein HTTP), DANN SOLL das Archiv den ersten Inhaber des Mandanten
   über dasselbe Dienstkonto und dieselben Formregeln anlegen und von Keycloak einladen lassen, ohne Passwort im
   Terminal, mit Protokolleintrag `betreiber:<name>`. `mandant anlegen` bleibt unverändert und legt nie Benutzer an; die
   Admin-Kommandos laufen weiterhin im Wegwerf-Container (README).
2. WENN der Betreiber `archiv benutzer abgleichen <code> [--pruefen]` ausführt, DANN SOLL das Archiv die Benutzer des
   Mandanten in Keycloak lesen, die Abweichungen nach 6.5 und 9.4 ausgeben und ohne `--pruefen` die fehlenden Zeilen im
   Benutzerverzeichnis (`UEBERNAHME`) schreiben; es SOLL nie etwas in Keycloak ändern. Exit-Code 0 ohne Abweichung,
   1 mit Abweichungen, 2 falscher Aufruf, 3 technischer Fehler.
3. Das Archiv SOLL die Verfahren per `kcadm.sh` aus dem README (Benutzer anlegen, Rollen setzen) als Notfallweg für den
   Fall behalten, dass das Dienstkonto oder der Mailversand von Keycloak nicht bereit ist; das README und
   `docs/anleitung-verwaltung.md` SOLLEN den normalen Weg (Oberfläche, Admin-Kommando) zuerst nennen. Nachweis: Dokument.
4. WENN die Migration dieser Spec läuft, DANN SOLL das Archiv die bestehenden Benutzer (heute z. B. `fabian`, ein
   Mitarbeiter, der Treuhänder) nicht verändern: Anmeldung, Passwort, TOTP, Rolle und Attribut bleiben; der Abgleich
   (9.2) nimmt sie in das Benutzerverzeichnis auf und meldet Abweichungen (keine oder mehrere Archiv-Rollen, `mandant`
   fehlt oder mehrwertig, Rollen ausserhalb der vier). Nachweis: manuell [Fabian] (Lauf auf Stufe A) und Szenario mit
   Test-Realm.
5. Das Archiv SOLL für jedes Kriterium dieser Spec ein Szenario mit `@req-<x.y>` und `@spec-06` unter
   `features/verwaltung/` haben (Ausnahmen mit Nachweis-Vermerk), dazu Isolationstests mit zwei Mandanten (`urinet`,
   `vhf-gsk` im Test-Realm) und allen vier Rollen über alle Endpunkte der Verwaltung (`VerwaltungIsolationIT`: kein Lesen,
   Ändern oder Sehen über die Mandantengrenze, auch nicht über IDs, Suche, Protokoll oder Einladung), Eskalationstests
   (`EskalationTest`: jede Rolle als Akteur gegen jede Rolle als Ziel und jede Rolle als vergebene Rolle, auch gegen sich
   selbst) und Tests zum Ausfall von Keycloak (WireMock: Zeitüberschreitung, 5xx, 401 des Dienstkontos, Schutzschalter).
   Nachweis: JUnit und Szenarien.
6. Der Test der Rechtematrix SOLL die Zeilen A20 bis A23 dieser Spec gegen `Aktion.bezeichnung()` und die Rollen
   prüfen (Erweiterung von `RechtematrixSpecTest` mit Aufgabe 19.5); `FehlercodeSpecTest` SOLL die neuen Codes aus
   design.md gegen das Enum prüfen. Nachweis: JUnit.
7. Das Archiv SOLL den Abschluss in `docs/abnahme/verwaltung.md` festhalten, nur mit Nummern und Ergebnissen (grüne
   Szenarien und Tests, die manuellen Prüfungen von Fabian: Einladung mit echtem Postfach, TOTP-Einrichtung, Sperren
   beendet die Sitzung nach höchstens 5 Sekunden, Rolle ändern, Löschen nach Frist, Step-up, Messung des Speichers,
   Rotationsprobe des Dienstkonto-Geheimnisses, Lösen der Google-Verknüpfung WO O.8). Nachweis: Dokument und manuell
   [Fabian].

---

## Rückverfolgung Auftrag → Spec
| Auftrag Fabian (18.11.2026) | Requirement |
| --- | --- |
| Einstellungen in der Anwendung anpassen (Klassen, Validierung, Versionen, Protokoll) | 1, 2, 8 |
| Benutzer einrichten, sperren, deaktivieren, löschen, Zugang zurücksetzen, Google | 3, 5, 6, 7, 8 |
| Berechtigungen verteilen (Rollen, Eskalationsschutz, letzter Inhaber, Rechte-Übersicht) | 4 |
| Protokoll, Fehlerfälle, Grenzen, Alarm | 5, 6 |
| Sicherheit und Bedrohungsmodell | 5, 7 (design.md «Bedrohungsmodell») |
| Inbetriebnahme, Migration, Tests, Abnahme | 9 |
