# language: de
@spec-03 @webseiten-lesen
Funktionalität: Lesende Endpunkte der Webseiten-Schnittstelle (Aufgabe 11.2)
  Liste, Suche, Details, Datei-Link, Filter und Umleitung unter /public/v1. Jede Abfrage trägt den festen Filter
  (archiviert, veröffentlicht, Vertraulichkeit NORMAL, Zugriffsstufe bis zur Stufe der Anfrage); ein Dokument ausserhalb
  davon gibt es für die Anfrage nicht. Alle Dokumente, Reihen und Texte sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und der Webseiten-Lesezugang «vorstand» der Webseite «probe-lesen» im Mandanten «urinet» hat die höchste Stufe «vorstand»
    Und der Webseiten-Lesezugang «mitglieder» der Webseite «probe-lesen» im Mandanten «urinet» hat die höchste Stufe «mitglieder»
    Und der Webseiten-Lesezugang «oeffentlich» der Webseite «probe-lesen» im Mandanten «urinet» hat die höchste Stufe «oeffentlich»
    Und im Mandanten «urinet» sind diese Reihen angelegt:
      | code        | name            | aktiv |
      | WEBTEST_BUL | Probe-Bulletin  | true  |
      | WEBTEST_MER | Probe-Merkblatt | true  |
      | WEBTEST_ALT | Probe-Altreihe  | false |
      | WEBTEST_VOR | Probe-Vorstand  | true  |
    Und im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | art         | ausgabe | jahr | datum      | zugriff     | seiten | text                                  |
      | b1   | WEBTEST_BUL | 1       | 2024 | 2024-03-01 | oeffentlich | 12     | Der Zitronenkuchen im Verbandsheft    |
      | b2   | WEBTEST_BUL | 2       | 2024 | 2024-06-01 | oeffentlich | 8      | Ein Bericht über die Wanderwege       |
      | b3   | WEBTEST_BUL | 1       | 2025 | 2025-03-01 | mitglieder  | 10     | Zitronenkuchen nur für Mitglieder     |
      | m1   | WEBTEST_MER | -       | 2025 | -          | oeffentlich | 2      | Merkblatt zum Zitronenkuchen          |
      | v1   | WEBTEST_VOR | 7       | 2025 | 2025-09-01 | vorstand    | 4      | Zitronenkuchen im Vorstandsprotokoll  |
      | a1   | WEBTEST_ALT | 3       | 2020 | 2020-01-01 | oeffentlich | 6      | Altes Heft vom Zitronenkuchen         |

  @req-5.1 @req-5.3
  Szenariogrundriss: Die Liste liefert die Dokumente bis zur Stufe der Anfrage
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?pro_seite=100» mit der Stufe «<stufe>» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und nennt die Antwort genau die Dokumente «<dokumente>»

    Beispiele:
      | stufe       | dokumente         |
      | oeffentlich | b1,b2,m1,a1       |
      | mitglieder  | b1,b2,b3,m1,a1    |
      | vorstand    | b1,b2,b3,m1,v1,a1 |

  @req-5.1 @req-5.3
  Szenario: Ohne gemeldete Stufe gilt oeffentlich, auch bei einem Schlüssel mit der Stufe vorstand
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?pro_seite=100» ohne Stufe aufruft
    Dann nennt die Antwort genau die Dokumente «b1,b2,m1,a1»

  @req-5.1
  Szenariogrundriss: Die Liste filtert nach Reihe, Jahr und Ausgabe
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?<parameter>» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Dokumente «<dokumente>»

    Beispiele:
      | parameter                              | dokumente   |
      | reihe=WEBTEST_BUL                      | b1,b2,b3    |
      | reihe=WEBTEST_BUL&jahr=2024            | b1,b2       |
      | reihe=WEBTEST_BUL&jahr=2024&ausgabe=2  | b2          |
      | jahr=2025                              | b3,m1,v1    |
      | ausgabe=1                              | b1,b3       |
      | reihe=WEBTEST_MER&jahr=2024            |             |

  @req-5.1
  Szenario: Die Liste blättert und sortiert, die Antwort nennt Seite, Grösse und Gesamtzahl
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?reihe=WEBTEST_BUL&pro_seite=2&seite=2&sortierung=datum_auf» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort die Dokumente «b3» in dieser Reihenfolge
    Und nennt die Antwort die Seite 2 mit 2 Einträgen je Seite und der Gesamtzahl 3
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?reihe=WEBTEST_BUL&sortierung=datum_ab» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort die Dokumente «b3,b2,b1» in dieser Reihenfolge
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?reihe=WEBTEST_BUL&sortierung=titel» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort die Dokumente «b1,b2,b3» in dieser Reihenfolge

  @req-5.1
  Szenariogrundriss: Ungültige Parameter der Liste ergeben 400 ohne den Wert zu nennen
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?<parameter>» mit der Stufe «vorstand» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und steht in der Lese-Antwort weder «<wert>» noch «meldung»

    Beispiele:
      | parameter                     | wert        |
      | seite=0                       | seite=0     |
      | seite=abc                     | abc         |
      | pro_seite=101                 | 101         |
      | pro_seite=0                   | pro_seite=0 |
      | sortierung=zufall             | zufall      |
      | jahr=20x4                     | 20x4        |
      | reihe=kleinbuchstaben         | kleinbuch   |

  @req-5.3
  Szenario: Feste Parameter wie zugriff, veroeffentlicht und status ändern den Filter nicht
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?pro_seite=100&zugriff=vorstand&veroeffentlicht=false&status=NEU&vertraulichkeit=PERSONAL&mandant=vhf-gsk&seit=0&max=1&referenz=x&groesse=1&url=x&id=x» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort genau die Dokumente «b1,b2,m1,a1»

  @req-5.3 @req-3.6 @req-3.7
  Szenariogrundriss: Die Filterachse «<achse>» hält das Dokument aus Liste, Suche, Details, Filter und Umleitung heraus
    Angenommen im Mandanten «urinet» sind diese Reihen angelegt:
      | code        | name        | aktiv |
      | WEBTEST_XXX | Probe-Achse | true  |
    Und im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff   | veroeffentlicht   | status   | vertraulichkeit   | bestaetigt   | text                      |
      | ok   | WEBTEST_VOR | vorstand  | true              | ARCHIVIERT | NORMAL          | ja           | Achsentest Rhabarber      |
      | x    | WEBTEST_XXX | <zugriff> | <veroeffentlicht> | <status> | <vertraulichkeit> | <bestaetigt> | Achsentest Rhabarber     |
    Und die alten URLs «/alt/ok» und «/alt/x» führen im Mandanten «urinet» zu den Dokumenten «ok» und «x»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?pro_seite=100&reihe=WEBTEST_XXX» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Dokumente «»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/suche?q=Rhabarber» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Dokumente «ok»
    Wenn der Webseiten-Lesezugang «vorstand» das Dokument «x» mit der Stufe «vorstand» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/filter» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort nicht die Reihe «WEBTEST_XXX»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/umleitung?url=/alt/x» mit der Stufe «vorstand» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/umleitung?url=/alt/ok» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort die Dokument-ID von «ok»

    Beispiele:
      | achse                    | status       | vertraulichkeit | bestaetigt | zugriff          | veroeffentlicht |
      | Status NEU               | NEU          | NORMAL          | ja         | oeffentlich      | true            |
      | Status EINGANGSKORB      | EINGANGSKORB | NORMAL          | ja         | oeffentlich      | true            |
      | Status ARCHIVIERUNG      | ARCHIVIERUNG | NORMAL          | ja         | oeffentlich      | true            |
      | Status VERWORFEN         | VERWORFEN    | NORMAL          | ja         | oeffentlich      | true            |
      | Status FEHLER            | FEHLER       | NORMAL          | ja         | oeffentlich      | true            |
      | Vertraulichkeit INTERN   | ARCHIVIERT   | INTERN          | ja         | oeffentlich      | true            |
      | Vertraulichkeit PERSONAL | ARCHIVIERT   | PERSONAL        | ja         | oeffentlich      | true            |
      | Art nicht bestätigt      | ARCHIVIERT   | NORMAL          | nein       | oeffentlich      | true            |
      | zurückgezogen            | ARCHIVIERT   | NORMAL          | ja         | oeffentlich      | false           |
      | veroeffentlicht Text     | ARCHIVIERT   | NORMAL          | ja         | oeffentlich      | "true"          |
      | veroeffentlicht fehlt    | ARCHIVIERT   | NORMAL          | ja         | oeffentlich      | -               |
      | veroeffentlicht null     | ARCHIVIERT   | NORMAL          | ja         | oeffentlich      | null            |
      | veroeffentlicht Zahl 1   | ARCHIVIERT   | NORMAL          | ja         | oeffentlich      | 1               |
      | veroeffentlicht Liste    | ARCHIVIERT   | NORMAL          | ja         | oeffentlich      | [true]          |
      | zugriff fehlt            | ARCHIVIERT   | NORMAL          | ja         | -                | true            |
      | zugriff unbekannt        | ARCHIVIERT   | NORMAL          | ja         | geheim           | true            |
      | zugriff Umlaut           | ARCHIVIERT   | NORMAL          | ja         | "öffentlich"     | true            |
      | zugriff null             | ARCHIVIERT   | NORMAL          | ja         | null             | true            |
      | zugriff Zahl 1           | ARCHIVIERT   | NORMAL          | ja         | 1                | true            |
      | zugriff Liste            | ARCHIVIERT   | NORMAL          | ja         | ["oeffentlich"]  | true            |

  @req-5.3 @req-5.5
  Szenariogrundriss: Ein Dokument über der Stufe der Anfrage ist in allen Endpunkten unsichtbar
    Angenommen die alten URLs «/alt/b3» und «/alt/v1» führen im Mandanten «urinet» zu den Dokumenten «b3» und «v1»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/suche?q=Zitronenkuchen» mit der Stufe «<stufe>» aufruft
    Dann nennt die Antwort genau die Dokumente «<suche>»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/filter» mit der Stufe «<stufe>» aufruft
    Dann nennt die Antwort genau die Reihen «<reihen>»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/umleitung?url=/alt/b3» mit der Stufe «<stufe>» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status <status_b3>
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/umleitung?url=/alt/v1» mit der Stufe «<stufe>» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status <status_v1>

    Beispiele:
      | stufe       | suche             | reihen                                  | status_b3 | status_v1 |
      | oeffentlich | b1,m1,a1          | WEBTEST_BUL,WEBTEST_MER                 | 404       | 404       |
      | mitglieder  | b1,b3,m1,a1       | WEBTEST_BUL,WEBTEST_MER                 | 200       | 404       |
      | vorstand    | b1,b3,m1,v1,a1    | WEBTEST_BUL,WEBTEST_MER,WEBTEST_VOR     | 200       | 200       |

  @req-5.5
  Szenario: Details eines Dokuments über der Stufe sind 404 wie bei einem unbekannten Dokument
    Wenn der Webseiten-Lesezugang «vorstand» das Dokument «v1» mit der Stufe «mitglieder» abruft
    Und der Webseiten-Lesezugang «vorstand» ein unbekanntes Dokument mit der Stufe «mitglieder» abruft
    Dann ist die Lese-Antwort für das Dokument gleich der für ein unbekanntes Dokument
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente/kein-uuid» mit der Stufe «vorstand» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-5.1 @req-5.11
  Szenario: Die Details nennen die Antwortfelder mit Auszug und sonst nichts
    Wenn der Webseiten-Lesezugang «oeffentlich» das Dokument «b1» mit der Stufe «oeffentlich» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und hat die Antwort die Felder «id,titel,reihe,ausgabe,jahr,datum,zugriff,seiten,auszug» und nur diese
    Und nennt die Antwort die Reihe «WEBTEST_BUL» mit dem Namen «Probe-Bulletin», die Ausgabe «1», das Jahr 2024, das Datum «2024-03-01», die Zugriffsstufe «oeffentlich» und 12 Seiten
    Und beginnt der Auszug mit «Der Zitronenkuchen»

  @req-5.11
  Szenario: Antworten tragen nur Felder des Vertrags, nie Dateiname, Vertraulichkeit, Status, Partner oder eine URL einer Seite
    Angenommen das Dokument «b1» trägt den Dateinamen «geheimer-dateiname-4711.pdf» und den Partner «Geheimpartner AG»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/dokumente?pro_seite=100» mit der Stufe «oeffentlich» aufruft
    Dann haben alle Einträge nur die Felder «id,titel,reihe,ausgabe,jahr,datum,zugriff,seiten»
    Und steht in der Lese-Antwort weder «geheimer-dateiname» noch «Geheimpartner»
    Und steht in der Lese-Antwort weder «http» noch «vertraulichkeit»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Zitronenkuchen» mit der Stufe «oeffentlich» aufruft
    Dann haben alle Einträge nur die Felder «id,titel,reihe,ausgabe,jahr,datum,zugriff,seiten,auszug,fundstelle,rang»
    Und steht in der Lese-Antwort weder «geheimer-dateiname» noch «Geheimpartner»
    Und steht in der Lese-Antwort weder «http» noch «status»
    Wenn der Webseiten-Lesezugang «oeffentlich» das Dokument «b1» mit der Stufe «oeffentlich» abruft
    Dann steht in der Lese-Antwort weder «geheimer-dateiname» noch «Geheimpartner»
    Und steht in der Lese-Antwort weder «http» noch «veroeffentlicht»

  @req-5.1
  Szenario: Die Suche findet Dokumente im Text, ohne Dateinamen, und nennt Rang und Rückfall
    Angenommen das Dokument «b2» trägt den Dateinamen «ausschreibungsunikat.pdf» und den Partner «-»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Wanderwege» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort genau die Dokumente «b2»
    Und hat die Suche den Rang 1 und eine Angabe zum Rückfall
    Und enthält der Auszug des Treffers «b2» das Wort «Wanderwege» und höchstens 300 Zeichen
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=ausschreibungsunikat» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort genau die Dokumente «»

  @req-5.1
  Szenariogrundriss: Die Suche filtert nach Reihe, Jahr und Ausgabe
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/suche?q=Zitronenkuchen&<parameter>» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Dokumente «<dokumente>»

    Beispiele:
      | parameter                    | dokumente |
      | reihe=WEBTEST_BUL            | b1,b3     |
      | jahr=2025                    | b3,m1,v1  |
      | reihe=WEBTEST_BUL&ausgabe=1  | b1,b3     |
      | reihe=WEBTEST_MER&jahr=2024  |           |

  @req-5.1
  Szenariogrundriss: Eine ungültige Suche ergibt 400
    Wenn der Webseiten-Lesezugang «oeffentlich» «<pfad>» mit der Stufe «oeffentlich» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

    Beispiele:
      | pfad                                     |
      | /public/v1/suche                         |
      | /public/v1/suche?q=                      |
      | /public/v1/suche?q=%20%20                |
      | /public/v1/suche?q=wort&jahr=zwanzig     |
      | /public/v1/suche?q=wort&reihe=klein      |

  @req-5.1
  Szenario: Ein zu langer Suchbegriff ergibt 400
    Wenn der Webseiten-Lesezugang «oeffentlich» die Suche mit einem Begriff von 201 Zeichen aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

  @req-5.4 @req-13.1
  Szenario: Der Datei-Link eines öffentlichen Dokuments gilt höchstens 5 Minuten und wird mit gekürzter IP protokolliert
    Angenommen das Dokument «b1» hat eine Datei
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «oeffentlich» die Datei des Dokuments «b1» mit der Stufe «oeffentlich» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und nennt die Antwort eine Adresse der Datei, die höchstens 5 Minuten gilt
    Und steht im Zugriffsprotokoll von «urinet» genau ein neuer Eintrag HERUNTERLADEN für «b1» von der Adresse «192.0.2.0» ohne weitere Angaben

  @req-5.4 @req-13.1
  Szenario: Der Datei-Link eines öffentlichen Dokuments gilt auch bei höherer gemeldeter Stufe
    Angenommen das Dokument «b1» hat eine Datei
    Wenn der Webseiten-Lesezugang «vorstand» die Datei des Dokuments «b1» mit der Stufe «vorstand» und der Adresse «2001:db8:1:2::5» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 200

  @req-5.4 @req-5.5
  Szenariogrundriss: Ohne Datei bleiben unveröffentlichte und unsichtbare Dokumente 404 (Stufen mitglieder und vorstand streamt Aufgabe 11.8, siehe webseiten-datei.feature)
    Angenommen im Mandanten «urinet» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff     | veroeffentlicht | text     |
      | z    | WEBTEST_BUL | <zugriff>   | <veroeffentlicht> | Datei  |
    Und das Dokument «z» hat eine Datei
    Und der Stand des Zugriffsprotokolls von «urinet» ist für die Webseite gemerkt
    Wenn der Webseiten-Lesezugang «vorstand» die Datei des Dokuments «z» mit der Stufe «vorstand» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und steht im Zugriffsprotokoll von «urinet» kein neuer Eintrag

 Beispiele:
  | zugriff | veroeffentlicht |
  | oeffentlich | false |
  | - | true |

  @req-5.4
  Szenario: Der Datei-Link ist 404, wenn das Dokument keine Datei hat, und ein unbekanntes Dokument ist 404
    Wenn der Webseiten-Lesezugang «oeffentlich» die Datei des Dokuments «b2» mit der Stufe «oeffentlich» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-5.8
  Szenariogrundriss: Der Filter nennt nur Reihen mit sichtbaren Dokumenten bis zur Stufe
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/filter» mit der Stufe «<stufe>» aufruft
    Dann nennt die Antwort genau die Reihen «<reihen>»

    Beispiele:
      | stufe       | reihen                              |
      | oeffentlich | WEBTEST_BUL,WEBTEST_MER             |
      | mitglieder  | WEBTEST_BUL,WEBTEST_MER             |
      | vorstand    | WEBTEST_BUL,WEBTEST_MER,WEBTEST_VOR |

  @req-5.8
  Szenario: Der Filter nennt Code und Name, nach Name sortiert, ohne inaktive Reihen und ohne Reihen ohne Dokumente
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/filter» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort die Reihen «WEBTEST_BUL:Probe-Bulletin,WEBTEST_MER:Probe-Merkblatt,WEBTEST_VOR:Probe-Vorstand» in dieser Reihenfolge

  @req-5.10
  Szenariogrundriss: Die Umleitung liefert die Dokument-ID zur alten URL, auch mit Schema, Host, Anfrage und Fragment
    Angenommen die alten URLs «/alt/bulletin/2024/heft-1.pdf» und «/alt/zweite seite» führen im Mandanten «urinet» zu den Dokumenten «b1» und «b2»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/umleitung?url=<url>» mit der Stufe «oeffentlich» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 200
    Und nennt die Antwort die Dokument-ID von «<dokument>»

    Beispiele:
      | url                                                            | dokument |
      | /alt/bulletin/2024/heft-1.pdf                                  | b1       |
      | https%3A%2F%2Falt.example%2Falt%2Fbulletin%2F2024%2Fheft-1.pdf%3Fx%3D1%23seite%3D3 | b1       |
      | /alt//bulletin/2024/heft-1.pdf/                                | b1       |
      | /alt/bulletin/2024/heft%2D1.pdf                                | b1       |
      | /alt/zweite%20seite                                            | b2       |

  @req-5.10
  Szenariogrundriss: Die Umleitung ergibt 404 für unbekannte URLs und 400 für ungültige
    Angenommen die alten URLs «/alt/bulletin/2024/heft-1.pdf» und «/alt/zweite seite» führen im Mandanten «urinet» zu den Dokumenten «b1» und «b2»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/umleitung<anfrage>» mit der Stufe «oeffentlich» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status <status> und Fehlercode «<code>»
    Und steht in der Lese-Antwort weder «unbekannt» noch «meldung»

    Beispiele:
      | anfrage                                   | status | code             |
      | ?url=/alt/unbekannt                       | 404    | NICHT_GEFUNDEN   |
      | ?url=/ALT/bulletin/2024/heft-1.pdf        | 404    | NICHT_GEFUNDEN   |
      |                                           | 400    | ANFRAGE_UNGUELTIG |
      | ?url=                                     | 400    | ANFRAGE_UNGUELTIG |
      | ?url=/alt/zweite%25ZZseite                  | 400    | ANFRAGE_UNGUELTIG |
      | ?url=/alt/zweite%2500seite                  | 400    | ANFRAGE_UNGUELTIG |
      | ?url=/alt/bulletin/2024/heft-1.pdf%3Bx    | 404    | NICHT_GEFUNDEN    |
      | ?url=kein-pfad                            | 400    | ANFRAGE_UNGUELTIG |

  @req-5.2 @req-5.10 @req-1.4 @req-9.1
  Szenario: Die Zuordnung eines anderen Mandanten wird nie geliefert
    Angenommen im Mandanten «vhf-gsk» sind diese Reihen angelegt:
      | code        | name                  | aktiv |
      | WEBTEST_VHF | Fremdmandanten-Reihe  | true  |
    Angenommen im Mandanten «vhf-gsk» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff     | text           |
      | x    | WEBTEST_VHF | oeffentlich | Fremdmandant   |
    Und das Dokument «x» hat eine Datei
    Und die alten URLs «/alt/fremd» und «/alt/fremd2» führen im Mandanten «vhf-gsk» zu den Dokumenten «x» und «x»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/umleitung?url=/alt/fremd» mit der Stufe «vorstand» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «vorstand» das Dokument «x» mit der Stufe «vorstand» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/suche?q=Fremdmandant» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Dokumente «»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/dokumente?pro_seite=100&mandant=vhf-gsk» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Dokumente «b1,b2,b3,m1,v1,a1»

  @req-1.4 @req-9.1
  Szenario: Datei und Filter eines anderen Mandanten werden nie geliefert
    Angenommen im Mandanten «vhf-gsk» sind diese Reihen angelegt:
      | code        | name                  | aktiv |
      | WEBTEST_VHF | Fremdmandanten-Reihe  | true  |
    Und im Mandanten «vhf-gsk» liegen diese Webseiten-Dokumente:
      | name | art         | zugriff     | text           |
      | x    | WEBTEST_VHF | oeffentlich | Fremdmandant   |
    Und das Dokument «x» hat eine Datei
    Wenn der Webseiten-Lesezugang «vorstand» die Datei des Dokuments «x» mit der Stufe «vorstand» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «vorstand» «/public/v1/filter» mit der Stufe «vorstand» aufruft
    Dann nennt die Antwort genau die Reihen «WEBTEST_BUL,WEBTEST_MER,WEBTEST_VOR»

  @req-9.4
  Szenario: Ein öffentlicher Schlüssel sieht keine Dokumente höherer Zugriffsstufe
    Angenommen die alten URLs «/alt/mitglieder» und «/alt/vorstand» führen im Mandanten «urinet» zu den Dokumenten «b3» und «v1»
    Und das Dokument «b3» hat eine Datei
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/dokumente?pro_seite=100» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort genau die Dokumente «b1,b2,m1,a1»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/suche?q=Zitronenkuchen» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort genau die Dokumente «b1,m1,a1»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/filter» mit der Stufe «oeffentlich» aufruft
    Dann nennt die Antwort genau die Reihen «WEBTEST_BUL,WEBTEST_MER»
    Wenn der Webseiten-Lesezugang «oeffentlich» das Dokument «b3» mit der Stufe «oeffentlich» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «oeffentlich» die Datei des Dokuments «b3» mit der Stufe «oeffentlich» und der Adresse «192.0.2.77» abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/umleitung?url=/alt/mitglieder» mit der Stufe «oeffentlich» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn der Webseiten-Lesezugang «oeffentlich» «/public/v1/umleitung?url=/alt/vorstand» mit der Stufe «oeffentlich» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-5.9
  Szenariogrundriss: Eine gemeldete Stufe über der Stufe des Schlüssels ergibt 403, ein unbekannter Wert 400 – an jedem Endpunkt
    Wenn der Webseiten-Lesezugang «<schluessel>» «<pfad>» mit der Stufe «<stufe>» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status <status> und Fehlercode «<code>»
    Und steht in der Lese-Antwort weder «<stufe>» noch «meldung»

    Beispiele:
      | schluessel  | pfad                                  | stufe      | status | code              |
      | oeffentlich | /public/v1/dokumente                  | mitglieder | 403    | NICHT_ERLAUBT     |
      | mitglieder  | /public/v1/suche?q=wort               | vorstand   | 403    | NICHT_ERLAUBT     |
      | mitglieder  | /public/v1/filter                     | vorstand   | 403    | NICHT_ERLAUBT     |
      | oeffentlich | /public/v1/umleitung?url=/alt/x       | vorstand   | 403    | NICHT_ERLAUBT     |
      | oeffentlich | /public/v1/dokumente/kein-uuid        | mitglieder | 403    | NICHT_ERLAUBT     |
      | vorstand    | /public/v1/dokumente                  | admin-4711 | 400    | ANFRAGE_UNGUELTIG |
      | vorstand    | /public/v1/suche?q=wort               | Mitglieder | 400    | ANFRAGE_UNGUELTIG |

  @req-5.2 @req-15.3
  Szenariogrundriss: Ohne gültigen Lese-Schlüssel gibt es nichts, ein Erfassungs-Schlüssel genügt nicht
    Angenommen der Webseiten-Erfassungszugang «erfassung» der Webseite «probe-lesen» im Mandanten «urinet» ist angelegt
    Wenn <zugang> «<pfad>» aufruft
    Dann antwortet die Lese-Schnittstelle mit Status <status> und Fehlercode «<code>»

    Beispiele:
      | zugang                                    | pfad                              | status | code             |
      | ohne Schlüssel                            | /public/v1/dokumente              | 401    | NICHT_ANGEMELDET |
      | mit dem ungültigen Schlüssel «ua_x_y»     | /public/v1/suche?q=wort           | 401    | NICHT_ANGEMELDET |
      | mit dem Erfassungs-Zugang «erfassung»     | /public/v1/dokumente              | 403    | NICHT_ERLAUBT    |
      | mit dem Erfassungs-Zugang «erfassung»     | /public/v1/filter                 | 403    | NICHT_ERLAUBT    |

  @req-15.4
  Szenariogrundriss: Jede Antwort ist nicht zwischenzuspeichern und ohne CORS
    Wenn der Webseiten-Lesezugang «oeffentlich» «<pfad>» mit der Stufe «oeffentlich» aufruft
    Dann trägt die Lese-Antwort den Header «Cache-Control» mit dem Wert «no-store»
    Und trägt die Lese-Antwort keinen Header «Access-Control-Allow-Origin»

    Beispiele:
      | pfad                              |
      | /public/v1/dokumente              |
      | /public/v1/suche?q=wort           |
      | /public/v1/filter                 |
      | /public/v1/umleitung?url=/nichts  |
      | /public/v1/dokumente/kein-uuid    |

  @req-4.3 @req-5.10
  Szenario: CSV-Import ist idempotent, mandantensicher und im Trockenlauf unverändert
    Angenommen die CSV-Datei ordnet die URL «/alt/import» dem Dokument «b1» zu
    Wenn der Import alter Links im Mandanten «urinet» als Trockenlauf gestartet wird
    Dann meldet der alte-Link-Import die Zeile 2 als «ANGELEGT»
    Und ist die importierte alte URL im Mandanten «urinet» nicht gespeichert
    Und ist die importierte alte URL im Mandanten «vhf-gsk» nicht zugeordnet
    Und ist der alte-Link-Import nicht im Zugriffsprotokoll erfasst
    Wenn der Import alter Links im Mandanten «urinet» ausgeführt wird
    Dann meldet der alte-Link-Import die Zeile 2 als «ANGELEGT»
    Und ist die importierte alte URL im Mandanten «urinet» einmal gespeichert
    Und ist die importierte alte URL im Mandanten «vhf-gsk» nicht zugeordnet
    Und ist der alte-Link-Import einmal im Zugriffsprotokoll erfasst
    Wenn der Import alter Links im Mandanten «urinet» ausgeführt wird
    Dann meldet der alte-Link-Import die Zeile 2 als «SCHON_VORHANDEN»
    Und ist die importierte alte URL im Mandanten «urinet» einmal gespeichert
    Und ist der alte-Link-Import einmal im Zugriffsprotokoll erfasst
    Wenn die öffentliche Umleitung die importierte alte URL abruft
    Dann nennt die Umleitung die Dokument-ID von «b1»

  @req-4.3 @req-5.10
  Szenario: Importiert unveröffentlichte Ziele, ohne sie über die Umleitung sichtbar zu machen
    Angenommen das Dokument «b1» ist unveröffentlicht
    Und die CSV-Datei ordnet die URL «/alt/unveröffentlicht» dem Dokument «b1» zu
    Wenn der Import alter Links im Mandanten «urinet» ausgeführt wird
    Dann meldet der alte-Link-Import die Zeile 2 als «ANGELEGT»
    Und ist die importierte alte URL im Mandanten «urinet» einmal gespeichert
    Wenn die öffentliche Umleitung die importierte alte URL abruft
    Dann antwortet die Lese-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-4.3 @req-5.10
  Szenario: Ein Zieldokument eines anderen Mandanten wird abgelehnt und nichts geschrieben
    Angenommen die CSV-Datei ordnet die URL «/alt/fremd» dem Dokument «b1» zu
    Wenn der Import alter Links im Mandanten «vhf-gsk» ausgeführt wird
    Dann meldet der alte-Link-Import die Zeile 2 als «ABGELEHNT»
    Und nennt der alte-Link-Import den Grund «Zieldokument existiert nicht im Mandanten»
    Und ist die importierte alte URL im Mandanten «urinet» nicht gespeichert
    Und ist die importierte alte URL im Mandanten «vhf-gsk» nicht gespeichert
    Und ist der alte-Link-Import nicht im Zugriffsprotokoll erfasst
