# language: de
@spec-02
Funktionalität: Chat-Schnittstelle (Aufgabe 7.3)
  «POST /api/v1/chat» nimmt eine Frage an und streamt die Antwort als Server-Sent Events («werkzeug», «text»,
  «quellen», «fertig» oder «fehler»). Gespräche und Nachrichten liegen im Schema des Mandanten, jeder sieht nur seine
  eigenen. Der Agent ist ein Testagent hinter dem ChatAgentPort (ohne Eingriff der Dev-Stub mit seiner Demo-Folge);
  das Sprachmodell folgt mit Aufgabe 7.2. Alle Daten sind synthetisch.

 Hintergrund:
  Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-3.8
  Szenario: Das erste Textereignis kommt beim Client an, bevor der Agent sein Ende liefert
    Angenommen der Testagent wartet nach dem ersten Text, bis der Client ihn erhalten hat
    Wenn «inhaber-urinet» die Frage «Wie hoch waren die Einnahmen 2025?» stellt
    Dann hat der Client das erste «text»-Ereignis erhalten, bevor der Testagent sein Ende lieferte
    Und zeigt der Stream die Ereignisse «text, text, quellen, fertig»

  @req-4.1
  Szenario: Quellen kommen genau einmal vor «fertig» und stammen aus den Werkzeugergebnissen
    Angenommen der Testagent liefert diese Folge:
      | art      | name             | dokumente | belege | parameter                   | wert                                |
      | werkzeug | dokumente_suchen |           |        |                             |                                     |
      | ergebnis | dokumente_suchen | D1, D2    |        | frage=Einnahmen             |                                     |
      | werkzeug | kennzahl         |           |        |                             |                                     |
      | ergebnis | kennzahl         | D1        | 3      | messgroesse=EINNAHMEN       |                                     |
      | werkzeug | dokument_zeigen  |           |        |                             |                                     |
      | ergebnis | dokument_zeigen  | D3        |        | id=D3                       |                                     |
      | text     |                  |           |        |                             | Die Einnahmen betragen CHF 1'250.50. |
      | ende     |                  |           |        |                             | 120/30                              |
    Wenn «inhaber-urinet» die Frage «Wie hoch waren die Einnahmen?» stellt
    Dann zeigt der Stream die Ereignisse «werkzeug, werkzeug, werkzeug, text, quellen, fertig»
    Und kommt «quellen» genau einmal unmittelbar vor «fertig»
    Und nennt «quellen» genau das Dokument «D3» und den Datenstand von «kennzahl» mit 3 Belegen
    Und beginnt jeder Link in «quellen» mit «/dokument/»

  @req-5.4
  Szenario: Frage, Werkzeugaufrufe, Quellen, Tokens und Status sind gespeichert
    Angenommen der Testagent liefert diese Folge:
      | art      | name            | dokumente | belege | parameter | wert                     |
      | werkzeug | dokument_zeigen |           |        |           |                          |
      | ergebnis | dokument_zeigen | D1        |        | id=D1     |                          |
      | text     |                 |           |        |           | Die Rechnung ist bezahlt. |
      | ende     |                 |           |        |           | 120/30                   |
    Wenn «inhaber-urinet» die Frage «Zeig mir die Rechnung 2025-007» stellt
    Dann ist im Mandanten «urinet» für «inhaber-urinet» das Gespräch mit dem Titel «Zeig mir die Rechnung 2025-007» gespeichert
    Und hat das Gespräch im Mandanten «urinet» die Frage «Zeig mir die Rechnung 2025-007» und eine Antwort mit Status «OK», 120 Tokens ein und 30 Tokens aus
    Und nennt die gespeicherte Antwort den Werkzeugaufruf «dokument_zeigen» mit dem Parameter «id» = «D1» und der ID von «D1» ohne Titel
    Und nennt die gespeicherte Antwort die Quelle «D1» mit Link

  @req-5.4
  Szenario: Der Aufräumer löscht Nachrichten nach 12 Monaten und danach leere Gespräche
    Angenommen im Mandanten «urinet» hat «inhaber-urinet» das Gespräch «ALT» mit Nachrichten vor 13 Monaten
    Und im Mandanten «urinet» hat «inhaber-urinet» das Gespräch «GEMISCHT» mit Nachrichten vor 13 Monaten und vor 11 Monaten
    Und im Mandanten «vhf-gsk» hat «inhaber-vhf-gsk» das Gespräch «FREMD» mit Nachrichten vor 13 Monaten
    Und im Mandanten «vhf-gsk» hat «inhaber-vhf-gsk» das Gespräch «JUNG» mit Nachrichten vor 11 Monaten
    Wenn der Chat-Aufräumer läuft
    Dann gibt es das Gespräch «ALT» nicht mehr
    Und hat das Gespräch «GEMISCHT» nur noch die 2 Nachrichten vor 11 Monaten
    Und gibt es das Gespräch «FREMD» nicht mehr
    Und hat das Gespräch «JUNG» nur noch die 2 Nachrichten vor 11 Monaten

  @req-5.5
  Szenario: Die 101. Frage innert 60 Minuten ergibt 429 mit Retry-After, andere Benutzer und Mandanten nicht
    Angenommen «inhaber-urinet» hat im Mandanten «urinet» in den letzten 60 Minuten 100 Fragen gestellt, die älteste vor 59 Minuten
    Wenn «inhaber-urinet» die Frage «Noch eine Frage?» stellt
    Dann lehnt das Archiv die Anfrage mit 429 «ZU_VIELE_ANFRAGEN» ab
    Und nennt die Ablehnung Retry-After von 50 bis 61 Sekunden
    Und hat «inhaber-urinet» im Mandanten «urinet» 100 Fragen in der letzten Stunde
    Wenn «mitarbeiter-urinet» die Frage «Noch eine Frage?» stellt
    Dann endet der Stream mit «fertig»
    Angenommen «inhaber-vhf-gsk» hat im Mandanten «urinet» in den letzten 60 Minuten 100 Fragen gestellt, die älteste vor 59 Minuten
    Wenn «inhaber-vhf-gsk» die Frage «Noch eine Frage?» stellt
    Dann endet der Stream mit «fertig»
    Wenn die Fragen von «inhaber-urinet» im Mandanten «urinet» 2 Minuten älter werden
    Und «inhaber-urinet» die Frage «Jetzt wieder?» stellt
    Dann endet der Stream mit «fertig»

  Szenariogrundriss: Eine ungültige Frage ergibt 400 ohne den Wert und ohne Gespräch
    Wenn «inhaber-urinet» den Chat mit dem Körper «<koerper>» aufruft
    Dann lehnt das Archiv die Anfrage mit 400 «ANFRAGE_UNGUELTIG» ab
    Und im Mandanten «urinet» hat «inhaber-urinet» kein Gespräch

    Beispiele:
      | koerper                                         |
      | {"frage": "     "}                              |
      | {"frage": 42}                                   |
      | {"gespraechId": null}                           |
      | ["Frage im Array"]                              |
      | Frage ohne JSON                                 |
      | {"frage": "Hallo", "gespraechId": "keine-uuid"} |

  Szenario: Eine Frage über 2'000 Zeichen ergibt 400, genau 2'000 Zeichen nicht
    Wenn «inhaber-urinet» eine Frage mit 2001 Zeichen stellt
    Dann lehnt das Archiv die Anfrage mit 400 «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» eine Frage mit 2000 Zeichen stellt
    Dann endet der Stream mit «fertig»

  Szenario: Ein fremdes oder unbekanntes Gespräch ergibt 404, auch im Verlauf
    Wenn «mitarbeiter-urinet» die Frage «Meine eigene Frage» stellt
    Und «inhaber-urinet» im Gespräch von «mitarbeiter-urinet» die Frage «Fremdes Gespräch?» stellt
    Dann lehnt das Archiv die Anfrage mit 404 «NICHT_GEFUNDEN» ab
    Wenn «inhaber-vhf-gsk» im Gespräch von «mitarbeiter-urinet» die Frage «Fremder Mandant?» stellt
    Dann lehnt das Archiv die Anfrage mit 404 «NICHT_GEFUNDEN» ab
    Wenn «inhaber-urinet» in einem unbekannten Gespräch die Frage «Unbekannt?» stellt
    Dann lehnt das Archiv die Anfrage mit 404 «NICHT_GEFUNDEN» ab
    Wenn «inhaber-urinet» den Verlauf des Gesprächs von «mitarbeiter-urinet» abruft
    Dann lehnt das Archiv die Anfrage mit 404 «NICHT_GEFUNDEN» ab
    Wenn «inhaber-vhf-gsk» den Verlauf des Gesprächs von «mitarbeiter-urinet» abruft
    Dann lehnt das Archiv die Anfrage mit 404 «NICHT_GEFUNDEN» ab
    Wenn «mitarbeiter-urinet» den Verlauf des Gesprächs von «mitarbeiter-urinet» abruft
    Dann zeigt der Verlauf mit dem Titel «Meine eigene Frage» diese Nachrichten:
      | rolle     | inhalt                                                                       | status | quellen |
      | BENUTZER  | Meine eigene Frage                                                           |        |         |
      | ASSISTENT | Das ist eine Demo-Antwort ohne Sprachmodell. Der Chat-Agent folgt mit Aufgabe 7.2. | OK     |         |

  Szenario: Eine zweite gleichzeitige Frage desselben Benutzers ergibt 429
    Angenommen der Testagent hält die Antwort nach dem ersten Text an
    Wenn «inhaber-urinet» die Frage «Erste Frage» stellt, ohne das Ende abzuwarten
    Und «inhaber-urinet» die Frage «Zweite Frage» stellt
    Dann lehnt das Archiv die Anfrage mit 429 «ZU_VIELE_ANFRAGEN» ab
    Und nennt die Ablehnung Retry-After von 1 bis 10 Sekunden
    Wenn der Testagent die Antwort freigibt
    Dann endet der offene Stream mit «fertig»
    Wenn «inhaber-urinet» die Frage «Dritte Frage» stellt
    Dann endet der Stream mit «fertig»
    Und sind alle Plätze für laufende Fragen wieder frei

  Szenario: Ohne Agenten antwortet der Chat mit «fehler» und dem festen Wortlaut
    Angenommen der Testagent ist der Adapter ohne Agenten
    Wenn «inhaber-urinet» die Frage «Gibt es den Chat schon?» stellt
    Dann zeigt der Stream die Ereignisse «fehler»
    Und meldet «fehler» «Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.» mit der ID des Gesprächs
    Und ist die Antwort im Mandanten «urinet» mit Status «FEHLER» und dem Inhalt «» gespeichert

  Szenario: Ein Ausfall mitten im Stream meldet «fehler» nach den Teilantworten und speichert FEHLER
    Angenommen der Testagent fällt nach dem Text «Die Einnahmen betragen» aus
    Wenn «inhaber-urinet» die Frage «Wie hoch sind die Einnahmen?» stellt
    Dann zeigt der Stream die Ereignisse «text, fehler»
    Und meldet «fehler» «Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.» mit der ID des Gesprächs
    Und ist die Antwort im Mandanten «urinet» mit Status «FEHLER» und dem Inhalt «Die Einnahmen betragen» gespeichert

  Szenario: Schliesst der Client den Stream, wird die Teilantwort als ABGEBROCHEN gespeichert
    Angenommen der Testagent wartet nach dem Text «Teilantwort» auf den Abbruch
    Wenn «inhaber-urinet» die Frage «Eine lange Frage» stellt und den Stream nach dem ersten «text» schliesst
    Dann bemerkt der Testagent den Abbruch
    Und ist die Antwort im Mandanten «urinet» mit Status «ABGEBROCHEN» und dem Inhalt «Teilantwort» gespeichert
    Und sind alle Plätze für laufende Fragen wieder frei

  Szenariogrundriss: Alle vier Rollen dürfen fragen
    Wenn «<benutzer>» die Frage «Was kann der Chat?» stellt
    Dann endet der Stream mit «fertig»
    Und ist das Gespräch im Mandanten «<mandant>» für «<benutzer>» gespeichert

    Beispiele:
      | benutzer                 | mandant |
      | inhaber-urinet           | urinet  |
      | mitarbeiter-urinet       | urinet  |
      | treuhaender-urinet       | urinet  |
      | geschaeftsstelle-vhf-gsk | vhf-gsk |

  Szenario: Gespräch und Stub-Lauf landen im Schema des fragenden Mandanten
    Wenn «inhaber-urinet» die Frage «Frage in urinet» stellt
    Und «inhaber-vhf-gsk» die Frage «Frage in vhf-gsk» stellt
    Dann lief der Testagent für «inhaber-urinet» im Mandanten-Kontext «urinet» mit dessen Schema, ohne Transaktion, auf einem Chat-Thread
    Und lief der Testagent für «inhaber-vhf-gsk» im Mandanten-Kontext «vhf-gsk» mit dessen Schema, ohne Transaktion, auf einem Chat-Thread
    Und ist das Gespräch «Frage in urinet» nur im Mandanten «urinet» gespeichert
    Und ist das Gespräch «Frage in vhf-gsk» nur im Mandanten «vhf-gsk» gespeichert

  Szenario: Folgefragen erhalten den Verlauf, und der Verlauf zeigt Antworten mit Quellen
    Angenommen der Testagent liefert diese Folge:
      | art      | name            | dokumente | belege | parameter | wert         |
      | werkzeug | dokument_zeigen |           |        |           |              |
      | ergebnis | dokument_zeigen | D1        |        | id=D1     |              |
      | text     |                 |           |        |           | Antwort eins |
      | ende     |                 |           |        |           | 10/2         |
    Wenn «inhaber-urinet» die Frage «Frage eins» stellt
    Und «inhaber-urinet» im eigenen Gespräch die Frage «Frage zwei» stellt
    Dann erhielt der Testagent zur letzten Frage diesen Verlauf:
      | rolle     | inhalt       |
      | BENUTZER  | Frage eins   |
      | ASSISTENT | Antwort eins |
    Wenn «inhaber-urinet» den Verlauf des Gesprächs von «inhaber-urinet» abruft
    Dann zeigt der Verlauf mit dem Titel «Frage eins» diese Nachrichten:
      | rolle     | inhalt       | status | quellen |
      | BENUTZER  | Frage eins   |        |         |
      | ASSISTENT | Antwort eins | OK     | D1      |
      | BENUTZER  | Frage zwei   |        |         |
      | ASSISTENT | Antwort eins | OK     | D1      |

  Szenario: Die Gesprächsliste zeigt höchstens 20 eigene Gespräche, neueste zuerst
    Angenommen im Mandanten «urinet» hat «inhaber-urinet» 21 Gespräche
    Und im Mandanten «urinet» hat «mitarbeiter-urinet» 1 Gespräche
    Wenn «inhaber-urinet» die Gesprächsliste abruft
    Dann enthält die Liste die 20 neuesten Gespräche von «inhaber-urinet», neueste zuerst
    Wenn «mitarbeiter-urinet» die Gesprächsliste abruft
    Dann enthält die Liste 1 Gespräche
    Wenn «inhaber-vhf-gsk» die Gesprächsliste abruft
    Dann enthält die Liste 0 Gespräche

  Szenario: Frage, Antwort, Titel und Werkzeugparameter erscheinen nicht im Log
    Angenommen das Log des Chats wird mitgeschnitten
    Und der Testagent liefert diese Folge:
      | art      | name             | dokumente | belege | parameter                   | wert                 |
      | werkzeug | dokumente_suchen |           |        |                             |                      |
      | ergebnis | dokumente_suchen | D1        |        | frage=Geheimer-Parameter-27 |                      |
      | text     |                  |           |        |                             | Geheime-Antwort-4711 |
      | ausfall  |                  |           |        |                             | TestAusfall          |
    Wenn «inhaber-urinet» die Frage «Geheime-Frage-0815 zum Lohn» stellt
    Dann enthält das mitgeschnittene Log weder «Geheime-Frage-0815» noch «Geheime-Antwort-4711» noch «Geheimer-Parameter-27»
