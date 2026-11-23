# language: de
@spec-06 @verwaltung-abgleich
Funktionalität: Abgleich der Benutzerverwaltung und Alarmzustellung

  @req-6.5 @req-6.7
  Szenario: Nicht dokumentierte Inhabervergaben werden je Mandant protokolliert und gemeldet
    Angenommen zwei Mandanten haben je einen synthetischen Inhaber ohne dokumentierte Vergabe
    Wenn der Worker die Benutzerverwaltung abgleicht
    Dann steht jede Abweichung im Protokoll und im Alarm des eigenen Mandanten
    Und die Überwachung meldet jeden Befund einmal an den betroffenen Mandanten
