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

  @req-11.1 @req-11.8
  Szenario: Die Übersicht bleibt aus normalen Quellen normal, eine interne Quelle stuft sie wirksam hoch
    Wenn eine Übersicht aus einer normalen Quelle freigegeben wird und die Quelle danach intern wird
    Dann ist die gespeicherte Vertraulichkeit der Übersicht zuerst NORMAL und alle Rollen mit Recht A15 sehen sie
    Und speichert die Neuberechnung danach die wirksame Vertraulichkeit INTERN und «mitarbeiter» sieht die Übersicht nicht

  @req-3.1 @req-3.2 @req-3.3 @req-3.4 @req-3.6 @req-3.8 @req-4.4 @req-4.5 @req-14.2 @req-14.4 @req-14.5 @req-14.6
  Szenario: Leseendpunkte filtern Index, Seiten, Versionen, Links und Log nach Sichtbarkeit
    Angenommen synthetische Wissensleseproben sind angelegt
    Wenn «mitarbeiter» Index, Seite, Versionen, Diff, Links und Log abruft
    Dann verraten Zähler, Seiten, Versionen, Links und Log keine unsichtbaren Daten
