# language: de
@spec-04 @browser
Funktionalität: Wissensquellen im Chat gegen das echte Archiv

  @req-8.8 @req-16.6
  Szenario: Eine Chatquelle öffnet die freigegebene Wissensseite mit Protokolleintrag
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und «inhaber» im Mandanten «urinet» hat das Dokument «Rechnung Chatdorf» mit dem Titel «Rechnung Testverein Chatdorf» im Eingangskorb
    Und eine synthetische Wissensseite ist freigegeben
    Und das simulierte Sprachmodell nutzt die Wissensseite und das Dokument «Rechnung Chatdorf»
    Und ein Bildschirm mit 375 Pixel Breite
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich den Chat über das Menü öffne
    Und ich die Frage «Zeig mir Wissen und Dokument» sende
    Dann zeigt der echte Chat unterscheidbare Quellen
    Wenn ich die echte Wissensquelle im Chat öffne
    Dann ist die echte Wissensseite geöffnet und ihr Abruf protokolliert

  @req-16.6
  Szenario: Eine Antwort mit freigegebenem Wissen wird als Analyse vorgeschlagen
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und eine synthetische Wissensseite ist freigegeben
    Und das simulierte Sprachmodell nutzt nur die Wissensseite
    Und ein Bildschirm mit 375 Pixel Breite
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich den Chat über das Menü öffne
    Und ich die Frage «Zeig mir Wissen» sende
    Und ich die echte Antwort als Analyse vorschlage
    Dann zeigt der echte Chat den Status des Analysevorschlags
