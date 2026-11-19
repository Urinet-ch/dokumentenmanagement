# language: de
@spec-02
Funktionalität: Werkzeuge des Chat-Agenten: Kennzahl und offene Posten (Aufgabe 7.1b)
  Die Werkzeuge «kennzahl» und «offene_posten» rechnen im Archiv, nie im Sprachmodell. Die Szenarien rufen sie wie
  «werkzeuge.feature» ohne Sprachmodell auf, jeden Aufruf in einem eigenen Thread ohne Anmeldung. Welche Arten zählen,
  steht je Mandant in der Konfiguration (Entscheid Fabian 14.11.2026, E1): Einnahmen AUSGANGSRECHNUNG; Ausgaben
  LIEFERANTENBELEG, QUITTUNG, BEHOERDE und LOHNABRECHNUNG nur mit Monatsangabe (periode JJJJ-MM). OFFERTE,
  VERSICHERUNG und der Lohnausweis (periode JJJJ) zählen nicht. Ohne AbaNinja (Welle 8) sind Zahlstatus und
  Zahlungsdatum unbekannt. Alle Daten sind synthetisch; das Jahr 2075 trennt sie von den Dokumenten anderer Szenarien.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Finanzbelege:
      | name        | art              | richtung | partner                 | datum      | betrag   | waehrung | faelligkeit | belegnummer | vertraulichkeit | bestaetigt | erfasser           | person             | status       | periode |
      | E1          | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2075-02-10 | 1000.00  | CHF      | 2075-03-12  | 2075-001    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | E2          | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2075-02-20 | 500.50   | CHF      | 2075-03-22  | 2075-002    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | E3          | AUSGANGSRECHNUNG | EINNAHME | Brunnmatt GmbH          | 2075-05-05 | 250.00   | CHF      | 2075-06-04  | 2075-003    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | E4          | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2076-01-15 | 300.00   | CHF      | 2076-02-14  | 2076-001    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | E_INTERN    | AUSGANGSRECHNUNG | EINNAHME | Brunnmatt GmbH          | 2075-03-01 | 400.00   | CHF      | 2075-03-31  | 2075-004    | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | E_PERSONAL  | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2075-06-10 | 150.00   | CHF      | 2075-06-20  | 2075-005    | PERSONAL        | ja         | inhaber-urinet     | mitarbeiter-urinet | ARCHIVIERT   |         |
      | E_FREMD     | AUSGANGSRECHNUNG | EINNAHME | Brunnmatt GmbH          | 2075-06-11 | 90.00    | CHF      | 2075-06-21  | 2075-006    | PERSONAL        | ja         | inhaber-urinet     | treuhaender-urinet | ARCHIVIERT   |         |
      | E_UNBEST    | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2075-04-04 | 700.00   | CHF      | 2075-04-20  | 2075-007    | NORMAL          | nein       | mitarbeiter-urinet |                    | ARCHIVIERT   |         |
      | E_EUR       | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2075-04-10 | 90.00    | EUR      | 2075-05-10  | 2075-008    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | E_ENTWURF   | AUSGANGSRECHNUNG | EINNAHME | Quellwald AG            | 2075-04-21 | 800.00   | CHF      | 2075-05-21  | 2075-009    | NORMAL          | ja         | inhaber-urinet     |                    | EINGANGSKORB |         |
      | E_OFFERTE   | OFFERTE          | EINNAHME | Quellwald AG            | 2075-04-20 | 9000.00  | CHF      | 2075-05-20  | O-2075-001  | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | A1          | LIEFERANTENBELEG | AUSGABE  | Seilerei Brunnmatt GmbH | 2075-02-12 | 200.00   | CHF      | 2075-03-14  | L-1         | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | A2          | QUITTUNG         | AUSGABE  |                         | 2075-02-13 | 30.00    | CHF      |             |             | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | A3          | BEHOERDE         | AUSGABE  | Gemeinde Altdorf        | 2075-06-01 | 120.00   | CHF      | 2075-06-30  | B-1         | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
      | A_LOHN      | LOHNABRECHNUNG   | AUSGABE  |                         | 2075-03-31 | 5000.00  | CHF      |             |             | PERSONAL        | ja         | inhaber-urinet     | inhaber-urinet     | ARCHIVIERT   | 2075-03 |
      | A_AUSWEIS   | LOHNABRECHNUNG   | AUSGABE  |                         | 2075-12-31 | 60000.00 | CHF      |             |             | PERSONAL        | ja         | inhaber-urinet     | inhaber-urinet     | ARCHIVIERT   | 2075    |
      | A_VERS      | VERSICHERUNG     | AUSGABE  | Sachversicherung AG     | 2075-04-01 | 800.00   | CHF      | 2075-04-30  | V-1         | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   |         |
  @req-3.3
  Szenario: Einnahmen je Jahr, ohne Offerte, Entwurf, unbestätigte Belege und Fremdwährung
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2076-12-31» nach «JAHR» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe | betrag        | anzahl |
      | 2075   | CHF 2'390.50  | 6      |
      | 2076   | CHF 300.00    | 1      |
    Und ist die Summe der Kennzahl «CHF 2'690.50» bei 7 Belegen und der Datenstand lautet «nach Rechnungsdatum, 7 Rechnungen»
    Und die Kennzahl nennt 1 ausgeschlossene Fremdwährungsbelege und die Belege «E1, E2, E3, E4, E_INTERN, E_PERSONAL, E_FREMD»

  @req-3.3
  Szenario: Einnahmen je Monat
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2075-12-31» nach «MONAT» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe  | betrag        | anzahl |
      | 2075-02 | CHF 1'500.50  | 2      |
      | 2075-03 | CHF 400.00    | 1      |
      | 2075-05 | CHF 250.00    | 1      |
      | 2075-06 | CHF 240.00    | 2      |
    Und ist die Summe der Kennzahl «CHF 2'390.50» bei 6 Belegen und der Datenstand lautet «nach Rechnungsdatum, 6 Rechnungen»

  @req-3.3
  Szenario: Einnahmen je Partner und je Art
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2075-12-31» nach «PARTNER» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe         | betrag        | anzahl |
      | Quellwald AG   | CHF 1'650.50  | 3      |
      | Brunnmatt GmbH | CHF 740.00    | 3      |
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2075-12-31» nach «ART» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe           | betrag        | anzahl |
      | AUSGANGSRECHNUNG | CHF 2'390.50  | 6      |

  @req-3.3
  Szenario: Ausgaben nach den Arten des Mandanten: Lohnabrechnung nur mit Monat, ohne Lohnausweis und Versicherung
    Wenn «inhaber-urinet» im Chat die Kennzahl «AUSGABEN» von «2075-01-01» bis «2075-12-31» nach «ART» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe           | betrag        | anzahl |
      | LOHNABRECHNUNG   | CHF 5'000.00  | 1      |
      | LIEFERANTENBELEG | CHF 200.00    | 1      |
      | BEHOERDE         | CHF 120.00    | 1      |
      | QUITTUNG         | CHF 30.00     | 1      |
    Und ist die Summe der Kennzahl «CHF 5'350.00» bei 4 Belegen und der Datenstand lautet «nach Belegdatum, 4 Belege»
    Und die Kennzahl nennt 0 ausgeschlossene Fremdwährungsbelege und die Belege «A1, A2, A3, A_LOHN»
    Wenn «inhaber-urinet» im Chat die Kennzahl «AUSGABEN» von «2075-01-01» bis «2075-12-31» nach «JAHR» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe | betrag        | anzahl |
      | 2075   | CHF 5'350.00  | 4      |

  @req-3.2
  Szenario: Die Summe stammt aus der vordefinierten Abfrage und stimmt mit der Rechnung direkt in SQL überein
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2076-12-31» nach «KEINE» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe | betrag        | anzahl |
      | Total  | CHF 2'690.50  | 7      |
    Und entspricht die Summe der Kennzahl der direkt in SQL gerechneten Summe der Arten «AUSGANGSRECHNUNG» im Mandanten «urinet»

  @req-3.2 @req-5.1
  Szenario: Finanzwerkzeuge richten sich nach der Mandantenkonfiguration
    Dann antwortet kennzahl und offene_posten für «inhaber-vhf-gsk» mit «{"fehler":"Werkzeug nicht verfügbar"}»
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2075-12-31» nach «KEINE» auf Basis «BELEGDATUM» abfragt
    Dann ist die Summe der Kennzahl «CHF 2'390.50» bei 6 Belegen und der Datenstand lautet «nach Rechnungsdatum, 6 Rechnungen»

  Szenario: Ein Fremdwährungsbeleg wird gezählt, aber nicht summiert
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-04-01» bis «2075-04-30» nach «KEINE» auf Basis «BELEGDATUM» abfragt
    Dann ist die Summe der Kennzahl «CHF 0.00» bei 0 Belegen und der Datenstand lautet «nach Rechnungsdatum, 0 Rechnungen»
    Und die Kennzahl nennt 1 ausgeschlossene Fremdwährungsbelege und die Belege «»

  @req-3.3
  Szenario: Mit dem Zahlungsdatum als Basis gibt es ohne AbaNinja keine Zahl
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2075-12-31» nach «MONAT» auf Basis «ZAHLUNGSDATUM» abfragt
    Dann hat die Antwort keine Summe, keine Zeilen und keine Belege
    Und hat die Antwort den Hinweis «Zahlungsdatum unbekannt (keine AbaNinja-Verbindung)»

  @req-5.2
  Szenario: Der Mitarbeiter erhält keine Summe mit INTERN- oder fremden PERSONAL-Belegen und den Sicht-Hinweis
    Wenn «mitarbeiter-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2076-12-31» nach «KEINE» auf Basis «BELEGDATUM» abfragt
    Dann ist die Summe der Kennzahl «CHF 2'200.50» bei 5 Belegen und der Datenstand lautet «nach Rechnungsdatum, 5 Rechnungen»
    Und die Kennzahl nennt 1 ausgeschlossene Fremdwährungsbelege und die Belege «E1, E2, E3, E4, E_PERSONAL»
    Und hat die Antwort den Hinweis «nur Belege, die du sehen darfst»
    Wenn «mitarbeiter-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2075-12-31» nach «PARTNER» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe         | betrag        | anzahl |
      | Quellwald AG   | CHF 1'650.50  | 3      |
      | Brunnmatt GmbH | CHF 250.00    | 1      |
    Wenn «mitarbeiter-urinet» im Chat die Kennzahl «AUSGABEN» von «2075-01-01» bis «2075-12-31» nach «ART» auf Basis «BELEGDATUM» abfragt
    Dann lauten die Zeilen der Kennzahl der Reihe nach:
      | gruppe           | betrag        | anzahl |
      | LIEFERANTENBELEG | CHF 200.00    | 1      |
      | QUITTUNG         | CHF 30.00     | 1      |

  @req-5.2
  Szenario: Der Treuhänder sieht die bestätigten Belege, unbestätigte zählen nie
    Wenn «treuhaender-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2076-12-31» nach «KEINE» auf Basis «BELEGDATUM» abfragt
    Dann ist die Summe der Kennzahl «CHF 2'690.50» bei 7 Belegen und der Datenstand lautet «nach Rechnungsdatum, 7 Rechnungen»
    Und hat die Antwort den Hinweis «nur Belege, die du sehen darfst»

  @req-5.2
  Szenario: Der Inhaber sieht alles und bekommt keinen Sicht-Hinweis
    Wenn «inhaber-urinet» im Chat die Kennzahl «EINNAHMEN» von «2075-01-01» bis «2076-12-31» nach «KEINE» auf Basis «BELEGDATUM» abfragt
    Dann hat die Antwort den Hinweis «nur Belege, die du sehen darfst» nicht

  @req-3.4
  Szenario: Offene Posten der Einnahmen mit Fälligkeit und unbekanntem Zahlstatus
    Wenn «inhaber-urinet» im Chat die offenen Posten zum Stichtag «2075-06-01» für «EINNAHME» abfragt
    Dann lauten die offenen Posten der Reihe nach:
      | dokument   | belegnummer | partner        | betrag       | faelligkeit | ueberfaellig | zahlstatus |
      | E1         | 2075-001    | Quellwald AG   | CHF 1'000.00 | 12.03.2075  | ja           | UNBEKANNT  |
      | E2         | 2075-002    | Quellwald AG   | CHF 500.50   | 22.03.2075  | ja           | UNBEKANNT  |
      | E_INTERN   | 2075-004    | Brunnmatt GmbH | CHF 400.00   | 31.03.2075  | ja           | UNBEKANNT  |
      | E_EUR      | 2075-008    | Quellwald AG   | EUR 90.00    | 10.05.2075  | ja           | UNBEKANNT  |
      | E3         | 2075-003    | Brunnmatt GmbH | CHF 250.00   | 04.06.2075  | nein         | UNBEKANNT  |
      | E_PERSONAL | 2075-005    | Quellwald AG   | CHF 150.00   | 20.06.2075  | nein         | UNBEKANNT  |
      | E_FREMD    | 2075-006    | Brunnmatt GmbH | CHF 90.00    | 21.06.2075  | nein         | UNBEKANNT  |
    Und die offenen Posten haben die Gesamtzahl 7 und das Fenster von «03.03.2075» bis «01.07.2075»
    Und hat die Antwort den Hinweis «Zahlstatus nicht bekannt, AbaNinja nicht verbunden»
    Und hat die Antwort den Hinweis «nur Belege, die du sehen darfst» nicht

  @req-3.4 @req-5.2
  Szenario: Offene Posten für den Mitarbeiter ohne INTERN, fremde PERSONAL und unbestätigte Belege
    Wenn «mitarbeiter-urinet» im Chat die offenen Posten zum Stichtag «2075-06-01» für «EINNAHME» abfragt
    Dann lauten die offenen Posten der Reihe nach:
      | dokument   | belegnummer | partner        | betrag       | faelligkeit | ueberfaellig | zahlstatus |
      | E1         | 2075-001    | Quellwald AG   | CHF 1'000.00 | 12.03.2075  | ja           | UNBEKANNT  |
      | E2         | 2075-002    | Quellwald AG   | CHF 500.50   | 22.03.2075  | ja           | UNBEKANNT  |
      | E_EUR      | 2075-008    | Quellwald AG   | EUR 90.00    | 10.05.2075  | ja           | UNBEKANNT  |
      | E3         | 2075-003    | Brunnmatt GmbH | CHF 250.00   | 04.06.2075  | nein         | UNBEKANNT  |
      | E_PERSONAL | 2075-005    | Quellwald AG   | CHF 150.00   | 20.06.2075  | nein         | UNBEKANNT  |
    Und die offenen Posten haben die Gesamtzahl 5 und das Fenster von «03.03.2075» bis «01.07.2075»
    Und hat die Antwort den Hinweis «nur Belege, die du sehen darfst»

  @req-3.4
  Szenario: Offene Posten der Ausgaben nach den Arten des Mandanten
    Wenn «inhaber-urinet» im Chat die offenen Posten zum Stichtag «2075-06-01» für «AUSGABE» abfragt
    Dann lauten die offenen Posten der Reihe nach:
      | dokument | belegnummer | partner                 | betrag     | faelligkeit | ueberfaellig | zahlstatus |
      | A1       | L-1         | Seilerei Brunnmatt GmbH | CHF 200.00 | 14.03.2075  | ja           | UNBEKANNT  |
      | A3       | B-1         | Gemeinde Altdorf        | CHF 120.00 | 30.06.2075  | nein         | UNBEKANNT  |

  @req-3.4
  Szenario: Offene Posten liefern höchstens 50 Rechnungen und nennen die Gesamtzahl
    Angenommen im Mandanten «urinet» liegen 52 weitere Rechnungen mit Fälligkeit am «2070-08-31»
    Wenn «inhaber-urinet» im Chat die offenen Posten zum Stichtag «2070-09-01» für «EINNAHME» abfragt
    Dann enthält die Antwort 50 offene Posten bei der Gesamtzahl 52
