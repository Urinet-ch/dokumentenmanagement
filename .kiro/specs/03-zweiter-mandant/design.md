# Design Document — 03 Zweiter Mandant VHF/GSK

## Overview
Kein neuer Dienst: der bestehende Archiv-Dienst erhält einen Mandanten `vhf-gsk`, eine
öffentliche Schnittstelle `/public/v1` und ein WordPress-Plugin. Besucher sprechen nie direkt
mit dem Archiv; das Plugin ruft es serverseitig mit einem Webseiten-Schlüssel auf. Neue Teile folgen der
hexagonalen Gliederung aus `structure.md` (`integration/webseite` mit `domain`, `application`,
`adapter`; bisher `integration/wordpress`, geändert 13.11.2026, E-B); Schemaänderungen nur als neue Liquibase-Changesets.

Erweiterung 13.11.2026 (Entscheide E-A bis E-D, entschieden von Fabian am 13.11.2026; Leitentscheid
[ADR-014](../../../docs/adr/ADR-014-webseiten-schnittstelle.md)): Die öffentliche Schnittstelle wird zum
lesenden Teil der Webseiten-Schnittstelle, eines versionierten Vertrags für jede Webseite. Sie hat zwei Teile, die getrennte Schlüssel
erreichen: **Lesen** (`/public/v1`, Lese-Schlüssel, jede Seitenansicht; nur dieser Teil heisst «öffentliche
Schnittstelle», Glossar) und **Erfassung** (`/public/v1/erfassung`, Erfassungs-Schlüssel, nur Verwaltung durch die
Geschäftsstelle, sieht wie die Rolle `geschaeftsstelle`). Das WordPress-Plugin ist ein Nutzer dieses Vertrags.
Grundsätze:
- **Nur serverseitig.** CORS bleibt zu (Requirement 5.6); kein Schlüssel im Browser.
- **Das Archiv ruft nie hinaus.** Keine Webhooks, kein Abholen von URLs (kein SSRF); die Webseite fragt den
  Änderungs-Feed ab und sendet Dateien als Bytes.
- **Die Webseite meldet die Stufe, der Schlüssel begrenzt sie.** Das Archiv vertraut der gemeldeten Stufe nur bis zur
  höchsten Stufe des Schlüssels.
- **Gleiche Fachlogik wie die Web-App.** Die Erfassung ruft dieselben Application-Services wie `/api/v1` auf (Upload,
  Bestätigen, Ändern, Versionsprüfung, Protokoll); sie ist ein zweiter Eingang, keine zweite Logik.

```mermaid
sequenceDiagram
  actor B as Besucher (Mitglied)
  participant WP as Webseite VHF/GSK<br/>Plugin urinet-archiv
  participant A as Archiv /public/v1
  participant S3 as Object Storage
  B->>WP: Seite «Merkblätter» (angemeldet)
  WP->>A: GET /public/v1/dokumente?reihe=MERKBLATT<br/>Bearer Lese-Schlüssel · X-Archiv-Zugriff: mitglieder · X-Archiv-Client: <Hash>
  A-->>WP: Liste (Stufen oeffentlich + mitglieder)
  WP-->>B: HTML (Transient-Cache 10 Min.)
  B->>WP: Klick «PDF öffnen» (öffentliches Dokument)
  WP->>A: GET /public/v1/dokumente/{id}/datei
  A-->>WP: 200 application/json {url (5 Min.), gueltigBis}
  WP-->>B: 302 → vorsignierte URL
  B->>S3: PDF laden
  B->>WP: Klick «PDF öffnen» (Dokument der Stufe mitglieder)
  WP->>A: GET /public/v1/dokumente/{id}/datei · X-Archiv-Zugriff: mitglieder
  A->>S3: GET (Versions-ID der PDF/A)
  S3-->>A: Bytes (Blöcke)
  A-->>WP: 200 application/pdf (Stream, no-store)
  WP-->>B: PDF (Stream, private, no-store)
```

Erfassen über die Webseite (Requirement 10):

```mermaid
sequenceDiagram
  actor G as Geschäftsstelle
  participant WP as Webseite (WP-Admin)
  participant A as Archiv /public/v1/erfassung
  participant W as Worker
  G->>WP: Datei und Angaben erfassen (Titel, Reihe, Verband, Stufe …)
  Note over WP: Plugin hält die Angaben je Referenz (Post-Meta)
  WP->>A: PUT /bearbeiter/wp:17 {name} (beim ersten Auftreten oder neuem Namen)
  WP->>A: POST /dokumente (multipart: datei, referenz=wp-123)<br/>Bearer Erfassungs-Schlüssel · X-Archiv-Bearbeiter: wp:17
  A-->>WP: 202 {id, status: NEU} · ETag
  W->>W: VERARBEITEN, KI_VORSCHLAG → EINGANGSKORB
  WP->>A: GET /aenderungen?seit=<cursor>
  A-->>WP: [{id, referenz, status: EINGANGSKORB}]
  WP->>A: GET /dokumente/{id} (Vorschlag, ETag)
  G->>WP: Formular vorbelegt (Angaben der Person vor KI-Vorschlag), Datei ansehen, «Bestätigen»
  WP->>A: POST /dokumente/{id}/bestaetigen · If-Match · X-Archiv-Bearbeiter
  A-->>WP: 202 · ETag (Status ARCHIVIERUNG)
  W->>W: ARCHIVIEREN → ARCHIVIERT
  G->>WP: «Veröffentlichen»
  WP->>A: POST /dokumente/{id}/veroeffentlichen · If-Match · X-Archiv-Bearbeiter
  A-->>WP: 200 · ETag
  Note over A,WP: Lese-Feed meldet SICHTBAR → Plugin verwirft Transients
```

## Components and Interfaces

### Mandant und Branding
- Seed `backend/src/main/resources/seed/vhf-gsk/dokumentarten.json` (Bulletin, Merkblatt, Protokoll, Jahresbericht; Gruppe `frei`; `zusatz_schema` mit `reihe`, `ausgabe`, `jahr`, `fachgebiet`, `zugriff`, `veroeffentlicht`; Aufbewahrung unbefristet; Standard-Vertraulichkeit und `gesetzliche_pflicht` [offen: je Art VHF/GSK – Entscheid vor Welle 10]). Ohne gesetzliche Pflicht keine Object-Lock-Sperre (Spec 01, 6.12).
  Spec 05 ergänzt (Spec 05, Req 1.6, 2.7, 3.1, 5.1; umgesetzt in Spec 05, Aufgabe 17.7, und Aufgabe 10.2): An die Stelle von `dokumentarten.json` tritt `seed/vhf-gsk/konfiguration.json`. Die Reihe ist die Dokumentart, das Zusatzfeld `reihe` entfällt; das Fachgebiet ist die Klassifikation `fachgebiet` (mehrere Werte je Dokument, `oeffentlich_filter`); `ausgabe` und `jahr` bleiben Zusatzfelder mit den Regeln `EINDEUTIG` und `FOLGE`; `zugriff` und `veroeffentlicht` sind reservierte Felder, deren Definition hier fest steht (Abschnitt «Reservierte Felder») und die die Konfiguration nicht anders definieren darf.
- Klassifikation `verband` (Requirement 1.6; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Werte `vhf` («VHF») und `gsk` («GSK»), mehrere Werte je Dokument, `suchfilter` und `oeffentlich_filter` ja, ohne KI-Vorschlag; ein gemeinsames Dokument trägt beide Werte. Öffentlich filterbar über `?verband=vhf` nach der Mechanik von Spec 05, 8.6; der Code `verband` ist kein fester Parameter (Liste unten).
- Stammdaten-Tabelle `vhf-gsk` (Spec 05 ergänzt, Spec 05, Req 6.7): Analog zu Spec 01 design.md, «Stammdaten der Dokumentarten», steht hier die Tabelle der Arten, Klassifikationen und Regeln von `vhf-gsk`, sobald ihre Werte entschieden sind (offener Punkt «je Art VHF/GSK» oben); ab dann vergleicht `KonfigurationSeedTest` den Seed Feld für Feld mit ihr. Die Klassifikationen `fachgebiet` und `verband` gehören in diese Tabelle.
- `WISSENSQUELLE` kommt nicht aus diesem Seed, sondern aus dem Seed-Teil `seed/_alle/dokumentarten-wissen.json` (Spec 04, 1.1); für `vhf-gsk` mit `zugriff` (Pflicht, Standard `mitglieder`) und `veroeffentlicht` (Standard `false`). Spec 05 ergänzt (Spec 05, Req 2.6): Diese Ergänzung für `vhf-gsk` kommt aus der Konfiguration von `vhf-gsk`, nicht aus dem mandantenunabhängigen Seed-Teil.
- Provisionierung wie Spec 01 (Changelog `db/mandant`, Bucket im Object-Storage-Projekt des Archivs, App-Schlüssel `s3_app_zugang`, der nie den Bypass-Header setzt, Spec 01, 6.5; Bucket Policy mit Deny auf `s3:BypassGovernanceRetention` wie in Spec 01, design.md «Ablage»); Schema und Bucket werden in Sicherung, Zweitkopie und Restore-Probe aufgenommen (Requirement 1.5).
- `mandant.branding` = `{name, logoUrl, primaerfarbe, domain}`; `GET /api/v1/branding?domain=` (öffentlich, nur Darstellung).
  Umsetzung (PR #228, Abweichung): Das Verzeichnis liest `archiv.branding.mandanten.<id>.*` aus der Konfiguration (`application.properties`, Domain per Umgebungsvariable), nicht aus den Spalten `mandant.domain` und `mandant.branding`; die Domain von VHF/GSK ist offen und soll nicht im Repo stehen, die UriNet-Domain `archiv.apps.urinet.ch` ist öffentlich und Standard. Die Spalten bleiben ungenutzt, bis ein Entscheid die Quelle wechselt. Die Antwort enthält zusätzlich `sprache` (nur `de-CH`); `textfarbe` berechnet die Web-App aus `primaerfarbe` (besseres Verhältnis aus dunkler und heller Textfarbe). Primärfarben, bei denen keine Textfarbe 4,5:1 (WCAG 2.1 AA) erreicht, fallen auf die Standardfarbe zurück. Logos: nur Pfade auf der eigenen Origin oder geprüfte Daten-URIs (png, jpeg, webp), keine fremden Hosts. Offline zeigt die PWA die zuletzt gültige, erneut geprüfte Antwort aus `localStorage` (je Domain; ohne gespeicherte Antwort das neutrale Standard-Branding). Ein einzelner abschliessender Punkt im Hostnamen wird entfernt (FQDN-Schreibweise).
- Domain: [offen: Domain VHF/GSK – Entscheid vor Welle 10]; zusätzlicher Traefik-Router per Labels in `deploy/infomaniak/compose.yml`, Deploy nur über den GitHub-Actions-Workflow.
- Keycloak (`archiv-auth.apps.urinet.ch`): Client `archiv-vhf-gsk` mit Theme `vhf-gsk`; Benutzer der Geschäftsstelle mit Attribut `mandant=vhf-gsk`, Rolle `geschaeftsstelle` (Rechte nach Rechtematrix Spec 01, 2.6; A11 bis A14 offen, bis dahin 403; A19 der Wissensbasis 403, Spec 04, 12.1).
  Vorschlag Rest von 10.3 (17.11.2026, wartet auf Prüfung von Fabian, nichts ausgerollt): Theme `vhf-gsk` unter `deploy/infomaniak/keycloak/themes/vhf-gsk/login/` (Elterntheme `keycloak.v2`; nur Platzhalter: Textmarke «VHF/GSK Archiv» statt Logo, Primärfarbe `#eeb111` und dunkler Text wie in `archiv.branding.mandanten.vhf-gsk.*`, keine echten Assets), vom `Dockerfile` des Keycloak-Images nach `/opt/keycloak/themes/vhf-gsk` kopiert. Es gilt erst, wenn der Client `archiv-vhf-gsk` im Realm besteht und dort «Login-Theme» = `vhf-gsk` trägt: Der Realm wird nur beim ersten Start importiert (`start.sh`), eine Änderung an `realm-export.json` erreicht den laufenden Realm nicht; Client, Redirect-URI des Hosts und Theme setzt Fabian darum einmal in der Admin-Konsole (die Konsole ist von aussen nicht erreichbar). Die Web-App verwendet bis dahin `archiv-pwa` (Anmeldung auf dem Host von VHF/GSK schlägt ohne diesen Client fehl; offen, mit der Aufgabe für den Mandanten-Client). Router: Labels `archiv-vhf-gsk` und `archiv-vhf-gsk-api` in `deploy/infomaniak/compose.yml` für den Host aus `ARCHIV_HOST_VHF_GSK` (Standard `vhf-gsk.archiv.apps.urinet.ch`, Platzhalter unter der Archiv-Domain; DNS-Eintrag nötig); dieselbe Variable setzt `ARCHIV_BRANDING_VHF_GSK_DOMAIN` der API, damit das Branding dort den Mandanten erkennt. Ohne `/q/` und ohne `/public/` (die Webseiten-Schnittstelle bleibt am Host von UriNet mit eigener Drosselung). Umstellung auf die Verbandsdomain: Wert der Variable ändern; weil die `.env` des Bundles heute nur `ARCHIV_ALARM_EMPFAENGER_URINET` enthält (`schreibe-env.sh`, `.github/`), braucht das eine Änderung des Deploy-Workflows (nicht Teil dieses PR). Rückfall: Labels, Variable und Theme-Kopie entfernen und neu deployen.
- KI-Prompt pro Mandant: Dokumentarten-Beschreibung und Fachgebiet-Liste aus dem Seed; Zugriffsstufe nicht Teil des Vorschlags. Spec 05 ergänzt (Spec 05, Req 8.1, 8.4, 8.9): Prompt und Antwortschema kommen aus der Konfiguration von `vhf-gsk` (Arten mit `ki_vorschlag = ja`, `ki_felder` `ausgabe` und `jahr`, Klassifikation `fachgebiet` mit KI-Vorschlag); `zugriff` und `veroeffentlicht` schlägt die KI nie vor; Vorschläge erst nach Abnahme und KI-Freigabe (umgesetzt in Spec 05, Aufgabe 18.1).

### Reservierte Felder
Massgebliche Definition für Spec 05, 2.7 (`Konfiguration.pruefen` vergleicht jede Art, die diese Felder führt, mit
dieser Tabelle; neu 13.11.2026, E-C):

| Feld | JSON-Typ | Werte | Standard | Pflicht | Gesetzt durch |
| --- | --- | --- | --- | --- | --- |
| `zugriff` | string | `oeffentlich`, `mitglieder`, `vorstand` (Ordnung in dieser Reihenfolge) | `mitglieder` | ja | nur eine Person (Web-App oder Erfassung), nie die KI (Requirement 3.3) |
| `veroeffentlicht` | boolean (JSON `true`/`false`, kein String) | `true`, `false` | `false` | ja | nur eine Person: in der Erfassung nur über Veröffentlichen/Zurückziehen (Requirement 10.5, 10.6; in `PATCH` und Bestätigen der Erfassung 400); in der Web-App über `PATCH`, wirksam erst durch den festen Filter (`ARCHIVIERT`) |

- Domain-Typ `Zugriffsstufe` (`integration/webseite/domain`) mit `OEFFENTLICH < MITGLIEDER < VORSTAND` und
  `bisUndMit()` → Liste der Codes; der Filter nimmt diese Liste, nie einen Wert aus der Anfrage.
- Keine Datenmigration nötig: Werte für `zugriff` gibt es erst ab Welle 10 (Spec 05, Wellen 17/18 vor Welle 10).
- Aufgabe 10.4 (PR #229): Der Eingangskorb und die Metadatenansicht bauen Zusatzfelder aus dem Schema der gewählten
  Dokumentart. `zugriff` startet unabhängig von KI-Vorschlägen bei `mitglieder` (Ausnahme `VORSTANDSPROTOKOLL`: `vorstand`, Entscheid Fabian 17.11.2026); `veroeffentlicht` erscheint erst bei
  archivierten Dokumenten und wird über A6 geändert. `PATCH` protokolliert die Änderung als `AENDERN` und weist
  Veröffentlichung vor bestätigter Archivierung ab. Bei `vhf-gsk` werden Finanzfelder weder angezeigt noch bestätigt;
  finanzielle KI-Vorschläge und ihre Sicherheitswerte werden serverseitig verworfen. Die Geschäftsstelle behält die
  bestehenden Rechte A1–A10; A11–A14 bleiben 403. Aufgabe 11.10 nutzt dieselben Rechte über die Rolle `GESCHAEFTSSTELLE` und `Rechtematrix.ergebnis`.
  Der Feed-Eintrag setzt `zugriff` voraus: ohne gültige Stufe bleibt `stufe_nachher` leer, das Dokument erscheint nicht.
  Der Server prüft `zugriff` und Vertraulichkeit beim Veröffentlichen nicht; er liefert fail-closed nichts aus. Finanzfelder
  sperrt für `vhf-gsk` nur die KI und das Frontend (bekannte Grenze, Spec 05, 18.2).

### Webseiten-Schnittstelle: Anmeldung, Schlüssel, Stufen (`integration/webseite`)
- Bisher `SiteKeyAuthMechanism` (Scope `wordpress`); geändert 13.11.2026 (E-B): Kein eigener Mechanismus. Webseiten-Schlüssel sind API-Tokens nach Spec 01, 2.5 (`ua_<id>_<geheimnis>`, Argon2id, `HashSperre`) mit den neuen Scopes `webseite-lesen` und `webseite-erfassung`; `ApiTokenAuthMechanism` meldet sie an. Die Identität trägt zusätzlich die Attribute `webseite` (Kennung) und `hoechste-stufe`.
- Scope-Bindung (Spec 03 ergänzt Spec 01, design.md, «Umsetzung (Aufgabe 1.5)», Scope-Bindung): Endpunkte der Webseiten-Schnittstelle tragen `@WebseitenZugang(LESEN|ERFASSUNG)` statt `@ErlaubtScope`; `ZugangFilter` lässt eine Identität mit Scope `webseite-*` nur an Methoden mit dem passenden `@WebseitenZugang` durch und jede andere Identität (OIDC, `generator`, `import`) an diesen Methoden nicht (403 `NICHT_ERLAUBT`, Protokoll `VERWEIGERT`). `EndpunktWaechterTest` erweitert: `@WebseitenZugang` nur unter `/public/`, jede Methode unter `/public/` trägt genau `@WebseitenZugang`, und die Regel für `@ErlaubtScope` bleibt unverändert (nur `POST` mit A1).
- Erfassungs-Schlüssel: `Berechtigung` wertet die Rechtematrix für die Rolle `geschaeftsstelle` aus und lässt zusätzlich nur die feste Positivliste `ERFASSUNG_ERLAUBT = EnumSet.of(A1, A2, A3, A4, A5, A6, A7, A8, A10)` zu (sonst 403), auch wenn eine spätere Spec der Geschäftsstelle A11 ff. gibt oder das Enum umsortiert wird (Requirement 10.8; geändert 13.11.2026, bisher `Aktion.ordinal` ≤ A10). `ErfassungRechteTest` (JUnit) prüft die Liste gegen 10.8 und dass jede Aktion darin in der Spalte `geschaeftsstelle` erlaubt ist. Sichtbarkeit wie `geschaeftsstelle` (A2 bis A4).
- Lese-Schlüssel: keine Rolle der Rechtematrix; Zugriff nur über den festen Filter unten.
- Header (Anfrage):

| Header | Teil | Bedeutung |
| --- | --- | --- |
| `Authorization: Bearer ua_…` | alle | Webseiten-Schlüssel |
| `X-Archiv-Zugriff: oeffentlich\|mitglieder\|vorstand` | Lesen | gemeldete Stufe; fehlt → `oeffentlich`; über der Stufe des Schlüssels → 403 `NICHT_ERLAUBT`; unbekannt → 400 (Requirement 5.9); gilt für Liste, Suche, Details, Datei, Filter, Umleitung und Chat; im Feed nicht beachtet (dort gilt die Stufe des Schlüssels) |
| `X-Archiv-Client: <SHA-256(IP + Tages-Salz)>` | Lesen (Chat) | Client-Hash für 6.4, von der Webseite berechnet |
| `X-Archiv-Bearbeiter: <Kennung>` | Erfassung | Kennung der Person auf der Webseite, `^[A-Za-z0-9._:@-]{1,64}$`; Pflicht bei jeder protokollierten Aktion; muss vorher über `PUT /erfassung/bearbeiter/{kennung}` gemeldet sein (Requirement 10.9, 10.12) |
| `If-Match: "<version>"` | Erfassung, ändernd | starkes ETag wie Spec 01 (`*`, `W/"…"` → 412) |

- Header (Antwort): `ETag` (Erfassung), `Retry-After` (429, 503), `Cache-Control: no-store` (alle, Requirement 15.4), `Deprecation`, `Sunset`, `Link: <…>; rel="successor-version"` (abgekündigte Version).
- Schlüsselverwaltung (Requirement 12; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR):
  - `archiv webseite schluessel erstellen <mandant> <webseite> lesen|erfassung --stufe oeffentlich|mitglieder|vorstand [--absender-pruefen]`: Schlüssel nur auf stdout, ID, Mandant, Webseite, Scope und Stufe auf stderr (wie `archiv token erstellen`). Erfassungs-Schlüssel erhalten die Stufe `vorstand` fest (die Geschäftsstelle sieht alle Stufen); die Angabe `--stufe` ist dort nicht erlaubt.
  - Die Kennung der Webseite (1–100 Zeichen) enthält weder Leerraum noch Steuerzeichen noch die Properties-Trennzeichen `: = # ! \`, weil sie als Schlüssel der Absenderdatei dient; ungültig → Exit 2 ohne Ausgabe (Aufruffehler), Fachfehler (z. B. unbekannter oder inaktiver Mandant) → Exit 1 mit Fehlerzeile. Widerruf ist idempotent: ein schon widerrufener Schlüssel ergibt Exit 0.
  - `archiv webseite schluessel rotieren <tokenId> --ueberlappung <ISO-Dauer>` (Standard `P7D`, höchstens `P30D`): neuer Schlüssel mit denselben Angaben; beim alten wird `gueltig_bis` gesetzt; danach 401. Rotiert wird nur ein aktiver Schlüssel ohne Ablauf: Ein abgelaufener oder schon rotierter Schlüssel (`gueltig_bis` gesetzt) ergibt `NICHT_GEFUNDEN`, ein Ablauf wird nie verschoben, und zwei gleichzeitige Rotationen erzeugen keinen zweiten Nachfolger (das `update` ist an `gueltig_bis is null` gebunden).
  - Widerruf über `archiv webseite schluessel widerrufen <tokenId>` (Betreiber, auch für Mandanten ohne `inhaber`) bzw. `DELETE /api/v1/api-tokens/{tokenId}` (A14). `POST /api/v1/api-tokens` kennt nur `generator` und `import` (Schema-Enum), Webseiten-Scopes → 400.
  - Absender-Einschränkung (präzisiert 13.11.2026): Das Kommando setzt nur `absender_pruefen` in `api_client`; die Netze stehen je Webseite (Kennung, nicht Schlüssel-ID) in einer Datei der Betriebskonfiguration auf dem Server (nie im Repository, nie in Logs), die die API beim Start und bei jeder Änderung der Datei neu liest. Weil die Netze an der Webseite hängen, braucht eine Rotation nichts zu übertragen; die Rotation übernimmt `absender_pruefen`. Hat ein Schlüssel `absender_pruefen = true` und die Datei keinen Eintrag für seine Webseite, lehnt das Archiv jede Anfrage ab (geschlossen bei fehlender Konfiguration) und loggt die Schlüssel-ID. Geprüft wird gegen `X-Real-Ip` von Traefik (Spec 01, 10.2) erst nach erfolgreicher Schlüsselprüfung, damit die Antwortzeit nicht verrät, ob eine ID existiert (wie die Attrappe mit gleichem Weg und Timing in Spec 01 design.md, «Sperre gegen Erschöpfen des Heaps»); sonst 401 wie ein ungültiger Schlüssel.
  - Dateiformat der Betriebskonfiguration: UTF-8-Properties, ein Eintrag pro Webseiten-Kennung, z. B. `wordpress=203.0.113.0/24,2001:db8::/32`; Werte sind literale IPv4-/IPv6-CIDR-Netze ohne DNS-Auflösung. `archiv.webseite.absender-datei` benennt die Datei (Standard `/etc/urinet-archiv/absender-netze.properties`). Die API liest sie beim Start und prüft bei Anfragen auf Änderungen; fehlt die Datei, ist sie unlesbar oder enthält sie ungültige Netze, ist die Liste leer und jeder eingeschränkte Schlüssel wird abgelehnt.
  - Prüf-Cache (Requirement 12.8; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR; Spec 03 ergänzt Spec 01, 2.5, das bisher «kein Cache» vorsieht): Nach einer erfolgreichen Argon2id-Prüfung eines Schlüssels mit Scope `webseite-*` merkt sich `ApiTokenPruefung` im Speicher der API je Schlüssel-ID einen HMAC-SHA-256 des Geheimnisses (Schlüssel des HMAC zufällig je Prozessstart, nie gespeichert) mit Ablauf `archiv.webseite.pruef-cache` (Startwert `PT5M`). Jede Anfrage liest weiter die Zeile in `api_client` (`aktiv`, `gueltig_bis`, Mandant aktiv); nur der Argon2id-Vergleich entfällt bei einem Treffer. Der Ablauf ist absolut: Ein Treffer verlängert den Eintrag nicht, spätestens nach `PT5M` folgt wieder ein Argon2id-Vergleich (Re-Verify). Der Cache hält höchstens 1000 Einträge (ein Eintrag je Schlüssel-ID, nur nach erfolgreicher Prüfung gefüllt); ist er voll, werden abgelaufene Einträge entfernt, sonst wird nicht gecacht und die Anfrage nimmt den Weg über die `HashSperre`. Darum wirken Widerruf, Ablauf und Sperre des Mandanten ab der nächsten Anfrage (12.5), auch wenn das Kommando in einem anderen Prozess läuft. Ein falsches Geheimnis trifft nie den Cache und nimmt den normalen Weg über die `HashSperre` (gleiches Timing wie heute). So können fremde Anfragen mit falschen `ua_…`-Werten die Plätze der `HashSperre` zwar belegen, die Webseite mit gültigem Schlüssel aber nicht aussperren (OWASP API4). Für `generator` und `import` bleibt es beim Spec-01-Verhalten ohne Cache. Restrisiko: Nach einem Neustart braucht die erste Anfrage der Webseite einen freien Platz (Open Points).
  - Leistung: Ohne Cache-Treffer rechnet eine Anfrage Argon2id einmal und teilt sich die Plätze der `HashSperre`; ist kein Platz frei, 503 `UEBERLASTET` mit `Retry-After: 1`, und das Plugin zeigt seine Meldung (7.5). Aufgabe 11.7 misst Trefferquote und Wartezeiten unter Last mit fremden Anfragen.
  - Messung 15.11.2026 (`ApiTokenPruefungTest`, synthetischer Hasher, zwei belegte Plätze, zwei gleichzeitige falsche Token und 100 warme Lese-Anfragen): Trefferquote 100/100, p95 und Maximum unter 1 ms (Millisekunden-Auflösung); eine weitere falsche Anfrage erhielt nach 106–111 ms bei 100 ms Wartezeit `UEBERLASTET`. Das misst Cache- und Sperrverhalten, nicht Argon2-Durchsatz oder Produktionskapazität; diese Messung bleibt für Stufe B offen.
  - Umsetzung (Aufgabe 11.7, PR #168, Changeset `010-webseiten-schluessel`):
    - Paketwahl: Die Klassen liegen in `sicherheit` (`WebseitenSchluesselVerwaltung`, `WebseitenAbsenderPruefung`, `WebseitenNetz`, Kommando unter `sicherheit/adapter/in/kommando`), nicht in `integration/webseite`: Schlüssel, Scope und Prüfung gehören zum Zugang (Spec 01, `sicherheit`), `integration/webseite` bleibt dem Vertrag und den Endpunkten (11.6, 11.2, 11.10) vorbehalten und hängt von `sicherheit` ab, nicht umgekehrt.
    - Test-Endpunkt: Die Szenarien laufen gegen die Probe `/public/v1/probe` (nur in `src/test`, Aufgabe 11.6); der Testpfad der Absenderdatei wird im Testprofil nach `target/` umgeleitet, damit kein Test in die Betriebskonfiguration schreibt.
    - Szenarien: `features/sicherheit/webseiten-schluessel.feature` (`@req-12.1`–`@req-12.8`); der Vertrag bleibt in `features/integration/webseiten-vertrag.feature`.
    - Cache-Grenze: 1000 Einträge, absoluter Ablauf (siehe Prüf-Cache); `ApiTokenPruefungTest` prüft beides.
    - Check-Constraint `api_client_webseite_ck`: verlangt `hoechste_stufe is not null` ausdrücklich, weil ein `NULL` in `in (…)` den Check sonst bestehen lässt; `WebseitenSchluesselConstraintTest` belegt es.
    - Absenderdatei: Eine unlesbare Datei schliesst alle eingeschränkten Schlüssel und wird bei Wechsel der Fehlerursache einmal als ERROR geloggt (nicht je Anfrage); weil das Archiv nicht weiss, ob ein Schlüssel auf die Datei zählt, gibt es keine WARN-Stufe.
  - Umsetzung (Aufgabe 11.1, mandantenneutrale Vorstufe; Endpunkte folgen mit 11.2):
    - Pakete: `Zugriffsstufe` und `Lesezugriff` in `integration/domain/webseite` (das Verzeichnis heisst so, nicht `integration/webseite/domain`; ArchUnit verlangt `<fachbereich>/domain|application|adapter`), `FesterFilter` in `integration/application/webseite`, `LeseZugriffFilter` und `LeseAnfrage` in `integration/adapter/in/rest/webseite`. Andere Fachbereiche (Suche, Chat) nutzen `FesterFilter` und `Lesezugriff` über `application` und `domain`.
    - Ablauf: `ZugangFilter` prüft Scope und Mandant, danach liest `LeseZugriffFilter` (Priorität `USER + 100`) bei jedem `@WebseitenZugang(LESEN)` die Header und legt den `Lesezugriff` in das `@RequestScoped`-Objekt `LeseAnfrage`; Endpunkte injizieren es und geben `zugriff()` an Abfragen weiter (ohne gesetzten Zugriff `IllegalStateException`, nie Daten). Der Erfassungsteil und der Feed lesen den Header nicht.
    - `X-Archiv-Zugriff`: fehlt → `oeffentlich`; genau ein Wert, genau einer der drei Codes in Kleinbuchstaben, sonst 400 `ANFRAGE_UNGUELTIG` (auch «Mitglieder», leer, mehrfach, mit Komma; der Wert steht nie in der Antwort); über der Stufe des Schlüssels 403 `NICHT_ERLAUBT` mit Eintrag `VERWEIGERT` im Zugriffsprotokoll (wie jede andere Verweigerung), nie stilles Kappen. Gekappt wird nur der Filter (`Lesezugriff.wirksameStufe()`), das ist bei gültiger Anfrage immer die gemeldete Stufe.
    - `X-Archiv-Client`: optional; genau 64 Hexziffern in Kleinbuchstaben (SHA-256), sonst 400; der Hash kommt ins Protokoll des Chats (11.3), nie die IP.
    - `FesterFilter.bedingung(alias)` liefert das Prädikat `status = 'ARCHIVIERT' and vertraulichkeit = 'NORMAL' and zusatz -> 'veroeffentlicht' = 'true'::jsonb and zusatz ->> 'zugriff' = any(:stufen)`; `FesterFilter.stufen(lesezugriff)` ist der `String[]` für den Parameter `stufen`. Nur ein Dokument mit JSON-`true` zählt; der Vergleich läuft als `jsonb`, weil `->> 'veroeffentlicht' = 'true'` auch den String `"true"` durchliesse (das Szenario mit dem String-Wert war mit `->>` rot und bewies das; Abweichung von der Formulierung `->>` im Abschnitt «Fester Filter»; `"true"` fehlendes Feld, unbekannte Stufe: nicht sichtbar). Der Filter kommt zu den übrigen Filtern hinzu, nie an deren Stelle.
    - 404 bei zu hoher Stufe: Abfragen nach einer ID verwenden denselben Filter; was er nicht liefert, ist 404 `NICHT_GEFUNDEN` und gleicht der Antwort für eine unbekannte ID (kein Hinweis auf die Existenz, Req 5.5). `Lesezugriff.sichtbar(Zugriffsstufe)` dient nur für schon geladene Dokumente.
    - CORS: nicht konfiguriert; das Szenario belegt, dass `GET` und `OPTIONS` mit `Origin` keinen `Access-Control-Allow-Origin` erhalten.
    - Nachweis: `features/integration/zugriffsstufen.feature` (Probe `/public/v1/probe/stufe`, `/probe/dokumente`, `/probe/dokumente/{id}` in `ProbeWebseiteResource`); `public-v1.yaml` unverändert, die Header beschreibt 11.2 mit den Endpunkten (`PublicVertragTest` gleicht Codes und Pfade ab).

### Webseiten-Schnittstelle: Lesen (`/public/v1`, Lese-Schlüssel)

| Methode | Pfad | Zweck | Status |
| --- | --- | --- | --- |
| GET | `/public/v1/dokumente?reihe=&jahr=&<klassifikation>=&seite=&pro_seite=&sortierung=` | Liste veröffentlichter Dokumente bis zur Stufe | 200, 400, 401, 403, 429 |
| GET | `/public/v1/suche?q=&reihe=&jahr=&<klassifikation>=` | Hybridsuche (nur veröffentlicht, Zugriffsstufe); je Treffer `fundstelle {seite}`, soweit bekannt (Requirement 5.7) | 200, 400, 401, 403, 429 |
| GET | `/public/v1/dokumente/{id}` | Details (Felder unten) | 200, 400, 403, 404 |
| GET | `/public/v1/dokumente/{id}/datei` | Stufe `oeffentlich`: 200 `application/json` `{url, gueltigBis}` (5 Min.); Stufen `mitglieder`, `vorstand`: 200 `application/pdf` als Stream (Requirement 13) | 200, 400, 403, 404, 503 `SPEICHER_NICHT_ERREICHBAR` |
| GET | `/public/v1/filter` | Reihen (Code, Name) und Klassifikationen mit `oeffentlich_filter` samt Werten, nur soweit der feste Filter bis zur Stufe Dokumente liefert (Requirement 5.8) | 200, 400, 403 |
| GET | `/public/v1/umleitung?url=` | alte URL (4.3; Pfad ohne Schema und Host, normalisiert wie beim CSV-Import) → `{dokumentId}`, nur für Dokumente, die der feste Filter bis zur Stufe liefert (Requirement 5.10) | 200, 400, 403, 404 |
| POST | `/public/v1/chat` | `{frage, gespraechId?}` → SSE wie Spec 02, Werkzeuge eingeschränkt; Quellen mit `dokumentId`, Titel, Reihe, Ausgabe (Requirement 5.11) | 200, 400, 403, 429 `BUDGET` |
| GET | `/public/v1/aenderungen?seit=&max=` | Änderungs-Feed bis zur Stufe des Schlüssels (Requirement 14.1) | 200, 400, 410 |

Zusätzlich bei allen Endpunkten beider Teile (geändert 13.11.2026; `PublicVertragTest` gleicht diese Codes mit
`public-v1.yaml` ab): 401 `NICHT_ANGEMELDET` (Requirement 15.3), 403 `NICHT_ERLAUBT` (falscher Scope, 12.1),
429 `ZU_VIELE_ANFRAGEN` mit `Retry-After` (15.1; bei `/datei` auch 13.4), 503 `UEBERLASTET` mit `Retry-After: 1`
(volle `HashSperre`).

Antwortfelder der lesenden Endpunkte (Requirement 5.11; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR;
neue Felder sind additiv):

| Endpunkt | Felder |
| --- | --- |
| Liste (`eintraege[]`, dazu `seite`, `proSeite`, `gesamt`) | `id`, `titel`, `reihe {code, name}`, `ausgabe`, `jahr`, `datum` (Erscheinungsdatum, `dokument.datum`, kann fehlen), `klassifikationen` (nur mit `oeffentlich_filter`, je `{code, werte[{code, name}]}`), `zugriff`, `seiten` |
| Suche (`treffer[]`, dazu `degradiert`) | wie Liste, dazu `auszug` (ohne Dateinamen), `fundstelle {seite}` (kann fehlen), `rang` |
| Details | wie Liste, dazu `auszug` |
| Chat-Quellen (SSE-Ereignis `quellen`) | `dokumentId`, `titel`, `reihe {code, name}`, `ausgabe` |

Nie enthalten: Dateiname, Vertraulichkeit, Status, `veroeffentlicht`, Referenzen, Speicher-URLs (ausser der
vorsignierten URL von `/datei` bei Stufe `oeffentlich`) und URLs von Seiten der Webseite.

- Umsetzung (Aufgabe 11.2, mandantenneutrale Vorstufe; Teil 1 mit Liste, Suche, Details, Datei-Link, Filter und Umleitung):
  - Pakete: `WebseitenLesenResource` (`integration/adapter/in/rest/webseite`, Pfad `/public/v1`, Header über `@BeanParam LeseKopf` im Vertrag), Use Cases `WebseiteLesen` (Liste, Details, Filter, Umleitung), `WebseiteSuche` (Hybridsuche) und `WebseiteDatei` (Datei-Link) in `integration/application/webseite`, Port `WebseitenLesePort` mit `SqlWebseitenLese` (`integration/adapter/out/persistence`), Domain-Typen `AlteUrl`, `ListenFilter`, `WebseiteDokument` u. a. in `integration/domain/webseite`. Schemas des Vertrags heissen `Webseite…`, weil die App-OpenAPI Namen wie `Dokument` und `Fundstelle` schon belegt (der Vertragstest liest Code und App in einem Index).
  - Fester Filter in jeder Abfrage: Der SQL-Adapter hängt `FesterFilter.bedingung("d")` und `d.bestaetigt is not null` (eine Art ohne Bestätigung wirkt als `PERSONAL`, Spec 01, 2.21) an jede Abfrage; die Hybridsuche läuft über die neue Sicht `Sichtregel.fuerWebseite(Sichtregel.Webseite(stufen, jahr, ausgabe))`, die `VertraulichkeitFilter.sql` in jede Teilabfrage (Volltext, Vektor, Texte, Details) übersetzt, und `SqlWebseitenLese` lädt die Felder der Treffer unter demselben Filter noch einmal. Die HQL-Variante für die App lehnt die Regel ab (`IllegalStateException`, nie eine zu weite Sicht). `stufen` ist immer `Lesezugriff.stufen()`, nie ein Wert aus der Anfrage; `zugriff`, `veroeffentlicht`, `status`, `mandant` und alle übrigen festen Namen werden nie gelesen, unbekannte Parameter ignoriert.
  - Liste: `reihe` (Code der Dokumentart), `jahr` (vier Ziffern) und `ausgabe` (Zusatzfelder `zusatz.jahr` und `zusatz.ausgabe`, Vergleich als Text, darum passen JSON-Zahl und -String), `seite` ab 1 (Standard 1, höchstens 9999; anders als die 0-basierte `seite` der App, weil die Webseite für Besucher zählt), `pro_seite` 1 bis 100 (Standard 20), `sortierung` `datum_ab` (Standard), `datum_auf`, `titel`, bei Gleichstand nach ID. Ungültige Angaben: 400 `ANFRAGE_UNGUELTIG` ohne den Wert.
  - Suche: `q` Pflicht (höchstens 200 Zeichen), `reihe`, `jahr`, `ausgabe`; `HybridSuche` mit `mitDateiname = false` und höchstens 30 Treffern ohne Blättern; `rang` ist der Platz in der Antwort ab 1, `fundstelle {seite}` nur, wenn die Hybridsuche eine Seite kennt (Aufgabe 11.13: erste Seite des besten Abschnitts, nur wenn sie in `1..dokument.seiten` liegt; im Rückfall und bei Treffern nur aus dem Volltext ohne Fundstelle). Der `auszug` entsteht nur aus dem Text des Dokuments (`ts_headline` ohne Markierung, höchstens 300 Zeichen), nie aus Dateiname, Partnername oder Tags; die Teile B (Partner, Tags) der Suche zählen für den Treffer mit (Restrisiko: ein Suchwort, das nur im Partnernamen eines veröffentlichten Dokuments steht, findet es, ohne dass die Antwort den Namen nennt). Der Index für die Suche ohne Dateinamen besteht seit Changeset 028 (Spec 02, Aufgabe 7.1a); 11.2 legt keinen weiteren an.
  - Details: wie die Liste, dazu `auszug` (Anfang des Textes, höchstens 300 Zeichen, fehlt ohne Text). Ungültige ID und nicht sichtbares Dokument sind beide 404 `NICHT_GEFUNDEN` und gleichen einer unbekannten ID.
  - Datei-Link: nur für Dokumente der Stufe `oeffentlich`: `DokumentDatei.herunterladen` mit der Sicht «fester Filter mit der einen Stufe `oeffentlich`» (liegt in jeder gemeldeten Stufe, ein höheres Dokument bleibt unsichtbar), PDF/A mit gespeicherter Versions-ID, `archiv.ablage.url-gueltigkeit` (5 Minuten), Dateiname der Adresse nur aus ID und Rolle. Jedes andere Dokument war bis Aufgabe 11.8 404 `NICHT_GEFUNDEN`; seit 11.8 streamt die API es (Abschnitt «Dateiauslieferung und Stream»), 404 bleibt für alles, was die Anfrage nicht sehen darf. Protokoll `HERUNTERLADEN` in derselben Transaktion nach dem Signieren, mit Kennung des Schlüssels (`token:<id>`), Dokument-ID und der IP aus `X-Real-Ip` gekürzt (`IpAdresse.gekuerzt()`: IPv4 ohne letztes Byte, IPv6 auf 48 Bit), ohne Rolle, ohne Client-Hash und ohne Header-Werte; 404 schreibt keinen Eintrag. Die Antwort ist `{url, gueltigBis}`; `toString` nennt die Adresse nie.
  - Filter: `{reihen: [{code, name}]}`, nach Name sortiert: aktive Dokumentarten mit mindestens einem Dokument, das der feste Filter bis zur Stufe liefert. Die Klassifikationen mit `oeffentlich_filter` (Spec 05, Req 8.6) fehlen, weil Spec 05 im Code noch nicht besteht; das Feld kommt additiv, sobald es sie gibt.
  - Umleitung: `?url=` (Pfad oder URL, bis 2000 Zeichen) → `AlteUrl.normalisieren` (nur Pfad, ohne Schema, Host, Anfrage und Fragment, Prozent-Kodierung aufgelöst, NFC, doppelte Schrägstriche zusammengefasst, ohne abschliessenden Schrägstrich, Gross- und Kleinschreibung bleibt; ungültig 400) → Tabelle `alte_url(url text primary key, dokument_id uuid not null references dokument)` (Changeset `031-alte-url`; die Nummer 030 bleibt frei für parallele Aufgaben, die Tabelle füllt der Import in Aufgabe 10.5 mit derselben `AlteUrl`) → `{dokumentId}` nur, wenn das Dokument der feste Filter bis zur Stufe liefert, sonst 404.
  - Fehler: 400, 401, 403 (nach `LeseZugriffFilter`), 404 (Details, Datei, Umleitung) und 503 `UEBERLASTET` stehen im Vertrag je Endpunkt; 429 `ZU_VIELE_ANFRAGEN` kommt mit den Grenzen (11.11 umgesetzt, Abschnitt «Grenzen»; 11.8 für Streams), die Drosselung je IP mit Traefik (11.6, nicht hier). Die Suche verbraucht Embedding- und Reranker-Tokens (Zweck `SUCHE`, ohne Limit, Spec 02, Req 2.8); die Grenze je Schlüssel (strenger für die Suche) setzt 11.11 durch.
  - Entschieden von Fabian am 16.11.2026: `seite` 1-basiert (in Version 1 fest); Datei-Link nur `oeffentlich`, `mitglieder`/`vorstand` 404 bis 11.8 (11.8 vor dem Plugin-Download 11.5); Suche über Partner und Tags (Gewicht B) angenommen; 11.11 und 11.6 vor dem Livegang der Webseite von VHF/GSK, bis dahin nur Urinet und Testmandanten.
  - Nachweis: `features/integration/webseiten-lesen.feature` (`@req-5.1`, `5.2`, `5.3`, `5.4`, `5.5`, `5.8`, `5.9`, `5.10`, `5.11`, `15.3`, `15.4`) mit je einer Filterachse als eigenem Beispiel gegen alle Endpunkte; JUnit `AlteUrlTest`, `IpAdresseTest`, `VertraulichkeitFilterWebseiteTest`, `PublicVertragTest` (Code = Datei, Gegenprobe nur auf ihren Pfad beschränkt).
- Fester Filter in allen Abfragen (geändert 13.11.2026, E-C; bisher `(zusatz->>'zugriff'='oeffentlich' OR :mitglied)`): `status='ARCHIVIERT' AND vertraulichkeit='NORMAL' AND zusatz -> 'veroeffentlicht' = 'true'::jsonb AND zusatz->>'zugriff' = ANY(:stufen)` mit `:stufen = min(gemeldete Stufe, Stufe des Schlüssels).bisUndMit()` (Requirement 3.4, 3.6, 3.7, 5.3). Seit 11.1 läuft der Vergleich als `jsonb` (siehe «Umsetzung (Aufgabe 11.1)»): Nur JSON-`true` zählt. `->> 'veroeffentlicht' = 'true'` liesse den String `"true"` durch und darf nicht kopiert werden; `ZUSATZ_UNGUELTIG` genügt als Sperre nicht, weil sie nur beim Schreiben greift, nicht bei bestehenden Zeilen.
- `/datei`: Der Medientyp der Antwort hängt von der Stufe des Dokuments ab; die Details liefern `zugriff`, damit die Webseite vorab weiss, was kommt. Die Variante ist die PDF/A-Fassung mit gespeicherter Versions-ID wie Spec 01, 14.6.
- Klassifikationen (Spec 05 ergänzt, Spec 05, Req 8.6; umgesetzt in Aufgaben 11.2 und 11.5): Die Liste filtert zusätzlich nach den Codes der Klassifikationen mit `oeffentlich_filter` (z. B. `?fachgebiet=bad`, `?verband=gsk`) und gibt nur diese Klassifikationen aus; der feste Sicherheitsfilter oben bleibt unverändert davor. Feste Parameter, die kein Klassifikations-Code sein dürfen: `q`, `reihe`, `jahr`, `ausgabe`, `seite`, `pro_seite`, `id`, `sortierung`, `gespraech_id`, `zugriff`, `veroeffentlicht`, `seit`, `max`, `referenz`, `groesse`, `status`, `url` (die letzten sechs ergänzt 13.11.2026, weil Feed, Umleitung und Erfassung sie im selben Vertrag nutzen; Spec 05 design.md führt dieselbe Liste).
- Fundstelle (Requirement 5.7; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Spec 03 ergänzt Spec 02, Aufgabe 6.3: `chunk` erhält `seite_von` und `seite_bis` (1-basiert), die der `Chunker` über `Seitentext.von(volltext)` aus Spec 04 design.md bestimmt; beide sind `null` ohne Seitentrenner (z. B. bei `pdfa_fehlt`) und wenn die Zahl der `\f`-Seiten nicht `dokument.seiten` entspricht (dieselbe Plausibilitätsprüfung wie Spec 04, sonst zeigte `#page=` auf eine falsche Seite). Umgesetzt mit Spec 02, Aufgabe 6.3 (Changeset 021, `Chunker`, `Seitentext` in `gemeinsam/domain`); Aufgabe 11.13 ist umgesetzt: `HybridSuche` gibt den Abschnitt mit, `WebseiteSuche` prüft die Seite gegen `dokument.seiten` (sonst keine Fundstelle). Spec 03 ergänzt Spec 02, Aufgabe 6.4: `HybridSuche` gibt je Dokument den besten Chunk mit; die öffentliche Suche liefert `fundstelle: {seite: seite_von}`. Treffer nur aus dem Volltext haben keine Fundstelle. Das Plugin verlinkt auf die Download-Route mit `#page=<n>`. Der Parameter `seite` bleibt die Blätterung; die PDF-Seite heisst im Vertrag immer `fundstelle.seite`.
- Dateiname (Spec 01 ergänzt, Req 3.13; Review #104): Suche, Details mit Auszug und öffentlicher Chat nutzen die Suche ohne Dateinamen (`mitDateiname=false`) und geben das Feld `dateiname` nicht weiter; das gilt für die ganze Webseiten-Schnittstelle einschliesslich Erfassung (Requirement 10.11). Diese Variante prüft über `ts_filter(volltext_tsv, '{a,b,c}')`, was den GIN-Index nicht nutzt; der Index besteht (Changeset 028, Spec 02, Aufgabe 7.1a); Aufgabe 11.2 legt keinen an (Spec 01, design.md, «Umsetzung 4.10»).
- `OeffentlicherChatAgent`: eigener `@RegisterAiService` mit `OeffentlicheWerkzeuge` (`dokumente_suchen`, `dokument_zeigen`), Systemprompt mit Verbandsname, Fachgebieten und Hinweis auf Geschäftsstelle; Gesprächsspeicher 6 Nachrichten. Die Werkzeuge erhalten `:stufen` aus der Anfrage, nie aus dem Prompt.
- Rate-Limit: 20 Fragen/h pro Client-Hash; Token-Budget pro Mandant und Monat aus `mandant_budget(monat, tokens_max, tokens_verbraucht)`. Die Wissensbasis führt ein eigenes Monatsbudget in `m_<code>` und belastet `mandant_budget` nicht, damit ein Ingest den Chatbot nicht sperrt und umgekehrt (Spec 04, 13.6). Dazu die Grenzen je Schlüssel (Abschnitt «Grenzen»).
- Umsetzung (Aufgabe 11.3, öffentlicher Chatbot; Szenarien `features/integration/oeffentlicher-chat.feature`, `@req-6.1`–`@req-6.5`):
  - Pakete: Endpunkt `OeffentlicherChatResource` in `integration/adapter/in/rest/webseite` (nutzt `LeseAnfrage`; ein Fachbereich greift nur über `application` auf einen anderen, darum liegt der Dienst in `ki`), Dienst `OeffentlicheFragen` und die Werkzeug-Dienste in `ki/application/oeffentlich`, Werkzeuge `OeffentlicheWerkzeuge` in `ki/adapter/in/werkzeug`, Agent `OeffentlicherAgent`, `OeffentlicherToolProvider` und `LangChain4jOeffentlicherChatAgent` in `ki/adapter/out/langchain4j` (eigener `@RegisterAiService`, dasselbe `ChatModell`, `AgentSpeicher` und `AgentLaeufe` wie der interne Chat; `AgentLauf` erhielt einen zweiten Konstruktor ohne `ChatAnfrage`, `DokumentZeigen.einzeilig(wert, max)` ist öffentlich). Es gibt keine Verbindung zu `ChatFragen`, `ArchivWerkzeuge`, `WerkzeugKontext` oder zur Rolle: Der öffentliche Chat kennt weder Benutzer noch Sichtregel der App.
  - Eingeschränkte Werkzeuge: Der Agent erhält genau `dokumente_suchen` (Parameter `frage` ≤ 200 Zeichen, `reihe` = Code der Dokumentart, `anzahl` 1–5) und `dokument_zeigen` (Gesprächsnummer oder ID); alles andere (auch `kennzahl`, `fristen`, erfundene Namen) beantwortet `UnbekanntesWerkzeug`. Kein Parameter nennt Stufe, Mandant oder Rolle; zusätzliche Parameter des Modells (`zugriff`, `stufen`, `mandant`) ignoriert die Bibliothek. Mandant und `Lesezugriff` stehen im `OeffentlicherKontextRegister` unter der Lauf-ID, gesetzt aus Schlüssel und Header, nie aus dem Prompt (Requirement 5.1 sinngemäss).
  - Zwei Schichten für «nur freigegebene Dokumente»: (1) Die Suche nimmt die Sicht der Webseite `Sichtregel.fuerWebseite(new Sichtregel.Webseite(stufen, null, null))`, das gemeinsame Modell mit 11.2: `VertraulichkeitFilter.sql` baut daraus die Sicht (nur bestätigte `NORMAL`) und den festen Filter mit den Stufen; `SuchFilter` bleibt unverändert und gilt für Volltext und alle Datenbankteile der Hybridsuche. Die HQL-Variante (`VertraulichkeitFilter.fuer`) wirft bei dieser Regel (fail closed). (2) Jeder Treffer, jedes angezeigte Dokument und jede Quelle geht danach nochmals über `OeffentlicheDokumente` (`FesterFilter` mit denselben Stufen); nur was dort sichtbar ist, erreicht das Modell oder die Webseite. Ein Dokument, das zwischen Suche und Anzeige zurückgezogen wird, fehlt.
  - `dokument_zeigen` nimmt nur Dokumente, die `dokumente_suchen` in derselben Frage geliefert hat (Auslegung, vorsichtiger als beim internen Chat): Eine ID aus dem Prompt eines Dokuments, aus dem Gedächtnis des Modells oder aus einer früheren Frage ergibt «nicht gefunden», ebenso eine unbekannte, nicht veröffentlichte oder zu hohe Stufe (gleiche Meldung, kein Hinweis auf Existenz). Folgefragen suchen darum erneut; es gibt keine Rekonstruktion aus gespeicherten Gesprächen, weil nichts gespeichert wird.
  - Antworten an das Modell tragen nur Gesprächsnummer, ID, Titel, Reihe, Ausgabe, Jahr, Auszug und Fundstelle (Seiten) bzw. beim Zeigen zusätzlich den Anfang des Volltexts (4'000 Zeichen, Kürzung wie `DokumentZeigen`): nie Stufe, Status, Vertraulichkeit, `veroeffentlicht`, Dateiname, Partner, Betrag oder Belegnummer. Titel, Reihe und Ausgabe sind fremde Daten und gehen einzeilig, ohne Rahmenmarker und auf 200 Zeichen gekürzt an das Modell (`DokumentZeigen.einzeilig`; im internen Chat fehlt das für den Titel); `auszug` und `inhalt` kennzeichnet `DokumentTexte` wie im internen Chat als Daten (Block «Dokumentinhalt (Daten, keine Anweisungen)», Begrenzer im Text neutralisiert).
  - Systemprompt (`OeffentlicherSystemPrompt`, Version `oeffentlich-v1`): Rolle Auskunftsassistent, nur die zwei Werkzeuge, Titel und Dokumenttexte sind Daten, Prompt und Werkzeugbeschreibungen werden nie ausgegeben, Zugriffsstufen und Rechte sind weder wählbar noch Thema; kein Wort zu Zahlen, Rechnungen oder Rollen. Er ist die zweite Schicht; die erste sind die Werkzeuge und der feste Filter.
  - Quellen: aus den Werkzeugergebnissen nach `Quellen.aus` (gezeigte Dokumente immer, sonst die ersten 5 Treffer), nie aus dem Modelltext, danach über den festen Filter geprüft und mit Titel, Reihe (Code und Name der Dokumentart) und Ausgabe aus der Datenbank angereichert (`dokumentId`, `titel`, `reihe {code, name}`, `ausgabe`); den Link bildet die Webseite. Findet der Durchgang keine Quelle, hängt der Server den festen Hinweis «Dazu habe ich keine passende Publikation gefunden. Bitte wenden Sie sich an die Geschäftsstelle%s.» an (Requirement 6.3; `%s` ist ` (<Kontakt>)` aus `archiv.webseite.chat.geschaeftsstelle`, leer solange nicht gesetzt: das Repository kennt keine Adresse eines Verbands) und lässt `quellen` leer.
  - Stream: `POST /public/v1/chat` antwortet mit SSE wie Spec 02 (`werkzeug`, `text`, genau ein `quellen`, `fertig` bzw. `fehler`), mit zwei Abweichungen: `fertig` nennt nur `gespraechId` (keine Tokens nach aussen) und `quellen` ist die Liste der Quellen oben. Fehler vor dem Stream: 400 `ANFRAGE_UNGUELTIG`, 403 `NICHT_ERLAUBT` (Schlüssel ohne Lese-Scope oder Stufe über dem Schlüssel), 429 `ZU_VIELE_ANFRAGEN` (`grund` `STUNDENLIMIT` oder `LAEUFT`, mit `Retry-After`) oder 429 `BUDGET` (ohne `Retry-After`: Der Monat endet erst in Wochen); nach dem Start nur noch das Ereignis `fehler` mit festem Text («Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.»), nie eine Ausnahmeklasse, URL oder ein HTTP-Status des Modells. Die Quellen-Prüfung scheitert geschlossen (`fehler`, nie ungeprüfte Quellen).
  - Eingabe: `frage` nach Trim 1 bis `archiv.webseite.chat.max-frage` (Standard 500) Zeichen ohne Steuerzeichen (Zeilenumbruch und Tabulator erlaubt); `gespraechId` optional. Fehler nennen nie den Wert.
  - Gespräch: Verlauf nur im Speicher der Instanz (`OeffentlicheGespraeche`): höchstens 6 Nachrichten (`archiv.webseite.chat.fenster`), höchstens 30 Minuten ab der letzten Frage, höchstens 5'000 Gespräche; es gehört zu Mandant, Webseite, Client-Hash und wirksamer Zugriffsstufe (Nachbesserung Review: bei einem Stufenwechsel beginnt still ein neues Gespräch, sonst gäbe der Verlauf Antworttexte einer höheren Stufe an das Modell der niedrigeren weiter). Eine unbekannte, fremde oder abgelaufene `gespraechId` beginnt still ein neues Gespräch (kein Orakel). Es gibt keine Tabelle für Gespräche und keinen Eintrag im internen Chat (`chat_gespraech`, `chat_nachricht`).
  - Grenzen im Speicher der Instanz (`OeffentlicheGrenzen`, getrennt von `ChatGrenzen`, damit der öffentliche Chat den internen nie verdrängt): 20 Fragen je Stunde und Client-Hash (`archiv.webseite.chat.fragen-je-stunde`), dazu 600 je Stunde und Webseite (`…-schluessel`, Startwert: Wer den Hash wechselt, umgeht sonst die Grenze je Client; ohne `X-Archiv-Client` zählen alle Besucher der Webseite zusammen), eine laufende Frage je Client und 4 gleichzeitig insgesamt. Eine abgelehnte Frage zählt in keiner Grenze; Reihenfolge der Ablehnungen: 400, `BUDGET`, `ZU_VIELE_ANFRAGEN`. Der Hash steht nur im Speicher, nie in einer Tabelle.
  - Budget: `public.mandant_budget(mandant, monat, tokens_max, tokens_verbraucht)` (Changeset `public/012`, Monat = erster Tag in `Europe/Zurich`, Fremdschlüssel auf `mandant`, App-Rolle `select, insert, update`). Ohne Zeile gilt `archiv.webseite.chat.monatsbudget-tokens` (Startwert 2'000'000, Entscheid Fabian offen); der Betreiber legt je Mandant eine Zeile mit eigenem `tokens_max` an (Wert 0 sperrt den Chat). `JdbcMandantBudget.verbrauchen` ist ein einziges `insert … on conflict do update`; der Adapter bucht jeden Modellaufruf (Werkzeug-Runden und Schlussantwort) sofort, auch wenn der Durchgang später scheitert, und schreibt wie der interne Chat eine Zeile `ki_verbrauch` mit Zweck `CHAT`. Geprüft wird vor der Frage (`verbraucht ≥ max` → 429 `BUDGET`); gleichzeitige Fragen können das Budget um höchstens die Kosten der laufenden Fragen überschreiten (begrenzt durch die Gleichzeitigkeit). Ein Tagesbudget gibt es nicht (das Design nennt nur den Monat; Burst-Schutz sind die Grenzen je Client und je Webseite). Das Budget der Wissensbasis bleibt getrennt (Spec 04, 13.6). `tokens_max` wird beim ersten Verbrauch des Monats aus der Konfiguration in die Zeile übernommen und gilt dann bis Monatsende (Absicht: Die Zeile ist die Obergrenze des Monats und lässt sich je Mandant per SQL anpassen); eine geänderte Konfiguration greift darum erst ab dem nächsten Monat.
  - Speicherung (Requirement 6.5): `oeffentliche_frage(id, frage, quellen, tokens, zeit)` in jedem Mandanten-Schema (Changeset `mandant/030`, nicht nur `m_vhf_gsk`: mandantenneutral): die bereinigte Frage (`FrageBereinigung`: E-Mail, Telefon, IBAN und andere lange Zifferfolgen, Web- und IP-Adressen, Steuerzeichen entfernt, auf 500 Zeichen gekürzt), die geprüften Quellen (ID, Titel, Reihe, Ausgabe) und die Tokens der Frage. Es gibt keine Spalte für Client-Hash, IP, Webseite, Schlüssel, Antwort oder Gespräch; `X-Real-Ip` liest der öffentliche Chat nicht. Namen in einer Freitextfrage erkennt kein Verfahren; darum die kurze Aufbewahrung: Standard 90 Tage (`archiv.webseite.chat.aufbewahrung-tage`), je Mandant einstellbar mit `archiv.mandant.<code>.chat-aufbewahrung-tage` (Muster von `eigene-arten`; gültig 1 bis 365, sonst Startabbruch ohne den Wert zu nennen; für `vhf-gsk` 30 Tage laut Zusage an den Verband, entschieden von Fabian am 16.11.2026) mit dem täglichen Löschjob `OeffentlicheFragenAufraeumer` (Worker, 03:40 Zürich; `OeffentlicheFragenAufraeumen` liest die Frist je Mandant)), nur über aktive Mandanten (Annahme: Der Abbau eines Mandanten löscht sein Schema samt Fragen; ein inaktivierter Mandant mit bestehendem Schema bleibt bis zur Reaktivierung unberührt). Eine nicht speicherbare Frage verhindert die Antwort nicht (Log nur mit Klassennamen).
  - Fehlercodes: `BUDGET` (429) neu in `Fehlercode` und in der Tabelle von Spec 01 design.md; `ZU_VIELE_ANFRAGEN` war dort schon eingetragen (Chat und Spec 03). Der Vertrag `docs/schnittstelle/public-v1.yaml` enthält den Endpunkt (`/public/v1/chat`), die Header `X-Archiv-Zugriff` und `X-Archiv-Client`, das Schema `ChatFrage`, die Antwort `ZuVieleAnfragen`, die Werte `ZU_VIELE_ANFRAGEN` und `BUDGET` bei `fehlercode` und das optionale Feld `grund`; `PublicVertragTest` vergleicht Code und Datei. Die Gegenproben des Tests vergleichen seither nur ihren eigenen Pfad `/public/v1/gegenprobe`, weil es echte Endpunkte gibt.
  - Betrieb: Das Profil `dev` (`archiv.chat.stub=true`) hat für den öffentlichen Chat keinen Stub: Ohne Token des Sprachmodells antwortet er mit `fehler`. Gespräche und Zähler gehen bei einem Neustart verloren (Hinweis: eine Instanz, wie bei den Grenzen).
  - Entschieden von Fabian am 16.11.2026: Monatsbudget 2'000'000 Tokens je Mandant, Grenze 600 Fragen/h je Webseite, ohne `X-Archiv-Client` ein gemeinsamer Zähler, Aufbewahrung je Mandant einstellbar (Standard 90, VHF/GSK 30 Tage). Offen als Hinweis, nicht blockierend: der Kontakt der Geschäftsstelle von VHF/GSK (`archiv.webseite.chat.geschaeftsstelle`, heute eine Eigenschaft der Installation) ist noch einzuholen.

### Webseiten-Schnittstelle: Erfassung (`/public/v1/erfassung`, Erfassungs-Schlüssel)
Neu 13.11.2026 (Requirement 10; entschieden von Fabian am 13.11.2026, E-A). Alle Endpunkte delegieren an dieselben
Application-Services wie `/api/v1` (Spec 01 design.md, Endpunkt-Tabelle); Reihenfolge der Prüfungen wie dort
(sichtbar 404 → Recht 403 → `If-Match` 428/412 → Status 409 → Inhalt 400/422).

| Methode | Pfad | Zweck | Aktion / Protokoll | Status |
| --- | --- | --- | --- | --- |
| POST | `/erfassung/dokumente` | `multipart/form-data`: `datei` (genau eine), `referenz` (Pflicht, `^[A-Za-z0-9._:-]{1,100}$`), `hinweis` (optional, ≤ 1'000 Zeichen) → 202 `{id, status, referenz}` mit `Location` und `ETag`; 200 bei gleicher Referenz und Datei oder bei Dublette (`dublette: true`, `id` nur, wenn sichtbar) | A1 / `ERFASSEN` | 202, 200, 400, 409 `REFERENZ_VERGEBEN`, 413, 415, 422, 503 `SPEICHER_NICHT_ERREICHBAR` |
| GET | `/erfassung/dokumente?referenz=` | Status zu einer Referenz der eigenen Webseite (nur Statusfelder: `id`, `referenz`, `status`, `fehlercode`, `version`); Referenz einer anderen Webseite → 404 | A2–A4 / keine (Liste) | 200, 400, 404 |
| GET | `/erfassung/dokumente?status=&reihe=&jahr=&veroeffentlicht=&q=&seite=&groesse=` | Liste der sichtbaren Dokumente wie Spec 01 `GET /dokumente` (Volltextsuche ohne Dateinamen, `groesse` 1–100), je Eintrag `id`, `titel`, `reihe`, `ausgabe`, `jahr`, `status`, `zugriff`, `veroeffentlicht`, `version`, `referenz` (nur eigene Webseite, sonst `null`) (Requirement 10.13; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR); `referenz` zusammen mit anderen Filtern → 400 | A2–A4 / keine (Liste) | 200, 400 |
| GET | `/erfassung/dokumente/{id}` | Status, Metadaten, Klassifikationen, KI-Vorschlag, `zugriff`, `veroeffentlicht`, `fehlercode`, `referenz` (nur eigene Webseite); `ETag` | A2–A4 / `ANZEIGEN` | 200, 400, 404 |
| GET | `/erfassung/dokumente/{id}/datei` | Stream wie Requirement 13 (PDF/A, solange sie fehlt das Original; nie eine URL), gleiche Plätze und Zeitlimits (Requirement 10.14; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR) | A2–A4 / `HERUNTERLADEN` | 200, 400, 404, 503 `SPEICHER_NICHT_ERREICHBAR` |
| GET | `/erfassung/eingangskorb?seite=&groesse=` | wie Spec 01 `GET /eingangskorb` (`groesse` 1–100) | A5 / keine | 200, 400 |
| GET | `/erfassung/dokumentarten` | aktive Arten mit Feldern und Klassifikationen (Formular der Webseite) | wie Stammdaten lesen | 200 |
| PUT | `/erfassung/bearbeiter/{kennung}` | `{name}` (1–200 Zeichen) → Eintrag im Benutzerverzeichnis, wenn neu oder Name geändert (Requirement 10.12; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR) | keine Aktion der Matrix / kein Protokolleintrag (das Verzeichnis selbst ist nur anfügend) | 204, 400 |
| POST | `/erfassung/dokumente/{id}/bestaetigen` | wie Spec 01 `POST /dokumente/{id}/bestaetigen`, `If-Match`; `dateiname` und `zusatz.veroeffentlicht` → 400 | A7 (A8) / `BESTAETIGEN` | 202, 400, 404, 409, 412, 422, 428 |
| PATCH | `/erfassung/dokumente/{id}` | wie Spec 01 `PATCH /dokumente/{id}`, `If-Match`; bei `ARCHIVIERT` `grund` Pflicht; ohne `person_benutzer`, `dateiname` und `zusatz.veroeffentlicht` (400) | A6 (A8) / `AENDERN` | 200, 400, 404, 409, 412, 422, 428 |
| POST | `/erfassung/dokumente/{id}/veroeffentlichen` | `veroeffentlicht = true`, nur `ARCHIVIERT`, `If-Match`; fester Grund «über Webseite veröffentlicht» | A6 / `AENDERN` | 200, 400, 404, 409, 412, 428 |
| POST | `/erfassung/dokumente/{id}/zurueckziehen` | `veroeffentlicht = false`, nur `ARCHIVIERT`, `If-Match`; fester Grund «über Webseite zurückgezogen» | A6 / `AENDERN` | 200, 400, 404, 409, 412, 428 |
| POST | `/erfassung/dokumente/{id}/wiederholen` | wie Spec 01 «erneut verarbeiten» bzw. Archivierung wiederholen, nur `FEHLER` und `ARCHIVIERUNG_FEHLER` (Requirement 10.8; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR) | A10 / wie Spec 01 | 202, 400, 404, 409 |
| GET | `/erfassung/aenderungen?seit=&max=` | Änderungs-Feed des Mandanten (Requirement 14.2) | keine | 200, 400, 410 |

(Pfade relativ zu `/public/v1`; zusätzlich die Codes für alle Endpunkte unter der Tabelle «Lesen». 400 bei fehlendem,
ungültigem oder nicht gemeldetem `X-Archiv-Bearbeiter` gilt für jede Zeile mit Protokoll, Requirement 10.9.)
- Kein `DELETE`, kein Verwerfen (Requirement 10.7, 10.8); ein `DELETE` beantwortet das Framework mit 405 (`ANFRAGE_UNGUELTIG` mit Status 405 nach Spec 01 design.md, Error Handling).
- Referenzen nur der eigenen Webseite: Jeder Zugriff auf `webseite_referenz` (Upload, Statusabruf, Liste, Details, Erfassungs-Feed) filtert auf `webseite` = Webseite des Schlüssels; fremde Referenzen erscheinen als `null` bzw. 404 (Requirement 9.4, 10.4).
- Hintergrundabrufe: Das Plugin fragt ohne angemeldete Person nur den Erfassungs-Feed und den Statusabruf über die Referenz ab; beide schreiben kein Protokoll und brauchen keinen Bearbeiter.
- Quelle `WEBSEITE` (Spec 03 ergänzt Spec 01, design.md, Upload, `quelle`): neues Changeset unter `db/mandant` erweitert den Check `dokument_quelle_ck`; bestehende Changesets bleiben unverändert.
- Grenzen des Uploads wie Spec 01 (`archiv.upload.max-datei` 50 MB, `Content-Length` Pflicht); die Datei geht wie beim Upload nach `archiv_arbeit` und in den Bucket, nie in den Heap.
- Idempotenz (Requirement 10.3; geändert 13.11.2026, bisher «der Primärschlüssel entscheidet»): Tabelle `webseite_referenz`; Ablauf in einer Transaktion, die als Erstes `pg_advisory_xact_lock` auf einen Hash von (Webseite, Referenz) setzt, noch vor dem Lesen der Referenz und vor der Dublettenprüfung (wie die Sperre auf die Prüfsumme in Spec 01): Referenz der eigenen Webseite suchen → vorhanden und Dokument nicht `VERWORFEN`: gleiche SHA-256 → 200, sonst 409 `REFERENZ_VERGEBEN`; vorhanden mit `VERWORFEN` oder nicht vorhanden: Upload wie Spec 01 (Dublettenprüfung per SHA-256, die verworfene Dokumente übergeht → 200 mit `dublette: true` und Bindung der Referenz an das vorhandene Dokument), dann Referenz einfügen bzw. neu binden. Ein zweiter gleichzeitiger Upload derselben Referenz wartet auf die Sperre, liest danach die Referenz und entscheidet nach denselben Regeln (gleiche Datei → 200, andere → 409). Die Abbildung von `DUBLETTE` (Spec 01, 202 mit Ergebnis je Datei) auf 200 mit `dublette: true` geschieht in der Erfassung, nicht im Upload-Service; `id` nur, wenn der Schlüssel das vorhandene Dokument nach Spec 01, 2.3 sehen darf, sonst bleibt die Referenz ungebunden.
- Protokoll (Requirement 10.9, 10.12; Spec 03 ergänzt Spec 01, 10.2 und 10.10): `benutzer = token:<id>`, `rolle = geschaeftsstelle`, neue Spalte `bearbeiter` mit `<webseite>/<Header-Wert>` (z. B. `vhf-gsk-web/wp:17`); dieselbe Kennung steht in der neuen Spalte `dokument_version.bearbeiter` der Fassung (Spec 01, 10.1) und als `benutzer` im Benutzerverzeichnis. `PUT /erfassung/bearbeiter/{kennung}` fügt dort `{benutzer: <webseite>/<kennung>, name, rolle: geschaeftsstelle, gueltig_von: jetzt}` ein, wenn die Kennung neu ist oder der Name abweicht; einen Austritt erfasst wie bei Benutzern ein neuer Eintrag mit «gültig bis» (Spec 01, 10.10). Logs nennen nur die Schlüssel-ID. Der Bearbeiter ist eine Angabe der Webseite; das Archiv kann sie nicht prüfen, die Webseite verantwortet sie mit ihrem Schlüssel (ADR-014, Folgen); die Zuordnung zur Person bleibt aber im Archiv, auch wenn die Webseite ihre Benutzer löscht.
- Fehlercodes im Status: `fehlercode` aus dem Feld `fehler` der Verarbeitung, nur als Code (z. B. `DATEI_UNGUELTIG`), nie Meldungstexte oder Ursachen.

### Dateiauslieferung und Stream
Neu 13.11.2026 (Requirement 13; entschieden von Fabian am 13.11.2026, E-D).
- `DateiAuslieferung` (application): in einer kurzen Transaktion Dokument mit festem Filter laden (sonst 404; in der Erfassung mit der Sichtbarkeit A2–A4), Versions-ID lesen und Protokoll `HERUNTERLADEN` schreiben (wie 5.4, gekürzte Client-IP; in der Erfassung mit Bearbeiter); die Transaktion endet vor dem ersten Byte (Requirement 13.8), damit ein Stream weder eine DB-Verbindung hält noch mit seiner Transaktion den Horizont des Änderungs-Feeds zurückhält → Stufe `oeffentlich` (nur Lesen): `AblagePort.vorsignierteUrl` (höchstens `PT5M`, Spec 01) → JSON; sonst Stream.
- Stream (Adapter `rest`): Quarkus REST mit `StreamingOutput` bzw. `Multi<Buffer>`; der S3-Client liefert einen `InputStream` der gespeicherten Versions-ID; Weitergabe in Blöcken von 64 KiB mit Gegendruck (Backpressure), nie `readAllBytes`. Antwort `Content-Type: application/pdf`, `Content-Length` aus den Objektdaten, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline` ohne Dateinamen (Spec 01, 3.12); kein `Range` in v1 (der Kopf wird ignoriert, `Accept-Ranges: none`; später additiv möglich; präzisiert mit Aufgabe 11.8).
- Begrenzung (Startwerte geändert 13.11.2026, bisher 4 gesamt, 2 je Schlüssel und `PT2M` Gesamtdauer): `archiv.webseite.streams-gesamt` (Startwert 8) und `archiv.webseite.streams-je-schluessel` (Startwert 8, gleich dem Gesamtwert, solange es eine Webseite gibt; sonst teilte eine Webseite mit einem Lese-Schlüssel alle Downloads ihrer Besucher auf zwei Plätze auf) als `Semaphore`, ohne Warten; kein Platz → 429 `ZU_VIELE_ANFRAGEN` mit `Retry-After: 5`. Weil das Plugin blockweise an den Browser durchreicht, hält ein langsamer Browser seinen Platz; darum zwei Zeitlimits: `archiv.webseite.stream-leerlauf` (Startwert `PT30S` ohne weitergegebenen Block) und `archiv.webseite.stream-zeitlimit` (Startwert `PT15M` Gesamtdauer, deckt 50 MB bei rund 0,5 Mbit/s). Abbruch durch den Client oder ein Zeitlimit schliesst den S3-Stream und gibt den Platz in `finally` frei (Requirement 13.7). Gegen eine Person, die mit vielen langsamen Downloads alle Plätze belegt, begrenzt das Plugin gleichzeitige Downloads je angemeldeter Person bzw. je Client-Hash (Startwert 2).
- Speicher: 8 × 64 KiB Puffer plus Verbindungen des S3-Clients; passt in das Speicherbudget der API in Stufe A (tech.md). Aufgabe 11.8 misst einen Fall «50 MB an einen langsamen Client». `DateiStreamTest` streamt eine Datei, die grösser ist als ein künstlich begrenzter Puffer, und prüft, dass nie mehr als ein Block gelesen, aber nicht weitergegeben ist.
- Storage nicht erreichbar → 503 `SPEICHER_NICHT_ERREICHBAR` mit `Retry-After` (Spec 01).
- Umsetzung (Aufgabe 11.8; Vorzieh-Entscheid 16.11.2026, «11.8 kommt vor dem Plugin-Download»; Szenarien `@req-13.1`–`@req-13.8` in `features/integration/webseiten-datei.feature` und `webseiten-lesen.feature`, JUnit `DateiStreamTest`):
  - Pakete: `WebseiteDatei` (Transaktionen), `DateiAuslieferung` (Ablauf, `@Transactional(NEVER)`), `DateiPlaetze`, `StreamLauf` und `StreamWaechter` in `integration/application/webseite`; `WebseitenLesenResource.datei` liefert `Response` mit JSON oder `StreamingOutput`; neu im Port `AblagePort.oeffnen(mandant, ort, schluessel, versionId)` → `DateiStrom` (S3: `getObject` mit Versions-ID und `abort()`, Stub: Datei) und in `DokumentDatei.finden` der Ort der Version (dieselbe Prüfung wie beim Link).
  - Ablauf des Streams, damit nichts vor dem ersten Byte offen bleibt: (1) eine kurze Transaktion (`WebseiteDatei.vorbereiten`): `WebseiteLesen.details` mit dem festen Filter und den Stufen der Anfrage (sonst 404, auch bei zu hoher Stufe), dann bei `oeffentlich` Adresse und Protokoll in derselben Transaktion (wie 11.2), sonst nur der Ort der Version; (2) Platz reservieren (429); (3) Version im Object Storage öffnen (503); (4) kurze Transaktion: Protokoll `HERUNTERLADEN` (`WebseiteDatei.protokollieren`); (5) `StreamLauf` ohne Transaktion und ohne Datenbankverbindung. Auslegung: Das Protokoll steht nach Platz und Öffnen statt in der ersten Transaktion (Auslegung von «protokollieren, bevor sie beginnt», Req 13.6): Eine mit 429 oder 503 abgelehnte Anfrage hat nichts ausgeliefert und schreibt keinen Eintrag; das Protokoll steht in jedem Fall vor dem ersten Byte. Die Sicht der Datei ist die der Anfrage (`Sichtregel.fuerWebseite` mit `Lesezugriff.stufen()`), nie mehr.
  - Stream (`StreamLauf`): ein wiederverwendeter Puffer von 64 KiB, `readNBytes` füllt den Block, `write` nimmt ihn an (blockiert beim langsamen Client, das ist der Gegendruck); Länge aus den Objektdaten als `Content-Length`, kürzer als angekündigt ist ein Fehler. Antwort: `Content-Type: application/pdf` (aus einer Liste mit genau diesem einen Typ; die einzige Variante ist die PDF/A-Fassung, ein anderer Typ wäre ein Fehler im Datenbestand und ergibt 500), `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline` ohne Dateinamen (strenger als der interne Download in #186, der `attachment` mit `<ID>-<Rolle>` setzt: Der Webseitenserver reicht den Stream selbst weiter und bestimmt Namen und Anzeige), `Accept-Ranges: none`. `WebseitenRoute` überschreibt `Cache-Control` nur noch, wenn es kein `no-store` enthält (Req 15.4 bleibt für jede Antwort erfüllt).
  - Range (Auslegung, vorsichtiger als eine halbe Unterstützung): Ein `Range`-Kopf wird ignoriert, die Antwort ist immer die ganze Datei mit 200 und `Accept-Ranges: none` (RFC 9110, 14.2: ein Server darf `Range` ignorieren); 206 und 416 gibt es nicht. Ein Browser, der den PDF-Betrachter mit `Range: bytes=0-` startet, bekommt so eine gültige Antwort, was bei 416 nicht gälte. Additiv nachrüstbar.
  - HEAD (Nachbesserung Review PR #221): `HEAD` auf `/dokumente/{id}/datei` ist 405 mit `Allow: GET`. Das Framework führte sonst den GET-Handler aus (Platz und Protokolleintrag), ohne dass je ein Byte fliesst; den Platz gäbe erst der Leerlauf nach 30 s frei. Beim Abbruch des Clients oder Ausfall des Speichers verwirft `StreamLauf` die Speicherverbindung per `abbrechen()` (nicht nur `close()`); scheitert das Bauen der Antwort vor `senden`, gibt die Resource den Lauf sofort frei.
  - Plätze (`DateiPlaetze`): `archiv.webseite.streams-gesamt` und `streams-je-schluessel` (je 8), Schlüssel ist die Kennung `token:<id>`; ohne Warten, sonst 429 `ZU_VIELE_ANFRAGEN` mit `Retry-After: 5` und `grund` `LAEUFT` (der Wert besteht schon für den Chat, kein neuer Fehlercode). Die Grenze je Schlüssel (Anfragen je Sekunde) bleibt Aufgabe 11.11. Zeitlimits: `StreamWaechter` ist ein Daemon-Thread für alle Streams; `archiv.webseite.stream-leerlauf` (`PT30S` ohne weitergegebenen Block) und `stream-zeitlimit` (`PT15M`) verwerfen bei Ablauf Speicher-Verbindung (`abort()`) und Verbindung zum Client (`HttpServerResponse.close()`), damit ein blockiertes `read` oder `write` endet, und geben den Platz frei; ein nie gestarteter Stream (Client weg, bevor Quarkus schreibt) wird so nach dem Leerlauf frei. Freigabe sonst in `finally` von `StreamLauf.senden`.
  - Tests: `%test`-Werte 4 gesamt, 2 je Schlüssel, Leerlauf `PT3S`. Gemessen: Der Messfall «50 MB an einen langsamen Client» läuft als Szenario `@req-13.3` mit einem rohen Socket und kleinem Empfangspuffer (Prüfsumme der 50 MB, Heap der API nach der Hälfte höchstens 20 MB gewachsen) und als `DateiStreamTest` ohne Netz (Zuweisungen des Threads unter 4 MB für 50 MB). Ein `@QuarkusTest` läuft nicht unter `-Xmx64m`, der Heap-Nachweis ist darum relativ (Zuwachs nach dem Aufräumen) und nicht die Grenze des Speicherbudgets der Stufe A.

### Änderungs-Feed
Neu 13.11.2026 (Requirement 14; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR).
- Tabelle `webseite_aenderung` in `m_<code>`, befüllt von einem Trigger `AFTER INSERT OR UPDATE OR DELETE` auf `dokument`, wenn sich `status` oder `version` ändert bzw. die Zeile gelöscht wird (dann `stufe_nachher = null`, für ein späteres physisches Löschen nach Spec 01, 9.9), in derselben Transaktion. Grund für den Trigger statt Aufrufen im Code: Er erfasst jeden Weg (Web-App, Erfassung, Worker, Import), auch künftige.
- Der Trigger schreibt `stufe_vorher` und `stufe_nachher`: die öffentliche Stufe (`zusatz->>'zugriff'`), falls das Dokument vor bzw. nach der Änderung den festen Filter ohne Stufe erfüllt (`ARCHIVIERT`, `NORMAL`, `veroeffentlicht`), sonst `null`. `AenderungFeedTest` (JUnit) prüft für eine Matrix aus Status, Vertraulichkeit, `veroeffentlicht` und `zugriff`, dass Trigger und Java-Filter dasselbe ergeben.
- Lese-Feed: Einträge mit `stufe_vorher` oder `stufe_nachher` in `Stufe des Schlüssels.bisUndMit()`; «sichtbar» heisst im Folgenden: Stufe nicht `null` und in diesem Bereich. Art `SICHTBAR` (vorher nicht sichtbar, nachher sichtbar), `GEAENDERT` (vorher und nachher sichtbar), `NICHT_SICHTBAR` (vorher sichtbar, nachher nicht sichtbar, auch wenn die neue Stufe über dem Schlüssel liegt). Felder `{dokumentId, art, stufe, zeit}`, ohne Titel oder Inhalt; die Webseite holt Details bei Bedarf. `stufe` ist die tiefere der sichtbaren Stufen vor und nach der Änderung (präzisiert 13.11.2026, Requirement 14.1): Sie liegt so nie über dem Schlüssel, und ab ihr aufwärts liegt das Dokument in einem Cache der Webseite. Beispiel: Wechsel `oeffentlich` → `vorstand` ergibt für einen Schlüssel der Stufe `oeffentlich` oder `mitglieder` `NICHT_SICHTBAR` mit `stufe = oeffentlich` und für einen Schlüssel der Stufe `vorstand` `GEAENDERT` mit `stufe = oeffentlich`; Wechsel `mitglieder` → `vorstand` ergibt für einen Schlüssel `mitglieder` `NICHT_SICHTBAR` mit `stufe = mitglieder`. `AenderungFeedTest` und ein Szenario `@req-14.1` prüfen diese Fälle.
- Erfassungs-Feed: alle Einträge des Mandanten, Felder `{dokumentId, referenz, status, fehlercode, version, zeit}`; `referenz` nur aus `webseite_referenz` mit `webseite` = Webseite des Schlüssels, sonst `null` (Requirement 9.4, 14.2).
- Cursor: opak (Base64url), intern `(xid, id)`. Jeder Eintrag speichert `pg_current_xact_id()`; ein Abruf liefert nur Einträge mit `xid < pg_snapshot_xmin(pg_current_snapshot())` (alle älteren Transaktionen sind abgeschlossen), geordnet nach `(xid, id)`, ab dem Cursor. Grund: Eine fortlaufende Nummer allein genügt nicht, weil sie vor dem Commit vergeben wird und eine später committete kleinere Nummer sonst übersprungen würde (Requirement 14.3).
- Grenze des Cursors: Der Horizont `pg_snapshot_xmin` gilt für den ganzen Datenbank-Cluster. Eine lange offene Transaktion, auch eines anderen Mandanten (Import, Migration, Ingest der Wissensbasis), hält den Feed aller Mandanten an, bis sie endet; so lange greift das sofortige Verwerfen nach 7.9 nicht, und es bleibt die Cache-Dauer (7.4). Darum hält kein Stream eine Transaktion offen (13.8). `AenderungFeedTest` prüft einen Fall mit lang offener Transaktion (der Feed liefert danach lückenlos); die Betriebsüberwachung warnt, wenn der Horizont länger als `archiv.webseite.feed-stillstand` (Startwert `PT10M`) nicht vorrückt.
- Aufbewahrung `archiv.webseite.feed-aufbewahrung` (Startwert `P90D`), täglicher Auftrag `FEED_BEREINIGEN` löscht ältere Einträge und setzt `webseite_feed_stand.bereinigt_bis`; ein Cursor darunter → 410 `CURSOR_ABGELAUFEN`, die Webseite lädt ihren Bestand neu. Ohne `seit` liefert der Feed `{eintraege: [], cursor}` mit dem aktuellen Horizont.
- Umsetzung (Aufgabe 11.9; mandantenneutral, getestet mit `urinet` und `vhf-gsk` als Testmandanten, ohne Seed und Domain von VHF/GSK; Szenarien `@req-14.1`–`@req-14.4` und `@req-15.1` in `features/integration/webseiten-feed.feature`, JUnit `AenderungFeedTest`, `AenderungFeedLogikTest`, `FeedCursorTest`, `LeseAenderungTest`, `FeedHorizontUeberwachungTest`, `AenderungFeedAufraeumenTest`, ArchUnit `keinHttpClientInDerWebseitenSchnittstelle` für Req 14.5):
  - Changesets `db/mandant` 032 (`webseite_aenderung`, `webseite_feed_stand`, Funktion und Trigger `webseite_aenderung_erfassen`) und 033 (`webseite_referenz`). Die Trigger-Funktion schreibt mit `TG_TABLE_SCHEMA` in die Tabelle desselben Schemas, unabhängig vom `search_path` der Session (Schreibzugriffe mit qualifizierten Namen, Worker, Import); sie bildet die Bedingung des festen Filters nach (`ARCHIVIERT`, `NORMAL`, `bestaetigt` gesetzt, `veroeffentlicht` als JSON-`true`, `zugriff` eine der drei Stufen). `AenderungFeedTest` vergleicht sie für 160 Kombinationen aus Status, Vertraulichkeit, Bestätigung, `zugriff` und `veroeffentlicht` mit den Leseendpunkten (`WebseiteLesen.details` bei jeder Stufe).
  - Auslegung (Trigger): Ein Eintrag entsteht bei `INSERT`, `DELETE` und bei einer Änderung von `status`, `version`, `fehler` oder der Stufe (nicht nur von `status` oder `version`, wie oben verkürzt). Grund: Ein Stufenwechsel, ein Rückzug oder eine Bestätigung ändert `zusatz`, `vertraulichkeit` oder `bestaetigt` und muss auch dann gemeldet werden, wenn eine Quelle `version` nicht erhöht; alles Übrige (`volltext`, `titel`, Zeitstempel) erzeugt keinen Eintrag, damit OCR und Indexierung den Feed nicht fluten. Mehrere Änderungen in einer Transaktion ergeben mehrere Einträge. Beim Löschen sind `status` und `version` leer und `stufe_nachher` ist `null`.
  - Auslegung (Fehlercode): `fehlercode` des Erfassungs-Feeds ist ein stabiler Code aus dem Status: `VERARBEITUNG_FEHLGESCHLAGEN` bei `FEHLER`, `ARCHIVIERUNG_FEHLGESCHLAGEN` bei `ARCHIVIERUNG_FEHLER`, sonst leer. Das Archiv führt bisher keinen Code je Dokument; Aufgabe 11.10 kann die Werte verfeinern (additiv, Feld `fehlercode` ist nicht erweiterbar festgelegt, die Webseite zeigt den Wert nie an).
  - Auslegung (`webseite_referenz`): Der Erfassungs-Feed liest `webseite_referenz`; die Tabelle gehört zu Aufgabe 11.10 und kommt hier mit dem Changeset 033 vorweg (genau das Schema aus «Data Models»), damit Req 14.2 belegbar ist. Aufgabe 11.10 übernimmt sie und legt nur noch ihre übrigen Changesets an. Befüllt wird sie bis dahin nirgends.
  - Cursor: Base64url ohne Auffüllung über `v1.<xid>.<id>`, strikt (nur diese Schreibweise gilt, sonst 400 `ANFRAGE_UNGUELTIG`). Eine Anfrage liest in **einer** Anweisung Horizont und Einträge aus demselben Schnappschuss (`with h as (select pg_snapshot_xmin(pg_current_snapshot()) …) … left join lateral`). Hat die Antwort weniger als `max` Einträge, ist der neue Cursor `(Horizont, 0)` (alles darunter ist gelesen), sonst der letzte gelieferte Eintrag; der Cursor rückt nie zurück. Ohne `seit` kommt `(Horizont, 0)` ohne Einträge: erst Cursor holen, dann Bestand laden, dann lesen. Ein Cursor mit `xid` grösser als die nächste vergebene Transaktions-ID ist 400, `max` ausserhalb von 1 bis 500 ist 400 (wie `pro_seite` in 11.2; kein stilles Kappen).
  - Aufbewahrung: `FEED_BEREINIGEN` ist wie die Aufräumer des Chats ein täglicher Takt des Workers (`archiv.webseite.feed-bereinigen`, Standard 03:50 Europe/Zurich, je aktivem Mandanten, eigener Thread ohne Request-Kontext), nicht ein Eintrag der Auftragsschlange: Er braucht weder Wiederholung noch Nutzlast, und die Schlange kennt keine Aufträge ohne Dokument. Er löscht Einträge mit `zeit` älter als `archiv.webseite.feed-aufbewahrung` (1 bis 365 Tage, sonst Startabbruch) und setzt `bereinigt_bis` auf die grösste gelöschte `xid`; ein Cursor mit `xid ≤ bereinigt_bis` ist 410 `CURSOR_ABGELAUFEN` (der Fehlercode steht in Spec 01 design.md).
  - Stillstand des Horizonts: `FeedHorizontWaechter` (Takt `archiv.webseite.feed-ueberwachung`, Standard 1 Minute, nur Worker) prüft `pg_snapshot_xmin`/`xmax` und loggt eine Warnung, wenn der Horizont länger als `archiv.webseite.feed-stillstand` (`PT10M`) steht, obwohl Transaktionen offen sind (`xmin < xmax`); eine ruhige Datenbank ohne offene Transaktion ist kein Stillstand. Auslegung: Die «Betriebsüberwachung» ist hier ein Log-Eintrag auf WARN (ohne Transaktions-IDs); eine Kennzahl oder Benachrichtigung bleibt der Überwachung des Betreibers überlassen.
  - Lese-Feed und Stufe: `@FeedZugriff` am Endpunkt sorgt dafür, dass `LeseZugriffFilter` weder `X-Archiv-Zugriff` noch `X-Archiv-Client` liest und die Stufe des Schlüssels setzt; ein falscher Header ist im Feed kein Fehler. Der Feed zählt in der Grenze je Schlüssel wie jeder Lese- bzw. Erfassungs-Endpunkt (`@WebseitenZugang`, 11.11). Der Lese-Feed gehört dem Lese-Schlüssel, der Erfassungs-Feed dem Erfassungs-Schlüssel; der jeweils andere bekommt 403 `NICHT_ERLAUBT`.
  - Vertrag: `public-v1.yaml` additiv (Pfade `/public/v1/aenderungen` und `/public/v1/erfassung/aenderungen`, Schemas, Antwort `CursorAbgelaufen`, Wert `CURSOR_ABGELAUFEN` im erweiterbaren `fehlercode`).

### Vertrag und Versionierung
Neu 13.11.2026 (Requirement 11; entschieden von Fabian am 13.11.2026, E-B).
- Quelle des Vertrags ist die eingecheckte Datei `docs/schnittstelle/public-v1.yaml` (OpenAPI 3.1), getrennt von der
  App-Beschreibung `frontend/openapi/` (die Pfade unter `/public/` gehören nicht in den Angular-Client).
- `PublicVertragTest` (JUnit, ohne Netz): erzeugt die Beschreibung der Pfade unter `/public/v1` aus dem Code und
  vergleicht sie mit der Datei (Pfade, Parameter, Statuscodes, Schemas; Requirement 11.2).
- Herkunft der Beschreibung (präzisiert 13.11.2026): Die App-Beschreibung schliesst das Paket der Webseiten-Endpunkte
  über `mp.openapi.scan.exclude.packages` aus (wie heute `ch.urinet.archiv.probe`), damit `frontend/openapi/` und der
  Angular-Client unverändert bleiben. Den Vertrag erzeugt `PublicVertragTest` in einem eigenen Lauf ohne diesen
  Ausschluss, nur für `/public/` (Scanner von smallrye-open-api direkt auf dem Index oder ein Test-Profil); Aufgabe 11.6
  wählt den Weg.
- Nur additiv (Requirement 11.3; geändert 13.11.2026, bisher Teil von `PublicVertragTest`): Ein CI-Schritt im
  Build-Job, der die Git-Historie vollständig lädt (wie «Bestehende Liquibase-Changesets unverändert»), vergleicht die
  Datei mit ihrem Stand auf `origin/main` und bricht bei jeder nicht additiven Änderung ab (entfernte oder umbenannte
  Pfade, Felder oder Parameter, geänderte Typen, neue Pflichtparameter, entfernte Statuscodes, neue Werte in Enums, die
  nicht mit `x-erweiterbar: true` markiert sind). Grund: Die Jobs der Backend-Tests laden keine vollständige
  Historie, dort fehlte `origin/main`. Das Werkzeug (eigenes Skript oder z. B. `oasdiff breaking`) wählt Aufgabe 11.6.
- Erweiterbar markiert sind von Anfang an: `art` im Lese-Feed, `status` und `fehlercode` (neue Fehlercodes sind
  additiv), Klassifikationen.
- Hauptversion: Pfad-Präfix `/public/v<n>`; Abkündigung über `archiv.webseite.v<n>.sunset` (Datum); ab dann die Header
  `Deprecation`, `Sunset` und `Link` (Requirement 11.5). Parallelbetrieb mindestens 6 Monate (Requirement 11.4).
- Traefik: `/public/` läuft über einen eigenen Router mit eigener Drosselung je IP, damit die Seitenaufrufe einer
  Webseite (ein Server, eine IP) nicht unter die Grenze der App fallen; der Wert steht in `deploy/`, nicht hier.
- Umsetzung (Aufgabe 11.6, Vorstufe 14.11.2026; Entscheid Fabian vom 14.11.2026 «Neutrale Vorstufen jetzt»):
  - Weg für `/public/v1`: Quarkus REST registriert am Router nur `quarkus.rest.path` (`/api/v1`). `WebseitenRoute`
    (`integration/adapter/in/rest/webseite`) leitet jede Anfrage unter `/public/` vor der Anmeldung (Order -250, vor
    den Filtern der Anmeldung bei -200) per Vert.x-Reroute auf den internen Pfad `/api/v1/public/…` um; die Resources
    tragen `@Path("/public/v1/…")`. Danach gelten Anmeldung, HTTP-Rechte (`/api/*`), `ProblemAnmeldung`,
    `ProblemFehlerRoute` und `ProblemAbbildung` wie für die App, ohne Änderung der bestehenden Pfade. Der interne Pfad
    antwortet von aussen 404 `NICHT_GEFUNDEN`, schon vor der Anmeldung. Geprüft wird ohne Matrix-Parameter (`;…`),
    weil Quarkus REST sie vor der Zuordnung entfernt; unter `/public/` weist die Route Pfade mit `;` mit 404 ab, und ein
    Filter nach der Zuordnung (`WebseitenSperre`) weist jede Resource unter `/public/` ohne vorherigen Reroute ab
    (Review PR #140). `Cache-Control: no-store` und die Header der
    Abkündigung setzt derselbe Handler am Router für jede Antwort unter `/public/`, auch für Fehler vor der Resource.
    `quarkus.http.auth.permission` und `ProblemAnmeldung` nennen `/public/` zusätzlich, Quinoa ignoriert `/public`.
  - Paket: Die Regel der Schichten in `ArchitekturTest` verlangt `<fachbereich>.(domain|application|adapter)`; die
    Webseiten-Schnittstelle liegt darum wie `integration.domain.mail` unter `integration.domain.webseite` und
    `integration.adapter.in.rest.webseite`. `mp.openapi.scan.exclude.packages` schliesst beide Präfixe aus
    (`ch.urinet.archiv.integration.webseite` und `ch.urinet.archiv.integration.adapter.in.rest.webseite`, Unterpakete
    inbegriffen; `OpenApiAusschlussTest`).
  - Vertrag: `PublicVertragTest` beschreibt den Code mit dem Scanner von smallrye-open-api (`smallrye-open-api-jaxrs`
    als Testabhängigkeit, Version aus der Quarkus-BOM) auf einem Jandex-Index aller Klassen aus `src/main`, mit den
    Einstellungen `mp.openapi.*` der App ohne Ausschluss, ohne Quarkus-Start; die Datei liest derselbe Parser.
    Verglichen werden die Pfade unter `/public/` normalisiert (Parameter, Pflicht, Anfrage-Inhalt, Statuscodes, Schemas
    mit aufgelösten Referenzen; Antworten mit `$ref` über den Namen). Dazu Mindestprüfungen als OpenAPI 3.1 und jede
    Referenz `$ref` lokal und auflösbar. Die Fassung dieser Aufgabe hat `paths: {}`.
  - Nur additiv: eigenes Skript `.github/scripts/pruefe-vertrag.sh` mit `pruefe-vertrag.rb` (Ruby-Standardbibliothek,
    auf ubuntu-24.04 und macOS vorhanden; Python hat kein YAML in der Standardbibliothek), weil `x-erweiterbar` eine
    eigene Konvention ist. Fehlende oder unbekannte Basis und unlesbare Dateien → Exit 2, gelöschte Datei → Exit 1,
    Datei ohne Stand auf der Basis = erste Fassung. Selbsttest `pruefe-vertrag-test.sh` über `VertragPruefungTest`.
  - Abkündigung: `archiv.webseite.v<n>.sunset` (ISO-Datum; ungültig → Start bricht ab). `Sunset` = Tagesbeginn UTC als
    HTTP-Datum (RFC 8594), `Deprecation` = Sunset minus 6 Monate als `@<Unix-Sekunden>` (RFC 9745; Wahl dieser Aufgabe,
    die Spec nennt keinen Zeitpunkt), `Link: </public/v<n+1>>; rel="successor-version"`. Nach dem Sunset liefert das
    Archiv weiter aus (Abschalten ist ein Betriebsentscheid).
  - Offen: CI-Schritt «Vertrag nur additiv» im Build-Job (`.github/workflows/` nicht in dieser Aufgabe), Router für
    `/public/` mit eigener Drosselung in Traefik (`deploy/infomaniak/compose.yml`, Wert betrieblich); bis dahin läuft
    `/public/` über den Router der App.
  - Vorschlag Traefik-Drosselung (17.11.2026, Aufgabe 11.6; wartet auf Prüfung von Fabian, nichts ausgerollt): Router
    `archiv-public` (`PathPrefix(/public/)`, Priorität 200, vor dem PWA-Router) und `archiv-public-suche`
    (`Path(/public/v1/suche)`, Priorität 300), Labels in `deploy/infomaniak/compose.yml`. Je Quell-IP (Traefik-Standard
    `remoteAddr`, wie die bestehende Drosselung): alle Pfade 50/s, Spitze 200; Suche zusätzlich 5/s, Spitze 20; höchstens
    24 gleichzeitige Anfragen (`inflightreq`). Begründung: äussere Schicht, bewusst über den Grenzen je Schlüssel (Lesen
    20/s, Suche 2/s, Erfassung 5/s, Uploads 120/h, siehe «Grenzen»), damit im Normalfall das Backend mit
    `ZU_VIELE_ANFRAGEN` und `Retry-After` antwortet; ein Webseitenserver ist eine IP und bedient mehrere Schlüssel
    (Lesen und Erfassung, je Webseite), darum Summe plus Reserve. Die Schicht fängt Schlüssel-Raten (viele falsche
    Schlüssel je IP, jeder rechnet Argon2) und Last vor dem Backend ab. Die Zähler liegen im Speicher von Traefik; das 429
    von Traefik hat kein Problem Details und kein `Retry-After` (Clients behandeln jedes 429 als «später erneut»; die
    Messung am Livegang, Kennzahl `archiv_webseite_grenze_abgelehnt_total` und Traefik-Zugriffslog, zeigt, ob die Werte
    passen). Header `X-Archiv-Zugriff`, `-Client`, `-Bearbeiter`: Traefik reicht Request-Header unverändert weiter, keine
    Middleware ändert sie. Body-Grösse der Uploads: nur
    als Kommentar in `compose.yml` (`buffering` mit 52 MiB), weil es den Body in Traefik puffert; Entscheid mit 11.10.
    Interne Routen (`/api/`, `/q/`) unberührt. Rückfall: die Labels entfernen und neu deployen; `/public/` läuft dann
    wie bisher über den PWA-Router (nur Backend-Grenzen).

### Grenzen
Startwerte (Requirement 15.1; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR), konfigurierbar unter
`archiv.webseite.*`, gemessen in Aufgabe 11.11:

| Grenze | Startwert | Antwort |
| --- | --- | --- |
| Anfragen je Lese-Schlüssel | 20/s, Spitze 100 (Token-Bucket) | 429 `ZU_VIELE_ANFRAGEN`, `Retry-After` |
| Suche je Lese-Schlüssel (zusätzlich, strenger; Embedding und Reranker) | 2/s, Spitze 10 (Startwert 16.11.2026, vorsichtig geschätzt, Entscheid Fabian offen) | 429 `ZU_VIELE_ANFRAGEN`, `grund` `SUCHE` |
| Anfragen je Erfassungs-Schlüssel | 5/s, Spitze 20 | 429 `ZU_VIELE_ANFRAGEN` |
| Uploads je Erfassungs-Schlüssel | 120 je Stunde | 429 `ZU_VIELE_ANFRAGEN` |
| Streams gesamt / je Schlüssel | 8 / 8 gleichzeitig (Leerlauf `PT30S`, Gesamtdauer `PT15M`) | 429 `ZU_VIELE_ANFRAGEN`, `Retry-After: 5` |
| Prüf-Cache für Webseiten-Schlüssel | `PT5M` je Schlüssel-ID (12.8) | – |
| Chat je Client-Hash | 20 Fragen/h (6.4) | 429 `ZU_VIELE_ANFRAGEN` |
| Chat-Budget je Mandant und Monat | `mandant_budget` (6.4) | 429 `BUDGET` |
| Feed | `max` ≤ 500 | 400 bei mehr |

Die Zähler liegen im Speicher der API (eine Instanz); nach einem Neustart beginnen sie neu. Mehrere Instanzen sind
nicht vorgesehen; kämen sie, brauchte es einen gemeinsamen Zähler (Open Points).

Umsetzung (Aufgabe 11.11; Stand: Baustein und Lese-Endpunkte umgesetzt, Messung und echter Upload offen):
- Baustein: `WebseitenGrenzen` (Paket `integration/application/webseite`, `@ApplicationScoped`) mit `TokenBucket`
  (`integration/domain/webseite`, rechnet nur mit der übergebenen Zeit). Je Schlüssel-ID (Principal `token:<id>` der
  angemeldeten Identität, nie ein Header-Wert) vier Buckets: Lesen, Suche, Erfassung, Uploads. Eine Anfrage nimmt aus allen
  Buckets ihrer `Grenzklasse` je eine Marke oder aus keinem (eine abgelehnte kostet nichts): `LESEN` = Lesen; `SUCHE` =
  Lesen und Suche; `ERFASSUNG` = Erfassung; `UPLOAD` = Erfassung und Uploads. Sperre: ein `synchronized` für alle
  Schlüssel (die Rechnung dauert Nanosekunden).
- Einhängen: `WebseitenGrenzenFilter` (Priorität `USER + 50`: nach `ZugangFilter`, vor `LeseZugriffFilter`) bei jedem
  Endpunkt mit `@WebseitenZugang`; Klasse nach Scope (Lesen/Erfassung), strenger mit `@WebseitenGrenze(SUCHE|UPLOAD)`
  (Suche trägt `SUCHE`; Endpunkt des Uploads in 11.10 trägt `UPLOAD`; bis dahin belegt ihn die Probe
  `/public/v1/probe/erfassung/upload` in `src/test`). Interne Endpunkte ohne `@WebseitenZugang` berühren die Grenzen nie.
  Die Prüfung läuft vor `LeseZugriffFilter`, damit auch Anfragen mit ungültigen Headern zählen.
- Auslegungen: Der öffentliche Chat zählt als Lese-Anfrage im Bucket seines Schlüssels mit; seine Grenzen
  (20 Fragen/h je Client-Hash, 600/h je Webseite, Gleichzeitigkeit, Monatsbudget) bleiben unverändert und sind eigene
  Bausteine (`OeffentlicheGrenzen`). Die Suche zählt zusätzlich im Bucket «Lesen» (sie ist auch eine Lese-Anfrage). Uploads je
  Stunde sind ein Bucket mit Fassungsvermögen = Stundenwert und gleichmässiger Auffüllung über die Stunde (kein festes
  Stundenfenster: kein Stoss zum Stundenwechsel); der Stundenwert ist zugleich der erlaubte Stoss. Die Rotation eines
  Schlüssels (zwei IDs während der Überlappung) verdoppelt für diese Zeit die Grenze der Webseite; hingenommen.
- Antwort: `FachFehler` `ZuVieleAnfragen` (429 `ZU_VIELE_ANFRAGEN`, Problem Details, schon in Spec 01 eingetragen) mit
  `Retry-After` = Sekunden bis zur nächsten Marke des knappsten Buckets (aufgerundet, mindestens 1, höchstens 3600) und
  `grund` `ANFRAGEN`, `SUCHE` oder `UPLOADS`; die Antwort nennt weder Schlüssel noch Header-Wert. Der Vertrag
  `public-v1.yaml` führt 429 mit `Retry-After` an allen lesenden Endpunkten und die Werte `ANFRAGEN` und `SUCHE` in `grund`.
- Speicher: höchstens `archiv.webseite.grenzen.max-schluessel` (1000) Einträge; nur ein angemeldeter, gültiger Schlüssel legt
  einen an. Ist die Tabelle voll, fliegen Einträge im Leerlauf (alle Buckets voll, also verlustfrei) hinaus, höchstens einmal je
  Minute; reicht das nicht, teilen sich die weiteren Schlüssel einen Sammel-Eintrag (sie begrenzen sich dann gegenseitig, die
  Tabelle wächst nie).
- Neustart und mehrere Instanzen: Die Buckets liegen im Speicher des Prozesses (Stufe A hat eine Instanz). Nach einem Neustart
  beginnt jeder Schlüssel mit vollem Bucket (ein Stoss ist wieder möglich, kein Dauerschaden); bei mehreren Instanzen gälte die
  Grenze je Instanz (Open Points: gemeinsamer Zähler).
- Fehler der Prüfung: Eine unerwartete Ausnahme in der Prüfung lässt die Anfrage durch (Log nur mit Klassennamen, Kennzahl
  `archiv_webseite_grenze_ausgefallen_total`). Begründung: Sie rechnet nur im Speicher und kann ausser durch einen
  Programmfehler nicht ausfallen; ein solcher soll die Webseite nicht abschalten, und die Anmeldung davor bleibt fail closed. Die
  Drosselung je IP vor der API (11.6) und das Monatsbudget des Chats bleiben als weitere Schichten.
- Kennzahl: `archiv_webseite_grenze_abgelehnt_total` mit dem einzigen Tag `grund` (`ANFRAGEN`, `SUCHE`, `UPLOADS`, alle von Beginn
  an mit 0 angelegt); nie Schlüssel-ID, Webseite oder Client. Messung der Startwerte (Aufgabe): mit dem Livegang an dieser
  Kennzahl und an den 429 im Zugriffs-Log der Webseite prüfen.
- Einstellungen `archiv.webseite.grenzen.*` (`application.properties`): `lesen-je-sekunde` 20, `lesen-spitze` 100,
  `suche-je-sekunde` 2, `suche-spitze` 10, `erfassung-je-sekunde` 5, `erfassung-spitze` 20, `uploads-je-stunde` 120,
  `max-schluessel` 1000; ungültige Werte (Spitze unter 1, Rate 0) brechen den Start ab, ohne einen Wert zu nennen. Im Profil
  `test` kleine Werte, damit die Szenarien die Grenze mit wenigen Anfragen erreichen.
- Nachweis: `TokenBucketTest`, `WebseitenGrenzenTest` (Uhr-Attrappe: Auffüllen, Stoss, Suche strenger, Uploads, Speicher,
  Fehlerverhalten), `features/integration/webseiten-grenzen.feature` (`@req-15.1`, `@req-6.4`: Schlüssel A begrenzt und B nicht,
  Wechsel von `X-Archiv-Client` und `X-Archiv-Zugriff` umgeht nichts, `Retry-After`, Suche strenger, Uploads, Chat zählt mit
  und behält seine Grenzen, interne Endpunkte unberührt, Kennzahl nur mit `grund`).

### WordPress-Plugin `wordpress/urinet-archiv-plugin`
Nutzer des Vertrags `public-v1` (geändert 13.11.2026, E-B): Das Plugin nutzt nur, was in `docs/schnittstelle/public-v1.yaml`
steht, und ignoriert unbekannte Felder (Requirement 11.6).
```
urinet-archiv-plugin/
├── urinet-archiv.php            Plugin-Header, Bootstrap
├── includes/
│   ├── class-einstellungen.php  Settings API (URL, Lese- und Erfassungs-Schlüssel verschlüsselt in wp_options, Cache-Dauer, Rollen für Mitglieder, Vorstand und Geschäftsstelle)
│   ├── class-client.php         wp_remote_get/post an /public/v1, Header, Problem Details, Transients
│   ├── class-shortcodes.php     urinet_archiv_liste, urinet_archiv_suche, urinet_chatbot
│   ├── class-dokumentseite.php  /urinet-archiv/dokument/{id}: Dokumentseite aus /public/v1/dokumente/{id}; Umleitung alter Links (301) über /public/v1/umleitung
│   ├── class-rest.php           eigene WP-REST-Route /wp-json/urinet-archiv/v1/chat (Proxy, Nonce)
│   ├── class-download.php       /urinet-archiv/datei/{id}: oeffentlich → 302 auf vorsignierte URL; sonst Stream blockweise durchreichen
│   ├── class-feed.php           WP-Cron: Änderungs-Feed abfragen, Transients verwerfen, Status der Erfassung nachführen
│   └── class-erfassung.php      WP-Admin für die Geschäftsstelle (nur mit Erfassungs-Schlüssel): Liste, Hochladen, Status, Datei ansehen, Bestätigen, Ändern, Veröffentlichen/Zurückziehen, Wiederholen
├── blocks/                      block.json je Block (Liste, Suche, Chatbot)
├── assets/chatbot.js            schlanker Chat ohne Framework, liest SSE über den WP-Proxy
└── languages/                   de_CH
```
- Zugriffsstufe (geändert 13.11.2026, E-C; bisher nur `mitglieder`): nicht angemeldet → `oeffentlich`; Rolle für Vorstand → `vorstand`; sonst Rolle für Mitglieder → `mitglieder`; sonst `oeffentlich`. Die höhere Stufe gewinnt.
- Schlüssel in `wp_options` mit `sodium_crypto_secretbox` und Schlüssel aus `wp-config.php` (`URINET_ARCHIV_KEY`).
- Caching: Transient-Schlüssel enthält Shortcode-Parameter und Zugriffsstufe (drei Stufen); auch `/filter` wird je Stufe zwischengespeichert. `class-feed.php` fragt den Lese-Feed alle 5 Minuten (Startwert) ab und verwirft bei jedem Eintrag die Transients von Liste, Suche und Filter ab der Stufe `stufe` des Eintrags aufwärts sowie die Details des Dokuments (Requirement 7.9, 14.1). Bei 410 verwirft es alle Transients.
- Dokumentseite (Requirement 5.11, 7.2; vorgeschlagen 13.11.2026, entschieden mit dem Merge dieses PR): Das Plugin bildet alle Links auf Dokumente selbst (`/urinet-archiv/dokument/{id}`), aus Liste, Suche, Chat-Quellen und Umleitung; das Archiv liefert nur `dokumentId`.
- Download (Requirement 7.8; geändert 13.11.2026, bisher wahlweise mit temporärer Datei): Das Plugin prüft vor dem Abruf, ob die Person die Stufe hat, und begrenzt gleichzeitige Downloads je Person bzw. Client-Hash (Startwert 2). Es reicht den Stream blockweise ohne Datei durch: Ausgabepuffer schliessen, Kopfzeilen setzen, dann ein cURL-Write-Callback (über den Hook `http_api_curl` der WP-HTTP-API oder direkt mit begründeter Ausnahme der Coding Standards), der jeden Block mit `echo` und `flush()` weitergibt. Die Variante `wp_remote_get(…, ['stream' => true, 'filename' => …])` ist ausgeschlossen, weil sie geschützte Dokumente ganz auf das Webhosting schriebe. Antwort an den Browser mit `Cache-Control: private, no-store` und einem Dateinamen aus dem Titel (nie dem Dateinamen des Archivs, den das Archiv nicht liefert). Bei 429 eine Meldung «Gerade sind viele Downloads aktiv – bitte in einer Minute nochmals versuchen.» Ein Plugin-Test prüft, dass Upload- und Temp-Verzeichnis nach einem Download leer bleiben.
- Erfassung (Requirement 7.10): eigener Admin-Bereich «Archiv» für die Rolle der Geschäftsstelle; Liste aus `GET /erfassung/dokumente` (auch Altbestand ohne Referenz); Formular aus `/erfassung/dokumentarten`; die Datei geht direkt aus dem PHP-Upload an das Archiv und wird danach gelöscht (nicht in die Mediathek); Referenz = `wp-<post-id>` bzw. eine beim Formular erzeugte UUID; Bearbeiter = `wp:<user-id>`, beim ersten Auftreten und bei geändertem Anzeigenamen über `PUT /erfassung/bearbeiter/{kennung}` gemeldet; `If-Match` aus dem zuletzt gelesenen `ETag`, bei 412 neu laden und die Person entscheiden lassen. Angaben beim Hochladen (Titel, Reihe, Klassifikationen, Stufe usw.) speichert das Plugin als Post-Meta je Referenz und belegt damit das Bestätigen vor, sobald der Feed `EINGANGSKORB` meldet (die Angaben der Person gehen dem KI-Vorschlag vor; übernommen werden sie erst mit dem Bestätigen durch die Person, Requirement 7.10). «Veröffentlichen» zeigt das Plugin erst, wenn der Feed `ARCHIVIERT` meldet. «Entfernen» = Zurückziehen. Fehlerfälle zeigt es mit «Wiederholen»; was Wiederholen nicht löst, verwirft die Geschäftsstelle in der Web-App, oder sie lädt eine korrigierte Datei unter neuer Referenz hoch.

### Mandantenverwaltung
- `MandantBericht` monatlich (Auftrag `MANDANT_BERICHT`): Speicher (Summe aller Objektversionen des Buckets, Stand bei der Erhebung; Dokumente je Status ebenso, nur Tokens und Chat-Fragen sind auf den Monat begrenzt), Dokumente, `ki_verbrauch` nach Zweck, Chat-Fragen; E-Mail an Betreiber (mindestens-einmal; ohne Empfänger bleibt der Bericht gespeichert und die Planung reiht täglich neu ein).
- Umsetzung (PR #224): Changeset `034-mandant-bericht` (032 und 033 sind durch den Webseiten-Feed belegt); tägliche Planung in `Europe/Zurich` reiht je aktivem Mandanten höchstens einen offenen Auftrag für den Vormonat ein; je Mandant und Monat wird der Bericht im Mandantenschema einmalig gespeichert. Speicher und Dokumentstatus sind der Stand bei der ersten Erstellung, Token- und Chat-Zahlen stammen aus dem halb offenen Monatsfenster. Bei fehlgeschlagenem Mailversand bleibt der Zahlenbericht gespeichert und wird beim nächsten Plantakt erneut versandt; Inhalt, Titel, Fragetext und Personenbezug werden nicht gespeichert oder versandt.
- `MandantVollexport` (Auftrag `MANDANT_EXPORT`): wie Jahresexport, aber alle Jahre, Original + Arbeitsfassung (falls vorhanden) + PDF/A, `metadaten.json`, `metadaten.csv`. Spec 05 ergänzt (Spec 05 design.md, «Folgeänderungen»; umgesetzt in Aufgabe 12.2): je Dokument die Klassifikationen mit Code und Name und die Konfigurationsversion, dazu die aktuelle Konfigurationsdatei und alle Konfigurationsversionen des Mandanten.
- `MandantAbbau` (Kommando `archiv mandant abbauen <code> --nach-export <exportId>`): deaktivieren → nach 30 Tagen Schema droppen, Keycloak-Client löschen, Objekte ohne Sperre löschen (nie mit Bypass-Header, Spec 01, 6.5; gesperrte Objekte bleiben, Manifeste unter COMPLIANCE bis zum Ablauf ihrer Sperre), gesperrte auflisten; der Bericht nennt das Datum, an dem der letzte Sicherungsstand der Zweitkopie mit Daten des Mandanten ausläuft (Requirement 8.5). Ergänzt 13.11.2026: Das Deaktivieren sperrt auch alle Webseiten-Schlüssel des Mandanten wie jedes Token (403 `MANDANT_FEHLT`, umkehrbar, Spec 01 design.md, «Sicherheit»); widerrufen werden sie erst mit dem Löschen nach 30 Tagen.

## Data Models (neu)
```sql
-- public (Liquibase-Changelog db/public)
mandant_budget(mandant text, monat date, tokens_max int, tokens_verbraucht int, primary key (mandant, monat))
-- neues Changeset (nach 008-pg-trgm) für die Webseiten-Schlüssel, 13.11.2026:
api_client                                -- Spalten ergänzt
  + webseite text null                    -- Kennung der Webseite, Pflicht bei Scope webseite-*
  + hoechste_stufe text null              -- oeffentlich|mitglieder|vorstand, Pflicht bei Scope webseite-*
  + gueltig_bis timestamptz null          -- Ende der Überlappung nach Rotation (nimmt O.7 von Spec 01 teilweise vorweg)
  + absender_pruefen bool not null default false  -- Netze je Webseite in der Betriebskonfiguration, nicht in der DB
  check scope in ('generator','import','webseite-lesen','webseite-erfassung')   -- ersetzt den Check aus 003
  check (scope not like 'webseite-%') or (webseite is not null and hoechste_stufe is not null)
-- m_vhf_gsk (zusätzlich zu Spec 01/02; Liquibase-Changelog db/mandant)
alte_url(url text pk, dokument_id uuid fk)
oeffentliche_frage(id bigserial pk, frage text, quellen jsonb, tokens int, zeit timestamptz)  -- Standard 90 Tage, je Mandant einstellbar (VHF/GSK 30 Tage)
-- jedes Mandanten-Schema (db/mandant, neue Changesets, 13.11.2026)
webseite_referenz(webseite text, referenz text, dokument_id uuid fk, sha256 text, erstellt timestamptz,
                  primary key (webseite, referenz))
webseite_aenderung(id bigserial pk, xid xid8 not null, dokument_id uuid, zeit timestamptz,
                   status text, version int, stufe_vorher text null, stufe_nachher text null,
                   fehlercode text null)  -- 90 Tage, Trigger auf dokument; fehlercode seit Aufgabe 11.9
webseite_feed_stand(id int pk check (id = 1), bereinigt_bis xid8 null)
zugriff_protokoll + bearbeiter text null  -- <webseite>/<X-Archiv-Bearbeiter>; nur INSERT wie bisher (Spec 01, 10.3)
dokument_version + bearbeiter text null   -- dieselbe Kennung bei Fassungen aus der Erfassung (Spec 01, 10.1)
benutzer_verzeichnis                      -- keine neue Spalte; benutzer zusätzlich in der Form <webseite>/<bearbeiter> (Req 10.12)
dokument.quelle  -- Check um 'WEBSEITE' erweitert
-- Spec 02, chunk (im Changeset von Spec 02, Aufgabe 6.3; ist es schon ausgerollt, ein neues):
chunk + seite_von int null, seite_bis int null
```
Die Werte `zugriff` und `veroeffentlicht` bleiben in `dokument.zusatz` (keine eigene Spalte); ein Ausdrucksindex
`((zusatz->>'zugriff'))` mit Bedingung `status='ARCHIVIERT'` legt Aufgabe 11.2 an, falls die Messung ihn braucht.

## Error Handling
- Geändert 13.11.2026 (Requirement 15.2; bisher «Öffentliche Fehler ohne Details (`{"meldung":"Im Moment nicht verfügbar"}`)»): Alle Fehler der Webseiten-Schnittstelle sind Problem Details nach Spec 01 (`application/problem+json`, `type` `urn:urinet:archiv:fehler:<FEHLERCODE>`, `instance` mit Fehler-ID, feste deutsche Texte ohne Eingabewerte); Details nur im Server-Log mit Fehler-ID. 401 schreibt `ProblemAnmeldung` auch unter `/public/` (bisher nur `/api/`; Spec 03 ergänzt Spec 01 design.md, Error Handling).
- Budget erschöpft → HTTP 429 mit `fehlercode=BUDGET`, Plugin zeigt «Der Chatbot ist diesen Monat ausgelastet – die Suche funktioniert weiterhin.»
- Neue Fehlercodes (Spec 03). `FehlercodeSpecTest` vergleicht das Enum mit der Tabelle in Spec 01 design.md; darum trägt die Aufgabe, die einen Code einführt, ihn im selben PR dort ein (mit Verweis «Spec 03, Req x.y»), wie in Spec 05 design.md:

| HTTP | `fehlercode` | Wann | Aufgabe |
| --- | --- | --- | --- |
| 409 | `REFERENZ_VERGEBEN` | Referenz der Webseite gehört schon zu einer anderen Datei (Req 10.3) | 11.10 |
| 410 | `CURSOR_ABGELAUFEN` | Cursor älter als die Aufbewahrung des Feeds (Req 14.4) | 11.9 |
| 429 | `ZU_VIELE_ANFRAGEN` | Grenze je Schlüssel, je Client im Chat oder für Streams überschritten, mit `Retry-After` (Req 6.4, 13.4, 15.1) | 11.3 (sonst die zuerst gemergte von 11.8 und 11.11) |
| 429 | `BUDGET` | Token-Budget des Mandanten im Monat erschöpft (Req 6.4) | 11.3 |

- Bestehende Codes in neuer Lage: 403 `NICHT_ERLAUBT` für falschen Scope oder eine gemeldete Stufe über dem Schlüssel; 404 `NICHT_GEFUNDEN` für unsichtbare Dokumente (auch Stufe zu hoch); 503 `UEBERLASTET` bei voller `HashSperre`.
- Plugin: Problem Details → freundliche Meldung je Code (7.5), nie den `detail`-Text des Archivs an Besucher.

## Testing Strategy
Jedes Kriterium ohne Nachweis-Vermerk hat ein Cucumber-Szenario mit Tag `@req-<x.y>` (tech.md).

| Ebene | Schwerpunkt |
| --- | --- |
| Szenarien Backend (Cucumber) | Zugriffsstufen (drei Stufen, Kappung durch den Schlüssel, 403 bei zu hoher gemeldeter Stufe), `veroeffentlicht`, nie `INTERN`/`PERSONAL` öffentlich, Schlüssel eines Mandanten sieht nie den anderen, 404 statt 403, Rate-Limit, Budget, Rechte `geschaeftsstelle` nach Rechtematrix; Erfassung je Kriterium 10.x (Upload, Idempotenz mit zwei parallelen Uploads derselben Referenz, verworfene Referenz frei, Dublette mit und ohne Sichtbarkeit, Status, Liste, Datei ansehen, Bestätigen, Ändern mit 412/428, `veroeffentlicht` in `PATCH` → 400, Veröffentlichen/Zurückziehen, Wiederholen, kein Löschen und Verwerfen, Bearbeiter im Protokoll und im Benutzerverzeichnis, nicht gemeldeter Bearbeiter → 400); Schlüssel 12.x (Scope-Trennung, Rotation, Widerruf trotz Prüf-Cache, Absender, fremde Anfragen sperren die Webseite nicht aus); Stream 13.x (Medientyp, kein Dateiname, 429, Leerlauf); Feed 14.x (Arten, `stufe` beim Wechsel `oeffentlich` → `vorstand` und `mitglieder` → `vorstand`, 410, Stufe des Schlüssels, fremde Referenzen `null`); Lesen 5.8, 5.10, 5.11 (Filter und Umleitung bis zur Stufe, keine Seiten-URLs); Fehlerformat 15.x |
| JUnit | `PublicVertragTest` (11.2), `DateiStreamTest` (13.3), `AenderungFeedTest` (Trigger = Java-Filter; Cursor mit gleichzeitigen Transaktionen ohne Lücke; lang offene Transaktion; Löschen einer Zeile), `EndpunktWaechterTest` (`@WebseitenZugang`), `ErfassungRechteTest` (Positivliste nach 10.8), ArchUnit (kein HTTP-Client in `integration/webseite`, 14.5) |
| CI | Schritt «Vertrag `public-v1` nur additiv» im Build-Job (11.3) |
| Isolation | `MandantenIsolationIT` (Szenarien `@req-1.4`, `@req-9.1`, `@req-9.4`): urinet-Benutzer, vhf-gsk-Benutzer, Lese- und Erfassungs-Schlüssel beider Mandanten, Chat – Matrix aller Wege einschliesslich Erfassung, Stream, Feed und Referenzen; zwei Schlüssel desselben Mandanten mit verschiedenen Stufen |
| Sicherung | Restore-Probe stellt Schema und Bucket von `vhf-gsk` mit wieder her (Requirement 1.5) |
| Plugin | PHPUnit mit WP-Test-Suite (wp-env), Mock des Archivs nach `public-v1.yaml` (auch mit unbekannten Zusatzfeldern, 11.6); Cucumber.js + Playwright gegen wp-env: Liste, Suche (Fundstelle), Dokumentseite, Umleitung alter Links, Chatbot, Download (302 und Stream ohne Datei im Upload- und Temp-Verzeichnis, 429-Meldung), Gast vs. Mitglied vs. Vorstand, Feed verwirft Transients, Erfassung im WP-Admin |
| Sicherheit | OWASP API Top 10 Prüfliste, ZAP-Baseline gegen Staging; umfasst Erfassung (Upload ohne URL-Abruf, Massenzuweisung von `zugriff`/`veroeffentlicht`/`dateiname`, BOLA über `{id}` und `referenz`), Stream (Erschöpfung, Abbruch, langsame Clients), Feed, Filter und Umleitung, Stufe `vorstand`, Schlüssel-Trennung und Erschöpfung der Schlüsselprüfung durch fremde `ua_…`-Anfragen (Requirement 9.2, 12.8) |

## Open Points
- Entscheide vom 13.11.2026 (E-A bis E-D) stehen in `docs/umsetzungsplan.md`, Abschnitt 6; die mit «vorgeschlagen 13.11.2026» markierten Punkte gelten mit dem Merge dieses PR.
- Nicht Teil dieser Erweiterung (nicht spezifiziert):
  - Erschliessung der Bulletins bis auf Ebene Fachbericht (Titel und Seitenbereich je Fachbericht); heute liefert die Fundstelle nur die Seite des besten Abschnitts (5.7).
  - Aufbewahrung der öffentlichen Chat-Fragen: entschieden von Fabian am 16.11.2026: Standard 90 Tage, je Mandant einstellbar, VHF/GSK 30 Tage (Req 6.5).
  - Zeitpunkt: Die Webseite will die Suche schon in ihrer ersten Etappe (Dezember/Januar); diese Spec setzt Gate Phase 2 voraus. Bestimmt durch den Konzept-Entscheid E4 (nach dem 19.11.2026). Teilentscheid vom 14.11.2026 (Fabian, «Neutrale Vorstufen jetzt»): Die mandantenneutralen Teile 11.6 (Vertrag und Versionierung `/public/v1`) und 11.7 (Webseiten-Schlüssel) werden schon vor Gate Phase 2 und vor dem Zuschlag VHF/GSK gebaut, ohne Domain VHF/GSK, ohne Seed `vhf-gsk`, ohne Dateiauslieferung (11.8), Feed (11.9) und Erfassung (11.10); 11.1 und 11.8–11.13 sowie alles VHF-spezifische (Welle 10, 11.2–11.5, Welle 12) bleiben an Gate Phase 2, Zuschlag und E4 gebunden (tasks.md, Voraussetzungen).
- Verwerfen (A9) über die Erfassung: nicht angeboten, weil Verwerfen die Dateien unter `eingang/` löscht (Requirement 10.7, 10.8). Folge: Ein Dokument in `FEHLER`, das Wiederholen nicht behebt (z. B. beschädigte Datei), bleibt im Eingangskorb, bis die Geschäftsstelle es in der Web-App verwirft; eine korrigierte Datei kann sie über die Webseite unter neuer Referenz hochladen. Für diesen Fall bleibt die Web-App nötig; vorgelegt Fabian mit diesem PR.
- Begrenzung der Vertraulichkeit je Erfassungs-Schlüssel: Ein Erfassungs-Schlüssel sieht wie die Geschäftsstelle auch `INTERN` und `PERSONAL` (und Dokumente im Eingangskorb, die nach Spec 01, 2.21 als `PERSONAL` wirken). Eine Obergrenze je Schlüssel bräuchte eine Regel für den Eingangskorb.
- Schlüsselprüfung unter Last: Der Prüf-Cache (12.8) verhindert, dass fremde Anfragen die Webseite aussperren. Offen bleibt: Nach einem Neustart braucht die erste Anfrage der Webseite einen freien Platz der `HashSperre`, und fremde Anfragen können weiterhin die Tokens `generator` und `import` mit 503 treffen (wie heute, Spec 01). Eigene Plätze für `/public/` kosten je Platz rund 19 MiB Heap; Gemessen am 15.11.2026 in Aufgabe 11.7 (Zahlen im Abschnitt «Schlüsselverwaltung», Messung 15.11.2026): Trefferquote 100/100, `UEBERLASTET` nach 106–111 ms. Eigene Plätze für `/public/` bleiben bis zur Messung mit echtem Argon2id auf dem Produktionsrechner (Stufe B) offen.
- Gemeinsame Zähler für Grenzen und Prüf-Cache, falls die API je mit mehreren Instanzen läuft.
- Stillstand des Feeds durch lang offene Transaktionen (Abschnitt «Änderungs-Feed», Grenze des Cursors): bewusst hingenommen; dann gilt die Cache-Dauer der Webseite.
- Domain VHF/GSK (offen seit 2.2) bestimmt auch die Basis-URL im Vertrag.
