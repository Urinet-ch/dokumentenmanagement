# Benutzer-Migration: synthetischer Plan

Dieser Trockenlauf dient nur der Vorbereitung von Aufgabe 21.2. Er liest ausschliesslich eine lokale Datei mit
erfundenen Kennungen und gibt einen Plan auf stdout aus. Er verbindet sich mit keinem Dienst und ändert weder
Benutzer noch Rollen oder Mandanten. Der Plan ist kein Abgleich mit Keycloak und keine Betriebsfreigabe.

## Eingabe und Aufruf

Eine JSON-Datei **ausserhalb des Repositorys** mit ausschliesslich erfundenen Daten erstellen:

```json
[
  {
    "kennung": "synthetisch-eins",
    "mandanten": ["test"],
    "rollen": ["inhaber"],
    "vorhanden": true,
    "totp": true,
    "passkey": false
  }
]
```

`kennung` muss mit `synthetisch-` beginnen; Namen, E-Mail-Adressen, Passwörter und weitere Felder sind nicht
zulässig. `mandanten` und `rollen` sind Listen; `vorhanden`, `totp` und `passkey` sind `true`, `false` oder `null`
(unbekannt). Die vier Archiv-Rollen sind `inhaber`, `mitarbeiter`, `treuhaender` und `geschaeftsstelle`.

```sh
python3 tools/benutzer_migrationsplan.py /pfad/zur/synthetischen-liste.json
python3 -m unittest tools/test_benutzer_migrationsplan.py
```

Der Plan unterscheidet bestehenden Zugang abgleichen, Anlage prüfen und ungeklärten Zielzustand. Mandanten,
Rollen und Hinweise zu TOTP und optionalem Passkey werden ausgegeben; doppelte Kennungen, fehlender oder
mehrdeutiger Mandant, fehlende, mehrdeutige oder unbekannte Rolle und unbekanntes Vorhandensein sind Konflikte.
Bei Konflikten darf keine Übernahme aus dem Eintrag abgeleitet werden. Exit 0 bedeutet konfliktfrei, 1 bedeutet
Konflikte, 2 ungültige Eingabe oder Lesefehler. Die Angaben sind Selbstauskunft, keine Prüfung der tatsächlich
vergebbaren Rollen, bestehenden Zugänge, aktiven Inhaber oder Sicherheitsfaktoren.

## Betrieb und Rückfall

Ausführung und Abnahme bleiben bei Fabian. Bestehende Benutzer werden nach Spec 06, Requirement 9.4 übernommen,
nicht aufgrund dieses Plans neu angelegt. Vorgehen, geschützter Abgleich und der Betreibervorgang für bestehende
Inhaber stehen im [Migrationsentwurf](../benutzer-migration-entwurf.md) und in der
[Verwaltungsanleitung](../anleitung-verwaltung.md). Bei Unklarheit stoppen und den tatsächlichen Zustand geschützt
prüfen. Die Übernahme schreibt append-only Verzeichniszeilen; sie nicht löschen. Eine Datenbank-Wiederherstellung
ist ein gesonderter Betriebsentscheid mit Risiko für spätere Änderungen, kein automatischer Rückfall. 21.2 und 21.6
bleiben bis zum Betriebsnachweis offen.
