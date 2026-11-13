# language: de
# Metadaten bearbeiten, Versionsliste und Vorschaubild in der Dokumentansicht der PWA (Aufgabe 4.6, Rest). Die API
# beantwortet eine Attrappe mit synthetischen Dokumenten (frontend/e2e/hilfen/suche-attrappe.mjs); den Vertrag von PATCH,
# If-Match, Grund und Fassungen belegen die Java-Szenarien in dokument/aendern.feature, das Vorschaubild
# dokument/eingangskorb.feature. Darum Teilnachweis (@attrappe).
@spec-01 @browser @attrappe
Funktionalität: Metadaten bearbeiten und Versionen in der Dokumentansicht

  Hintergrund:
    Angenommen das Archiv liefert synthetische Suchtreffer

  @req-10.1
  Szenario: Der Inhaber ändert ein archiviertes Dokument mit Grund, die bisherige Fassung erscheint in den Versionen
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Seedorf» suche
    Und ich den Treffer «Rechnung Vereinsfest Seedorf» öffne
    Dann sehe ich die Dokumentansicht von «Rechnung Vereinsfest Seedorf»
    Und nennt die Dokumentansicht keine früheren Fassungen
    Wenn ich den Knopf «Metadaten bearbeiten» wähle
    Und ich den Titel auf «Rechnung Vereinsfest Seedorf 2026» ändere
    Und ich ohne Grund speichere
    Dann verlangt das Formular einen Grund und die PWA hat nichts gesendet
    Wenn ich als Grund «Jahr im Titel ergänzt» angebe und speichere
    Dann hat die PWA nur den Titel und den Grund mit «If-Match» der Version 2 gesendet
    Und trägt die Dokumentansicht den Titel «Rechnung Vereinsfest Seedorf 2026»
    Und meldet die Dokumentansicht «Änderungen gespeichert.»
    Und nennt die Dokumentansicht die Fassung 2 mit dem Grund «Jahr im Titel ergänzt», geändert von «Ihnen»

  @req-10.1
  Szenario: Beim Artwechsel auf eine Art ohne Zusatzfelder geht kein Zusatzwert der alten Art mit
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Altdorf» suche
    Und ich den Treffer «Offerte Testclub Altdorf» öffne
    Dann sehe ich die Dokumentansicht von «Offerte Testclub Altdorf»
    Wenn ich den Knopf «Metadaten bearbeiten» wähle
    Und ich die Art auf «Quittung» wechsle
    Und ich als Grund «Art korrigiert» angebe und speichere
    Dann hat die PWA die Art «QUITTUNG» mit den Zusatzwerten {} und dem Grund gesendet

  @req-10.1
  Szenario: Beim Artwechsel auf eine Art mit anderen Zusatzfeldern gehen nur deren Werte mit
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Altdorf» suche
    Und ich den Treffer «Offerte Testclub Altdorf» öffne
    Dann sehe ich die Dokumentansicht von «Offerte Testclub Altdorf»
    Wenn ich den Knopf «Metadaten bearbeiten» wähle
    Und ich die Art auf «Lohnabrechnung» wechsle
    Und ich das Zusatzfeld «periode» mit «2026-10» ausfülle
    Und ich das Zusatzfeld «person» mit «Testperson» ausfülle
    Und ich als Grund «Art korrigiert» angebe und speichere
    Dann hat die PWA die Art «LOHNABRECHNUNG» mit den Zusatzwerten {"periode":"2026-10","person":"Testperson"} und dem Grund gesendet

  @req-10.1
  Szenario: Ohne Recht A6 bietet die Ansicht kein Bearbeiten an
    Angenommen ich bin als «treuhaender» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Seedorf» suche
    Und ich den Treffer «Rechnung Vereinsfest Seedorf» öffne
    Dann sehe ich die Dokumentansicht von «Rechnung Vereinsfest Seedorf»
    Und bietet die Dokumentansicht «Metadaten bearbeiten» nicht an

  @req-14.6
  Szenario: Auf dem Handy zeigt die Dokumentansicht das Vorschaubild der ersten Seite statt des eingebetteten PDF
    Angenommen das Browserfenster ist 375 Pixel breit
    Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Und ich die Suche über das Menü öffne
    Wenn ich nach «Seedorf» suche
    Und ich den Treffer «Rechnung Vereinsfest Seedorf» öffne
    Dann sehe ich die Dokumentansicht von «Rechnung Vereinsfest Seedorf»
    Und zeigt die Dokumentansicht das Vorschaubild der Seite 1 von «Rechnung Vereinsfest Seedorf»
    Und öffnet «PDF/A herunterladen» die vorsignierte Adresse in einem neuen Tab
    Und lässt sich die Seite nicht waagrecht scrollen
