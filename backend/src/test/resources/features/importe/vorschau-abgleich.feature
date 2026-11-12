# language: de
@spec-01
Funktionalität: CSV-Import aus 21.CRM – Vorschau und Abgleich (Aufgabe 4.2)
  Nach der Zuordnung gleicht der Auftrag CSV_VORSCHAU jede Kontaktzeile mit den bestehenden Kontakten ab: crm_id →
  E-Mail (ohne Gross-/Kleinschreibung) → normalisierter Name + PLZ. Treffen mehrere Kontakte, zeigen die Stufen auf
  verschiedene Kontakte, ist der Name nur ähnlich (ab 0.6) oder kommt die Zeile in der Datei schon vor, ist die Zeile
  eine «mögliche Dublette». Der Lauf zählt neu, geändert, unverändert, mögliche Dubletten und fehlerhaft;
  «GET /api/v1/importe/{id}» zeigt Zähler und Zeilen seitenweise. Alle Namen und Werte sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es diese Kontakte für den Abgleich:
      | kontakt | typ          | crm_id  | vorname | name               | plz  | email                 |
      | K1      | ORGANISATION | CRM-T01 |         | Probe Holz AG      | 6000 | info@probe-holz.test  |
      | K2      | ORGANISATION |         |         | Muster Garage GmbH | 6460 | garage@muster.test    |
      | K3      | PERSON       |         | Erika   | Beispiel           | 6004 |                       |
      | K4      | ORGANISATION |         |         | Muster Garage AG   | 6460 | zweite@garage.test    |
    Und im Mandanten «vhf-gsk» gibt es diese Kontakte für den Abgleich:
      | kontakt | typ          | crm_id  | vorname | name               | plz  | email                 |
      | F1      | ORGANISATION | CRM-T09 |         | Fremd Verband      | 6000 | info@fremd.test       |

  @req-13.3 @req-13.4 @req-13.5
  Szenario: Die Vorschau gleicht jede Zeile ab und zählt neu, geändert, unverändert, Dubletten und fehlerhaft
    Angenommen der CSV-Import schneidet das Log mit
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;Vorname;PLZ;E-Mail;Ort
      CRM-T01;Probe Holz AG;;6000;info@probe-holz.test;
      ;Neue Werkstatt GmbH;;6430;werkstatt@neu.test;Schwyz
      ;Beispiel;Erika;CH-6004;;
      ;Sonnenweg Bäckerei;;6010;;Kriens
      ;Probe Holz;;6000;;
      CRM-T01;Probe Holz AG;;6000;garage@muster.test;
      ;Muster Garage;;6460;;
      ;Probe Holz Bau AG;;6430;;
      CRM-T09;Fremd Verband;;6000;info@fremd.test;
      ;;;6000;leer@name.test;
      ;Andere Druckerei;;6430;druck@andere.test;
      ;Andere Druckerei;;6440;DRUCK@andere.test;
      CRM-T77;Neuer Name;;6000;info@probe-holz.test;
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 13 Datenzeilen, 1 fehlerhaft und 0 übersprungen
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 4 neu, 0 geändert, 2 unverändert, 6 mögliche Dubletten, 1 fehlerhaft und 0 übersprungen
    Und zeigt die Vorschau diese Zeilen:
      | nr | ergebnis     | meldung                                                               | kontakt | kandidaten |
      | 2  | UNVERAENDERT |                                                                       | K1      |            |
      | 3  | NEU          |                                                                       |         |            |
      | 4  | UNVERAENDERT |                                                                       | K3      |            |
      | 5  | NEU          |                                                                       |         |            |
      | 6  | DUBLETTE     | doppelt in der Datei (Zeile 2)                                        |         | K1         |
      | 7  | DUBLETTE     | Kontakt-ID, E-Mail bzw. Name und PLZ zeigen auf verschiedene Kontakte |         | K1, K2     |
      | 8  | DUBLETTE     | mehrere Kontakte mit gleichem Namen und gleicher PLZ                  |         | K2, K4     |
      | 9  | DUBLETTE     | ähnlicher Name                                                        |         | K1         |
      | 10 | NEU          |                                                                       |         |            |
      | 11 | FEHLER       | Name fehlt                                                            |         |            |
      | 12 | NEU          |                                                                       |         |            |
      | 13 | DUBLETTE     | doppelt in der Datei (Zeile 12)                                       |         |            |
      | 14 | DUBLETTE     | Treffer über E-Mail mit anderer Kontakt-ID aus 21.CRM                 |         | K1         |
    Und enthält das Log keinen der Werte «Probe» und «druck@andere.test»

  @req-13.4
  Szenario: Ein abweichendes Feld macht den getroffenen Kontakt zu «geändert»
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;Vorname;PLZ;E-Mail;Ort
      CRM-T01;Probe Holz AG;;6001;info@probe-holz.test;
      ;Muster Garage GmbH;;;garage@muster.test;Altdorf
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 0 neu, 2 geändert, 0 unverändert, 0 mögliche Dubletten, 0 fehlerhaft und 0 übersprungen
    Und zeigt die Vorschau diese Zeilen:
      | nr | ergebnis  | meldung | kontakt | kandidaten |
      | 2  | GEAENDERT |         | K1      |            |
      | 3  | GEAENDERT |         | K2      |            |

  @req-13.3
  Szenario: Die Zeilen der Vorschau kommen seitenweise; andere Mandanten sehen den Lauf nicht
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Firma A;6000
      Firma B;6000
      Firma C;6000
      Firma D;6000
      Firma E;6000
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» die Seite 1 mit 2 Zeilen des Laufs abfragt
    Dann enthält die Seite 1 von 5 Zeilen die Zeilen 4 bis 5
    Wenn «inhaber-urinet» die Seite 0 mit 101 Zeilen des Laufs abfragt
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-vhf-gsk» den Lauf abfragt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab

  @req-13.3
  Szenario: Gelöschte Zeilen bleiben in der Vorschau übersprungen
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Kontakt-Nr;Name;PLZ;Ist gelöscht
      CRM-T01;Probe Holz AG;6000;true
      ;Neue Werkstatt GmbH;6430;false
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» mit 2 Datenzeilen, 0 fehlerhaft und 1 übersprungen
    Und sind für den Lauf 1 Aufträge «CSV_VORSCHAU» eingereiht
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 1 neu, 0 geändert, 0 unverändert, 0 mögliche Dubletten, 0 fehlerhaft und 1 übersprungen
    Und zeigt die Vorschau diese Zeilen:
      | nr | ergebnis      | meldung         | kontakt | kandidaten |
      | 2  | UEBERSPRUNGEN | im CRM gelöscht |         |            |
      | 3  | NEU           |                 |         |            |

  @req-13.3
  Szenario: Für einen Verkaufschancen-Lauf reiht das Archiv keinen Kontakt-Abgleich ein
    Wenn «inhaber-urinet» die Verkaufschancen-Kopfzeile aus 21.CRM mit einer erfundenen Zeile hochlädt
    Und «inhaber-urinet» den Vorschlag für den Verkaufschancen-Lauf bestätigt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» ohne Zähler der Vorschau
    Und sind für den Lauf 0 Aufträge «CSV_VORSCHAU» eingereiht
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann steht der Lauf auf «VORSCHAU_AUSSTEHEND» ohne Zähler der Vorschau

  @req-13.3
  Szenariogrundriss: Ein unbekannter oder ungültiger Lauf ist nicht gefunden
    Wenn «inhaber-urinet» den Lauf «<lauf>» abfragt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab

    Beispiele:
      | lauf                                 |
      | 00000000-0000-4000-8000-000000000000 |
      | kein-lauf                            |

  @req-13.3
  Szenario: Eine negative Seite lehnt der CSV-Import ab
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Firma A;6000
      """
    Und «inhaber-urinet» die Seite -1 mit 10 Zeilen des Laufs abfragt
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab

  @req-2.18
  Szenariogrundriss: Ohne Rolle «inhaber» liefert der CSV-Import keine Vorschau
    Wenn «<hochlader>» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Firma A;6000
      """
    Und «<benutzer>» den Lauf abfragt
    Dann lehnt der CSV-Import mit Status 403 und Fehlercode «NICHT_ERLAUBT» ab

    Beispiele:
      | hochlader       | benutzer                 |
      | inhaber-urinet  | mitarbeiter-urinet       |
      | inhaber-urinet  | treuhaender-urinet       |
      | inhaber-vhf-gsk | geschaeftsstelle-vhf-gsk |
