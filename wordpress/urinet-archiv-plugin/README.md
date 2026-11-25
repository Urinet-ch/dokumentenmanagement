# UriNet Archiv: WordPress-Plugin

Plugin für die serverseitige Webseiten-Schnittstelle. Für die verschlüsselte Speicherung der
Webseiten-Schlüssel muss die Betreiberin oder der Betreiber `URINET_ARCHIV_KEY` in der
WordPress-Konfiguration als Base64-kodierten, zufällig erzeugten Schlüssel aus 32 Bytes setzen.
Dieser Schlüssel und die Webseiten-Schlüssel gehören nicht ins Repository. Geht der
Verschlüsselungsschlüssel verloren, müssen die Webseiten-Schlüssel erneut eingetragen werden.

Der Einsatz ist bis zum Nachweis der nachfolgenden Empfangsgrenzen und zum Test auf einer echten
WordPress-Seite gesperrt. Diese Nachweise sind nachgelagert [Fabian].

Unter «Einstellungen → UriNet Archiv» werden HTTPS-Archiv-URL, Lese-Schlüssel, optional
Erfassungs-Schlüssel, Cache-Dauer und Rollen eingestellt. Ein leeres Schlüsselfeld behält den
bisherigen Wert. Mitglieder- und Vorstandsrolle bestimmen serverseitig die höchste Zugriffsstufe.
Die Geschäftsstelle-Rolle wird erst mit der Erfassung verwendet (Aufgabe 11.12).
Das Speichern benötigt JavaScript und sendet ein JSON mit festem Feldschema an
`wp-admin/admin-post.php?action=urinet_archiv_speichern`. Der Endpunkt liest höchstens
8'193 Bytes und verwirft Anfragen über 8'192 Bytes, auch ohne `Content-Length`. Die Erfassung
sendet Dateien bis 50 MiB an `wp-admin/admin.php?page=urinet-archiv-erfassung`; sie werden nicht
in der Mediathek abgelegt.

## Einbindung in Seiten

Die Shortcodes `[urinet_archiv_liste]`, `[urinet_archiv_suche]` und
`[urinet_chatbot]` liefern Liste, Suche und den Chatbot. Die gleichen Funktionen
stehen als dynamische Gutenberg-Blöcke `urinet-archiv/liste`,
`urinet-archiv/suche` und `urinet-archiv/chatbot` zur Verfügung. Dokumente
verwenden die interne Seite `/urinet-archiv/dokument/{id}`; Dateien laufen über
die serverseitige Route `/urinet-archiv-datei/{id}` und werden nie in der
WordPress-Mediathek gespeichert. Alte Pfade können über
`/urinet-archiv-umleitung?url=...` mit einer 301-Umleitung aufgelöst werden.
Die Liste lädt ihre Reihen und öffentlich filterbaren Klassifikationen über
`/public/v1/filter`. Besucher können nach Reihe, Jahr, Ausgabe und den
freigegebenen Klassifikationen filtern, die Seitengrösse wählen und durch die
Ergebnisse blättern; pro Seite sind höchstens 100 Einträge möglich.

Der Chat wird über `/wp-json/urinet-archiv/v1/chat` als Server-Sent Events
weitergereicht. Der Schlüssel bleibt dabei auf dem Server. Die Seite muss
`wp_enqueue_scripts` ausführen; das Plugin lädt `assets/chatbot.js` auf Seiten
mit dem Chatbot-Shortcode oder -Block. Einen vom Archiv im Ereignis `fertig`
gelieferten `hinweis` zeigt das Plugin nach der Antwort an; den Wortlaut
bestimmt die API.

## Pflicht vor dem Einsatz: Empfang begrenzen

PHP liest formkodierte Anfragen bereits vor WordPress und seinen Hooks ein. Die Begrenzung des
Plugin-Endpunkts allein schützt deshalb **nicht** vor grossen Anfragen beim Empfang. Der Betrieb
muss am äussersten Webserver oder Reverse Proxy vor PHP für alle Zugriffe auf
`/wp-admin/admin-post.php` (auch alternative Pfade und Query-Strings) eine Grenze von höchstens
8'192 Bytes durchsetzen, auch bei `Transfer-Encoding: chunked` und ohne `Content-Length`. Für
`/wp-admin/admin.php` muss der äusserste Webserver oder Reverse Proxy zusätzlich die Anfragen auf
höchstens 50 MiB plus 64 KiB begrenzen. Auch diese Grenze muss ohne `Content-Length` und bei
`Transfer-Encoding: chunked` gelten. PHP darf nicht direkt am Proxy vorbei erreichbar sein. Bei
einer WordPress-Installation in einem Unterverzeichnis sind die Pfade entsprechend anzupassen.

Mögliche Einstellungen in der bestehenden Serverkonfiguration: Apache `LimitRequestBody 8192`
und mit `mod_reqtimeout` `RequestReadTimeout body=10,MinRate=1024` für `admin-post.php`;
nginx `client_max_body_size 8k`, `client_body_timeout 10s` und `proxy_request_buffering on` bzw.
`fastcgi_request_buffering on` in dessen Location. Für `admin.php` gelten separat Apache
`LimitRequestBody 52494336` und `RequestReadTimeout body=120,MinRate=1024` oder nginx
`client_max_body_size 51m`, `client_body_timeout 120s` und vollständige Request-Pufferung. **Diese
Beispiele allein sind kein Nachweis:**
Bei einem lokalen Apache-Reverse-Proxy-Test erreichte eine zu grosse Anfrage den Upstream
trotz `LimitRequestBody`. Die vorgeschaltete Grenze und vollständige Pufferung vor PHP müssen
für die tatsächliche Proxy-Kette nachgewiesen werden. Die PHP-Konfiguration setzt zusätzlich `upload_max_filesize=50M`, `post_max_size=51M` und
`max_input_vars=200` oder strengere Werte, die den Upload weiterhin ermöglichen. Diese PHP-Werte
ersetzen keine der vorgeschalteten Grenzen. Die Grenze für `admin-post.php` gilt
auch für andere Plugins; deren Verträglichkeit ist vor dem Einrichten zu prüfen. Der Betrieb
prüft auf dem tatsächlichen Hosting mit und ohne `Content-Length`, insbesondere mit einer zu
grossen Chunked-Anfrage, dass der Server vor PHP mit HTTP 413 ablehnt.

Lokale Entwicklung: `npm ci`, `composer install`, `npm run wp-env -- start`, `npm test`,
`npm run test:cucumber` und anschliessend `npm run wp-env -- stop`. `vendor/bin/phpcs` prüft
die WordPress-Regeln; ausgenommen sind vorgeschriebene Dateinamen und reine Kommentarvorgaben.
`npm run build` erstellt ein Paket ausschliesslich mit den freigegebenen Laufzeitdateien
(PHP und benötigte Skripte) und prüft dessen Inhalt.
`npm run test:cucumber` installiert Chromium für Playwright und führt die Browser-Szenarien gegen
wp-env mit einer synthetischen Test-API aus.
Nur synthetische Testdaten verwenden. Installation auf einer echten WordPress-Seite und
Gerätetests bleiben nachgelagert.
