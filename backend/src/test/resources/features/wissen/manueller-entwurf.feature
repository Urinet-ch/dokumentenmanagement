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

  @req-6.3 @req-6.4 @req-6.5 @req-6.7 @req-14.1
  Szenario: Eine Änderung wird erst nach Korrektur und mit If-Match angenommen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn der Inhaber einen manuellen Wissensentwurf ohne Titel anlegt
    Und der Inhaber diese Änderung ohne If-Match annehmen möchte
    Dann wird die Annahme mit 428 abgelehnt
    Wenn der Inhaber die Änderung mit aktueller Version annehmen möchte
    Dann wird die Annahme mit 422 und dem Befund «FELD_FEHLT» abgelehnt und bleibt offen
    Wenn der Inhaber die Fassung korrigiert und einzeln annimmt
    Dann sind die Seite und ihre Version freigegeben und der Entscheid ist protokolliert

  @req-6.8
  Szenario: Eine erhöhte Mindeststufe kann bei späterer Änderung nicht gesenkt werden
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn der Inhaber einen manuellen Wissensentwurf ohne Titel anlegt
    Und der Inhaber die korrigierte Fassung zuerst als PERSONAL und dann als INTERN annehmen möchte
    Dann wird PERSONAL abgelehnt und die erhöhte Mindeststufe INTERN gespeichert
    Wenn der Inhaber die Seite erneut ändern und auf NORMAL senken möchte
    Dann wird die Senkung abgelehnt und die Mindeststufe bleibt INTERN
