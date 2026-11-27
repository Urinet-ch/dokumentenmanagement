# Requirements — 08 Löschung nach revDSG

**Status:** Entwurf, nicht freigegeben. Die folgenden Kriterien sind Vorschläge; Rechtsfragen sind durch eine Fachperson zu prüfen.

## Einleitung

Diese Spec soll den späteren Umgang mit Löschbegehren und abgelaufenen Aufbewahrungsfristen beschreiben. Sie ändert keine bestehenden Anforderungen oder Entscheide. Insbesondere gelten bis zu einem ausdrücklichen Entscheid die Aufbewahrung und Unveränderbarkeit aus [Spec 01](../01-archiv-kern/requirements.md), die Versionsregeln der [Spec 04](../04-wissensbasis/requirements.md) und die Gesprächsaufbewahrung der [Spec 02](../02-abgleich-chat/requirements.md).

### Requirement 1: Löschung auslösen und prüfen

**User Story:** Als betroffene Person möchte ich ein Löschbegehren stellen können,
damit meine Daten geprüft und – soweit zulässig – gelöscht werden.

#### Acceptance Criteria
1. WENN die Aufbewahrungsfrist eines Dokuments endet, DANN SOLL das Archiv einen Löschkandidaten zur Prüfung bereitstellen, ohne damit allein schon die Löschung auszulösen.
2. WENN ein Löschbegehren eingeht, DANN SOLL das Archiv einen prüfbaren Vorgang eröffnen und die betroffene Person sowie den Umfang des Begehrens zuordnen, ohne Daten vor Abschluss der Prüfung zu löschen.
3. SOLANGE eine gesetzliche Aufbewahrungspflicht, eine aktive Sperre oder eine andere zu prüfende Aufbewahrungsvorgabe gilt, SOLL das Archiv die davon betroffenen Daten nicht löschen oder ihre Sperre verkürzen. Die Berechnung der massgeblichen Frist und die Behandlung eines entgegenstehenden Begehrens sind durch eine Fachperson zu prüfen.
4. WENN ein Löschvorgang freigegeben wird, DANN SOLL er nur den bestätigten Mandanten- und Datenumfang betreffen; die Berechtigung zur Freigabe ist [offen: Wer darf Löschbegehren prüfen und freigeben?].

### Requirement 2: Datenumfang und abgeleitete Daten

**User Story:** Als betroffene Person möchte ich, dass ein Löschentscheid alle betroffenen Ablagen berücksichtigt,
damit gelöschte Inhalte nicht über Kopien oder Ableitungen weiter zugänglich sind.

#### Acceptance Criteria
1. WENN ein Löschvorgang ausgeführt wird, DANN SOLL das Archiv mindestens Originale, Arbeitsfassungen, PDF/A-Dateien, alle S3-Versionen, Dokumentmetadaten, Volltext, Suchindex und Embeddings im bestätigten Umfang erfassen.
2. WENN ein Dokument als Quelle einer Wissensseite verwendet wird, DANN SOLL das Archiv die betroffenen Seiten, Quellverknüpfungen, Seitenversionen und daraus abgeleiteten Index- und Embedding-Daten erfassen; die Behandlung früherer Seitenversionen ist [offen: Welche Variante gilt für Seitenversionen mit Inhalten oder Bezügen zum gelöschten Dokument?].
3. WENN ein Löschvorgang ein Korrekturbeispiel betrifft, DANN SOLL das Archiv `ki_beispiel` über `dokument_id` und `partner_id` in den Umfang der Prüfung und der Auskunft aufnehmen; die konkrete Lösch- oder Aufbewahrungsregel ist [offen: Wie werden Korrekturbeispiele mit Daten der betroffenen und weiterer Personen behandelt?].
4. WENN ein Löschvorgang Gespräche betrifft, DANN SOLL das Archiv Fragen, Werkzeugaufrufe, Quellen und Tokenangaben des Gesprächs erfassen. Das bestehende Löschziel nach 12 Monaten aus Spec 02, Requirement 3.4 bleibt bis zu einem Entscheid bestehen; eine frühere Löschung ist [offen: Wann darf oder muss ein Gespräch vor Ablauf der 12 Monate gelöscht werden?].
5. WENN ein Löschvorgang Protokoll- oder Versionsdaten betrifft, DANN SOLL das Archiv deren bestehende Aufbewahrungsfristen und das Verbot von Änderung und Löschung berücksichtigen; eine abweichende Behandlung ist [offen: Welche Protokoll- und Versionsdaten dürfen nach ihrer Frist gelöscht oder minimiert werden?].
6. WENN eine Sicherung Daten enthält, die im Produktivbestand gelöscht werden, DANN SOLL das Archiv diese Sicherung und ihre Wiederherstellbarkeit in den Löschvorgang einbeziehen; der Umgang mit bestehenden Sicherungsständen ist [offen: Wie und wann werden betroffene Sicherungen ersetzt oder ausgedünnt?].
7. WENN die Löschprüfung eine Archiv-Identität der betroffenen Person feststellt, DANN SOLL das Archiv den passenden Eintrag in `benutzer_verzeichnis` (Keycloak-`sub`, Name, Rolle und Gültigkeit) und – soweit vorhanden – das damit verknüpfte aktuelle Keycloak-Benutzerkonto samt vorhandenen Identitätsattributen (Benutzername, E-Mail, Vor- und Nachname, `mandant` und Rollen) als eigene Datenorte erfassen; die Lösch- oder Aufbewahrungsbehandlung ist [offen: Gehören diese Identitätsdaten in denselben Löschlauf, oder welche Aufbewahrungs- und Löschregeln gelten für sie? Rechtliche Einordnung durch eine Fachperson prüfen.].

### Requirement 3: Durchführung und Nachweis

**User Story:** Als Inhaber möchte ich den Stand eines Löschvorgangs nachvollziehen können,
damit fehlgeschlagene oder unvollständige Löschungen nicht als abgeschlossen gelten.

#### Acceptance Criteria
1. WENN ein Löschvorgang startet, DANN SOLL das Archiv je Datenort den Prüf-, Lösch- oder Aufbewahrungsstatus festhalten und einen Vorgang erst als abgeschlossen kennzeichnen, wenn alle betroffenen Datenorte abgeschlossen oder ausdrücklich als aufzubewahren dokumentiert sind.
2. FALLS ein Datenort nicht erreichbar ist oder die Löschung fehlschlägt, DANN SOLL das Archiv den Vorgang als unvollständig kennzeichnen und eine erneute Prüfung ermöglichen; es SOLL keinen erfolgreichen Abschluss vortäuschen.
3. WENN das Archiv einen Löschvorgang protokolliert, DANN SOLL der Nachweis mindestens Vorgangskennung, Zeitpunkt, freigebende Rolle, betroffene Datenkategorien, Ergebnis und begründete Ausnahmen enthalten, aber keine gelöschten Inhalte.
4. Der Umfang und die Aufbewahrungsdauer dieses Nachweises sind [offen: Welche personenbezogenen Kennungen darf der Löschbeleg enthalten und wie lange wird er aufbewahrt?].

### Requirement 4: Auskunft

**User Story:** Als betroffene Person möchte ich Auskunft über meine im Archiv bearbeiteten Daten erhalten,
damit ich den Datenbestand und den Stand eines Löschbegehrens nachvollziehen kann.

#### Acceptance Criteria
1. WENN eine Auskunft freigegeben wird, DANN SOLL sie die erfassten Datenkategorien, Zwecke, Empfänger, Herkunft, Aufbewahrungsfristen sowie gelöschte, noch vorhandene und aus einem benannten Grund zurückgehaltene Daten ausweisen.
2. Die Auskunft SOLL abgeleitete Daten und `ki_beispiel` einschliessen und kenntlich machen, wenn ein Datensatz zusätzlich Informationen über Dritte enthält.
3. Die Identitätsprüfung, Frist, Form und der Umgang mit Informationen über Dritte in einer Auskunft sind [offen: Welche Auskunftsregeln und Schwärzungen sind nach Prüfung durch eine Fachperson anzuwenden?].
4. WENN eine Auskunft freigegeben wird und ein passender Archivzugang besteht, DANN SOLL sie die Datenkategorien aus `benutzer_verzeichnis` und dem aktuellen Keycloak-Benutzerkonto sowie deren Herkunft, Zweck, Aufbewahrungsgrund und Status berücksichtigen; welche Einzelwerte ausgegeben werden dürfen, ist [offen: Welche Identitätsattribute gehören in die Auskunft, und welche Sicherheitsdaten müssen ausgeschlossen bleiben? Rechtliche Einordnung durch eine Fachperson prüfen.].
