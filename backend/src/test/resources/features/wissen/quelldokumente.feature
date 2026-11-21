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

  @req-15.2 @quellen-freigabe
  Szenario: Die Freigabe einer Seitenänderung entfernt den Quellenhinweis
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn der Inhaber den Titel des Quelldokuments ändert
    Dann der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»
    Wenn der Inhaber eine Änderung dieser Wissensseite freigibt
    Dann ist der Quellenhinweis durch die Freigabe entfernt

  @req-1.4
  Szenario: Die Freigabe einer Seitenänderung verändert das Quelldokument nicht
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Und das Quelldokument hat Volltext und eine PDF-A-Fassung
    Und der Zustand des Quelldokuments mit Dokumentzeile und Dateizeilen ist festgehalten
    Wenn der Inhaber eine Änderung dieser Wissensseite freigibt
    Dann sind Dokumentzeile und Dateizeilen des Quelldokuments unverändert

  @req-15.3
  Szenario: Quelleneinträge zeigen den aktuellen Dokumenttitel
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn der Inhaber den Titel des Quelldokuments ändert
    Dann zeigt der Quelleneintrag den neuen Titel

  @req-15.5
  Szenario: Ein simuliertes Löschereignis kennzeichnet die betroffene Wissensseite
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn das Dokumentereignis vor einer späteren Löschung simuliert wird
    Dann der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»

  @req-11.3
  Szenario: Die Neuberechnung nach einer Quelländerung fällt nicht unter die Mindeststufe der Seite
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Und die Wissensseite hat die Mindeststufe «INTERN»
    Wenn der Inhaber den Titel des Quelldokuments ändert
    Dann sieht der Mitarbeiter die Wissensseite nicht mehr
    Und der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»

  @req-15.1
  Szenariogrundriss: Statuswechsel und Artänderung des Quelldokuments lösen «Quelle prüfen» aus
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein Quelldokument im Status «<status>»
    Wenn der Inhaber am Quelldokument die Änderung «<aenderung>» auslöst
    Dann der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»

    Beispiele:
      | status     | aenderung  |
      | FEHLER     | VERWERFEN  |
      | FEHLER     | WIEDERHOLEN |
      | ARCHIVIERT | ART        |
