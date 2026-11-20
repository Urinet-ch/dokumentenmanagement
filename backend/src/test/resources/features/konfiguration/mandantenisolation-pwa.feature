# language: de
@spec-05 @browser @req-9.3
Funktionalität: Mandantenkonfiguration in der Verwaltungsoberfläche

  Szenariogrundriss: Die Oberfläche zeigt nur Dokumentarten des angemeldeten Mandanten
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und ich bin als «inhaber» im Mandanten «<mandant>» angemeldet
    Wenn ich im Menü «Dokumentarten» wähle
    Dann zeigt die Verwaltung die eigene Art «<eigene>» und nicht die fremde Art «<fremde>»

    Beispiele:
      | mandant | eigene             | fremde             |
      | urinet  | AUSGANGSRECHNUNG  | PROBE_VHF_BULLETIN |
      | vhf-gsk | PROBE_VHF_BULLETIN | AUSGANGSRECHNUNG  |
