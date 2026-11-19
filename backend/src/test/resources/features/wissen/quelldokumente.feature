# language: de
@spec-04 @quellen-aenderung
Funktionalität: Änderungen an Quelldokumenten

  @req-11.3 @req-15.1
  Szenariogrundriss: Eine vertraulichere Quelle entzieht Mitarbeitenden die Wissensseite
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn der Inhaber die Vertraulichkeit des Quelldokuments auf «<stufe>» ändert
    Dann sieht der Mitarbeiter die Wissensseite nicht mehr
    Und der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»

    Beispiele:
      | stufe    |
      | INTERN   |
      | PERSONAL |

  @req-15.2
  Szenario: Eine berechtigte Person bestätigt die Prüfung einer geänderten Quelle
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn der Inhaber die Vertraulichkeit des Quelldokuments auf «INTERN» ändert
    Und der Inhaber die Quellenprüfung der Wissensseite bestätigt
    Dann ist der Quellenhinweis entfernt und die Bestätigung protokolliert

  @req-15.3
  Szenario: Quelleneinträge zeigen den aktuellen Dokumenttitel
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn der Inhaber den Titel des Quelldokuments ändert
    Dann zeigt der Quelleneintrag den neuen Titel
