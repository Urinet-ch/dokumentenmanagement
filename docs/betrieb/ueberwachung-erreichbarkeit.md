# Vorschlag: externe Erreichbarkeit überwachen

Stand 27.11.2026. Dieser Entwurf beschreibt ausschliesslich einen möglichen
Ausbau. Er ändert weder das Monitoring der VPS noch den Deploy-Workflow und
enthält keine Betriebsadressen; die Beispiele verwenden Platzhalter.

## Was heute alarmiert

- Die Anwendung verschickt gemäss [Alarme und Kennzahlen](../alarme.md)
  E-Mails für fachliche und betriebliche Befunde, etwa hängen gebliebene
  Dokumente, fehlende Manifeste, Sicherungen und JVM-Speicher. Diese Überwachung
  läuft in der Anwendung selbst.
- Der Deploy-Workflow prüft beim Deploy die Health der Archiv-Dienste, die
  öffentlichen Antworten beider Hosts und die Health anderer Apps vor und nach
  dem Deploy. Bei Fehlern scheitert der Lauf; nach einem übernommenen Stand
  versucht er den Rollback auf die Vorgänger-Images. Das ist keine
  kontinuierliche Überwachung zwischen Deploys.
- Die vorhandene Monitoring-Compose-Datei im Infra-Repo beobachtet Container
  und Host-Ressourcen. Sie enthält keinen externen HTTP- oder
  Zertifikatsablauf-Test.

## Verbliebene Lücke

Wenn API oder Anmeldedienst nicht erreichbar sind, kann die Anwendung keine
Alarm-Mail versenden. Insbesondere erkennt ihre interne Überwachung nicht, dass
sie selbst ausgefallen ist. Am 26.11.2026 lieferten beide Hosts rund zwei
Stunden HTTP 404; ohne unabhängige HTTP-Probe entstand dadurch kein externer
Erreichbarkeitsalarm. Der Deploy-Workflow erkennt Erreichbarkeitsfehler
nur während eines Deploy-Laufs. Ausfälle zwischen Deploys kann deshalb erst
eine Person oder ein unabhängiger externer Prüfer entdecken.

## Vorschlag für unabhängige Prüfungen

| Prüfung | Umfang und möglicher Alarm | Aufwand (Schätzung) | Ablage bei Umsetzung |
| --- | --- | --- | --- |
| Erreichbarkeit beider Hosts | Von ausserhalb der VPS HTTPS-Aufrufe an `https://<ARCHIV_HOST>/q/health/live` und `https://<ARCHIV_AUTH_HOST>/realms/urinet-archiv/.well-known/openid-configuration`; Status und Antwortzeit prüfen. Als Startwert wäre ein Intervall von einer Minute und ein Alarm nach drei aufeinanderfolgenden Fehlern denkbar. | 1–2 h für Probe, Alarmregel und einen kontrollierten Fehlertest | Probe und Alarmregel im `monitoring/`-Verzeichnis des Infra-Repos; Zielwerte ausserhalb des Repos konfigurieren |
| Zertifikate | TLS-Zertifikat beider Platzhalter-Hosts prüfen, Ablaufdatum erfassen und vor Ablauf warnen. Eine Vorwarnung von 30 Tagen wäre ein Vorschlag, kein gesetzter Betriebswert. | 0.5–1 h zusätzlich, sofern die externe Probe TLS-Metadaten liefert | Dieselbe Monitoring-Konfiguration; Warnschwelle als dokumentierter Konfigurationswert |
| Fehlgeschlagener Deploy | Fehlgeschlagene Läufe von `infomaniak-deploy.yml` melden, einschliesslich fehlgeschlagener Health-Prüfung oder Rollback. Die GitHub-Actions-Laufseite bleibt die Quelle für Details; der Alarm enthält nur Workflow, Laufstatus und Link. | 1–2 h für Benachrichtigung und einen kontrollierten Fehlversuch | Benachrichtigungsregel beim GitHub-Workflow oder im freigegebenen Wartungs-/Monitoring-Workflow; Empfänger ausserhalb des Repos |

Die drei Prüfungen ergänzen einander: HTTP/TLS erkennt einen laufenden
Erreichbarkeits- oder Zertifikatsfehler unabhängig von der Anwendung; der
Deploy-Alarm meldet eine fehlgeschlagene Änderung auch dann, wenn der
vorherige Stand durch Rollback wieder erreichbar ist. Gesamtaufwand für eine
Integration mit Alarmweg und Nachweis: grob 3–5 Stunden, abhängig vom
vorhandenen Alarmkanal.

## Grenzen und Ablage

Die Probes sollen nur die öffentlichen, dafür vorgesehenen Antworten prüfen.
Keine Kennzahlen oder Detail-Health öffentlich freigeben. Ziele,
Benachrichtigungsempfänger und allfällige Zugangsdaten gehören nicht in dieses
Repository; hier bleiben ausschliesslich Platzhalter und das Konzept. Die
Monitoring-Regeln und ihre Tests würden im Infra-Repo abgelegt, die
Workflow-Benachrichtigung beim zuständigen GitHub-Workflow. Keine Änderung am
Infra-Repo oder an Workflows ist Teil dieses Vorschlags.

## Offen für Fabian

Welche Prüfung soll umgesetzt werden: Erreichbarkeit, Zertifikatsablauf,
fehlgeschlagener Deploy oder eine Kombination? Bis zu diesem Entscheid wird
nichts davon eingerichtet.
