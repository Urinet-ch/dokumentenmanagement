# language: de
@spec-07 @browser
Funktionalität: Abschnitte der Anleitung zu Suche, Chat, Kontakten und Wissen
  Die Anleitung führt die Abschnitte und Unterabschnitte aus Requirement 6.1 und design.md «Hilfe-Katalog» je mit einer
  Datei, die mit der App gebaut wird (Spec 07, Aufgabe 22.6). Reihenfolge, Bedingungen, Eltern, Sichtbarkeit je Rolle und
  die Zuordnung der Pfade prüft der Vitest hilfe-katalog.spec.ts; die Seite /hilfe mit dem Inhaltsverzeichnis je Rolle
  kommt mit Aufgabe 22.2.

  @req-6.1
  Szenariogrundriss: Jeder Abschnitt der Aufgabe 22.6 kommt mit dem Prod-Build
    Dann liefert das Archiv die Anleitung «<anker>» als Markdown aus dem Build aus

    Beispiele:
      | anker            |
      | suche            |
      | chat             |
      | kontakte         |
      | wissen           |
      | wissen-entwuerfe |
