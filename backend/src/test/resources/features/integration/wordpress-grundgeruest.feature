# language: de
@spec-03 @wordpress
Funktionalität: WordPress-Grundgerüst
  Die Webseite verwendet das Archiv nur serverseitig und trennt ihre Ergebnisse nach Zugriffsstufe.

  @req-7.1
  Szenario: Beide Webseiten-Schlüssel bleiben auf dem Server
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn die Webseiten-Schlüssel und die Rolle der Geschäftsstelle eingestellt werden
    Dann sind die Schlüssel verschlüsselt gespeichert und auf der Einstellungsseite nicht sichtbar

  @req-7.1
  Szenario: Die Einstellungsanfrage ohne Content-Length bleibt begrenzt
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn die Einstellungsdaten ohne Längenangabe als Eingabestrom eintreffen
    Dann prüft das Plugin die Grösse und das feste Schema der Anfrage

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

  @req-7.9
  Szenario: Der Änderungs-Feed invalidiert alte Dokumente sofort
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn ein Dokument im Lese-Feed zurückgezogen wird oder der Feed ausfällt
    Dann gibt das Plugin keinen veralteten Cache aus und bricht bei Feed-Ausfall geschlossen ab

  @req-5.8 @req-6.2 @req-7.2 @req-7.6
  Szenario: Die Liste filtert nach öffentlichen Werten und zeigt Chatquellen
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn Besucher die Archivliste und den Chat öffnen
    Dann zeigt das Plugin dynamische Filter, Seitennavigation und lesbare Chatquellen

  @req-7.8
  Szenario: Download-Sperren laufen ab und grosse Streams melden ihre Länge
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn Download-Sperren ausfallen und ein grosser Download gestreamt wird
    Dann prüft das Plugin ablaufende Sperren und die Länge eines Teil-Streams

  @req-7.10
  Szenario: Das Erfassungsformular folgt dem Art-Schema
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn die Geschäftsstelle die Erfassungsseite öffnet
    Dann zeigt das Plugin Schema-Felder und sperrt die öffentliche Zugriffsstufe

  @req-7.7
  Szenario: Das Plugin funktioniert mit unterstütztem WordPress und PHP
    Angenommen ein WordPress mit dem Archiv-Plugin
    Wenn das Plugin mit der unterstützten PHP-Version und WordPress geprüft wird
    Dann bestehen die Plugin-Tests und die WordPress-Coding-Standards-Prüfung
