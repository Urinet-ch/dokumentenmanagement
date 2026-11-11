# language: de
@spec-01
Funktionalität: CSV-Import aus 21.CRM – Erkennung und Spaltenzuordnung (Aufgabe 4.1)
  Das Archiv liest die CSV-Datei, erkennt Zeichensatz und Trennzeichen, prüft die Zeilen und speichert die
  Spaltenzuordnung pro Mandant und Importart. Vorschau und Abgleich (4.2) sowie die Übernahme (4.3) folgen.
  Die Kontakt-Beispieldatei stammt aus Aufgabe 0.4 (Windows-1252, Semikolon, 151 Spalten, 5 Datenzeilen).

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-2.18
  Szenariogrundriss: Ohne Rolle «inhaber» antwortet der CSV-Import mit 403
    Wenn «<benutzer>» die Beispieldatei «21crm-kontakte-beispiel.csv» als Importart «kontakt» hochlädt
    Dann lehnt der CSV-Import mit Status 403 und Fehlercode «NICHT_ERLAUBT» ab
    Wenn «<benutzer>» die gespeicherte Kontakt-Zuordnung abfragt
    Dann lehnt der CSV-Import mit Status 403 und Fehlercode «NICHT_ERLAUBT» ab
    Wenn «<benutzer>» für einen unbekannten Lauf die Kontakt-Zuordnung «21.CRM» bestätigt
    Dann lehnt der CSV-Import mit Status 403 und Fehlercode «NICHT_ERLAUBT» ab
    Und ist im Schema von «urinet» kein Importlauf gespeichert
    Und ist im Schema von «vhf-gsk» kein Importlauf gespeichert

    Beispiele:
      | benutzer                 |
      | mitarbeiter-urinet       |
      | treuhaender-urinet       |
      | geschaeftsstelle-vhf-gsk |

  @req-13.1
  Szenariogrundriss: Das Archiv erkennt Zeichensatz und Trennzeichen selbst
    Wenn «inhaber-urinet» die Kontakt-Beispieldatei in der Variante «<variante>» hochlädt
    Dann erkennt der CSV-Import Zeichensatz «<zeichensatz>», Trennzeichen «<trennzeichen>» und 5 Datenzeilen
    Und zeigt der CSV-Import den Zuordnungsdialog mit 151 Spalten und dem Profil «21.CRM»

    Beispiele:
      | variante   | zeichensatz  | trennzeichen |
      | original   | windows-1252 | ;            |
      | utf8       | UTF-8        | ;            |
      | utf8-bom   | UTF-8        | ;            |
      | komma-crlf | windows-1252 | ,            |

  @req-13.1
  Szenariogrundriss: Kurze Windows-1252-Dateien nimmt das Archiv an, auch wenn ICU4J eine andere Latin-Kodierung vermutet
    Wenn «inhaber-urinet» diese Kontakt-CSV im Zeichensatz «windows-1252» hochlädt:
      """
      Name;Ort
      <name>;<ort>
      """
    Dann erkennt der CSV-Import Zeichensatz «windows-1252», Trennzeichen «;» und 1 Datenzeilen

    Beispiele:
      | name         | ort         |
      | A            | Schönenberg |
      | Café Crème   | Genève      |
      | Škoda Garage | Bern        |
      | Niño Peña    | Lugano      |
      | Gül          | Yilmaz      |

  @req-13.1
  Szenariogrundriss: Dateien in einem nicht-lateinischen Zeichensatz lehnt das Archiv ab
    Wenn «inhaber-urinet» diese Kontakt-CSV im Zeichensatz «<zeichensatz>» hochlädt:
      """
      <kopf>
      <zeile1>
      <zeile2>
      """
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_UNGUELTIG» ab
    Und ist im Schema von «urinet» kein Importlauf gespeichert

    Beispiele:
      | zeichensatz  | kopf          | zeile1      | zeile2      |
      | Big5         | 姓名;城市     | 張偉;台北   | 王芳;高雄   |
      | GB18030      | 姓名;城市     | 张伟;北京   | 王芳;上海   |
      | EUC-KR       | 이름;도시     | 김민수;서울 | 이영희;부산 |
      | windows-1256 | الاسم;المدينة | محمد;القاهرة | أحمد;دبي   |

  @req-13.1
  Szenario: Felder in Anführungszeichen mit Trennzeichen und Zeilenumbruch bleiben ein Feld
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name,Notiz,PLZ
      "Muster, AG","erste Zeile
      zweite ""Zeile""",6000
      Test GmbH,,6460
      """
    Dann erkennt der CSV-Import Zeichensatz «UTF-8», Trennzeichen «,» und 2 Datenzeilen
    Und steht der Lauf auf «ZUORDNUNG_NOETIG» mit 2 Datenzeilen, 0 fehlerhaft und 0 übersprungen

  @req-13.1
  Szenariogrundriss: Ungültige Parameter lehnt der CSV-Import ab
    Wenn «inhaber-urinet» eine Kontakt-CSV mit art «<art>» und vollexport «<vollexport>» hochlädt
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab

    Beispiele:
      | art      | vollexport |
      | dokument | false      |
      | kontakt  | ja         |

  @req-13.2
  Szenario: Die bestätigte Zuordnung gilt für den nächsten Upload, eine abweichende Kopfzeile öffnet den Dialog erneut
    Wenn «inhaber-urinet» die Beispieldatei «21crm-kontakte-beispiel.csv» als Importart «kontakt» hochlädt
    Dann zeigt der CSV-Import den Zuordnungsdialog mit 151 Spalten und dem Profil «21.CRM»
    Und steht der Lauf auf «ZUORDNUNG_NOETIG» mit 5 Datenzeilen, 0 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» die gespeicherte Kontakt-Zuordnung abfragt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab
    Wenn «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 5 Datenzeilen, 0 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» die gespeicherte Kontakt-Zuordnung abfragt
    Dann liefert der CSV-Import die gespeicherte Kontakt-Zuordnung mit dem Profil «21.CRM»
    Wenn «inhaber-urinet» die Kontakt-Beispieldatei in der Variante «utf8-bom» hochlädt
    Dann nimmt der CSV-Import die Datei mit Status 202 an, ohne Dialog, Lauf «VORSCHAU_AUSSTEHEND»
    Und steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 5 Datenzeilen, 0 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» die Kontakt-Beispieldatei mit umbenannter letzter Spalte hochlädt
    Dann zeigt der CSV-Import den Zuordnungsdialog mit 151 Spalten und dem Profil «21.CRM»

  @req-13.2
  Szenario: Die Zuordnung gilt nur im eigenen Mandanten
    Wenn «inhaber-urinet» die Beispieldatei «21crm-kontakte-beispiel.csv» als Importart «kontakt» hochlädt
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und «inhaber-vhf-gsk» die gespeicherte Kontakt-Zuordnung abfragt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab
    Wenn «inhaber-vhf-gsk» für den Lauf die Kontakt-Zuordnung «21.CRM» bestätigt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab
    Und ist im Schema von «vhf-gsk» kein Importlauf gespeichert
    Wenn «inhaber-vhf-gsk» die Beispieldatei «21crm-kontakte-beispiel.csv» als Importart «kontakt» hochlädt
    Dann zeigt der CSV-Import den Zuordnungsdialog mit 151 Spalten und dem Profil «21.CRM»

  @req-13.2
  Szenario: Eine fremde Kopfzeile erhält einen Vorschlag aus der Synonymliste, den der Benutzer anpasst
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Nachname;Firma;Vorname;E-Mail (Geschäft);Tel.;Ort
      Muster;;Erika;e@example.test;041 000 00 00;Altdorf
      """
    Dann zeigt der CSV-Import den Zuordnungsdialog mit 6 Spalten ohne Profil und den Feldern:
      | name    | 1 |
      | vorname | 2 |
      | ort     | 5 |
      | email   | 3 |
      | telefon | 4 |
    Wenn «inhaber-urinet» für den Lauf die Kontakt-Zuordnung bestätigt:
      | name    | 0 |
      | vorname | 2 |
      | email   | 3 |
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 1 Datenzeilen, 0 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» für den Lauf die Kontakt-Zuordnung «21.CRM» bestätigt
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_UNGUELTIG» ab

  @req-13.2
  Szenario: Für Verkaufschancen gibt es nur den Vorschlag aus der Synonymliste, kein Profil «21.CRM»
    Wenn «inhaber-urinet» die Verkaufschancen-Kopfzeile aus 21.CRM mit einer erfundenen Zeile hochlädt
    Dann zeigt der CSV-Import den Zuordnungsdialog mit 32 Spalten ohne Profil und den Feldern:
      | crm_id         | 11 |
      | kontakt_crm_id | 18 |
      | titel          | 3  |
      | phase          | 8  |
      | betrag         | 22 |
      | datum          | 6  |
      | geloescht      | 26 |
    Wenn «inhaber-urinet» für den Lauf die Verkaufschancen-Zuordnung «21.CRM» bestätigt
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab

  @req-13.10
  Szenario: Mehr als 1'000 Spalten oder ein zu langer Spaltenname: Das Archiv lehnt die Datei ab
    Wenn «inhaber-urinet» eine Kontakt-CSV mit 1001 Spalten hochlädt
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_UNGUELTIG» ab
    Und nennt die Meldung «mehr als 1'000 Spalten»
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;{RIESIG}
      Muster AG;x
      """
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_UNGUELTIG» ab
    Und nennt die Meldung «einen zu langen Spaltennamen»
    Und ist im Schema von «urinet» kein Importlauf gespeichert
    Wenn «inhaber-urinet» eine Kontakt-CSV mit 1000 Spalten hochlädt
    Dann steht der Lauf auf «ZUORDNUNG_NOETIG» mit 1 Datenzeilen, 0 fehlerhaft und 0 übersprungen

  @req-13.10
  Szenario: Ohne Spalte für den Namen lehnt das Archiv die Zuordnung ab
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Vorname;PLZ
      Erika;6000
      """
    Und «inhaber-urinet» für den Lauf die Kontakt-Zuordnung bestätigt:
      | vorname | 0 |
      | plz     | 1 |
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_UNGUELTIG» ab
    Und nennt die Meldung «keine Spalte für den Namen»
    Wenn «inhaber-urinet» die gespeicherte Kontakt-Zuordnung abfragt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab

  @req-13.10
  Szenario: Zeilen ohne Namen, mit falscher Spaltenzahl oder zu langem Feld sind fehlerhaft, gelöschte übersprungen
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Kontakt-Nr;Name;PLZ;Ist gelöscht;Notiz
      K1;Muster AG;6000;false;
      K2;;6000;false;ohne Namen
      K3;Zu viel AG;6000;false;a;b
      K4;Gelöscht AG;6000;true;
      K5;Riesig AG;6000;false;{RIESIG}
      K6;=1+1;+41 41 000 00 00;false;@SUMME
      """
    Dann zeigt der CSV-Import den Zuordnungsdialog mit 5 Spalten ohne Profil und den Feldern:
      | crm_id    | 0 |
      | name      | 1 |
      | plz       | 2 |
      | geloescht | 3 |
    Und steht der Lauf auf «ZUORDNUNG_NOETIG» mit 6 Datenzeilen, 2 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 6 Datenzeilen, 3 fehlerhaft und 1 übersprungen
    Und hat der Lauf im Schema von «urinet» diese Zeilen:
      | nr | ergebnis     | meldung                                    | werte gespeichert | daten enthalten |
      | 2  | OFFEN        |                                            | ja                |                 |
      | 3  | FEHLER       | Name fehlt                                 | ja                |                 |
      | 4  | FEHLER       | Spaltenzahl 6 statt 5 wie in der Kopfzeile | ja                |                 |
      | 5  | UEBERSPRUNGEN | im CRM gelöscht                           | ja                |                 |
      | 6  | FEHLER       | Feld in Spalte 5 länger als 10000 Zeichen  | nein              |                 |
      | 7  | OFFEN        |                                            | ja                | =1+1            |

  @req-13.10
  Szenario: Mehr als 10'000 Datenzeilen lehnt das Archiv ab
    Wenn «inhaber-urinet» eine Kontakt-CSV mit 10001 Datenzeilen hochlädt
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_UNGUELTIG» ab
    Und nennt die Meldung «mehr als 10'000 Datenzeilen»
    Und ist im Schema von «urinet» kein Importlauf gespeichert
    Wenn «inhaber-urinet» eine Kontakt-CSV mit 10000 Datenzeilen hochlädt
    Dann steht der Lauf auf «ZUORDNUNG_NOETIG» mit 10000 Datenzeilen, 0 fehlerhaft und 0 übersprungen

  @req-13.10
  Szenario: Eine Datei über der Grössengrenze lehnt das Archiv mit 413 ab, bevor der Parser läuft
    Wenn «inhaber-urinet» eine UTF-16-Datei mit 1 Byte über der Grenze des CSV-Imports hochlädt
    Dann lehnt der CSV-Import mit Status 413 und Fehlercode «DATEI_ZU_GROSS» ab
    Und ist im Schema von «urinet» kein Importlauf gespeichert
    Wenn «inhaber-urinet» eine Kontakt-CSV mit genau der Grenze des CSV-Imports hochlädt
    Dann nimmt der CSV-Import die Datei an der Grenze mit allen Datenzeilen an

  @req-13.11
  Szenariogrundriss: Eine Datei ohne Datenzeilen lehnt das Archiv mit «keine Datenzeilen» ab
    Wenn «inhaber-urinet» die Beispieldatei «<datei>» als Importart «<art>» hochlädt
    Dann lehnt der CSV-Import mit Status 422 und Fehlercode «CSV_LEER» ab
    Und nennt die Meldung «keine Datenzeilen»
    Und ist im Schema von «urinet» kein Importlauf gespeichert

    Beispiele:
      | datei                              | art            |
      | 21crm-verkaufschancen-beispiel.csv | verkaufschance |
      | nur-kopfzeile.csv                  | kontakt        |

  @req-16.7
  Szenario: Das Log des CSV-Imports enthält die Prüfsumme, aber keine Feldwerte und keinen Dateinamen
    Angenommen der CSV-Import schneidet das Log mit
    Wenn «inhaber-urinet» die Kontakt-CSV mit dem Wert «Geheimwert-4711» unter dem Dateinamen «Kunden-Geheim.csv» hochlädt
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 2 Datenzeilen, 1 fehlerhaft und 0 übersprungen
    Und enthält das Log die Prüfsumme der Datei, aber keinen der Werte «Geheimwert-4711» und «Kunden-Geheim.csv»
