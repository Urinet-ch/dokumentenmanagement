# UriNet Archiv: WordPress-Plugin

Grundgerüst für die serverseitige Webseiten-Schnittstelle. Für die verschlüsselte Speicherung der
Webseiten-Schlüssel muss die Betreiberin oder der Betreiber `URINET_ARCHIV_KEY` in der
WordPress-Konfiguration als Base64-kodierten, zufällig erzeugten Schlüssel aus 32 Bytes setzen.
Dieser Schlüssel und die Webseiten-Schlüssel gehören nicht ins Repository. Geht der
Verschlüsselungsschlüssel verloren, müssen die Webseiten-Schlüssel erneut eingetragen werden.

Das Grundgerüst ist noch nicht für den Einsatz freigegeben: Der Änderungs-Feed zur sofortigen
Invalidierung zurückgezogener Dokumente fehlt (Aufgabe 11.12), ebenso die vollständigen Tests
und der Nachweis der nachfolgenden Empfangsgrenze auf dem tatsächlichen Hosting.

Unter «Einstellungen → UriNet Archiv» werden HTTPS-Archiv-URL, Lese-Schlüssel, optional
Erfassungs-Schlüssel, Cache-Dauer und Rollen eingestellt. Ein leeres Schlüsselfeld behält den
bisherigen Wert. Mitglieder- und Vorstandsrolle bestimmen serverseitig die höchste Zugriffsstufe.
Die Geschäftsstelle-Rolle wird erst mit der Erfassung verwendet (Aufgabe 11.12).
Das Speichern benötigt JavaScript und sendet ein JSON mit festem Feldschema an
`wp-admin/admin-post.php?action=urinet_archiv_speichern`. Der Endpunkt liest höchstens
8'193 Bytes und verwirft Anfragen über 8'192 Bytes, auch ohne `Content-Length`.

## Pflicht vor dem Einsatz: Empfang begrenzen

PHP liest formkodierte Anfragen bereits vor WordPress und seinen Hooks ein. Die Begrenzung des
Plugin-Endpunkts allein schützt deshalb **nicht** vor grossen Anfragen beim Empfang. Der Betrieb
muss am äussersten Webserver oder Reverse Proxy vor PHP für alle Zugriffe auf
`/wp-admin/admin-post.php` (auch alternative Pfade und Query-Strings) eine Grenze von höchstens
8'192 Bytes durchsetzen, auch bei `Transfer-Encoding: chunked` und ohne `Content-Length`. PHP
darf nicht direkt am Proxy vorbei erreichbar sein. Bei einer WordPress-Installation in einem
Unterverzeichnis ist der Pfad entsprechend anzupassen.

Mögliche Einstellungen in der bestehenden Serverkonfiguration: Apache `LimitRequestBody 8192`
und mit `mod_reqtimeout` `RequestReadTimeout body=10,MinRate=1024`; nginx
`client_max_body_size 8k`, `client_body_timeout 10s` und `proxy_request_buffering on` bzw.
`fastcgi_request_buffering on` in der Location. **Diese Beispiele allein sind kein Nachweis:**
Bei einem lokalen Apache-Reverse-Proxy-Test erreichte eine zu grosse Anfrage den Upstream
trotz `LimitRequestBody`. Die vorgeschaltete Grenze und vollständige Pufferung vor PHP müssen
für die tatsächliche Proxy-Kette nachgewiesen werden. Die PHP-Konfiguration
setzt zusätzlich `post_max_size=8M` und `max_input_vars=200` oder strengere Werte. Diese
PHP-Werte ersetzen die vorgeschaltete 8-KiB-Grenze nicht. Die Grenze für `admin-post.php` gilt
auch für andere Plugins; deren Verträglichkeit ist vor dem Einrichten zu prüfen. Der Betrieb
prüft auf dem tatsächlichen Hosting mit und ohne `Content-Length`, insbesondere mit einer zu
grossen Chunked-Anfrage, dass der Server vor PHP mit HTTP 413 ablehnt.

Lokale Entwicklung: `npm ci`, `composer install`, `npm run wp-env -- start`, `npm test`,
`npm run test:cucumber` und anschliessend `npm run wp-env -- stop`. `vendor/bin/phpcs` prüft
die WordPress-Regeln; ausgenommen sind vorgeschriebene Dateinamen und reine Kommentarvorgaben.
`npm run build` erstellt ein Paket ausschliesslich mit den freigegebenen Laufzeitdateien
(PHP und das Einstellungen-Skript) und prüft dessen Inhalt.
Nur synthetische Testdaten verwenden. Installation auf einer echten WordPress-Seite und
Gerätetests bleiben nachgelagert.
