# language: de
@spec-06 @verwaltung-abgleich
Funktionalität: Abgleich der Benutzerverwaltung und Alarmzustellung

  @req-6.5 @req-6.7
  Szenario: Nicht dokumentierte Inhabervergaben werden je Mandant protokolliert und gemeldet
    Angenommen zwei Mandanten haben je einen synthetischen Inhaber ohne dokumentierte Vergabe
    Wenn der Worker die Benutzerverwaltung abgleicht
    Dann steht jede Abweichung im Protokoll und im Alarm des eigenen Mandanten
    Und die Überwachung meldet jeden Befund einmal an den betroffenen Mandanten

  @req-6.5 @req-6.7
  Szenario: Fehlerhafte Mandanten und mehrere Rollen werden abgeglichen und gemeldet
    Angenommen synthetische Realm-Benutzer haben fehlende oder mehrdeutige Mandanten und mehrere Rollen
    Wenn der Worker die Benutzerverwaltung abgleicht
    Dann stehen die Abweichungen im Protokoll und werden einmal dem zuständigen Mandanten gemeldet
