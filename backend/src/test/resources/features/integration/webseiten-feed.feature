# language: de
@spec-03 @webseiten-feed
Funktionalität: Änderungs-Feed der Webseiten-Schnittstelle (Aufgabe 11.9)
  Die Webseite fragt Änderungen ab (Lese-Feed mit der Stufe des Schlüssels, Erfassungs-Feed für die Dokumente des
  Mandanten); das Archiv ruft nie eine Webseite auf. Ein Trigger auf «dokument» schreibt jede Änderung in derselben
  Transaktion; der Cursor liefert jeden Eintrag genau einmal und nie vor dem Ende seiner Transaktion. Dokumente und
  Texte sind synthetisch; Cursor werden vor der jeweiligen Änderung gemerkt, damit das Anlegen der Dokumente nicht zählt.
  Die lang offene Transaktion und die Gleichheit von Trigger und festem Filter belegt das JUnit AenderungFeedTest.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Webseiten-Lesezugang «vorstand» der Webseite «probe-feed» im Mandanten «urinet» hat die höchste Stufe «vorstand»
    Und der Webseiten-Lesezugang «mitglieder» der Webseite «probe-feed» im Mandanten «urinet» hat die höchste Stufe «mitglieder»
    Und der Webseiten-Lesezugang «oeffentlich» der Webseite «probe-feed» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und der Webseiten-Erfassungszugang «erfassung» der Webseite «probe-feed» im Mandanten «urinet» ist angelegt
    Und der Webseiten-Erfassungszugang «fremd» der Webseite «probe-andere» im Mandanten «urinet» ist angelegt

  @req-14.1
  Szenario: Ein Stufenwechsel meldet die tiefere sichtbare Stufe und nie eine Stufe über dem Schlüssel
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | zugriff     |
      | s1   | oeffentlich |
      | s2   | mitglieder  |
    Und der Zugang «oeffentlich» hat den Cursor «o» von «/public/v1/aenderungen» gemerkt
    Und der Zugang «mitglieder» hat den Cursor «m» von «/public/v1/aenderungen» gemerkt
    Und der Zugang «vorstand» hat den Cursor «v» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «s1» auf die Stufe «vorstand» gesetzt wird
    Und das Dokument «s2» auf die Stufe «vorstand» gesetzt wird
    Und der Zugang «oeffentlich» «/public/v1/aenderungen» ab dem Cursor «o» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | s1       | NICHT_SICHTBAR | oeffentlich |
    Wenn der Zugang «mitglieder» «/public/v1/aenderungen» ab dem Cursor «m» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | s1       | NICHT_SICHTBAR | oeffentlich |
      | s2       | NICHT_SICHTBAR | mitglieder  |
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art       | stufe       |
      | s1       | GEAENDERT | oeffentlich |
      | s2       | GEAENDERT | mitglieder  |
    Wenn das Dokument «s1» auf die Stufe «oeffentlich» gesetzt wird
    Und der Zugang «oeffentlich» «/public/v1/aenderungen» ab dem Cursor «o» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art      | stufe       |
      | s1       | SICHTBAR | oeffentlich |
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art       | stufe       |
      | s1       | GEAENDERT | oeffentlich |

  @req-14.1
  Szenario: Nichts Unveröffentlichtes erscheint, bis es sichtbar wird
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | veroeffentlicht | vertraulichkeit | status        | bestaetigt |
      | u1   | false           | NORMAL          | ARCHIVIERT    | ja         |
      | i1   | true            | INTERN          | ARCHIVIERT    | ja         |
      | n1   | true            | NORMAL          | EINGANGSKORB  | ja         |
      | p1   | true            | NORMAL          | ARCHIVIERT    | nein       |
    Und der Zugang «vorstand» hat den Cursor «v» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «u1» eine neue Version erhält
    Und das Dokument «i1» eine neue Version erhält
    Und das Dokument «n1» eine neue Version erhält
    Und das Dokument «p1» eine neue Version erhält
    Und der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed keine Änderungen
    Wenn das Dokument «u1» veröffentlicht wird
    Und das Dokument «i1» die Vertraulichkeit «NORMAL» erhält
    Und das Dokument «n1» den Status «ARCHIVIERT» erhält
    Und der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art      | stufe       |
      | u1       | SICHTBAR | oeffentlich |
      | i1       | SICHTBAR | oeffentlich |
      | n1       | SICHTBAR | oeffentlich |

  @req-14.1
  Szenario: Ein Rückzug meldet NICHT_SICHTBAR, und kein Eintrag trägt Inhalt
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | zugriff     | text                     |
      | r1   | oeffentlich | Geheimer Zitronenkuchen  |
    Und der Zugang «oeffentlich» hat den Cursor «o» von «/public/v1/aenderungen» gemerkt
    Und der Zugang «vorstand» hat den Cursor «v» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «r1» zurückgezogen wird
    Und der Zugang «oeffentlich» «/public/v1/aenderungen» ab dem Cursor «o» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | r1       | NICHT_SICHTBAR | oeffentlich |
    Und hat jeder Eintrag des Feeds nur die Felder «dokumentId,art,stufe,zeit»
    Und steht in der Lese-Antwort weder «webtest-r1» noch «Zitronenkuchen»
    Und trägt die Lese-Antwort den Header «Cache-Control» mit dem Wert «no-store»
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | r1       | NICHT_SICHTBAR | oeffentlich |

  @req-14.1
  Szenario: Im Feed gilt die Stufe des Schlüssels, die Header der Anfrage zählen nicht
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | zugriff    |
      | h1   | mitglieder |
    Und der Zugang «oeffentlich» hat den Cursor «o» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «h1» auf die Stufe «oeffentlich» gesetzt wird
    Und der Zugang «oeffentlich» «/public/v1/aenderungen» ab dem Cursor «o» mit der Stufe «vorstand» im Header abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art      | stufe       |
      | h1       | SICHTBAR | oeffentlich |
    Wenn der Zugang «oeffentlich» «/public/v1/aenderungen» ab dem Cursor «o» mit der Stufe «unsinn» im Header abruft
    Dann nennt der Feed keine Änderungen

  @req-14.2 @req-9.4
  Szenario: Der Erfassungs-Feed nennt Status und Fehlercode und die Referenz nur der eigenen Webseite
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | status       | veroeffentlicht |
      | e1   | ARCHIVIERT   | false           |
      | e2   | ARCHIVIERT   | false           |
      | e3   | ARCHIVIERT   | false           |
    Und die Webseite «probe-feed» hat für das Dokument «e1» die Referenz «R-1»
    Und die Webseite «probe-andere» hat für das Dokument «e2» die Referenz «R-2»
    Und der Zugang «erfassung» hat den Cursor «a» von «/public/v1/erfassung/aenderungen» gemerkt
    Und der Zugang «fremd» hat den Cursor «b» von «/public/v1/erfassung/aenderungen» gemerkt
    Wenn das Dokument «e1» den Status «FEHLER» erhält
    Und das Dokument «e2» eine neue Version erhält
    Und das Dokument «e3» den Status «EINGANGSKORB» erhält
    Und der Zugang «erfassung» «/public/v1/erfassung/aenderungen» ab dem Cursor «a» abruft
    Dann nennt der Feed genau diese Erfassungs-Änderungen:
      | dokument | referenz | status       | fehlercode                  | version |
      | e1       | R-1      | FEHLER       | VERARBEITUNG_FEHLGESCHLAGEN | 1       |
      | e2       | -        | ARCHIVIERT   | -                           | 1       |
      | e3       | -        | EINGANGSKORB | -                           | 1       |
    Und steht in der Lese-Antwort weder «webtest-e1» noch «R-2»
    Wenn der Zugang «fremd» «/public/v1/erfassung/aenderungen» ab dem Cursor «b» abruft
    Dann nennt der Feed genau diese Erfassungs-Änderungen:
      | dokument | referenz | status       | fehlercode                  | version |
      | e1       | -        | FEHLER       | VERARBEITUNG_FEHLGESCHLAGEN | 1       |
      | e2       | R-2      | ARCHIVIERT   | -                           | 1       |
      | e3       | -        | EINGANGSKORB | -                           | 1       |
    Und steht in der Lese-Antwort weder «webtest-e1» noch «R-1»

  @req-14.2 @req-9.4
  Szenario: Lese- und Erfassungs-Schlüssel erreichen nur ihren eigenen Feed
    Wenn mit dem Erfassungs-Zugang «oeffentlich» «/public/v1/erfassung/aenderungen» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn mit dem Erfassungs-Zugang «erfassung» «/public/v1/aenderungen» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Wenn ohne Schlüssel «/public/v1/aenderungen» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 401 und Fehlercode «NICHT_ANGEMELDET»
    Wenn ohne Schlüssel «/public/v1/erfassung/aenderungen» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 401 und Fehlercode «NICHT_ANGEMELDET»

  @req-14.1 @req-14.2
  Szenario: Der Feed zeigt nie Änderungen eines anderen Mandanten
    Angenommen der Webseiten-Lesezugang «anderer» der Webseite «probe-feed» im Mandanten «vhf-gsk» hat die höchste Stufe «vorstand»
    Und im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | zugriff     |
      | mu   | oeffentlich |
    Und im Mandanten «vhf-gsk» liegen diese Webseiten-Dokumente:
      | name | zugriff     |
      | mv   | oeffentlich |
    Und der Zugang «vorstand» hat den Cursor «v» von «/public/v1/aenderungen» gemerkt
    Und der Zugang «anderer» hat den Cursor «x» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «mu» zurückgezogen wird
    Und das Dokument «mv» zurückgezogen wird
    Und der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | mu       | NICHT_SICHTBAR | oeffentlich |
    Wenn der Zugang «anderer» «/public/v1/aenderungen» ab dem Cursor «x» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | mv       | NICHT_SICHTBAR | oeffentlich |

  @req-14.1 @req-9.4
  Szenario: Der öffentliche Schlüssel erhält keine Feed-Änderungen zu höheren Zugriffsstufen
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | zugriff     |
      | h1   | mitglieder  |
      | h2   | vorstand    |
    Und der Zugang «oeffentlich» hat den Cursor «o» von «/public/v1/aenderungen» gemerkt
    Und der Zugang «mitglieder» hat den Cursor «m» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «h1» eine neue Version erhält
    Und das Dokument «h2» eine neue Version erhält
    Und der Zugang «oeffentlich» «/public/v1/aenderungen» ab dem Cursor «o» abruft
    Dann nennt der Feed keine Änderungen
    Wenn der Zugang «mitglieder» «/public/v1/aenderungen» ab dem Cursor «m» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art       | stufe       |
      | h1       | GEAENDERT | mitglieder  |

  @req-14.2 @req-1.4 @req-9.1
  Szenario: Erfassungs-Referenzen bleiben auch zwischen Mandanten getrennt
    Angenommen der Webseiten-Erfassungszugang «fremd-erfassung» der Webseite «probe-feed» im Mandanten «vhf-gsk» ist angelegt
    Und im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | status     | veroeffentlicht |
      | ru   | ARCHIVIERT | false           |
    Und im Mandanten «vhf-gsk» liegen diese Webseiten-Dokumente:
      | name | status     | veroeffentlicht |
      | rv   | ARCHIVIERT | false           |
    Und die Webseite «probe-feed» hat für das Dokument «ru» die Referenz «REFERENZ-URINET»
    Und die Webseite «probe-feed» hat für das Dokument «rv» die Referenz «REFERENZ-VHF»
    Und der Zugang «erfassung» hat den Cursor «ru-cursor» von «/public/v1/erfassung/aenderungen» gemerkt
    Und der Zugang «fremd-erfassung» hat den Cursor «rv-cursor» von «/public/v1/erfassung/aenderungen» gemerkt
    Wenn das Dokument «ru» den Status «FEHLER» erhält
    Und das Dokument «rv» den Status «FEHLER» erhält
    Und der Zugang «erfassung» «/public/v1/erfassung/aenderungen» ab dem Cursor «ru-cursor» abruft
    Dann nennt der Feed genau diese Erfassungs-Änderungen:
      | dokument | referenz       | status | fehlercode                  | version |
      | ru       | REFERENZ-URINET | FEHLER | VERARBEITUNG_FEHLGESCHLAGEN | 1       |
    Wenn der Zugang «fremd-erfassung» «/public/v1/erfassung/aenderungen» ab dem Cursor «rv-cursor» abruft
    Dann nennt der Feed genau diese Erfassungs-Änderungen:
      | dokument | referenz    | status | fehlercode                  | version |
      | rv       | REFERENZ-VHF | FEHLER | VERARBEITUNG_FEHLGESCHLAGEN | 1       |

  @req-14.3
  Szenario: Jeder Eintrag kommt genau einmal, höchstens max je Abruf, und der Cursor führt weiter
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | zugriff     |
      | p1   | oeffentlich |
      | p2   | oeffentlich |
      | p3   | oeffentlich |
      | p4   | oeffentlich |
      | p5   | oeffentlich |
    Und der Zugang «vorstand» hat den Cursor «v» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «p1» eine neue Version erhält
    Und das Dokument «p2» eine neue Version erhält
    Und das Dokument «p3» eine neue Version erhält
    Und das Dokument «p4» eine neue Version erhält
    Und das Dokument «p5» eine neue Version erhält
    Und der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» mit höchstens 2 Einträgen abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art       | stufe       |
      | p1       | GEAENDERT | oeffentlich |
      | p2       | GEAENDERT | oeffentlich |
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» mit höchstens 2 Einträgen abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art       | stufe       |
      | p3       | GEAENDERT | oeffentlich |
      | p4       | GEAENDERT | oeffentlich |
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» mit höchstens 2 Einträgen abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art       | stufe       |
      | p5       | GEAENDERT | oeffentlich |
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» ab dem Cursor «v» abruft
    Dann nennt der Feed keine Änderungen

  @req-14.3
  Szenario: Ungültige Angaben von max und seit sind 400
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» mit höchstens «0» Einträgen abruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» mit höchstens «501» Einträgen abruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» mit höchstens «viele» Einträgen abruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn der Zugang «erfassung» «/public/v1/erfassung/aenderungen» mit höchstens «-1» Einträgen abruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn der Zugang «vorstand» «/public/v1/aenderungen» mit dem Cursor-Text «kein-cursor» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn der Zugang «erfassung» «/public/v1/erfassung/aenderungen» mit dem Cursor-Text «djEuOTk5OTk5OTk5OTk5OTk5OTk5LjA» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

  @req-14.4
  Szenario: Ein Cursor älter als die Aufbewahrung ist 410, und ohne Cursor beginnt der Feed neu
    Angenommen der Webseiten-Lesezugang «alt» der Webseite «probe-feed» im Mandanten «vhf-gsk» hat die höchste Stufe «vorstand»
    Und der Webseiten-Erfassungszugang «alt-erfassung» der Webseite «probe-feed» im Mandanten «vhf-gsk» ist angelegt
    Und im Mandanten «vhf-gsk» liegen diese Webseiten-Dokumente:
      | name | zugriff     |
      | z1   | oeffentlich |
    Und der Zugang «alt» hat den Cursor «alt» von «/public/v1/aenderungen» gemerkt
    Wenn das Dokument «z1» zurückgezogen wird
    Und der Zugang «alt» «/public/v1/aenderungen» ab dem Cursor «alt» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art            | stufe       |
      | z1       | NICHT_SICHTBAR | oeffentlich |
    Wenn die Einträge des Feeds im Mandanten «vhf-gsk» 91 Tage später bereinigt werden
    Und der Zugang «alt» «/public/v1/aenderungen» mit dem Cursor-Text «djEuMS4w» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 410 und Fehlercode «CURSOR_ABGELAUFEN»
    Und trägt die Lese-Antwort den Header «Cache-Control» mit dem Wert «no-store»
    Wenn der Zugang «alt-erfassung» «/public/v1/erfassung/aenderungen» mit dem Cursor-Text «djEuMS4w» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 410 und Fehlercode «CURSOR_ABGELAUFEN»
    Wenn der Zugang «alt» «/public/v1/aenderungen» ohne Cursor abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und nennt der Feed keine Änderungen
    Wenn das Dokument «z1» veröffentlicht wird
    Und der Zugang «alt» «/public/v1/aenderungen» ab dem Cursor «alt» abruft
    Dann nennt der Feed genau diese Änderungen:
      | dokument | art      | stufe       |
      | z1       | SICHTBAR | oeffentlich |

  @req-15.1
  Szenario: Beide Feeds zählen in der Grenze je Schlüssel mit
    Angenommen es gibt den Lese-Schlüssel «A» für Grenzen
    Und es gibt den Erfassungs-Schlüssel «E» und den Erfassungs-Schlüssel «F» für Grenzen
    Wenn der Schlüssel «A» «/public/v1/aenderungen» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird
    Dann wurde die erste Anfrage zwischen der 41. und der 44. abgelehnt
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «ANFRAGEN»
    Wenn der Schlüssel «E» «/public/v1/erfassung/aenderungen» mit wechselnden Clients und Stufen aufruft, bis eine Anfrage abgelehnt wird
    Dann wurde die erste Anfrage zwischen der 9. und der 12. abgelehnt
    Und die Ablehnung ist 429 «ZU_VIELE_ANFRAGEN» mit «Retry-After» zwischen 1 und 3600 Sekunden und dem Grund «ANFRAGEN»
    Wenn der Schlüssel «F» «/public/v1/erfassung/aenderungen» ohne Header aufgerufen wird
    Dann antwortet die Grenze mit Status 200
