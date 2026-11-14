# language: de
# Hybridsuche als Standard in der PWA gegen das echte Archiv (Spec 02, Aufgabe 6.6): Playwright leitet die Anfragen der
# PWA an das Archiv im Testmodus weiter (BrowserBackendTest mit PostgreSQL, Keycloak und MinIO). Die Dokumente sind
# synthetisch: 31 Dateien, deren Namen ein gemeinsames Wort tragen und deren Inhalte verschieden sind
# (Dublettenprüfung); im Testmodus läuft kein Worker, sie bleiben in NEU und sind über den Dateinamen auffindbar.
#
# Ehrlich zur Herkunft des Rückfalls: BrowserBackendTest schaltet Embedding und Reranker auf die Antwort der Dev-Stubs
# (TestEmbeddingPort, TestRerankerPort). Das Archiv antwortet im Modus hybrid darum deterministisch mit `degradiert`,
# ohne je einen Request an Port 8089 oder ins Internet zu senden. Einen echten Ausfall des Rerankers belegt hier nicht
# diese Datei, sondern suche/hybridsuche.feature (WireMock, @req-2.3). Der Hybrid-Pfad mit Reranker-Reihenfolge ohne
# Rückfall ist in der PWA nur gegen die Attrappe belegt (suche/hybridsuche-attrappe-pwa.feature).
@spec-02 @browser
Funktionalität: Hybridsuche als Standard im Browser gegen das echte Archiv

  Hintergrund:
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und «inhaber» im Mandanten «urinet» hat 31 Dateien mit einem gemeinsamen Wort im Namen hochgeladen
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne

  @req-2.7
  Szenario: Die Suche ist hybrid und zeigt im Rückfall den Hinweis, 30 Treffer ohne Gesamtzahl und ohne Blättern
    Wenn ich nach dem gemeinsamen Wort suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert   |
      | modus     | hybrid |
    Und sendet die PWA keinen Parameter «sortierung, seite, groesse»
    Und hat das Archiv im Modus hybrid mit degradiert geantwortet
    Und zeigt die Seite den Hinweis «Nur im Volltext gesucht»
    Und sehe ich 30 Treffer
    Und zeigt die Seite keine Gesamtzahl
    Und gibt es kein Blättern
    Und ist der Umschalter «Volltext» aus

  @req-2.7
  Szenario: Der Knopf im Hinweis wechselt in den Volltext mit Gesamtzahl und Blättern
    Wenn ich nach dem gemeinsamen Wort suche
    Und ich im Hinweis den Knopf «Alle Treffer im Volltext zeigen» wähle
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert     |
      | modus     | volltext |
      | seite     | 0        |
      | groesse   | 20       |
    Und zeigt die Seite die Anzahl «31 Treffer»
    Und zeigt die Seite keinen Hinweis auf den Volltext
    Und ist der Umschalter «Volltext» ein
    Und sehe ich 20 Treffer
    Wenn ich zur nächsten Seite blättere
    Dann kann ich blättern und stehe auf «Seite 2 von 2»
    Und sehe ich 11 Treffer

  @req-2.7
  Szenario: Ohne Suchbegriff, nur mit Filter, läuft der Volltext mit Anzahl und Blättern
    Wenn ich nur den Status «Neu» als Filter setze und suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert     |
      | modus     | volltext |
      | status    | NEU      |
      | seite     | 0        |
    Und sendet die PWA keinen Parameter «q, sortierung»
    Und zeigt die Seite eine Anzahl von mindestens 31 Treffern
    Und kann ich blättern
    Und gibt es den Umschalter «Volltext» nicht

  @req-2.7
  Szenario: Die Sortierung nach Datum sucht im Volltext und zeigt den Umschalter «Volltext» ein
    Wenn ich nach dem gemeinsamen Wort suche
    Und ich die Sortierung «Belegdatum, neueste zuerst» wähle und suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter  | wert     |
      | modus      | volltext |
      | sortierung | DATUM    |
      | seite      | 0        |
    Und zeigt die Seite die Anzahl «31 Treffer»
    Und ist der Umschalter «Volltext» ein

  @req-2.7
  Szenario: «Zurück zur Suche» aus der Dokumentansicht zeigt denselben Modus und dieselben Treffer
    Wenn ich nach dem gemeinsamen Wort suche
    Und ich den ersten Treffer öffne
    Und ich den Link «Zurück zur Suche» wähle
    Dann zeigt die Suche dieselben Treffer wie vor dem Öffnen, ohne neue Anfrage
    Und zeigt die Seite keine Gesamtzahl
    Und ist der Umschalter «Volltext» aus
    Und zeigt die Seite den Hinweis «Nur im Volltext gesucht»
