# language: de
@spec-04
@wissen-sichtbarkeit
Funktionalität: Sichtbarkeit und Rechte der Wissensbasis

  @req-11.1 @req-11.2 @req-11.3 @req-11.5 @req-12.1
  Szenario: Rekursive Hülle und Versionensichtbarkeit bleiben je Mandant getrennt
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Wenn rekursive Wissenshüllen und Sichtbarkeit in beiden Mandanten geprüft werden
    Dann enthält die Hülle jeder zyklisch verknüpften Seite das Quelldokument
    Und sehen alle vier Rollen die normalen Seiten beider Mandanten
    Und ist eine Version ohne gespeicherte Mindeststufe nur für Rollen mit Recht A3 sichtbar
    Und sieht «mitarbeiter» im Mandanten «vhf-gsk» nach manueller Erhöhung und Neuberechnung die aktuelle Seite nicht
    Und sieht «mitarbeiter» die frühere normale Version weiterhin, bis sich die Sichtbarkeit ihrer Quelle ändert
    Und sehen nach der Änderung der Quelle nur die Rollen mit Recht A3 die internen Seiten und Versionen

  @req-12.1 @req-12.2
  Szenario: Schreibende Aktionen der Wissensbasis bleiben auf Inhaber und Geschäftsstelle beschränkt
    Angenommen die Rollen der Wissensbasis sind geprüft
    Dann ist die Aktion A17 für «treuhaender» verboten
    Und ist die Aktion A19 für «geschaeftsstelle» verboten

  @req-12.3
  Szenario: Eigene Analysevorschläge gewähren nur eingeschränkten Zugriff
    Angenommen die Rollen der Wissensbasis sind geprüft
    Dann dürfen Benutzer ohne Recht A17 nur Status, Entscheid und Grund eigener Analysevorschläge sehen

  @req-11.8
  Szenario: Die Übersicht bleibt normal eingestuft
    Wenn eine Übersicht mit einer internen Quelle neu berechnet wird
    Dann bleibt die gespeicherte Vertraulichkeit der Übersicht NORMAL
