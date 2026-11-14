# language: de
@spec-01
Funktionalität: Kontakte und Partner-Abgleich
  Kontakte suchen (Partnerauswahl, A6) und von Hand pflegen (A12, nur «inhaber», Req 2.18) unter «/api/v1/kontakte».
  Der Partner-Abgleich (Req 7.4) prüft E-Mail, UID und IBAN, sonst die Namensähnlichkeit ab 0.6 (pg_trgm in der
  Datenbank); liegt der Zweitbeste weniger als 0.1 dahinter, ist der Treffer «unsicher». Den Abgleich ruft ab
  Aufgabe 3.2 der Worker auf; hier ruft ihn das Szenario im Mandanten-Kontext auf wie der Worker.
  Alle Namen und Werte sind synthetisch.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und in den Mandanten «urinet» und «vhf-gsk» gibt es keine Kontakte

  @req-2.18
  Szenariogrundriss: Ohne Rolle inhaber darf niemand Kontakte anlegen
    Wenn «<benutzer>» den Kontakt «Musterfirma Probe AG» anlegt
    Dann antwortet die Kontakt-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und es gibt keine Kontakte mehr

    Beispiele:
      | benutzer                |
      | mitarbeiter-urinet      |
      | treuhaender-urinet      |
      | geschaeftsstelle-vhf-gsk |

  @req-2.18
  Szenariogrundriss: Ohne Rolle inhaber darf niemand Kontakte ändern
    Angenommen «inhaber-urinet» hat den Kontakt «Musterfirma Probe AG» angelegt
    Wenn «<benutzer>» den Kontakt «Musterfirma Probe AG» in «Geändert GmbH» umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und der Kontakt «Musterfirma Probe AG» hat noch Version 0

    Beispiele:
      | benutzer           |
      | mitarbeiter-urinet |
      | treuhaender-urinet |

  @req-2.18
  Szenario: Der inhaber legt einen Kontakt an und ändert ihn mit If-Match
    Angenommen alle Logeinträge zu Kontakten werden mitgeschnitten
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» samt E-Mail « Info@Musterfirma.TEST » erfasst
    Dann antwortet die Kontakt-Schnittstelle mit Status 201 und ETag «"0"»
    Und der Kontakt hat die E-Mail «info@musterfirma.test» und die Quelle «MANUELL»
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» in «Musterfirma Neu AG» umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 200 und ETag «"1"»
    Und der Kontakt hat die E-Mail «info@musterfirma.test» und die Quelle «MANUELL»
    Und kein Logeintrag enthält einen Wert der Kontakte

  @req-2.18
  Szenario: Ändern ohne If-Match oder mit veralteter Version ändert nichts
    Angenommen «inhaber-urinet» hat den Kontakt «Musterfirma Probe AG» angelegt
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» ohne If-Match umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» mit If-Match «"7"» umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» mit If-Match «*» umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Und der Kontakt «Musterfirma Probe AG» hat noch Version 0

  @req-2.18
  Szenariogrundriss: Nur ein starkes ETag passt zu If-Match
    Angenommen «inhaber-urinet» hat den Kontakt «Musterfirma Probe AG» angelegt
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» mit If-Match «<if-match>» umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Und der Kontakt «Musterfirma Probe AG» hat noch Version 0

    Beispiele:
      | if-match |
      | W/"0"    |
      | 0        |
      | "0       |
      | 0"       |

  @req-2.18
  Szenariogrundriss: Angaben bis zur Höchstlänge werden übernommen
    Wenn «inhaber-urinet» einen Kontakt anlegt, dessen Feld «<feld>» <laenge> Zeichen hat
    Dann antwortet die Kontakt-Schnittstelle mit Status 201

    Beispiele:
      | feld      | laenge |
      | name      | 200    |
      | plz       | 20     |
      | telefon   | 50     |
      | uid       | 30     |
      | iban      | 40     |
      | kategorie | 100    |

  @req-2.18
  Szenariogrundriss: Zu lange Angaben werden abgewiesen
    Wenn «inhaber-urinet» einen Kontakt anlegt, dessen Feld «<feld>» <laenge> Zeichen hat
    Dann antwortet die Kontakt-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und es gibt keine Kontakte mehr

    Beispiele:
      | feld      | laenge |
      | name      | 201    |
      | vorname   | 201    |
      | strasse   | 201    |
      | plz       | 21     |
      | ort       | 201    |
      | email     | 201    |
      | telefon   | 51     |
      | uid       | 31     |
      | iban      | 41     |
      | kategorie | 101    |

  @req-2.18
  Szenario: Eine zu lange Änderung ändert nichts
    Angenommen «inhaber-urinet» hat den Kontakt «Musterfirma Probe AG» angelegt
    Wenn «inhaber-urinet» den Kontakt «Musterfirma Probe AG» so ändert, dass das Feld «name» 201 Zeichen hat
    Dann antwortet die Kontakt-Schnittstelle mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und der Kontakt «Musterfirma Probe AG» hat noch Version 0

  @req-2.18
  Szenario: Ein Kontakt ohne Name wird nicht angelegt
    Wenn «inhaber-urinet» einen Kontakt ohne Name anlegt
    Dann antwortet die Kontakt-Schnittstelle mit Status 422 und Fehlercode «PFLICHTFELD_FEHLT»
    Und es gibt keine Kontakte mehr

  @req-2.6
  Szenariogrundriss: Die Partnerauswahl folgt der Zeile A6 der Rechtematrix
    Wenn «<benutzer>» Kontakte mit «Musterfirma» sucht
    Dann antwortet die Kontakt-Schnittstelle mit Status <status>

    Beispiele:
      | benutzer                 | status |
      | inhaber-urinet           | 200    |
      | geschaeftsstelle-vhf-gsk | 200    |
      | mitarbeiter-urinet       | 403    |
      | treuhaender-urinet       | 403    |

  @req-2.5
  Szenario: Ein API-Token erreicht die Kontakte nicht
    Angenommen für «urinet» gibt es ein API-Token mit Scope «import»
    Wenn das API-Token einen Kontakt «Musterfirma Probe AG» anlegt
    Dann antwortet die Kontakt-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und es gibt keine Kontakte mehr
    Wenn das API-Token Kontakte mit «Musterfirma» sucht
    Dann antwortet die Kontakt-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  @req-17.5 @req-17.6 @req-2.5
  Szenariogrundriss: Ein API-Token erreicht weder Liste noch Detail noch die Pflege der Kontakte
    Angenommen für «urinet» gibt es ein API-Token mit Scope «<scope>»
    Wenn das API-Token die Kontakte über «<methode> <pfad>» erreichen will
    Dann antwortet die Kontakt-Schnittstelle mit Status 403 und Fehlercode «NICHT_ERLAUBT»

    Beispiele:
      | scope     | methode | pfad                    |
      | import    | GET     | /liste                  |
      | generator | GET     | /{id}                   |
      | import    | GET     | /{id}/verweise          |
      | import    | POST    | /{id}/zusammenfuehren   |
      | import    | POST    | /{id}/inaktiv           |
      | generator | POST    | /{id}/aktiv             |

  @req-7.4 @req-17.12
  Szenario: Inaktive Kontakte sind weder Treffer noch Kandidat des Abgleichs und fehlen in der Partnerauswahl
    Angenommen im Mandanten «urinet» gibt es die Kontakte:
      | name                    | typ          | email                     | uid                  | iban |
      | Sportclub Seeblick      | ORGANISATION | sekretariat@sc.example.org | CHE-100.200.300 MWST |      |
      | Sportclub Seeblick Nord | ORGANISATION |                           |                      |      |
    Und der Kontakt «Sportclub Seeblick» im Mandanten «urinet» ist inaktiv
    Wenn der Abgleich in «urinet» den Partner «» mit E-Mail «sekretariat@sc.example.org», UID «» und IBAN «» sucht
    Dann ergibt der Abgleich «neuer Partner»
    Wenn der Abgleich in «urinet» den Partner «» mit E-Mail «», UID «CHE-100.200.300» und IBAN «» sucht
    Dann ergibt der Abgleich «neuer Partner»
    # aktiv wäre «sportclub seeblick» mit 1.0 der Beste; inaktiv bleibt «… nord» mit 0.78 allein
    Wenn der Abgleich in «urinet» den Partner «Sportclub Seeblick» mit E-Mail «», UID «» und IBAN «» sucht
    Dann ergibt der Abgleich «verknüpft: Sportclub Seeblick Nord»
    Wenn «inhaber-urinet» Kontakte mit «Sportclub» sucht
    Dann enthält die Trefferliste den Kontakt «Sportclub Seeblick Nord»
    Und enthält die Trefferliste den Kontakt «Sportclub Seeblick» nicht

  @req-1.2
  Szenario: Kontakte bleiben im eigenen Mandanten
    Angenommen «inhaber-urinet» hat den Kontakt «Musterfirma Probe AG» angelegt
    Wenn «inhaber-urinet» Kontakte mit «Musterfirma» sucht
    Dann enthält die Trefferliste den Kontakt «Musterfirma Probe AG»
    Wenn «inhaber-vhf-gsk» Kontakte mit «Musterfirma» sucht
    Dann enthält die Trefferliste den Kontakt «Musterfirma Probe AG» nicht
    Wenn «inhaber-vhf-gsk» den Kontakt «Musterfirma Probe AG» in «Fremd AG» umbenennt
    Dann antwortet die Kontakt-Schnittstelle mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und der Kontakt «Musterfirma Probe AG» hat noch Version 0

  @req-1.2
  Szenariogrundriss: Der Partner-Abgleich findet nur Kontakte des eigenen Mandanten
    Angenommen im Mandanten «<eigener>» gibt es die Kontakte:
      | name               | typ          | email               | uid                  | iban                       |
      | Sportclub Seeblick | ORGANISATION | sekretariat@sc.test | CHE-100.200.300 MWST | CH93 0076 2011 6238 5295 7 |
    Wenn der Abgleich in «<fremder>» den Partner «<name>» mit E-Mail «<email>», UID «<uid>» und IBAN «<iban>» sucht
    Dann ergibt der Abgleich «neuer Partner»
    Wenn der Abgleich in «<eigener>» den Partner «<name>» mit E-Mail «<email>», UID «<uid>» und IBAN «<iban>» sucht
    Dann verknüpft der Abgleich den Kontakt «Sportclub Seeblick» über «<merkmal>»

    Beispiele:
      | eigener | fremder | name               | email               | uid             | iban                  | merkmal |
      | urinet  | vhf-gsk |                    | sekretariat@sc.test |                 |                       | EMAIL   |
      | urinet  | vhf-gsk |                    |                     | CHE-100.200.300 |                       | UID     |
      | urinet  | vhf-gsk |                    |                     |                 | CH9300762011623852957 | IBAN    |
      | urinet  | vhf-gsk | Sportclub Seeblick |                     |                 |                       | NAME    |
      | vhf-gsk | urinet  |                    | sekretariat@sc.test |                 |                       | EMAIL   |
      | vhf-gsk | urinet  |                    |                     | CHE-100.200.300 |                       | UID     |
      | vhf-gsk | urinet  |                    |                     |                 | CH9300762011623852957 | IBAN    |
      | vhf-gsk | urinet  | Sportclub Seeblick |                     |                 |                       | NAME    |

  @req-7.4
  Szenariogrundriss: Exakte Merkmale gehen vor dem Namen
    Angenommen im Mandanten «urinet» gibt es die Kontakte:
      | name               | typ          | email                | uid                  | iban                       |
      | Sportclub Seeblick | ORGANISATION | sekretariat@sc.test  |                      |                            |
      | Beispiel Druck AG  | ORGANISATION |                      | CHE-100.200.300 MWST |                            |
      | Alpen Garage GmbH  | ORGANISATION |                      |                      | CH93 0076 2011 6238 5295 7 |
    Wenn der Abgleich in «urinet» den Partner «<name>» mit E-Mail «<email>», UID «<uid>» und IBAN «<iban>» sucht
    Dann verknüpft der Abgleich den Kontakt «<kontakt>» über «<merkmal>»

    Beispiele:
      | name               | email                 | uid             | iban                    | kontakt            | merkmal |
      | Alpen Garage       | Sekretariat@SC.test   |                 |                         | Sportclub Seeblick | EMAIL   |
      | Sportclub Seeblick |                       | CHE100200300    |                         | Beispiel Druck AG  | UID     |
      |                    |                       |                 | ch9300762011623852957   | Alpen Garage GmbH  | IBAN    |
      | Sport Club Seeblick |                      |                 |                         | Sportclub Seeblick | NAME    |
      | Beispiel Druckerei |                       | CHE-999.999.999 |                         | Beispiel Druck AG  | NAME    |

  @req-7.4
  Szenario: Widersprechen sich die Merkmale, ist der Abgleich unsicher
    Angenommen im Mandanten «urinet» gibt es die Kontakte:
      | name               | typ          | email               | uid | iban                       |
      | Sportclub Seeblick | ORGANISATION | sekretariat@sc.test |     |                            |
      | Alpen Garage GmbH  | ORGANISATION |                     |     | CH93 0076 2011 6238 5295 7 |
    Wenn der Abgleich in «urinet» den Partner «Sportclub Seeblick» mit E-Mail «sekretariat@sc.test», UID «» und IBAN «CH9300762011623852957» sucht
    Dann ist der Abgleich unsicher zwischen «Sportclub Seeblick» und «Alpen Garage GmbH»

  @req-7.4
  Szenariogrundriss: Namensähnlichkeit mit Schwelle 0.6 und Abstand 0.1
    Angenommen im Mandanten «urinet» gibt es die Kontakte:
      | name                    | typ          | email | uid | iban |
      | Sportclub Seeblick Nord | ORGANISATION |       |     |      |
      | Sportclub Seeblick Süd  | ORGANISATION |       |     |      |
      | Bergbahn Probe AG       | ORGANISATION |       |     |      |
      | Bergbahn Probe GmbH     | ORGANISATION |       |     |      |
      | Alpen Garage AG         | ORGANISATION |       |     |      |
      | Alpen Garagen           | ORGANISATION |       |     |      |
      | AB                      | ORGANISATION |       |     |      |
    Wenn der Abgleich in «urinet» den Partner «<name>» mit E-Mail «», UID «» und IBAN «» sucht
    Dann ergibt der Abgleich «<ergebnis>»

    # Ähnlichkeiten (pg_trgm, normalisierte Namen): «sportclub seeblick» 0.82 zu «… sued», 0.78 zu «… nord»;
    # «alpin garage» 0.63 zu «alpen garage», 0.53 zu «alpen garagen» (Zweitbester unter der Schwelle, aber im Abstand);
    # «alpen garagen» 1.0 zu sich, 0.86 zu «alpen garage»; «sportclub seewald» höchstens 0.44; «abc» 0.4 zu «ab»
    Beispiele:
      | name                 | ergebnis                                                 |
      | Sportclub Seeblick   | unsicher: Sportclub Seeblick Süd, Sportclub Seeblick Nord |
      | Bergbahn Probe       | unsicher: Bergbahn Probe AG, Bergbahn Probe GmbH          |
      | Alpin Garage         | unsicher: Alpen Garage AG, Alpen Garagen                  |
      | Alpen Garagen        | verknüpft: Alpen Garagen                                  |
      | Alpen Garage         | verknüpft: Alpen Garage AG                                |
      | Turnverein Bergwiese | neuer Partner                                            |
      | Sportclub Seewald    | neuer Partner                                            |
      | AB                   | verknüpft: AB                                            |
      | ABC                  | neuer Partner                                            |
      | A                    | neuer Partner                                            |
      | AG                   | neuer Partner                                            |
      |                      | neuer Partner                                            |
