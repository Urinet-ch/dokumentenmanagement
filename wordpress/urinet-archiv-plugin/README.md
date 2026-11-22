# UriNet Archiv: WordPress-Plugin

Grundgerüst für die serverseitige Webseiten-Schnittstelle. Für die verschlüsselte Speicherung der
Webseiten-Schlüssel muss die Betreiberin oder der Betreiber `URINET_ARCHIV_KEY` in der
WordPress-Konfiguration als Base64-kodierten, zufällig erzeugten Schlüssel aus 32 Bytes setzen.
Dieser Schlüssel und die Webseiten-Schlüssel gehören nicht ins Repository. Geht der
Verschlüsselungsschlüssel verloren, müssen die Webseiten-Schlüssel erneut eingetragen werden.

Das Grundgerüst ist noch nicht für den Einsatz freigegeben: Der Änderungs-Feed zur sofortigen
Invalidierung zurückgezogener Dokumente fehlt (Aufgabe 11.12), ebenso die vollständigen Tests.

Unter «Einstellungen → UriNet Archiv» werden HTTPS-Archiv-URL, Lese-Schlüssel, optional
Erfassungs-Schlüssel, Cache-Dauer und Rollen eingestellt. Ein leeres Schlüsselfeld behält den
bisherigen Wert. Mitglieder- und Vorstandsrolle bestimmen serverseitig die höchste Zugriffsstufe.
Die Geschäftsstelle-Rolle wird erst mit der Erfassung verwendet (Aufgabe 11.12).

Lokale Entwicklung: `npm ci`, `composer install`, `npm run wp-env -- start`, `npm test`,
`npm run test:cucumber` und anschliessend `npm run wp-env -- stop`. `vendor/bin/phpcs` prüft
die WordPress-Regeln; ausgenommen sind vorgeschriebene Dateinamen und reine Kommentarvorgaben.
`npm run build` erstellt
ein Paket ausschliesslich mit den freigegebenen Laufzeitdateien und prüft dessen Inhalt.
Nur synthetische Testdaten verwenden. Installation auf einer echten WordPress-Seite und
Gerätetests bleiben nachgelagert.
