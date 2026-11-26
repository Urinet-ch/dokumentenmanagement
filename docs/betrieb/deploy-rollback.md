# Deploy und Rollback

Stand: Prüfung des lokalen Infra-Skripts `deploy-app.sh` am 26.11.2026;
kein Zugriff auf den Zielhost. Deploys laufen über die Pipeline.

## Wirkung und Grenzen

Der Rollback verwendet die **aktuelle** Compose-Datei. Er setzt das Hauptimage
`<app>:current` auf `<app>:previous` und, soweit vorhanden, die zugehörigen
Dienstimages `<app>:<dienst>-current` auf `<app>:<dienst>-previous`. Fehlt ein
Dienst-Vorgängerimage, bleibt dessen aktuelles Image bestehen und das Skript
meldet dies. Fehlt das Haupt-Vorgängerimage, bricht der Rollback ab.

Danach erstellt Compose die Container neu. Die Compose-Datei und ihre
Traefik-Labels werden nicht auf den vorherigen Stand zurückgesetzt. Ebenso
werden Datenbank, Schemaänderungen und Volumes nicht zurückgesetzt. Deshalb
helfen ältere Images allein nicht gegen fehlerhafte Labels der aktuellen
Compose-Datei; die Konfiguration muss über einen korrigierten Deploy behoben
werden. Ein Datenbank-Restore ist ein separates Verfahren.

Die tatsächliche Rollback-Dauer ist **nicht geprüft**. Das Skript begrenzt
`compose up` auf 600 Sekunden; das ist keine Zusage für die gesamte Dauer:
Sperrwartezeit und weitere Schritte kommen hinzu, und ein Timeout bedeutet
keinen erfolgreichen Rollback.

## Prüfung danach

Rollback-Ergebnis und Containerzustand im Deploy-Protokoll prüfen, auch
Meldungen über fehlende Dienst-Vorgängerimages. Anschliessend den öffentlichen
Health-Pfad prüfen:

```sh
curl --fail --show-error --silent https://<ziel-adresse>/q/health/live
```

Ein erfolgreicher Health-Aufruf allein beweist weder Anmeldung noch
Datenbankintegrität. Bei Fehlern nicht von einem erfolgreichen Rollback
ausgehen; die Prüfung der Anmeldung und weitere Diagnose erfolgen separat.

## Lehre aus dem Ausfall vom 26.11.2026

Der Router `archiv-auth` des Dienstes `kc` verwies auf eine Middleware des
Dienstes `api`. Traefik führt Router nur für gesunde Container; die API wartete
beim Start auf Keycloak über `archiv-auth`. Dadurch entstand ein Deadlock und
beide Routen antworteten mit 404. Router und ihre Middlewares werden daher am
selben Dienst definiert, damit die Route nicht von der Gesundheit eines
anderen Dienstes abhängt. Die statische Compose-Prüfung deckt diese Zuordnung ab.
