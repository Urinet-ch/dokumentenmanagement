# language: de
@spec-06 @benutzer-verwaltung
Funktionalität: Benutzer des eigenen Mandanten verwalten

  @req-3.1 @req-4.8 @req-7.5
  Szenario: Liste und Detail zeigen nur Benutzer des eigenen Mandanten
    Angenommen in zwei Mandanten gibt es je einen synthetischen Benutzer
    Wenn der Inhaber die Benutzerliste und beide Details abruft
    Dann sieht er nur den eigenen Benutzer und fremde Kennungen ergeben 404

  @req-3.2 @req-6.1 @req-6.2 @req-6.3 @req-6.4
  Szenario: Ein neuer Benutzer wird eingeladen und die Anlage protokolliert
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Wenn der Inhaber einen synthetischen Mitarbeiter anlegt
    Dann ist der Mitarbeiter mit Einrichtungspflichten im Mandanten angelegt und protokolliert

  @req-3.6 @req-6.6
  Szenario: Erneute Einladungen sind je Ziel begrenzt
    Angenommen ein synthetischer Mitarbeiter ist eingeladen
    Wenn der Inhaber die Einladung viermal erneut sendet
    Dann sind drei Einladungen erlaubt und die vierte wird begrenzt

  @req-3.13
  Szenario: Ein Zugang wird mit neuer Passwort- und TOTP-Einrichtung zurückgesetzt
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer Mitarbeiter ist eingeladen
    Wenn der Inhaber den Zugang des Mitarbeiters zurücksetzt
    Dann sind Passwort und TOTP erneut einzurichten und der Vorgang ist protokolliert

  @req-4.9
  Szenario: Ein altes Benutzertoken wird nach dem Widerruf abgewiesen
    Angenommen ein synthetischer Verwaltungszugang hat ein altes Token
    Wenn das Token des Inhabers widerrufen wird
    Dann wird dieses Token innerhalb der Frist mit 401 abgewiesen

  @req-3.15
  Szenario: Eine Google-Verknüpfung wird gelöst und Sitzungen beendet
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer Benutzer hat eine Google-Verknüpfung
    Wenn der Inhaber die Google-Verknüpfung löst
    Dann ist die Verknüpfung entfernt und die Sitzungen sind beendet

  @req-3.14 @req-3.18
  Szenario: Eine E-Mail-Änderung verlangt die aktuelle Version und benachrichtigt beide Adressen
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer Mitarbeiter ist eingeladen
    Und die Mitteilung an die bisherige Adresse wird aufgezeichnet
    Wenn der Inhaber die E-Mail-Adresse des Mitarbeiters mit der aktuellen Version ändert
    Dann muss die neue Adresse bestätigt werden und die bisherige Adresse wird benachrichtigt

  @req-3.18 @req-6.2
  Szenario: Eine Namensänderung verlangt die aktuelle Version und protokolliert nur die Feldnamen
    Angenommen ein synthetischer Mitarbeiter ist eingeladen
    Wenn der Inhaber den Namen des Mitarbeiters mit der aktuellen Version ändert
    Dann ist der neue Name gespeichert und nicht im Verwaltungsprotokoll enthalten

  @req-3.8 @req-4.9
  Szenario: Ein aktiver Benutzer wird gesperrt und wieder entsperrt
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter besteht
    Wenn der Inhaber den Mitarbeiter sperrt und wieder entsperrt
    Dann ist der Mitarbeiter wieder aktiv

  @req-3.9 @req-3.10
  Szenario: Ein Austritt wird erfasst und der Zugang später neu eingerichtet
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter besteht
    Wenn der Inhaber den Mitarbeiter deaktiviert und reaktiviert
    Dann ist der Austritt verzeichnet und eine neue Zugangseinrichtung verlangt

  @req-3.4 @req-5.9
  Szenario: Ungültige Angaben und Realm-Konflikte werden ohne Personendaten abgewiesen
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und in zwei Mandanten gibt es je einen synthetischen Benutzer
    Wenn der Inhaber ungültige und bereits vergebene Angaben zur Anlage sendet
    Dann werden Formfehler und Konflikt ohne die fremden Angaben gemeldet

  @req-3.4
  Szenario: Ein Benutzername bleibt nach vollständigem Mandantenabbau gesperrt
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein Benutzername wurde in einem vollständig abgebauten Testmandanten vergeben
    Wenn der Inhaber einen synthetischen Mitarbeiter anlegt
    Dann wird die Wiedervergabe ohne Angabe des früheren Mandanten abgewiesen

  @req-3.5 @req-7.6
  Szenario: Deaktivierte Benutzer zählen zur Höchstzahl
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und der Mandant hat 25 deaktivierte synthetische Benutzer
    Wenn der Inhaber einen synthetischen Mitarbeiter anlegt
    Dann wird die Anlage wegen der Höchstzahl und eine übergrosse Anfrage abgewiesen

  @req-3.7 @req-5.7
  Szenario: Eine eindeutig abgewiesene Einladung wird kompensiert
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und der Identitätsdienst weist den Einladungsversand eindeutig ab
    Wenn der Inhaber einen synthetischen Mitarbeiter anlegt
    Dann wird der Benutzer entfernt und der Fehlerabschluss protokolliert

  @req-7.7
  Szenario: Verwaltungsantworten werden nicht zwischengespeichert und Schreiben verlangt JSON und Anmeldung
    Angenommen ein synthetischer Mitarbeiter ist eingeladen
    Wenn Verwaltungsanfragen mit und ohne Anmeldung und JSON gestellt werden
    Dann tragen die Antworten No-store und unzulässige Schreibanfragen werden abgewiesen

  @req-7.8
  Szenario: Eine Benutzeranlage schreibt keine Personendaten ins Log
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und die synthetischen Verwaltungslogs werden aufgezeichnet
    Wenn der Inhaber einen synthetischen Mitarbeiter anlegt
    Dann enthält das Log weder den Namen noch die E-Mail-Adresse

  @req-4.11
  Szenario: Ein neuer Inhaber bleibt bis zur Zustimmung des zweiten Inhabers ohne Archiv-Rolle
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und im Mandanten gibt es zwei aktive synthetische Inhaber
    Wenn der Inhaber einen weiteren Inhaber anlegt
    Dann ist die Inhaber-Rolle nicht vergeben und ein offener Antrag erfasst
    Wenn der Inhaber die Einladung des neuen Inhabers erneut sendet
    Dann wird die Einladung ohne Rollenvergabe erneut gesendet
