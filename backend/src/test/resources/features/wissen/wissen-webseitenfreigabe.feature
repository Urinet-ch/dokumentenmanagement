# language: de
@spec-04
Funktionalität: Datenmodell für die Webseitenfreigabe von Wissensseiten

  @req-21.6
  Szenario: Eine Wissensseite ist standardmässig nicht für die Webseite freigegeben
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann ist die Webseitenfreigabe einer neuen Wissensseite aus und verlangt Akteur und Zeitpunkt

  @req-21.6
  Szenario: Die Freigabe prüft aktuelle direkte und indirekte Quellen
    Angenommen das Wissensdatenmodell ist für den Mandanten «urinet» migriert
    Dann erlaubt die Quellenprüfung nur belegte öffentliche normale Seiten und protokolliert Freigabe und Entzug
