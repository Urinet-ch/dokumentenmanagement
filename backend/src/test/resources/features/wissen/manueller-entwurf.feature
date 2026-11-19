# language: de
@spec-04 @wissen-manuell
Funktionalität: Manuelle Wissensentwürfe

  @req-6.2 @req-6.9 @req-12.2
  Szenario: Ein manueller Entwurf wird geprüft und nur von Berechtigten angelegt
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn der Inhaber einen manuellen Wissensentwurf ohne Titel anlegt
    Dann ist der Entwurf offen und hat den Befund «FELD_FEHLT»
    Wenn der Treuhänder denselben manuellen Wissensentwurf anlegen möchte
    Dann erhält er 403 und es entsteht kein weiterer Wissensentwurf
