# Design Document — 08 Löschung nach revDSG

**Status:** Entwurf vom 27.11.2026. Keine Freigabe, keine Umsetzung und keine Rechtsauskunft. Alle mit `[offen: …]` markierten Punkte brauchen einen Entscheid; rechtliche Fragen sind durch eine Fachperson zu prüfen. Dieses Dokument ändert keine bestehende Spec oder ADR.

## Ziel und Abgrenzung

Die Löschfunktion soll zwei Auslöser unterstützen: das Ende einer Aufbewahrungsfrist und ein geprüftes Löschbegehren. Ein Eingang oder die Genehmigung eines Begehrens hebt eine Aufbewahrungspflicht oder eine aktive Sperre nicht auf. Vor Ende der einschlägigen Frist wird nichts an einer gesperrten Datei gelöscht und keine Sperre verkürzt. Welche Rechtsgrundlage im Einzelfall Vorrang hat, entscheidet diese technische Entwurfsspec nicht.

Es gibt hier bewusst keine `tasks.md` und keine Implementierungsaufgaben. Erst nach den Entscheiden werden Requirements und Datenmodell nötigenfalls angepasst und Umsetzungsaufgaben separat freigegeben.

## Bestehende Leitplanken

- Spec 01, Requirement 9.9, lässt abgelaufene Dokumente bis zur späteren Löschfunktion unverändert. Req 9.3 unterscheidet befristete und unbefristete Aufbewahrung; Req 6.5 verbietet der Anwendung, gesperrte Archivdateien zu löschen oder Sperren zu verkürzen.
- Zugriffsprotokoll und `dokument_version` sind nach Req 10.3 nur anfügend. Req 10.9 setzt für Protokolle mindestens das Geschäftsjahresende plus zehn Jahre und schliesst bisher Löschen und Pseudonymisieren aus; `dokument_version` bleibt so lange wie das Dokument.
- Das Manifest bindet Hashes von `zugriff_protokoll` und `dokument_version` (Spec 01, Req 15.1). Manifeste liegen unter `manifest/` mit COMPLIANCE-Sperre bis zur längsten enthaltenen Frist (Req 15.4). Ein Löschverfahren darf diese vorhandenen Manifeste nicht verändern oder ihre Sperre umgehen.
- `wissensseite_version` ist INSERT-only. Spec 04, Req 15.5 verlangt bei Löschung einer Quelle «Quelle prüfen», lässt aber den Umgang mit früheren Seitenversionen ausdrücklich offen.
- Spec 02, Req 3.4 speichert Gesprächsfragen, Werkzeugaufrufe, Quellen und Tokenangaben und löscht Gespräche nach 12 Monaten.
- ADR-008 sichert Datenbank und Bucket verschlüsselt; Bucket-Zuwächse sind mit `--keep-tag` vom Ausdünnen ausgenommen. Der Eintrag am Ende von Spec 01 `design.md` nennt als möglichen Weg einen periodischen Vollexport mit neuem Tag und anschliessendes `forget` der alten Kette mit dem zweiten Schlüssel.

## Dateninventar für Prüfung und Ausführung

Jeder freigegebene Vorgang braucht vor dem Löschen ein Inventar je Mandant, Person, Objekt und Datenort. Die Tabelle grenzt die Datenorte ab, entscheidet aber nicht über rechtliche Zulässigkeit oder physisches Löschen.

| Datenort | Zu erfassender Bestand | Vorläufige Behandlung / offener Konflikt |
| --- | --- | --- |
| Objektablage | Original, Arbeitsfassung, PDF/A, Vorschaudateien, alle S3-Versionen und Löschmarken in `eingang/`, `archiv/`, `vorschau/` und `archiv/`-Unterpräfixen | Erst nach Frist- und Object-Lock-Prüfung; keine Umgehung von GOVERNANCE/COMPLIANCE und kein Bypass-Header (ADR-004, ADR-007). |
| Datenbank und Metadaten | Dokument, Zusatzfelder, Beziehungen, Fristen, Prüfsummen, Objektversionen und `dokument_version` | Personenbezug und Referenzen prüfen; Append-only und Req 10.9 widersprechen einem stillen Entfernen. |
| Extraktion und Suche | OCR-/Volltext, Suchindizes, normalisierte Texte, Embeddings und weitere materialisierte Suchdaten aus Dokumenten | Nach erfolgreicher Löschung neu aufbauen oder gezielt invalidieren; Vollständigkeit des Index-Abbaus nachweisen. |
| Wissensbasis | `wissensseite`, `wissensseite_version`, `wissensseite_quelle`, Entwürfe, Hüllen, Chunks, Volltext und Embeddings | Req 04.15.5 markiert aktuelle Seiten «Quelle prüfen». Historische Seitenversionen können Inhalt und Auszüge enthalten und sind nur anfügend. |
| Korrekturbeispiele | `ki_beispiel` mit bestätigten Werten und Merkmalen, insbesondere `dokument_id`, `partner_id`, IBAN und E-Mail | Auch Drittpersonendaten einbeziehen; vorläufig werden Beispiele zu fehlenden, verworfenen oder unbestätigten Dokumenten nicht ausgewählt (Spec 01 design.md, Open Points). |
| Gespräche | `chat_gespraech` und `chat_nachricht`: Fragen, Werkzeugparameter und Dokument-IDs, Quellen, Tokens, Antwortinhalt | Spec 02 verlangt Löschung nach 12 Monaten; Vorziehen oder inhaltsfreie Nachweise brauchen Entscheid und gegebenenfalls Spec-Nachzug. |
| Protokolle und Nachweise | `zugriff_protokoll`, `dokument_version`, `wissens_log`, Manifestzeilen, Signaturen, Zeitstempel und Löschbeleg | Getrennt nach Aufbewahrungsfrist, Append-only-Regeln und Manifestbindung prüfen; kein stilles Umschreiben historischer Nachweise. |
| Archivisches Benutzerverzeichnis | `benutzer_verzeichnis` je Mandant mit `benutzer` (Keycloak-`sub`, `token:<id>` oder `<webseite>/<bearbeiter>`), Name/Anzeigename, Rolle, Gültigkeitszeitraum und Anlass | Zugehörige Einträge anhand der `sub` bzw. des gespeicherten Bearbeiter-Schlüssels in Prüfung und Auskunft aufnehmen. Nach Spec 01, Req 10.10 INSERT-only und überlebt einen Neuaufbau von Keycloak; Aufbewahrung und eine allfällige spätere Minimierung brauchen einen Entscheid. |
| Laufender Keycloak-Benutzerbestand | Aktuelle Benutzerkonten des Realm `urinet-archiv` mit `sub` und vorhandenen Identitätsattributen wie Benutzername, E-Mail, Vor- und Nachname, `mandant` und Rollen; Konten stehen nicht im Realm-Export | Passendes Konto anhand des stabilen `sub` in Prüfung und Auskunft aufnehmen. Kontolöschung bleibt vom Dokumentlöschlauf getrennt und folgt bis zu einem Entscheid dem Lebenszyklus aus Spec 06. Die Keycloak-Datenbank wird separat gesichert; Löschung aus dieser Sicherung folgt der gewählten Sicherungsvariante. |
| Sicherungen | tägliche PostgreSQL-Exporte (Archiv und Keycloak), Bucket-Exporte, restic-Snapshots, Probe- und Wiederherstellungsstände | Sicherungen sind Kopien mit eigener Lebensdauer. Die inkrementellen Bucket-Zuwächse sind aktuell vom `forget` ausgenommen; deshalb reicht Löschen im Produktivbestand nicht. |

Zusätzliche Caches, temporäre Dateien, Analyse-/Exportdateien oder Kopien ausserhalb dieser Liste dürfen nicht als gelöscht gelten, bevor ihr Speicherort und ihre Bereinigung geprüft sind. [offen: Welche weiteren persistenten oder temporären Datenorte sind verbindlich in das Inventar aufzunehmen?]

## Vorgeschlagener Ablauf

1. **Vorgang erfassen:** Löschbegehren oder Fristablauf mit Mandant, anfragender Person und gewünschtem Umfang aufnehmen. Eingangsbestätigung ist keine Zusage, dass Daten gelöscht werden.
2. **Identität und Umfang prüfen:** Anfrage der betroffenen Person zuordnen, Personen- und Drittpersonendaten unterscheiden und verbundene Dokumente, Beispiele, Seiten, Gespräche sowie Sicherungen inventarisieren. Bei einer Person mit Archivzugang zusätzlich den passenden Eintrag in `benutzer_verzeichnis` und – soweit das Konto besteht – das aktuelle Keycloak-Benutzerkonto samt vorhandenen Identitätsattributen anhand des `sub` erfassen. [offen: Welche Rolle darf Identität, Vertretung und Umfang bestätigen?]
3. **Aufbewahrung und Sperren prüfen:** `aufbewahren_bis`, `gesetzliche_pflicht`, aktive Object-Lock-Sperren, Manifestbindungen, Protokollfristen und bestehende Gesprächsfrist pro Datenort prüfen. Rechtsgrundlage, Ausnahmen und Fristberechnung sind durch eine Fachperson zu prüfen.
4. **Entscheid dokumentieren:** Freigabe, Zurückstellung bis zu einem konkreten Fristende oder begründete Ablehnung festhalten. Bei aktiver Sperre nur eine spätere Prüfung vormerken; keine Datei vor Fristende löschen.
5. **Datenorte abarbeiten:** freigegebene, nicht gesperrte Bestände und abgeleitete Daten entfernen oder gemäss beschlossenem Verfahren unzugänglich machen; abhängige Seiten und Indizes aktualisieren. Sicherungen separat gemäss gewählter Variante behandeln.
6. **Prüfen und abschliessen:** je Datenort den Erfolg verifizieren. Fehler oder nicht erreichbare Kopien bleiben sichtbar als «unvollständig»; der Vorgang darf dann nicht als gelöscht bestätigt werden.
7. **Auskunft und Nachweis:** der betroffenen Person eine verständliche Antwort mit Ergebnis, Kategorien, Fristen und verbleibenden Daten geben; bei einem Archivzugang auch Datenorte, Zweck, Herkunft und Aufbewahrungsstatus von `benutzer_verzeichnis` und passendem Keycloak-Konto berücksichtigen. Den internen Nachweis inhaltsarm halten und nur die nach Entscheid erlaubten Kennungen aufnehmen.

## Varianten und Entscheide

### 1. Aufbewahrungsende und Löschbegehren

- **A — Nur Fristablauf:** Löschlauf startet ausschliesslich, wenn die konfigurierte Aufbewahrung endet. Geringer Aufwand; erfasst kein früheres Begehren.
- **B — Nur geprüftes Begehren:** Löschlauf startet nur nach expliziter Freigabe eines Begehrens. Gute Kontrolle, aber abgelaufene Bestände bleiben ohne Antrag liegen.
- **C — Beide Auslöser, gemeinsame Prüfung (Empfehlung):** Fristablauf erzeugt einen Kandidaten, ein Begehren eröffnet einen Prüfprozess; erst die bestätigte Zulässigkeit startet denselben Löschlauf. Mittlerer Aufwand, klare Trennung zwischen Auslöser und rechtlicher Freigabe.

[offen: Sollen Fristablauf und geprüftes Löschbegehren beide Auslöser sein, und welche Rolle entscheidet über die Freigabe?]

### 2. Seitenversionen der Wissensbasis

- **A — Anfügend behalten und in der neuen Fassung schwärzen:** Betroffene Stellen, Quellbezüge und Auszüge in einer neuen freigegebenen Seitenversion schwärzen und «Quelle prüfen» setzen. Geringer bis mittlerer Aufwand; alte `wissensseite_version`-Inhalte bleiben unverändert und lesbar, also ist das keine vollständige Löschung.
- **B — Betroffene historische Versionen physisch entfernen, nicht-inhaltlichen Stumpf protokollieren:** Entfernt den Inhalt, verletzt aber die bestehende INSERT-only-Regel und kann bereits manifestierte Hashes historischer Versionen nicht rückwirkend korrigieren. Hoher Migrations- und Nachweisaufwand.
- **C — Dokumentbezogene Inhalte verschlüsseln und den Schlüssel verwerfen:** Erhält append-only-Zeilen und macht den betroffenen Inhalt nach Schlüsselvernichtung unlesbar. Sehr hoher Aufwand für Schlüsselverwaltung, Migration, Sicherungen und Nachweis; für bereits unverschlüsselte Versionen nicht rückwirkend ohne Umbau.

**Empfehlung:** C für neue Versionen prüfen; für bestehende Versionen keine Löschung behaupten, bis B oder eine andere geprüfte Migration samt Manifestfolgen entschieden ist. Rechtswirkung jeder Variante ist durch eine Fachperson zu prüfen.

[offen: Welche Variante gilt für historische Seitenversionen, Auszüge und abgeleitete Chunks?]

### 3. Korrekturbeispiele `ki_beispiel`

- **A — Nur am gelöschten Dokument verknüpfte Zeilen entfernen:** Geringer Aufwand, erhält Beispiele aus anderen Quellen; kann aber personenbezogene Merkmale über `partner_id` oder gemeinsam genutzte Inhalte zurücklassen.
- **B — Über `dokument_id` und `partner_id` verknüpfte Beispiele samt sensiblen Merkmalen entfernen (Empfehlung):** Deckt Quell- und Partnerbezug ab und passt zum offenen Hinweis in Spec 01. Mittlerer Aufwand; ohne feinere Herkunft kann es nützliche Beispiele anderer Dokumente mitentfernen.
- **C — Namen und Drittpersonenmerkmale schwärzen, restliche Korrekturwerte behalten:** Erhält Lernnutzen, benötigt Feld-für-Feld-Regeln und birgt Re-Identifikationsrisiken; Erfolg der Anonymisierung ist nicht vorauszusetzen.

[offen: Bedeutet die Verknüpfung über `partner_id` die Löschung aller Beispiele dieses Partners oder nur der betroffenen Quelle und Merkmale?]

### 4. Gespräche

- **A — Bestehende 12 Monate unverändert anwenden:** Geringster Aufwand und kein Spec-Konflikt; ein gültiges früheres Begehren ändert den Zeitpunkt nicht.
- **B — Bei freigegebenem Begehren früher vollständig löschen:** Stellt Inhalte und Quellenbezüge in den Mittelpunkt; muss Spec 02, Req 3.4, Löschjob, Fehlerbehandlung und Beleg aktualisieren.
- **C — Inhalte früh löschen, nur inhaltsfreien Nachweis behalten:** Reduziert verbleibende Inhalte, bewahrt aber einen Nachweis; erfordert eine Entscheidung über IDs und die Dauer dieses Nachweises.

**Empfehlung:** C als Entwurfsrichtung prüfen und die 12-Monats-Regel von Spec 02 nur nach ausdrücklichem Entscheid anpassen. Rechtsgrundlage und zulässiger Nachweis sind durch eine Fachperson zu prüfen.

[offen: Kann ein freigegebenes Begehren die 12-monatige Aufbewahrung eines Gesprächs verkürzen, und welche Nachweisdaten bleiben?]

### 5. Protokolle und Löschbeleg

- **A — Bestehende Req 10.3/10.9 unverändert anwenden:** Protokolle bleiben append-only und mindestens bis zur festgelegten Frist erhalten. Geringer Aufwand und konsistent mit den aktuellen Entscheiden; personenbezogene Kennungen können über die Löschfrist hinaus bestehen.
- **B — Nach Fristende personenbezogene Werte entfernen oder pseudonymisieren:** Verringert Identifizierbarkeit, widerspricht aber dem heutigen Verbot der Pseudonymisierung und braucht Änderungen an Datenbankrechten, Req 10.9 und Manifestnachweisen.
- **C — Personenbezogenen Payload verschlüsseln und Schlüssel verwerfen:** Kann append-only-Hüllen und Hashes bewahren; hoher Umbau, und Wiederherstellungsstände dürfen keinen alten Schlüssel zurückbringen.

**Empfehlung:** Bis zu expliziter Freigabe gilt A; B oder C erst nach fachlicher Prüfung und datiertem Entscheid in Req 10.9. Ein Löschbeleg soll keine Inhalte gelöschter Datensätze kopieren.

[offen: Welche Protokollfelder und Kennungen dürfen nach Ablauf der Frist erhalten bleiben, und wird Req 10.9 dafür geändert?]

### 6. Sicherungen und Zweitkopie

- **A — Ablauf der bestehenden Sicherungsfristen abwarten:** Geringster Umbau; Löschung kann erst nach Ablauf aller betroffenen Stände bestätigt werden. Die mit `--keep-tag` ausgenommenen Bucket-Zuwächse laufen derzeit nicht regulär aus.
- **B — Bereinigten Vollexport erstellen, Restore prüfen, alte Kette ausdünnen (Empfehlung):** Erzeugt eine neue konsistente Sicherungsbasis ohne die gelöschten Inhalte; nach erfolgreichem Restore-Test kann die alte Kette mit dem zweiten Schlüssel gezielt per `forget` ausgedünnt werden. Hoher Betriebs- und Prüfaufwand, neue Betriebsregel und Nachweis nötig.
- **C — Verschlüsselung je Dokument mit Schlüsselvernichtung:** Schnelle kryptografische Unzugänglichkeit in Produktivbestand und Sicherungen, falls alle Kopien den Schlüssel nicht enthalten; sehr hoher Architekturaufwand und Key-Management-Risiko, nicht im heutigen Backup-Design vorhanden.

[offen: Wie oft wird ein bereinigter Vollexport erstellt, welche Restore-Prüfung ist nötig, und wann darf die alte Sicherungskette ausgedünnt werden?]

### 7. Auskunft und Daten Dritter

- **A — Nur Daten der anfragenden Person ausgeben:** Einfach, kann aber verbundene Datenorte und abgeleitete Werte auslassen.
- **B — Datenkategorien und abgeleitete Datensätze vollständig ausweisen, fremde Inhalte minimieren (Empfehlung):** Macht Verknüpfungen und Aufbewahrung transparent; braucht eine fachlich geprüfte Grenze, damit keine Daten Dritter offengelegt werden.
- **C — Rohdaten aller verknüpften Dokumente und Gesprächsinhalte ausgeben:** Vollständig, aber hoher Aufwand und mögliches Offenlegen von Personendaten Dritter.

[offen: Welche Daten und Drittpersonenmerkmale gehören in die Auskunft, und welche dürfen oder müssen geschwärzt werden?]

### 8. Benutzerverzeichnis und Keycloak-Identitäten

- **A — Beide Datenorte unmittelbar mitlöschen:** Entfernt beim freigegebenen Begehren den Keycloak-Benutzer und den zugehörigen `benutzer_verzeichnis`-Eintrag. Das wäre ein direkter Löschweg, widerspricht aber dem INSERT-only-Grundsatz und den Aufbewahrungsfristen aus Spec 01, Req 10.9/10.10; hoher Änderungs- und Nachweisaufwand.
- **B — In Prüfung und Auskunft aufnehmen, bestehende Lebenszyklen getrennt lassen (vorläufige Empfehlung):** `benutzer_verzeichnis` bleibt nach Req 10.10 anfügend und wird bis zur festgelegten Frist aufbewahrt; Änderungen am Keycloak-Konto folgen bis zu einem Entscheid dem Lebenszyklus aus Spec 06. Geringster Umbau und keine stillschweigende Änderung bestehender Regeln, lässt aber Identitätsdaten bestehen.
- **C — Getrennter, zweistufiger Prozess:** Den Keycloak-Benutzer nach separater Freigabe gemäss Spec 06 löschen, den append-only-Eintrag im Benutzerverzeichnis bis zum Ende seiner Aufbewahrung behalten und erst danach eine ausdrücklich beschlossene Minimierung ausführen. Hoher Aufwand für Fristen, Protokoll- und Manifestfolgen; erfordert Entscheide und Anpassungen an bestehenden Specs.

**Empfehlung:** B als Zwischenstand; beide Datenorte werden sichtbar geprüft und in der Auskunft berücksichtigt, aber nicht automatisch geändert. Eine abweichende Löschung oder Minimierung braucht einen datierten Entscheid und die Prüfung durch eine Fachperson.

[offen: Gehören `benutzer_verzeichnis` und Keycloak-Identitätsdaten selbst zum Löschbegehren, und welche Teile dürfen nach Ablauf der jeweiligen Aufbewahrung gelöscht oder minimiert werden?]

## Noch zu klären

- [offen: Welche Fristen und rechtlichen Ausnahmen gelten für jedes Datenobjekt? Durch eine Fachperson zu prüfen.]
- [offen: Wie werden Bearbeitung, Ablehnung, Zurückstellung und Auskunft gegenüber der betroffenen Person bestätigt?]
- [offen: Welche Datenorte ausserhalb der in diesem Entwurf aufgelisteten Systeme müssen Inventar und Löschbeleg umfassen?]

## Verweise

- [Spec 01, Requirements 6.5, 9.3, 9.9, 10.3, 10.9 und 15](../01-archiv-kern/requirements.md)
- [Spec 01, Open Points zu Wissensversionen, `ki_beispiel` und Sicherungen](../01-archiv-kern/design.md)
- [Spec 04, Requirement 15.5 und append-only `wissensseite_version`](../04-wissensbasis/requirements.md)
- [Spec 02, Requirement 3.4](../02-abgleich-chat/requirements.md)
- [Spec 06, Identitätsquelle und Benutzerlebenszyklus](../06-verwaltung-einstellungen-benutzer/requirements.md), [Design](../06-verwaltung-einstellungen-benutzer/design.md)
- [ADR-004: Zweistufige Ablage](../../../docs/adr/ADR-004-zweistufige-ablage.md), [ADR-007: Unveränderbarkeit](../../../docs/adr/ADR-007-unveraenderbarkeit-governance.md), [ADR-008: Sicherung](../../../docs/adr/ADR-008-sicherung-zweitkopie.md)
- [Verfahrensbeschreibung](../../../docs/verfahrensbeschreibung-erfassen-archivieren.md)
