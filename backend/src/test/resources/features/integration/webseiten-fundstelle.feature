# language: de
@spec-03 @webseiten-fundstelle
Funktionalität: Fundstelle in Suchtreffern der Webseiten-Schnittstelle (Aufgabe 11.13)
  Die Hybridsuche gibt je Dokument den besten Abschnitt mit; «GET /public/v1/suche» nennt daraus die PDF-Seite als
  «fundstelle {seite}» (erste Seite des Abschnitts, ab 1). Die Angabe fehlt, wenn die Seite unbekannt ist: Treffer nur
  aus dem Volltext, Abschnitt ohne Seitenbereich oder eine Seite ausserhalb von «dokument.seiten». Die Antwort trägt
  weder den Text des Abschnitts noch eine Seiten-URL; die Webseite bildet den Link aus der Dokument-ID mit «#page=<n>».
  Alle Dokumente und Texte sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Webseiten-Lesezugang «oeffentlich» der Webseite «probe-fundstelle» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und im Mandanten «urinet» sind diese Reihen angelegt:
      | code        | name           | aktiv |
      | WEBTEST_BUL | Probe-Bulletin | true  |
    Und im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | art         | ausgabe | jahr | zugriff     | seiten | text                                |
      | f1   | WEBTEST_BUL | 1       | 2024 | oeffentlich | 12     | Der Kirschkuchen im Verbandsheft    |
      | f2   | WEBTEST_BUL | 2       | 2024 | oeffentlich | 12     | Kirschkuchen ohne Seitentrenner     |
      | f3   | WEBTEST_BUL | 3       | 2024 | oeffentlich | 12     | Kirschkuchen nur im Volltext        |
      | f4   | WEBTEST_BUL | 4       | 2024 | oeffentlich | 3      | Kirschkuchen mit veralteter Seite   |
      | f5   | WEBTEST_BUL | 5       | 2024 | mitglieder  | 12     | Kirschkuchen nur für Mitglieder     |
    Und das Embedding-Modell bettet jede Frage der Webseiten-Suche gleich ein

  @req-5.7
  Szenario: Der Treffer nennt die erste Seite des besten Abschnitts, ohne den Text des Abschnitts
    Angenommen das Dokument «f1» hat diese Abschnitte nahe der Frage:
      | nr | seiten |
      | 1  | 7-9    |
      | 2  | 3-4    |
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Kirschkuchen» mit der Stufe «oeffentlich» aufruft
    Dann trägt der Treffer «f1» die Fundstelle mit Seite 7
    Und nennt die Antwort den Text der Abschnitte nicht
    Und haben alle Einträge nur die Felder «id,titel,reihe,ausgabe,jahr,datum,zugriff,seiten,klassifikationen,auszug,fundstelle,rang»
    Und steht in der Lese-Antwort weder «http» noch «seiteBis»

  @req-5.7
  Szenario: Ohne Seitenbereich und bei Treffern nur aus dem Volltext fehlt die Fundstelle
    Angenommen das Dokument «f2» hat diese Abschnitte nahe der Frage:
      | nr | seiten |
      | 1  | -      |
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Kirschkuchen» mit der Stufe «oeffentlich» aufruft
    Dann trägt der Treffer «f2» keine Fundstelle
    Und trägt der Treffer «f3» keine Fundstelle

  @req-5.7
  Szenario: Eine Seite ausserhalb von dokument.seiten ergibt keine Fundstelle
    Angenommen das Dokument «f4» hat diese Abschnitte nahe der Frage:
      | nr | seiten |
      | 1  | 5-6    |
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Kirschkuchen» mit der Stufe «oeffentlich» aufruft
    Dann trägt der Treffer «f4» keine Fundstelle

  @req-5.7 @req-5.3
  Szenario: Die Fundstelle eines Dokuments über der Stufe der Anfrage erscheint nie
    Angenommen das Dokument «f5» hat diese Abschnitte nahe der Frage:
      | nr | seiten |
      | 1  | 2-2    |
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Kirschkuchen» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort nicht das Dokument «f5»
