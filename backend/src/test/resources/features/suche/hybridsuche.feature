# language: de
@spec-02
Funktionalität: Hybridsuche (Spec 02, Aufgabe 6.4)
  «GET /api/v1/dokumente?modus=hybrid» sucht mit dem Suchbegriff im Volltext (die besten 50) und über die Abschnitte
  (die 50 nächsten nach Kosinus, je Dokument der beste), führt beide Listen mit RRF zusammen und lässt die besten 30
  vom Reranker ordnen (Req 2.2). Fallen Embedding-Modell oder Reranker aus, liefert sie die besten Volltexttreffer in
  derselben Form mit «degradiert» (Req 2.3). Sicht und Mandant gelten in jedem Teil (Req 2.5). Die Antwort hat
  höchstens 30 Treffer ohne Blättern; ohne Modus bleibt die Volltextsuche unverändert (Req 2.7). Jeder Aufruf eines
  Modells mit Antwort ist eine Zeile «ki_verbrauch» mit Zweck SUCHE (Req 2.8).
  Embedding-Modell und Reranker sind WireMocks: Das Embedding-Modell bettet jede Frage gleich ein, ein «naher»
  Abschnitt (Abstand t, kleiner ist näher) liegt nahe bei ihr, ein «ferner» fast senkrecht dazu; der Reranker kehrt die
  Reihenfolge der Texte um. Abschnitte: «t» oder «t@von-bis» je Abschnitt, getrennt durch «;». Fundstelle:
  «Abschnitt/von-bis», «Abschnitt/-» ohne Seiten oder «keine». Alle Daten sind synthetisch; jedes Szenario filtert auf
  seinen eigenen Lauf-Tag.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Hybridsuche ist bereit

  @req-2.2
  Szenario: «Unfallversicherung» findet über die Abschnitte eine Police, die nur «UVG» enthält
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name      | art          | text                         | abschnitte |
      | H_POLICE  | VERSICHERUNG | Police UVG Prämie 2026       | 0.1@1-1    |
      | H_OHNE    | VERTRAG      | Mietvertrag ohne Abschnitte  |            |
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Unfallversicherung |
    Dann ist die Hybridsuche nicht degradiert
    Und enthält die Hybridsuche genau diese Treffer:
      | name     | fundstelle |
      | H_POLICE | 1/1-1      |

  @req-2.2 @req-2.7
  Szenario: Die Antwort folgt dem Reranker, der höchstens 30 Texte erhält, und zeigt höchstens 30 Treffer ohne Blättern
    Angenommen im Mandanten «urinet» liegen 40 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Rechnung |
    Dann ist die Hybridsuche nicht degradiert
    Und erhielt der Reranker 30 Texte mit top_n 30 und der Frage "Rechnung"
    Und lauten die Hybridtreffer der Reihe nach wie die Antwort des Rerankers
    Und hat die Hybridantwort 30 Treffer, gesamt 30, seite 0 und groesse 30

  @req-2.2
  Szenario: Fundstelle mit Seitenbereich, ohne Seitentrenner mit leeren Seiten, ein reiner Volltexttreffer ohne Fundstelle
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name       | art          | text                   | abschnitte      |
      | H_SEITEN   | VERSICHERUNG | Police Seite eins zwei | 0.3@1-1;0.1@2-3 |
      | H_OHNE_S   | VERSICHERUNG | Police ohne Trenner    | 0.2             |
      | H_VOLLTEXT | VERSICHERUNG | Prämie für das Jahr    |                 |
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Prämie |
    Dann ist die Hybridsuche nicht degradiert
    Und enthält die Hybridsuche genau diese Treffer:
      | name       | fundstelle |
      | H_SEITEN   | 2/2-3      |
      | H_OHNE_S   | 1/-        |
      | H_VOLLTEXT | keine      |

  @req-2.2
  Szenario: Filter nach Art und Zeitraum wirken auch auf die Abschnitte
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name      | art     | datum      | text    | abschnitte |
      | H_PASST   | OFFERTE | 2026-03-01 | Garage  | 0.3        |
      | H_ART     | VERTRAG | 2026-03-01 | Garage  | 0.1        |
      | H_ALT     | OFFERTE | 2025-01-15 | Garage  | 0.1        |
      | H_OHNE_D  | OFFERTE |            | Garage  | 0.1        |
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q        | Unfallversicherung |
      | art      | OFFERTE            |
      | datumVon | 2026-01-01         |
    Dann ist die Hybridsuche nicht degradiert
    Und lauten die Hybridtreffer «H_PASST»
    Und erhielt der Reranker nur Texte von «H_PASST»

  @req-2.3 @req-2.7
  Szenariogrundriss: Fällt ein Modell aus, liefert die Suche die besten 30 Volltexttreffer in derselben Form mit «degradiert»
    Angenommen im Mandanten «urinet» liegen 40 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Und das Log der Anwendung wird mitgeschnitten
    Und <stoerung>
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Trikots |
    Dann ist die Hybridsuche degradiert
    Und hat die Hybridantwort 30 Treffer, gesamt 30, seite 0 und groesse 30
    Und sind die Hybridtreffer ohne Fundstelle die ersten 30 der Volltextsuche nach "Trikots" für «inhaber-urinet»
    Und enthält das Log «Trikots» nicht

    Beispiele:
      | stoerung                                                 |
      | das Embedding-Modell antwortet mit Status 500            |
      | das Embedding-Modell antwortet erst nach 3000 ms         |
      | das Embedding-Modell hat kein Token                      |
      | das Embedding-Modell liefert keine Vektoren wie der Dev-Stub |
      | der Reranker antwortet mit Status 500                    |
      | der Reranker antwortet erst nach 3000 ms                 |
      | der Reranker antwortet mit einem Index ausserhalb der Texte |
      | der Reranker hat kein Token                              |
      | der Reranker liefert keine Reihenfolge wie der Dev-Stub  |

  @req-2.5
  Szenario: Jede Rolle findet nach der Rechtematrix nur, was sie sehen darf, und der Reranker erhält nur diese Texte
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name            | art            | vertraulichkeit | bestaetigt | erfasser           | person             | status       | text                | abschnitte |
      | H_NORMAL        | OFFERTE        | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Police normal       | 0.1        |
      | H_INTERN        | OFFERTE        | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Police intern       | 0.1        |
      | H_INTERN_TEXT   | OFFERTE        | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Police nur Volltext |            |
      | H_LOHN_JOEL     | LOHNABRECHNUNG | PERSONAL        | ja         | inhaber-urinet     | mitarbeiter-urinet | ARCHIVIERT   | Police Lohn         | 0.1        |
      | H_LOHN_FAB      | LOHNABRECHNUNG | PERSONAL        | ja         | inhaber-urinet     | inhaber-urinet     | ARCHIVIERT   | Police Lohn         | 0.1        |
      | H_OFFEN_EIGEN   |                | NORMAL          | nein       | mitarbeiter-urinet |                    | EINGANGSKORB | Police offen        | 0.1        |
      | H_OFFEN_FREMD   |                | NORMAL          | nein       | inhaber-urinet     |                    | EINGANGSKORB | Police offen        | 0.1        |
      | H_VERWORFEN     | OFFERTE        | NORMAL          | ja         | inhaber-urinet     |                    | VERWORFEN    | Police verworfen    | 0.1        |
    Dann finden die Benutzer hybrid mit dem Suchbegriff "Police" diese Dokumente, und der Reranker erhält nur deren Texte:
      | benutzer           | treffer                                                                                  | grund                                                 |
      | inhaber-urinet     | H_NORMAL, H_INTERN, H_INTERN_TEXT, H_LOHN_JOEL, H_LOHN_FAB, H_OFFEN_EIGEN, H_OFFEN_FREMD | alles ausser Verworfenem                              |
      | mitarbeiter-urinet | H_NORMAL, H_LOHN_JOEL, H_OFFEN_EIGEN                                                     | NORMAL, eigene PERSONAL, selbst erfasste unbestätigte |
      | treuhaender-urinet | H_NORMAL, H_INTERN, H_INTERN_TEXT, H_LOHN_JOEL, H_LOHN_FAB                               | alle bestätigten, keine unbestätigten                 |

  @req-2.5
  Szenario: Wird die Vertraulichkeit nach dem Einbetten erhöht, verschwindet der Treffer sofort
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name     | art     | text   | abschnitte |
      | H_NORMAL | OFFERTE | Garage | 0.1        |
      | H_BLEIBT | OFFERTE | Garage | 0.2        |
    Wenn «mitarbeiter-urinet» hybrid sucht mit:
      | q | Unfallversicherung |
    Dann lauten die Hybridtreffer «H_NORMAL, H_BLEIBT»
    Wenn die Vertraulichkeit von «H_NORMAL» auf «INTERN» geändert wird
    Und «mitarbeiter-urinet» hybrid sucht mit:
      | q | Unfallversicherung |
    Dann ist die Hybridsuche nicht degradiert
    Und lauten die Hybridtreffer «H_BLEIBT»
    Und erhielt der Reranker nur Texte von «H_BLEIBT»

  @req-2.5
  Szenario: Treffer und Texte an den Reranker kommen nur aus dem eigenen Mandanten
    Angenommen im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name     | art     | text          | abschnitte |
      | H_URINET | OFFERTE | Police Uri    | 0.2        |
    Und im Mandanten «vhf-gsk» liegen diese Hybrid-Dokumente:
      | name     | erfasser                 | text          | abschnitte |
      | H_VHF    | geschaeftsstelle-vhf-gsk | Police Uri    | 0.1        |
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Police |
    Dann lauten die Hybridtreffer «H_URINET»
    Und erhielt der Reranker nur Texte von «H_URINET»
    Wenn «inhaber-vhf-gsk» hybrid sucht mit:
      | q | Police |
    Dann lauten die Hybridtreffer «H_VHF»
    Und erhielt der Reranker nur Texte von «H_VHF»

  @req-2.5
  Szenario: Ohne gültigen Mandanten antwortet die Hybridsuche mit 403 ohne Aufruf eines Modells
    Dann antwortet die Hybridsuche für «ohne-mandant» mit Status 403 und Fehlercode «MANDANT_FEHLT»
    Und antwortet die Hybridsuche für «unbekannt-mandant» mit Status 403 und Fehlercode «MANDANT_FEHLT»

  @req-2.6
  Szenario: Mit verzögerten Modellen antwortet die Suche innert der Zeitgrenzen mit «degradiert» (Teilnachweis, Messung auf Stufe B)
    Angenommen im Mandanten «urinet» liegen 40 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Und das Embedding-Modell antwortet erst nach 500 ms
    Und der Reranker antwortet erst nach 5000 ms
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Trikots |
    Dann ist die Hybridsuche degradiert
    Und antwortet die Hybridsuche innert 1500 ms über der Summe der Zeitgrenzen

  @req-2.7
  Szenario: Ungültige Angaben im Modus hybrid ergeben 400
    Dann antwortet die Suche für «inhaber-urinet» so:
      | parameter           | wert                    | status | grund                         |
      | modus               | semantisch              | 400    | unbekannter Modus             |
      | modus,q             | Hybrid,Rechnung         | 400    | Modus mit Grossbuchstaben     |
      | modus               | hybrid                  | 400    | hybrid ohne Suchbegriff       |
      | modus,q             | hybrid,{leer}           | 400    | hybrid mit leerem Suchbegriff |
      | modus,q,sortierung  | hybrid,Rechnung,DATUM   | 400    | Reihenfolge des Rerankers     |
      | modus,q,seite       | hybrid,Rechnung,0       | 400    | kein Blättern                 |
      | modus,q,groesse     | hybrid,Rechnung,30      | 400    | feste Grösse                  |
      | modus,q,sortierung  | hybrid,Rechnung,RELEVANZ | 200   | Relevanz ist erlaubt          |
      | modus,q             | volltext,Rechnung       | 200    | Volltext ausdrücklich         |

  @req-2.7
  Szenario: Ohne Modus bleibt die Volltextsuche mit Blättern und Gesamtzahl unverändert
    Angenommen im Mandanten «urinet» liegen 5 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Wenn «inhaber-urinet» im Modus "volltext" sucht mit:
      | q       | Rechnung |
      | groesse | 2        |
      | seite   | 2        |
    Dann ist die Gesamtzahl der Volltextsuche 5 mit 1 Treffern auf der Seite
    Und gleicht die Antwort ohne Modus Byte für Byte der mit Modus «volltext» für «inhaber-urinet» mit:
      | q       | Rechnung |
      | groesse | 2        |
      | seite   | 1        |

  @req-2.8
  Szenario: Jede Hybridsuche hält Embedding und Reranker in «ki_verbrauch» des Mandanten fest, die Volltextsuche nichts
    Angenommen im Mandanten «urinet» liegen 3 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Wenn «inhaber-urinet» im Modus "volltext" sucht mit:
      | q | Rechnung |
    Dann hält «ki_verbrauch» im Mandanten «urinet» keine neue Zeile fest
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Rechnung |
    Dann hält «ki_verbrauch» im Mandanten «urinet» je eine Zeile «SUCHE» für das Embedding-Modell und den Reranker fest
    Und hält «ki_verbrauch» im Mandanten «vhf-gsk» keine neue Zeile fest

  @req-2.8
  Szenario: Eine ungültige Antwort mit Tokens ist eine Zeile, eine Zeitüberschreitung keine
    Angenommen im Mandanten «urinet» liegen 3 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Und der Reranker antwortet mit einem Index ausserhalb der Texte
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Rechnung |
    Dann ist die Hybridsuche degradiert
    Und hält «ki_verbrauch» im Mandanten «urinet» je eine Zeile «SUCHE» für das Embedding-Modell und den Reranker mit 7 Tokens fest

  @req-2.8
  Szenario: Bei einer Zeitüberschreitung des Rerankers bleibt nur die Zeile des Embedding-Modells
    Angenommen im Mandanten «urinet» liegen 3 Hybrid-Dokumente «H_SERIE» mit dem Text "Rechnung Trikots" und je einem nahen Abschnitt
    Und der Reranker antwortet erst nach 3000 ms
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Rechnung |
    Dann ist die Hybridsuche degradiert
    Und hält «ki_verbrauch» im Mandanten «urinet» nur die Zeile «SUCHE» des Embedding-Modells fest
