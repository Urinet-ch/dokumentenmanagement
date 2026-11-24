# language: de
@spec-04
Funktionalität: Öffentlicher Wissenschat

  @req-21.6
  Szenario: Nur freigegebene Wissensseiten mit Belegen liefern
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Reihen:
      | code        | name        |
      | PROBE_WISSEN | Probe-Reihe |
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe        | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | PROBE_WISSEN | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis? Meine Kontaktadresse ist max.muster@example.invalid.»
    Dann bietet die erste Anfrage dem Sprachmodell genau die Werkzeuge «wissen_index_oeffentlich, wissen_seite_zeigen_oeffentlich»
    Und liefert der Wissenschat die Seite mit belegter Quelle und protokolliert die bereinigte Frage
    Und bleibt die öffentliche Wissensseite im anderen Mandanten unsichtbar

  @req-21.6
  Szenario: Ein gebundenes Gespräch wird fortgesetzt und die Frage ohne erkennbare Kontaktdaten protokolliert
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Reihen:
      | code        | name        |
      | PROBE_WISSEN | Probe-Reihe |
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe        | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | PROBE_WISSEN | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis? Meine Kontaktadresse ist max.muster@example.invalid.»
    Dann liefert der Wissenschat die Seite mit belegter Quelle und protokolliert die bereinigte Frage
    Angenommen das simulierte Sprachmodell beantwortet die Fortsetzung
    Wenn die Webseite mit dem Schlüssel «wissen» das bisherige Wissenschat-Gespräch mit «Fortsetzung-2071» fortsetzt
    Dann enthält die Fortsetzung den vorherigen Antwortverlauf und bleibt an die freigegebene Seite gebunden

  @req-21.6
  Szenario: Ein Entzug vor der Fortsetzung verhindert den nächsten Modellaufruf
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Reihen:
      | code        | name        |
      | PROBE_WISSEN | Probe-Reihe |
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe        | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | PROBE_WISSEN | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann liefert der Wissenschat die Seite mit belegter Quelle und protokolliert die bereinigte Frage
    Angenommen das simulierte Sprachmodell beantwortet die Fortsetzung
    Wenn die Freigabe der öffentlichen Wissensseite entzogen wird
    Und die Webseite mit dem Schlüssel «wissen» das bisherige Wissenschat-Gespräch mit «Fortsetzung-2071» fortsetzt
    Dann wird die Fortsetzung ohne Modellaufruf sicher abgelehnt

  @req-21.6
  Szenario: Ohne Einschalten des Mandanten wird das Modell nicht aufgerufen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Chatbot-Schlüssel «aus» der Webseite «probe-wissen» im Mandanten «vhf-gsk» hat die höchste Stufe «oeffentlich»
    Wenn die Webseite mit dem Schlüssel «aus» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann liefert der Chatbot Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und hat das simulierte Sprachmodell keine Anfrage erhalten

  @req-21.6 @req-21.7
  Szenario: Erschöpftes Wissensbudget lehnt ab und gibt den öffentlichen Platz wieder frei
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und das Wissensbudget von «urinet» ist durch synthetischen Verbrauch erschöpft
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann liefert der Chatbot Status 429 und Fehlercode «BUDGET»
    Und hat das simulierte Sprachmodell keine Anfrage erhalten
    Wenn der synthetische Wissensverbrauch entfernt wird
    Angenommen das simulierte Sprachmodell antwortet nacheinander:
      | antwort | art  | inhalt      | argumente | tokens |
      | 1       | text | Kein Beleg. |           | 5/2    |
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann endet der Stream des Chatbots mit «quellen» und «fertig»
    Und lautet der Text des Chatbots «Dazu steht nichts in der Wissensbasis.»

  @req-21.6
  Szenariogrundriss: Entzug und Höherstufung während einer Frage verhindern die Antwort
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | -     | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite und verzögert die Schlussantwort
    Wenn die Webseite mit dem Schlüssel «wissen» fragt und danach «<änderung>» geändert wird
    Dann liefert der Wissenschat nach der Änderung weder Modelltext noch Quellen

    Beispiele:
      | änderung     |
      | Entzug       |
      | Höherstufung |
