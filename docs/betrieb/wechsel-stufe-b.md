# Wechsel von Stufe A auf Stufe B

Arbeitsanleitung für Fabian und Infra zu Spec 01, Aufgabe 31.7. Der Wechsel ist für den 29./30.12.2026
vorgesehen; er erfolgt auf derselben VPS. Diese Anleitung ist kein Betriebsnachweis und keine Freigabe für echte
Belege. Ergebnisse in der [Vorlage](../abnahme/wechsel-stufe-b-vorlage.md) festhalten; Betriebswerte, Geheimnisse,
Beleginhalte und personenbezogene Angaben gehören nicht ins Repository.

## Voraussetzungen und Abbruch

1. **Fabian:** synthetischen Probelauf aus Spec 09, 29.10 abschliessen. Danach alle Eingänge und Uploads der Stufe A
   stoppen, auch automatisierte; den letzten Upload und den Beginn der Sperrfrist im Wechselprotokoll festhalten.
   Vor dem Leeren jedes Test-Buckets muss mindestens ein Tag seit dem letzten Upload vergangen sein. Spätere
   Schreibvorgänge können eigene Sperren auslösen: alle Versionen und ihre Sperren prüfen; bei aktiver Sperre warten,
   nicht umgehen oder verkürzen.
2. **Fabian und Infra:** Nachweise zu 31.2–31.6 und 31.10 sowie 1.10 und 5.5 prüfen. Dazu gehören die freigegebenen
   Stufe-B-Werte und der Weg zu leeren Volumes, das eigene Netz, Speicher- und Schutzprüfung, tägliche Sicherung mit
   Zweitkopie, grüne Restore-Probe mit synthetischen Daten, Alarme und Leistungsmessung. Ein verfehltes Leistungsziel
   Fabian zur Entscheidung vorlegen; daraus folgt kein automatischer Stopp. Fehlende andere Voraussetzungen oder ein
   nicht geklärter Befund: Wechsel anhalten. Die Liste
   [Start mit echten Belegen](start-echtbelege.md) bleibt für das spätere Go/No-go massgeblich.
3. **Fabian:** Wartungsfenster, Zuständigkeiten und Rückweg festlegen. Festhalten, welche ausschliesslich
   synthetischen oder anonymisierten Testdaten auf Stufe A lagen. Geschwärzte echte Belege sind keine Testdaten.
   Zugangsdaten, Daten- und Manifest-Schlüssel bleiben bestehen; nur das Testschlüsselpaar des Sicherungsfunktionstests
   nach Req 16.19 wird verworfen. Probe- und produktive Schlüssel nicht damit verwechseln.
4. **Fabian und Infra:** Bestand sämtlicher Stufe-A-Mandanten, Test-Buckets und ihrer Versionen einschliesslich
   Löschmarken sowie Testbenutzer und Schlüssel vor der Bereinigung inventarisieren. Auch deaktivierte Mandanten,
   Benutzer ohne gültiges Mandantenattribut und nicht mehr im Mandantenregister geführte Buckets berücksichtigen.
   Wo Herkunft oder Vollständigkeit unklar ist: anhalten und klären, nicht aus einem leeren Teilbestand auf den
   Gesamtbestand schliessen. Nur IDs und Zähler im Protokoll; keine Zugangsdaten oder Inhalte.

## Bereinigung und leerer Start

5. **Fabian:** Nach Ablauf der Frist sämtliche Versionen und Löschmarken der inventarisierten Stufe-A-Test-Buckets
   einschliesslich `manifest/` mit dem App-Schlüssel ohne Bypass-Header entfernen und anschliessend die Buckets
   löschen. Keine produktiven oder Probe-Buckets anfassen, keinen Inhaber-Zugang und keinen Governance-Bypass
   verwenden. COMPLIANCE-Sperren laufen regulär ab. Bei verweigertem Löschen oder unvollständiger Auflistung
   abbrechen und Ursache klären; nicht eine Sperre umgehen. Erneut vollständig prüfen, dass keine Stufe-A-Buckets
   oder Versionen übrig sind.
6. **Fabian und Infra:** Archiv- und Keycloak-Datenbank nach dem freigegebenen Weg aus 31.2 leer neu bereitstellen,
   einschliesslich des dort vorgesehenen Umgangs mit alten Daten- und Arbeitsvolumes. Nicht bestehende
   produktive Datenbanken oder Volumes löschen. Das Testschlüsselpaar des Funktionstests verwerfen. Festhalten,
   welche Daten verworfen wurden und dass Zugangsdaten, Daten- und Manifest-Schlüssel erhalten bleiben.
7. **Fabian:** Vor der Anlage produktiver Benutzer und Schlüssel die leeren Datenbanken und den Realm prüfen:
   pro Mandant keine Dokumente, API-Tokens und Webseiten-Schlüssel aus Stufe A; realmweit keine Testbenutzer
   oder Testrollen, einschliesslich gesperrter und nicht zuordenbarer Benutzer. Dienstkonten und notwendige
   Standardrollen des leeren produktiven Realms nicht mit Testkonten verwechseln. Ein Namensmuster allein beweist
   nicht, dass keine Testkonten bestehen: den gesamten Bestand mit dem vorherigen Inventar und dem freigegebenen
   Realm abgleichen. Auch widerrufene oder abgelaufene Testschlüssel erfassen, nicht nur aktive.
   Bei einem Fund, fehlender Berechtigung, unvollständiger Auflistung oder nicht zuordenbarem Eintrag: **kein
   Weiterfahren**, klären und Prüfung wiederholen.

   **Offener Repo-Teil:** Ein automatisiertes, ausschliesslich lesendes Prüfwerkzeug für diese Bestände ist noch
   nicht vorhanden. Die obige Prüfung ist daher nicht durch einen Werkzeugaufruf nachgewiesen; die Umsetzung und
   ihr Nachweis bleiben offen. Bestehende `archiv`-Kommandos dürfen nicht als rein lesender Ersatz angenommen werden:
   ihr Start kann Migrationen und das Aufräumen des Arbeitsverzeichnisses auslösen.

## Produktiv einrichten und prüfen

8. **Fabian und Infra:** Produktiven Bucket gemäss 31.7 und 5.4 anlegen; Versionierung, Object Lock und Bucket
   Policy mit Deny auf `s3:BypassGovernanceRetention` prüfen. Das leere Ziel und die Zuordnung zum Mandanten vor der
   Nutzung prüfen. Produktive Sperren nicht verkürzen; vorhandene produktive Objekte nie bereinigen.
9. **Fabian:** Freigegebenen PR aus 31.2 mergen und ausschliesslich über den Deploy-Workflow ausrollen. Vorher
   grünen CI-Nachweis des zu deployenden Stands prüfen. `archiv.stufe=B`, produktives Bucket-Präfix, keine
   Höchstfristen und COMPLIANCE für Manifeste prüfen. API und Worker bleiben zusammen; Deploy-Ziel und DNS bleiben.
   Scheitern Deploy oder Health, keinen echten Beleg einbringen und den unten beschriebenen Rückweg beachten.
10. **Fabian:** Produktiven Realm mit TOTP einrichten und Mandant `urinet` im Wegwerf-Container anlegen; den
    freigegebenen Ablauf aus 31.2 verwenden. Anmeldungen, Mandantenzuordnung und Rollen prüfen; keine Testkonten oder
    Testschlüssel übernehmen. Netz- und Portprüfung nach 16.28 sowie Geheimniszuordnung nach 16.29 dokumentieren.
11. **Fabian und Infra:** Zeitstempeldienste vor produktiver Archivierung anbinden und prüfen; tägliche Sicherung
    mit Zweitkopie und Alarmierung prüfen, `archiv.ueberwachung.sicherung-aktiv` einschalten, sobald sie läuft.
    Health, Überwachung und Schutz der anderen Apps prüfen. Fehler, fehlende Nachweise und Abweichungen halten den
    Wechsel an; insbesondere keinen echten Beleg als Funktionstest verwenden.
12. **Fabian und QA:** Wechselprotokoll vervollständigen und nach 31.8 die Go/No-go-Liste mit tatsächlichen
    Betriebsnachweisen prüfen. Erst ein dokumentiertes Go erlaubt den Probelauf mit echten Belegen nach Spec 09,
    30.1. Das erste produktive Manifest mit Zeitstempeln nachweisen. Die Restore-Probe nach 31.9 folgt nach diesem
    Probelauf und vor dem Bestandsimport.

## Rückweg

- Vor der Bereinigung kann Fabian den Wechsel anhalten; Stufe A bleibt ausschliesslich für Testdaten zulässig.
  Sind danach neue Uploads erfolgt, beginnt die Wartefrist für die betroffenen Test-Buckets erneut.
- Das Verwerfen der Testdaten und Datenbanken ist endgültig; ein Rollback des Images stellt sie nicht wieder her.
  Nach begonnener Bereinigung Stufe A nicht als unverändert oder vollständig wiederhergestellt ausgeben.
- Vor dem ersten echten Beleg: Bei gescheitertem Deploy, fehlendem Nachweis oder aktiven Sperren den Betrieb
  geschlossen halten, Fehler und Stand dokumentieren. Fabian entscheidet über Wiederholung oder Rückkehr zu einer
  ausschliesslich synthetischen Stufe A; Deploy und gegebenenfalls Rollback nur über den vorgesehenen Workflow.
- Nach Eingang echter Belege **nicht** auf Stufe A mit verkürzten Sperren zurückschalten und keine produktiven
  Daten oder Bucket-Versionen verwerfen. Fabian stoppt weitere Eingänge und veranlasst die Wiederherstellung nach
  [Wiederherstellungsanleitung](../wiederherstellung.md); Object Lock und Aufbewahrung bleiben verbindlich.
