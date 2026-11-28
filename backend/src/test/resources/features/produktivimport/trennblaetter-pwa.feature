# language: de
# Seite «Trennblätter» der PWA (Spec 09, Aufgabe 27.5) gegen eine Attrappe von GET /dokumentarten und
# POST /trennblaetter (frontend/e2e/schritte/trennblaetter.mjs). Das PDF und die Rechte des Servers belegen die
# Java-Szenarien in trennblaetter.feature. Alle Daten sind synthetisch.
@spec-09 @browser @attrappe
Funktionalität: Trennblätter in der PWA

  @req-6.1
  Szenariogrundriss: Trennblätter und Deckblatt wählen und das PDF herunterladen (<breite> px, <schema>)
    Angenommen das Browserfenster ist <breite> Pixel breit
    Und das Farbschema ist «<schema>»
    Und die Trennblatt-Schnittstelle ist eine Attrappe
    Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Und ich im Menü «Trennblätter» wähle
    Dann zeigt die Seite «Trennblätter» die Arten «BANK, QUITTUNG»
    Wenn ich für «Ohne Art» 2 Trennblätter wähle
    Und ich für «Quittung / Spesen» 3 Trennblätter wähle
    Und ich das Ordner-Deckblatt «Versicherungen 2026» hinzufüge
    Dann ist die Seite «Trennblätter» ohne Überlauf und axe-Befund
    Wenn ich auf «PDF herunterladen» klicke
    Dann hat das Archiv diese Trennblätter erhalten «TRENN - 2, TRENN QUITTUNG 3, ORDNER Versicherungen 2026 1»
    Und lädt der Browser das PDF «trennblaetter.pdf» herunter

    Beispiele:
      | breite | schema |
      | 375    | hell   |
      | 375    | dunkel |
      | 1280   | hell   |
      | 1280   | dunkel |

  @req-6.1
  Szenario: Ohne Recht A1 zeigt die PWA weder Menüpunkt noch Seite
    Angenommen die Trennblatt-Schnittstelle ist eine Attrappe
    Und Keycloak kennt mich als «treuhaender» im Mandanten «urinet»
    Wenn ich die Seite «/trennblaetter» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/»
    Und sehe ich im Menü genau «Übersicht, Eingangskorb, Suche, Chat, Wissen, Jahresexport, Quellen»
    Und hat die PWA die Trennblatt-Schnittstelle nicht aufgerufen
