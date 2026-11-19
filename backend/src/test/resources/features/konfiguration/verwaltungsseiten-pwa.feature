# language: de
# Browser-Teilnachweis für die Verwaltung; die Fachregeln und Rechte prüft das Backend separat.
@spec-05 @browser @attrappe
Funktionalität: Verwaltungsseiten für Dokumentarten

  Hintergrund:
    Angenommen die Verwaltungsseite liefert synthetische Konfigurationsdaten
    Und die Verwaltungsseite wird auf 375 px geprüft
    Und ich die Seite «/verwaltung/dokumentarten» öffne
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet

  @req-7.3
  Szenario: Eine Regel wird mit der aktuellen Konfigurationsversion gespeichert
    Wenn ich die neue Regel «test_regel» mit der Meldung «Titel muss eindeutig sein.» anlege
    Dann wurde die Regel mit dem aktuellen ETag gespeichert
    Und zeigt die Verwaltungsseite zwei betroffene Dokumente
    Und scrollt die Verwaltungsseite nicht horizontal

  @req-7.4
  Szenario: Der Feld-Editor erzeugt ein Zusatz-Schema mit Werteliste und Pflichtfeld
    Wenn ich die Dokumentart «TEST_ART» mit dem Auswahlfeld «prioritaet» anlege
    Dann wurde die Dokumentart mit dem aktuellen ETag gespeichert
    Und enthält das gesendete Zusatz-Schema die Werte und Beschriftungen des Feld-Editors
    Und scrollt die Verwaltungsseite nicht horizontal
