# language: de
# Dateiname als Platzhalter in der PWA gegen das echte Archiv (Aufgabe 4.10, Abschluss): Playwright leitet alle Anfragen
# der PWA an /api/v1/** an das Archiv im Testmodus weiter (BrowserBackendTest mit PostgreSQL, Keycloak und MinIO) und
# tauscht das Token der Keycloak-Attrappe gegen ein echtes Token desselben Testbenutzers
# (frontend/e2e/hilfen/echtes-backend.mjs). Die Dokumente kommen über die API mit gespeichertem Namen; im Testmodus läuft
# kein Worker, den Status EINGANGSKORB oder FEHLER setzt die Testhilfe von BrowserBackendTest. Die Dateien sind synthetisch.
@spec-01 @browser
Funktionalität: Dateiname als Platzhalter im Browser gegen das echte Archiv

  Hintergrund:
    Angenommen die PWA spricht mit dem Archiv im Testmodus

  @req-14.9 @req-14.10
  Szenario: Ein Dokument in NEU ist über ein Wort nur aus seinem Dateinamen auffindbar und zeigt auf dem Handy den Platzhalter
    Angenommen «inhaber» im Mandanten «urinet» hat die Datei «Scan_Kassenzettel_Testlauf_2026.pdf» schon hochgeladen
    Und das Browserfenster ist 375 Pixel breit
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Kassenzettel» suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert   |
      | modus     | hybrid |
    Und zeigt der Treffer «Scan_Kassenzettel_Testlauf_2026.pdf» den Dateinamen als Platzhalter statt eines Titels
    Und lässt sich die Seite nicht waagrecht scrollen
    Wenn ich den Treffer «Scan_Kassenzettel_Testlauf_2026.pdf» öffne
    Dann sehe ich die Dokumentansicht von «Scan_Kassenzettel_Testlauf_2026.pdf»
    Und zeigt die Dokumentansicht den Dateinamen «Scan_Kassenzettel_Testlauf_2026.pdf» als Platzhalter und in der Zeile «Dateiname»
    Und lässt sich die Seite nicht waagrecht scrollen
    Und steht «Scan_Kassenzettel_Testlauf_2026.pdf» im Archiv im Status «NEU» ohne Titel

  @req-14.10
  Szenario: Die Dokumentansicht zeigt einen Namen mit Markup und Schrift von rechts nach links nur als Text und sendet ihn nie als Titel
    Angenommen «inhaber» im Mandanten «urinet» hat die Datei «קבלה_<b>Fotobeleg.pdf» schon hochgeladen
    Und das Dokument «קבלה_<b>Fotobeleg.pdf» liegt im Mandanten «urinet» ohne Titel und Vorschlag im Status «EINGANGSKORB»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Fotobeleg» suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert   |
      | modus     | hybrid |
    Wenn ich den Treffer «קבלה_<b>Fotobeleg.pdf» öffne
    Dann sehe ich die Dokumentansicht von «קבלה_<b>Fotobeleg.pdf»
    Und zeigt die Dokumentansicht den Dateinamen «קבלה_<b>Fotobeleg.pdf» als Platzhalter und in der Zeile «Dateiname»
    Wenn ich den Knopf «Metadaten bearbeiten» wähle
    Dann ist im Formular das Feld «Titel» leer
    Wenn ich die Tags auf «quittung» ändere
    Und ich ohne Grund speichere
    Dann hat die PWA die Änderung ohne Titel und ohne den Dateinamen «קבלה_<b>Fotobeleg.pdf» gesendet
    Und steht «קבלה_<b>Fotobeleg.pdf» im Archiv im Status «EINGANGSKORB» ohne Titel

  @req-8.11
  Szenario: Der Eingangskorb zeigt den Platzhalter, bestätigt ohne Titel aus dem Dateinamen und nennt den Platzhalter in der Meldung
    Angenommen «inhaber» im Mandanten «urinet» hat die Datei «Scan_Quittung_Testgarage_2026.jpg» schon hochgeladen
    Und das Dokument «Scan_Quittung_Testgarage_2026.jpg» liegt im Mandanten «urinet» ohne Titel und Vorschlag im Status «EINGANGSKORB»
    Und «inhaber» im Mandanten «urinet» hat eine Datei ohne Namen hochgeladen
    Und das Dokument «ohne Namen» liegt im Mandanten «urinet» ohne Titel und Vorschlag im Status «FEHLER»
    Und im Mandanten «urinet» gibt es die Art «Probe ohne Titel» ohne Pflichtfelder
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann zeigt der Eintrag «Scan_Quittung_Testgarage_2026.jpg» den Dateinamen als Platzhalter statt eines Titels
    Und zeigt der Eintrag mit dem Fehlertext «Probe: Verarbeitung gescheitert» den Titel «Ohne Titel» ohne Platzhalter
    Und scrollt die Seite nicht horizontal
    Wenn ich beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» auf «Angaben ändern» tippe
    Dann ist beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» das Feld «Titel» leer
    # Req 8.3: Bei einer Art mit Pflichtfeld «titel» gilt der Platzhalter nicht als Titel
    Wenn ich beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» die Art «Korrespondenz» wähle
    Und ich beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» auf «Bestätigen» tippe
    Dann hat das Archiv das Bestätigen von «Scan_Quittung_Testgarage_2026.jpg» ohne Titel abgelehnt, weil «titel» fehlt
    Und ist beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» das Feld «Titel» als ungültig markiert
    Wenn ich beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» die Art «Probe ohne Titel» wähle
    Und ich beim Eintrag «Scan_Quittung_Testgarage_2026.jpg» auf «Bestätigen» tippe
    Dann hat das Archiv den Eintrag mit dem Dateinamen «Scan_Quittung_Testgarage_2026.jpg» ohne Titel bestätigt
    Und sehe ich die Meldung ««Ohne Titel · Scan_Quittung_Testgarage_2026.jpg» bestätigt, wird archiviert.» mit isoliertem Dateinamen
    Und steht «Scan_Quittung_Testgarage_2026.jpg» im Archiv im Status «ARCHIVIERUNG» ohne Titel
