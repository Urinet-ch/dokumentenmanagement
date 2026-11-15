# language: de
# Chat freigeschaltet, einmal durchgehend gegen das echte Archiv (Spec 02, Aufgabe 7.4b; Req 4.3, 6.3; 6.1 und 6.2 weist chat-pwa.feature gegen die Attrappe nach): Playwright
# leitet die Anfragen der PWA an das Archiv im Testmodus weiter (BrowserBackendTest mit PostgreSQL, Keycloak, MinIO).
# BrowserBackendTest schaltet den Chat dort auf den echten Agenten; das Sprachmodell ist der WireMock von KiWireMock
# (gestreamter Tool-Call «dokument_zeigen», danach gestreamter Text). Kein Request an Infomaniak. Das Dokument ist
# synthetisch (ein Text-PDF ohne Inhalt, Titel gesetzt über die Testhilfe für den Status).
@spec-02 @browser
Funktionalität: Chat im Menü, Frage, Quelle und Protokoll gegen das echte Archiv

  @req-4.3
  Szenario: Die Gesprächsliste zeigt nur Gespräche des angemeldeten Benutzers
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und verschiedene Benutzer haben eigene echte Chat-Gespräche
    Und ein Bildschirm mit 1280 Pixel Breite
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich den Chat über das Menü öffne
    Dann sehe ich im Chat nur meine eigenen Gespräche

  @req-4.3 @req-6.3
  Szenariogrundriss: Frage stellen, Antwort fortlaufend, Quelle öffnet das Dokument, Protokoll enthält ANZEIGEN (<breite> px)
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und der Szenariobeginn ist gemerkt
    Und «inhaber» im Mandanten «urinet» hat das Dokument «Rechnung Chatdorf» mit dem Titel «Rechnung Testverein Chatdorf» im Eingangskorb
    Und das simulierte Sprachmodell ruft «dokument_zeigen» für «Rechnung Chatdorf» auf und antwortet danach «Die Rechnung vom Testverein Chatdorf liegt im Eingangskorb und ist noch nicht bestätigt.»
    Und ein Bildschirm mit <breite> Pixel Breite
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich den Chat über das Menü öffne
    Und ich die Frage «Zeig mir die Rechnung von Chatdorf» sende
    Dann sehe ich die Antwort fortlaufend, bis sie vollständig ist
    Und sehe ich die Quellen «Rechnung Testverein Chatdorf»
    Und ragt nichts über den Bildschirm hinaus
    Wenn ich auf die Quelle «Rechnung Testverein Chatdorf» tippe
    Dann bin ich auf der Seite des angelegten Dokuments «Rechnung Chatdorf»
    Und ragt nichts über den Bildschirm hinaus
    Und enthält das Zugriffsprotokoll von «urinet» ANZEIGEN für das Dokument «Rechnung Chatdorf» mindestens einmal mit dem Kanal «CHAT» und mindestens einmal ohne Kanal

    Beispiele:
      | breite |
      | 375 |
      | 1280 |
