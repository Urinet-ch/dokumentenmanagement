# language: de
@spec-01
Funktionalität: CSV-Import aus 21.CRM – Übernahme und Protokoll (Aufgabe 4.3)
  «POST /api/v1/importe/{id}/uebernehmen» übernimmt einen Lauf in der Vorschau in einer Transaktion (Auftrag
  CSV_UEBERNEHMEN): neue Kontakte werden angelegt, getroffene aktualisiert (die CSV gewinnt, leere Zellen überschreiben
  nichts), mögliche Dubletten nur nach dem Entscheid des Benutzers. Das Archiv löscht nie; nur ein Vollexport kennzeichnet
  fehlende Kontakte mit Kontakt-ID aus 21.CRM als «nicht mehr im CRM-Export». Der Lauf ist das Protokoll, die
  Originaldatei liegt unter «importe/<laufId>.csv». Verkaufschancen hängen über die Kontakt-ID aus 21.CRM am Kontakt.
  Alle Namen und Werte sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es diese Kontakte für den Abgleich:
      | kontakt | typ          | crm_id  | vorname | name               | plz  | email                 | nicht_im_export |
      | K1      | ORGANISATION | CRM-T01 |         | Probe Holz AG      | 6000 | info@probe-holz.test  | false           |
      | K2      | ORGANISATION |         |         | Muster Garage GmbH | 6460 | garage@muster.test    | false           |
      | K4      | ORGANISATION |         |         | Muster Garage AG   | 6460 | zweite@garage.test    | false           |
      | K5      | ORGANISATION | CRM-T05 |         | Alte Schreinerei   | 6005 | alt@schreinerei.test  | false           |
      | K6      | ORGANISATION | CRM-T06 |         | Wieder Da AG       | 6006 |                       | true            |
    Und im Mandanten «vhf-gsk» gibt es diese Kontakte für den Abgleich:
      | kontakt | typ          | crm_id  | vorname | name               | plz  | email                 | nicht_im_export |
      | F1      | ORGANISATION | CRM-T05 |         | Fremd Verband      | 6000 | info@fremd.test       | false           |

  @req-13.6 @req-13.8
  Szenario: Die Übernahme legt neue Kontakte an, aktualisiert getroffene und führt eine Dublette nach Auswahl zusammen
    Angenommen der CSV-Import schneidet das Log mit
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;Vorname;PLZ;E-Mail;Ort
      CRM-T01;Probe Holz AG;;6001;;Altdorf
      CRM-T20;Neue Werkstatt GmbH;;6430;werkstatt@neu.test;Schwyz
      ;Muster Garage;;6460;;Flüelen
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 1 neu, 1 geändert, 0 unverändert, 1 mögliche Dubletten, 0 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid          |
      | 4     | ZUSAMMENFUEHREN:K2 |
    Dann nimmt der CSV-Import die Übernahme mit Status 202 an
    Und sind für den Lauf 1 Aufträge «CSV_UEBERNEHMEN» eingereiht
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 1 angelegt, 2 aktualisiert, 0 ausgelassen und 0 gekennzeichnet
    Und hat der Mandant «urinet» diese Kontakte:
      | kontakt | crm_id  | name                | plz  | ort     | email                | quelle     | nicht_im_export |
      | K1      | CRM-T01 | Probe Holz AG       | 6001 | Altdorf | info@probe-holz.test | MANUELL    | false           |
      | K2      |         | Muster Garage       | 6460 | Flüelen | garage@muster.test   | MANUELL    | false           |
      | K4      |         | Muster Garage AG    | 6460 |         | zweite@garage.test   | MANUELL    | false           |
      | CRM-T20 | CRM-T20 | Neue Werkstatt GmbH | 6430 | Schwyz  | werkstatt@neu.test   | CSV_IMPORT | false           |
    Und enthält das Log keinen der Werte «Probe» und «werkstatt@neu.test»

  @req-13.6
  Szenario: Eine Dublette wird nur nach Auswahl angelegt; ohne Entscheid lässt die Übernahme sie aus
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Muster Garage;6460
      Probe Holz Bau AG;6430
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 0 neu, 0 geändert, 0 unverändert, 2 mögliche Dubletten, 0 fehlerhaft und 0 übersprungen
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid |
      | 2     | NEU       |
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 1 angelegt, 0 aktualisiert, 1 ausgelassen und 0 gekennzeichnet
    Und gibt es im Mandanten «urinet» 1 Kontakte mit dem Namen «Muster Garage» aus dem CSV-Import
    Und gibt es im Mandanten «urinet» 0 Kontakte mit dem Namen «Probe Holz Bau AG» aus dem CSV-Import
    Und hat der Mandant «urinet» diese Kontakte:
      | kontakt | crm_id  | name               | plz  | ort | email                | quelle  | nicht_im_export |
      | K1      | CRM-T01 | Probe Holz AG      | 6000 |     | info@probe-holz.test | MANUELL | false           |
      | K2      |         | Muster Garage GmbH | 6460 |     | garage@muster.test   | MANUELL | false           |

  @req-13.6
  Szenario: Ungültige Entscheide, ein falscher Status oder ein fremder Lauf ändern nichts
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Muster Garage;6460
      Neue Firma;6430
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und «inhaber-urinet» den Lauf übernimmt
    Dann lehnt der CSV-Import mit Status 409 und Fehlercode «STATUS_KONFLIKT» ab
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid          |
      | 2     | ZUSAMMENFUEHREN:K1 |
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid  |
      | 2     | VIELLEICHT |
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid |
      | 2     | NEU       |
      | 02    | NEU       |
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid |
      | 3     | NEU       |
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-vhf-gsk» den Lauf übernimmt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab
    Wenn «inhaber-urinet» den Lauf «00000000-0000-4000-8000-000000000000» übernimmt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab
    Wenn «inhaber-urinet» den Lauf «kein-lauf» übernimmt
    Dann lehnt der CSV-Import mit Status 404 und Fehlercode «NICHT_GEFUNDEN» ab
    Und sind für den Lauf 0 Aufträge «CSV_UEBERNEHMEN» eingereiht
    Und gibt es im Mandanten «urinet» 0 Kontakte mit dem Namen «Neue Firma» aus dem CSV-Import
    Wenn «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf übernimmt
    Dann lehnt der CSV-Import mit Status 409 und Fehlercode «STATUS_KONFLIKT» ab
    Und gibt es im Mandanten «urinet» 1 Kontakte mit dem Namen «Neue Firma» aus dem CSV-Import

  @req-13.6
  Szenario: Während die Übernahme den Lauf sperrt, antworten Übernahme und Zuordnung sofort mit 409, statt zu warten
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Neue Firma;6430
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und eine andere Transaktion den Lauf sperrt, wie es die laufende Übernahme tut
    Und «inhaber-urinet» den Lauf übernimmt
    Dann lehnt der CSV-Import nach höchstens 5 Sekunden mit Status 409 und Fehlercode «STATUS_KONFLIKT» ab
    Wenn «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Dann lehnt der CSV-Import nach höchstens 5 Sekunden mit Status 409 und Fehlercode «STATUS_KONFLIKT» ab
    Und sind für den Lauf 0 Aufträge «CSV_UEBERNEHMEN» eingereiht
    Wenn die andere Transaktion ihre Sperre freigibt
    Und «inhaber-urinet» den Lauf übernimmt
    Dann nimmt der CSV-Import die Übernahme mit Status 202 an

  @req-13.6
  Szenario: Wartet die Übernahme zu lange auf einen gesperrten Kontakt, scheitert der Versuch und ändert nichts
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;PLZ
      CRM-T01;Probe Holz AG;6001
      CRM-T21;Neue Firma;6430
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf übernimmt
    Und eine andere Transaktion den Kontakt K1 im Mandanten «urinet» sperrt
    Und der Worker alle fälligen Aufträge bearbeitet
    Dann ist der Auftrag «CSV_UEBERNEHMEN» des Laufs nach 1 Fehlversuch wieder offen
    Wenn «inhaber-urinet» den Lauf abfragt
    Dann steht der Lauf weiter in der Vorschau, ohne Übernahme
    Und gibt es im Mandanten «urinet» 0 Kontakte mit dem Namen «Neue Firma» aus dem CSV-Import
    Wenn die andere Transaktion ihre Sperre freigibt
    Und der Auftrag «CSV_UEBERNEHMEN» des Laufs wieder fällig ist
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 1 angelegt, 1 aktualisiert, 0 ausgelassen und 0 gekennzeichnet

  @req-13.6 @req-13.8
  Szenariogrundriss: Ohne Rolle «inhaber» antwortet die Übernahme mit 403
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      Name;PLZ
      Neue Firma;6430
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «<benutzer>» den Lauf übernimmt
    Dann lehnt der CSV-Import mit Status 403 und Fehlercode «NICHT_ERLAUBT» ab
    Wenn «<benutzer>» die Import-Läufe abfragt
    Dann lehnt der CSV-Import mit Status 403 und Fehlercode «NICHT_ERLAUBT» ab
    Und sind für den Lauf 0 Aufträge «CSV_UEBERNEHMEN» eingereiht

    Beispiele:
      | benutzer                 |
      | mitarbeiter-urinet       |
      | treuhaender-urinet       |
      | geschaeftsstelle-vhf-gsk |

  @req-13.7
  Szenario: Ein Vollexport kennzeichnet fehlende und im CRM gelöschte Kontakte und löscht keinen
    Wenn «inhaber-urinet» diese Kontakt-CSV als Vollexport hochlädt:
      """
      ID;Name;PLZ;Ist gelöscht
      CRM-T01;Probe Holz AG;6000;false
      CRM-T06;Wieder Da AG;6006;false
      CRM-T05;Alte Schreinerei;6005;true
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 0 angelegt, 0 aktualisiert, 1 ausgelassen und 1 gekennzeichnet
    Und hat der Mandant «urinet» diese Kontakte:
      | kontakt | crm_id  | name               | plz  | ort | email                | quelle  | nicht_im_export |
      | K1      | CRM-T01 | Probe Holz AG      | 6000 |     | info@probe-holz.test | MANUELL | false           |
      | K2      |         | Muster Garage GmbH | 6460 |     | garage@muster.test   | MANUELL | false           |
      | K4      |         | Muster Garage AG   | 6460 |     | zweite@garage.test   | MANUELL | false           |
      | K5      | CRM-T05 | Alte Schreinerei   | 6005 |     | alt@schreinerei.test | MANUELL | true            |
      | K6      | CRM-T06 | Wieder Da AG       | 6006 |     |                      | MANUELL | false           |
    Und hat der Mandant «vhf-gsk» diese Kontakte:
      | kontakt | crm_id  | name          | plz  | ort | email           | quelle  | nicht_im_export |
      | F1      | CRM-T05 | Fremd Verband | 6000 |     | info@fremd.test | MANUELL | false           |

  @req-13.7 @req-17.14
  Szenario: Ein Vollexport ändert inaktive Kontakte nie, auch nicht ihre Kennzeichnung
    Angenommen im Mandanten «urinet» gibt es diese Kontakte für den Abgleich:
      | kontakt | typ          | crm_id  | vorname | name             | plz  | email | nicht_im_export | aktiv |
      | K7      | ORGANISATION | CRM-T07 |         | Ruhende Probe AG | 6007 |       | false           | false |
      | K8      | ORGANISATION | CRM-T08 |         | Stille Probe AG  | 6008 |       | true            | false |
    Wenn «inhaber-urinet» diese Kontakt-CSV als Vollexport hochlädt:
      """
      ID;Name;PLZ;Ist gelöscht
      CRM-T01;Probe Holz AG;6000;false
      CRM-T05;Alte Schreinerei;6005;false
      CRM-T06;Wieder Da AG;6006;false
      CRM-T08;Stille Probe AG Neu;6008;false
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zählt die Vorschau 1 inaktiv
    Wenn «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 0 angelegt, 0 aktualisiert, 1 ausgelassen und 0 gekennzeichnet
    Und hat der Mandant «urinet» diese Kontakte:
      | kontakt | crm_id  | name             | plz  | ort | email | quelle  | nicht_im_export |
      | K6      | CRM-T06 | Wieder Da AG     | 6006 |     |       | MANUELL | false           |
      | K7      | CRM-T07 | Ruhende Probe AG | 6007 |     |       | MANUELL | false           |
      | K8      | CRM-T08 | Stille Probe AG  | 6008 |     |       | MANUELL | true            |
    Und hat der Kontakt K7 im Mandanten «urinet» die Version 0 und «nicht mehr im CRM-Export» false
    Und hat der Kontakt K8 im Mandanten «urinet» die Version 0 und «nicht mehr im CRM-Export» true

  @req-17.14
  Szenario: Wird ein Kontakt nach der Vorschau inaktiv, lässt die Übernahme seine Zeile aus
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;PLZ;Ort
      CRM-T01;Probe Holz AG;6000;Luzern
      CRM-T05;Alte Schreinerei;6005;
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 0 neu, 1 geändert, 1 unverändert, 0 mögliche Dubletten, 0 fehlerhaft und 0 übersprungen
    Angenommen wird der Kontakt K1 im Mandanten «urinet» inaktiv markiert
    Und wird der Kontakt K5 im Mandanten «urinet» inaktiv markiert
    Wenn «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 0 angelegt, 0 aktualisiert, 2 ausgelassen und 0 gekennzeichnet
    Und hat der Mandant «urinet» diese Kontakte:
      | kontakt | crm_id  | name          | plz  | ort | email                | quelle  | nicht_im_export |
      | K1      | CRM-T01 | Probe Holz AG | 6000 |     | info@probe-holz.test | MANUELL | false           |
    Und hat der Kontakt K1 im Mandanten «urinet» die Version 1 und «nicht mehr im CRM-Export» false

  @req-17.13
  Szenario: Ein Dubletten-Entscheid mit einem inaktiven Kontakt wird abgelehnt
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;PLZ
      ;Muster Garage;6460
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 0 neu, 0 geändert, 0 unverändert, 1 mögliche Dubletten, 0 fehlerhaft und 0 übersprungen
    Angenommen wird der Kontakt K2 im Mandanten «urinet» inaktiv markiert
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid          |
      | 2     | ZUSAMMENFUEHREN:K2 |
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid          |
      | 2     | ZUSAMMENFUEHREN:K4 |
    Dann nimmt der CSV-Import die Übernahme mit Status 202 an

  @req-13.7
  Szenario: Ohne Vollexport kennzeichnet die Übernahme nichts
    Wenn «inhaber-urinet» diese Kontakt-CSV hochlädt:
      """
      ID;Name;PLZ
      CRM-T01;Probe Holz AG;6000
      """
    Und «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 0 angelegt, 0 aktualisiert, 0 ausgelassen und 0 gekennzeichnet
    Und hat der Mandant «urinet» diese Kontakte:
      | kontakt | crm_id  | name             | plz  | ort | email                | quelle  | nicht_im_export |
      | K5      | CRM-T05 | Alte Schreinerei | 6005 |     | alt@schreinerei.test | MANUELL | false           |
      | K6      | CRM-T06 | Wieder Da AG     | 6006 |     |                      | MANUELL | true            |

  @req-13.8
  Szenario: Der Lauf ist das Protokoll; die Originaldatei liegt unter «importe/<laufId>.csv»
    Wenn «inhaber-urinet» die Kontakt-CSV mit dem Wert «Protokoll» unter dem Dateinamen «export-crm.csv» hochlädt
    Dann liegt die Originaldatei des Laufs unter «importe/<laufId>.csv» mit der Prüfsumme der hochgeladenen Datei
    Wenn «inhaber-urinet» den Vorschlag für den Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» die Import-Läufe abfragt
    Dann listet der CSV-Import den Lauf mit Dateiname «export-crm.csv», Prüfsumme, Benutzer «inhaber-urinet» und Zeit
    Wenn «inhaber-vhf-gsk» die Import-Läufe abfragt
    Dann listet der CSV-Import 0 Läufe

  @req-13.9
  Szenario: Verkaufschancen hängen über die Kontakt-ID aus 21.CRM am Kontakt
    Wenn «inhaber-urinet» diese Verkaufschancen-CSV hochlädt:
      """
      GGUID;Kontakt-Nr;Titel;Phase;Betrag;Datum
      VC-01;CRM-T01;Neubau Werkhof;Offerte;1'234.50;08.11.2026
      VC-02;CRM-T99;Unbekannter Kontakt;Offerte;100;01.11.2026
      VC-03;;Ohne Kontakt;Offerte;;
      VC-04;CRM-T05;Falscher Betrag;Offerte;viel;
      """
    Und «inhaber-urinet» den Vorschlag für den Verkaufschancen-Lauf bestätigt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann zeigt die Vorschau 1 neu, 0 geändert, 0 unverändert, 0 mögliche Dubletten, 3 fehlerhaft und 0 übersprungen
    Und zeigt die Vorschau diese Zeilen:
      | nr | ergebnis | meldung                           | kontakt | kandidaten |
      | 2  | NEU      |                                   | K1      |            |
      | 3  | FEHLER   | Kontakt aus 21.CRM nicht gefunden |         |            |
      | 4  | FEHLER   | Kontakt-ID aus 21.CRM fehlt       |         |            |
      | 5  | FEHLER   | Betrag ungültig                   |         |            |
    Wenn «inhaber-urinet» den Lauf mit diesen Dubletten-Entscheiden übernimmt:
      | zeile | entscheid |
      | 2     | NEU       |
    Dann lehnt der CSV-Import mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG» ab
    Wenn «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 1 angelegt, 0 aktualisiert, 3 ausgelassen und 0 gekennzeichnet
    Und hat der Mandant «urinet» diese Verkaufschancen:
      | crm_id | kontakt | titel          | phase   | betrag  | datum      |
      | VC-01  | K1      | Neubau Werkhof | Offerte | 1234.50 | 2026-11-08 |
    Wenn «inhaber-urinet» diese Verkaufschancen-CSV hochlädt:
      """
      GGUID;Kontakt-Nr;Titel;Phase;Betrag;Datum
      VC-01;CRM-T05;Neubau Werkhof;Auftrag;;
      """
    Dann nimmt der CSV-Import die Datei mit Status 202 an, ohne Dialog, Lauf «VORSCHAU_AUSSTEHEND»
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf übernimmt
    Und der Worker alle fälligen Aufträge bearbeitet
    Und «inhaber-urinet» den Lauf abfragt
    Dann ist der Lauf von «inhaber-urinet» übernommen mit 0 angelegt, 1 aktualisiert, 0 ausgelassen und 0 gekennzeichnet
    Und hat der Mandant «urinet» diese Verkaufschancen:
      | crm_id | kontakt | titel          | phase   | betrag  | datum      |
      | VC-01  | K5      | Neubau Werkhof | Auftrag | 1234.50 | 2026-11-08 |
