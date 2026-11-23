# language: de
@spec-06 @admin-kommando
Funktionalität: Betreiberkommandos für Benutzer und Mandanten

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
