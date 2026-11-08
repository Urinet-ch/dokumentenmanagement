# ADR-007: Unveränderbarkeit über Object Lock GOVERNANCE, Schlüssel ohne Bypass und zeitgestempeltes Manifest

**Status:** Vorgeschlagen – Freigabe per Merge an Gate 0 (Aufgabe 0.6). Vorbehalt: Spike 0.2 (Schlüssel ohne `s3:BypassGovernanceRetention`, Verfügbarkeit von COMPLIANCE).

**Datum:** 08.11.2026

## Kontext
- Gescannte Belege gelten nur als Buchungsbelege, wenn sie unveränderbar abgelegt sind und Integrität und Speicherzeitpunkt nachweisbar sind (Art. 958f OR, GeBüV, Art. 9 GeBüV; Req 6, 15).
- S3 Object Lock kennt zwei Modi: **COMPLIANCE** lässt Verkürzen und Löschen bis zum Ablauf durch niemanden zu. **GOVERNANCE** erlaubt beides Inhabern des Rechts `s3:BypassGovernanceRetention`, verhindert es aber für alle anderen.
- Fehlarchivierungen (falsche Art, falsche Frist, falsches Dokument) und Löschbegehren nach revDSG müssen in Ausnahmefällen lösbar bleiben. Unter COMPLIANCE wäre das bis zum Ablauf der Frist unmöglich (P2).
- GOVERNANCE schützt nur, wenn die Anwendung selbst das Bypass-Recht nicht hat. Ein kompromittierter Server darf archivierte Dateien weder löschen noch die Sperre verkürzen können.
- Eine Sperre verhindert Änderungen, belegt sie aber nicht gegenüber Dritten. Dafür braucht es einen unabhängigen Nachweis von Inhalt und Zeitpunkt; ein Zeitstempel lässt sich nicht rückwirkend nachholen (Req 15.3).
- Ob Infomaniak einen Schlüssel ohne Bypass-Recht ausstellen kann und COMPLIANCE anbietet, ist offen und Gegenstand des Spikes Object Storage (Aufgabe 0.2, Req 0.4).

## Entscheid
1. **Modus GOVERNANCE** für alle Sperren: Dateien unter `archiv/` bei Arten mit `gesetzliche_pflicht` (wann gesperrt wird und bis wann: ADR-004) und Manifeste unter `manifest/`.
2. **App-Schlüssel ohne `s3:BypassGovernanceRetention`:** Die Anwendung erhält nur diesen Schlüssel (Secret-Datei `s3_app_zugang`). Mit ihm lassen sich Sperren setzen und verlängern, aber nicht verkürzen, und gesperrte Versionen nicht löschen (Req 6.5).
3. **Sperren nur verlängern:** Ergibt eine Metadatenänderung ein früheres `aufbewahren_bis`, bleibt die Sperre unverändert, und der Konflikt wird mit Aktion `SPERRE_KONFLIKT` protokolliert (Req 9.7, tech.md Regel 2).
4. **Ein einziger Bypass-Schlüssel**, offline beim Inhaber in den Notfallunterlagen, nie auf dem Server und nie in GitHub-Secrets (Req 6.10, 16.12).
5. **Tägliches Manifest** je Mandant: Liste der archivierten Dateien mit SHA-256, Hash-Kette über `vorher_sha256`, Signatur Ed25519 und **RFC-3161-Zeitstempel eines Schweizer Anbieters als Pflicht** (Req 15.1–15.3). Ohne Zeitstempel gilt das Manifest als fehlend und löst einen Alarm aus (Req 15.8). Im Profil `prod` startet die API nicht ohne `archiv.tsa.url`; produktiv archiviert wird erst mit angebundenem Zeitstempeldienst. Anbieter: [offen: Schweizer TSA-Anbieter – Entscheid vor Welle 5].
6. **Sperre der Manifeste** bis zum spätesten `aufbewahren_bis` der enthaltenen Dateien, ohne Datei mit Frist 10 Jahre (Req 15.4). Der monatliche Prüflauf verlängert Sperren unbefristeter Dokumente und von Manifesten mit weniger als 12 Monaten Restlaufzeit um 10 Jahre (Req 15.9).
7. **Rückfall:** Kann Infomaniak keinen Schlüssel ohne Bypass-Recht ausstellen, sperrt das Archiv die Manifeste unter `manifest/` im Modus **COMPLIANCE** (Req 6.11), umschaltbar per Konfiguration (Aufgabe 2.3). Das gilt mindestens für `manifest/` (P2). Ob weitere Präfixe auf COMPLIANCE wechseln, entscheidet das Ergebnis von Spike 0.2. Req 6.11 verlangt nur `manifest/`.

## Betrachtete Alternativen
- **COMPLIANCE für alle Objekte:** abgelehnt (P2). Fehlarchivierungen und Löschbegehren nach revDSG wären bis zum Ablauf der Frist nicht lösbar, eine falsch zu lang gesetzte Frist nie korrigierbar.
- **Kein Object Lock, nur Zugriffsrechte und Manifest:** abgelehnt. Das Manifest deckt eine Änderung nur auf, verhindert sie aber nicht. Ein kompromittierter App-Schlüssel könnte Dateien löschen.
- **Bypass-Recht beim App-Schlüssel oder Bypass-Schlüssel auf dem Server:** abgelehnt. GOVERNANCE wäre dann bei einem Einbruch wirkungslos.
- **Zeitstempel optional oder aus der eigenen Systemzeit:** abgelehnt. Eine eigene Zeitangabe belegt gegenüber Dritten nichts, und ein fehlender Zeitstempel lässt sich nicht nachholen (Req 15.3).

## Folgen
- **Positiv:** Die Anwendung kann archivierte Dateien technisch nicht verändern oder löschen. Integrität und Speicherzeitpunkt lassen sich unabhängig vom Speicher prüfen: Signatur, Hash-Kette und Zeitstempel bleiben auch nach einer Wiederherstellung in einen neuen Bucket gültig, weil SHA-256 massgeblich ist (Req 15.5, ADR-008).
- **Positiv:** Korrekturen bleiben in Ausnahmefällen möglich, aber nur mit dem offline verwahrten Bypass-Schlüssel und damit bewusst durch den Inhaber.
- **Negativ:** Der Schutz hängt an der Verwahrung des Bypass-Schlüssels; wer ihn hat, kann Sperren aufheben. Gegenmittel sind die versiegelten Notfallunterlagen (Aufgabe 5.10) und das Manifest, das jede nachträgliche Änderung aufdeckt.
- **Negativ:** Abhängigkeit vom Zeitstempeldienst: Fällt er aus, fehlen Manifeste (Alarm), und ohne ihn startet die Produktion nicht. Dazu kommen Kosten für den Anbieter.
- **Umsetzung:** Sperre setzen und verlängern im Ablage-Service (Aufgabe 2.3); Manifest mit Prüfwerkzeug `archiv manifest pruefen <datum>` (Aufgabe 5.2); Prüflauf (Aufgabe 5.3); App- und Bypass-Schlüssel ausstellen (Aufgabe 5.4).
- **Tests:** Testcontainers MinIO: App-Schlüssel kann gesperrte Versionen nicht löschen und Sperren nicht verkürzen, Verlängern gelingt. WireMock für den Zeitstempeldienst. JUnit für Kanonisierung (RFC 8785) und Signatur. Der Nachweis zu Req 6.10 ist manuell.
- **Betrieb:** Der Ablauf einer Korrektur mit dem Bypass-Schlüssel sowie Löschfunktion, Sperrvermerk und Auskunft folgen später (P12); diese Spec beschreibt sie nicht.

## Verweise
- Req 0.4, 6.3, 6.5, 6.9, 6.10, 6.11, 9.7, 15.1–15.5, 15.8, 15.9, 16.9, 16.12
- P2, P12, P17
- Aufgaben 0.2, 2.3, 4.9, 5.2, 5.3, 5.4, 5.10
- design.md: «Ablage», «Protokoll und Manifest», «Secrets und Konfiguration»; tech.md Regel 2
- ADR-004 (Ablage und Zeitpunkt der Sperre), ADR-008 (Sicherung von Manifesten und Manifest-Schlüssel)
