# OWASP API Security Top 10 (2023): codebasierte Prüfliste

## Umfang und Ergebnis

Statische Prüfung des Quellstands `2ccc3ecf` (`origin/main` am 17.11.2026; gegenüber der ersten Fassung `dd80b6a8` änderten sich nur Frontend und zwei Feature-Dateien unter `dokument` und `suche`, die Backend-Quellen und alle zitierten Zeilen sind unverändert) gegen Req 9.2 sowie die Anforderungen 11–15 von Spec 03. Geprüft wurden die vorhandenen Endpunkte der Webseiten-Schnittstelle und die zugehörigen Tests. Es wurden weder Staging noch produktive Systeme angesprochen; die ZAP-Baseline gegen Staging bleibt offen.

«Lücke» bezeichnet hier eine fehlende Sicherheitsabdeckung oder eine nicht umgesetzte Funktion. Sie ist nicht gleichbedeutend mit einer bestätigten ausnutzbaren Schwachstelle. In den vorhandenen Lese-, Datei-, Feed- und Chat-Endpunkten wurde kein hoch oder kritisch eingestufter, bestätigter Befund festgestellt.

## Abdeckung der Punkte aus Req 9.2

| Punkt | Befund | Beleg oder Folgeaufgabe |
|---|---|---|
| Lesen, Suche, Filter, Umleitung fremder Mandanten | ok | API1; `webseiten-lesen.feature:311`, `:330` |
| Datei und Stream fremder Mandanten | ok | `webseiten-datei.feature:66` |
| Feed und Referenzen fremder Mandanten | ok | `webseiten-feed.feature:153`, `:192` |
| Chat fremder Mandanten und Stufen | ok | `oeffentlicher-chat.feature:363` |
| Stufe `vorstand` und zwei Schlüssel desselben Mandanten mit verschiedenen Stufen (Req 9.4) | ok | API1; `webseiten-lesen.feature:344`, `:363`; `zugriffsstufen.feature:47`; `webseiten-datei.feature:26`, `:40` |
| Erfassung | noch nicht umgesetzt | Aufgabe 11.10; Folgeaufgabe F1 |
| Erschöpfung der Schlüsselprüfung durch fremde Anfragen | F2 umgesetzt; Restrisiko verteilt über viele Quellen | API2; `webseiten-schluessel.feature` (`@req-9.2`, `@req-12.8`) |

**Folgeaufgaben** (stehen als Vermerk in `tasks.md` unter 12.3; keine Haken gesetzt):

- **F1:** Erfassung (Aufgabe 11.10) mit nachweisbaren Grenzen für Requestgrösse, Dateigrösse und Rate, Feld-Allowlist und Schutz vor Massenänderungen (BOPLA), Scope-Test mit beiden Schlüsselarten (BFLA), Vertrag und Endpunktinventar (API9) und ausschliesslich Datei-Bytes ohne URL-Abruf (API7).
- **F2 (erledigt):** Token-Bucket je Quelladresse vor der `HashSperre`, Test für kalte Lese- und Erfassungs-Schlüssel anderer Quellen sowie geprüfte Vertrauensgrenze für `X-Real-Ip`. Die zusätzliche Traefik-Drosselung aus Aufgabe 11.6 (#241) bleibt ausserhalb dieses Auftrags und ist nicht ausgerollt.
- **F3 (erledigt):** Atomare Reservierung einer Chatfrage je Mandant und Monat verhindert parallele Überschreitungen; eine bereits angenommene letzte Frage darf das Restbudget einmal überschreiten. Fehler beim Schreiben der Nutzung bleiben bewusst fail-open, erhöhen aber `archiv_oeffentlicher_chat_nutzung_schreibfehler_total` und haben einen Alarmvorschlag in `docs/alarme.md`.
- **F4 (erledigt):** Test zum Fehlerpfad des Pro-Schlüssel-Limiters belegt fail-open und genau einen Anstieg der Kennzahl `archiv_webseite_grenze_ausgefallen_total`; der Alarmvorschlag steht in `docs/alarme.md`.

## API1: Broken Object Level Authorization (BOLA)

**Bedeutung:** Eine Objekt-ID oder ein Filter darf keine Daten ausserhalb des Mandanten oder der für den Schlüssel zulässigen Zugriffsstufe liefern.

**Belege:** `ApiTokenIdentityProvider` bindet Identität, Mandant und Scope (`backend/src/main/java/ch/urinet/archiv/sicherheit/adapter/in/auth/ApiTokenIdentityProvider.java:54-66`); `MandantAufloesung` und `MandantTenantResolver` lösen den Mandanten fail-closed auf (`backend/src/main/java/ch/urinet/archiv/mandant/application/MandantAufloesung.java:25-52`, `backend/src/main/java/ch/urinet/archiv/mandant/adapter/out/persistence/MandantTenantResolver.java:22-30`). `FesterFilter` beschränkt veröffentlichte Leseergebnisse auf die effektive Stufe (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/FesterFilter.java:26-39`); die SQL-Abfragen für Liste, Suche, Details, Filter und Umleitung binden Filter und Eingaben (`backend/src/main/java/ch/urinet/archiv/integration/adapter/out/persistence/SqlWebseitenLese.java:61-147`). Die gemeldete Stufe (`oeffentlich`, `mitglieder` oder `vorstand`) darf nicht höher als die des Schlüssels sein; die Filterprüfung weist dies zurück (`backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/LeseZugriffFilter.java:63-88`). Stufentrennung innerhalb eines Mandanten: `webseiten-lesen.feature:344` (ein öffentlicher Schlüssel sieht keine höheren Stufen), `:363` (gemeldete Stufe über der des Schlüssels ergibt 403), `zugriffsstufen.feature:47`, `webseiten-datei.feature:26` und `:40` (Stufen `mitglieder` und `vorstand` nur als Stream, sonst 404). Mandantenübergreifende Szenarien: `webseiten-lesen.feature` (Zeilen 310-330), `webseiten-feed.feature` (152-153), `webseiten-datei.feature` (92) und `oeffentlicher-chat.feature` (362-386).

**Befund:** ok für die vorhandenen Lese-, Datei-, Such-, Feed- und Chat-Pfade.

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
4. Anwendungsdrosselung je Quelladresse (`ApiTokenPruefGrenzen`): 5 Prüfungen/s, Spitze 10, bis 1000 Quell-Buckets, vor der `HashSperre`; warme Webseiten-Schlüssel aus dem Prüf-Cache werden nicht gezählt. Bei voller Tabelle werden nur vollständig aufgefüllte Buckets bereinigt, sonst teilen neue Quellen einen Sammel-Bucket. Tests: `ApiTokenPruefungTest` (Grenze, Auffüllen, Speicherlimit und kalte Schlüssel), `ApiTokenAuthMechanismTest` (gefälschter `X-Real-Ip`-Header) und Szenario `webseiten-schluessel.feature` (`@req-9.2`, `@req-12.8`). `ProblemFehlerRouteTest` belegt 429 mit `Retry-After` und `grund=SCHLUESSELPRUEFUNG`.

**Befund:** Die F2-Lücke ist auf Anwendungsebene behoben: Viele falsche Schlüsselversuche von einer Quelle erschöpfen nur deren begrenzten Bucket; ein kalter Lese-Schlüssel oder ein Erfassungs-Schlüssel einer anderen Quelle bleibt nutzbar. Die Quelladresse wird über Quarkus’ `remoteAddress()` übernommen; `X-Real-Ip` gilt nur bei aktivierter Proxy-Weiterleitung und einem in `quarkus.http.proxy.trusted-proxies` eingetragenen unmittelbaren Proxy. Die CIDRs werden produktiv über `ARCHIV_VERTRAUENSWUERDIGE_PROXY_NETZE` konfiguriert; `deploy/` wird in F2 nicht geändert.

**Restrisiko:** Mehrere Quelladressen können die gemeinsame `HashSperre` weiterhin auslasten; dadurch können kalte Schlüssel und die internen Tokens `generator` und `import` weiterhin 503 `UEBERLASTET` erhalten. Buckets gelten je Prozess und starten nach Neustart voll. Ohne produktiv korrekt gesetzte Proxy-Netze sieht die Anwendung nur die Verbindung des Proxys, sodass dessen Clients denselben Bucket teilen. Die zusätzliche Drosselung in Traefik (#241) bleibt vor dem Livegang ein separater Betriebsbedarf. Es gibt keinen Authentifizierungsbypass oder Datenabfluss.

## API3: Broken Object Property Level Authorization (BOPLA)

**Bedeutung:** Antworten dürfen nur freigegebene Felder enthalten; Schreibpfade müssen geschützte Felder wie Zugriffsstufe und Veröffentlichung gegen unzulässige Massenänderungen absichern.

**Belege:** Vorhandene Leseantworten werden über explizite DTO-Felder aufgebaut (`backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/WebseitenLesenResource.java:104-188`). Die produktiven Ressourcen enthalten Lese-, Datei-, Feed- und Chat-Pfade, aber keine produktiven Bestätigungs- oder Metadatenänderungs-Handler (`WebseitenLesenResource.java:191-374`, `backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/AenderungFeedResource.java:97-162`, `backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/OeffentlicherChatResource.java:76-108`). Spec 03 Req 10.5–10.6 verlangt solche Mutationen. Verhaltensbelege der Leseseite: `webseiten-lesen.feature:166` (Felder «id,titel,reihe,ausgabe,jahr,datum,zugriff,seiten,auszug» und nur diese) und `:171-178` (nie Dateiname, Vertraulichkeit, Status, Partner oder eine URL).

**Befund:** ok für die Leseseite (Allowlist durch die genannten Szenarien belegt). Mutationen: noch nicht umgesetzt (Req 10.5–10.6, Aufgabe 11.10), Lücke – mittel, als Prüfbarkeitslücke. Für die noch fehlenden Mutationspfade lassen sich Feld-Allowlist und Schutz vor Massenänderungen noch nicht nachweisen; es wurde kein entsprechender produktiver Handler mit einer bestätigten Schwachstelle gefunden.

**Restrisiko:** Die BOPLA-Prüfung muss bei Umsetzung der Erfassung und Änderung ergänzt werden (Folgeaufgabe F1, Aufgabe 11.10). Bis dahin lässt sich aus dieser Prüfliste keine Aussage über deren spätere Feldautorisierung ableiten.

## API4: Unrestricted Resource Consumption

**Bedeutung:** Anfragen, Suchvorgänge, Uploads, Chats und Dateistreams müssen begrenzt werden, damit einzelne Schlüssel keine unverhältnismässigen Ressourcen verbrauchen.

**Belege:** Pro-Schlüssel-Grenzen für Lesen und Suche sowie getrennte Grenzen für Chat sind in `WebseitenGrenzen` und `OeffentlicheGrenzen` implementiert (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/WebseitenGrenzen.java:17-29,106-155`; `backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheGrenzen.java:18-29,86-117`). Streams sind global und je Schlüssel begrenzt und haben Zeitlimits (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/DateiPlaetze.java:11-17,46-97`; `backend/src/main/java/ch/urinet/archiv/integration/application/webseite/StreamLauf.java:17-28,88-115`). Der Feed ist auf höchstens 500 Einträge begrenzt (`backend/src/main/java/ch/urinet/archiv/integration/application/webseite/AenderungFeed.java:29-30,54-62`). Szenarien dazu: `webseiten-grenzen.feature:12-65` und `webseiten-datei.feature:150-203`; Limiter-Fail-open ist durch `WebseitenGrenzenTest.internerFehlerLaesstAnfrageDurchUndErhoehtGenauDieAusfallkennzahl` belegt.

**Befund:** teilweise ok; die Grenzen für vorhandene Endpunkte sind belegt. Lücke – mittel, als Prüfbarkeitslücke: Es gibt keinen produktiven öffentlichen Upload-Endpunkt, daher belegen die allgemeine Uploadgrössen- und Upload-Ratenkonfiguration keine Begrenzung einer öffentlichen Erfassung. Der Fehlerpfad des Pro-Schlüssel-Limiters lässt bei einem unerwarteten Laufzeitfehler weiterlaufen (`WebseitenGrenzen.java:111-120`); ein vom Angreifer auslösbarer Fehler wurde nicht nachgewiesen. Das Verhalten ist in Spec 03 design.md («Grenzen», Fehler der Prüfung) als Entscheid begründet (fail open, Kennzahl `archiv_webseite_grenze_ausgefallen_total`); `WebseitenGrenzenTest.internerFehlerLaesstAnfrageDurchUndErhoehtGenauDieAusfallkennzahl` belegt Durchlass und genau einen Zähleranstieg. Für die Kennzahl gibt es einen Alarmvorschlag in `docs/alarme.md`.

**Restrisiko:** Die Erfassung muss vor Bereitstellung eigene, nachweisbare Grenzen für Requestgrösse, Dateigrösse und Rate erhalten (Folgeaufgabe F1, Aufgabe 11.10). Für den bestehenden Limiter bleibt ein niedriges Restrisiko bei unerwarteten internen Fehlern.

## API5: Broken Function Level Authorization

**Bedeutung:** Jeder Endpunkt muss den passenden Scope und die erforderlichen Rechte erzwingen; Lese- und Erfassungsschlüssel dürfen nicht austauschbar sein.

**Belege:** `ZugangFilter` verlangt den passenden Webseiten-Scope und weist nicht annotierte Pfade geschlossen ab (`backend/src/main/java/ch/urinet/archiv/sicherheit/adapter/in/rest/ZugangFilter.java:53-109`). `EndpunktWaechterTest.jederEndpunktFragtDieRegelAb` kontrolliert die REST-Endpunkte; der Wächter weist öffentliche Methoden ohne Zugangsannotation zurück (`backend/src/test/java/ch/urinet/archiv/sicherheit/EndpunktWaechterTest.java:86-95,168-190`). Szenarien prüfen die Trennung von Lese- und Erfassungsschlüsseln (`backend/src/test/resources/features/sicherheit/webseiten-schluessel.feature:15-23`).

**Befund:** ok für implementierte Endpunkte; Lücke – mittel, als Prüfbarkeitslücke für die noch fehlenden Bestätigungs- und Änderungsaktionen aus Req 10.

**Restrisiko:** Die Test-Probe für Erfassung belegt nicht die Autorisierung künftiger produktiver Mutationspfade. Diese müssen vom Endpunktwächter erfasst und mit beiden Schlüsselarten getestet werden (Folgeaufgabe F1, Aufgabe 11.10).

## API6: Unrestricted Access to Sensitive Business Flows

**Bedeutung:** Chat und Änderungs-Feed dürfen nicht missbraucht werden, um Kosten oder Verarbeitung ausserhalb der vorgesehenen Grenzen auszulösen.

**Belege:** Der Chat begrenzt Anfragen je Client und Webseite sowie parallele Fragen; Gesprächszustand wird an Mandant, Webseite, Client und Zugriffsstufe gebunden (`OeffentlicheGrenzen.java:90-117`; `backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheGespraeche.java:64-73,109-112`). Das Monatsbudget wird je Mandant und Monat atomar reserviert und die Nutzung danach addiert (`OeffentlicheFragen.java`, `JdbcMandantBudget.java`; Changeset `public/038`). Die Szenarien «Jede Frage verbraucht Tokens …», «Gleichzeitige Fragen können das Monatsbudget nur einmal überschreiten» und «Ein Fehler beim Schreiben des Monatsbudgets wird in einer Kennzahl erfasst» in `oeffentlicher-chat.feature` belegen Budgetgrenze, erlaubte letzte Überschreitung und fail-open mit Kennzahl.

**Befund:** Das Budget wird für parallele Fragen je Mandant und Monat hart serialisiert; eine bereits angenommene letzte Frage kann um ihren tatsächlichen Verbrauch überziehen. Ein Schreibfehler bleibt bewusst fail-open, damit ein bereits begonnener Stream nicht abbricht; der Adapter erhöht dann `archiv_oeffentlicher_chat_nutzung_schreibfehler_total` und `docs/alarme.md` schlägt einen Alarm vor. Im Fehlerfall ist die Budgetobergrenze nicht nachgewiesen; dies ist ein begrenztes Abrechnungsrisiko, kein Rechte- oder Mandantenbypass.

**Restrisiko:** Parallele Fragen können das verbleibende Budget überschreiten. Die gewünschte Semantik einer harten Ausgabengrenze ist von der aktuellen, durch das Szenario bestätigten Überschreitung zu unterscheiden. Folgeaufgabe F3.

## API7: Server-Side Request Forgery (SSRF)

**Bedeutung:** Vom Aufrufer gelieferte URLs dürfen den Server nicht dazu bringen, beliebige interne oder externe Ziele abzurufen.

**Belege:** Alte URLs werden normalisiert und als gebundener Datenbankwert gesucht; die URL wird nicht abgerufen (`backend/src/main/java/ch/urinet/archiv/integration/domain/webseite/AlteUrl.java:31-70`; `backend/src/main/java/ch/urinet/archiv/integration/adapter/out/persistence/SqlWebseitenLese.java:138-147`). In den geprüften öffentlichen Webseiten- und Chat-Pfaden wurde kein ausgehender HTTP-Aufruf festgestellt. Spec 03 Req 10.2 verlangt Datei-Bytes statt eines abzurufenden Datei-Links.

**Befund:** nicht anwendbar auf die vorhandenen öffentlichen Routen; kein URL-Abrufpfad festgestellt.

**Restrisiko:** Die noch nicht vorhandene Erfassung muss weiterhin ausschliesslich Datei-Bytes annehmen und darf keine URL abrufen (Folgeaufgabe F1).

## API8: Security Misconfiguration

**Bedeutung:** CORS, Cache, Sicherheitsheader und Fehlerantworten müssen so konfiguriert sein, dass keine unnötigen Daten offengelegt werden.

**Belege:** `WebseitenRoute` setzt `no-store` auch für Fehlerantworten (`backend/src/main/java/ch/urinet/archiv/integration/adapter/in/rest/webseite/WebseitenRoute.java:103-148`). Problem Details enthalten feste Texte statt interner Fehlerdetails (`backend/src/main/java/ch/urinet/archiv/gemeinsam/adapter/in/rest/ProblemAntwort.java:14-22,54-83`; `ProblemFehlerRoute.java:99-122`). Streams setzen `nosniff` und `no-store` (`WebseitenLesenResource.java:315-325`). Tests: `WebseitenRouteTest.ohneSunsetNurNoStore`, `.internerPfadIstVonAussenGesperrt` sowie Szenarien in `webseiten-vertrag.feature:18-46`. CORS: `zugriffsstufen.feature:178-180` («Die Webseiten-Schnittstelle gibt kein CORS frei», je Methode kein `Access-Control-Allow-Origin` bei gesetztem Herkunftskopf) und `webseiten-lesen.feature:392-395` («Jede Antwort ist nicht zwischenzuspeichern und ohne CORS»).

**Befund:** ok für statische Konfiguration, Cache- und Fehlerverhalten. CORS ist in der Konfiguration nicht aktiviert und durch die zwei genannten Szenarien zur Laufzeit belegt.

**Restrisiko:** Gemäss Vertrag bleibt CORS deaktiviert; Schlüssel gehören nur auf den Webseitenserver.

## API9: Improper Inventory Management

**Bedeutung:** Vertrag, implementierte Routen und deren Sicherheitsdeklarationen müssen übereinstimmen und vollständig inventarisiert sein.

**Belege:** `PublicVertragTest.codeUndDateiBeschreibenDieselbenPfade` gleicht Code und Vertrag ab (`backend/src/test/java/ch/urinet/archiv/integration/PublicVertragTest.java:61-65`); `EndpunktWaechterTest` prüft die Sicherheitsdeklarationen (`backend/src/test/java/ch/urinet/archiv/sicherheit/EndpunktWaechterTest.java:86-95`). Der Vertrag führt Lese-, Chat- und Feed-Endpunkte auf (`docs/schnittstelle/public-v1.yaml`).

**Befund:** Lücke – mittel, als Funktions-/Prüfabdeckung: Die Anforderungen Req 10.1 und 10.5–10.6 beschreiben Upload, Bestätigen, Ändern und Veröffentlichen; diese produktiven Mutationspfade fehlen im geprüften Code und damit im aktuellen Vertrag. Das ist keine bestätigte Schwachstelle eines vorhandenen Endpunkts.

**Restrisiko:** Vor Bereitstellung der Erfassung müssen Vertrag, Code, Scope-Schutz und Endpunktinventar gemeinsam ergänzt und geprüft werden (Folgeaufgabe F1, Aufgabe 11.10).

## API10: Unsafe Consumption of APIs

**Bedeutung:** Aufrufe externer Dienste und Werkzeuge müssen begrenzt und kontrolliert sein; nicht vertrauenswürdige Dokument- oder Benutzereingaben dürfen keine unzulässigen Aktionen auslösen.

**Belege:** Die KI-Basis-URL wird beim Start auf Schema und Host geprüft (`backend/src/main/java/ch/urinet/archiv/ki/adapter/out/KiBasisUrlPruefung.java:93-160`; `ChatModell.java:12-21,67-78`). Der öffentliche Agent stellt nur Suche und Anzeige bereit; Treffer werden erneut gegen den Zugriffsschutz geprüft (`backend/src/main/java/ch/urinet/archiv/ki/adapter/out/langchain4j/OeffentlicherAgent.java:12-29`; `backend/src/main/java/ch/urinet/archiv/ki/adapter/in/werkzeug/OeffentlicheWerkzeuge.java:20-26,75-93`; `backend/src/main/java/ch/urinet/archiv/ki/application/oeffentlich/OeffentlicheAbfragen.java:146-163`). Dokumenttext wird vor dem Modellaufruf neutralisiert; ein Prompt-Injection-Szenario steht in `oeffentlicher-chat.feature:147-165`. Ein Mail-Aufruf im geprüften öffentlichen Pfad wurde nicht festgestellt.

**Befund:** teilweise ok; kein unzulässiger Werkzeug- oder Mailpfad festgestellt.

**Restrisiko:** Die ursprüngliche Besucherfrage wird dem Modell unverändert übermittelt; nur die gespeicherte Fassung wird bereinigt (`OeffentlicheFragen.java:110-117,211-219`). Die Bereinigung erkennt Namen ausdrücklich nicht (`backend/src/main/java/ch/urinet/archiv/ki/domain/oeffentlich/FrageBereinigung.java:5-10,29-40`). Das ist ein Datenschutz- und Abdeckungsrisiko; eine Offenlegung durch den Anbieter oder ein unzulässiger Zugriff ist damit nicht belegt.

## Offene Nachweise
- **Hoch/kritisch:** keine bestätigten offenen Befunde in den geprüften, vorhandenen öffentlichen Endpunkten.
- **Mittel:** BOPLA, Funktionsautorisierung, Ressourcenbegrenzung und Inventar der noch fehlenden Erfassungs-/Änderungsendpunkte (Folgeaufgabe F1, Aufgabe 11.10); die monatliche Budgetobergrenze ist bei einem Schreibfehler der Nutzung nicht nachgewiesen.
- **Tief:** Verteilte ungültige Schlüsselanfragen können weiterhin gemeinsame Prozessressourcen belasten; unveränderte Besucherfrage an das Modell.
- **Offen für Fabian:** ZAP-Baseline gegen Staging (Req 9.2). Dieser Codebericht ersetzt den Staging-Test nicht.
