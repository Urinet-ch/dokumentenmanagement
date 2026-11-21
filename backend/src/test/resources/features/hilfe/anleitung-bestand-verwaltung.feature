# language: de
@spec-07 @browser
Funktionalität: Abschnitte der Anleitung zu Importe, Jahresexport, Verwaltung und Probleme
  Die Anleitung führt die Abschnitte nach Requirement 6.1 (Spec 07, Aufgabe 22.7). Die Reihenfolge und die Bedingungen
  prüft der Vitest hilfe-katalog.spec.ts; Inhalt, Wortwahl und Pflegeregel (6.4, 6.6, 7.5, 7.7) sind Nachweis: Dokument.

  @req-6.1 @req-7.1
  Szenariogrundriss: Jeder Abschnitt aus Bestand, Verwaltung und Problemen kommt mit dem Prod-Build
    Dann liefert das Archiv die Anleitung «<anker>» als Markdown aus dem Build aus

    Beispiele:
      | anker                    |
      | importe                  |
      | jahresexport             |
      | verwaltung               |
      | verwaltung-dokumentarten |
      | verwaltung-protokoll     |
      | verwaltung-api-tokens    |
      | probleme                 |
