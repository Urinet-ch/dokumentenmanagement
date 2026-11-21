# language: de
@spec-04
@wissen-sichtbarkeit
Funktionalität: Sichtbarkeit und Rechte der Wissensbasis

  @req-11.1 @req-11.2 @req-11.3 @req-11.5 @req-12.1
  Szenario: Rekursive Hülle und Versionensichtbarkeit bleiben je Mandant getrennt
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn rekursive Wissenshüllen und Sichtbarkeit in beiden Mandanten bei gleichzeitigen Daten des anderen Mandanten geprüft werden
    Dann enthält die Hülle jeder zyklisch verknüpften Seite das Quelldokument
    Und wirken Hülle, Vertraulichkeit und Seiten des anderen Mandanten nicht im eigenen Mandanten
    Und sehen alle vier Rollen die normalen Seiten beider Mandanten
    Und ist eine Version ohne gespeicherte Mindeststufe nur für Rollen mit Recht A3 sichtbar
    Und ist eine Version ohne gespeicherte Hülle nur für Rollen mit Recht A3 sichtbar
    Und sieht «mitarbeiter» im Mandanten «vhf-gsk» nach manueller Erhöhung und Neuberechnung die aktuelle Seite nicht
    Und sieht «mitarbeiter» dann auch die frühere normale Version der Seite nicht mehr
    Und sehen nach der Änderung der Quelle nur die Rollen mit Recht A3 die internen Seiten und Versionen

  @req-12.1
  Szenariogrundriss: API-Tokens erreichen die Wissensbasis nicht
    Angenommen für «urinet» gibt es das Token «<scope>» mit Scope «<scope>»
    Wenn das Token «<scope>» <methode> «<pfad>» mit einem leeren JSON-Objekt aufruft
    Dann antwortet das Archiv auf die Token-Anfrage mit Status 403 und Fehlercode «NICHT_ERLAUBT»

    Beispiele:
      | scope     | methode | pfad                                                                        |
      | generator | GET     | /api/v1/wissensseiten                                                       |
      | import    | GET     | /api/v1/wissensseiten/00000000-0000-4000-8000-000000000001                  |
      | generator | GET     | /api/v1/wissensbasis/log                                                    |
      | import    | GET     | /api/v1/wissensseiten/00000000-0000-4000-8000-000000000001/diff?von=1&bis=2 |

  @req-12.1
  Szenariogrundriss: Webseiten-Schlüssel erreichen die Wissensbasis nicht
    Wenn «<schluessel>» GET «<pfad>» aufruft
    Dann antwortet die Webseiten-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

    Beispiele:
      | schluessel         | pfad                     |
      | webseite           | /api/v1/wissensseiten    |
      | webseite-erfassung | /api/v1/wissensbasis/log |

  @req-12.1 @req-12.2
  Szenario: Schreibende Aktionen der Wissensbasis bleiben auf Inhaber und Geschäftsstelle beschränkt
    Angenommen die Rollen der Wissensbasis sind geprüft
    Dann ist die Aktion A17 für «treuhaender» verboten
    Und ist die Aktion A19 für «geschaeftsstelle» verboten

  @req-12.3
  Szenario: Eigene Analysevorschläge gewähren nur eingeschränkten Zugriff
    Angenommen die Rollen der Wissensbasis sind geprüft
    Dann dürfen Benutzer ohne Recht A17 nur Status, Entscheid und Grund eigener Analysevorschläge sehen

  @req-11.1 @req-11.9
  Szenario: Die Übersicht bleibt aus normalen Quellen normal, eine interne Quelle stuft sie wirksam hoch
    Wenn eine Übersicht aus einer normalen Quelle freigegeben wird und die Quelle danach intern wird
    Dann ist die gespeicherte Vertraulichkeit der Übersicht zuerst NORMAL und alle Rollen mit Recht A15 sehen sie
    Und speichert die Neuberechnung danach die wirksame Vertraulichkeit INTERN und «mitarbeiter» sieht die Übersicht nicht

  @req-11.10
  Szenario: Eine zitierte interne Mindeststufe bleibt über die Quellkette erhalten
    Wenn eine dreiseitige Zitierkette mit einer internen Mindeststufe freigegeben und die Quelle herabgestuft wird
    Dann bleibt die Mindeststufe der zitierenden Seite INTERN
    Und sehen nur Rollen mit Recht A3 die zitierende Seite im Index, in der Einzelansicht und in der Version

  @req-11.8
  Szenario: Eine Änderung, die die Übersicht über NORMAL einstufen würde, erhält einen Befund
    Wenn für die Übersicht eine Fassung mit einer intern eingestuften Quellseite und eine Fassung mit einer normalen Quellseite geprüft werden
    Dann ergibt die Fassung mit der internen Quellseite die wirksame Vertraulichkeit INTERN und den Befund «STUFE_UEBERBLICK»
    Und ergibt die Fassung mit der normalen Quellseite die wirksame Vertraulichkeit NORMAL und keinen Befund

  @req-11.2 @req-11.5
  Szenario: Eine aktuelle Seite mit Quellen, aber ohne gespeicherte Hülle, ist nur für Rollen mit Recht A3 sichtbar
    Wenn eine normale Seite mit einer Quelle ohne gespeicherte Hülle gelesen wird
    Dann sehen nur die Rollen mit Recht A3 die Seite ohne Hülle

  @req-11.2 @req-11.5
  Szenario: Neue Quellen ausserhalb der gespeicherten Hülle werden ohne Neuberechnung nach dem Live-Stand geprüft
    Wenn nach der Freigabe neue Quellen ausserhalb der gespeicherten Hülle ohne Neuberechnung eingetragen werden
    Dann sehen vor der Änderung alle Rollen mit Recht A15 die Seiten
    Und sieht «mitarbeiter» die Seite mit einem neuen normalen Dokument danach nicht, einzeln und in der Liste
    Und sieht «mitarbeiter» die Seite nicht, deren zitierte Quellseite ein neues Dokument erhalten hat
    Und sieht «treuhaender» die Seite mit einem neuen unbestätigten Dokument nicht, «inhaber» und «geschaeftsstelle» sehen sie

  @req-11.2
  Szenario: Eine Seite mit gespeicherter wirksamer Vertraulichkeit INTERN ist nur für Rollen mit Recht A3 sichtbar
    Wenn eine Seite mit Mindeststufe NORMAL, normaler Hülle und gespeicherter Vertraulichkeit INTERN gelesen wird
    Dann sehen nur die Rollen mit Recht A3 die intern gespeicherte Seite, einzeln und in der Liste

  @req-3.1 @req-3.2 @req-3.3 @req-3.4 @req-3.6 @req-3.8 @req-4.4 @req-4.5 @req-14.2 @req-14.4 @req-14.5 @req-14.6
  Szenario: Leseendpunkte filtern Index, Seiten, Versionen, Links und Log nach Sichtbarkeit
    Angenommen synthetische Wissensleseproben sind angelegt
    Wenn «mitarbeiter» Index, Seite, Versionen, Diff, Links und Log abruft
    Dann verraten Zähler, Seiten, Versionen, Links und Log keine unsichtbaren Daten

  @req-15.2 @req-15.4
  Szenario: Dokumentfilter und Prüfhinweis zeigen nur sichtbare Wissensseiten
    Angenommen synthetische Wissensleseproben sind angelegt
    Wenn die Wissensseiten nach einem Quelldokument gefiltert werden
    Dann zeigt der Index nur sichtbare Seiten des eigenen Mandanten und deren Prüfhinweis

  @req-8.2 @req-8.3 @req-8.6 @req-11.4 @wissen-chat-probe
  Szenario: Chat-Werkzeuge liefern nur freigegebene sichtbare Wissensseiten
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und synthetische Wissensleseproben sind angelegt
    Wenn der Mitarbeiter die Wissenswerkzeuge mit den Leseproben aufruft
    Dann liefern die Wissenswerkzeuge nur sichtbare freigegebene Seiten und verdecken unsichtbare Links

  @req-8.1 @req-8.4 @req-8.5 @req-8.7 @wissen-chat-probe
  Szenario: Wissensquellen gelangen über den Agenten in Stream und Verlauf
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und synthetische Wissensleseproben sind angelegt
    Und der Chat nutzt den Agenten mit dem simulierten Sprachmodell
    Und das simulierte Sprachmodell ruft Index und sichtbare Wissensseite der Leseprobe auf
    Wenn «mitarbeiter-urinet» dem Agenten die Frage «Welche Regel gilt?» stellt
    Dann zeigt der Stream des Agenten 2 Werkzeugaufrufe
    Und enthalten Stream und Verlauf die Wissensseite mit ihrem direkten Quelldokument
    Und verlangt der Systemprompt der ersten Anfrage «wissen_index | wissen_seite_zeigen | VERALTET | WIDERSPRUCH»
