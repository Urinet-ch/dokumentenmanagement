# Notfallunterlagen

> **Entwurf, ungeprüft – nicht freigegeben.** Prüfung durch Fabian und Gegenlesen anhand des Notfallablaufs aus
> Aufgabe 5.7 stehen aus. Notfallkontakt: `<Name/Rolle, Kontaktweg>`. Ablageort des versiegelten Satzes: `<…>`.

## Zweck und erste Schritte

Diese Anleitung unterstützt die zuständige Person bei Ausfall, Datenverlust oder Verdacht auf kompromittierte
Zugänge. Der Treuhänder meldet den Vorfall an `<Kontakt/Kontaktweg>`; Wiederherstellung und Schlüsselverwaltung
übernimmt `<beauftragte Rolle>`. Vor jedem Restore werden Sicherungsdateien und ihre Prüfsummen anhand eines
gültigen Manifests geprüft. Wiederherstellungen erfolgen zuerst in einer getrennten Testumgebung. Es werden nur
synthetische Daten für Übungen verwendet. (Spec 01, Req 16.2–16.3, 16.7; [Wiederherstellungsanleitung](../wiederherstellung.md))

Bei jedem Vorfall: Datum/Zeit und betroffene Systemkomponente festhalten, Vorgang an `<Entscheidende Rolle>` melden
und keine Schlüssel, Dokumentinhalte oder Personendaten in Notizen oder Meldungen aufnehmen. Keine Sicherung
ausdünnen und keine Daten überschreiben, bis `<Entscheidende Rolle>` das weitere Vorgehen freigibt. Protokoll und
Übungsnachweis enthalten nur IDs und Zähler (Req 16.7).

## Szenarien

| Szenario und Erkennung | Sofortmassnahme | Wiederherstellung und Nachweis |
| --- | --- | --- |
| **Archiv/VPS nicht erreichbar:** Health-Prüfung schlägt fehl oder zuständige Person meldet Ausfall. | Vorfall an `<Kontakt/Kontaktweg>` melden; keine zweite Instanz eigenmächtig starten. | Verifizierte Sicherung auswählen und nach [Wiederherstellungsanleitung](../wiederherstellung.md) Datenbanken, Realm und Bucket in einer getrennten Umgebung wiederherstellen. Anmeldung, Dokumentliste, Prüfsummen und Prüflauf prüfen; Protokoll unter `<…>` ablegen. (Req 16.1–16.3) |
| **Datenbank verloren oder beschädigt:** Datenbank nicht verfügbar, Sicherungsalarm oder Restore-Prüfung findet Abweichungen. | Schreibvorgänge nach `<freigegebenem Verfahren>` anhalten; keine beschädigte Datenbank überschreiben. | Nur einen Snapshot verwenden, dessen SHA-256 im gültigen Manifest belegt ist. Archiv- und Keycloak-Datenbank mit `pg_restore`, Realm mit `kc.sh import` wiederherstellen; Objektbestand, Anmeldung und Prüflauf kontrollieren. (Req 15.5, 16.2–16.3; `docs/wiederherstellung.md`) |
| **Bucket beschädigt oder unvollständig:** monatlicher Prüflauf oder Manifestprüfung meldet fehlende/abweichende Dateien. | Weitere Änderungen und Löschungen am betroffenen Bestand unterlassen; Abweichung und betroffene IDs melden. | Inventar und Exportkette mit der wiederhergestellten Datenbank abgleichen; Bucket in ein neues, leeres Ziel mit `archiv sicherung wiederherstellen --mandant <code> --inventar <inventar.jsonl> --strom <bucket-export.bin>` einspielen. Sperren aus dem Inventar neu setzen, ohne sie zu verkürzen; danach Prüflauf ausführen. (Req 6.5, 15.5, 16.3, 16.27; `docs/wiederherstellung.md`) |
| **Schlüssel verloren oder kompromittiert:** Schlüssel fehlt, wurde offengelegt oder ein Zugriff ist nicht erklärbar. | Schlüssel als nicht vertrauenswürdig melden; nicht in Meldungen kopieren oder weiterverwenden. `<Entscheidende Rolle>` veranlasst Sperrung/Ersatz gemäss `<freigegebenem Schlüsselverfahren>`. | Betroffene Sicherungen und verbleibende Entschlüsselungswege durch `<beauftragte Rolle>` prüfen lassen. Privaten Sicherungsschlüssel zuerst aus dem versiegelten Satz (`<Verweis/Ablageort>`) beziehen; fehlt auch dieser, gelten die verschlüsselten Sicherungen als nicht entschlüsselbar und es folgt ein Neuanfang mit Vollexport. Ein Wechsel des Probe-Schlüssels erfordert einen neuen Vollexport; die Probe-Schlüsseldatei gehört nicht in die Notfallunterlagen (Req 16.8, 16.12, 16.23). |
| **Hostpoint-Zweitkopie nicht lesbar:** Sicherungs-Workflow oder Integritätsprüfung schlägt fehl, oder benötigte Sicherungsdaten sind nicht abrufbar. | Lauf nicht ausdünnen; Fehler an `<Kontakt/Kontaktweg>` melden und den letzten nachweislich gültigen Stand erhalten. | Ursache und Lesbarkeit durch `<beauftragte Rolle>` prüfen lassen. `deploy/backup/taeglich.sh` erzeugt und prüft lokale verschlüsselte Läufe, transportiert sie aber nicht; Transport und Zweitkopie sind im Betriebsablauf separat zu prüfen. Nach erfolgreicher Bereitstellung Restore in der getrennten Umgebung und Prüflauf nachweisen. (Req 16.2–16.3; `deploy/backup/README.md`) |
| **Konto gesperrt:** `<betroffenes Konto/Anbieter>` ist nicht mehr erreichbar oder eine Anmeldung wird abgewiesen. | Keine Zugangsdaten umgehen oder an andere Personen weitergeben; `<Inhaber/Entscheidende Rolle>` über `<Kontaktweg>` informieren. | Kontozugriff über den freigegebenen Anbieterweg klären. Erst nach Freigabe und Prüfung der verfügbaren, manifestbestätigten Sicherung wiederherstellen; Anbieterwechsel nur nach `<Entscheid>` und dokumentierter Prüfung (Req 16.2–16.3). |

Die Shell-Übung `deploy/backup/restore-uebung-lokal.sh` ist eine lokale Teilprobe mit synthetischen Daten. Sie prüft
nicht den vollständigen Restore von Datenbank-Dumps und Keycloak-Realm in eine zweite Datenbank; sie ersetzt Aufgabe
5.12 und den manuellen Restore-Nachweis deshalb nicht (`deploy/backup/README.md`, `tasks.md` 5.5 und 5.12).

## Versiegelter Satz und Verwahrung

Der Satz wird ausserhalb des Systems am Ort `<…>` versiegelt verwahrt. Die tatsächlichen Schlüssel, Codes und
Kontozugänge stehen weder hier noch im Repository. Verwahrung bzw. Bezug: `<…>`.

- Keycloak-Admin-Zugang und TOTP-Backup-Codes: `<Verweis/Ablageort>`.
- Privater Sicherungsschlüssel für die Exportentschlüsselung und restic-Schlüssel: `<Verweis/Ablageort>`.
- S3-Zugang des Inhabers (Req 6.10): `<Verweis/Ablageort>`.
- Zweiter SSH-Schlüssel für das Ausdünnen der Hostpoint-Zweitkopie (Req 16.2): `<Verweis/Ablageort>`.
- Öffentlicher Manifest-Schlüssel: `<Verweis/Ablageort>`.
- Geheimnisse der Benutzerverwaltung (Spec 06): `keycloak_verwaltung_secret` (Dienstkonto), `keycloak_smtp_passwort`,
  `namenssperre_salz` (nie ungeplant wechseln, siehe [Geheimnisse rotieren](../geheimnisse-rotieren.md)) und der Datenschlüssel
  `archiv_data_key`: `<Verweis/Ablageort>`. Noch nicht durch den Betreiber bestätigt (19.3, 21.6).
- Probe-Schlüssel: **nicht aufnehmen**; er ist ersetzbar und sein privater Teil gehört nicht in diesen Satz (Req 16.12, 16.23).

## Jährliche Übung und Nachweis

Die manuelle Übung ergänzt die monatliche Restore-Probe aus Req 16.3. Verantwortliche, Termin, Testumgebung und
Berichtsablage: `<…>`. Ausschliesslich synthetische Daten und für den Test vorgesehene Schlüssel verwenden.

- [ ] Notfallunterlagen vollständig, versiegelt und erreichbar; tatsächliche Geheimnisse nicht in den Bericht kopieren.
- [ ] Szenario und Zuständigkeiten bestimmen; Kontakt- und Entscheidwege mit Platzhaltern prüfen.
- [ ] Geeigneten Snapshot wählen und SHA-256 mit `archiv manifest pruefen <datum> [<bis-datum>]` verifizieren.
- [ ] In einem getrennten, leeren Ziel Datenbanken, Realm und Bucket wiederherstellen; Sperren nach Inventar prüfen.
- [ ] Anmeldung, Dokumentliste und Prüfsummen vergleichen; abschliessend einen grünen Prüflauf nachweisen.
- [ ] Ergebnis mit Datum, Szenario, Lauf-/Dokument-IDs, Zählern, Abweichungen und Freigabe unter `<…>` ablegen.
- [ ] Schlüssel nach der Übung wieder sicher verwahren; temporäre Testzugänge nach dem freigegebenen Verfahren entfernen.

Bis Aufgabe 5.12 vollständig durchgeführt und dokumentiert ist, gilt die lokale Teilprobe nicht als vollständiger
Restore-Nachweis. Jede freigegebene Fassung dieses Dokuments erhält «gültig ab» und wird als PDF/A im Archiv abgelegt
(Req 16.6).

## Quellen

- [Spec 01 – Requirements](../../.kiro/specs/01-archiv-kern/requirements.md): Req 6, 10, 15 und 16.
- [Spec 01 – Tasks](../../.kiro/specs/01-archiv-kern/tasks.md): Aufgaben 5.5, 5.10 und 5.12.
- [Wiederherstellungsanleitung](../wiederherstellung.md), `deploy/backup/README.md`, `deploy/backup/taeglich.sh`,
  `deploy/backup/restore-uebung-lokal.sh` und [Alarme](../alarme.md).
