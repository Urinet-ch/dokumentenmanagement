# Implementation Plan — 06 Einstellungen, Benutzer und Berechtigungen verwalten

Voraussetzungen: Spec 06 auf `main` gemergt, die vier Entscheide aus design.md «Open Points» gefallen (Aufgabe 19.1),
Spec 01 bis einschliesslich Gate Phase 1 umgesetzt (Anmeldung 1.4, API-Tokens 1.5, Protokoll und Benutzerverzeichnis 5.1).
Welle 19 (Benutzer und Rollen) hängt nicht von Spec 05 ab und kann parallel zu den Wellen 17 und 18 laufen; Wunsch: vor
Welle 10 (Benutzer von `vhf-gsk`), nach der Entscheidung zu Spec 03, 3.5. Welle 20 (Einstellungen) setzt Spec 05, Aufgabe
17.7 voraus. Wellen, Rollen und Gates wie in `docs/umsetzungsplan.md`; Arbeitsweise (ein Branch und PR je Aufgabe) nach
`CLAUDE.md`, Branch `feature/06-<aufgabe>-<stichwort>`. Steht ein Wert noch auf «[offen: …]», wird er nicht geraten; die
Aufgabe wartet auf den Entscheid. Abhängigkeiten zu anderen Specs stehen als `Spec 0n: <Aufgabe>`. Jede Aufgabe liefert für
ihre Anforderungen Cucumber-Szenarien mit `@req-<x.y>` und `@spec-06` unter `features/verwaltung/` (ausser bei
Nachweis-Vermerk) und erfüllt die Definition of Done aus `tech.md`. Führt eine Aufgabe einen Fehlercode oder eine Aktion ein
oder erweitert sie die Rechtematrix, trägt sie das im selben PR in Spec 01 nach (design.md «Error Handling» und
«Protokoll», requirements.md 2.6; `FehlercodeSpecTest`, `RechtematrixSpecTest`). Jede Aufgabe ist höchstens etwa drei
Stunden gross. Aufgaben mit **[Fabian]** erledigt Fabian (Realm, Secrets, Deploy, Messungen), nicht der Agent; kein Zugriff
auf Server oder Secrets aus der Sitzung.

## Welle 19 — Dienstkonto, Benutzer und Rollen
- [ ] 19.1 Entscheide und Spike Keycloak [Backend]
  - Die vier Entscheide aus design.md «Open Points» (vhf-gsk, Löschfrist, Wissens-Einstellungen, Alarm-Empfänger und
    Budget) sind mit dem Merge dieses PR gefallen oder stehen als Notiz im PR.
  - Spike gegen den Dev Service mit Keycloak 26.8.2 (Ergebnis als «Umsetzung 19.1» in design.md): feingranulare Admin-Rechte
    (`map-roles` nur auf die vier Archiv-Rollen) für das Dienstkonto; interne Adresse `http://kc:8080` und Aussteller des
    Tokens (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`); Rotation des Client-Secrets mit Überlappung; `auth_time` im Access-Token;
    `prompt=login` gegen den Cookie-Zweig von `browser-totp-pflicht`; `execute-actions-email` für einen Benutzer ohne Passwort
    mit den drei Aktionen; Suche `q=mandant:<m>`; Rollenmitglieder (`/roles/{rolle}/users`).
  - _Anforderungen: 5.2, 5.3, 7.2_
  - _Abhängig von: Spec 01: 1.4_

- [ ] 19.2 Realm-Export und Realm-Tests [Infra]
  - Client `archiv-verwaltung` mit Platzhalter für das Secret und den drei Rollen des Dienstkontos (Härtung nach 19.1);
    Passwort-Richtlinie; Access-Token-Lebensdauer 300 s; Lebensdauer der Aktions-Links; Ereignisse und Admin-Ereignisse mit
    400 Tagen; `auth_time`-Mapper; Test-Realm mit Dienstkonto und SMTP an Mailpit; `RealmExportTest` und `ExportKeycloak`
    erweitert (nur diese Rollen, kein Secret, kein Realm-Admin, Benutzerprofil unverändert).
  - _Anforderungen: 3.17, 4.9, 5.2, 5.3, 6.9, 7.4_
  - _Abhängig von: 19.1_

- [ ] 19.3 Stufe A: Dienstkonto, SMTP, Secrets, Deploy [Fabian]
  - Realm-Änderungen einmalig per `kcadm.sh` im Wegwerf-Container (der Import wirkt nur beim ersten Start); GitHub-Secrets
    `KEYCLOAK_VERWALTUNG_SECRET` und `KEYCLOAK_SMTP_PASSWORT`, Secret-Dateien im Deploy-Bundle und Pflichtprüfung im Workflow;
    Compose: Secret für `api`; Admin-Konsole bleibt nicht öffentlich; Probe: Token des Dienstkontos holbar; Notfallunterlagen
    um das neue Secret ergänzt.
  - _Anforderungen: 5.2, 5.3, 5.4, 6.9, 7.4_
  - _Abhängig von: 19.2_

- [ ] 19.4 Port `IdentitaetsDienst` und Adapter Keycloak [Backend]
  - Port, Dev-Stub, `KeycloakAdminAdapter` (JDK-HTTP-Client, Positivliste der Aufrufe, Token-Cache, Zeitgrenzen, zwei gleichzeitige
    Aufrufe, Schutzschalter, Fehlerabbildung auf 502 und 503, Filter auf `mandant`), Detail-Health; `SecretPruefung` verlangt
    `keycloak_verwaltung_secret` für `api` und Kommando; Konfiguration `archiv.verwaltung.keycloak.*`; WireMock-Tests
    (Zeitüberschreitung, 5xx, 401 des Dienstkontos, Schutzschalter, kein Wiederholungssturm).
  - Codes `IDENTITAETSDIENST_FEHLER`, `IDENTITAETSDIENST_NICHT_ERREICHBAR` in Spec 01 und Enum.
  - _Anforderungen: 3.3, 5.1, 5.4, 5.5, 5.6, 5.8, 5.9, 5.10_
  - _Abhängig von: 19.1_

- [ ] 19.5 Domain-Regeln und Rechtematrix A20 bis A23 [Backend]
  - `Vergaberegeln` (Eskalationsschutz, Satz der Rollen, letzter Inhaber, Rollen ausserhalb der vier), `Benutzerstatus` und
    Statusautomat, `Aktion` A20 bis A23 mit Zeilen der Matrix, `RechteUebersicht`; `RechtematrixSpecTest` liest die Tabelle aus
    Spec 06; Absatz unter der Tabelle in Spec 01 requirements.md 2.6; Changeset `public.mandant.verwaltung_rollen`.
  - _Anforderungen: 3.16, 4.1, 4.2, 4.3, 4.4, 4.6, 4.7, 4.10, 4.12, 9.6_
  - _Abhängig von: Spec 01: 1.4_

- [ ] 19.6 Protokoll, Vorgänge und Persistenz [Backend]
  - Aktionen `BENUTZER_*`, `ROLLE_ZUWEISEN` und Check-Constraints (neue Changesets), `anlass` `ANLAGE` und `UEBERNAHME`,
    Absicht und Abschluss mit `vorgangId`, Vorgangssperre (`verwaltung_vorgang`), `benutzer_status`, `verwaltung_antrag`,
    Raten aus dem Protokoll (`archiv.verwaltung.grenzen.*`), `VerwaltungProtokollTest` (kein Klartext), `KeinLoeschpfadTest`
    erweitert; Code `ZU_VIELE_ANFRAGEN` mit `grund = VERWALTUNG`.
  - _Anforderungen: 4.7, 6.1, 6.2, 6.3, 6.4, 6.6, 6.8_
  - _Abhängig von: 19.5, Spec 01: 5.1_

- [ ] 19.7 REST Benutzer lesen, anlegen und einladen [Backend]
  - `GET /rollen`, `GET /benutzer`, `GET /benutzer/{id}`, `POST /benutzer` (Formregeln, Höchstzahl, `BENUTZER_EXISTIERT`,
    Namenssperre über das Verzeichnis, Kompensation), `POST /benutzer/{id}/einladung`; Sicherheits-Header und
    `Cache-Control: no-store`, nur `application/json`; `EndpunktWaechterTest` erweitert.
  - Codes `BENUTZER_EXISTIERT` und `STATUS_KONFLIKT` für die Höchstzahl.
  - _Anforderungen: 3.1, 3.2, 3.4, 3.5, 3.6, 3.7, 4.8, 5.7, 5.9, 7.5, 7.6, 7.7, 7.8_
  - _Abhängig von: 19.4, 19.6_

- [ ] 19.8 REST Status, Zugang und E-Mail [Backend]
  - `sperren`, `entsperren` (inkl. Sperre durch Fehlversuche), `deaktivieren` (Zeile `AUSTRITT`), `reaktivieren`,
    `zugang-zuruecksetzen` (TOTP entfernen), `PATCH` für E-Mail mit `VERIFY_EMAIL` und Mitteilung an die alte Adresse,
    `DELETE …/google-verknuepfung` (WO O.8), `If-Match` und `ETag` des Benutzers.
  - _Anforderungen: 3.8, 3.9, 3.10, 3.13, 3.14, 3.15, 3.17, 3.18_
  - _Abhängig von: 19.7_

- [ ] 19.9 Token-Widerruf [Backend]
  - Tabelle `token_widerruf`, `ZugangFilter` lehnt Tokens mit `iat` vor `ab` ab (Zwischenspeicher höchstens 5 s), Aufräumen im
    Worker, Setzen nach Sperren, Deaktivieren, Löschen, Zugang zurücksetzen, Google lösen und Rollenwechsel; Szenarien: Sperre
    wirkt nach höchstens 5 Sekunden; Rollenwechsel wirkt nach Erneuerung.
  - _Anforderungen: 4.9_
  - _Abhängig von: 19.8_

- [ ] 19.10 Step-up [Backend]
  - `StepUp` mit `auth_time`, Code `STEP_UP_NOETIG` mit `WWW-Authenticate`, Liste der Aktionen nach design.md, Konfiguration
    `archiv.verwaltung.step-up-gueltigkeit`, API-Tokens und Webseiten-Schlüssel scheitern an der Verwaltung mit 403; Test
    gegen echtes Keycloak (`prompt=login` verlangt Passwort und TOTP).
  - _Anforderungen: 7.2, 7.3, 7.9_
  - _Abhängig von: 19.2, 19.7_

- [ ] 19.11 REST Rolle, Rechte-Übersicht und Anträge [Backend]
  - `PUT /benutzer/{id}/rolle`, `GET /benutzer/{id}/rechte`, Anträge (Vier-Augen: `inhaber` vergeben und Löschen, ab zwei aktiven
    Inhabern; Verfall nach 72 Stunden), Alarm-Hook für `inhaber`, Abweisung des Rollenkonflikts, Hinweis auf API-Tokens in der
    Antwort; Codes `ESKALATION_VERBOTEN`, `LETZTER_INHABER`.
  - _Anforderungen: 3.18, 4.2, 4.5, 4.6, 4.7, 4.11, 4.12, 4.13_
  - _Abhängig von: 19.8, 19.10_

- [ ] 19.12 Löschen nach Frist [Backend]
  - `GET /benutzer/{id}/loeschvorschau`, `POST /benutzer/{id}/loeschen` (Frist, Begründung, Step-up, nicht der Akteur, nicht der
    letzte Inhaber, Vier-Augen), Wirkung in Keycloak, Namenssperre bleibt, Protokoll und Verzeichnis unverändert; `KeinLoeschpfadTest`.
  - _Anforderungen: 3.11, 3.12_
  - _Abhängig von: 19.11_

- [ ] 19.13 Abgleich, Alarme und Raten [Backend]
  - `Abgleich` (täglich im Worker, beim Start; Codes der Abweichungen), `BENUTZER_ABWEICHUNG`, Alarm-Mails (Muster
    `docs/alarme.md`: Vergabe `inhaber`, Häufung, Verweigerungen, Abweichung, Fehler des Dienstkontos), Tabelle der Alarme in
    `docs/alarme.md` ergänzt.
  - _Anforderungen: 6.5, 6.7, 7.6_
  - _Abhängig von: 19.6, 19.11_

## Welle 20 — Einstellungen und Oberfläche
- [ ] 20.1 Einstellungskatalog und Abbild [Backend]
  - `Einstellungskatalog` (Domain), Abschnitt `einstellungen` im Abbild und in `konfiguration.schema.json` (Test
    `EinstellungskatalogSchemaTest`), Prüfung in `Konfiguration.pruefen`, Standardwerte, `EinstellungsInventarTest`,
    `EinstellungskatalogTest` (nur Klasse (a)), Konfigurationsdatei und Export mit dem erweiterten Abschnitt.
  - _Anforderungen: 1.1, 1.2, 1.3, 1.4, 1.6, 2.3, 2.4_
  - _Abhängig von: Spec 05: 17.7_

- [ ] 20.2 REST Einstellungen [Backend]
  - `GET` mit Katalog und Block `betrieb`, `PATCH` als Merge Patch mit `If-Match`, `bestaetigt`, Step-up, Vorschau und
    `Auswirkung`, Zurücksetzen (Gruppe, Version), Datei und Kommando mit Bestätigung; Rechte je Gruppe (A20, A19, A11); Code
    `BESTAETIGUNG_NOETIG`.
  - _Anforderungen: 1.8, 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.14_
  - _Abhängig von: 20.1, 19.10_

- [ ] 20.3 Einstellungen der Wissensbasis in die Mechanik [Backend]
  - Nach dem Entscheid in 19.1: `/wissensbasis/einstellungen` (A19) über denselben Weg; falls die Tabelle `wissens_einstellung`
    schon Werte trägt, Migration mit Quelle `MIGRATION`; sonst Wegfall der Tabelle in einem späteren Changeset.
  - _Anforderungen: 2.13_
  - _Abhängig von: 20.2, Spec 04: 13.6_

- [ ] 20.4 Dienste lesen die Werte der Konfiguration, Migration [Backend]
  - Branding, Aufbewahrung der Chat-Fragen (intern und öffentlich) und die Budgets lesen über `MandantEinstellungen`; die
    Eigenschaften bleiben Standard; Migration der heute wirksamen Werte (Version, Quelle `MIGRATION`); «wirksam ab» wie im
    Katalog.
  - _Anforderungen: 1.5, 2.12_
  - _Abhängig von: 20.2_

- [ ] 20.5 Oberfläche Einstellungen [Frontend]
  - Seite `/verwaltung/einstellungen`: Gruppen, Grenzen, Standard, Vorschau, Dialog bei riskanten Werten, Verlauf mit Vergleich,
    Fehlermatrix, 375 px, WCAG 2.1 AA, Texte in `texte.de-CH.ts`.
  - _Anforderungen: 8.1, 8.2, 8.3, 8.4, 8.5, 8.7_
  - _Abhängig von: 20.2_

- [ ] 20.6 Oberfläche Benutzer lesen [Frontend]
  - Seiten `/verwaltung/benutzer` und `/verwaltung/benutzer/:id`: Liste, Karten, Status, Rechte-Übersicht, Anträge, Hinweis auf API-Tokens;
    Menüpunkte nach Recht, Seite «Nicht gefunden» ohne Recht.
  - _Anforderungen: 8.1, 8.2, 8.3, 8.7_
  - _Abhängig von: 19.7, 19.11_

- [ ] 20.7 Oberfläche Benutzer: Aktionen und Dialoge [Frontend]
  - Einladen, sperren, entsperren, deaktivieren, reaktivieren, Zugang zurücksetzen, Rolle ändern, E-Mail ändern, löschen (mit
    Eingabe des Benutzernamens und Vorschau), Google lösen; Step-up mit genau einer Wiederholung; Fehlermatrix; keine
    Passwörter oder Links in der Oberfläche; keine Offline-Warteschlange.
  - _Anforderungen: 8.4, 8.5, 8.6_
  - _Abhängig von: 19.12, 20.6_

- [ ] 20.8 E-Mail-Texte und Theme von Keycloak [Infra]
  - Deutsche Texte der Einladung (Einmal-Link, Gültigkeit, Hinweis, was zu tun ist) und der Mitteilung an die alte Adresse, Theme
    `urinet-archiv` (Logo-Textmarke, Farben wie `vhf-gsk`-Muster aus Spec 03, 2.3); keine Namen Dritter in Betreffzeilen.
  - _Anforderungen: 7.4, 7.8, 8.6_
  - _Abhängig von: 19.2_

## Welle 21 — Admin-Kommando, Migration, Tests und Abnahme
- [ ] 21.1 Admin-Kommando [Backend]
  - `archiv mandant anlegen … --rollen`, `archiv mandant inhaber-einladen`, `archiv benutzer abgleichen [--pruefen]`,
    `archiv mandant benutzer-sperren <code>` für den Mandantenabbau (Exit-Codes, `--json`), Protokoll `betreiber:<name>`;
    README-Abschnitt «Admin-Kommandos auf der VPS» ergänzt.
  - _Anforderungen: 4.4, 9.1, 9.2, 9.8_
  - _Abhängig von: 19.7, 19.13_

- [ ] 21.2 Migration der heutigen Benutzer [Fabian]
  - Lauf `archiv benutzer abgleichen urinet --pruefen`, dann ohne `--pruefen` auf Stufe A; Abweichungen klären (Rollen, `mandant`,
    TOTP); `fabian` bleibt Inhaber; Anmeldungen vor und nach dem Lauf prüfen; Ergebnis in `docs/abnahme/verwaltung.md`.
  - _Anforderungen: 9.4_
  - _Abhängig von: 21.1, 19.3_

- [ ] 21.3 Isolations- und Eskalationstests [QA]
  - `VerwaltungIsolationIT` (zwei Mandanten, vier Rollen, alle Endpunkte), `EskalationTest` (Matrix), `LetzterInhaberTest` mit
    gleichzeitigen Aufrufen, `RollenGrenzeTest`, Rechtematrix-Test; Beispiel-Mandant ohne Bezug zu `urinet`.
  - _Anforderungen: 4.2, 4.3, 4.6, 4.7, 4.8, 4.10, 9.5, 9.6_
  - _Abhängig von: 19.12, 19.13_

- [ ] 21.4 Sicherheits- und Ausfalltests [QA]
  - Ausfall von Keycloak (kein Hängen, keine Wiederholung, Schutzschalter), Step-up, Raten, Header und Medientyp,
    Protokoll ohne Klartext, Einladung bis zum Link mit Mailpit, Konsistenz nach Abbruch zwischen Absicht und Abschluss.
  - _Anforderungen: 5.5, 5.6, 5.7, 5.8, 6.2, 6.4, 6.6, 7.2, 7.5, 7.7, 7.8, 9.5_
  - _Abhängig von: 19.13, 20.7_

- [ ] 21.5 Dokumentation [Backend]
  - `docs/anleitung-verwaltung.md` (vom Entwurf zur gültigen Anleitung), Abschnitt «Geheimnisse rotieren» in `docs/` (Rotation je
    Geheimnis der Klasse (c), Dienstkonto), README «Benutzer in Keycloak anlegen» als Notweg, `docs/alarme.md`, Notfallunterlagen.
  - _Anforderungen: 1.7, 9.3_
  - _Abhängig von: 20.7, 21.1_

- [ ] 21.6 Abnahme Verwaltung [Fabian]
  - `docs/abnahme/verwaltung.md` nur mit Nummern und Ergebnissen: Szenarien und Tests grün; manuell: Einladung mit echtem
    Postfach, TOTP-Einrichtung, Sperren beendet die Sitzung nach höchstens 5 Sekunden, Rolle ändern, Löschen nach Frist, Step-up,
    Messung des Speichers auf Stufe A, Rotationsprobe des Dienstkonto-Geheimnisses, 375 px am Handy, Lösen der
    Google-Verknüpfung (WO O.8).
  - _Anforderungen: 5.10, 8.2, 9.4, 9.7_
  - _Abhängig von: 21.2, 21.3, 21.4, 21.5_
