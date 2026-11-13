# language: de
@spec-01
Funktionalität: Lernen aus Korrekturen
  Beim Bestätigen vergleicht das Archiv den KI-Vorschlag mit dem Ergebnis, zählt bestätigte und korrigierte Felder und
  speichert bei einer Korrektur ein Beispiel mit IBAN, UID, E-Mail und Belegnummer-Muster des Belegs. Beim nächsten
  Beleg mit einem dieser Merkmale kommen bis zu 5 Beispiele in den Prompt, ohne zweiten KI-Aufruf (Aufgabe 3.3).
  Das Sprachmodell ist ein WireMock (Req 16.7, 16.21), alle Belege, Kennungen und Antworten sind synthetisch (Req 16.15).

  Hintergrund:
    Angenommen die Verarbeitung ist bereit
    Und das Sprachmodell ist bereit
    Und die Zähler der Korrekturquote sind abgelesen
    Und der Partner «Muster Bürobedarf AG» ist als Kontakt mit der IBAN «CH4431999123000889012» erfasst
    Und das Sprachmodell schlägt «KORRESPONDENZ» mit Belegnummer «» und Vertraulichkeit «INTERN» vor

  @req-7.7 @req-7.9 @req-7.10
  Szenario: Eine Korrektur beim Partner kommt beim nächsten Beleg mit dessen IBAN in den Prompt
    Angenommen der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Lernprobe Bueromaterial» und der Vertraulichkeit «NORMAL» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» die Korrektur '{"titel": "Lernprobe Bueromaterial", "vertraulichkeit": "NORMAL"}' mit der IBAN «CH4431999123000889012» und der Vertraulichkeit «NORMAL»
    Und die Korrekturquote zählt für «titel» 1 Bestätigung und 1 Korrektur
    Und die Korrekturquote zählt für «vertraulichkeit» 1 Bestätigung und 1 Korrektur
    Und die Korrekturquote zählt für «datum» 1 Bestätigung und 0 Korrekturen
    Und die Korrekturquote zählt für «partnerName» 1 Bestätigung und 0 Korrekturen
    Und der Aufruf des Sprachmodells für den Beleg «A» enthält kein Korrekturbeispiel
    Angenommen der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «Zahlbar auf CH4431999123000889012»
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Lernprobe Bueromaterial; vertraulichkeit=NORMAL"

  @req-7.7
  Szenario: Ohne Korrektur entsteht kein Beispiel, die Bestätigung zählt trotzdem
    Angenommen der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Rechnung Büromaterial» und der Vertraulichkeit «INTERN» bestätigt
    Dann «ki_beispiel» enthält keine Zeile für den Beleg «A»
    Und die Korrekturquote zählt für «titel» 1 Bestätigung und 0 Korrekturen
    Und die Korrekturquote zählt für «art» 1 Bestätigung und 0 Korrekturen

  @req-7.9
  Szenario: Ein internes Beispiel erreicht nur Erfasser, die interne Dokumente sehen dürfen
    Angenommen der Beleg «A» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Worker die Aufträge bearbeitet
    Wenn «inhaber-urinet» den Beleg «A» mit dem Titel «Interne Lernprobe» und der Vertraulichkeit «INTERN» bestätigt
    Dann «ki_beispiel» enthält für den Beleg «A» die Korrektur '{"titel": "Interne Lernprobe"}' mit der IBAN «CH4431999123000889012» und der Vertraulichkeit «INTERN»
    Angenommen der Beleg «B» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «C» als digitales PDF mit «Muster Buerobedarf AG» und «IBAN CH44 3199 9123 0008 8901 2»
    Und der Beleg «C» wurde von «inhaber-urinet» erfasst
    Wenn der Worker die Aufträge bearbeitet
    Dann der Aufruf des Sprachmodells für den Beleg «B» enthält kein Korrekturbeispiel
    Und der Aufruf des Sprachmodells für den Beleg «C» enthält das Korrekturbeispiel "- art=KORRESPONDENZ; titel=Interne Lernprobe"
