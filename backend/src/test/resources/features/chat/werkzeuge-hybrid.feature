# language: de
@spec-02
Funktionalität: Hybridsuche im Chat-Werkzeug dokumente_suchen (Aufgabe 7.1d)
  Das Werkzeug «dokumente_suchen» sucht mit einer Frage und ohne «neueste» über die Hybridsuche der Aufgabe 6.4: ohne
  Dateinamen, mit «k» gleich der gewünschten Anzahl, Art, Partner und Zeitraum als Filter und mit der Sichtregel des
  Chat-Benutzers in jedem Teil (Req 3.1, 2.3, 2.5; Spec 01, Req 3.13). Fallen Embedding-Modell oder Reranker aus, liefert
  es die Volltexttreffer mit dem Hinweis «nur Volltext». Embedding-Modell und Reranker sind WireMocks: Das Embedding-
  Modell bettet jede Frage gleich ein, ein Abschnitt mit Abstand t liegt nahe bei ihr, der Reranker kehrt die
  Reihenfolge der Texte um. Abschnitte: «t» oder «t@von-bis» je Abschnitt, getrennt durch «;». Alle Daten sind
  synthetisch; die Jahre 2072/2073 trennen sie von den Dokumenten anderer Szenarien.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Hybridsuche im Chat ist bereit

  @req-3.1 @req-2.2
  Szenario: Ein Begriff, der im Text fehlt, findet ein Dokument nur über den Vektor-Teil, mit Seiten der Fundstelle
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name      | titel | art          | datum      | text                            | abschnitte |
      | C_POLICE  | C_POLICE | VERSICHERUNG | 2072-03-01 | Police UVG Prämie 2072          | 0.1@2-3    |
      | C_OHNE    | C_OHNE   | VERTRAG      | 2072-03-02 | Mietvertrag ohne Abschnitte     |            |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Unfallversicherung |
    Dann lauten die Treffer im Chat "C_POLICE"
    Und ist die Antwort im Chat nicht degradiert und ohne Hinweis
    Und nennt der Treffer «C_POLICE» im Chat die Seiten "2-3"
    Und erhielt der Reranker im Chat nur Texte von «C_POLICE»

  @req-3.1 @req-2.2
  Szenario: Ein Treffer nur aus dem Volltext hat keine Seiten
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name       | titel      | art          | datum      | text                  | abschnitte |
      | C_VOLLTEXT | C_VOLLTEXT | VERSICHERUNG | 2072-03-01 | Prämie Zebrastreifen  |            |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Zebrastreifen |
    Dann lauten die Treffer im Chat "C_VOLLTEXT"
    Und nennt der Treffer «C_VOLLTEXT» im Chat die Seiten ""
    Und ist die Antwort im Chat nicht degradiert und ohne Hinweis

  @req-3.1
  Szenario: Art und Zeitraum gehen als Filter in alle Teile der Hybridsuche
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name      | titel     | art          | datum      | text   | abschnitte |
      | C_PASST   | C_PASST   | VERSICHERUNG | 2072-03-01 | Garage | 0.3        |
      | C_ART     | C_ART     | VERTRAG      | 2072-03-01 | Garage | 0.1        |
      | C_ALT     | C_ALT     | VERSICHERUNG | 2070-01-15 | Garage | 0.1        |
      | C_SPAET   | C_SPAET   | VERSICHERUNG | 2074-06-01 | Garage | 0.1        |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Unfallversicherung |
      | art   | versicherung       |
      | von   | 2072-01-01         |
      | bis   | 2073-12-31         |
    Dann lauten die Treffer im Chat "C_PASST"
    Und erhielt der Reranker im Chat nur Texte von «C_PASST»

  @req-3.1
  Szenario: Mit «neueste» und mit der Anzahl 1 bleibt es bei der Volltextsuche beziehungsweise bei einem Treffer
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name   | titel  | art          | datum      | text                    | abschnitte |
      | C_NEU  | C_NEU  | VERSICHERUNG | 2072-05-01 | Nachtragspolice Quarz   | 0.1        |
      | C_ALT2 | C_ALT2 | VERSICHERUNG | 2072-01-01 | Nachtragspolice Quarz   | 0.2        |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage   | Nachtragspolice |
      | neueste | true            |
    Dann lauten die Treffer im Chat "C_NEU, C_ALT2"
    Und ist die Antwort im Chat nicht degradiert und ohne Hinweis
    Und ging kein Aufruf an den Reranker
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage  | Nachtragspolice |
      | anzahl | 1               |
    Dann hat der Chat genau 1 Treffer

  @req-3.1 @req-3.13
  Szenario: Ein Begriff nur im Dateinamen trifft auch hybrid nicht
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name     | titel    | art           | datum      | text                       | dateiname                | abschnitte |
      | C_DATEI  | C_DATEI  | KORRESPONDENZ | 2072-01-20 | Planung der Bergstation    | Zahnradbahn_Sitzung.pdf  |            |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Zahnradbahn |
    Dann hat der Chat keine Treffer
    Und ging kein Aufruf an den Reranker

  @req-3.1 @req-3.13
  Szenario: Der Dateiname steht in keiner Antwort und in keinem Text an den Reranker
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name     | titel    | art           | datum      | text                       | dateiname                | abschnitte |
      | C_DATEI  | C_DATEI  | KORRESPONDENZ | 2072-01-20 | Planung der Bergstation    | Zahnradbahn_Sitzung.pdf  | 0.1        |
      | C_VOLL   | C_VOLL   | KORRESPONDENZ | 2072-01-21 | Bergstation Nachtrag       | Gleisplan_Scan.pdf       |            |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Bergstation |
    Dann lauten die Treffer im Chat "C_DATEI, C_VOLL"
    Und enthält kein Text an den Reranker und keine Antwort des Chats den Dateinamen von «C_DATEI»
    Und enthält kein Text an den Reranker und keine Antwort des Chats den Dateinamen von «C_VOLL»

  @req-3.1 @req-2.5
  Szenario: Jede Rolle erhält nur, was sie sehen darf, und der Reranker bekommt nur deren Texte
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name         | titel        | art          | datum      | vertraulichkeit | bestaetigt | erfasser       | person             | status     | text                          | abschnitte |
      | C_NORMAL     | C_NORMAL     | VERSICHERUNG | 2072-03-01 | NORMAL          | ja         | inhaber-urinet |                    | ARCHIVIERT | Police normal                 | 0.1        |
      | C_INTERN     | C_INTERN     | VERSICHERUNG | 2072-03-02 | INTERN          | ja         | inhaber-urinet |                    | ARCHIVIERT | Geheimnis Jahresabschluss     | 0.1        |
      | C_INTERN_TXT | C_INTERN_TXT | VERSICHERUNG | 2072-03-03 | INTERN          | ja         | inhaber-urinet |                    | ARCHIVIERT | Geheimnis Police Volltext     |            |
      | C_LOHN_MA    | C_LOHN_MA    | LOHNABRECHNUNG | 2072-03-31 | PERSONAL      | ja         | inhaber-urinet | mitarbeiter-urinet | ARCHIVIERT | Police Lohn Mitarbeiter       | 0.1        |
      | C_LOHN_IN    | C_LOHN_IN    | LOHNABRECHNUNG | 2072-03-31 | PERSONAL      | ja         | inhaber-urinet | inhaber-urinet     | ARCHIVIERT | Geheimnis Lohn Inhaber        | 0.1        |
      | C_VERWORFEN  | C_VERWORFEN  | VERSICHERUNG | 2072-03-04 | NORMAL          | ja         | inhaber-urinet |                    | VERWORFEN  | Police verworfen              | 0.1        |
    Wenn «mitarbeiter-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Police |
    Dann lauten die Treffer im Chat "C_NORMAL, C_LOHN_MA"
    Und erhielt der Reranker im Chat nur Texte von «C_NORMAL, C_LOHN_MA»
    Und enthält kein Text an den Reranker und keine Antwort des Chats "Geheimnis"
    Wenn «treuhaender-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Police |
    Dann lauten die Treffer im Chat "C_NORMAL, C_INTERN, C_INTERN_TXT, C_LOHN_MA, C_LOHN_IN"
    Und erhielt der Reranker im Chat nur Texte von «C_NORMAL, C_INTERN, C_INTERN_TXT, C_LOHN_MA, C_LOHN_IN»

  @req-3.1 @req-2.5
  Szenario: Ein anderer Mandant sieht die Dokumente weder in der Antwort noch im Reranker-Text
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name     | titel    | art          | datum      | text           | abschnitte |
      | C_EIGEN  | C_EIGEN  | VERSICHERUNG | 2072-03-01 | Police Eigen   | 0.1        |
    Und im Mandanten «vhf-gsk» liegen diese Hybrid-Chat-Dokumente:
      | name     | titel    | art          | datum      | text           | abschnitte |
      | C_FREMD  | C_FREMD  |              | 2072-03-01 | Police Fremd   | 0.1        |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Police |
    Dann lauten die Treffer im Chat "C_EIGEN"
    Und erhielt der Reranker im Chat nur Texte von «C_EIGEN»

  @req-3.1 @req-2.3
  Szenario: Fällt das Embedding-Modell aus, liefert das Werkzeug die Volltexttreffer mit dem Hinweis «nur Volltext»
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name      | titel     | art          | datum      | text                     | abschnitte |
      | C_TEXT    | C_TEXT    | VERSICHERUNG | 2072-03-01 | Prämie Quarzsand Police  | 0.1@1-1    |
      | C_VEKTOR  | C_VEKTOR  | VERSICHERUNG | 2072-03-02 | Unfall ohne das Wort     | 0.1        |
    Und das Embedding-Modell antwortet im Chat mit Status 500
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Quarzsand |
    Dann lauten die Treffer im Chat "C_TEXT"
    Und ist die Antwort im Chat degradiert mit dem Hinweis "nur Volltext"
    Und nennt der Treffer «C_TEXT» im Chat die Seiten ""
    Und ging kein Aufruf an den Reranker

  @req-3.1 @req-2.3
  Szenario: Fällt der Reranker aus, liefert das Werkzeug die Volltexttreffer mit dem Hinweis «nur Volltext»
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name      | titel     | art          | datum      | text                     | abschnitte |
      | C_TEXT    | C_TEXT    | VERSICHERUNG | 2072-03-01 | Prämie Quarzsand Police  | 0.1        |
    Und der Reranker hat im Chat kein Token
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Quarzsand |
    Dann lauten die Treffer im Chat "C_TEXT"
    Und ist die Antwort im Chat degradiert mit dem Hinweis "nur Volltext"

  @req-3.1 @req-2.8
  Szenario: Die Hybridsuche im Chat zählt nur mit dem Zweck SUCHE, je eine Zeile für Embedding-Modell und Reranker
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Chat-Dokumente:
      | name      | titel     | art          | datum      | text        | abschnitte |
      | C_POLICE  | C_POLICE  | VERSICHERUNG | 2072-03-01 | Police UVG  | 0.1        |
    Wenn «inhaber-urinet» im Chat mit dokumente_suchen sucht mit:
      | frage | Unfallversicherung |
    Dann lauten die Treffer im Chat "C_POLICE"
    Und hält «ki_verbrauch» im Mandanten «urinet» genau je eine Zeile «SUCHE» für Embedding-Modell und Reranker fest
