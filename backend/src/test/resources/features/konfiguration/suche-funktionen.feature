# language: de
@spec-05
Funktionalität: Klassifikationen in Suche und Funktionen des Mandanten
  Die Suche filtert und zählt Klassifikationen nur über sichtbare Dokumente; klassifizierte Werte sind im Volltext
  auffindbar. Finanzfunktionen richten sich nach den aktiven Arten des aktuellen Mandanten.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-8.5
  Szenario: Suchfilter, Facetten und Volltext verwenden Klassifikationswerte unter der Sicht des Benutzers
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":true,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen im Mandanten «urinet» liegen diese Suchdokumente:
      | name         | titel       | art              | partner | tags | datum | betrag | richtung | belegnummer | vertraulichkeit | bestaetigt | erfasser       | person | status     | text                 | klassifikationen |
      | FACH_BAU     | Plan A      | PROJEKTDOKUMENT  |         |      |       |        |          |             | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT | Unterlagen Projekt A | fachgebiet=bau   |
      | FACH_ELEKTRO | Plan B      | PROJEKTDOKUMENT  |         |      |       |        |          |             | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT | Unterlagen Projekt B | fachgebiet=elektro |
      | FACH_INTERN  | Interner Plan | PROJEKTDOKUMENT |         |      |       |        |          |             | INTERN          | ja         | inhaber-urinet |        | ARCHIVIERT | Internes Projekt     | fachgebiet=bau   |
    Wenn «mitarbeiter-urinet» sucht mit:
      | klassifikation.fachgebiet | bau |
    Dann lauten die Treffer der Reihe nach «FACH_BAU»
    Und zeigt die Suche diese Klassifikationsfacetten:
      | klassifikation | name       | wert    | wertName | anzahl |
      | fachgebiet     | Fachgebiet | bau     | Bau      | 1      |
      | fachgebiet     | Fachgebiet | elektro | Elektro  | 1      |
    Wenn «mitarbeiter-urinet» sucht mit:
      | q | Elektro |
    Dann lauten die Treffer der Reihe nach «FACH_ELEKTRO»

  @req-8.8
  Szenario: Verfügbarkeit ist mandantenbezogen und wird vor dem Jahresexport geprüft
    Wenn «inhaber-urinet» fragt die Funktionen des Mandanten ab
    Dann sind die Finanzfunktionen verfügbar
    Wenn «inhaber-vhf-gsk» fragt die Funktionen des Mandanten ab
    Dann sind die Finanzfunktionen nicht verfügbar
    Und bietet das Archiv «inhaber-vhf-gsk» diese Werkzeuge an: «dokumente_suchen,dokument_zeigen,fristen,vollstaendigkeit»
    Wenn «inhaber-vhf-gsk» fragt die Jahresexporte ab
    Dann antwortet der Jahresexport mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «geschaeftsstelle-vhf-gsk» fragt die Jahresexporte ab
    Dann antwortet der Jahresexport mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-8.7
  Szenario: Der Generator übernimmt Klassifikationswerte über ihre Codes
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Wenn der Generator die PDF «klassifikation.pdf» mit diesen Metadaten sendet:
      """
      {"art":"PROJEKTDOKUMENT","titel":"Probe","projekt":"Probe","klassifikationen":{"fachgebiet":["bau"]}}
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «Generator-Klassifikation»
    Und hat das Generator-Dokument «Generator-Klassifikation» die Klassifikation «fachgebiet» mit dem Wert «bau»

  @req-8.7
  Szenario: Der Drive-Import übernimmt Klassifikationswerte als unbestätigte Vorbelegung
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad             | typ |
      | Projekte/plan.pdf | pdf |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt und dabei Art «PROJEKTDOKUMENT» sowie Klassifikation «fachgebiet» mit Wert «bau» vorbelegt
    Dann endet der Drive-Import mit Exit-Code 0
    Und steht jedes importierte Dokument im Status «NEU» als «PERSONAL», unbestätigt und nicht archiviert
    Und hat jedes importierte Dokument die Klassifikation «fachgebiet» mit dem Wert «bau»
