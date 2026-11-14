# language: de
@spec-01
Funktionalität: Kontakte ansehen und pflegen (Aufgabe 4.11)
  Liste, Detail und Dokumente eines Kontakts sehen die Rollen mit Recht A6 («inhaber», «geschaeftsstelle»); ändern,
  zusammenführen, als inaktiv markieren und wieder aktivieren nur der «inhaber» (A12). Zusammenführen hängt in einer
  Transaktion alle Verweise der Quelle an das Ziel und macht die Quelle inaktiv; Kontakte werden nie gelöscht.
  Inaktive sind kein Ziel neuer Zuordnungen (Req 17.12). Alle Namen, Adressen, E-Mails (example.org) und
  Telefonnummern sind erfunden.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und in den Mandanten «urinet» und «vhf-gsk» gibt es keine Kontakte
    Und im Mandanten «urinet» gibt es diese gepflegten Kontakte:
      | kontakt | typ          | name                    | vorname | strasse    | plz  | ort            | email                     | telefon          | crm_id  | quelle     | nicht_im_export | aktiv |
      | K1      | ORGANISATION | Probe Holzbau AG        |         | Probeweg 1 | 6000 | Musterstadt    | info@holzbau.example.org  | +41 00 000 00 01 | CRM-P01 | CSV_IMPORT | false           | true  |
      | K2      | ORGANISATION | Probe Holzbau Nord GmbH |         | Probeweg 3 | 6000 | Musterstadt    | buero@holzbau.example.org |                  | CRM-P02 | CSV_IMPORT | false           | true  |
      | K3      | PERSON       | Beispiel                | Erika   |            | 6004 | Beispielhausen | erika@example.org         |                  |         | MANUELL    | false           | true  |
      | K4      | ORGANISATION | Ruhende Werkstatt       |         |            | 6010 | Musterstadt    |                           |                  | CRM-P04 | CSV_IMPORT | true            | false |
      | K5      | ORGANISATION | Zeichen Probe AG        |         |            | 6020 | Andersort      | zeichen_probe@example.org |                  |         | MANUELL    | false           | true  |
      | K6      | ORGANISATION | Crm P02 Handel          |         |            | 6030 | Andersort      |                           |                  |         | MANUELL    | false           | true  |
    Und im Mandanten «vhf-gsk» gibt es diese gepflegten Kontakte:
      | kontakt | typ          | name                | plz  | ort         | email                   | crm_id  | quelle     |
      | F1      | ORGANISATION | Fremder Probeverein | 6000 | Musterstadt | info@verein.example.org | CRM-P01 | CSV_IMPORT |
      | F2      | ORGANISATION | Fremde Probe Stiftung | 6001 | Musterstadt |                       |         | MANUELL    |

  @req-17.1 @req-17.2
  Szenario: Ohne Suchbegriff zeigt die Liste nur aktive Kontakte, nach Name sortiert, mit Gesamtzahl
    Wenn «inhaber-urinet» die Kontaktliste abfragt
    Dann listet die Kontaktliste 5 Kontakte, auf der Seite «K6, K3, K1, K2, K5»
    Und nennt die Kontaktliste Seite 0 und Grösse 50
    Und zeigt die Kontaktliste für K1 diese Felder:
      | typ           | ORGANISATION             |
      | name          | Probe Holzbau AG         |
      | vorname       | null                     |
      | plz           | 6000                     |
      | ort           | Musterstadt              |
      | email         | info@holzbau.example.org |
      | quelle        | CSV_IMPORT               |
      | aktiv         | true                     |
      | nichtImExport | false                    |
    Wenn «inhaber-urinet» die Kontaktliste mit «seite=1&groesse=2» abfragt
    Dann listet die Kontaktliste 5 Kontakte, auf der Seite «K1, K2»
    Und nennt die Kontaktliste Seite 1 und Grösse 2
    Wenn «inhaber-urinet» die Kontaktliste mit «seite=9&groesse=2» abfragt
    Dann listet die Kontaktliste 5 Kontakte, auf der Seite «»

  @req-17.2
  Szenariogrundriss: Filter für Status, Herkunft und «nicht mehr im CRM-Export»
    Wenn «inhaber-urinet» die Kontaktliste mit «<parameter>» abfragt
    Dann listet die Kontaktliste <gesamt> Kontakte, auf der Seite «<kontakte>»

    Beispiele:
      | parameter                         | gesamt | kontakte              |
      | status=AKTIV                      | 5      | K6, K3, K1, K2, K5    |
      | status=INAKTIV                    | 1      | K4                    |
      | status=ALLE                       | 6      | K6, K3, K1, K2, K4, K5 |
      | quelle=MANUELL                    | 3      | K6, K3, K5            |
      | quelle=CSV_IMPORT&status=ALLE     | 3      | K1, K2, K4            |
      | nichtImExport=true&status=ALLE    | 1      | K4                    |
      | nichtImExport=false               | 5      | K6, K3, K1, K2, K5    |
      | q=Ruhende                         | 0      |                       |
      | q=Ruhende&status=INAKTIV          | 1      | K4                    |

  @req-17.1
  Szenariogrundriss: Die Suche findet Name, Vorname, E-Mail, PLZ, Ort und die Kontakt-ID aus 21.CRM
    Wenn «inhaber-urinet» die Kontaktliste mit «q=<q>» abfragt
    Dann listet die Kontaktliste <gesamt> Kontakte, auf der Seite «<kontakte>»

    # Sortierung mit Suchbegriff: exakte Kontakt-ID und E-Mail zuerst (K2 vor K6, obwohl «crm p02» in K6 steckt),
    # dann Ähnlichkeit, dann Name. «Zeichen Probr» gleicht «zeichen probe» nur (pg_trgm), enthält es nicht.
    Beispiele:
      | q                         | gesamt | kontakte |
      | holzbau                   | 2      | K1, K2   |
      | HOLZBAU nord              | 1      | K2       |
      | Erika                     | 1      | K3       |
      | beispielhausen            | 1      | K3       |
      | MUSTERSTADT               | 2      | K1, K2   |
      | 6004                      | 1      | K3       |
      | CH-6004                   | 1      | K3       |
      | buero@HOLZBAU.example.org | 1      | K2       |
      | CRM-P02                   | 2      | K2, K6   |
      | CRM-P01                   | 1      | K1       |
      | Zeichen Probr             | 1      | K5       |
      | {leer}                    | 5      | K6, K3, K1, K2, K5 |

  @req-17.1
  Szenariogrundriss: Sonderzeichen von LIKE im Suchbegriff gelten wörtlich
    Wenn «inhaber-urinet» die Kontaktliste mit «q=<q>» abfragt
    Dann listet die Kontaktliste <gesamt> Kontakte, auf der Seite «<kontakte>»

    Beispiele:
      | q  | gesamt | kontakte |
      | _  | 1      | K5       |
      | %  | 0      |          |
      | \\ | 0      |          |

  @req-17.1 @req-17.2
  Szenariogrundriss: Ungültige Parameter der Liste ergeben 400, ohne den Wert in Antwort oder Log
    Angenommen das Log der Kontaktpflege wird mitgeschnitten
    Wenn «inhaber-urinet» die Kontaktliste mit «<parameter>» abfragt
    Dann antwortet die Kontaktpflege mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und enthält das Log weder Kontaktdaten noch Eingabewerte

    Beispiele:
      | parameter              |
      | q={201}                |
      | q=Probe{tab}Holz       |
      | status=FALSCHER_STATUS |
      | quelle=CRM_CSV_FALSCH  |
      | nichtImExport=vielleicht |
      | seite=-1               |
      | seite=erste            |
      | groesse=0              |
      | groesse=101            |
      | groesse=viele          |

  @req-17.1
  Szenario: Ein Suchbegriff mit genau 200 Zeichen ist erlaubt
    Wenn «inhaber-urinet» die Kontaktliste mit «q={200}» abfragt
    Dann listet die Kontaktliste 0 Kontakte, auf der Seite «»

  @req-17.3
  Szenario: Das Detail zeigt Stammdaten, Herkunft und Status, aber keine Zahl der Dokumente
    Wenn «inhaber-urinet» den Kontakt K1 ansieht
    Dann zeigt das Detail diese Felder:
      | id                  | <K1>                     |
      | typ                 | ORGANISATION             |
      | name                | Probe Holzbau AG         |
      | vorname             | null                     |
      | strasse             | Probeweg 1               |
      | plz                 | 6000                     |
      | ort                 | Musterstadt              |
      | email               | info@holzbau.example.org |
      | telefon             | +41 00 000 00 01         |
      | uid                 | null                     |
      | iban                | null                     |
      | kategorie           | null                     |
      | crmId               | CRM-P01                  |
      | quelle              | CSV_IMPORT               |
      | nichtImExport       | false                    |
      | aktiv               | true                     |
      | inaktivSeit         | null                     |
      | zusammengefuehrtIn  | null                     |
      | version             | 0                        |
    Und trägt die Antwort das ETag «"0"»
    Und zeigt das Detail «zusammengeführt aus» «»
    Und nennt das Detail keine Zahl der Dokumente

  @req-17.3
  Szenario: Auch ein inaktiver Kontakt ist lesbar
    Wenn «geschaeftsstelle-vhf-gsk» den Kontakt F1 ansieht
    Dann zeigt das Detail diese Felder:
      | name   | Fremder Probeverein |
      | aktiv  | true                |
    Wenn «inhaber-urinet» den Kontakt K4 ansieht
    Dann zeigt das Detail diese Felder:
      | name               | Ruhende Werkstatt |
      | aktiv              | false             |
      | nichtImExport      | true              |
      | zusammengefuehrtIn | null              |
    Und nennt das Detail keine Zahl der Dokumente

  @req-17.3 @req-1.2
  Szenario: Ein Kontakt eines anderen Mandanten ist unbekannt
    Wenn «inhaber-vhf-gsk» den Kontakt K1 ansieht
    Dann antwortet die Kontaktpflege mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» die Verweise auf den Kontakt K1 abfragt
    Dann antwortet die Kontaktpflege mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» die Kontaktliste mit «status=ALLE» abfragt
    Dann listet die Kontaktliste 2 Kontakte, auf der Seite «F2, F1»
    Wenn «inhaber-vhf-gsk» die Kontaktliste mit «q=CRM-P01» abfragt
    Dann listet die Kontaktliste 1 Kontakte, auf der Seite «F1»

  @req-17.4
  Szenariogrundriss: Die Dokumente des Kontakts folgen der Rechtematrix, ohne verworfene, neueste zuerst
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | person             | datum      |
      | D1       | K1      | ARCHIVIERT | NORMAL          |                    | 2026-03-01 |
      | D2       | K1      | ARCHIVIERT | INTERN          |                    | 2026-02-01 |
      | D3       | K1      | ARCHIVIERT | PERSONAL        | mitarbeiter-urinet | 2026-01-15 |
      | D4       | K1      | ARCHIVIERT | PERSONAL        | inhaber-urinet     | 2026-01-10 |
      | D5       | K1      | VERWORFEN  | NORMAL          |                    | 2026-04-01 |
      | D6       | K2      | ARCHIVIERT | NORMAL          |                    | 2026-05-01 |
    Wenn «<benutzer>» die Dokumente des Kontakts K1 abfragt
    Dann zeigen die Dokumente des Kontakts insgesamt <gesamt>, auf der Seite «<dokumente>»

    Beispiele:
      | benutzer           | gesamt | dokumente      |
      | inhaber-urinet     | 4      | D1, D2, D3, D4 |
      | treuhaender-urinet | 4      | D1, D2, D3, D4 |
      | mitarbeiter-urinet | 2      | D1, D3         |
      | inhaber-vhf-gsk    | 0      |                |

  @req-17.4
  Szenario: Die Dokumente des Kontakts lassen sich blättern, die Geschäftsstelle sieht ihren Mandanten
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | datum      |
      | D1       | K1      | ARCHIVIERT | NORMAL          | 2026-03-01 |
      | D2       | K1      | ARCHIVIERT | INTERN          | 2026-02-01 |
      | D3       | K1      | ARCHIVIERT | NORMAL          |            |
    Und im Mandanten «vhf-gsk» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | datum      |
      | D7       | F1      | ARCHIVIERT | INTERN          | 2026-06-01 |
    Wenn «inhaber-urinet» die Seite 1 mit 2 Dokumenten des Kontakts K1 abfragt
    Dann zeigen die Dokumente des Kontakts insgesamt 3, auf der Seite «D3»
    Wenn «geschaeftsstelle-vhf-gsk» die Dokumente des Kontakts F1 abfragt
    Dann zeigen die Dokumente des Kontakts insgesamt 1, auf der Seite «D7»

  @req-17.5
  Szenariogrundriss: Ohne Recht A6 keine Liste und kein Detail, jede Verweigerung wird protokolliert
    Angenommen der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «<benutzer>» die Kontaktliste abfragt
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A6    | NICHT_ERLAUBT |
    Angenommen der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «<benutzer>» den Kontakt K1 ansieht
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A6    | NICHT_ERLAUBT |

    Beispiele:
      | benutzer           | rolle       |
      | mitarbeiter-urinet | MITARBEITER |
      | treuhaender-urinet | TREUHAENDER |

  @req-17.5
  Szenariogrundriss: Mit Recht A6 sehen Inhaber und Geschäftsstelle Liste und Detail ihres Mandanten
    Wenn «<benutzer>» die Kontaktliste abfragt
    Dann antwortet die Kontaktpflege mit Status 200
    Wenn «<benutzer>» den Kontakt <kontakt> ansieht
    Dann antwortet die Kontaktpflege mit Status 200

    Beispiele:
      | benutzer                 | kontakt |
      | inhaber-urinet           | K1      |
      | geschaeftsstelle-vhf-gsk | F1      |

  @req-17.6
  Szenariogrundriss: Ohne Rolle inhaber kein Ändern, Zusammenführen, Markieren oder Aktivieren, protokolliert
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Und der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Kontakt <quelle> in den Kontakt <ziel> zusammenführt
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A12   | NICHT_ERLAUBT |
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Kontakt <quelle> als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A12   | NICHT_ERLAUBT |
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Kontakt <inaktiv> wieder aktiviert
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A12   | NICHT_ERLAUBT |
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» den Kontakt <quelle> in «Geändert Probe» umbenennt
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A12   | NICHT_ERLAUBT |
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Wenn «<benutzer>» die Verweise auf den Kontakt <quelle> abfragt
    Dann antwortet die Kontaktpflege mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und das Zugriffsprotokoll von «<mandant>» hat genau einen neuen Eintrag:
      | benutzer   | rolle   | aktion     | dokument | ip | grund | fehlercode    |
      | <benutzer> | <rolle> | VERWEIGERT |          |    | A12   | NICHT_ERLAUBT |
    Und sind Kontakte und Dokumente unverändert

    Beispiele:
      | benutzer                 | rolle            | mandant | quelle | ziel | inaktiv |
      | mitarbeiter-urinet       | MITARBEITER      | urinet  | K1     | K2   | K4      |
      | treuhaender-urinet       | TREUHAENDER      | urinet  | K1     | K2   | K4      |
      | geschaeftsstelle-vhf-gsk | GESCHAEFTSSTELLE | vhf-gsk | F1     | F2   | F2      |

  @req-17.7 @req-17.8 @req-17.9
  Szenario: Zusammenführen hängt alle Verweise an das Ziel und macht die Quelle inaktiv
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | ki_partner | status      | vertraulichkeit | datum      |
      | D1       | K1      | K1         | ARCHIVIERT  | NORMAL          | 2026-03-01 |
      | D2       | K1      |            | EINGANGSKORB | NORMAL         |            |
      | D5       | K1      |            | VERWORFEN   | NORMAL          | 2026-04-01 |
      | D6       | K2      |            | ARCHIVIERT  | NORMAL          | 2026-05-01 |
      | D8       | K3      | K1         | EINGANGSKORB | NORMAL         |            |
    Und am Kontakt K1 hängen 2 Verkaufschancen und 1 Korrekturbeispiele zum Dokument D1
    Und das Log der Kontaktpflege wird mitgeschnitten
    Wenn «inhaber-urinet» die Verweise auf den Kontakt K1 abfragt
    Dann zählen die Verweise 3 Dokumente, 2 Verkaufschancen und 1 Korrekturbeispiele
    Wenn «inhaber-urinet» den Kontakt K1 in den Kontakt K2 zusammenführt
    Dann meldet das Zusammenführen das Ziel K2 mit 3 Dokumenten, 2 Verkaufschancen und 1 Korrekturbeispielen
    Und trägt die Antwort das ETag «"1"»
    Und haben die Dokumente «D1, D2, D5, D6» den Partner K2
    Und haben die Dokumente «D1, D8» den Partner des KI-Vorschlags K2
    Und hängen am Kontakt K2 2 Verkaufschancen und 1 Korrekturbeispiele
    Und hängen am Kontakt K1 0 Verkaufschancen und 0 Korrekturbeispiele
    Und hat das Dokument D1 die Version 1, den Partnernamen «Partner K1» und 1 Fassungen mit dem Grund «Kontakt zusammengeführt: Doppelt erfasst (Probe)»
    Und hat das Dokument D2 die Version 1, den Partnernamen «Partner K1» und 0 Fassungen mit dem Grund «Kontakt zusammengeführt: Doppelt erfasst (Probe)»
    Und hat das Dokument D6 die Version 0, den Partnernamen «Partner K2» und 0 Fassungen mit dem Grund «Kontakt zusammengeführt: Doppelt erfasst (Probe)»
    Und hat das Dokument D8 die Version 0, den Partnernamen «Partner K3» und 0 Fassungen mit dem Grund «Kontakt zusammengeführt: Doppelt erfasst (Probe)»
    Und gibt es für das Dokument D1 keinen Auftrag «EMBEDDING»
    Und ist der Kontakt K1 inaktiv und in K2 zusammengeführt, mit Version 1
    Und hat der Kontakt K1 den Namen «Probe Holzbau AG» und die Kontakt-ID «CRM-P01»
    Und ist der Kontakt K2 aktiv mit Version 0
    Und hat der Kontakt K2 den Namen «Probe Holzbau Nord GmbH» und die Kontakt-ID «CRM-P02»
    Und hält das Zugriffsprotokoll von «urinet» das Zusammenführen von K1 in K2 mit 3 Dokumenten, 2 Verkaufschancen und 1 Korrekturbeispielen fest, dazu je einen Eintrag für «D1, D2, D5»
    Und enthält das Log weder Kontaktdaten noch Eingabewerte
    Und nennt das Log die ID von K1
    Wenn «inhaber-urinet» den Kontakt K1 ansieht
    Dann zeigt das Detail diese Felder:
      | aktiv                   | false     |
      | zusammengefuehrtIn.id   | <K2>      |
      | zusammengefuehrtIn.name | Probe Holzbau Nord GmbH |
      | version                 | 1         |
    Wenn «inhaber-urinet» den Kontakt K2 ansieht
    Dann zeigt das Detail «zusammengeführt aus» «K1»
    Wenn «inhaber-urinet» die Verweise auf den Kontakt K1 abfragt
    Dann zählen die Verweise 0 Dokumente, 0 Verkaufschancen und 0 Korrekturbeispiele

  @req-17.4 @req-17.7
  Szenario: Nach dem Zusammenführen ist die Liste der Quelle leer, das Ziel hat ihre Dokumente
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | datum      |
      | D1       | K1      | ARCHIVIERT | NORMAL          | 2026-03-01 |
      | D2       | K1      | ARCHIVIERT | INTERN          | 2026-02-01 |
      | D6       | K2      | ARCHIVIERT | NORMAL          | 2026-05-01 |
    Wenn «inhaber-urinet» den Kontakt K1 in den Kontakt K2 zusammenführt
    Dann antwortet die Kontaktpflege mit Status 200
    Wenn «inhaber-urinet» die Dokumente des Kontakts K1 abfragt
    Dann zeigen die Dokumente des Kontakts insgesamt 0, auf der Seite «»
    Wenn «inhaber-urinet» die Dokumente des Kontakts K2 abfragt
    Dann zeigen die Dokumente des Kontakts insgesamt 3, auf der Seite «D6, D1, D2»
    Wenn «mitarbeiter-urinet» die Dokumente des Kontakts K2 abfragt
    Dann zeigen die Dokumente des Kontakts insgesamt 2, auf der Seite «D6, D1»

  @req-17.12 @req-17.7
  Szenario: Die Quelle eines Zusammenführens erscheint nicht mehr in der Partnerauswahl
    Wenn «inhaber-urinet» den Kontakt K1 in den Kontakt K2 zusammenführt
    Dann antwortet die Kontaktpflege mit Status 200
    Wenn «inhaber-urinet» in der Partnerauswahl nach «Probe Holzbau» sucht
    Dann enthält die Partnerauswahl K2, aber nicht K1
    Wenn «inhaber-urinet» die Kontaktliste mit «q=holzbau» abfragt
    Dann listet die Kontaktliste 1 Kontakte, auf der Seite «K2»
    Wenn «inhaber-urinet» die Kontaktliste mit «q=holzbau&status=INAKTIV» abfragt
    Dann listet die Kontaktliste 1 Kontakte, auf der Seite «K1»

  @req-17.10
  Szenariogrundriss: Ungültige Zusammenführungen ändern nichts
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | datum      |
      | D1       | K1      | ARCHIVIERT | NORMAL          | 2026-03-01 |
    Und der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt <quelle> mit diesem Körper zusammenführt:
      """
      <koerper>
      """
    Dann antwortet die Kontaktpflege mit Status <status> und Fehlercode «<code>»
    Und sind Kontakte und Dokumente unverändert

    Beispiele:
      | quelle | koerper                                                              | status | code              |
      | K1     | {"ziel": "<K1>", "grund": "Probe"}                                   | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "00000000-0000-4000-8000-0000000000aa", "grund": "Probe"}   | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "<F1>", "grund": "Probe"}                                   | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "kein-uuid", "grund": "Probe"}                              | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "<K2>"}                                                     | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "<K2>", "grund": "  "}                                      | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "<K2>", "grund": "{1001}"}                                  | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "<K2>", "grund": "Probe", "zusatz": 1}                      | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"grund": "Probe"}                                                   | 400    | ANFRAGE_UNGUELTIG |
      | K1     | {"ziel": "<K2>", "grund": "Probe"} x                                 | 400    | ANFRAGE_UNGUELTIG |
      | K4     | {"ziel": "<K2>", "grund": "Probe"}                                   | 409    | STATUS_KONFLIKT   |
      | K1     | {"ziel": "<K4>", "grund": "Probe"}                                   | 409    | STATUS_KONFLIKT   |

  @req-17.10
  Szenario: Ein Grund mit genau 1000 Zeichen ist erlaubt
    Wenn «inhaber-urinet» den Kontakt K1 mit diesem Körper zusammenführt:
      """
      {"ziel": "<K2>", "grund": "{1000}"}
      """
    Dann antwortet die Kontaktpflege mit Status 200

  @req-17.10
  Szenariogrundriss: Ohne passendes If-Match der Quelle ändert das Zusammenführen nichts
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K1 in den Kontakt K2 mit If-Match «<if-match>» zusammenführt
    Dann antwortet die Kontaktpflege mit Status <status> und Fehlercode «<code>»
    Und sind Kontakte und Dokumente unverändert

    Beispiele:
      | if-match | status | code             |
      | {ohne}   | 428    | VERSION_FEHLT    |
      | "7"      | 412    | VERSION_KONFLIKT |
      | W/"0"    | 412    | VERSION_KONFLIKT |
      | *        | 412    | VERSION_KONFLIKT |
      | 0        | 412    | VERSION_KONFLIKT |

  @req-17.10 @req-1.2
  Szenario: Eine Quelle aus einem anderen Mandanten ist unbekannt, nichts ändert sich
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-vhf-gsk» den Kontakt K1 in den Kontakt F1 zusammenführt
    Dann antwortet die Kontaktpflege mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» den Kontakt K1 als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» den Kontakt K4 wieder aktiviert
    Dann antwortet die Kontaktpflege mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» den Kontakt K1 in «Fremd Probe» umbenennt
    Dann antwortet die Kontaktpflege mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Und sind Kontakte und Dokumente unverändert

  @req-17.7 @req-10.8
  Szenario: Scheitert das Protokoll mitten in der Transaktion, ändert sich nichts
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | ki_partner | status     | vertraulichkeit | datum      |
      | D1       | K1      | K1         | ARCHIVIERT | NORMAL          | 2026-03-01 |
      | D2       | K1      |            | ARCHIVIERT | NORMAL          | 2026-03-02 |
    Und am Kontakt K1 hängen 1 Verkaufschancen und 1 Korrekturbeispiele zum Dokument D1
    Und der Stand der Kontakte und Dokumente ist gemerkt
    Und das Zugriffsprotokoll von «urinet» lehnt den Eintrag zum Zusammenführen ab
    Wenn «inhaber-urinet» den Kontakt K1 in den Kontakt K2 zusammenführt
    Dann antwortet die Kontaktpflege mit Status 500 und Fehlercode «INTERNER_FEHLER»
    Und sind Kontakte und Dokumente unverändert

  @req-17.8 @req-10.4
  Szenario: Ein Ändern des Dokuments mit der Version vor dem Zusammenführen scheitert mit 412
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | datum      |
      | D1       | K1      | ARCHIVIERT | NORMAL          | 2026-03-01 |
    Und die Version des Dokuments D1 ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K1 in den Kontakt K2 zusammenführt
    Dann antwortet die Kontaktpflege mit Status 200
    Wenn «inhaber-urinet» das Dokument D1 mit der gemerkten Version ändert
    Dann antwortet das Ändern des Dokuments mit Status 412 und Fehlercode «VERSION_KONFLIKT»

  @req-17.7
  Szenario: Zwei gegenläufige Zusammenführungen verklemmen nicht
    Wenn «inhaber-urinet» gleichzeitig K1 in K2 und K2 in K1 zusammenführt
    Dann endet eine der gleichzeitigen Zusammenführungen mit 200 und die andere mit 409 «STATUS_KONFLIKT»

  @req-17.7 @req-17.14
  Szenario: Hält eine laufende Übernahme einen der Kontakte, endet das Warten mit 409
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Und eine andere Transaktion den Kontakt K2 sperrt, wie es eine laufende Übernahme tut
    Wenn «inhaber-urinet» den Kontakt K1 mit diesem Körper zusammenführt:
      """
      {"ziel": "<K2>", "grund": "Probe"}
      """
    Dann antwortet die Kontaktpflege nach höchstens 5 Sekunden mit Status 409 und Fehlercode «STATUS_KONFLIKT»
    Wenn die andere Transaktion die Sperre des Kontakts freigibt
    Dann sind Kontakte und Dokumente unverändert

  @req-17.7
  Szenario: Zusammenführen von 1'000 archivierten Dokumenten in einer Transaktion
    Angenommen am Kontakt K1 hängen 1000 weitere archivierte Dokumente
    Wenn «inhaber-urinet» den Kontakt K1 mit diesem Körper zusammenführt:
      """
      {"ziel": "<K2>", "grund": "Probe Masse"}
      """
    Dann dauerte das Zusammenführen höchstens 30 Sekunden

  @req-17.11
  Szenario: Als inaktiv markieren lässt Stammdaten und Zuordnungen und wird protokolliert
    Angenommen im Mandanten «urinet» gibt es diese Dokumente zu Kontakten:
      | dokument | partner | status     | vertraulichkeit | datum      |
      | D1       | K3      | ARCHIVIERT | NORMAL          | 2026-03-01 |
    Wenn «inhaber-urinet» den Kontakt K3 mit dem Grund «Kein Kunde mehr (Probe)» als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 200
    Und trägt die Antwort das ETag «"1"»
    Und ist der Kontakt K3 inaktiv ohne Zusammenführung, mit Version 1
    Und hat der Kontakt K3 den Namen «Beispiel» und die Kontakt-ID «null»
    Und haben die Dokumente «D1» den Partner K3
    Und hat das Dokument D1 die Version 0, den Partnernamen «Partner K3» und 0 Fassungen mit dem Grund «Kein Kunde mehr (Probe)»
    Und hält das Zugriffsprotokoll von «urinet» «KONTAKT_INAKTIV» für den Kontakt K3 mit dem Grund «Kein Kunde mehr (Probe)» fest
    Wenn «inhaber-urinet» den Kontakt K3 als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 409 und Fehlercode «STATUS_KONFLIKT»

  @req-17.11
  Szenario: Ohne Grund markieren genügt
    Wenn «inhaber-urinet» den Kontakt K5 als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 200
    Und hält das Zugriffsprotokoll von «urinet» «KONTAKT_INAKTIV» für den Kontakt K5 mit dem Grund «» fest

  @req-17.11
  Szenariogrundriss: Ungültiges Markieren ändert nichts
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K5 mit dem Körper <koerper> als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und sind Kontakte und Dokumente unverändert

    Beispiele:
      | koerper                              |
      | '{"grund": "Probe", "zusatz": true}' |
      | '{"grund": {"text": "Probe"}}'       |
      | '[1]'                                |

  @req-17.11
  Szenariogrundriss: Ohne passendes If-Match ändert das Markieren nichts
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K5 mit If-Match «<if-match>» als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status <status> und Fehlercode «<code>»
    Und sind Kontakte und Dokumente unverändert

    Beispiele:
      | if-match | status | code             |
      | {ohne}   | 428    | VERSION_FEHLT    |
      | "3"      | 412    | VERSION_KONFLIKT |

  @req-17.11
  Szenario: Ein Grund über 1000 Zeichen ändert nichts
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K5 mit dem Grund «{1001}» als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und sind Kontakte und Dokumente unverändert

  @req-17.12
  Szenario: Ein inaktiv markierter Kontakt erscheint nicht in der Partnerauswahl
    Wenn «inhaber-urinet» den Kontakt K3 als inaktiv markiert
    Dann antwortet die Kontaktpflege mit Status 200
    Wenn «inhaber-urinet» in der Partnerauswahl nach «Erika Beispiel» sucht
    Dann enthält die Partnerauswahl keinen der Kontakte «K3»
    Wenn «inhaber-urinet» in der Partnerauswahl nach «Probe» sucht
    Dann enthält die Partnerauswahl K1, aber nicht K4

  @req-17.15
  Szenario: Ein inaktiv markierter Kontakt lässt sich wieder aktivieren
    Wenn «inhaber-urinet» den Kontakt K4 wieder aktiviert
    Dann antwortet die Kontaktpflege mit Status 200
    Und trägt die Antwort das ETag «"1"»
    Und ist der Kontakt K4 aktiv mit Version 1
    Und hält das Zugriffsprotokoll von «urinet» «KONTAKT_AKTIV» für den Kontakt K4 mit dem Grund «» fest
    Wenn «inhaber-urinet» in der Partnerauswahl nach «Ruhende Werkstatt» sucht
    Dann enthält die Partnerauswahl K4, aber nicht K1
    Wenn «inhaber-urinet» den Kontakt K4 wieder aktiviert
    Dann antwortet die Kontaktpflege mit Status 409 und Fehlercode «STATUS_KONFLIKT»

  @req-17.15
  Szenario: Ein zusammengeführter Kontakt bleibt inaktiv
    Angenommen der Kontakt K4 wurde schon in den Kontakt K2 zusammengeführt
    Und der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K4 wieder aktiviert
    Dann antwortet die Kontaktpflege mit Status 409 und Fehlercode «STATUS_KONFLIKT»
    Und sind Kontakte und Dokumente unverändert

  @req-17.15
  Szenariogrundriss: Ohne passendes If-Match ändert das Aktivieren nichts
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K4 mit If-Match «<if-match>» wieder aktiviert
    Dann antwortet die Kontaktpflege mit Status <status> und Fehlercode «<code>»
    Und sind Kontakte und Dokumente unverändert

    Beispiele:
      | if-match | status | code             |
      | {ohne}   | 428    | VERSION_FEHLT    |
      | "5"      | 412    | VERSION_KONFLIKT |

  @req-17.16
  Szenariogrundriss: Einen inaktiven Kontakt zu ändern lehnt das Archiv mit 409 ab
    Angenommen der Stand der Kontakte und Dokumente ist gemerkt
    Wenn «inhaber-urinet» den Kontakt K4 mit If-Match «<if-match>» in «Geändert Probe» umbenennt
    Dann antwortet die Kontaktpflege mit Status <status> und Fehlercode «<code>»
    Und sind Kontakte und Dokumente unverändert

    # 409 vor 412: Neu laden hilft bei einem inaktiven Kontakt nicht (Auslegung Aufgabe 4.11)
    Beispiele:
      | if-match | status | code            |
      | "0"      | 409    | STATUS_KONFLIKT |
      | "9"      | 409    | STATUS_KONFLIKT |
      | {ohne}   | 428    | VERSION_FEHLT   |
