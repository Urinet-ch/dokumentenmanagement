# Vertrag der Webseiten-Schnittstelle

`public-v<n>.yaml` beschreibt die Webseiten-Schnittstelle des Archivs unter `/public/v<n>` als OpenAPI 3.1, je
Hauptversion eine Datei (Spec 03, Requirement 11, ADR-014). Jede Webseite nutzt sie gleich und nur über diesen
Vertrag; er gehört nicht in die App-Beschreibung `frontend/openapi/` und nicht in den Angular-Client.

## Regeln
- Nur serverseitig: kein CORS, kein Schlüssel im Browser.
- Innerhalb einer Hauptversion nur additiv: neue Endpunkte, neue optionale Parameter, neue Antwortfelder, neue Werte nur
  bei Feldern mit `x-erweiterbar: true`. Alles andere nur unter `/public/v<n+1>`; die alte Version läuft mindestens 6
  Monate ab Bereitstellung der neuen parallel.
- Nutzer ignorieren unbekannte Antwortfelder.
- Jede Antwort trägt `Cache-Control: no-store`; Fehler sind Problem Details mit stabilem `fehlercode`.
- Abkündigung über die Eigenschaft `archiv.webseite.v<n>.sunset` (ISO-Datum, z. B. `2027-07-01`; Umgebungsvariable
  `ARCHIV_WEBSEITE_V<n>_SUNSET`). Ist sie gesetzt, trägt jede Antwort der Version die Header `Deprecation`
  (`@<Unix-Sekunden>`, Sunset minus 6 Monate), `Sunset` (HTTP-Datum, Tagesbeginn UTC) und
  `Link: </public/v<n+1>>; rel="successor-version"`. Ein ungültiger Wert bricht den Start ab.

## Lokal prüfen
- Code = Datei (Req 11.2): `mvn -f backend/pom.xml test -Dtest=PublicVertragTest` vergleicht die Pfade unter
  `/public/`, die der Code beschreibt, mit der Datei; ohne Quarkus-Start.
- Nur additiv (Req 11.3): `.github/scripts/pruefe-vertrag.sh origin/main` vergleicht jede Datei mit ihrem Stand auf der
  Basis (Exit 0 in Ordnung, 1 nicht additiv, 2 nicht prüfbar). Braucht `git` und `ruby` (Standardbibliothek).
  Selbsttest: `.github/scripts/pruefe-vertrag-test.sh` (läuft auch als `VertragPruefungTest` in den Backend-Tests).
