# language: de
@spec-07 @browser
Funktionalität: Abschnitte der Anleitung zu Erfassen, Eingangskorb und Dokumentansicht
  Die Anleitung führt die Abschnitte und Unterabschnitte aus Requirement 6.1 und design.md «Hilfe-Katalog» je mit einer
  Datei, die mit der App gebaut wird (Spec 07, Aufgabe 22.5). Reihenfolge, Titel, Bedingungen und Verweise zwischen den
  Abschnitten prüft der Vitest anleitung-abschnitte.spec.ts; die Seite /hilfe mit dem Inhaltsverzeichnis je Rolle kommt
  mit Aufgabe 22.2.

  @req-6.1
  Szenariogrundriss: Jeder Abschnitt der Aufgabe 22.5 kommt mit dem Prod-Build
    Dann liefert das Archiv die Anleitung «<anker>» als Markdown aus dem Build aus

    Beispiele:
      | anker                    |
      | erste-schritte           |
      | erfassen                 |
      | erfassen-handy           |
      | erfassen-dateien         |
      | erfassen-offline         |
      | eingangskorb             |
      | eingangskorb-bestaetigen |
      | eingangskorb-eigene      |
      | dokument                 |
      | dokument-aendern         |
