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

  @req-11.8
  Szenario: Eine Änderung, die die Übersicht über NORMAL einstufen würde, erhält einen Befund
    Wenn für die Übersicht eine Fassung mit einer intern eingestuften Quellseite und eine Fassung mit einer normalen Quellseite geprüft werden
    Dann ergibt die Fassung mit der internen Quellseite die wirksame Vertraulichkeit INTERN und den Befund «STUFE_UEBERBLICK»
    Und ergibt die Fassung mit der normalen Quellseite die wirksame Vertraulichkeit NORMAL und keinen Befund

  @req-11.2 @req-11.5
  Szenario: Eine aktuelle Seite mit Quellen, aber ohne gespeicherte Hülle, ist nur für Rollen mit Recht A3 sichtbar
    Wenn eine normale Seite mit einer Quelle ohne gespeicherte Hülle gelesen wird
    Dann sehen nur die Rollen mit Recht A3 die Seite ohne Hülle
