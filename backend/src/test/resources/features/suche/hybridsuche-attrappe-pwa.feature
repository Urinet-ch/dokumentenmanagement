# language: de
# Hybridsuche als Standard in der PWA gegen die Attrappe (Spec 02, Aufgabe 6.6). Die Attrappe
# (frontend/e2e/hilfen/suche-attrappe.mjs) antwortet im Modus hybrid nach dem Vertrag von Aufgabe 6.4 und lehnt falsche
# Anfragen der PWA wie die echte API mit 400 ab. Sie ist der einzige Ort, an dem die PWA eine Hybridsuche ohne Rückfall
# in der Reihenfolge des Rerankers zeigt; den Vertrag der echten Suche und den Ausfall des Rerankers belegen
# suche/hybridsuche.feature (@req-2.3, @req-2.7), die Rückfall-Anzeige gegen das echte Archiv
# suche/hybridsuche-pwa.feature. Darum Teilnachweis (@attrappe).
@spec-02 @browser @attrappe
Funktionalität: Hybridsuche als Standard im Browser gegen die Attrappe

  Hintergrund:
    Angenommen das Archiv liefert synthetische Suchtreffer
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne

  @req-2.7
  Szenario: Hybrid ohne Rückfall zeigt die Treffer in der gelieferten Reihenfolge, ohne Hinweis, Gesamtzahl und Blättern
    Wenn ich nach «Sammelbeleg» suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert        |
      | modus     | hybrid      |
      | q         | Sammelbeleg |
    Und sendet die PWA keinen Parameter «sortierung, seite, groesse»
    Und sehe ich 30 Treffer
    Und sehe ich die Treffer in der Reihenfolge, in der das Archiv sie liefert
    Und zeigt die Seite keine Gesamtzahl
    Und gibt es kein Blättern
    Und zeigt die Seite keinen Hinweis auf den Volltext
    Und ist der Umschalter «Volltext» aus

  @req-2.7
  Szenario: Der Umschalter «Volltext» wechselt in den bisherigen Modus mit Anzahl und Blättern und zurück
    Wenn ich nach «Sammelbeleg» suche
    Und ich den Umschalter «Volltext» einschalte
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter  | wert        |
      | modus      | volltext    |
      | q          | Sammelbeleg |
      | sortierung | RELEVANZ    |
      | seite      | 0           |
      | groesse    | 20          |
    Und zeigt die Seite die Anzahl «45 Treffer»
    Und kann ich blättern und stehe auf «Seite 1 von 3»
    Wenn ich den Umschalter «Volltext» ausschalte
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert        |
      | modus     | hybrid      |
      | q         | Sammelbeleg |
    Und gibt es kein Blättern

  @req-2.7
  Szenario: Ohne Suchbegriff gibt es keinen Umschalter und die Suche läuft im Volltext
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert     |
      | modus     | volltext |
      | seite     | 0        |
    Und gibt es den Umschalter «Volltext» nicht

  @req-2.7
  Szenario: Die Sortierung nach Datum schaltet «Volltext» ein, das Ausschalten setzt die Relevanz
    Wenn ich nach «Sammelbeleg» suche
    Und ich die Sortierung «Belegdatum, neueste zuerst» wähle und suche
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter  | wert        |
      | modus      | volltext    |
      | sortierung | DATUM       |
      | seite      | 0           |
    Und ist der Umschalter «Volltext» ein
    Wenn ich den Umschalter «Volltext» ausschalte
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert   |
      | modus     | hybrid |
    Und ist die Sortierung «Relevanz» gewählt

  @req-2.7
  Szenario: Bei Ausfall des Rerankers zeigt die Seite den Hinweis und wechselt auf Wunsch in den Volltext
    Angenommen der Reranker des Archivs ist ausgefallen
    Wenn ich nach «Sammelbeleg» suche
    Dann zeigt die Seite den Hinweis «Nur im Volltext gesucht»
    Und sehe ich 30 Treffer
    Und zeigt die Seite keine Gesamtzahl
    Und gibt es kein Blättern
    Wenn ich im Hinweis den Knopf «Alle Treffer im Volltext zeigen» wähle
    Dann hat die PWA die Suche mit diesen Parametern angefragt:
      | parameter | wert     |
      | modus     | volltext |
      | seite     | 0        |
    Und zeigt die Seite die Anzahl «45 Treffer»
    Und zeigt die Seite keinen Hinweis auf den Volltext
    Und ist der Umschalter «Volltext» ein
    Wenn ich zur nächsten Seite blättere
    Dann kann ich blättern und stehe auf «Seite 2 von 3»
    Und sehe ich 20 Treffer

  @req-2.7
  Szenario: Auf dem Handy passen Umschalter und Hinweis ins Fenster, die Filterleiste bleibt eingeklappt
    Angenommen das Browserfenster ist 375 Pixel breit
    Und der Reranker des Archivs ist ausgefallen
    Wenn ich nach «Sammelbeleg» suche
    Dann liegen der Umschalter «Volltext» und der Hinweis ganz im Fenster
    Und ist die Filterleiste eingeklappt
    Und lässt sich die Seite nicht waagrecht scrollen
