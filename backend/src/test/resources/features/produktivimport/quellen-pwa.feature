# language: de
@spec-09 @browser @attrappe
Funktionalität: Quellenregister in der PWA
  Die Quellen-Seite zeigt Register und Quellläufe mit A27; Pflegeaktionen sind auf A28 beschränkt.
  Alle Testdaten sind synthetisch.

  @req-10.8
  Szenariogrundriss: Quellen und Quellläufe auf verschiedenen Bildschirmen lesen (<breite> px, <schema>)
    Angenommen das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und die Quellen-Schnittstelle ist eine Attrappe
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Quellen» wähle
    Dann sehe ich die synthetische Quelle mit Quelllauf-Zählern
    Und kann ich Quellen pflegen
    Und ist die Seite «Quellen» ohne Überlauf und axe-Befund

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |

  @req-10.8
  Szenario: Der Treuhänder sieht Quellen und Läufe, aber keine Pflegeaktionen
    Angenommen die Quellen-Schnittstelle ist eine Attrappe
    Und ich bin als «treuhaender» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Quellen» wähle
    Dann sehe ich die synthetische Quelle mit Quelllauf-Zählern
    Und kann ich Quellen nicht pflegen

  @req-10.8
  Szenario: Der Mitarbeiter ohne A27 kann die Quellen-Seite nicht öffnen
    Angenommen die Quellen-Schnittstelle ist eine Attrappe
    Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Wenn ich die Seite «/quellen» öffne
    Dann bin ich auf der Seite «/»
    Und hat die PWA das Quellenregister nicht abgerufen
