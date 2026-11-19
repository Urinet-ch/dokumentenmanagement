# language: de
@spec-04 @quellen-aenderung
Funktionalität: Änderungen an Quelldokumenten

  @req-11.3 @req-15.1
  Szenario: Eine interne Quelle entzieht Mitarbeitenden die Wissensseite
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine normale Wissensseite verweist auf ein normales Quelldokument
    Wenn der Inhaber die Vertraulichkeit des Quelldokuments auf INTERN ändert
    Dann sieht der Mitarbeiter die Wissensseite nicht mehr
    Und der Inhaber sieht auf der unveränderten Wissensseite den Hinweis «Quelle prüfen»
