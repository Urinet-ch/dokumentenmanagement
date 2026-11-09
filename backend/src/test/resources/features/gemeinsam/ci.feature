# language: de
@spec-01
Funktionalität: CI und Nachweise

  @req-16.7
  Szenario: Die CI verarbeitet keine echten Daten und ruft keine echte KI auf
    Dann verwendet kein Workflow unter «.github/workflows» ein Secret oder einen Golden-Lauf
    Und im Profil «test» zeigt «archiv.ki.basis-url» auf «localhost»
    Und Requests und Antworten des Sprachmodells werden nicht geloggt

  @req-16.14
  Szenario: Jedes Kriterium abgehakter Aufgaben ab Welle 1 ist abgedeckt
    Dann hat jedes Kriterium abgehakter Aufgaben ab Welle 1 ein Szenario «@req-x.y» oder einen Nachweis-Vermerk
