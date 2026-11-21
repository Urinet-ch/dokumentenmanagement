# Anleitung für Inhaber: Einstellungen, Benutzer und Rollen

> **Entwurf, nicht umgesetzt.** Beschreibt, wie die Verwaltung nach Spec 06 (`.kiro/specs/06-verwaltung-einstellungen-benutzer/`)
> funktionieren soll. Die Seiten gibt es erst nach Welle 20; bis dahin gilt der Weg im README («Benutzer in Keycloak anlegen»).
> Wird mit Aufgabe 21.5 zur gültigen Anleitung.
>
> **Bedienung in der App:** Die Seiten, die es heute schon gibt («Dokumentarten», «Zugriffsprotokoll», «API-Tokens»),
> beschreibt die Anleitung in der App unter «Hilfe», Abschnitt «Verwaltung» (`/hilfe#verwaltung`). Nach Spec 07,
> Requirement 7.7 kommt mit Aufgabe 21.5 auch die Bedienung von Benutzern und Einstellungen dorthin; diese Datei behält
> dann nur, was nicht in die App gehört (Wege des Betreibers, Notweg nach Spec 06, Requirement 9.3, Kontaktstellen).

Die Verwaltung finden Sie nur als **Inhaber** unter «Verwaltung». Alle Änderungen gelten nur für Ihren Mandanten und stehen im
Zugriffsprotokoll (`/verwaltung/protokoll`), ohne E-Mail-Adressen und Namen.

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
Weiterhin gilt für Notfälle der Betreiber (Fabian) mit dem Admin-Kommando.
