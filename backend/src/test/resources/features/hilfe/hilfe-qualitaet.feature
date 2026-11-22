# language: de
@spec-07 @browser @attrappe
Funktionalität: Barrierefreiheit und Datenschutz der Einführung und Hilfe

  @req-9.2 @req-9.4 @req-10.1
  Szenariogrundriss: Einführung und Hilfe für <rolle> bei <breite> px in <schema>
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und das Farbschema ist «<schema>»
    Und ich bin als «<rolle>» im Mandanten «<mandant>» angemeldet
    Wenn ich Einführung und Hilfe auf Barrierefreiheit und Datenschutz prüfe
    Dann meldet axe keinen Befund und die Hilfe sendet keine zusätzlichen API- oder fremden Anfragen

    Beispiele:
      | breite | schema | rolle            | mandant |
      | 375    | hell   | inhaber          | urinet  |
      | 375    | hell   | mitarbeiter      | urinet  |
      | 375    | hell   | treuhaender      | urinet  |
      | 375    | hell   | geschaeftsstelle | vhf-gsk |
      | 375    | dunkel | inhaber          | urinet  |
      | 375    | dunkel | mitarbeiter      | urinet  |
      | 375    | dunkel | treuhaender      | urinet  |
      | 375    | dunkel | geschaeftsstelle | vhf-gsk |
      | 1280   | hell   | inhaber          | urinet  |
      | 1280   | hell   | mitarbeiter      | urinet  |
      | 1280   | hell   | treuhaender      | urinet  |
      | 1280   | hell   | geschaeftsstelle | vhf-gsk |
      | 1280   | dunkel | inhaber          | urinet  |
      | 1280   | dunkel | mitarbeiter      | urinet  |
      | 1280   | dunkel | treuhaender      | urinet  |
      | 1280   | dunkel | geschaeftsstelle | vhf-gsk |

  @req-9.2 @req-9.4
  Szenariogrundriss: Hilfe ohne Anmeldung bei <breite> px in <schema>
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und das Farbschema ist «<schema>»
    Wenn ich die Seite «/» öffne
    Und ich die Hilfe ohne Anmeldung auf Barrierefreiheit und Datenschutz prüfe
    Dann meldet axe keinen Befund und die Hilfe sendet keine zusätzlichen API- oder fremden Anfragen

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |
