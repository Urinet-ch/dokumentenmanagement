# ADR-015: Benutzerverwaltung über die Keycloak-Admin-API mit eigenem Dienstkonto, Mandantentrennung im Archiv

**Status:** Vorgeschlagen – Freigabe per Merge des Spec-PR von Spec 06.

**Datum:** 18.11.2026

## Kontext
- Benutzer entstehen heute von Hand mit `kcadm.sh` in einem Wegwerf-Container (README, «Benutzer in Keycloak anlegen»), mit
  einem temporären Passwort, das im Terminal steht. Der Inhaber eines Mandanten kann keine Benutzer einladen, sperren oder
  umstellen; jede Änderung hängt am Betreiber.
- Keycloak ist Identitätsquelle (ADR-001, Spec 01, 2.1): Passwörter, TOTP, Rollen `inhaber`, `mitarbeiter`, `treuhaender`,
  `geschaeftsstelle` und das Benutzerattribut `mandant` (Regel 1 in `tech.md`). Der Realm hat keine Selbstregistrierung und
  kein «Passwort vergessen» (Spec 01, design.md «Sicherheit»).
- Keycloak läuft auf Stufe A mit 400 MiB und `-Xmx96m` (Spec 01, Plattform und Betrieb). Es gibt zwei Mandanten, je zwei bis
  drei Benutzer.
- Die Archiv-API hat mit der Datenbankrolle `archiv_app` und den S3-Schlüsseln der Anwendung schon Zugriff auf alle Mandanten.
- Rechtematrix und Rollen sind eng gefasst und stehen im Code (ADR-009). Das Zugriffsprotokoll und das Benutzerverzeichnis sind
  nur anfügend (Regel 4).

## Entscheid
1. **Keycloak bleibt die einzige Quelle** für Benutzer, Zugangsdaten, Rollen und `mandant`. Das Archiv speichert keine Passwörter
   und keine Kopie von Personendaten über das Benutzerverzeichnis (Spec 01, 10.10) hinaus.
2. **Das Archiv ruft die Admin-REST-API mit einem eigenen Dienstkonto** auf: Client `archiv-verwaltung` im Realm
   `urinet-archiv`, Client-Credentials, Rollen `query-users`, `view-users`, `manage-users`, nie `realm-admin`, `manage-realm`,
   `manage-clients` oder `manage-identity-providers`, kein Zugang zum Realm `master`. Die Adresse ist intern
   (`http://kc:8080`); der öffentliche Router bleibt bei `/realms/` und `/resources/`. Das Secret liegt als Datei
   `keycloak_verwaltung_secret` (Regel 7). Härtung mit feingranularen Admin-Rechten (Rollenzuweisung nur auf die vier Rollen),
   sobald Aufgabe 19.1 sie für Keycloak 26.8.2 bestätigt.
3. **Die Mandantentrennung erzwingt das Archiv:** Der Mandant stammt nur aus dem Token des Akteurs; jeder Zielbenutzer wird
   vor jeder Aktion auf das Attribut `mandant` geprüft; fremde IDs ergeben 404; das Attribut ändert nach dem Anlegen niemand.
4. **Rollen statt Rechte:** Die Oberfläche weist genau eine der vier Rollen zu (Satz je Mandant). Neue Rechtematrix-Zeilen A20 bis
   A23 (Einstellungen, Benutzer ansehen, Benutzer verwalten, Rollen zuweisen), nur `inhaber`. Eskalationsschutz, Schutz des
   letzten Inhabers, Step-up (frische Anmeldung mit Passwort und TOTP) und Vier-Augen ab zwei Inhabern.
5. **Einladung statt Passwort:** Keycloak sendet den Einmal-Link; die erforderlichen Aktionen sind E-Mail bestätigen, Passwort
   setzen und TOTP einrichten. Das Archiv sieht nie ein Passwort oder einen Link.
6. **Deaktivieren ist der Standard, Löschen nur nach Frist** und Begründung; Protokoll und Benutzerverzeichnis bleiben
   unverändert. Keine Pseudonymisierung in dieser Spec.
7. **Vorgänge mit Wirkung in Keycloak** werden als Absicht (vor dem Aufruf) und Abschluss (danach) protokolliert, mit
   Kompensation bei Teilfehlern, Zeitgrenzen, ohne automatische Wiederholung von Schreibaufrufen und mit Schutzschalter. Ein
   täglicher Abgleich meldet Abweichungen und ändert nichts.
8. **Der Betreiber** richtet den ersten Inhaber eines Mandanten mit dem Admin-Kommando `mandant inhaber-einladen` ein; es gibt
   keine Archiv-Rolle `plattform`. `kcadm.sh` bleibt Notweg.

## Betrachtete Alternativen
- **Keycloak «Organizations» je Mandant:** abgelehnt. Es ersetzt den Claim `mandant` durch `organization` (Umbau von Realm,
  Anmeldung und Tests, Spec 03, 2.3), erlaubt Mitglieder in mehreren Organisationen und braucht voraussichtlich grössere
  Admin-Rechte für das Dienstkonto; es zwingt das Archiv ausserdem nicht von der Prüfung im Code weg. Erneut prüfen ab drei
  Mandanten oder mit mandantenspezifischen Identity Providers.
- **Gruppe je Mandant mit feingranularen Admin-Rechten:** abgelehnt als Trennung (zweite Wahrheit neben dem Attribut, ein
  Dienstkonto je Mandant widerspricht Spec 03, 2.3); FGAP v2 nur als Härtung der Rollenzuweisung.
- **Nur Betreiber (Status quo):** bleibt Notweg und Weg zum ersten Inhaber; als Regelfall zu langsam und fehleranfällig
  (Passwort im Terminal, kein Protokoll im Archiv).
- **Admin-Konsole öffentlich oder Inhaber als Realm-Admin:** abgelehnt (Spec 01: Admin-Konsole nicht öffentlich).
- **Eigener Benutzerspeicher im Archiv:** abgelehnt (ADR-001, eigene Passwort- und MFA-Logik).
- **Einzelne Rechte statt Rollen:** abgelehnt (ADR-009: jede Matrixzeile ein testbares Kriterium).

## Folgen
- **Blast Radius:** Wird die Archiv-API übernommen, kann der Angreifer mit dem Dienstkonto Benutzer in Keycloak anlegen, sperren
  und Zugangsdaten zurücksetzen lassen (der Archiv-Code tut Letzteres nie, `manage-users` erlaubt es technisch). Die Daten
  der Mandanten sind in diesem Fall schon über die Datenbank lesbar; neu ist die Persistenz im Realm. Begrenzung: Dienstkonto
  ohne Realm-Admin, Alarm bei jeder Vergabe von `inhaber`, täglicher Abgleich, Admin-Ereignisse als zweite Spur, Raten.
  Details: design.md, «Blast Radius».
- **Speicher auf Stufe A:** der Heap der API wächst um höchstens 4 MiB (JDK-HTTP-Client, keine Keycloak-Bibliothek); die
  Wegwerf-Container für `kcadm.sh` entfallen für Benutzer. Keycloak wird nur durch einzelne Admin-Aufrufe belastet.
- **Neue Betriebsaufgaben [Fabian]:** Dienstkonto und Secret, SMTP im Realm, Passwort-Richtlinie, Ereignisse, Rotation des
  Secrets in den Notfallunterlagen.
- Eine Abhängigkeit der Verwaltung von der Erreichbarkeit von Keycloak; Anmeldung, Dokumente und Einstellungen laufen ohne sie
  weiter (Requirement 5.8).
- Spätere Optionen: `private_key_jwt` statt Client-Secret, Organizations ab drei Mandanten, Empfänger der Alarme als
  Verweis auf Benutzer.

## Verweise
- `.kiro/specs/06-verwaltung-einstellungen-benutzer/` (Requirements 3 bis 7, design.md «Architektur und Entscheid»,
  «Bedrohungsmodell»)
- Spec 01, 2.1, 2.2, 2.6, 10.9, 10.10; design.md «Sicherheit», «Secrets und Konfiguration»
- Spec 03, 2.3 (gemeinsame Clients), 3.5 (Rechte der Geschäftsstelle)
- Spec 05 (Konfiguration je Mandant), ADR-001, ADR-009, ADR-010
