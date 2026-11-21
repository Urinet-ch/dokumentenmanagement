# language: de
@spec-07 @browser
Funktionalität: Benutzeranleitung als Markdown mit der App
  Die Anleitung liegt als Markdown unter frontend/public/anleitung/, je Abschnitt eine Datei <anker>.md, wird mit der
  App gebaut und unter /anleitung/<anker>.md ausgeliefert (Spec 07, Requirement 7.1). Die übrigen Kriterien der
  Aufgabe 22.1 belegen Vitests (hilfe-katalog.spec.ts, anleitung-markdown.spec.ts, anleitung-inhalt.spec.ts).

  @req-7.1
  Szenariogrundriss: Jede Datei des Hilfe-Katalogs kommt mit dem Prod-Build
    Dann liefert das Archiv die Anleitung «<anker>» als Markdown aus dem Build aus

    Beispiele:
      | anker          |
      | erste-schritte |
      | probleme       |
