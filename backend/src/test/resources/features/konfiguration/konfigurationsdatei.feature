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

  @req-1.6
  Szenario: Ein neuer Mandant erhält nach dem Migrations-Snapshot seine Seed-Konfiguration
    Wenn der Betreiber den Mandanten «probe-version» anlegt
    Dann hat der Mandant «probe-version» die erste Version aus der Migration und die letzte aus dem Seed

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

  @req-6.6
  Szenario: Eine Version ohne KI-Texte entzieht beim Einspielen die KI-Freigabe nicht
    Angenommen die letzte Konfigurationsversion von «urinet» enthält keine KI-Texte
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann ist die KI im Export freigegeben
    Wenn in der Datei die Art «TEST_DATEI_KI» ergänzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Und nennt der letzte Eintrag «KONFIGURATION_AENDERN» im Protokoll von «urinet» den Text «arten.TEST_DATEI_KI»
    Und nennt der letzte Eintrag «KONFIGURATION_AENDERN» im Protokoll von «urinet» den Text «kiTexte» nicht
    Wenn «inhaber-urinet» die KI-Texte liest
    Dann ist die KI laut KI-Texte-Endpunkt freigegeben

  @req-1.3 @req-7.6
  Szenario: Eine Änderung der KI-Texte wird nach einem alten Snapshot versioniert
    Angenommen die letzte Konfigurationsversion von «urinet» enthält keine KI-Texte
    Wenn «inhaber-urinet» die KI-Texte liest
    Und «inhaber-urinet» die KI-Texte mit der aktuellen Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Und nennt der letzte Eintrag «KONFIGURATION_AENDERN» im Protokoll von «urinet» den Text «kiTexte.rolle»

  @req-1.3 @req-6.1
  Szenario: Eine Dateiänderung der KI-Texte wird nach einem alten Snapshot versioniert
    Angenommen die letzte Konfigurationsversion von «urinet» enthält keine KI-Texte
    Wenn «inhaber-urinet» die KI-Texte liest
    Und «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei der KI-Rollentext geändert wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Und nennt der letzte Eintrag «KONFIGURATION_AENDERN» im Protokoll von «urinet» den Text «kiTexte.rolle»

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
    Und ist die KI laut KI-Texte-Endpunkt freigegeben
    Wenn «inhaber-vhf-gsk» die KI-Texte liest
    Dann ist die KI laut KI-Texte-Endpunkt gesperrt
    Wenn «inhaber-urinet» die KI-Texte ohne If-Match ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» die KI-Texte mit der aktuellen Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Wenn «inhaber-urinet» die KI-Texte mit einer veralteten Version ändert
    Dann antwortet die Konfigurationsverwaltung mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Und ist die Konfigurationsversion unverändert

  @req-6.3 @req-1.2 @req-1.3
  Szenario: Eine neue Art aus der Datei erzeugt eine Version mit Quelle DATEI und fehlt nach dem Weglassen wieder
    Angenommen «inhaber-vhf-gsk» merkt sich die Konfigurationsversion
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_NEU» ergänzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Und hat die Konfigurationsversion die Quelle «DATEI»
    Und nennt der letzte Eintrag «KONFIGURATION_AENDERN» im Protokoll von «urinet» den Text «arten.TEST_DATEI_NEU»
    Und ist die Konfigurationsversion von «inhaber-vhf-gsk» unverändert
    Wenn «inhaber-vhf-gsk» die Konfigurationsdatei exportiert
    Dann enthält der Export die Art «TEST_DATEI_NEU» nicht
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_NEU» fehlt
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die Art «TEST_DATEI_NEU» nicht

  @req-6.3
  Szenario: Art und Klassifikation mit Dokumenten werden beim Weglassen deaktiviert statt gelöscht
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_BESTAND» ergänzt wird
    Und in der Datei die Art «TEST_DATEI_ZWEITE» ergänzt wird
    Und in der Datei die mehrfache Klassifikation «test_datei» mit den Werten «a» und «b» ergänzt wird
    Und in der Datei die Art «TEST_DATEI_ZWEITE» der Klassifikation «test_datei» als Pflicht zugeordnet ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Angenommen «inhaber-urinet» legt ein Dokument der Art «TEST_DATEI_BESTAND» mit den Werten «a» und «b» der Klassifikation «test_datei» an
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_BESTAND» fehlt
    Und in der Datei die Klassifikation «test_datei» fehlt
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und nennt das Ergebnis die Änderung "arten.TEST_DATEI_BESTAND.aktiv" mit 1 betroffenen Dokument
    Und nennt das Ergebnis die Änderung "klassifikationen.test_datei.aktiv" mit 1 betroffenen Dokument
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann ist im Export die Art «TEST_DATEI_BESTAND» inaktiv
    Und ist im Export die Klassifikation «test_datei» inaktiv
    Und ist im Export die Klassifikation «test_datei» der Art «TEST_DATEI_ZWEITE» weder Pflicht noch vorbelegt

  @req-6.3
  Szenario: Eine mehrfache Klassifikation mit mehreren Werten an einem Dokument wird nicht einfach
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_MEHRFACH» ergänzt wird
    Und in der Datei die mehrfache Klassifikation «test_datei» mit den Werten «a» und «b» ergänzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Angenommen «inhaber-urinet» legt ein Dokument der Art «TEST_DATEI_MEHRFACH» mit den Werten «a» und «b» der Klassifikation «test_datei» an
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Klassifikation «test_datei» einfach ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei prüft
    Dann ist die Konfigurationsdatei ungültig
    Und nennt die Antwort den Befund "klassifikationen.test_datei.mehrfach" mit dem Code «MEHRFACH_IN_GEBRAUCH»
    Wenn «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und nennt die Antwort den Befund "klassifikationen.test_datei.mehrfach" mit dem Code «MEHRFACH_IN_GEBRAUCH»
    Und ist die Konfigurationsversion unverändert

  @req-6.3
  Szenario: Eine Art der Datei darf keine fehlende oder inaktive Klassifikation als Pflicht zuordnen
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_ZUORDNUNG» ergänzt wird
    Und in der Datei die Art «TEST_DATEI_ZUORDNUNG» der Klassifikation «test_unbekannt» als Pflicht zugeordnet ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und nennt die Antwort den Befund "arten.TEST_DATEI_ZUORDNUNG.klassifikationen" mit dem Code «KLASSIFIKATION_UNGUELTIG»
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_ZUORDNUNG» ergänzt wird
    Und in der Datei die mehrfache Klassifikation «test_datei» mit den Werten «a» und «b» ergänzt wird
    Und in der Datei die Klassifikation «test_datei» inaktiv ist
    Und in der Datei die Art «TEST_DATEI_ZUORDNUNG» der Klassifikation «test_datei» als Pflicht zugeordnet ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und nennt die Antwort den Befund "arten.TEST_DATEI_ZUORDNUNG.klassifikationen.test_datei" mit dem Code «KLASSIFIKATION_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert

  @req-6.3
  Szenario: Ein Standardwert wechselt auf einen anderen Wert, während sein bisheriger Wert wegfällt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_STANDARD» ergänzt wird
    Und in der Datei die mehrfache Klassifikation «test_datei» mit den Werten «a» und «b» ergänzt wird
    Und in der Datei die Art «TEST_DATEI_STANDARD» der Klassifikation «test_datei» mit dem Standardwert «a» zugeordnet ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei der Wert «a» der Klassifikation «test_datei» fehlt
    Und in der Datei die Art «TEST_DATEI_STANDARD» der Klassifikation «test_datei» mit dem Standardwert «b» zugeordnet ist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann ist im Export «b» der Standardwert der Klassifikation «test_datei» der Art «TEST_DATEI_STANDARD»

  @req-6.3
  Szenario: Zwei neue Arten dürfen aufeinander verweisen und zusammen wegfallen
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_ERSTE» ergänzt wird
    Und in der Datei die Art «TEST_DATEI_ANDERE» ergänzt wird
    Und in der Datei die Art «TEST_DATEI_ERSTE» als Fremdbeleg auf «TEST_DATEI_ANDERE» verweist
    Und in der Datei die Art «TEST_DATEI_ANDERE» als Fremdbeleg auf «TEST_DATEI_ERSTE» verweist
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_ERSTE» fehlt
    Und in der Datei die Art «TEST_DATEI_ANDERE» fehlt
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Dann enthält der Export die Art «TEST_DATEI_ERSTE» nicht

  @req-6.3
  Szenario: Eine geänderte Aufbewahrung aus der Datei steht als Regeländerung im Protokoll
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Art «TEST_DATEI_FRIST» ergänzt wird
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die Aufbewahrung der Art «TEST_DATEI_FRIST» 5 Jahre beträgt
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und nennt der letzte Eintrag «REGEL_AENDERN» im Protokoll von «urinet» den Text «TEST_DATEI_FRIST»

  @req-6.3
  Szenario: Ein ungültiges Geschäftsjahresende wird abgelehnt
    Angenommen für die Konfigurationsdatei ist eine frische Anmeldung bestätigt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei das Geschäftsjahresende «02-30» steht
    Und «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 409 und Fehlercode «BESTAETIGUNG_NOETIG»
    Wenn «inhaber-urinet» die geänderte Konfigurationsdatei mit Bestätigung "geschaeftsjahrEnde" einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und nennt die Antwort den Befund "einstellungen.geschaeftsjahrEnde" mit dem Code «DATUM_UNGUELTIG»
    Und ist die Konfigurationsversion unverändert

  @req-6.4 @req-7.6
  Szenario: Eine exportierte Datei ist nach einer Änderung in der App veraltet, auch ohne If-Match
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und «inhaber-urinet» eine Dokumentart mit der aktuellen Version anlegt:
      """
      {"code":"TEST_DATEI_APP","name":"Test App","vertraulichkeit":"NORMAL","aufbewahrungJahre":null,"gesetzlichePflicht":false}
      """
    Dann antwortet die Konfigurationsverwaltung mit Status 201
    Wenn «inhaber-urinet» die exportierte Konfigurationsdatei ohne If-Match einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 409 und Fehlercode «KONFIGURATION_VERALTET»

  @req-6.6
  Szenario: Die Texte im KI-Prompt dürfen zusammen höchstens 24'000 Zeichen lang sein
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei die KI-Texte und die Klassifikation «test_ki_lang» mit KI-Vorschlag zusammen über 24'000 Zeichen lang sind
    Und «inhaber-urinet» die geänderte Konfigurationsdatei prüft
    Dann ist die Konfigurationsdatei ungültig
    Und nennt die Antwort den Befund "kiTexte" mit dem Code «TEXTE_ZU_LANG»
    Wenn «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und nennt die Antwort den Befund "kiTexte" mit dem Code «TEXTE_ZU_LANG»
    Und ist die Konfigurationsversion unverändert

  @req-6.2 @req-6.6
  Szenario: Ein unbekanntes Feld wird mit Pfad gemeldet und abgelehnt
    Wenn «inhaber-urinet» die Konfigurationsdatei exportiert
    Und in der Datei das Feld «mandant» mit dem Wert «vhf-gsk» steht
    Und «inhaber-urinet» die geänderte Konfigurationsdatei prüft
    Dann ist die Konfigurationsdatei ungültig
    Und nennt die Antwort den Befund "/mandant" mit dem Code «additionalProperties»
    Wenn «inhaber-urinet» die geänderte Konfigurationsdatei einspielt
    Dann antwortet die Konfigurationsverwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»
    Und nennt die Antwort den Befund "/mandant" mit dem Code «additionalProperties»
    Und ist die Konfigurationsversion unverändert

  @req-7.1
  Szenariogrundriss: Ohne Recht A11 bleibt die Konfigurationsdatei gesperrt
    Wenn «<benutzer>» die Konfigurationsverwaltung mit <methode> "<pfad>" aufruft
    Dann antwortet die Konfigurationsverwaltung mit Status 403

    Beispiele:
      | benutzer           | methode | pfad          |
      | mitarbeiter-urinet | GET     |               |
      | mitarbeiter-urinet | PUT     |               |
      | mitarbeiter-urinet | POST    | /pruefen      |
      | mitarbeiter-urinet | PATCH   | /ki-texte     |
      | treuhaender-urinet | GET     |               |
      | treuhaender-urinet | PUT     |               |
      | treuhaender-urinet | POST    | /pruefen      |
      | treuhaender-urinet | PATCH   | /ki-texte     |
