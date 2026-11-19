# Handbuch für Entwickler von Webseiten

Dieses Handbuch beschreibt die serverseitige Anbindung einer Webseite an die
Webseiten-Schnittstelle des Archivs. Der verbindliche Vertrag ist
[public-v1.yaml](./public-v1.yaml). Bei Abweichungen gilt der Vertrag; er wird
je Hauptversion versioniert.

## 1. Sicherheitsmodell

Die Schnittstelle ist für Server-zu-Server-Aufrufe bestimmt. Das Archiv gibt
CORS nicht frei. Webseiten-Schlüssel gehören ausschliesslich auf den Server der
Webseite: nie in JavaScript, HTML, eine App oder in den Browser-Speicher.

Jeder Schlüssel ist an genau eine Webseite und einen Mandanten gebunden. Der
Mandant kommt aus dem Schlüssel, nicht aus einem Pfad, Query-Parameter oder
Header. Ein Schlüssel mit Scope `webseite-lesen` darf nur lesen und chatten;
`webseite-erfassung` darf nur erfassen. Diese Anleitung behandelt den
Lesezugang; die Erfassung ist noch nicht verfügbar (siehe «Noch nicht
verfügbar» nach Abschnitt 5).

Das Archiv protokolliert jeden Lesezugriff, auch abgelehnte Stufen, ohne
Client-Hash und ohne IP. Die IP des Besuchers wird nie übermittelt und nicht
gespeichert.

Der Lese-Schlüssel legt die höchste erlaubte Zugriffsstufe fest. Die Webseite
meldet die Stufe des aktuellen Besuchers mit `X-Archiv-Zugriff`. Das Archiv
liefert nur Dokumente, die veröffentlicht und archiviert sind, die
Vertraulichkeit `NORMAL` haben und deren Stufe höchstens der gemeldeten Stufe
entspricht. Kein Parameter kann diesen festen Filter erweitern.

## 2. Schlüssel, Rotation und Absendernetze

Der Betreiber stellt für jede Webseite einen eigenen Lese-Schlüssel aus. Der
Schlüssel wird bei der Erstellung genau einmal angezeigt; behandelt ihn wie ein
Passwort und speichert ihn nur in der serverseitigen Secret-Verwaltung eurer
Webseite. Der Schlüssel wird als Bearer-Token mit jeder Anfrage gesendet.

Bei einer Rotation werden alter und neuer Schlüssel während einer befristeten
Überlappung akzeptiert. Der Standard beträgt sieben Tage, die Überlappung darf
höchstens 30 Tage dauern. Stellt zuerst die Webseite auf den neuen Schlüssel um
und entfernt den alten nach Ende der Überlappung. Bei Verlust oder Verdacht auf
Offenlegung muss der Schlüssel widerrufen werden.

Der Betreiber kann einen Schlüssel auf Absendernetze einschränken. Diese
Einschränkung wird serverseitig konfiguriert und ist an die Webseite gebunden,
nicht an einen einzelnen Schlüssel. Ist sie aktiviert und fehlt die passende
Konfiguration, lehnt das Archiv die Anfrage ab. Eine Anfrage von ausserhalb der
erlaubten Netze wird wie ein ungültiger Schlüssel mit `401 NICHT_ANGEMELDET`
abgelehnt. Die Webseite muss den Webseiten-Schlüssel daher immer von ihrem
Server senden; ein Besucher darf ihn weder erhalten noch selbst an das Archiv
senden.

## 3. Header und Zugriffsstufen

Alle Lese-Anfragen benötigen:

```http
Authorization: Bearer <LESE_SCHLUESSEL>
```

`X-Archiv-Zugriff` ist optional. Zulässig sind genau diese kleingeschriebenen
Werte:

| Wert | Bedeutung |
| --- | --- |
| `oeffentlich` | Öffentliche Publikationen |
| `mitglieder` | Öffentliche und Mitglieder-Publikationen |
| `vorstand` | Öffentliche, Mitglieder- und Vorstandspublikationen |

Fehlt der Header, gilt `oeffentlich` – auch bei einem Schlüssel mit höherer
Stufe. Der Header darf nie eine höhere Stufe als der Schlüssel anfordern; das
ergibt `403 NICHT_ERLAUBT`. Ein unbekannter, anders geschriebener, leerer oder
mehrfach gesendeter Wert ergibt `400 ANFRAGE_UNGUELTIG`. Das Archiv kappt eine
ungültige oder zu hohe Stufe nicht stillschweigend.

`X-Archiv-Client` ist optional und wird vor allem für die Chat-Begrenzung
verwendet. Die Webseite bildet den Wert aus der Besucher-IP und einem
tagesaktuellen Salz als SHA-256. Gesendet werden genau 64 Hexziffern in
Kleinbuchstaben; die IP selbst wird dem Archiv nicht übermittelt. Ohne diesen
Header teilen sich Besucher den gemeinsamen Client-Zähler der Webseite.

```http
X-Archiv-Zugriff: mitglieder
X-Archiv-Client: <64_KLEINBUCHSTABIGE_HEXZEICHEN>
```

## 4. Endpunkte

Die Beispiele verwenden absichtlich den reservierten Platzhalter-Host
`archiv.example.invalid` und Umgebungsvariablen für Zugangsdaten. Ersetzt den
Host durch die für eure Integration bereitgestellte Adresse. Gebt Schlüssel
und vorsignierte Datei-URLs nie in Logs aus.

### Dokumente auflisten

`GET /public/v1/dokumente` liefert eine Seite sichtbarer Dokumente. Filter:
`reihe` (Code), `jahr`, `ausgabe`; `seite` beginnt bei 1 (Standard 1),
`pro_seite` ist 1 bis 100 (Standard 20), `sortierung` ist `datum_ab`
(Standard), `datum_auf` oder `titel`. Unbekannte Parameter werden ignoriert.

```sh
curl --get 'https://archiv.example.invalid/public/v1/dokumente' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: mitglieder' \
  --data-urlencode 'reihe=MERKBLATT' \
  --data-urlencode 'jahr=2025' \
  --data-urlencode 'seite=1' \
  --data-urlencode 'pro_seite=20' \
  --data-urlencode 'sortierung=datum_ab'
```

Beispielantwort:

```json
{
  "eintraege": [
    {
      "id": "00000000-0000-4000-8000-000000000001",
      "titel": "Probe-Merkblatt",
      "reihe": {"code": "MERKBLATT", "name": "Merkblatt"},
      "ausgabe": "1",
      "jahr": 2025,
      "datum": "2025-03-01",
      "zugriff": "oeffentlich",
      "seiten": 2
    }
  ],
  "seite": 1,
  "proSeite": 20,
  "gesamt": 1
}
```

### Dokumente suchen

`GET /public/v1/suche` verlangt `q` (1 bis 200 Zeichen) und unterstützt
zusätzlich `reihe`, `jahr` und `ausgabe`. Die Suche liefert höchstens 30 Treffer
ohne Blättern. `degradiert: true` bedeutet, dass nur die Volltextsuche verwendet
wurde. `fundstelle` kann fehlen, wenn keine Seitenangabe bekannt ist (siehe «Noch nicht
verfügbar», 11.13).

```sh
curl --get 'https://archiv.example.invalid/public/v1/suche' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: oeffentlich' \
  --data-urlencode 'q=Merkblatt zum Wandern' \
  --data-urlencode 'reihe=MERKBLATT'
```

Beispielantwort:

```json
{
  "treffer": [
    {
      "id": "00000000-0000-4000-8000-000000000001",
      "titel": "Probe-Merkblatt",
      "reihe": {"code": "MERKBLATT", "name": "Merkblatt"},
      "ausgabe": "1",
      "jahr": 2025,
      "datum": "2025-03-01",
      "zugriff": "oeffentlich",
      "seiten": 2,
      "auszug": "Ein kurzer Auszug aus dem Dokument.",
      "fundstelle": {"seite": 1},
      "rang": 1
    }
  ],
  "degradiert": false
}
```

### Dokumentdetails abrufen

`GET /public/v1/dokumente/{id}` liefert die sichtbaren Metadaten und einen
Auszug (höchstens 300 Zeichen). Eine ungültige oder unbekannte ID sowie ein
Dokument ausserhalb des festen Filters ergeben dieselbe `404`-Antwort.

```sh
curl 'https://archiv.example.invalid/public/v1/dokumente/00000000-0000-4000-8000-000000000001' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: oeffentlich'
```

Beispielantwort:

```json
{
  "id": "00000000-0000-4000-8000-000000000001",
  "titel": "Probe-Merkblatt",
  "reihe": {"code": "MERKBLATT", "name": "Merkblatt"},
  "ausgabe": "1",
  "jahr": 2025,
  "datum": "2025-03-01",
  "zugriff": "oeffentlich",
  "seiten": 2,
  "auszug": "Ein kurzer Auszug aus dem Dokument."
}
```

### Filterwerte abrufen

`GET /public/v1/filter` liefert Reihen (Code und Name), für die es mindestens
ein sichtbares Dokument gibt. Reihen mit ausschliesslich nicht sichtbaren
Dokumenten werden nicht genannt.

```sh
curl 'https://archiv.example.invalid/public/v1/filter' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: mitglieder'
```

Beispielantwort:

```json
{
  "reihen": [
    {"code": "MERKBLATT", "name": "Merkblatt"}
  ]
}
```

### Alte URL auf ein Dokument abbilden

`GET /public/v1/umleitung?url=...` sucht die Zuordnung einer bisherigen
Webseiten-URL. Die URL darf ein Pfad oder eine vollständige URL sein; ausgewertet
wird der normalisierte Pfad. Die Antwort enthält ausschliesslich `dokumentId`.
Die Webseite baut daraus ihren eigenen Link zur Dokumentseite.

```sh
curl --get 'https://archiv.example.invalid/public/v1/umleitung' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: oeffentlich' \
  --data-urlencode 'url=/archiv/alte-ausgabe.pdf'
```

Beispielantwort:

```json
{
  "dokumentId": "00000000-0000-4000-8000-000000000001"
}
```

### Öffentlicher Datei-Link oder geschützter Stream

`GET /public/v1/dokumente/{id}/datei` wählt die Auslieferung nach der Stufe des
Dokuments. Die Details enthalten `zugriff`, damit die Webseite weiss, welche
Antwort sie erwartet:

- `oeffentlich`: `200 application/json` mit einer vorsignierten URL und
  `gueltigBis`. Die URL ist höchstens fünf Minuten gültig und selbst ein
  Zugangsschlüssel: nicht speichern, loggen oder an andere weitergeben.
- `mitglieder` oder `vorstand`: `200 application/pdf` als Stream direkt an den
  Webseitenserver, nie als Speicher-URL. Der Webseitenserver reicht die Blöcke
  an den Besucher weiter, ohne die ganze Datei im Speicher oder in einer
  temporären Datei abzulegen.

Öffentliche Datei-URL abfragen:

```sh
curl 'https://archiv.example.invalid/public/v1/dokumente/00000000-0000-4000-8000-000000000001/datei' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: oeffentlich'
```

Beispielantwort:

```json
{
  "url": "https://datei.example.invalid/vorsigniert/platzhalter",
  "gueltigBis": "2026-12-01T12:05:00Z"
}
```

Geschützten Stream abfragen (dieser Prüfaufruf verwirft den Körper):

```sh
curl --silent --show-error --dump-header - --output /dev/null \
  'https://archiv.example.invalid/public/v1/dokumente/00000000-0000-4000-8000-000000000001/datei' \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: mitglieder'
```

Beispielantwort-Header:

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Length: <DATEIGROESSE_IN_BYTES>
Cache-Control: private, no-store
X-Content-Type-Options: nosniff
Content-Disposition: inline
Accept-Ranges: none
```

Der Stream enthält die ganze Datei in Blöcken von 64 KiB. Ein `Range`-Header
wird ignoriert; die Antwort bleibt `200` mit der vollständigen Datei. `HEAD`
wird mit `405` abgelehnt. Pro Lese-Schlüssel und insgesamt sind höchstens acht
Streams gleichzeitig offen; bei voller Kapazität antwortet das Archiv mit
`429 ZU_VIELE_ANFRAGEN` und `Retry-After: 5`. Ein abgebrochener oder zu lange
inaktive Stream wird beendet; ein nicht erreichbarer Speicher ergibt
`503 SPEICHER_NICHT_ERREICHBAR` mit `Retry-After`.

Links auf Dokumentseiten – auch aus Listen, Suche, Umleitungen und Chatquellen –
erstellt die Webseite selbst aus der Dokument-ID. Der Datei-Endpunkt liefert
keine Seiten-URL.

### Öffentlichen Chat abfragen

`POST /public/v1/chat` nimmt `frage` (nach Trim 1 bis 500 Zeichen) und optional
eine UUID `gespraechId` entgegen. `curl --no-buffer` gibt die Server-Sent Events
(SSE) fortlaufend aus.

```sh
curl --no-buffer 'https://archiv.example.invalid/public/v1/chat' \
  --request POST \
  --header "Authorization: Bearer $ARCHIV_LESE_SCHLUESSEL" \
  --header 'X-Archiv-Zugriff: mitglieder' \
  --header "X-Archiv-Client: $ARCHIV_CLIENT_HASH" \
  --header 'Accept: text/event-stream' \
  --header 'Content-Type: application/json' \
  --data '{"frage":"Welche Merkblätter gibt es?","gespraechId":null}'
```

Beispielstream:

```text
event: werkzeug
data: {"werkzeug":"dokumente_suchen"}

event: text
data: {"delta":"Ich habe ein passendes Merkblatt gefunden."}

event: quellen
data: [{"dokumentId":"00000000-0000-4000-8000-000000000001","titel":"Probe-Merkblatt","reihe":{"code":"MERKBLATT","name":"Merkblatt"},"ausgabe":"1","jahr":2026,"fundstelle":{"seite":"3"}}]

event: fertig
data: {"gespraechId":"00000000-0000-4000-8000-000000000002","hinweis":"Antworten werden automatisch aus Verbandsdokumenten erstellt. Massgebend sind die verlinkten Originaldokumente; für verbindliche Auskünfte wenden Sie sich bitte an die Geschäftsstelle."}
```

`werkzeug` kann für eine Anzeige wie «Suche läuft» verwendet werden. Den Text
gibt das Archiv erst nach der Prüfung der Quellen aus, darum kommt er als ein
einziges `text`-Ereignis und nicht wortweise. `quellen` kommt genau einmal
unmittelbar vor `fertig`. Jede Quelle enthält `dokumentId`, Titel, Reihe,
Ausgabe, Jahr und Fundstelle – keine URL; `jahr` und `fundstelle` können `null`
sein. Verlinkt die Quelle auf eine Dokumentseite, die eure Webseite selbst aus
`dokumentId` bildet. Zeigt im Chatfenster den Text aus `fertig.hinweis`
unverändert an (je Mandant konfiguriert, oben der Wortlaut für VHF/GSK).

Bei einem Fehler nach Beginn des Streams folgt stattdessen ein `fehler`-Ereignis
mit festem Meldungstext; darauf folgt kein `fertig`. Eine fremde, abgelaufene
oder unbekannte Gesprächs-ID beginnt ein neues Gespräch. Ein Stufenwechsel
beginnt ebenfalls ein neues Gespräch, damit kein Verlauf einer höheren Stufe
weitergegeben wird.

Findet der Chat keine passende Publikation, bleibt `quellen` leer. Die Antwort
sagt das und verweist auf die Geschäftsstelle.

**Speicherung und Aufbewahrung.** Für den Besucher ist der Chat anonym. Das
Archiv speichert befristet die bereinigte Frage, die Quellen und die Tokens. Die
Frist beträgt standardmässig 90 Tage; der Betreiber kann sie je Mandant
abweichend festlegen. Client-Hash, IP, Antwort und Gespräch werden nie
gespeichert. Ein Gespräch lebt höchstens 30 Minuten im Speicher des Archivs. Der
Datenschutzhinweis der Webseite muss das berücksichtigen.

**Monatsbudget.** Der Betreiber begrenzt den Chat je Mandant und Monat nach
Tokens. Ist das Budget aufgebraucht, antwortet der Chat mit `429 BUDGET`
(siehe Abschnitt 5); Liste, Suche und Details bleiben nutzbar.

## 5. Fehler und Wiederholung

Fehler sind Problem Details nach RFC 9457 mit dem Medientyp
`application/problem+json`. Ausgewertet wird `fehlercode`, nicht der deutsche
Text. Die Antwort enthält `type`, `title`, `status`, `detail`, `instance` und
`fehlercode`; Titel und Detail sind feste Texte und enthalten keine
Anfragewerte oder internen Angaben.

| HTTP | Fehlercode | Behandlung auf der Webseite |
| --- | --- | --- |
| 400 | `ANFRAGE_UNGUELTIG` | Anfrage korrigieren; den gesendeten Wert nicht ungeprüft anzeigen. |
| 401 | `NICHT_ANGEMELDET` | `WWW-Authenticate: Bearer`; Schlüsselkonfiguration prüfen und Betreiber kontaktieren. Die Antwort unterscheidet fehlende, falsche, abgelaufene und widerrufene Schlüssel nicht. |
| 403 | `NICHT_ERLAUBT` | Scope und angeforderte Stufe prüfen; keine Wiederholung ohne Änderung. |
| 403 | `MANDANT_FEHLT` | Der Mandant des Schlüssels ist nicht nutzbar (zum Beispiel deaktiviert). Betreiber kontaktieren; nicht wiederholen. |
| 404 | `NICHT_GEFUNDEN` | Dokument oder Zuordnung als nicht verfügbar behandeln, ohne Existenz eines geschützten Dokuments anzudeuten. |
| 429 | `ZU_VIELE_ANFRAGEN` | `Retry-After` (Sekunden) abwarten. Bei lesenden Endpunkten bis dahin nur einen bewusst serverseitig zwischengespeicherten Inhalt derselben Stufe anzeigen. |
| 429 | `BUDGET` | Gilt für den Chat und enthält kein `Retry-After`; zeige «Der Chatbot ist diesen Monat ausgelastet – die Suche funktioniert weiterhin.» |
| 503 | `UEBERLASTET` | `Retry-After` abwarten und dann erneut versuchen. |
| 503 | `SPEICHER_NICHT_ERREICHBAR` | Betrifft Datei-Aufrufe. `Retry-After` abwarten. Der Code ist im Vertrag `public-v1.yaml` noch nicht aufgezählt; er wird dort in einem getrennten Schritt nachgezogen. |
| 500 | `INTERNER_FEHLER` | Nicht wiederholen, bis die Ursache geklärt ist; die Fehler-ID aus `instance` dem Betreiber melden. |

Bei `ZU_VIELE_ANFRAGEN` kann `grund` den begrenzten Bereich nennen. Im Chat
stehen insbesondere `STUNDENLIMIT` und `LAEUFT`, beim Lesen `ANFRAGEN` und
`SUCHE` (Werte siehe «Grenzen»). Unbekannte neue `fehlercode`- oder
`grund`-Werte müssen ohne
Sonderbehandlung anhand des HTTP-Status behandelt werden. Fehler dürfen nicht
durch rasche automatische Wiederholungen verstärkt werden.

Nach Beginn eines Chat-Streams ist ein HTTP-Statuswechsel nicht mehr möglich.
Behandle deshalb das SSE-Ereignis `fehler` ebenfalls und zeige nur dessen
festen Meldungstext.

### Grenzen

Das Archiv begrenzt die Anfragen. Die Werte unten sind **Startwerte des
Betreibers**, keine Zusage des Vertrags: Der Betreiber kann sie je Betrieb
ändern, und die Suchgrenze ist vorsichtig geschätzt (Entscheid offen). Die
Webseite darf sich nicht auf genau diese Zahlen verlassen, sondern muss `429`
mit `Retry-After` immer behandeln.

| Bereich | Startwert | `grund` bei 429 |
| --- | --- | --- |
| Lesen (Liste, Details, Filter, Umleitung) | 20 Anfragen je Sekunde und Lese-Schlüssel, Spitze 100 (Token-Bucket) | `ANFRAGEN` |
| Suche | 2 Anfragen je Sekunde und Lese-Schlüssel, Spitze 10 (Token-Bucket) | `SUCHE` |
| Chat je Besucher | 20 Fragen je Stunde und `X-Archiv-Client` | `STUNDENLIMIT` |
| Chat je Webseite | 600 Fragen je Stunde und Webseite | `STUNDENLIMIT` |
| Chat gleichzeitig | eine laufende Frage je Client | `LAEUFT` |
| Datei-Streams | höchstens 8 gleichzeitig (insgesamt und je Lese-Schlüssel) | kein `grund` |

Bei `429` wartet die Webseite die Sekunden aus `Retry-After` ab und wiederholt
erst danach, ohne Parallelität zu erhöhen. Das Monatsbudget des Chats ist eine
eigene Grenze (`BUDGET`, ohne `Retry-After`).

## Noch nicht verfügbar

Folgendes ist laut Aufgabenliste (Spec 03) noch offen. Die Webseite darf es
heute nicht voraussetzen:

- **11.9 Änderungs-Feed:** Es gibt keinen Feed. Die Stufenregeln aus Abschnitt 3
  gelten für ihn erst, wenn er umgesetzt ist. Bis dahin liest die Webseite
  Liste und Suche.
- **11.10 Erfassung über eine Webseite:** Es gibt keine Erfassungs-Endpunkte. Der
  Scope `webseite-erfassung` ist nur ausgestellt, aber nicht nutzbar.
- **11.13 Fundstelle in Suchtreffern:** `fundstelle.seite` ist nicht
  verlässlich gefüllt. Behandle das Feld immer als optional; das Beispiel
  oben zeigt den Fall mit Seitenangabe.
- **11.6 Vertrag und Versionierung (Rest):** Die automatische Prüfung im
  CI-Build und die Drosselung je IP im Reverse-Proxy fehlen noch. Die
  Abkündigungs-Header (`Deprecation`, `Sunset`, `Link`) sind angelegt, aber nur
  bei einer Abkündigung gesetzt.

## 6. Zwischenspeicherung

Jede Antwort der Schnittstelle – auch Fehlerantworten – enthält
`Cache-Control: no-store` (der Datei-Stream sendet `private, no-store`). Browser, CDN und HTTP-Proxy dürfen die Antwort daher
nicht automatisch zwischenspeichern. Eine Webseite darf nur dann bewusst in
ihrer serverseitigen Anwendungslogik cachen, wenn sie die Stufe als Teil des
Cache-Schlüssels verwendet. `oeffentlich`, `mitglieder` und `vorstand` sind
getrennte Cache-Bereiche; ein Eintrag einer höheren Stufe darf nie für eine
niedrigere Stufe ausgegeben werden. Dokument-Links und vorsignierte Datei-URLs
sind keine Cache-Inhalte.

## 7. Änderungen und Versionierung

Innerhalb von `/public/v1` sind nur additive Änderungen erlaubt. Entfernte oder
umbenannte Felder, geänderte Typen, neue Pflichtparameter und neue Werte nicht
erweiterbarer Felder benötigen eine neue Hauptversion, zum Beispiel
`/public/v2`. Die alte Hauptversion bleibt ab Bereitstellung der neuen
mindestens sechs Monate verfügbar.

Ignoriert unbekannte Antwortfelder. Bei Feldern, die der Vertrag ausdrücklich
als erweiterbar kennzeichnet, muss die Webseite unbekannte Werte als allgemeinen
Fall behandeln. Bei Fehlern entscheidet der HTTP-Status, danach der bekannte
`fehlercode`; Meldungstexte sind nicht stabil.

## 8. Checkliste «Anbindung abgenommen»

- [ ] Alle Aufrufe gehen vom Webseitenserver aus; Schlüssel sind nie im Browser.
- [ ] Der Lese-Schlüssel gehört zur richtigen Webseite und zum richtigen Mandanten.
- [ ] Rotation wurde mit Überlappung getestet; der alte Schlüssel wird danach entfernt.
- [ ] Eine allfällige Absendernetz-Einschränkung ist durch den Betreiber eingerichtet.
- [ ] Zugriffsstufen und die Ablehnung einer zu hohen oder falsch geschriebenen Stufe sind geprüft.
- [ ] Liste, Suche, Details, Filter und Umleitung verwenden nur die Antworten des Vertrags.
- [ ] Links auf Dokumentseiten werden auf der Webseite aus der Dokument-ID gebildet.
- [ ] Eine vorsignierte Datei-URL wird weder gespeichert noch protokolliert oder weitergegeben.
- [ ] Der Chat zeigt den Quellenhinweis, verlinkt Quellen über `dokumentId` und begrenzt Fragen auf 500 Zeichen.
- [ ] `no-store`, stufengetrennte serverseitige Anwendungs-Caches und die Fehlerfälle 401, 403, 404, 429 und 503 sind geprüft.
- [ ] Unbekannte Antwortfelder und erweiterbare Werte werden verträglich behandelt.

Im Repository [VHF-GSK](https://github.com/Urinet-ch/VHF-GSK) liegt ein
separater [Anbindungstest](https://github.com/Urinet-ch/VHF-GSK/tree/main/tools/anbindungstest).
Er wird hier nur referenziert und nicht kopiert.
