# Wechselprotokoll Stufe B – Vorlage

Keine Betriebswerte, Zugangsdaten, personenbezogenen Angaben oder Dokumentinhalte eintragen. Das ausgefüllte
Protokoll wird als Betriebsnachweis verwahrt; die Vorlage ist kein Nachweis. Nur IDs, Zähler und Verweise auf
freigegebene Nachweise verwenden.

| Feld | Ergebnis |
| --- | --- |
| Datum / Wartungsfenster | [offen] |
| Verantwortlich Fabian / Infra / QA | [offen] |
| Freigegebener Deploy-Stand und CI-Nachweis | [offen] |
| Probelauf Spec 09, 29.10 / letzter Upload Stufe A | [offen] |
| Art der ausschliesslich synthetischen oder anonymisierten Testdaten auf Stufe A | [offen] |
| Nachweise 31.2–31.6, 31.10 und 1.10 | [offen] |
| Verfehltes Leistungsziel / Entscheid Fabian, falls nötig | [offen] |

## Bereinigung und Prüfung

| Prüfung | Ergebnis / Nachweis |
| --- | --- |
| Inventar aller Mandanten und Stufe-A-Buckets vollständig, einschliesslich nicht zuordenbarer Einträge | [offen] |
| Frist ab letztem Upload eingehalten; gesperrte Versionen abgelaufen | [offen] |
| Stufe-A-Buckets: Versionen und Löschmarken ohne Bypass entfernt, Buckets gelöscht; Nachprüfung | [offen] |
| Archiv- und Keycloak-Datenbank leer neu, Weg aus 31.2 | [offen] |
| Testschlüsselpaar des Sicherungsfunktionstests verworfen | [offen] |
| Zugangsdaten, Daten- und Manifest-Schlüssel bestehen unverändert | [offen] |
| Je Mandant: Dokumente, Test-API-Tokens und Test-Webseiten-Schlüssel nicht mehr vorhanden | [offen] |
| Realmweit: Testbenutzer und Testrollen nicht mehr vorhanden, auch ohne gültigen Mandanten | [offen] |
| Prüfverfahren, Zeitpunkt, Zähler und Nachweis der Vollständigkeit | [offen] |

## Produktiver Aufbau und Entscheid

| Prüfung | Ergebnis / Nachweis |
| --- | --- |
| Produktiver Bucket: leer, Versionierung, Object Lock und Deny-Policy | [offen] |
| Deploy und Stufe-B-Werte, Health und produktive Sperren | [offen] |
| Realm mit TOTP, Mandant, Anmeldung und Rollen | [offen] |
| Netze, Ports und Geheimniszuordnung nach 16.28–16.29 | [offen] |
| Sicherung, Zweitkopie, Restore-Probe und Überwachung | [offen] |
| Zeitstempeldienste angebunden; erstes produktives Manifest nachgewiesen | [offen] |
| Abweichungen, Abbruch, Rückweg und Verantwortliche | [offen] |
| Go/No-go 31.8, Datum und Nachweis der Checkliste | [offen] |

Bis zum dokumentierten Go keine echten Belege. Aufgabe 31.7 bleibt bis zum tatsächlichen Wechsel offen.
