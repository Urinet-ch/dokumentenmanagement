# language: de
@spec-05
Funktionalität: Konfigurationsdatei
  Der Betreiber kann die Mandantenkonfiguration exportieren, prüfen und ohne unbemerkte Überschreibungen einspielen.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-6.1
  Szenario: Der Export enthält die vollständige Konfiguration und ihre Basisversion
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und enthält der Export die aktuelle Basisversion und alle Konfigurationsteile

  @req-6.2
  Szenario: Das Prüfen einer unveränderten Datei verändert die Konfiguration nicht
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» die exportierte Konfigurationsdatei prüft
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsdatei gültig und ohne Änderungen
    Und ist die Konfigurationsversion unverändert

  @req-6.4
  Szenario: Eine Datei mit abweichender Basisversion wird nicht eingespielt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» eine Konfigurationsdatei mit abweichender Basisversion einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 409 und Fehlercode «KONFIGURATION_VERALTET»
    Und ist die Konfigurationsversion unverändert

  @req-6.5
  Szenario: Ein unveränderter Rundlauf mit Kommentarfeld erzeugt keine Version
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» die exportierte Konfigurationsdatei mit Kommentar einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und meldet das Einspielen keine Änderung
    Und ist die Konfigurationsversion unverändert

  @req-6.6
  Szenario: Eine Datei darf die KI-Freigabe nicht ändern
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» eine Konfigurationsdatei mit geänderter KI-Freigabe einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert

  @req-6.3
  Szenario: Eine ungültige Konfiguration wird vor dem Schreiben abgelehnt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» die Konfigurationsdatei mit ungültiger WISSENSQUELLE einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert

  @req-6.7
  Szenario: Die WISSENSQUELLE bleibt beim Einspielen ohne eigene Definition erhalten
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» die Konfigurationsdatei ohne WISSENSQUELLE einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und meldet das Einspielen keine Änderung
    Und ist die Konfigurationsversion unverändert
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die Art «WISSENSQUELLE»

  @req-7.1 @req-7.2 @req-7.6
  Szenario: KI-Texte sind lesbar und Änderungen verlangen die aktuelle Version
    Wenn «inhaber-urinet» die KI-Texte liest
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und enthält der KI-Texte-Endpunkt Rolle, Feldregeln und Freigabestatus
    Wenn «inhaber-urinet» die KI-Texte ohne If-Match ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» die KI-Texte mit der aktuellen Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Wenn «inhaber-urinet» die KI-Texte mit einer veralteten Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Und ist die Konfigurationsversion unverändert
