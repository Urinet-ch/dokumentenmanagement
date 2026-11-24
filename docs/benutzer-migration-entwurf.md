# Benutzer-Migration: Entwurf für 21.2

**Entwurf, nicht freigegeben und nicht ausgeführt.** Fabian führt und dokumentiert die Migration; die Checkbox
21.2 bleibt bis zum Betriebsnachweis offen. Die Vorbereitung enthält keine echten Daten und verändert weder
Keycloak noch die Betriebsumgebung.

## Vorher

1. 19.3 und 21.1 müssen abgeschlossen und das Dienstkonto sowie das Betreiberkommando erreichbar sein.
   Geltende Umgebungsvorgaben und Freigabe für die Daten beachten; diese Anleitung veranlasst keinen Deploy.
2. Wartungsfenster vereinbaren: Benutzerverwaltung und konkurrierende Abgleiche pausieren, damit zwischen Prüfung,
   Übernahme und Nachprüfung keine Benutzer, Rollen, Attribute oder Verzeichniszeilen geändert werden.
3. Wiederherstellbarkeit und Zeitpunkt einer konsistenten Datenbanksicherung prüfen. Einen Wiederherstellungsplan
   einschliesslich Auswirkungen auf zwischenzeitliche Änderungen bereithalten; nicht automatisch zurückspielen.
4. Vorherigen Zustand und Anmeldbarkeit der betroffenen Zugänge einschliesslich Inhaber, Rolle, Mandant und TOTP
   geschützt prüfen. Keine Passwörter, TOTP-Codes, Links, Namen oder sonstigen Klartext im Abnahmeprotokoll ablegen.
5. Den Abgleich zunächst lesend ausführen. Abweichungen und Dienstfehler vor der Übernahme getrennt und geschützt
   untersuchen. Nicht allein aus Exit-Code 1 auf einen fachlichen Befund schliessen: der bekannte Folgefall
   `IDENTITAETSDIENST_FEHLER` ist Gegenstand von #529. Keine Annahme aus einem fehlenden JSON-Bericht ableiten.
   Ein bestehender Inhaber ohne protokollierten Anlage- oder Rollenvorgang kann `ROLLE_OHNE_VORGANG` auslösen;
   eine `UEBERNAHME` allein dokumentiert diesen Vorgang nicht. Der Entwurf lässt eine solche Abweichung nicht
   passieren. Vor einem betroffenen Echtlauf muss das Vorgehen dafür separat fachlich und technisch geklärt werden;
   keine Rolle ändern oder Protokollzeile erfinden, um die Prüfung zu umgehen.

## Starter und Ablauf

Das Skript [benutzer-migration-entwurf.py](../tools/benutzer-migration-entwurf.py) erwartet als erstes Argument
einen ausführbaren **lokal bereitgestellten Starter**, der seine Argumente unverändert an das Archiv-Kommando im
Wegwerf-Container weitergibt und dessen Exit-Code zurückliefert. Der Starter wird vom Betreiber für die vorhandene
Compose-Konfiguration bereitgestellt und geprüft; er darf keine persönlichen Daten oder Secrets in Argumente schreiben.
Zum Beispiel kann er nach Setzen von `COMPOSE_DIR` und `COMPOSE_FILE` ausserhalb des Repositorys so aussehen:

```sh
#!/bin/sh
exec sudo docker compose --project-directory "$COMPOSE_DIR" -f "$COMPOSE_FILE" \
  run --rm --no-deps -T -l traefik.enable=false api \
  sh -c 'exec java $JAVA_OPTS_APPEND -jar /deployments/quarkus-run.jar "$@"' sh "$@"
```

Den Starter nicht in einem laufenden Dienst ausführen. Erst Starter, Zielmandant und Image prüfen. Beispielaufrufe
mit Platzhaltern, nicht mit echten Werten ins Protokoll kopieren:

```sh
python3 tools/benutzer-migration-entwurf.py /pfad/zum/starter '<mandant>'
python3 tools/benutzer-migration-entwurf.py /pfad/zum/starter '<mandant>' --uebernehmen
```

Der erste Aufruf liest nur. Der zweite wiederholt die Prüfung und verlangt am Terminal `UEBERNEHMEN`. Bei jedem
nicht erfolgreichen Exit-Code, fehlendem oder widersprüchlichem JSON oder einer Abweichung bricht der Entwurf ab.
Nach dem Schreiben verlangt er dieselbe Anzahl Übernahmen wie geplant und eine erneute Prüfung ohne Abweichungen
und ohne noch geplante Übernahmen. Die Ausgabe des Skripts enthält nur Anzahlen und allgemeine Fehler, keine
Benutzerkennungen. Das zugrunde liegende Kommando kann bei Bedarf **separat** mit `--pruefen --json` aufgerufen
werden; dessen Ausgabe enthält Kennungen und gehört nicht ins Repository oder ungeschützte Protokolle.

Das vorhandene Kommando liest Keycloak und schreibt ohne `--pruefen` fehlende `UEBERNAHME`-Zeilen ins Verzeichnis;
es ändert Keycloak nicht. Ein Fehler oder eine Abweichung beim Schreiblauf kann nach bereits erfolgten Übernahmen
gemeldet werden. Deshalb bei jedem Abbruch den tatsächlichen Zustand prüfen, nicht von einem Rollback ausgehen.

## Nachher und Rückfall

- Anmeldbarkeit, Rolle, Mandant und TOTP der betroffenen Zugänge erneut geschützt prüfen; der Inhaber bleibt Inhaber.
  Keine Zugangsdaten erfassen. Abweichungen vor Freigabe klären; Wartungsfenster erst nach Prüfung beenden.
- In `docs/abnahme/verwaltung.md` nur Datum, Ergebnis der Prüfungen, Anzahl und anonymisierte Befundcodes festhalten;
  keine Kennungen, Namen, Adressen, Secrets oder personenbezogene Rohberichte. Aufgabe 21.2 erst nach Fabians
  tatsächlicher Ausführung und Abnahme abhaken.
- Bei Fehlern stoppen, Zustand und Folgeänderungen sichern und den Betreiberentscheid einholen. Die Verzeichniszeilen
  sind append-only: weder löschen noch als regulären Rollback zurücksetzen. Eine Wiederherstellung aus Sicherung ist
  ein gesonderter Betriebsentscheid mit Datenverlust-Risiko für spätere Änderungen; vorher Auswirkungen und
  Konsistenz prüfen. Wenn keine Wiederherstellung erfolgt, verbleibende Zeilen und nötige Korrekturen dokumentieren.
