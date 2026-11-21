# language: de
# Seite /hilfe der PWA (Spec 07, Aufgabe 22.2) gegen die Attrappen von Keycloak und Archiv. Die Seite zeigt den Hilfe-
# Katalog (frontend/src/app/hilfe/hilfe-katalog.ts), der mit den Aufgaben 22.5 bis 22.7 wächst: Die Szenarien prüfen
# darum die allgemeinen Abschnitte am Anfang und am Ende und die Anker, die eine Rolle nach design.md «Hilfe-Katalog» nie
# sehen darf; ein Anker, den der Katalog noch nicht kennt, erscheint ohnehin nicht. Den Filter über den ganzen Katalog
# belegt Vitest (hilfe-sichtbar.spec.ts). Weil die Anmeldung nur im Arbeitsspeicher liegt, öffnen angemeldete Szenarien
# die Seite innerhalb der App statt über ein Neuladen.
@spec-07 @browser
Funktionalität: Seite «Hilfe» mit Inhaltsverzeichnis und Sprungmarken

  @req-5.1 @req-6.2 @req-6.5
  Szenariogrundriss: Die Anleitung zeigt «<rolle>» nur die Abschnitte der eigenen Rolle
    Angenommen ich bin als «<rolle>» im Mandanten «<mandant>» angemeldet
    Wenn ich in der App «/hilfe» aufrufe
    Dann sehe ich die Seite «Hilfe» mit dem Namen aus dem Branding
    Und sehe ich das Inhaltsverzeichnis «Inhalt der Anleitung» von «Erste Schritte» bis «Wenn etwas nicht klappt»
    Und folgen die Abschnitte dem Inhaltsverzeichnis
    Und zeigt die Anleitung keinen der Abschnitte «<verborgen>»
    Und sehe ich die Meldung «Sie sind als ‹<bezeichnung>› angemeldet. Diese Anleitung zeigt die Bereiche, die Ihre Rolle nutzen darf.»

    Beispiele:
      | rolle            | mandant | bezeichnung     | verborgen                                                                                                                                                                                                  |
      | inhaber          | urinet  | Inhaber         | eingangskorb-eigene                                                                                                                                                                                        |
      | mitarbeiter      | urinet  | Mitarbeiter     | eingangskorb-bestaetigen, dokument-aendern, kontakte, wissen-entwuerfe, importe, jahresexport, verwaltung, verwaltung-dokumentarten, verwaltung-protokoll, verwaltung-api-tokens                          |
      | treuhaender      | urinet  | Treuhänder      | erfassen, erfassen-handy, erfassen-dateien, erfassen-offline, eingangskorb, eingangskorb-bestaetigen, eingangskorb-eigene, dokument-aendern, kontakte, wissen-entwuerfe, importe, verwaltung, verwaltung-dokumentarten, verwaltung-protokoll, verwaltung-api-tokens |
      | geschaeftsstelle | vhf-gsk | Geschäftsstelle | eingangskorb-eigene, importe, jahresexport, verwaltung, verwaltung-dokumentarten, verwaltung-protokoll, verwaltung-api-tokens                                                                              |

  @req-5.1 @req-6.3
  Szenario: Ohne Anmeldung zeigt die Anleitung nur die allgemeinen Abschnitte und den Hinweis
    Wenn ich die Seite «/hilfe» öffne
    Dann sehe ich die Seite «Hilfe» mit dem Namen aus dem Branding
    Und sehe ich das Inhaltsverzeichnis «Inhalt der Anleitung» von «Erste Schritte» bis «Wenn etwas nicht klappt»
    Und folgen die Abschnitte dem Inhaltsverzeichnis
    Und zeigt die Anleitung keinen der Abschnitte «erfassen, erfassen-handy, erfassen-dateien, eingangskorb, eingangskorb-bestaetigen, eingangskorb-eigene, suche, chat, dokument, dokument-aendern, kontakte, wissen, wissen-entwuerfe, importe, jahresexport, verwaltung, verwaltung-dokumentarten, verwaltung-protokoll, verwaltung-api-tokens»
    Und sehe ich die Meldung «Nach der Anmeldung sehen Sie die Anleitung für alle Bereiche Ihrer Rolle.»

  @req-6.3
  Szenario: Ohne Mandant zeigt die Anleitung dasselbe wie ohne Anmeldung
    Angenommen Keycloak kennt mich als «inhaber» ohne Mandant
    Wenn ich die Startseite öffne
    Und ich mich anmelde
    Und ich in der App «/hilfe» aufrufe
    Dann sehe ich das Inhaltsverzeichnis «Inhalt der Anleitung» von «Erste Schritte» bis «Wenn etwas nicht klappt»
    Und zeigt die Anleitung keinen der Abschnitte «erfassen, eingangskorb, suche, chat, dokument, kontakte, wissen, importe, jahresexport, verwaltung»
    Und sehe ich die Meldung «Nach der Anmeldung sehen Sie die Anleitung für alle Bereiche Ihrer Rolle.»

  @req-5.2 @req-5.3
  Szenariogrundriss: Das Inhaltsverzeichnis setzt die Sprungmarke, der Zurück-Knopf führt zur vorherigen Stelle (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Wenn ich in der App «/hilfe» aufrufe
    Und ich im Inhaltsverzeichnis «Wenn etwas nicht klappt» wähle
    Dann steht die Adresse auf «/hilfe#probleme»
    Und liegt der Fokus auf der sichtbaren Überschrift «Wenn etwas nicht klappt»
    Wenn ich im Inhaltsverzeichnis «Erste Schritte» wähle
    Dann steht die Adresse auf «/hilfe#erste-schritte»
    Und liegt der Fokus auf der sichtbaren Überschrift «Erste Schritte»
    Wenn ich im Browser zurückgehe
    Dann steht die Adresse auf «/hilfe#probleme»
    Und liegt der Fokus auf der sichtbaren Überschrift «Wenn etwas nicht klappt»

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-5.2
  Szenario: Eine Sprungmarke in der Adresse öffnet die Seite beim Abschnitt
    Angenommen ein Bildschirm mit 375 Pixel Breite
    Wenn ich die Seite «/hilfe#probleme» öffne
    Dann liegt der Fokus auf der sichtbaren Überschrift «Wenn etwas nicht klappt»

  @req-5.2
  Szenariogrundriss: Ein <art> Anker zeigt die Seite von oben, ohne Meldung
    Angenommen ein Bildschirm mit 375 Pixel Breite
    Und ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Wenn ich in der App «/hilfe#probleme» aufrufe
    Und ich in der App «/hilfe#<anker>» aufrufe
    Dann zeigt die Seite «Hilfe» von oben, ohne Meldung

    Beispiele:
      | art         | rolle       | anker          |
      | unbekannter | mitarbeiter | gibt-es-nicht  |
      | verborgener | treuhaender | erfassen       |

  @req-5.4
  Szenariogrundriss: Einspaltig auf dem Handy, breit mit dem Inhaltsverzeichnis links (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Wenn ich die Seite «/hilfe» öffne
    Dann steht das Inhaltsverzeichnis <lage>
    Und hat die Seite keinen waagrechten Bildlauf

    Beispiele:
      | breite | lage                                                            |
      | 375    | als Karte über den Abschnitten                                  |
      | 1280   | links neben den Abschnitten und bleibt beim Scrollen sichtbar   |

  @req-7.6
  Szenario: Eine nicht ladbare Datei zeigt den Hinweis beim Abschnitt, die übrigen Abschnitte bleiben
    Angenommen die Datei «/anleitung/probleme.md» ist nicht ladbar
    Wenn ich die Seite «/hilfe» öffne
    Dann zeigt der Abschnitt «probleme» den Hinweis «Dieser Teil der Anleitung ist gerade nicht verfügbar. Bitte versuchen Sie es später erneut.» mit dem Knopf «Erneut versuchen»
    Und zeigt der Abschnitt «erste-schritte» seinen Text
    Wenn die Datei «/anleitung/probleme.md» wieder ladbar ist
    Und ich beim Abschnitt «probleme» «Erneut versuchen» wähle
    Dann zeigt der Abschnitt «probleme» seinen Text
