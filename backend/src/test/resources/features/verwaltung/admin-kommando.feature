# language: de
@spec-06 @admin-kommando
Funktionalität: Betreiberkommandos für Benutzer und Mandanten

  @req-9.1 @admin-kommando
  Szenario: Das Betreiberkommando löst die Einladung des ersten Inhabers aus
    Angenommen der Betreiber hat einen Mandanten ohne Inhaber
    Wenn der Betreiber das Kommando für die erste Inhabereinladung ausführt
    Dann wird die erste Inhabereinladung über das Betreiberkommando ausgelöst
    Und enthält die Terminalausgabe nur die Benutzerkennung

  @req-9.4 @verwaltung-abgleich
  Szenario: Der Abgleich meldet unbekannte Realm-Rollen
    Angenommen ein synthetischer Realm-Benutzer hat eine unbekannte Realm-Rolle
    Wenn der Worker die Benutzerverwaltung abgleicht
    Dann meldet der Abgleich die fremde Rolle

  @req-9.2
  Szenario: Der Abgleich übernimmt einen bestehenden Benutzer, ohne Keycloak zu verändern
    Angenommen ein aktiver Mandant hat einen bestehenden Keycloak-Benutzer ohne Verzeichniszeile
    Wenn der Betreiber den Benutzerabgleich ausführt
    Dann wird die fehlende Zeile als Übernahme eingetragen
    Und bleiben Keycloak-Benutzer und Rolle unverändert

  @req-9.8
  Szenario: Mandantenbenutzer werden bis zum Ablauf der Frist deaktiviert und danach gelöscht
    Angenommen ein deaktivierter Mandant hat einen aktiven Keycloak-Benutzer
    Wenn der Betreiber den Sperrbefehl am 29. Tag ausführt
    Dann ist der Benutzer deaktiviert und seine Sitzung beendet
    Wenn der Betreiber den Sperrbefehl am 30. Tag ausführt
    Dann ist der Keycloak-Benutzer gelöscht
