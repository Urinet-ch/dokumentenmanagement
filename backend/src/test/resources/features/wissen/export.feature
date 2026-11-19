# language: de
@spec-04
@wissen-export
Funktionalität: Export der Wissensbasis

  @req-17.1
  Szenario: Eine aktuelle Wissensseite wird im OKF-Format exportiert
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine synthetische freigegebene Wissensseite ist vorhanden
    Wenn der Inhaber einen Wissensexport anfordert und ausführen lässt
    Dann enthält das ZIP die Seite mit OKF-Frontmatter sowie Index und Log

  @req-17.2
  Szenario: Quellen und interne Links werden als Verweise exportiert
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und eine synthetische Wissensseite verweist auf ein Dokument und eine andere Seite
    Wenn der Inhaber einen Wissensexport anfordert und ausführen lässt
    Dann enthält das ZIP URN und Seitenpfad statt Dokumentdateien und schreibt den Markdown-Link um

  @req-17.4
  Szenario: Ein fertiger Wissensexport kann 24 Stunden lang heruntergeladen werden
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn der Inhaber einen Wissensexport anfordert und ausführen lässt
    Dann ist der Download 24 Stunden ab Fertigstellung gültig und seine Prüfsumme protokolliert
