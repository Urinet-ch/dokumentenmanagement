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

  @req-8.5
  Szenario: Mehrere Werte gelten als ODER, mehrere Klassifikationen als UND, Facetten behalten Werte ohne Treffer
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":true,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"phase","name":"Phase","werte":[{"code":"planung","name":"Planung"},{"code":"ausfuehrung","name":"Ausführung"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «fachgebiet» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «bau» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «phase» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «planung» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen im Mandanten «urinet» liegen diese Suchdokumente:
      | name      | titel  | art             | partner | tags | datum | betrag | richtung | belegnummer | vertraulichkeit | bestaetigt | erfasser       | person | status     | text                 | klassifikationen              |
      | MK_BAU_P  | Plan A | PROJEKTDOKUMENT |         |      |       |        |          |             | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT | Unterlagen Projekt A | fachgebiet=bau,phase=planung  |
      | MK_BAU_A  | Plan B | PROJEKTDOKUMENT |         |      |       |        |          |             | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT | Unterlagen Projekt B | fachgebiet=bau,phase=ausfuehrung |
      | MK_ELEK_P | Plan C | PROJEKTDOKUMENT |         |      |       |        |          |             | NORMAL          | ja         | inhaber-urinet |        | ARCHIVIERT | Unterlagen Projekt C | fachgebiet=elektro,phase=planung |
    Wenn «mitarbeiter-urinet» sucht mit:
      | klassifikation.fachgebiet | bau     |
      | klassifikation.fachgebiet | elektro |
      | groesse                   | 100     |
    Dann ist die Gesamtzahl 3
    Wenn «mitarbeiter-urinet» sucht mit:
      | klassifikation.fachgebiet | bau      |
      | klassifikation.phase      | planung  |
    Dann lauten die Treffer der Reihe nach «MK_BAU_P»
    Wenn «mitarbeiter-urinet» sucht mit:
      | klassifikation.fachgebiet | elektro     |
      | klassifikation.phase      | ausfuehrung |
    Dann ist die Gesamtzahl 0
    Und zeigt die Suche diese Klassifikationsfacetten:
      | klassifikation | name       | wert        | wertName   | anzahl |
      | fachgebiet     | Fachgebiet | bau         | Bau        | 1      |
      | fachgebiet     | Fachgebiet | elektro     | Elektro    | 0      |
      | phase          | Phase      | planung     | Planung    | 1      |
      | phase          | Phase      | ausfuehrung | Ausführung | 0      |
    Wenn «mitarbeiter-urinet» sucht mit:
      | klassifikation.unbekannt | x |
    Dann antwortet die Suche mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»

  @req-8.5
  Szenario: Hybridfacetten zählen semantische Treffer unter derselben Sicht und denselben Filtern
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"},{"code":"elektro","name":"Elektro"}],
       "mehrfach":true,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"phase","name":"Phase","werte":[{"code":"planung","name":"Planung"},{"code":"ausfuehrung","name":"Ausführung"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen die Hybridsuche ist bereit
    Und im Mandanten «urinet» liegen diese Hybrid-Dokumente:
      | name          | art          | vertraulichkeit | bestaetigt | erfasser       | person | text                   | abschnitte |
      | SEM_BAU       | VERSICHERUNG | NORMAL          | ja         | inhaber-urinet |        | Police UVG Prämie 2026 | 0.1@1-1    |
      | SEM_ELEKTRO   | VERSICHERUNG | NORMAL          | ja         | inhaber-urinet |        | Police UVG Prämie 2026 | 0.2@1-1    |
      | SEM_INTERN    | VERSICHERUNG | INTERN          | ja         | inhaber-urinet |        | Police UVG Prämie 2026 | 0.1@1-1    |
    Und im Mandanten «vhf-gsk» liegen diese Hybrid-Dokumente:
      | name          | art          | vertraulichkeit | bestaetigt | erfasser        | person | text                   | abschnitte |
      | SEM_FREMD     |              | NORMAL          | ja         | inhaber-vhf-gsk |        | Police UVG Prämie 2026 | 0.1@1-1    |
    Und haben diese Hybrid-Dokumente diese Klassifikationen:
      | name        | klassifikation | wert        |
      | SEM_BAU     | fachgebiet     | bau         |
      | SEM_BAU     | phase          | planung     |
      | SEM_ELEKTRO | fachgebiet     | elektro     |
      | SEM_ELEKTRO | phase          | ausfuehrung |
      | SEM_INTERN  | fachgebiet     | bau         |
    Wenn «mitarbeiter-urinet» hybrid sucht mit:
      | q | Unfallversicherung |
    Dann lauten die Hybridtreffer «SEM_BAU,SEM_ELEKTRO»
    Und ist die Hybridsuche nicht degradiert
    Und zeigt die Hybridsuche diese Klassifikationsfacetten:
      | klassifikation | name       | wert    | wertName | anzahl |
      | fachgebiet     | Fachgebiet | bau     | Bau      | 1      |
      | fachgebiet     | Fachgebiet | elektro | Elektro  | 1      |
    Wenn «mitarbeiter-urinet» hybrid sucht mit:
      | q                         | Unfallversicherung |
      | klassifikation.fachgebiet | bau                |
      | klassifikation.fachgebiet | elektro            |
      | klassifikation.phase      | planung            |
    Dann lauten die Hybridtreffer «SEM_BAU»
    Und zeigt die Hybridsuche diese Klassifikationsfacetten:
      | klassifikation | name       | wert    | wertName | anzahl |
      | fachgebiet     | Fachgebiet | bau     | Bau      | 1      |
      | fachgebiet     | Fachgebiet | elektro | Elektro  | 0      |
    Wenn «mitarbeiter-urinet» hybrid sucht mit:
      | q                         | Unfallversicherung |
      | klassifikation.fachgebiet | bau                |
      | klassifikation.phase      | ausfuehrung        |
    Dann lauten die Hybridtreffer «»
    Und zeigt die Hybridsuche diese Klassifikationsfacetten:
      | klassifikation | name       | wert    | wertName | anzahl |
      | fachgebiet     | Fachgebiet | bau     | Bau      | 0      |
      | fachgebiet     | Fachgebiet | elektro | Elektro  | 0      |

  @req-8.5
  Szenario: Hybridfacetten zählen höchstens die gelieferten 30 Treffer auch beim Rückfall
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fachgebiet","name":"Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Angenommen die Hybridsuche ist bereit
    Und im Mandanten «urinet» liegen 40 Hybrid-Dokumente «VIELE» mit dem Text "Versicherung" und je einem nahen Abschnitt
    Und alle Hybrid-Dokumente des Mandanten «urinet» haben die Klassifikation «fachgebiet» mit Wert «bau»
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Versicherung |
    Dann ist die Hybridsuche nicht degradiert
    Und hat die Hybridantwort 30 Treffer, gesamt 30, seite 0 und groesse 30
    Und zeigt die Hybridsuche diese Klassifikationsfacetten:
      | klassifikation | name       | wert | wertName | anzahl |
      | fachgebiet     | Fachgebiet | bau  | Bau      | 30     |
    Angenommen der Reranker antwortet mit Status 503
    Wenn «inhaber-urinet» hybrid sucht mit:
      | q | Versicherung |
    Dann ist die Hybridsuche degradiert
    Und hat die Hybridantwort 30 Treffer, gesamt 30, seite 0 und groesse 30
    Und zeigt die Hybridsuche diese Klassifikationsfacetten:
      | klassifikation | name       | wert | wertName | anzahl |
      | fachgebiet     | Fachgebiet | bau  | Bau      | 30     |

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

  @req-8.7
  Szenariogrundriss: Ungültige Multipart-Vorbelegung hinterlässt in keinem Mandanten Importdaten
    Wenn «inhaber-urinet» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"importphase","name":"Importphase","werte":[{"code":"planung","name":"Planung"},{"code":"ausfuehrung","name":"Ausführung"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «importphase» der Dokumentart «PROJEKTDOKUMENT» mit dem Standardwert «planung» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» ordnet die Klassifikation «importphase» der Dokumentart «KORRESPONDENZ» mit dem Standardwert «planung» zu
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn «inhaber-urinet» die Dokumentart «KORRESPONDENZ» ändert:
      """
      {"aktiv":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Wenn «inhaber-vhf-gsk» legt die Klassifikation mit dem aktuellen Stand an:
      """
      {"code":"fremdfach","name":"Fremdes Fachgebiet","werte":[{"code":"bau","name":"Bau"}],
       "mehrfach":false,"kiVorschlag":false,"suchfilter":true,"oeffentlichFilter":false}
      """
    Dann antwortet die Klassifikationsverwaltung mit Status 201
    Wenn ein Import-Token von «urinet» eine PDF mit Art «<art>» und diesen Klassifikationen hochlädt:
      """
      <klassifikationen>
      """
    Dann antwortet der Upload mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und hat sich in keinem der beiden Mandanten der Importbestand geändert

    Beispiele:
      | art              | klassifikationen                              |
      | NICHT_VORHANDEN   | {"importphase":["planung"]}                    |
      | KORRESPONDENZ     | {"importphase":["planung"]}                    |
      | PROJEKTDOKUMENT   | {"fremdfach":["bau"]}                          |
      | PROJEKTDOKUMENT   | {"importphase":["planung","ausfuehrung"]}      |
