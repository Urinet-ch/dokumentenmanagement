# Anleitung für Inhaber: Einstellungen, Benutzer und Rollen

> **Stand:** Die Abschnitte «Für den Betreiber» (Admin-Kommando, Notweg, Alarme, Geheimnisse) beschreiben den Code auf `main`.
> Die Bedienung von Benutzern und Einstellungen durch den Inhaber beschreibt die Spec 06 (`.kiro/specs/06-verwaltung-einstellungen-benutzer/`);
> die Aktionen und Dialoge der Benutzerseiten (Aufgabe 20.7, PR #531) sind noch nicht auf `main`. **Bis zu deren Merge gelten die
> Abschnitte zu Einladen, Rollen, Sperren und Löschen als Sollbeschreibung**, nicht als Bedienungsanleitung der ausgelieferten Seiten;
> für Benutzer gilt bis dahin der Weg «Für den Betreiber».
>
> **Bedienung in der App:** Die Seiten «Dokumentarten», «Zugriffsprotokoll» und «API-Tokens» beschreibt die Anleitung in der App
> unter «Hilfe», Abschnitt «Verwaltung» (`/hilfe#verwaltung`). Nach Spec 07, Requirement 7.7 gehört die Bedienung von Benutzern
> und Einstellungen ebenfalls dorthin; diese Datei behält, was nicht in die App gehört (Wege des Betreibers, Notweg nach Spec 06,
> Requirement 9.3). **[offen:** Übernahme in die Hilfe der App und die Kontaktstellen sind nicht Teil dieser Aufgabe.**]**
> Die Aufgabe 21.5 ist nicht abgehakt: Nachweise des Betreibers fehlen (siehe [Geheimnisse rotieren](geheimnisse-rotieren.md)).

Die Verwaltung finden Sie nur als **Inhaber** unter «Verwaltung». Alle Änderungen gelten nur für Ihren Mandanten und stehen im
Zugriffsprotokoll (`/verwaltung/protokoll`), ohne E-Mail-Adressen und Namen.

## Normaler Weg für Benutzer
Benutzer und Rollen pflegt der Inhaber in der Verwaltungsoberfläche. Den ersten Inhaber eines neuen Mandanten lädt der
Betreiber mit `archiv mandant inhaber-einladen` ein; Keycloak verschickt den einmaligen Link, ohne dass Passwort oder Link
im Terminal erscheinen. Der Betreiber kann mit `archiv benutzer abgleichen <code> [--pruefen]` bestehende Zugänge prüfen
und sie bei Bedarf in das Benutzerverzeichnis übernehmen, ohne Keycloak zu ändern. Beim Mandantenabbau deaktiviert
`archiv mandant benutzer-sperren <code>` die Zugänge und beendet deren Sitzungen; nach 30 Tagen löscht ein erneuter Aufruf
die Keycloak-Benutzer.

## Benutzer einladen
1. «Benutzer» → «Person einladen». Benutzername (nur `a–z`, `0–9`, `.`, `_`, `-`), E-Mail, Vorname, Nachname und **Rolle** wählen.
2. Sie bestätigen mit Passwort und TOTP (frische Anmeldung, höchstens 5 Minuten alt).
3. Die Person erhält eine E-Mail von «UriNet Archiv» mit einem Link (gilt 24 Stunden, einmal). Darüber bestätigt sie die
   E-Mail-Adresse, setzt ein Passwort und richtet TOTP ein. **Sie sehen weder Passwort noch Link.**
4. Der Status zeigt «Einladung offen seit …». Ist der Link abgelaufen oder verloren: «Einladung erneut senden» (höchstens 3 Mal
   in 24 Stunden).

## Rollen und was sie dürfen
Sie vergeben **Rollen**, keine einzelnen Rechte. Unter «Rechte» sehen Sie je Person, was sie darf («Dokumente lesen»,
«erfassen», «bestätigen», «verwalten»); die Übersicht stammt direkt aus der Rechtematrix.
- `inhaber`: alles im Mandanten. `mitarbeiter`: erfassen, nur `NORMAL` und eigene `PERSONAL`-Dokumente lesen.
  `treuhaender`: nur lesen. `geschaeftsstelle`: nur im Mandanten `vhf-gsk`.
- Sie ändern nie Ihre **eigene** Rolle, und es muss immer **mindestens ein aktiver Inhaber** bleiben. Bei zwei Inhabern bestätigt der
  zweite die Vergabe der Rolle `inhaber`.
- Eine Rollenänderung gilt spätestens nach 5 Minuten; die Person meldet sich bei Bedarf neu an.

## Sperren, Austritt, Zugang zurücksetzen
- **Sperren** (vorübergehend, z. B. verlorenes Gerät): sofort, die laufenden Sitzungen enden. «Entsperren» hebt es auf.
- **Deaktivieren** (Person scheidet aus): wie Sperren, aber als Austritt; ihre Dokumente und Einträge bleiben. «Reaktivieren» lädt
  die Person neu ein.
- **Zugang zurücksetzen** (Passwort oder TOTP-Gerät verloren): Die Person erhält einen neuen Link und richtet Passwort und TOTP
  neu ein. Ein «Passwort vergessen» gibt es nicht.
- **E-Mail-Adresse ändern:** nur durch Sie; die neue Adresse muss bestätigt werden, die alte erhält eine Mitteilung.
- **Löschen:** erst 30 Tage nach dem Deaktivieren, mit Begründung. Es entfernt E-Mail, Namen und Zugangsdaten aus dem Anmeldesystem;
  Protokoll und Zuordnung der Kennung bleiben (Aufbewahrungspflicht). Vorschau und Eingabe des Benutzernamens vorher.
- API-Tokens und Webseiten-Schlüssel bleiben gültig; prüfen Sie «API-Tokens» beim Austritt.

## Einstellungen ändern
«Einstellungen» zeigt Gruppen mit **Grenzen**, **Standardwert** und «wirksam ab». Mit «Vorschau» sehen Sie vor dem Speichern, was
geschieht (z. B. «N Fragen werden gelöscht»). Riskante Änderungen (kürzere Aufbewahrung, Geschäftsjahr-Ende, hohes Budget)
verlangen eine Bestätigung. «Auf Standard» setzt zurück; der Verlauf zeigt jede Version mit Vergleich und lässt `einstellungen` auf
einen früheren Stand zurücksetzen. Ändert jemand gleichzeitig, meldet die App «Die Daten haben sich geändert».
Nicht hier änderbar: Alarm-Empfänger, Budget des öffentlichen Chats, Modelle, Limits und Geheimnisse (Betreiber).

## Wenn etwas nicht geht
| Meldung | Bedeutung |
| --- | --- |
| «Bitte erneut anmelden» | Die letzte Anmeldung ist zu alt; nach Passwort und TOTP läuft die Aktion einmal erneut. |
| «Letzter Inhaber» | Es muss ein aktiver Inhaber bleiben. |
| «Zu viele Anfragen» | Grenze erreicht (z. B. 10 Einladungen je Stunde); später wieder. |
| «Anmeldedienst nicht erreichbar» | Keycloak antwortet nicht; Dokumente und Einstellungen funktionieren weiter. Später erneut versuchen. |

## Für den Betreiber

### Normaler Weg: Admin-Kommando
Die Kommandos laufen im Wegwerf-Container wie im README unter «Admin-Kommandos auf der VPS» beschrieben, nie in einem laufenden
Dienst. Jeder Aufruf steht im Protokoll als `betreiber:<name>` (`--wer`). Aufruf und Platzhalter stehen im README unter
[«Benutzerverwaltung: normaler Weg»](../README.md#benutzerverwaltung-normaler-weg); hier die Bedeutung:

| Kommando | Wirkung |
| --- | --- |
| `mandant inhaber-einladen <code> --benutzer <name> --email <adresse> --vorname <v> --nachname <n> --wer <betreiber>` | Legt den ersten Inhaber des Mandanten über das Dienstkonto an; Keycloak versendet den Einmal-Link. Passwort und Link erscheinen nie im Terminal. `mandant anlegen` legt keine Benutzer an. |
| `benutzer abgleichen <code> [--pruefen] [--json]` | Liest die Benutzer des Mandanten in Keycloak und gibt Abweichungen aus. Ohne `--pruefen` schreibt es die fehlenden Zeilen ins Benutzerverzeichnis (Übernahme); mit `--pruefen` ändert es nichts. Keycloak ändert es nie. |
| `mandant benutzer-sperren <code> [--json]` | Mandantenabbau: deaktiviert alle Keycloak-Benutzer des Mandanten und beendet ihre Sitzungen; ab 30 Tagen nach der Deaktivierung löscht ein erneuter Aufruf die Keycloak-Benutzer. Zugriffsprotokoll und Benutzerverzeichnis bleiben bis zum Mandantenabbau. |

Exit-Codes von `benutzer abgleichen`: 0 keine Abweichung, 1 mindestens eine Abweichung, 2 falscher Aufruf, 3 technischer Fehler.
Abweichungscodes: `MEHRERE_ROLLEN` (keine oder mehrere Archiv-Rollen), `KEIN_MANDANT`, `MEHRERE_MANDANTEN` (Attribut `mandant`
fehlt oder ist mehrwertig), `FREMDE_ROLLE` (Rolle ausserhalb der vier), `BENUTZER_FEHLT`, `ROLLE_OHNE_VORGANG`, `VORGANG_OFFEN`.
**[offen:** Die genaue Bedeutung von `ROLLE_OHNE_VORGANG`, `BENUTZER_FEHLT` und `VORGANG_OFFEN` und das Vorgehen je Code
legt die Spec nicht fest; im Verwaltungsprotokoll und in Keycloak prüfen.**]** Die Migration der heutigen Benutzer
(`archiv benutzer abgleichen urinet --pruefen`, danach ohne `--pruefen`) ist Aufgabe 21.2 und liegt beim Betreiber; ihr Ergebnis
steht in `docs/abnahme/verwaltung.md`.

### Notweg: Benutzer per `kcadm.sh`
Nur wenn das Dienstkonto oder der Mailversand von Keycloak nicht bereit ist (zum Beispiel bei einem Ausfall von Keycloak oder
einem fehlenden Dienstkonto-Secret), legt der Betreiber Benutzer und Rollen mit `kcadm.sh` an: README,
[«Notfallweg: Benutzer in Keycloak per kcadm.sh anlegen»](../README.md#notfallweg-benutzer-in-keycloak-per-kcadmsh-anlegen).
Das ist nicht der normale Weg; Benutzer, die so entstehen, nimmt `benutzer abgleichen` danach ins Benutzerverzeichnis auf.
Bei «Anmeldedienst nicht erreichbar» in der Verwaltung funktionieren Dokumente und Einstellungen weiter; die Verwaltung der
Benutzer wartet, bis Keycloak wieder antwortet.

### Alarme der Benutzerverwaltung
Die Mails «Alarm der Benutzerverwaltung» und «Abweichung in der Benutzerverwaltung» und ihre Schwellen stehen in
[alarme.md](alarme.md). Die Mail nennt nur Aktion, Anzahl, Zeit und IDs.

### Geheimnisse
`NAMENSSPERRE_SALZ`, `KEYCLOAK_VERWALTUNG_SECRET`, `KEYCLOAK_SMTP_PASSWORT` und die übrigen Geheimnisse wechselt nur der Betreiber,
nach [Geheimnisse rotieren](geheimnisse-rotieren.md). **Datenschlüssel und Salz der Namenssperre nie ungeplant wechseln.**
