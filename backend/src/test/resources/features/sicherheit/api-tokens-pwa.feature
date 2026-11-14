# language: de
# API-Token-Verwaltung in der PWA gegen das echte Archiv im Testmodus. Alle Tokens und Dateien sind synthetisch.
@spec-01 @browser
Funktionalität: API-Tokens in der PWA

  @req-2.5
  Szenario: Inhaber sieht die Nutzung und widerruft ein API-Token
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und ein Bildschirm mit 375 Pixel Breite
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und im Mandanten «urinet» gibt es ein nie genutztes und ein einmal genutztes API-Token
    Und ich im Menü «API-Tokens» wähle
    Dann sehe ich das nie genutzte API-Token vor dem genutzten
    Und ragt nichts über den Bildschirm hinaus
    Wenn ich das genutzte API-Token mit Bestätigung widerrufe
    Dann das API-Token ist widerrufen und erhält bei Verwendung HTTP 401

  @req-2.20
  Szenariogrundriss: Andere Rollen können API-Tokens nicht verwalten (<rolle>)
    Angenommen Keycloak kennt mich als «<rolle>» im Mandanten «<mandant>»
    Wenn ich die Seite «/verwaltung/api-tokens» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/»
    Und sehe ich im Menü genau «<menü>»
    Und hat die PWA die API-Tokens nicht abgerufen

    Beispiele:
      | rolle            | mandant | menü                          |
      | mitarbeiter      | urinet  | Erfassen, Eingangskorb, Suche |
      | treuhaender      | urinet  | Suche, Jahresexport           |
      | geschaeftsstelle | vhf-gsk | Erfassen, Eingangskorb, Suche, Kontakte |
