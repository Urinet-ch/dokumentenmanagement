# language: de
@spec-01
Funktionalität: Anmeldung über Keycloak mit zweitem Faktor und Rollen

  @req-2.1
  Szenario: Ohne Token lehnt die API ab
    Wenn jemand ohne Token «/api/v1/anmeldung-probe» abfragt
    Dann lehnt das Archiv die Anfrage mit Status 401 ab

  @req-2.1
  Szenario: Ohne TOTP stellt Keycloak kein Token aus
    Wenn sich «inhaber-urinet» nur mit dem Passwort anmeldet
    Dann stellt Keycloak kein Token aus

  @req-2.1
  Szenario: Mit Passwort und TOTP ist die Anfrage angemeldet
    Wenn sich «inhaber-urinet» mit Passwort und TOTP anmeldet
    Und mit diesem Token «/api/v1/anmeldung-probe» abfragt
    # angemeldet; den Pfad unter /api/ gibt es nie
    Dann antwortet das Archiv angemeldet mit Status 404

  @req-2.1
  Szenario: Ein Token ohne Audience archiv-api lehnt die API ab
    Wenn sich «mitarbeiter-urinet» über den Client «archiv-test-fremd» mit Passwort und TOTP anmeldet
    Und mit diesem Token «/api/v1/anmeldung-probe» abfragt
    Dann lehnt das Archiv die Anfrage mit Status 401 ab

  # Der importierte Export (kein Token ohne TOTP über irgendeinen Client oder Ablauf) prüft RealmExportKeycloakTest.
  @req-2.1
  Szenario: Der Realm für Stufe A und B verlangt TOTP ohne Ausnahme
    Dann verlangt der Realm-Export im Browser Passwort und TOTP
    Und jeder neue Benutzer muss TOTP einrichten

  @req-2.2
  Szenario: Der Realm kennt die vier Rollen
    Dann kennt der Realm-Export genau die Rollen «inhaber», «mitarbeiter», «treuhaender» und «geschaeftsstelle»

  @req-2.2
  Szenariogrundriss: Das Token trägt Rolle und Mandant des Benutzers
    Wenn sich «<benutzer>» mit Passwort und TOTP anmeldet
    Dann enthält das Token die Rolle «<rolle>» und den Mandanten «<mandant>»

    Beispiele:
      | benutzer                 | rolle            | mandant |
      | inhaber-urinet           | inhaber          | urinet  |
      | mitarbeiter-urinet       | mitarbeiter      | urinet  |
      | treuhaender-urinet       | treuhaender      | urinet  |
      | inhaber-vhf-gsk          | inhaber          | vhf-gsk |
      | geschaeftsstelle-vhf-gsk | geschaeftsstelle | vhf-gsk |

  @req-2.25
  Szenario: Google ist erst nach der ausdrücklichen Freigabe verfügbar
    Dann ist Google im Realm-Export als deaktivierter, nicht vertrauender Provider vorbereitet

  @req-2.26
  Szenario: Der Google-Erstlogin lehnt unbekannte Konten ab
    Dann verweigert der Google-Erstlogin unbekannte Konten

  @req-2.27
  Szenario: Google-Konten verknüpft nur der Administrator
    Dann ist die Google-Verknüpfung nur administrativ möglich

  @req-2.28
  Szenario: Google-Anmeldung verlangt weiterhin TOTP
    Dann verlangt die Google-Anmeldung weiterhin TOTP

  @req-2.29
  Szenario: Das Google-Geheimnis bleibt ein Platzhalter
    Dann bleibt das Google-Clientgeheimnis ein Platzhalter und die Anmeldeereignisse sind aktiviert
