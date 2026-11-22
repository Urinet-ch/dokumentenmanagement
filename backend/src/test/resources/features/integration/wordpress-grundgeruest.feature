# language: de
@spec-03 @wordpress
Funktionalität: WordPress-Grundgerüst
  Die Webseite verwendet das Archiv nur serverseitig und trennt ihre Ergebnisse nach Zugriffsstufe.

  @req-7.1
  Szenario: Beide Webseiten-Schlüssel bleiben auf dem Server
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn die Webseiten-Schlüssel und die Rolle der Geschäftsstelle eingestellt werden
    Dann sind die Schlüssel verschlüsselt gespeichert und auf der Einstellungsseite nicht sichtbar

  @req-7.3 @req-7.4
  Szenario: Die höchste WordPress-Rolle bestimmt den getrennten Cache
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn Gäste, Mitglieder und Personen mit beiden Rollen Dokumente abrufen
    Dann meldet das Plugin die jeweilige höchste Stufe und trennt die Cache-Einträge

  @req-7.5
  Szenario: Ein nicht erreichbares Archiv bricht die Webseite nicht ab
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn das Archiv nicht erreichbar ist
    Dann liefert das Plugin eine freundliche Meldung ohne technische Einzelheiten

  @req-7.7
  Szenario: Das Plugin funktioniert mit unterstütztem WordPress und PHP
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn das Plugin mit der unterstützten PHP-Version und WordPress geprüft wird
    Dann bestehen die Plugin-Tests und die WordPress-Coding-Standards-Prüfung
