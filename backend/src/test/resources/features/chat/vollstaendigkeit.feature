# language: de
@spec-02
Funktionalität: Werkzeug vollstaendigkeit: Lücken in Nummernfolgen und Monaten (Aufgabe 7.1c)
  Das Werkzeug «vollstaendigkeit» ist eine reine Java-Methode; die Szenarien rufen sie ohne Sprachmodell über CDI auf,
  jeden Aufruf in einem eigenen Thread ohne Anmeldung. Die aktiven Regeln des Mandanten bestimmen Nummernfolge und
  erwartete Monate je Dokumentart. Alle Daten sind synthetisch und liegen im Jahr 2003, das übrige Szenarien nicht
  benutzen; die Monate sind damit sicher abgeschlossen.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» sind die Seed-Regeln für den Lückenbericht eingerichtet
    Und im Mandanten «urinet» gibt es für die Lückenprüfung die Kontakte «Kantonalbank Eichenau» und «Raiffeisen Birkenfeld»

  @req-3.6
  Szenario: Fehlende Rechnungsnummern, Lohnabrechnungen und Kontoauszüge eines Geschäftsjahres, mit dem Hinweis «nur mit AbaNinja»
    Angenommen im Mandanten «urinet» liegen bestätigte Ausgangsrechnungen mit den Belegnummern «2003-001, 2003-002, 2003-004, 2003-007»
    Und im Mandanten «urinet» liegen diese weiteren Dokumente für die Lückenprüfung:
      | art              | vertraulichkeit | datum      | belegnummer | periode | person         | status     | bestaetigt |
      | AUSGANGSRECHNUNG | NORMAL          | 2003-02-01 | R0009       |         |                | ARCHIVIERT | ja         |
      | AUSGANGSRECHNUNG | NORMAL          | 2003-02-02 | 2002-050    |         |                | ARCHIVIERT | ja         |
      | LOHNABRECHNUNG   | PERSONAL        | 2003-01-20 |             | 2003    | Beat Rinderli  | ARCHIVIERT | ja         |
    Und im Mandanten «urinet» liegen bestätigte Lohnabrechnungen der Person «Anna Weidmatt» für die Monate «2003-01, 2003-02, 2003-04, 2003-05, 2003-06, 2003-07, 2003-08, 2003-09, 2003-10, 2003-11, 2003-12»
    Und im Mandanten «urinet» liegen bestätigte Lohnabrechnungen der Person «Clara Moosbrugger» für die Monate «2003-05, 2003-06, 2003-07, 2003-08, 2003-09, 2003-10, 2003-11, 2003-12»
    Und im Mandanten «urinet» liegen bestätigte Kontoauszüge des Partners «Kantonalbank Eichenau» für die Monate «2003-01, 2003-02, 2003-04, 2003-05, 2003-06, 2003-07, 2003-09, 2003-10, 2003-11, 2003-12»
    Und im Mandanten «urinet» liegen bestätigte Kontoauszüge des Partners «Raiffeisen Birkenfeld» für die Monate «2003-01, 2003-02, 2003-03, 2003-04, 2003-05, 2003-06, 2003-07, 2003-08, 2003-09, 2003-10, 2003-11, 2003-12»
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann fehlen bei den Ausgangsrechnungen die Nummern «2003-003, 2003-005, 2003-006»
    Und fehlen bei der Art «LOHNABRECHNUNG» die Monate «2003-03»
    Und beginnt die Reihe der Art «LOHNABRECHNUNG» mit dem Monat «2003-01»
    Und hat die Art «BANK» keine fehlenden Monate
    Und enthält der Bericht keine Personennamen
    Und nennt die Antwort die ausdrückliche Lücke «Lieferantenbelege ohne Archivdokument: nur mit AbaNinja»
    Und liefern «inhaber-urinet», «treuhaender-urinet» und «geschaeftsstelle-urinet» dieselben Lücken

  @req-3.6
  Szenario: Eine lückenlose Folge nennt keine fehlende Nummer, und ein Geschäftsjahr ausserhalb der Grenzen ist ein Fehler
    Angenommen im Mandanten «urinet» liegen bestätigte Ausgangsrechnungen mit den Belegnummern «2003-001, 2003-002, 2003-003»
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann fehlen bei den Ausgangsrechnungen keine Nummern, die höchste ist «2003-003»
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «1999» abfragt
    Dann ist die Antwort der Fehler «ungültiger Parameter: geschaeftsjahr»
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «3000» abfragt
    Dann ist die Antwort der Fehler «ungültiger Parameter: geschaeftsjahr»

  @req-4.2
  Szenario: Rollen ohne A3 und A4 uneingeschränkt erhalten das Werkzeug nicht angeboten, ein direkter Aufruf ergibt einen Fehler
    Dann wird «inhaber-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «treuhaender-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «geschaeftsstelle-urinet» das Werkzeug vollstaendigkeit angeboten
    Und wird «inhaber-vhf-gsk» das Werkzeug vollstaendigkeit angeboten
    Und wird «mitarbeiter-urinet» das Werkzeug vollstaendigkeit nicht angeboten
    Und wird «mitarbeiter-vhf-gsk» das Werkzeug vollstaendigkeit nicht angeboten
    Wenn «mitarbeiter-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann ist die Antwort der Fehler «Werkzeug nicht verfügbar»

  @req-5.2
  Szenario: Lücken entstehen nur aus bestätigten Dokumenten des eigenen Mandanten
    Angenommen im Mandanten «urinet» liegen bestätigte Ausgangsrechnungen mit den Belegnummern «2003-001, 2003-002, 2003-004»
    Und im Mandanten «vhf-gsk» liegen bestätigte Ausgangsrechnungen mit den Belegnummern «2003-003»
    Und im Mandanten «urinet» liegen bestätigte Lohnabrechnungen der Person «Anna Weidmatt» für die Monate «2003-01, 2003-02, 2003-04, 2003-05, 2003-06, 2003-07, 2003-08, 2003-09, 2003-10, 2003-11, 2003-12»
    Und im Mandanten «urinet» liegen diese weiteren Dokumente für die Lückenprüfung:
      | art              | vertraulichkeit | datum      | belegnummer | periode | person        | status       | bestaetigt |
      | AUSGANGSRECHNUNG | NORMAL          | 2003-01-20 | 2003-003    |         |               | EINGANGSKORB | nein       |
      | LOHNABRECHNUNG   | PERSONAL        | 2003-03-31 |             | 2003-03 | Anna Weidmatt | EINGANGSKORB | nein       |
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann fehlen bei den Ausgangsrechnungen die Nummern «2003-003»
    Und fehlen bei der Art «LOHNABRECHNUNG» die Monate «2003-03»
    Und nennt der Datenstand die Zahl der bestätigten Belege der Arten «AUSGANGSRECHNUNG, LOHNABRECHNUNG, BANK» im Mandanten «urinet»
    Wenn «inhaber-vhf-gsk» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann hat die Antwort keine Kategorien und den Hinweis «Für diesen Mandanten sind keine Regeln für Lücken eingerichtet»

  @req-3.6
  Szenario: Bestätigte Monatsbelege derselben Art ergeben eine gemeinsame Reihe
    Angenommen im Mandanten «urinet» liegen bestätigte Kontoauszüge des Partners «Kantonalbank Eichenau» für die Monate «2003-01, 2003-02, 2003-05, 2003-06, 2003-07, 2003-08, 2003-09, 2003-10, 2003-11, 2003-12»
    Und im Mandanten «urinet» liegen bestätigte Kontoauszüge unter dem Namen «Kantonalbank Eichenau AG» des Partners «Kantonalbank Eichenau» für die Monate «2003-03, 2003-04»
    Und im Mandanten «urinet» liegen bestätigte Lohnabrechnungen der Person «Anna Weidmatt» für die Monate «2003-01, 2003-02, 2003-05, 2003-06, 2003-07, 2003-08, 2003-09, 2003-10, 2003-11, 2003-12»
    Und im Mandanten «urinet» liegen diese weiteren Dokumente für die Lückenprüfung:
      | art            | vertraulichkeit | datum      | belegnummer | periode | person          | status     | bestaetigt |
      | LOHNABRECHNUNG | PERSONAL        | 2003-03-31 |             | 2003-03 | anna weidmatt   | ARCHIVIERT | ja         |
      | LOHNABRECHNUNG | PERSONAL        | 2003-04-30 |             | 2003-04 | Anna Weidmatt   | ARCHIVIERT | ja         |
    Wenn «inhaber-urinet» im Chat die Vollständigkeit für das Geschäftsjahr «2003» abfragt
    Dann hat die Art «BANK» keine fehlenden Monate
    Und hat die Art «LOHNABRECHNUNG» keine fehlenden Monate
