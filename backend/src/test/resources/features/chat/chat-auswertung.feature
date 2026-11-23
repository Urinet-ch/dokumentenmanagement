# language: de
@spec-02
Funktionalität: Chat-Bewertungen auswerten und exportieren
  Der Inhaber wertet nur die Bewertungen des eigenen Mandanten aus und exportiert sie als geschwärztes ZIP.
  Alle Daten in diesen Szenarien sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-15.5
  Szenario: Auswertung ist auf den eigenen Mandanten und den Inhaber begrenzt
    Angenommen im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 2 Sternen
    Und im Mandanten «vhf-gsk» gibt es eine synthetische Chat-Bewertung mit 5 Sternen
    Wenn «inhaber-urinet» liest die Chat-Auswertung für die letzten 30 Tage
    Dann ist die Chat-Auswertung mit Status 200 beantwortet
    Und enthält die Auswertung 1 Bewertungen
    Wenn «mitarbeiter-urinet» liest die Chat-Auswertung für die letzten 30 Tage
    Dann ist die Chat-Auswertung mit Status 403 beantwortet
    Wenn «treuhaender-urinet» liest die Chat-Auswertung für die letzten 30 Tage
    Dann ist die Chat-Auswertung mit Status 403 beantwortet
    Wenn «geschaeftsstelle-vhf-gsk» liest die Chat-Auswertung für die letzten 30 Tage
    Dann ist die Chat-Auswertung mit Status 403 beantwortet

  @req-15.6
  @req-15.8
  Szenario: Inhaber exportiert ein ZIP nach bestätigter frischer Anmeldung
    Angenommen im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 4 Sternen
    Wenn «inhaber-urinet» exportiert die Chat-Auswertung für die letzten 30 Tage
    Dann ist der Chat-Auswertungs-Export mit Status 200 beantwortet
    Und enthält der Chat-Auswertungs-Export die Dateien «bewertungen.jsonl», «schema.json» und «README.txt»
    Und enthält der Chat-Auswertungs-Export keine Benutzer- oder Mandantenkennung
    Und ist der Export ohne Inhalte protokolliert

  @req-15.6
  Szenario: Export wird ohne frische Anmeldung abgelehnt
    Angenommen im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 4 Sternen
    Wenn «inhaber-urinet» exportiert die Chat-Auswertung ohne frische Anmeldung
    Dann ist der Chat-Auswertungs-Export mit Status 401 beantwortet
    Und ist der Fehlercode der Chat-Auswertung «STEP_UP_NOETIG»

  @req-15.5
  Szenario: Ein API-Token ohne Inhaberrolle kann die Chat-Auswertung nicht lesen oder exportieren
    Angenommen für die Chat-Auswertung gibt es einen API-Token ohne Inhaberrolle
    Wenn der API-Token die Chat-Auswertung liest
    Dann ist die Chat-Auswertung mit Status 403 beantwortet
    Wenn der API-Token die Chat-Auswertung exportiert
    Dann ist der Chat-Auswertungs-Export mit Status 403 beantwortet

  @req-15.5
  Szenario: Ein Webseiten-Schlüssel ohne Inhaberrolle kann die Chat-Auswertung nicht lesen oder exportieren
    Angenommen für die Chat-Auswertung gibt es einen Webseiten-Schlüssel ohne Inhaberrolle
    Wenn der Webseiten-Schlüssel die Chat-Auswertung liest
    Dann ist die Chat-Auswertung mit Status 403 beantwortet
    Wenn der Webseiten-Schlüssel die Chat-Auswertung exportiert
    Dann ist der Chat-Auswertungs-Export mit Status 403 beantwortet

  @req-15.6
  Szenario: Export mit mehr als 5000 Bewertungen wird vor dem Download abgelehnt
    Angenommen im Mandanten «urinet» gibt es 5001 synthetische Chat-Bewertungen
    Wenn «inhaber-urinet» exportiert die Chat-Auswertung für die letzten 30 Tage
    Dann ist der Chat-Auswertungs-Export mit Status 413 beantwortet
    Und ist der Fehlercode der Chat-Auswertung «BEWERTUNG_EXPORT_ZU_GROSS»

  @req-15.7
  Szenario: Der Export schwärzt standardmässig bekannte Personendaten
    Angenommen im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 2 Sternen
    Wenn «inhaber-urinet» exportiert die Chat-Auswertung für die letzten 30 Tage
    Dann ist der Chat-Auswertungs-Export mit Status 200 beantwortet
    Und enthält der Chat-Auswertungs-Export die Platzhalter für die synthetischen Personendaten

  @req-15.7
  Szenario: Kontakt- und dokumentierte Partnernamen teilen einen Platzhalter
    Angenommen im Mandanten «urinet» sind synthetische Kontakt- und Partnernamen für den Export erfasst
    Und im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 2 Sternen
    Wenn «inhaber-urinet» exportiert die Chat-Auswertung für die letzten 30 Tage
    Dann ist der Chat-Auswertungs-Export mit Status 200 beantwortet
    Und schwärzt der Export die bekannten Kontakt- und Partnernamen mit demselben Platzhalter

  @req-15.10
  Szenario: Eine Bewertung fliesst nicht in den nächsten Chat-Aufruf ein
    Angenommen der Chat nutzt den Agenten mit dem simulierten Sprachmodell
    Und im Mandanten «urinet» gibt es eine synthetische Chat-Bewertung mit 2 Sternen
    Und das simulierte Sprachmodell antwortet nacheinander:
      | antwort | art  | inhalt      | argumente | tokens |
      | 1       | text | Guten Tag.  |           | 5/2    |
    Wenn «inhaber-urinet» dem Agenten die Frage «Hallo?» stellt
    Dann lautet der gestreamte Text «Guten Tag.»
    Und hat das simulierte Sprachmodell 1 Anfragen erhalten
    Und enthält keine Nachricht der Anfrage 1 den Text «Ada Muster»
    Und enthält keine Nachricht der Anfrage 1 den Text «Synthetischer Exporttext»
