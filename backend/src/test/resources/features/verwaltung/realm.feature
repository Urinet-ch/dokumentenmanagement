# language: de
@spec-06
Funktionalität: Realm-Konfiguration für die Benutzerverwaltung
  Teilprüfungen zu 4.9 und 7.4: Die Szenarien weisen weder den Token-Widerruf noch den Einladungsablauf nach.
  Die weiteren Wirkungen der Benutzerverwaltung folgen in den Aufgaben 19.7 bis 19.10.

  Szenario: Der Realm begrenzt die Lebensdauer von Access-Tokens auf fünf Minuten
    Dann beträgt die Access-Token-Lebensdauer des Realm-Exports 300 Sekunden

  Szenario: Der Realm schützt neue Passwörter und verlangt TOTP
    Dann verlangt der Realm-Export Passwörter mit mindestens 12 Zeichen ohne Benutzername oder E-Mail
    Und jeder neue Benutzer muss TOTP einrichten
