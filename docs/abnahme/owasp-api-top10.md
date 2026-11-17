# OWASP API Security Top 10 (2023): codebasierte Prüfliste

## Umfang und Ergebnis

Statische Prüfung des Quellstands `2ccc3ecf` (`origin/main` am 17.11.2026; gegenüber der ersten Fassung `dd80b6a8` änderten sich nur Frontend und zwei Feature-Dateien unter `dokument` und `suche`, die Backend-Quellen und alle zitierten Zeilen sind unverändert) gegen Req 9.2 sowie die Anforderungen 11–15 von Spec 03. Geprüft wurden die vorhandenen Endpunkte der Webseiten-Schnittstelle und die zugehörigen Tests. Es wurden weder Staging noch produktive Systeme angesprochen; die ZAP-Baseline gegen Staging bleibt offen.

Ergänzung F1 am 17.11.2026 (Aufgabe 12.3 Teil 2, aufbauend auf #253 und #263): Die Erfassung ist implementiert. Die folgenden aktualisierten Abschnitte API1, API3, API4, API5, API7 und API9 beziehen ihre Erfassungsnachweise aus `features/sicherheit/mandanten-isolation.feature` (Grössen: Zeile 12; Scope-Matrix: 25; ID-Raten: 36; Hilfslisten: 48; Referenzen: 60 und 89; Bearbeiter: 103), `features/integration/webseiten-erfassung.feature` und `features/integration/webseiten-pflege.feature` sowie den dort genannten JUnit-Tests. F2–F4 wurden in diesem Teil nicht erneut beurteilt.

«Lücke» bezeichnet hier eine fehlende Sicherheitsabdeckung oder eine nicht umgesetzte Funktion. Sie ist nicht gleichbedeutend mit einer bestätigten ausnutzbaren Schwachstelle. In den vorhandenen Lese-, Datei-, Feed- und Chat-Endpunkten wurde kein hoch oder kritisch eingestufter, bestätigter Befund festgestellt.

## Abdeckung der Punkte aus Req 9.2

| Punkt | Befund | Beleg oder Folgeaufgabe |
|---|---|---|
| Lesen, Suche, Filter, Umleitung fremder Mandanten | ok | API1; `webseiten-lesen.feature:311`, `:330` |
| Datei und Stream fremder Mandanten | ok | `webseiten-datei.feature:66` |
| Feed und Referenzen fremder Mandanten | ok | `webseiten-feed.feature:153`, `:192` |
| Chat fremder Mandanten und Stufen | ok | `oeffentlicher-chat.feature:363` |
| Stufe `vorstand` und zwei Schlüssel desselben Mandanten mit verschiedenen Stufen (Req 9.4) | ok | API1; `webseiten-lesen.feature:344`, `:363`; `zugriffsstufen.feature:47`; `webseiten-datei.feature:26`, `:40` |
| Erfassung | ok, Code- und Szenariennachweis | Aufgabe 11.10; F1 unten; `mandanten-isolation.feature` |
| Erschöpfung der Schlüsselprüfung durch fremde Anfragen | Lücke, mittel | API2; Folgeaufgabe F2 |

**Folgeaufgaben** (stehen als Vermerk in `tasks.md` unter 12.3; keine Haken gesetzt):

- **F1 – erledigt (Code-/Testnachweis):** Erfassung (Aufgabe 11.10) mit Grenzen für Requestgrösse, Dateigrösse und Rate (API4), Feld-Allowlist und Schutz vor Massenänderungen (API3), automatischer Scope-Matrix für beide Schlüsselarten und Mandanten (API5), Vertrag und Endpunktinventar (API9) sowie ausschliesslich Datei-Bytes ohne URL-Abruf (API7). Der manuelle ZAP-Nachweis bleibt getrennt offen.
- **F2:** Test «N ungültige Schlüssel einer Quelle blockieren einen kalten Schlüssel und einen Erfassungs-Schlüssel nicht», Drosselung je Quelle vor der `HashSperre` (Traefik, Aufgabe 11.6, #241) und Klärung der Vertrauensfrage bei `X-Real-Ip`.
- **F3:** Budget des Chats als harte Obergrenze (Parallelität, Überschreiten durch die letzte Frage, Fehler beim Schreiben der Nutzung) mit Test.
- **F4:** Test zum Fehlerpfad des Pro-Schlüssel-Limiters (fail open, bewusster Entscheid in design.md Spec 03, «Grenzen») mit Kennzahl `archiv_webseite_grenze_ausgefallen_total` als Alarm.

## API1: Broken Object Level Authorization (BOLA)

**Bedeutung:** Eine Objekt-ID oder ein Filter darf keine Daten ausserhalb des Mandanten oder der für den Schlüssel zulässigen Zugriffsstufe liefern.

**Belege:** `ApiTokenIdentityProvider` bindet Identität, Mandant und Scope (`backend/src/main/java/ch/urinet/archiv/sicherheit/adapter/in/auth/ApiTokenIdentityProvider.java:54-66`); `MandantAufloesung` und `MandantTenantResolver` lösen den Mandanten fail-closed auf (`backend/src/main/java/ch/urinet/archiv/mandant/application/MandantAufloesung.java:25-52`, `backend/src/main/java/ch/urinet/archiv/mandant/adapter/out/persistence/MandantTenantResolver.java:22-30`). `FesterFilter` beschränkt veröffentlichte Leseergebnisse auf die effektive Stufe (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/FesterFilter.java:26-39`); die SQL-Abfragen für Liste, Suche, Details, Filter und Umleitung binden Filter und Eingaben (`backend/src/main/java/ch/urinet/archiv/integration/adapter/out/persistence/SqlWebseitenLese.java:61-147`). Die gemeldete Stufe (`oeffentlich`, `mitglieder` oder `vorstand`) darf nicht höher als die des Schlüssels sein; die Filterprüfung weist dies zurück (`backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/LeseZugriffFilter.java:63-88`). Stufentrennung innerhalb eines Mandanten: `webseiten-lesen.feature:344` (ein öffentlicher Schlüssel sieht keine höheren Stufen), `:363` (gemeldete Stufe über der des Schlüssels ergibt 403), `zugriffsstufen.feature:47`, `webseiten-datei.feature:26` und `:40` (Stufen `mitglieder` und `vorstand` nur als Stream, sonst 404). Mandantenübergreifende Szenarien: `webseiten-lesen.feature` (Zeilen 310-330), `webseiten-feed.feature` (152-153), `webseiten-datei.feature` (92) und `oeffentlicher-chat.feature` (362-386).

**Erfassung:** `mandanten-isolation.feature` prüft alle dokumentbezogenen Erfassungsaktionen aus dem automatisch ermittelten Inventar in beide Mandantenrichtungen: fremde ID und unbekannte ID ergeben dieselben Problem Details (nur `instance` unterscheidet sich), ohne ETag oder Location; eine eigene positive Gegenprobe schliesst einen pauschal gesperrten Endpunkt aus. Weitere Szenarien prüfen Eingangskorb, Liste, mandanteneigene Formulararten, dieselbe Referenz in zwei Mandanten und zwei Webseiten sowie getrennte Bearbeiterverzeichnisse. `webseiten-feed.feature:192` belegt getrennte Erfassungs-Feeds, `:113` die Referenzsicht je Webseite; `mandanten-isolation.feature` ergänzt Status, Details und Liste.

**Befund:** ok für die vorhandenen Lese-, Erfassungs-, Datei-, Such-, Feed- und Chat-Pfade.

**Restrisiko:** Die Webseite meldet die Zugriffsstufe ihrer Besucherin oder ihres Besuchers; das Archiv begrenzt sie auf die Höchststufe des Schlüssels, verifiziert aber keine Website-Mitgliedschaft. Ein gestohlener Schlüssel erlaubt Zugriff bis zu seiner konfigurierten Höchststufe.

## API2: Broken Authentication

**Bedeutung:** Schlüssel müssen sicher geprüft, widerrufen und abgelaufen behandelt werden; ungültige Schlüssel dürfen die Prüfung nicht unbegrenzt auslasten.

**Belege:** Bearer-Authentifizierung und Argon2id-Prüfung mit Attrappenvergleich für unbekannte IDs (`backend/src/main/java/ch/urinet/archiv/sicherheit/adapter/in/auth/ApiTokenAuthMechanism.java:32-45`, `backend/src/main/java/ch/urinet/archiv/sicherheit/application/ApiTokenPruefung.java:86-110`). Webseiten-Schlüssel sind an Scope, Webseite und Zugriffsstufe gebunden; Rotationsüberlappung höchstens 30 Tage (`backend/src/main/java/ch/urinet/archiv/sicherheit/application/WebseitenSchluesselVerwaltung.java:24-25,76-94`).

- **Widerruf:** `webseiten-vertrag.feature:12` (widerrufener Schlüssel ergibt 401) und `ApiTokenPruefungTest.widerrufUndAblaufWirkenTrotzPruefCacheSofort`.
- **Ablauf:** `ApiTokenPruefungTest.widerrufUndAblaufWirkenTrotzPruefCacheSofort` und `.abgelaufenerPruefCacheRechnetArgon2Erneut` (Ablauf des Prüf-Caches); Ablauf nach Rotation über `webseiten-schluessel.feature:44` und `:50`.
- **Rotation:** `webseiten-schluessel.feature:44` (Überlappung, beide Schlüssel gültig) und `:50` (ohne Überlappung wird der alte Schlüssel sofort 401).
- **Attrappe und Überlast:** `ApiTokenPruefungTest.unbekannteIdVergleichtTrotzdemEinmalGegenDieAttrappe`, `.ohnePlatzInDerSperreUeberlastetOhneArgon2`.

**Schutzschichten gegen Erschöpfung der Schlüsselprüfung (Req 9.2):**

1. `HashSperre`: Semaphor mit 2 Plätzen und 200 ms Wartezeit, geteilt von allen Schlüsselprüfungen und dem Anlegen von Tokens, auch für unbekannte IDs (Attrappe, `ApiTokenPruefung.java:86-91`; `application.properties:149-154`). Sie begrenzt die Argon2id-Arbeit, liefert bei Auslastung aber 503 `UEBERLASTET` auch an legitime Schlüssel.
2. Prüf-Cache (Req 12.8, `ApiTokenPruefung.java:94-108`, Standard 5 Minuten, höchstens 1000 Einträge, nur für `webseite-*`): Ein warmer Webseiten-Schlüssel braucht keinen Platz. Belege: Szenario «Ein warmer Schlüssel bleibt erreichbar, wenn die HashSperre ausgelastet ist» (`webseiten-schluessel.feature:72`) und `ApiTokenPruefungTest.gueltigerWebseitenSchluesselWartetBeiAusgelasteterHashSperreNicht`.
3. Drosselung je Quell-IP in Traefik: Middleware `archiv-drossel` (10/s, Spitze 50) auf allen Pfaden des Archivs (Spec 01 design.md, Aufgabe 1.9, Restrisiko ebenda; `deploy/infomaniak/compose.yml:108-110`), solange `/public/` über den Router der App läuft (Spec 03 design.md, «Grenzen»). Eine eigene Drosselung für `/public/` (`archiv-public-drossel`, 50/s) ist nur vorgeschlagen (17.11.2026, #241, Aufgabe 11.6) und nicht ausgerollt. Diese Schicht liegt ausserhalb der Anwendung; im Repository belegt sie kein Test, sie ist Konfiguration und je IP wirksam, mehrere Quellen umgehen sie.

**Befund:** Lücke – mittel (Verfügbarkeit), nicht «ok». Mehrere Quellen mit formal gültigen, aber falschen `Authorization: Bearer ua_…`-Anfragen können die zwei Plätze der `HashSperre` dauerhaft belegen. Warme Webseiten-Schlüssel bleiben erreichbar (Schicht 2). Kalte Webseiten-Schlüssel (Erstaufruf, Rotation, Neustart, Ablauf des Caches, voller Cache), Erfassungs-Schlüssel und die internen Tokens `generator` und `import` erhalten dann 503 `UEBERLASTET` (so auch Spec 03 design.md, «Schlüsselprüfung unter Last»). Kein Test belegt kalte Schlüssel und Erfassungs-Schlüssel unter Fremdlast, und die Quell-IP wird in der Anwendung für keine Drosselung genutzt (`X-Real-Ip` geht nur als Absenderprüfung in `ApiTokenAnfrage`). Die Schwere ist mittel: Es gibt keinen Authentifizierungsbypass und keinen Datenabfluss, die Wirkung ist eine vorübergehende Nichtverfügbarkeit ohne Anmeldung. Sie bleibt bis zu F2 offen; die Drosselung im Reverse-Proxy kommt laut Spec 03 tasks.md 11.6 vor dem Livegang der Webseite von VHF/GSK, bis dahin laufen nur Urinet und Testmandanten.

**Restrisiko:** Fremde Anfragen mit ungültigen Schlüsseln können die begrenzten Plätze belegen; legitime kalte Schlüssel erhalten dann Überlast statt unbegrenzt auf weitere Argon2id-Arbeit zu warten. Folgeaufgabe F2 (Test, Drosselung je Quelle, `X-Real-Ip`-Vertrauen).

## API3: Broken Object Property Level Authorization (BOPLA)

**Bedeutung:** Antworten dürfen nur freigegebene Felder enthalten; Schreibpfade müssen geschützte Felder wie Zugriffsstufe und Veröffentlichung gegen unzulässige Massenänderungen absichern.

**Belege:** Vorhandene Leseantworten werden über explizite DTO-Felder aufgebaut (`backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/WebseitenLesenResource.java:104-188`). Verhaltensbelege der Leseseite: `webseiten-lesen.feature:166` (Felder «id,titel,reihe,ausgabe,jahr,datum,zugriff,seiten,auszug» und nur diese) und `:171-178` (nie Dateiname, Vertraulichkeit, Status, Partner oder eine URL). Die Erfassung verwendet ebenfalls eigene DTOs. `ErfassungsMetadatenTest.unerlaubteFelderTypenUndMehrdeutigesJsonSindRot` und `webseiten-pflege.feature:42` prüfen die strikte Feld-Allowlist für PATCH und Bestätigen, einschliesslich `dateiname`, `mandant`, `status`, direkter Veröffentlichung, falscher Typen, doppelter Schlüssel und nachfolgenden JSONs. `webseiten-pflege.feature:20` prüft den vollständigen Rollback bei ungültiger Bestätigung. Veröffentlichung und Rückzug sind eigene, versionsgeprüfte Aktionen (`:86`); A8 gilt für Vertraulichkeit und Personenzuordnung. `ErfassungsMetadatenTest.personenzuordnungIstNurBeimBestaetigenWieInDerWebAppErlaubt` belegt die engere PATCH-Allowlist.

**Befund:** ok für Lese- und Erfassungswege; die bisherige Prüfbarkeitslücke F1 ist geschlossen.

**Restrisiko:** Neue Schreibfelder müssen weiter ausdrücklich in Allowlist, Vertrag und Negativtests aufgenommen werden.

## API4: Unrestricted Resource Consumption

**Bedeutung:** Anfragen, Suchvorgänge, Uploads, Chats und Dateistreams müssen begrenzt werden, damit einzelne Schlüssel keine unverhältnismässigen Ressourcen verbrauchen.

**Belege:** Pro-Schlüssel-Grenzen für Lesen und Suche sowie getrennte Grenzen für Chat sind in `WebseitenGrenzen` und `OeffentlicheGrenzen` implementiert (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/WebseitenGrenzen.java:17-29,106-155`; `backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheGrenzen.java:18-29,86-117`). Streams sind global und je Schlüssel begrenzt und haben Zeitlimits (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/DateiPlaetze.java:11-17,46-97`; `backend/src/main/java/ch/urinet/archiv/integration/application/webseite/StreamLauf.java:17-28,88-115`). Der Feed ist auf höchstens 500 Einträge begrenzt (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/AenderungFeed.java:29-30,54-62`). Szenarien dazu: `webseiten-grenzen.feature:12-65` und `webseiten-datei.feature:150-203`.

**Erfassung:** `mandanten-isolation.feature` prüft am echten Multipart-Endpunkt 51 MiB gegen die Dateigrenze von 50 MiB und 61 MiB gegen die Test-Anfragegrenze von 60 MiB: jeweils 413, keine Dokumente oder Referenzbindung. `webseiten-grenzen.feature:42` prüft die stündliche Uploadgrenze am echten Upload-Endpunkt (im Test 3 statt 120/h); `WebseitenGrenzenTest.uploadsSindProStundeBegrenztUndZaehlenImErfassungBucketMit` prüft den gemeinsamen Bucket. `WebseitenGrenzenTest.pruefdateienHabenEngereSpitzeUndZaehlenAuchImErfassungsbucket` belegt die zusätzliche Dateigrenze von 1/s, Spitze 2. `DateiStreamTest` prüft den tatsächlichen Erfassungs-Stream mit 50 MiB und weniger als 1 MiB Thread-Allokation; `WebseitenErfassungKnapperHeapTest` belegt nur den Hash-Pfad mit 96 MiB bei 64-MiB-Heap, ausdrücklich nicht das Multipart-Spooling.

**Befund:** Erfassungsgrenzen nachgewiesen, F1 geschlossen. Unverändert separat: Der Fehlerpfad des Pro-Schlüssel-Limiters lässt bei einem unerwarteten Laufzeitfehler weiterlaufen; ein vom Angreifer auslösbarer Fehler wurde nicht nachgewiesen. Das Verhalten ist im Design als Entscheid begründet (fail open, Kennzahl `archiv_webseite_grenze_ausgefallen_total`); Folgeaufgabe F4 bleibt ausserhalb dieses Teilberichts.

**Restrisiko:** Für den bestehenden Limiter bleibt ein niedriges Restrisiko bei unerwarteten internen Fehlern (F4).

## API5: Broken Function Level Authorization

**Bedeutung:** Jeder Endpunkt muss den passenden Scope und die erforderlichen Rechte erzwingen; Lese- und Erfassungsschlüssel dürfen nicht austauschbar sein.

**Belege:** `ZugangFilter` verlangt den passenden Webseiten-Scope und weist nicht annotierte Pfade geschlossen ab (`backend/src/main/java/ch/urinet/archiv/sicherheit/adapter/in/rest/ZugangFilter.java:53-109`). `EndpunktWaechterTest.jederEndpunktFragtDieRegelAb` kontrolliert die REST-Endpunkte; der Wächter weist öffentliche Methoden ohne Zugangsannotation zurück (`backend/src/test/java/ch/urinet/archiv/sicherheit/EndpunktWaechterTest.java:86-95,168-190`). Szenarien prüfen die Trennung von Lese- und Erfassungsschlüsseln (`backend/src/test/resources/features/sicherheit/webseiten-schluessel.feature:15-23`).

**Erfassung:** `mandanten-isolation.feature` durchläuft für beide Mandanten alle produktiven REST-Endpunkte aus `WebseitenEndpunkte.alle()` mit der jeweils falschen Schlüsselart: Lese-Schlüssel gegen sämtliche Erfassungswege einschliesslich Upload, Bearbeitermeldung, Feed, Hilfslisten und aller Mutationen; Erfassungs-Schlüssel gegen sämtliche Lesewege einschliesslich Chat. Jede Anfrage erwartet 403 `NICHT_ERLAUBT`. Das Inventar entsteht aus REST- und Zugangsannotationen, nicht aus einer handgepflegten Routenliste; der Endpunktwächter erzwingt die Annotation.

**Befund:** ok für implementierte Endpunkte; Prüfbarkeitslücke F1 geschlossen.

**Restrisiko:** Die Scope-Matrix prüft die produktiven Endpunkte, nicht deaktivierte Test-Proben.

## API6: Unrestricted Access to Sensitive Business Flows

**Bedeutung:** Chat und Änderungs-Feed dürfen nicht missbraucht werden, um Kosten oder Verarbeitung ausserhalb der vorgesehenen Grenzen auszulösen.

**Belege:** Der Chat begrenzt Anfragen je Client und Webseite sowie parallele Fragen; Gesprächszustand wird an Mandant, Webseite, Client und Zugriffsstufe gebunden (`OeffentlicheGrenzen.java:90-117`; `backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheGespraeche.java:64-73,109-112`). Das Monatsbudget wird vor einer Frage geprüft und die Nutzung danach atomar addiert (`backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheFragen.java:125-143`; `backend/src/main/java/ch/urinet/archiv/ki/adapter/out/persistence/JdbcMandantBudget.java:52-95`). Das Szenario `oeffentlicher-chat.feature:237-260` hält das erwartete Verhalten bei Überschreiten des Restbudgets fest. Der Feed ist begrenzt und ruft keine Webseiten auf (`AenderungFeed.java:29-30,54-75`; Spec 03 Req 14.5).

**Befund:** Lücke – mittel, beim Budget als harte Ausgabengrenze. Eine einzelne Frage, die das Restbudget überschreitet, wird noch beantwortet; zudem protokolliert der Modelladapter einen Fehler beim Schreiben der Nutzung und fährt fort (`backend/src/main/java/ch/urinet/archiv/ki/adapter/out/langchain4j/LangChain4jOeffentlicherChatAgent.java:143-156`). Das belegt ein begrenztes Überschreitungs- und Abrechnungsrisiko, keinen Rechte- oder Mandantenbypass.

**Restrisiko:** Parallele Fragen können das verbleibende Budget überschreiten. Die gewünschte Semantik einer harten Ausgabengrenze ist von der aktuellen, durch das Szenario bestätigten Überschreitung zu unterscheiden. Folgeaufgabe F3.

## API7: Server-Side Request Forgery (SSRF)

**Bedeutung:** Vom Aufrufer gelieferte URLs dürfen den Server nicht dazu bringen, beliebige interne oder externe Ziele abzurufen.

**Belege:** Alte URLs werden normalisiert und als gebundener Datenbankwert gesucht; die URL wird nicht abgerufen (`backend/src/main/java/ch/urinet/archiv/integration/domain/webseite/AlteUrl.java:31-70`; `backend/src/main/java/ch/urinet/archiv/integration/adapter/out/persistence/SqlWebseitenLese.java:138-147`). In den geprüften öffentlichen Webseiten- und Chat-Pfaden wurde kein ausgehender HTTP-Aufruf festgestellt. Spec 03 Req 10.2 verlangt Datei-Bytes statt eines abzurufenden Datei-Links.

**Erfassung:** `webseiten-erfassung.feature:106` sendet am echten Upload einen zusätzlichen Multipart-Teil `url`: 400 `ANFRAGE_UNGUELTIG`, keine Dokumente oder Referenzbindung. Der Upload nimmt genau Datei-Bytes und Referenz entgegen; die Metadaten-Allowlist bietet keinen URL-Import. Die Datei wird nie über eine vom Aufrufer angegebene Adresse geholt.

**Befund:** kein URL-Abrufpfad in den geprüften öffentlichen Routen; F1 belegt.

**Restrisiko:** Künftige Erweiterungen dürfen keinen URL-Import ohne neue Prüfung ergänzen.

## API8: Security Misconfiguration

**Bedeutung:** CORS, Cache, Sicherheitsheader und Fehlerantworten müssen so konfiguriert sein, dass keine unnötigen Daten offengelegt werden.

**Belege:** `WebseitenRoute` setzt `no-store` auch für Fehlerantworten (`backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/WebseitenRoute.java:103-148`). Problem Details enthalten feste Texte statt interner Fehlerdetails (`backend/src/main/java/ch/urinet/archiv/gemeinsam/adapter/in/rest/ProblemAntwort.java:14-22,54-83`; `ProblemFehlerRoute.java:99-122`). Streams setzen `nosniff` und `no-store` (`WebseitenLesenResource.java:315-325`). Tests: `WebseitenRouteTest.ohneSunsetNurNoStore`, `.internerPfadIstVonAussenGesperrt` sowie Szenarien in `webseiten-vertrag.feature:18-46`. CORS: `zugriffsstufen.feature:178-180` («Die Webseiten-Schnittstelle gibt kein CORS frei», je Methode kein `Access-Control-Allow-Origin` bei gesetztem Herkunftskopf) und `webseiten-lesen.feature:392-395` («Jede Antwort ist nicht zwischenzuspeichern und ohne CORS»).

**Befund:** ok für statische Konfiguration, Cache- und Fehlerverhalten. CORS ist in der Konfiguration nicht aktiviert und durch die zwei genannten Szenarien zur Laufzeit belegt.

**Restrisiko:** Gemäss Vertrag bleibt CORS deaktiviert; Schlüssel gehören nur auf den Webseitenserver.

## API9: Improper Inventory Management

**Bedeutung:** Vertrag, implementierte Routen und deren Sicherheitsdeklarationen müssen übereinstimmen und vollständig inventarisiert sein.

**Belege:** `PublicVertragTest.codeUndDateiBeschreibenDieselbenPfade` gleicht Code und Vertrag ab (`backend/src/test/java/ch/urinet/archiv/integration/PublicVertragTest.java:61-65`); `EndpunktWaechterTest` prüft die Sicherheitsdeklarationen (`backend/src/test/java/ch/urinet/archiv/sicherheit/EndpunktWaechterTest.java:86-95`). Der Vertrag führt Lese-, Chat- und Feed-Endpunkte auf (`docs/schnittstelle/public-v1.yaml`).

**Erfassung:** Der Vertrag enthält Upload, Bearbeitermeldung, Status/Liste/Details, Hilfslisten, PATCH, Bestätigen, Veröffentlichen/Rückzug, Wiederholen und Prüfdatei sowie den Erfassungs-Feed. `PublicVertragTest` gleicht Pfade und DTO-Schemas mit dem Scanner ab. `WebseitenEndpunkte.alle()` ermittelt die produktiven Methoden automatisch; die ausführbare Scope-Matrix prüft jede Methode. Test-Fixtures werden über ArchUnits `DO_NOT_INCLUDE_TESTS` ausgeschlossen.

**Befund:** ok, bisherige Funktions-/Prüflücke F1 geschlossen.

**Restrisiko:** Neue dokumentbezogene Methoden verlangen eine eigene positive Gegenprobe in der ID-Matrix; sonst schlägt diese bewusst fehl.

## API10: Unsafe Consumption of APIs

**Bedeutung:** Aufrufe externer Dienste und Werkzeuge müssen begrenzt und kontrolliert sein; nicht vertrauenswürdige Dokument- oder Benutzereingaben dürfen keine unzulässigen Aktionen auslösen.

**Belege:** Die KI-Basis-URL wird beim Start auf Schema und Host geprüft (`backend/src/main/java/ch/urinet/archiv/ki/adapter/out/KiBasisUrlPruefung.java:93-160`; `ChatModell.java:12-21,67-78`). Der öffentliche Agent stellt nur Suche und Anzeige bereit; Treffer werden erneut gegen den Zugriffsschutz geprüft (`backend/src/main/java/ch/urinet/archiv/ki/adapter/out/langchain4j/OeffentlicherAgent.java:12-29`; `backend/src/main/java/ch/urinet/archiv/ki/adapter/in/werkzeug/OeffentlicheWerkzeuge.java:20-26,75-93`; `backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheAbfragen.java:146-163`). Dokumenttext wird vor dem Modellaufruf neutralisiert; ein Prompt-Injection-Szenario steht in `oeffentlicher-chat.feature:147-165`. Ein Mail-Aufruf im geprüften öffentlichen Pfad wurde nicht festgestellt.

**Befund:** teilweise ok; kein unzulässiger Werkzeug- oder Mailpfad festgestellt.

**Restrisiko:** Die ursprüngliche Besucherfrage wird dem Modell unverändert übermittelt; nur die gespeicherte Fassung wird bereinigt (`OeffentlicheFragen.java:110-117,211-219`). Die Bereinigung erkennt Namen ausdrücklich nicht (`backend/src/main/java/ch/urinet/archiv/ki/domain/oeffentlich/FrageBereinigung.java:5-10,29-40`). Das ist ein Datenschutz- und Abdeckungsrisiko; eine Offenlegung durch den Anbieter oder ein unzulässiger Zugriff ist damit nicht belegt.

## Offene Nachweise
- **Hoch/kritisch:** keine bestätigten offenen Befunde in den geprüften, vorhandenen öffentlichen Endpunkten.
- **Mittel:** Erschöpfung der Schlüsselprüfung durch fremde Anfragen für kalte Schlüssel, Erfassungs-Schlüssel und interne Tokens (API2, Folgeaufgabe F2; Traefik-Drosselung je IP nur teilweise belegt, die eigene Drosselung für `/public/` nur vorgeschlagen, #241); Budgetkontrolle ist keine harte Obergrenze (Folgeaufgabe F3). F1 ist mit der Erfassung und ihren automatisierten Nachweisen geschlossen.
- **Tief:** Limiter-Fehlerpfad (Folgeaufgabe F4); unveränderte Besucherfrage an das Modell.
- **Offen für Fabian:** ZAP-Baseline gegen Staging (Req 9.2). Dieser Codebericht ersetzt den Staging-Test nicht.
