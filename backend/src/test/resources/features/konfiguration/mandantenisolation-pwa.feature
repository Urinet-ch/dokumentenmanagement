# language: de
@spec-05 @browser @req-9.3
Funktionalität: Mandantenkonfiguration in der Verwaltungsoberfläche

  Szenariogrundriss: Die Oberfläche zeigt nur Dokumentarten des angemeldeten Mandanten
    Angenommen die PWA spricht mit dem Archiv im Testmodus
    Und beide Mandanten haben je eine synthetische Klassifikation
    Und ich bin als «inhaber» im Mandanten «<mandant>» angemeldet
    Wenn ich im Menü «Dokumentarten» wähle
    Dann zeigt die Verwaltung die eigene Art «<eigene>» und nicht die fremde Art «<fremde>»
    Und zeigt die Verwaltung die eigene und nicht die fremde synthetische Klassifikation
    Und zeigt die Verwaltung nur Konfigurationsversionen des Mandanten «<mandant>» statt «<anderer>»
    Und ist die Urinet-Regel «ausgangsrechnung_folge» in der Verwaltung «<regel>»

    Beispiele:
      | mandant | anderer | eigene             | fremde             | regel           |
      | urinet  | vhf-gsk | AUSGANGSRECHNUNG   | PROBE_VHF_BULLETIN | sichtbar        |
      | vhf-gsk | urinet  | PROBE_VHF_BULLETIN | AUSGANGSRECHNUNG   | nicht sichtbar  |
