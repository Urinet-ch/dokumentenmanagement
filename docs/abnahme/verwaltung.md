# Abnahme Verwaltung (Spec 06, Aufgabe 21.6) – Zwischenstand
Stand 26.11.2026. **Noch nicht abgenommen:** Die Speichermessung auf Stufe A ist offen.
Nur Nummern, Status und Zeiten; keine Namen, Adressen, Geheimnisse oder UUIDs.

## Manuelle Prüfungen (Stufe A, Fabian, 26.11.2026)
| Nr. | Prüfung | Ergebnis |
| --- | --- | --- |
| 1 | Einladung mit echtem Postfach, TOTP-Einrichtung | bestanden |
| 2 | Rolle ändern | bestanden |
| 3 | Step-up (Passwort und TOTP, höchstens 5 Minuten alt) | bestanden |
| 4 | Sperren beendet die Sitzung nach höchstens 5 Sekunden | bestanden |
| 5 | Löschen nach Frist | bestanden; Positivfall nach Fristablauf nicht einzeln bestätigt |
| 6 | Lösen der Google-Verknüpfung (WO O.8) | bestanden |
| 7 | 375 px am Handy (Req 8.2) | bestanden |
| 8 | Rotationsprobe des Dienstkonto-Geheimnisses | bestanden |
| 9 | Messung des Speichers auf Stufe A | **offen** |

## Zugehörige Aufgaben
- 21.2 Benutzer-Migration Stufe A: bestanden. Der Abgleich meldete nur `ROLLE_OHNE_VORGANG` für den bestehenden Inhaber;
  nach dem Betreibervorgang `inhaber-uebernehmen` ohne Abweichung; Anmeldung und Benutzerliste korrekt.
- 19.3 Dienstkonto, SMTP, Secrets, Deploy: bestanden. Das Salz der Namenssperre ist ausserhalb von GitHub gesichert;
  Realm aktualisiert, Nachweise zu Issuer und Ablaufzeit der Secret-Überlappung geführt.
- 20.6 Rechte-Übersicht (Req 8.7): Inhalt bestanden; die Darstellung als Tabelle ist beauftragt.

## Befund
- Löschen eines noch aktiven Benutzers zeigt «Die Aktion ist im aktuellen Zustand nicht möglich. Bitte laden Sie die
  Angaben neu.» Das ist das erwartete `STATUS_KONFLIKT` (nur deaktivierte Benutzer nach Löschfrist mit genau einer Rolle),
  der Text nennt den Grund nicht. Verbesserungsvorschlag, kein Abnahmehindernis.

## Abnahmeprotokoll
- Datum: 26.11.2026
- Ergebnis: offen (Punkt 9)
- Prüfnummern mit Abweichung und gegebenenfalls Fehler-ID: keine
- CI-Lauf-ID und Ergebnis des endgültigen Stands: nachtragen
