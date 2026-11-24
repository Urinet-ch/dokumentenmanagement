# language: de
@spec-06 @benutzer-verwaltung
Funktionalität: Benutzer des eigenen Mandanten verwalten

  @req-3.1 @req-4.8 @req-4.13 @req-7.5
  Szenario: Liste und Detail zeigen nur Benutzer des eigenen Mandanten
    Angenommen in zwei Mandanten gibt es je einen synthetischen Benutzer
    Wenn der Inhaber die Benutzerliste und beide Details abruft
    Dann sieht er nur den eigenen Benutzer und fremde Kennungen ergeben 404

  @req-4.12 @req-4.8
  Szenario: Rechte-Übersicht zeigt die Matrix nur für Benutzer des eigenen Mandanten
    Angenommen in zwei Mandanten gibt es je einen synthetischen Benutzer
    Wenn der Inhaber die Rechte beider Benutzer abruft
    Dann sieht er die Rechte aus der Matrix nur für den eigenen Benutzer

  @req-4.8 @verwaltung-beispielmandant
  Szenario: Ein unabhängiger Beispielverband verwaltet nur seine eigenen Benutzer
    Angenommen in zwei Mandanten gibt es je einen synthetischen Benutzer
    Dann sieht ein Inhaber des Beispielverbands nur die Benutzer seines Mandanten

  @req-3.18 @req-4.5 @req-4.9
  Szenario: Rollenwechsel verlangt die aktuelle Version und widerruft alte Tokens
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter mit erneuerbarem Token besteht
    Wenn der Inhaber die Rolle mit fehlender, veralteter und aktueller Version ändert
    Dann ist nur die aktuelle Rollenänderung wirksam und ein erneuertes Token trägt die neue Rolle

  @req-4.11
  Szenario: Eine Inhabervergabe benötigt die Bestätigung eines zweiten Inhabers
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter und zwei aktive Inhaber bestehen
    Wenn der Inhaber die Inhaberrolle beantragt und beide Inhaber darüber entscheiden
    Dann kann nur der zweite Inhaber die Vergabe bestätigen

  @req-4.7
  Szenario: Gleichzeitige Rollenwechsel lassen einen aktiven Inhaber zurück
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und zwei aktive Inhaber bestehen
    Wenn beide Inhaber gleichzeitig die Rolle des anderen ändern
    Dann wird ein Vorgang gesperrt und mindestens ein Inhaber bleibt aktiv

  @req-3.2 @req-6.1 @req-6.2 @req-6.3 @req-6.4 @req-9.5
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
    Und ein synthetischer aktiver Mitarbeiter mit erneuerbarem Token besteht
    Wenn der Inhaber den Mitarbeiter sperrt und wieder entsperrt
    Dann ist der Mitarbeiter wieder aktiv und sein altes Token bleibt abgewiesen

  @req-3.9 @req-3.10
  Szenario: Ein Austritt wird erfasst und der Zugang später neu eingerichtet
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter besteht
    Wenn der Inhaber den Mitarbeiter deaktiviert und reaktiviert
    Dann ist der Austritt verzeichnet und eine neue Zugangseinrichtung verlangt

  @req-3.11 @req-3.12 @req-4.8
  Szenario: Ein Austritt darf erst nach der Frist gelöscht werden
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter besteht
    Und der Mitarbeiter ist mit einem Dokument verknüpft und deaktiviert
    Wenn der Inhaber Vorschau und Löschung vor und nach der Frist ausführt
    Dann wird nur nach der Frist gelöscht und die Archivverweise bleiben bestehen

  @req-3.11 @req-7.2
  Szenario: Eine Löschung verlangt eine frische Anmeldung
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter besteht
    Und der Mitarbeiter ist mit einem Dokument verknüpft und deaktiviert
    Wenn der Inhaber nach der Frist ohne frische Anmeldung löscht
    Dann wird die Löschung wegen fehlendem Step-up abgewiesen

  @req-3.11 @req-4.11
  Szenario: Ein Löschantrag braucht die Bestätigung eines zweiten Inhabers
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter und zwei Inhaber bestehen im Test-Keycloak
    Und der Mitarbeiter ist mit einem Dokument verknüpft und deaktiviert
    Wenn zwei Inhaber einen Löschantrag nach der Frist entscheiden
    Dann löscht nur die Bestätigung des zweiten Inhabers den Benutzer

  @req-3.11 @req-4.7 @req-4.11
  Szenario: Ein deaktivierter Inhaber kann gelöscht werden, wenn ein aktiver Inhaber verbleibt
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein aktiver und ein deaktivierter Inhaber bestehen im Test-Keycloak
    Wenn der aktive Inhaber den deaktivierten Inhaber nach der Frist löscht
    Dann ist nur der deaktivierte Inhaber gelöscht

  @req-3.11 @req-4.7 @req-4.11
  Szenario: Zwei aktive Inhaber bestätigen die Löschung eines deaktivierten Inhabers
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und zwei aktive und ein deaktivierter Inhaber bestehen im Test-Keycloak
    Wenn zwei Inhaber einen Löschantrag nach der Frist entscheiden
    Dann löscht nur die Bestätigung des zweiten Inhabers den Benutzer

  @req-3.11 @req-4.6 @req-4.8
  Szenario: Eine Löschung schützt eigene, fremde, unbekannte und aktive Benutzer
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter und zwei Inhaber bestehen im Test-Keycloak
    Wenn der Inhaber unzulässige Löschungen beantragt
    Dann werden die Löschungen abgewiesen und der Mitarbeiter bleibt aktiv

  @req-3.11 @req-4.11
  Szenario: Ein abgelehnter, zurückgezogener oder abgelaufener Löschantrag löscht nicht
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter und zwei Inhaber bestehen im Test-Keycloak
    Und der Mitarbeiter ist mit einem Dokument verknüpft und deaktiviert
    Wenn Löschanträge abgelehnt, zurückgezogen und nach Ablauf bestätigt werden
    Dann wird kein Benutzer durch diese Anträge gelöscht

  @req-3.11 @req-4.7 @req-4.11
  Szenario: Gleichzeitige Bestätigungen führen eine Löschung nur einmal aus
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und ein synthetischer aktiver Mitarbeiter und zwei Inhaber bestehen im Test-Keycloak
    Und der Mitarbeiter ist mit einem Dokument verknüpft und deaktiviert
    Wenn ein Löschantrag gleichzeitig zweimal bestätigt wird
    Dann wird nur eine Bestätigung ausgeführt

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

  @req-7.3 @req-7.9
  Szenario: Verwaltungsrechte werden für jeden Aufruf aus dem Zugang bestimmt
    Angenommen ein Mitarbeiterkonto, ein API-Token und ein Webseiten-Schlüssel bestehen
    Wenn alle drei Zugänge die Benutzerliste abrufen
    Dann werden alle drei mit «NICHT_ERLAUBT» abgewiesen

  @req-7.2
  Szenario: Eine kritische Benutzeranlage verlangt eine frische Anmeldung
    Angenommen die Anmeldung für eine kritische Aktion ist nicht frisch
    Wenn der Inhaber einen synthetischen Mitarbeiter anlegt
    Dann wird die kritische Aktion abgewiesen und kein Benutzer angelegt

  @req-4.11
  Szenario: Ein neuer Inhaber bleibt bis zur Zustimmung des zweiten Inhabers ohne Archiv-Rolle
    Angenommen die frische Anmeldung ist für den Test bestätigt
    Und im Mandanten gibt es zwei aktive synthetische Inhaber
    Wenn der Inhaber einen weiteren Inhaber anlegt
    Dann ist die Inhaber-Rolle nicht vergeben und ein offener Antrag erfasst
    Wenn der Inhaber die Einladung des neuen Inhabers erneut sendet
    Dann wird die Einladung ohne Rollenvergabe erneut gesendet
