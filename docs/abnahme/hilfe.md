# Abnahme Einführung und Hilfe – Vorbereitung

Stand 24.11.2026. **Nicht abgenommen:** Aufgabe 23.6 und alle manuellen Prüfungen sind offen.
Nur synthetische Testdaten verwenden; keine Inhalte aus Dokumenten, keine Bildschirmfotos mit echten Daten und keine
Personendaten in diesem Protokoll festhalten. Ergebnisse mit Nummern, Status, Zeiten und Fehler-IDs dokumentieren.

## Automatische Nachweise

Der lokale Browserlauf und die gezielten Hilfe-Tests beziehen sich auf den Stand von PR #549,
Commit `e02ff44774a49b060420bf9a3b0c3619495e2269`, mit `main` bis `79b89291e01384d84b7347c5d06afebc44243a59`.
Die PRs #549 und #550 sind noch nicht beide auf `main`; vor der Abnahme auf dem endgültigen Stand wiederholen.

Die Szenariozahlen zählen ausgeführte Beispiele je `@req`-Tag; ein Szenario mit mehreren Tags wird mehrfach gezählt.
Die Testzahlen sind die Anzahl aller bestandenen Fälle in den zugeordneten Testdateien, nicht die Zahl einzelner
Kriterienprüfungen; Dateien mit mehreren Requirements werden mehrfach gezählt. Null bedeutet keinen zugeordneten
Unit-Test in dieser Tabelle, nicht zwingend das Fehlen anderer Tests.

| Requirement | Szenarien mit Tag | Zugeordnete Unit-Tests | Ergebnis |
| --- | ---: | ---: | --- |
| 1 | 11 | 23 | bestanden |
| 2 | 10 | 4 | bestanden; 2.4 zusätzlich in #550 geprüft |
| 3 | 15 | 27 | bestanden |
| 4 | 20 | 13 | bestanden |
| 5 | 15 | 13 | bestanden |
| 6 | 32 | 18 | bestanden; Dokumentenprüfung ausstehend |
| 7 | 10 | 170 | bestanden; Dokumentenprüfung ausstehend |
| 8 | 3 | 0 | bestanden |
| 9 | 42 | 122 | bestanden; 9.1 manuell offen |
| 10 | 16 | 20 | bestanden; 10.3 manuell offen |

Zuordnung Unit-Tests: 1 `einfuehrung-vermerk` (23); 2 `einfuehrung-schritte` (4); 3 `einfuehrung-vermerk` (23)
und `hilfe-leiste` (4); 4 `hilfe-kontext` (9) und `hilfe-leiste` (4); 5 `hilfe-seite` (13);
6 `hilfe-sichtbar` (12) und `anleitung-abschnitte` (6); 7 `hilfe-katalog` (18), `anleitung-markdown` (23),
`anleitung-text` (7) und `anleitung-inhalt` (122); 9 `anleitung-inhalt` (122); 10 `ReqAbdeckungTest` (17) und
`SzenarienShardTest` (3). Die Vitest-Dateinamen enden auf `.spec.ts`.

- Spec-07-Browserlauf: **101 von 101 Szenarien, 1'378 von 1'378 Schritten bestanden**, lokaler Produktionsbuild mit
  SPA-Testserver und Attrappen. Darin enthalten: axe und Datenschutz bei vier Rollen sowie abgemeldet, bei 375 und
  1280 px in hell und dunkel; Offline- und Versionswechsel-Szenarien.
- Gezielte Hilfe-Vitests: **241 von 241 bestanden**; alle Frontend-Tests auf #549: **1'178 von 1'178 bestanden**.
- `ReqAbdeckungTest` und `SzenarienShardTest`: **20 von 20 bestanden**. Die Abdeckungsprüfung selbst erfasst nur
  abgehakte Aufgaben; zusätzlich wurden alle 57 Kriterien mit Tags und Nachweisvermerken abgeglichen. Der
  Tag `@req-2.4` prüft allein Branding; zusätzliche Unit-Tests in #550 vergleichen die Texte mit Testattrappen.
- Build und Gestaltungsprüfung: **bestanden**; bestehende Warnungen zu den Grössenbudgets bleiben bestehen.
- CI-Lauf **45343911872**, Commit `8cea5766633b1aeeb6ff2a1c78d26982330d6d41`: voller Lauf einschliesslich
  Browser und Frontend bestanden; **historischer Stand**, kein Nachweis für die oben genannten Änderungen.
  CI-Lauf **45362079477**, Commit `79b89291e01384d84b7347c5d06afebc44243a59`: beim Erstellen noch laufend.
  CI des endgültigen Stands: **offen**, Lauf-ID und Ergebnis nachtragen.

Kriterien mit ausschliesslich dokumentarischem oder manuellem Nachweis bleiben entsprechend der Spec gesondert zu
prüfen; insbesondere sind 9.1 und 10.3 nicht durch die obigen Tests abgenommen.

## Manuelle Checkliste – rund 60 Minuten

Nur Stufe A und neue Testbenutzer mit synthetischen Daten verwenden. Für jede Prüfung bestanden, nicht bestanden oder
offen vermerken; keine Zugangsdaten, Namen, Beleginhalte oder Bildschirmfotos mit echten Daten in dieses Protokoll.

### 1. Einführung je Rolle – 15 Minuten

Für jede Rolle mit einem neuen Testbenutzer anmelden und die Startseite öffnen. Prüfen: Einführung erscheint; nur
zulässige Schritte und Bereiche; «Weiter», «Zurück», Hilfe zum Schritt, «Fertig» oder «Überspringen» und anschliessend
«Einführung erneut zeigen» funktionieren. Beim erneuten Öffnen beginnt die Einführung bei Schritt 1.

- [ ] 1.1 Inhaber
- [ ] 1.2 Treuhänder
- [ ] 1.3 Mitarbeiter
- [ ] 1.4 Geschäftsstelle

### 2. Handy und Offline – 10 Minuten

- [ ] 2.1 iOS: Einführung und Hilfe bei 375 CSS-Pixel Breite bedienen; kein waagrechter Seitenbildlauf.
- [ ] 2.2 Android: dieselbe Prüfung bei 375 CSS-Pixel Breite.
- [ ] 2.3 Auf beiden Geräten die App zuerst online laden und die Anleitung öffnen. Flugmodus aktivieren und WLAN
  ausschalten; die installierte App erneut öffnen. Hilfe-Leiste und Anleitung öffnen und sichtbare Abschnitte lesen.
  Danach Flugmodus ausschalten. Falls 375 CSS-Pixel auf einem Gerät nicht erreichbar sind, Ergebnis offen lassen und
  die tatsächlich geprüfte Breite als Zahl notieren.

### 3. Screenreader – 10 Minuten

- [ ] 3.1 iOS: VoiceOver in den Bedienungshilfen einschalten; Startseite öffnen. Mit einem Finger nach rechts wischen,
  bis Einführung und Fortschritt vorgelesen werden. «Weiter» mit Doppeltipp auslösen; prüfen, dass die neue
  Schrittüberschrift angesagt wird. «Hilfe» wählen, mit Wischen den Dialog und «Schliessen» erreichen, doppeltippen und
  prüfen, dass der Fokus zum Auslöser zurückkehrt. VoiceOver anschliessend wieder ausschalten.
- [ ] 3.2 Android: TalkBack in den Bedienungshilfen einschalten; dieselben Schritte mit Rechtswischen und Doppeltipp
  ausführen. Überschrift, Fortschritt, Hilfe-Dialog und Fokusrückgabe prüfen; TalkBack anschliessend ausschalten.

### 4. Bildschirmfotos – 5 Minuten

- [ ] 4.1 Einführung, Hilfe-Leiste und Anleitung in hellem und dunklem Schema bei 375 und 1280 CSS-Pixeln ansehen.
  Bildschirmfotos ausschliesslich mit synthetischen Daten erstellen und im Abnahmenachweis verlinken; keine Inhalte oder
  Personendaten hier eintragen.

### 5. Lesetest – 15 Minuten

Eine Person ohne Archivkenntnisse bearbeitet die Aufgaben selbständig mit der Anleitung. Maximal 3 Minuten je Aufgabe,
danach 3 Minuten für die Bewertung. Keine Antworten oder Inhalte protokollieren.

1. Wie wird die Einführung erneut geöffnet?
2. Wo findet man Hilfe zur aktuellen Seite und die ganze Anleitung?
3. Wie wird eine Aufnahme ohne Netz später hochgeladen?
4. Was ist bei einer fehlenden Rolle oder einer fehlgeschlagenen Aktion zu tun?

Bewertung je Nummer: **2** selbständig gelöst, **1** mit Hinweis gelöst, **0** nicht gelöst; Zeit in Minuten:

| Nr. | Punkte 0–2 | Minuten |
| --- | --- | --- |
| 1 | ___ | ___ |
| 2 | ___ | ___ |
| 3 | ___ | ___ |
| 4 | ___ | ___ |

### 6. Offener Entscheid 2.4 und Abschluss – 5 Minuten

**Frage:** Soll für den zweiten Mandanten in Einführung oder Anleitung erklärt werden, welche bestätigten Dokumente
wann auf der Webseite erscheinen?

- [ ] 6.1 Option A: kein Zusatz; Texte bleiben für alle Mandanten gleich.
- [ ] 6.2 Option B: Zusatz nach abgestimmtem Wortlaut über die Mandantenkonfiguration; keine Abfrage der
  Mandantenkennung im Code.
- [ ] 6.3 Entscheid bleibt offen.

**Empfehlung:** Bis zum abgestimmten Wortlaut Option A beibehalten; noch keinen Entscheid vorwegnehmen.
Weitere `[offen: …]`-Stellen in Spec 07 nennen nur diesen Punkt oder den allgemeinen Umgang mit offenen Punkten.

## Abnahmeprotokoll

- Datum: ___
- Ergebnis: offen / bestanden / nicht bestanden
- Prüfnummern mit Abweichung und gegebenenfalls Fehler-ID: ___
- Bemerkung ohne Inhalte oder Personendaten: ___
- CI-Lauf-ID und Ergebnis des endgültigen Stands: ___
