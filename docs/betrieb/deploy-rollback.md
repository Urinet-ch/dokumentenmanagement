# Deploy und Rollback

Stand: Abgleich der versionierten Konfiguration am 27.11.2026;
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

### Schema neuer als Image
Nach einem Rückfall läuft das ältere Image auf dem neueren Schema; Liquibase
lässt Changesets, die das ältere Image nicht kennt, stehen. Für
`150-quelle` (Quellenregister und Quellläufe, Spec 09, Aufgabe 27.6) heisst
das nach der ersten Nutzung:

- Tabellen `quelle` und `quelle_lauf` und die Spalte
  `dokument.quelle_lauf_id` bleiben mit ihren Daten; das ältere Image liest
  und schreibt sie nicht. Uploads in dieser Zeit erhalten keinen Quelllauf,
  und ein laufender Lauf zählt sie nicht.
- Dokumente mit `dokument.quelle` `GMAIL` oder `ORDNER` bleiben gültig; das
  ältere Image zeigt den Wert unverändert, kennt ihn aber nicht als
  Auswahlwert.
- Die Werkzeuge erhalten für `/quellen` und `/dokumente/pruefsummen` einen
  Fehler (404 oder 405), und das ältere Image übergeht die Formularfelder
  `quelle` und `quelllauf` beim Upload; Läufe deshalb vor dem Rückfall abschliessen oder danach als abgebrochen
  behandeln und neu starten.
- Das Rollback des Changesets selbst gilt nur vor der ersten Nutzung: Seine
  Wache bricht ab, sobald Läufe, Verweise, `GMAIL`/`ORDNER` oder geänderte
  Quellen bestehen, statt sie zu verwerfen. Ein Schema-Rückbau danach ist
  ein Datenbank-Restore, kein Rollback.

Kehrt das neuere Image zurück, übernimmt es den Stand wieder; Uploads aus der
Zwischenzeit bleiben ohne Quelllauf.

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

## Rückfall vor «Wissen je Mandant freischalten» (Spec 04, Req 22)
Der Rollback lässt die Datenbank stehen: die Spalte `public.mandant.wissen_freigeschaltet`
(Changeset `045`) und die Protokollaktionen `WISSEN_FREIGESCHALTET` und `WISSEN_ABGESCHALTET`
(Changeset `142`). Auch `liquibase rollback` baut die Bedingung der Aktionen in `142` bewusst nicht zurück,
weil das Zugriffsprotokoll unveränderlich ist; solche Einträge werden nie gelöscht.

Ein Image vor PR #751 hat folgende Folgen:
- Es kennt den Schalter nicht. Wissen ist dann für **alle** Mandanten verfügbar (Menü, Chat, Suche,
  öffentlicher Wissenschat, Ingest, Lint), auch für Mandanten, für die es abgeschaltet ist. Das Kommando
  `archiv mandant wissen` fehlt.
- Sobald ein Mandant einmal geschaltet wurde, enthält sein Zugriffsprotokoll Einträge mit den neuen Aktionen. Das
  ältere Image kann sie nicht lesen (`ProtokollResource`, `ProtokollAktion.valueOf`): `GET /api/v1/protokoll`
  antwortet mit 500, sobald die angefragte Seite einen solchen Eintrag enthält. Dadurch bricht die Seite
  «Zugriffsprotokoll». Ein Filter auf eine andere Aktion und das Schreiben neuer Einträge funktionieren weiter.

Entscheid Fabian 28.11.2026: Rückfall auf ein Image vor #751 wird nicht unterstützt; Abhilfe ist ein Deploy nach vorn. Protokolleinträge werden dafür nie gelöscht. Vor dem ersten Schalten (kein Eintrag
mit den beiden Aktionen) bleibt ein Rückfall ohne Folgen für das Protokoll; die erste Folge oben gilt dann trotzdem.

## Router und Sicherheits-Header

Die Router der Anwendung (PWA, API, öffentliche Schnittstelle und Health) in
[compose.yml](../../deploy/infomaniak/compose.yml) nutzen `archiv-sts`,
definiert beim Dienst `api`; der Router `archiv-auth`
nutzt `archiv-auth-sts`, definiert beim Dienst `kc`. Der Anmeldedienst darf
keine Middleware des API-Dienstes verwenden. Damit hängt seine Route nicht
von der Gesundheit der API ab und umgekehrt; die Zusammenhänge des Ausfalls
vom 26.11.2026 stehen im nächsten Abschnitt.

Beide HSTS-Middlewares verwenden `max-age=31536000` (ein Jahr);
`includeSubDomains` und `preload` sind ausgeschaltet.
HSTS wird am Reverse Proxy gesetzt, nicht durch die Anwendung.

Die Anwendung setzt unabhängig davon in
[`SicherheitsHeaderRoute.java`](../../backend/src/main/java/ch/urinet/archiv/gemeinsam/adapter/in/rest/SicherheitsHeaderRoute.java)
`Content-Security-Policy: frame-ancestors 'none'` und `X-Frame-Options: DENY` auf ihren Antworten sowie
`X-Content-Type-Options: nosniff`. Für `/api` und `/api/*` kommt
`Cross-Origin-Resource-Policy: same-origin` dazu. Die [lokale ZAP-Baseline](./zap-baseline-lokal.md)
prüft nur einen isolierten Teststack; die [Staging-Anleitung](./zap-baseline-staging.md)
gilt nur für ein ausdrücklich freigegebenes Ziel.

## Lehre aus dem Ausfall vom 26.11.2026

Der Router `archiv-auth` des Dienstes `kc` verwies auf eine Middleware des
Dienstes `api`. Traefik führt Router nur für gesunde Container; die API wartete
beim Start auf Keycloak über `archiv-auth`. Dadurch entstand ein Deadlock und
beide Routen antworteten mit 404. Router und ihre Middlewares werden daher am
selben Dienst definiert, damit die Route nicht von der Gesundheit eines
anderen Dienstes abhängt. Die statische Compose-Prüfung deckt diese Zuordnung ab.
