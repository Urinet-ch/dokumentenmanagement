# language: de
@spec-01
Funktionalität: Lernen aus Korrekturen
  Beim Bestätigen vergleicht das Archiv den KI-Vorschlag mit dem Ergebnis, zählt bestätigte und korrigierte Felder und
  speichert bei einer Korrektur ein Beispiel mit IBAN, UID, E-Mail und Belegnummer-Muster des Belegs. Beim nächsten
  Beleg mit einem dieser Merkmale kommen bis zu 5 Beispiele in den Prompt, ohne zweiten KI-Aufruf (Aufgabe 3.3); ohne
  Treffer die 5 neuesten. In den Prompt kommen sie nur mit eingeschaltetem Schalter «archiv.ki.beispiele.aktiv» (in
  prod und in den Tests aus, bis ein Golden-Lauf mit Beispielen das Gate 0 bestätigt); die Szenarien schalten ihn
  selbst ein. Wer welches Beispiel bekommt, richtet sich nach der aktuellen Vertraulichkeit des bestätigten Dokuments.
  Das Sprachmodell ist ein WireMock (Req 16.7, 16.21), alle Belege, Kennungen und Antworten sind synthetisch (Req 16.15).

  Hintergrund:
    Angenommen die Verarbeitung ist bereit
    Und das Sprachmodell ist bereit
    Und die Zähler der Korrekturquote sind abgelesen
    Und der Partner «Muster Bürobedarf AG» ist als Kontakt mit der IBAN «CH4431999123000889012» erfasst
    Und das Sprachmodell schlägt «KORRESPONDENZ» mit Belegnummer «» und Vertraulichkeit «INTERN» vor

  @req-7.7 @req-7.9 @req-7.10
  Szenario: Eine Korrektur beim Partner kommt beim nächsten Beleg mit dessen IBAN in den Prompt
    Angenommen die Korrekturbeispiele im Prompt sind eingeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» die Korrektur '{"titel": "Lernprobe Bueromaterial", "vertraulichkeit": "NORMAL"}' mit der IBAN «CH4431999123000889012»
    Und die Korrekturquote zählt für «titel» 1 Bestätigung und 1 Korrektur
    Und die Korrekturquote zählt für «vertraulichkeit» 1 Bestätigung und 1 Korrektur
    Und die Korrekturquote zählt für «datum» 1 Bestätigung und 0 Korrekturen
    Und die Korrekturquote zählt für «partnerName» 1 Bestätigung und 0 Korrekturen
    Und der Aufruf des Sprachmodells für den Beleg «A» enthält kein Korrekturbeispiel
    Angenommen der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «Zahlbar auf CH4431999123000889012»
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Lernprobe Bueromaterial; vertraulichkeit=NORMAL"
    Und der Aufruf des Sprachmodells für den Beleg «B» nennt die Beispiele «mit gleichen Merkmalen»

  @req-7.7
  Szenario: Ohne Korrektur entsteht kein Beispiel, die Bestätigung zählt trotzdem
    Angenommen der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Rechnung Büromaterial» und der Vertraulichkeit «INTERN» bestätigt
    Dann «ki_beispiel» enthält keine Zeile für den Beleg «A»
    Und die Korrekturquote zählt für «titel» 1 Bestätigung und 0 Korrekturen
    Und die Korrekturquote zählt für «art» 1 Bestätigung und 0 Korrekturen

  @req-7.7
  Szenario: Ein vor dem Bestätigen umgehängter Partner zählt als Korrektur
    Angenommen der Partner «Zweitlieferant GmbH» ist als Kontakt ohne IBAN erfasst
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Und «inhaber-urinet» hängt den Beleg «A» an den Partner «Zweitlieferant GmbH»
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Rechnung Büromaterial» und der Vertraulichkeit «INTERN» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» die Korrektur '{"partnerName": "Zweitlieferant GmbH"}' mit der IBAN «CH4431999123000889012»
    Und die Korrekturquote zählt für «partnerName» 1 Bestätigung und 1 Korrektur
    Und die Korrekturquote zählt für «titel» 1 Bestätigung und 0 Korrekturen

  @req-7.9
  Szenario: Ein internes Beispiel erreicht nur Erfasser, die interne Dokumente sehen dürfen
    Angenommen die Korrekturbeispiele im Prompt sind eingeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Interne Lernprobe» und der Vertraulichkeit «INTERN» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» die Korrektur '{"titel": "Interne Lernprobe"}' mit der IBAN «CH4431999123000889012»
    Angenommen der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «C» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «C» wurde von «inhaber-urinet» erfasst
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält kein Korrekturbeispiel
    Und der Aufruf des Sprachmodells für den Beleg «C» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Interne Lernprobe"

  @req-7.9
  Szenario: Ein nachträglich auf INTERN gestuftes Beispiel erreicht den Mitarbeiter nicht mehr
    Angenommen die Korrekturbeispiele im Prompt sind eingeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Und «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Und «mitarbeiter-urinet» steht als «MITARBEITER» im Benutzerverzeichnis
    Und der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «B» wurde von «mitarbeiter-urinet» erfasst
    Und der Worker die Aufträge bearbeitet
    Und der Aufruf des Sprachmodells für den Beleg «B» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Lernprobe Bueromaterial"
    Wenn der Beleg «A» nachträglich auf «INTERN» gestuft wird
    Und der Beleg «C» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «C» wurde von «mitarbeiter-urinet» erfasst
    Und der Beleg «D» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «D» wurde von «inhaber-urinet» erfasst
    Und der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «C» enthält kein Korrekturbeispiel
    Und der Aufruf des Sprachmodells für den Beleg «D» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Lernprobe Bueromaterial"

  @req-7.10
  Szenario: Das Beispiel mit gleicher IBAN schlägt 5 neuere Beispiele ohne gemeinsames Merkmal
    Angenommen die Korrekturbeispiele im Prompt sind eingeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Und «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Und es gibt 5 neuere bestätigte Korrekturbeispiele mit der IBAN «CH9300762011623852957»
    Und der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «Zahlbar auf CH4431999123000889012»
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Lernprobe Bueromaterial; vertraulichkeit=NORMAL"
    Und der Aufruf des Sprachmodells für den Beleg «B» enthält kein Korrekturbeispiel «Probe neuer 1»

  @req-7.9 @req-7.10
  Szenario: Ohne gemeinsames Merkmal kommen die neuesten Beispiele mit eigener Einleitung
    Angenommen die Korrekturbeispiele im Prompt sind eingeschaltet
    Und es gibt 5 neuere bestätigte Korrekturbeispiele mit der IBAN «CH9300762011623852957»
    Und der Beleg «B» als digitales PDF mit «Ohne Bankangaben AG» und «Barzahlung»
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Probe neuer 1"
    Und der Aufruf des Sprachmodells für den Beleg «B» nennt die Beispiele «Bei den neuesten»

  @req-7.9
  Szenario: Ausgeschaltet wird das Beispiel gespeichert, der Prompt bleibt der des Gate-Laufs
    Angenommen die Korrekturbeispiele im Prompt sind ausgeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» die Korrektur '{"titel": "Lernprobe Bueromaterial", "vertraulichkeit": "NORMAL"}' mit der IBAN «CH4431999123000889012»
    Angenommen der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «Zahlbar auf CH4431999123000889012»
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält kein Korrekturbeispiel

  @req-7.9
  Szenario: Die eigene UID mit Zusatz und weiteren Ziffern zählt nicht als Merkmal, die fremde bleibt
    Angenommen der Mandant «urinet» hat die UID «CHE-123.456.789 MWST, HR 2020»
    Und die Korrekturbeispiele im Prompt sind ausgeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «An CHE-123.456.789 MWST von CHE-987.654.321 MWST»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» nur die UID «CHE-987.654.321»

  @req-7.9 @req-7.10
  Szenariogrundriss: Auch UID und E-Mail-Adresse bringen das Beispiel in den Prompt
    Angenommen die Korrekturbeispiele im Prompt sind eingeschaltet
    Und der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «<merkmal>»
    Und der Worker die Aufträge bearbeitet
    Und «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Und es gibt 5 neuere bestätigte Korrekturbeispiele mit der IBAN «CH9300762011623852957»
    Und der Beleg «B» als digitales PDF mit «Andere Zeile» und «<merkmal>»
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Lernprobe Bueromaterial; vertraulichkeit=NORMAL"
    Und der Aufruf des Sprachmodells für den Beleg «B» nennt die Beispiele «mit gleichen Merkmalen»

    Beispiele:
      | merkmal                                 |
      | UID CHE-987.654.321 MWST                |
      | Kontakt buchhaltung@muster-buero.example |
