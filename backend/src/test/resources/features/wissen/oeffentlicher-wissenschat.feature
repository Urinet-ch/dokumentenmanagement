# language: de
@spec-04
Funktionalität: Öffentlicher Wissenschat

  @req-21.6 @req-21.8
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
    Und liefert der Wissenschat die Seite mit belegter Quelle und schreibt ein inhaltsfreies Fragenprotokoll
    Und bleibt die öffentliche Wissensseite im anderen Mandanten unsichtbar

  @req-21.6 @req-21.8
  Szenario: Eine nicht freigegebene Wissensseite bleibt im öffentlichen Wissenschat unsichtbar
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Reihen:
      | code        | name        |
      | PROBE_WISSEN | Probe-Reihe |
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe        | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | PROBE_WISSEN | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Wenn die Freigabe der öffentlichen Wissensseite entzogen wird
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite
    Und die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann endet der Stream des Chatbots mit «quellen» und «fertig»
    Und lautet der Text des Chatbots «Dazu steht nichts in der Wissensbasis.»
    Und sind die Quellen des Chatbots leer
    Und enthält der Text des Chatbots nicht «Belegte synthetische Antwort.»
    Und hat das simulierte Sprachmodell keine Anfrage erhalten
    Und speichert der Wissenschat das Schweigen ohne Beleg in genau eine inhaltsfreie Frage
    Und bleibt die öffentliche Wissensseite im anderen Mandanten unsichtbar

  @req-21.6 @req-21.8
  Szenario: Eine Wissensseite mit Quellenstufe «mitglieder» bleibt öffentlich unsichtbar
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Reihen:
      | code        | name        |
      | PROBE_WISSEN | Probe-Reihe |
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe        | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | PROBE_WISSEN | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das Chatbot-Dokument «A» ist auf die Zugriffsstufe «mitglieder» hochgestuft
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann endet der Stream des Chatbots mit «quellen» und «fertig»
    Und lautet der Text des Chatbots «Dazu steht nichts in der Wissensbasis.»
    Und sind die Quellen des Chatbots leer
    Und enthält der Text des Chatbots nicht «Belegte synthetische Antwort.»
    Und hat das simulierte Sprachmodell keine Anfrage erhalten
    Und bleibt die öffentliche Wissensseite im anderen Mandanten unsichtbar

  @req-21.6 @req-21.8
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
    Dann liefert der Wissenschat die Seite mit belegter Quelle und schreibt ein inhaltsfreies Fragenprotokoll
    Angenommen das simulierte Sprachmodell liest in der Fortsetzung die Wissensseite erneut und antwortet
    Wenn die Webseite mit dem Schlüssel «wissen» das bisherige Wissenschat-Gespräch mit «Fortsetzung-2071» fortsetzt
    Dann enthält die Fortsetzung den vorherigen Antwortverlauf und bleibt an die freigegebene Seite gebunden

  @req-21.6 @req-21.8
  Szenario: Eine Folgefrage ohne Beleg zeigt keine Quellen früherer Runden
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
    Dann liefert der Wissenschat die Seite mit belegter Quelle und schreibt ein inhaltsfreies Fragenprotokoll
    Angenommen das simulierte Sprachmodell beantwortet die Fortsetzung
    Wenn die Webseite mit dem Schlüssel «wissen» das bisherige Wissenschat-Gespräch mit «Fortsetzung-2071» fortsetzt
    Dann endet der Stream des Chatbots mit «quellen» und «fertig»
    Und lautet der Text des Chatbots «Dazu steht nichts in der Wissensbasis.»
    Und sind die Quellen des Chatbots leer

  @req-21.6 @req-21.8
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
    Dann liefert der Wissenschat die Seite mit belegter Quelle und schreibt ein inhaltsfreies Fragenprotokoll
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

  @req-21.6 @req-21.8
  Szenariogrundriss: Entzug und Höherstufung während einer Frage verhindern die Antwort
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | -     | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das simulierte Sprachmodell liest diese öffentliche Wissensseite und verzögert die Schlussantwort
    Wenn die Webseite mit dem Schlüssel «wissen» fragt und nach dem ersten SSE-Ereignis «<änderung>» geändert wird
    Dann liefert der Wissenschat nach der Änderung weder Modelltext noch Quellen

    Beispiele:
      | änderung     |
      | Entzug       |
      | Höherstufung |

  @req-21.6 @req-21.7
  Szenario: Eine zu lange Frage wird abgelehnt, ohne das Modell zu fragen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und das simulierte Sprachmodell antwortet nacheinander:
      | antwort | art  | inhalt      | argumente | tokens |
      | 1       | text | Kein Beleg. |           | 5/2    |
    Wenn die Webseite mit dem Schlüssel «wissen» eine Wissenschat-Frage mit 501 Zeichen stellt
    Dann liefert der Chatbot Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und steht in der Chatbot-Antwort weder «aaaaaaaaaa» noch «501»
    Und hat das simulierte Sprachmodell keine Anfrage erhalten
    Wenn die Webseite mit dem Schlüssel «wissen» eine Wissenschat-Frage mit 500 Zeichen stellt
    Dann endet der Stream des Chatbots mit «quellen» und «fertig»

  @req-21.6 @req-21.7
  Szenario: Höchstens 20 Wissenschat-Fragen je Client und Stunde, danach 429 mit Retry-After
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und das simulierte Sprachmodell antwortet nacheinander:
      | antwort | art  | inhalt      | argumente | tokens |
      | 1       | text | Kein Beleg. |           | 5/2    |
    Wenn die Webseite mit dem Schlüssel «wissen» 20 Wissenschat-Fragen «Was steht in der Wissensbasis?» stellt
    Und die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Und noch etwas?»
    Dann liefert der Chatbot Status 429 und Fehlercode «ZU_VIELE_ANFRAGEN»
    Und trägt die Chatbot-Antwort den Header «Retry-After»
    Und nennt das Problem den Grund «STUNDENLIMIT»

  @req-21.6 @req-21.8
  Szenario: Antwortet das Modell erst nach der Zeitgrenze, meldet der Wissenschat den festen Fehler ohne Quellen
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Reihen:
      | code         | name        |
      | PROBE_WISSEN | Probe-Reihe |
    Und im Mandanten «urinet» liegen diese Chatbot-Dokumente:
      | name | titel          | reihe        | ausgabe | jahr | zugriff     | veroeffentlicht | vertraulichkeit | status     | text  |
      | A    | Probe-Bulletin | PROBE_WISSEN | 3/2071  | 2071 | oeffentlich | true            | NORMAL          | ARCHIVIERT | Probe |
    Und der Chatbot-Schlüssel «wissen» der Webseite «probe-wissen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und eine freigegebene öffentliche Wissensseite belegt das Chatbot-Dokument «A»
    Und das simulierte Sprachmodell antwortet erst nach 9 Sekunden
    Wenn die Webseite mit dem Schlüssel «wissen» den Wissenschat fragt: «Was steht in der Wissensbasis?»
    Dann meldet der Stream des Chatbots «fehler» «Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.» und weder «quellen» noch «fertig»
