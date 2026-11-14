# language: de
@spec-02
Funktionalität: Werkzeuge des Chat-Agenten: Suche, Dokument, Fristen (Aufgabe 7.1a)
  Die Werkzeuge «dokumente_suchen», «dokument_zeigen» und «fristen» sind reine Java-Methoden; die Szenarien rufen sie
  ohne Sprachmodell über CDI auf, jeden Aufruf in einem eigenen Thread ohne Anmeldung (wie die Callbacks beim
  Streaming). Der Kontext (Mandant, Benutzer, Rolle, Sicht, IP) ist wie zu Beginn einer Chat-Anfrage im Register
  festgehalten; kein Werkzeug hat einen Parameter dafür. Beide Mandanten haben ähnliche Daten. Alle Daten sind
  synthetisch, die Jahre 2070/2071 trennen sie von den Dokumenten anderer Szenarien.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» gibt es die Kontakte «Bergbahn Quellwald AG» und «Seilerei Brunnmatt GmbH»
    Und im Mandanten «urinet» liegen diese Chat-Dokumente:
      | name          | titel                          | art              | partner                 | datum      | betrag  | belegnummer | vertraulichkeit | bestaetigt | erfasser           | person             | status      | text                                         | dateiname               |
      | U_NORMAL      | Revision Gondel Nord           | AUSGANGSRECHNUNG | Bergbahn Quellwald AG   | 2071-03-10 | 1234.50 | 2071-001    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT  | Gondelrevision der Nordbahn                  | Gondelrevision_Scan.pdf |
      | U_ALT         | Revision Gondel Süd            | AUSGANGSRECHNUNG | Bergbahn Quellwald AG   | 2070-11-02 | 800.00  | 2070-014    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT  | Gondelrevision der Südbahn                   |                         |
      | U_VERTRAG     | Wartungsvertrag Seilbahn       | VERTRAG          | Seilerei Brunnmatt GmbH | 2070-01-15 |         |             | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT  | Gondelrevision jährlich                      |                         |
      | U_OFFERTE     | Offerte Steinschlagnetz        | OFFERTE          | Seilerei Brunnmatt GmbH | 2071-02-01 | 5600.00 | O-2071-003  | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT  | Gondelrevision und Steinschlagnetz           |                         |
      | U_INTERN      | Beratung Gondelrevision        | AUSGANGSRECHNUNG | Bergbahn Quellwald AG   | 2071-04-01 | 900.00  | 2071-002    | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT  | Beratung zum Jahresabschluss                 |                         |
      | U_PERSONAL_MA | Lohn Gondelrevision März       | LOHNABRECHNUNG   |                         | 2071-03-31 | 5000.00 |             | PERSONAL        | ja         | inhaber-urinet     | mitarbeiter-urinet | ARCHIVIERT  | Lohn mit Zulage                              |                         |
      | U_PERSONAL_IN | Arztrechnung Gondelrevision    | KORRESPONDENZ    |                         | 2071-02-15 | 180.00  |             | PERSONAL        | ja         | inhaber-urinet     | inhaber-urinet     | ARCHIVIERT  | Kontrolle                                    |                         |
      | U_OFFEN_EIGEN | Quittung Gondelrevision Benzin |                  |                         |            |         |             | NORMAL          | nein       | mitarbeiter-urinet |                    | EINGANGSKORB | Tankstelle                                  |                         |
      | U_OFFEN_FREMD | Quittung Gondelrevision Kiosk  |                  |                         |            |         |             | NORMAL          | nein       | inhaber-urinet     |                    | EINGANGSKORB | Kiosk                                       |                         |
      | U_VERWORFEN   | Revision Gondel doppelt        | AUSGANGSRECHNUNG | Bergbahn Quellwald AG   | 2071-03-11 | 1234.50 |             | NORMAL          | ja         | inhaber-urinet     |                    | VERWORFEN   | Gondelrevision doppelt erfasst               |                         |
      | U_DATEINAME   | Protokoll Bergstation Nord     | KORRESPONDENZ    |                         | 2071-01-20 |         |             | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT  | Planung der Bergstation                      | Zahnradbahn_Sitzung.pdf |
    Und die Chat-Dokumente haben diese Fristen:
      | name          | faelligkeit | naechste_frist | gueltig_bis | kuendigungsfrist       | angebot_gueltig_bis |
      | U_NORMAL      | 2071-04-09  |                |             |                        |                     |
      | U_VERTRAG     |             | 2071-06-30     | 2071-12-31  | 3 Monate auf Ende Jahr |                     |
      | U_OFFERTE     |             |                |             |                        | 2071-03-31          |
      | U_INTERN      | 2071-05-01  |                |             |                        |                     |
      | U_PERSONAL_MA | 2071-04-25  |                |             |                        |                     |
      | U_PERSONAL_IN | 2071-02-28  |                |             |                        |                     |
      | U_OFFEN_EIGEN | 2071-03-15  |                |             |                        |                     |
      | U_VERWORFEN   | 2071-03-20  |                |             |                        |                     |
    Und im Mandanten «vhf-gsk» liegen diese Chat-Dokumente:
      | name      | titel                         | art | partner | datum      | betrag | belegnummer | vertraulichkeit | bestaetigt | erfasser                 | person | status     | text                           | dateiname |
      | V_NORMAL  | Revision Gondel Vereinsbus    |     |         | 2071-03-10 | 450.00 | 2071-001    | NORMAL          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT | Gondelrevision des Vereinsbus  |           |
      | V_VERTRAG | Mietvertrag Gondelrevision    |     |         | 2070-02-01 |        |             | NORMAL          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT | Gondelrevision der Halle       |           |
    Und die Chat-Dokumente haben diese Fristen:
      | name      | faelligkeit | naechste_frist | gueltig_bis | kuendigungsfrist | angebot_gueltig_bis |
      | V_NORMAL  | 2071-04-09  |                |             |                  |                     |
      | V_VERTRAG |             | 2071-06-30     |             | 1 Monat          |                     |

  @req-5.1
  Szenario: Die Werkzeuge liefern nur Daten des Mandanten aus dem Kontext
    Dann liefern die Werkzeuge mit der Suche «Gondelrevision» und den Fristen von «2071-01-01» bis «2071-12-31» diese Ergebnisse:
      | benutzer                 | treffer                    | sichtbar  | unsichtbar         | fristen | belege | hinweis |
      | inhaber-urinet           | U_NORMAL, U_ALT, U_VERTRAG, U_OFFERTE, U_INTERN, U_PERSONAL_MA, U_PERSONAL_IN, U_OFFEN_EIGEN, U_OFFEN_FREMD | U_NORMAL | V_NORMAL, V_VERTRAG | 7 | 6 | nein |
      | inhaber-vhf-gsk          | V_NORMAL, V_VERTRAG        | V_NORMAL  | U_NORMAL, U_VERTRAG | 2      | 2      | nein    |
      | geschaeftsstelle-vhf-gsk | V_NORMAL, V_VERTRAG        | V_VERTRAG | U_NORMAL            | 2      | 2      | nein    |

  @req-5.1
  Szenario: Ohne Kontext antwortet jedes Werkzeug mit einem Fehler
    Dann antwortet jedes Werkzeug ohne Kontext mit «{"fehler":"kein Kontext"}»
    Und nach dem Ende der Chat-Anfrage von «inhaber-urinet» antwortet jedes Werkzeug mit «{"fehler":"kein Kontext"}»

  @req-5.2
  Szenario: Die Werkzeuge zeigen jeder Rolle nur, was sie sehen darf, auch in Gesamtzahl und Anzahl der Fristen
    Dann liefern die Werkzeuge mit der Suche «Gondelrevision» und den Fristen von «2071-01-01» bis «2071-12-31» diese Ergebnisse:
      | benutzer           | treffer                                                                                                      | sichtbar                                  | unsichtbar                                    | fristen | belege | hinweis |
      | mitarbeiter-urinet | U_NORMAL, U_ALT, U_VERTRAG, U_OFFERTE, U_PERSONAL_MA, U_OFFEN_EIGEN                                          | U_NORMAL, U_PERSONAL_MA, U_OFFEN_EIGEN    | U_INTERN, U_PERSONAL_IN, U_OFFEN_FREMD        | 5       | 4      | ja      |
      | treuhaender-urinet | U_NORMAL, U_ALT, U_VERTRAG, U_OFFERTE, U_INTERN, U_PERSONAL_MA, U_PERSONAL_IN                                | U_INTERN, U_PERSONAL_IN                   | U_OFFEN_EIGEN, U_OFFEN_FREMD                  | 7       | 6      | ja      |
      | inhaber-urinet     | U_NORMAL, U_ALT, U_VERTRAG, U_OFFERTE, U_INTERN, U_PERSONAL_MA, U_PERSONAL_IN, U_OFFEN_EIGEN, U_OFFEN_FREMD  | U_INTERN, U_PERSONAL_IN, U_OFFEN_FREMD    |                                               | 7       | 6      | nein    |

  @req-5.2
  Szenario: Unbestätigte Dokumente erscheinen nur mit Recht A7 oder für den Erfasser und tragen «bestaetigt: false»
    Dann sind in den Treffern von «inhaber-urinet» zu «Gondelrevision» genau «U_OFFEN_EIGEN, U_OFFEN_FREMD» unbestätigt
    Und sind in den Treffern von «mitarbeiter-urinet» zu «Gondelrevision» genau «U_OFFEN_EIGEN» unbestätigt
    Und sind in den Treffern von «treuhaender-urinet» zu «Gondelrevision» genau «» unbestätigt
    Und zeigt dokument_zeigen «U_OFFEN_EIGEN» für «mitarbeiter-urinet» mit «bestaetigt» «false»
    Und zeigt dokument_zeigen «U_NORMAL» für «mitarbeiter-urinet» mit «bestaetigt» «true»

  @req-3.5
  Szenario: fristen liefert Vertragsfristen, Fälligkeiten und die Gültigkeit von Offerten im Zeitraum, nach Datum sortiert
    Wenn «inhaber-urinet» im Chat die Fristen von «2071-03-01» bis «2071-06-30» abfragt
    Dann lauten die Fristen der Reihe nach:
      | datum      | frist              | dokumentart      | dokument      | beschreibung                                    |
      | 31.03.2071 | Offerte gültig bis | OFFERTE          | U_OFFERTE     | Seilerei Brunnmatt GmbH, CHF 5'600.00           |
      | 09.04.2071 | Fälligkeit         | AUSGANGSRECHNUNG | U_NORMAL      | Bergbahn Quellwald AG, CHF 1'234.50             |
      | 25.04.2071 | Fälligkeit         | LOHNABRECHNUNG   | U_PERSONAL_MA | CHF 5'000.00                                    |
      | 01.05.2071 | Fälligkeit         | AUSGANGSRECHNUNG | U_INTERN      | Bergbahn Quellwald AG, CHF 900.00               |
      | 30.06.2071 | Kündigungsfrist    | VERTRAG          | U_VERTRAG     | Seilerei Brunnmatt GmbH, 3 Monate auf Ende Jahr |
    Und die Fristen haben die Gesamtzahl 5, den Datenstand mit 5 Belegen und keinen Hinweis
    Wenn «inhaber-urinet» im Chat die Fristen von «2071-07-01» bis «2071-12-31» abfragt
    Dann lauten die Fristen der Reihe nach:
      | datum      | frist      | dokumentart | dokument  | beschreibung            |
      | 31.12.2071 | gültig bis | VERTRAG     | U_VERTRAG | Seilerei Brunnmatt GmbH |

  @req-3.5
  Szenario: fristen liefert höchstens 50 Fristen und nennt die Gesamtzahl
    Angenommen im Mandanten «urinet» liegen 52 weitere Rechnungen mit Fälligkeit am «2070-08-31»
    Wenn «inhaber-urinet» im Chat die Fristen von «2070-08-01» bis «2070-09-30» abfragt
    Dann enthält die Antwort 50 Fristen bei der Gesamtzahl 52 und den Datenstand mit 52 Belegen

  Szenario: Ein Begriff nur im Dateinamen trifft nicht, und keine Antwort enthält den Dateinamen
    Dann findet «inhaber-urinet» mit dokumente_suchen zu «Zahnradbahn» keine Treffer
    Und findet «inhaber-urinet» mit dokumente_suchen zu «Bergstation» die Treffer «U_DATEINAME»
    Und keine Antwort von dokumente_suchen zu «Gondelrevision» oder von dokument_zeigen für «U_NORMAL» und «U_DATEINAME» enthält den Dateinamen

  Szenario: dokumente_suchen löst den Partner über den Namen auf und vermutet nie
    Dann findet «inhaber-urinet» mit dokumente_suchen diese Treffer:
      | frage          | partner                  | art      | neueste | treffer                  | hinweis                |
      | Gondelrevision | Bergbahn Quelwald        |          | true    | U_INTERN, U_NORMAL, U_ALT |                        |
      | Gondelrevision | seilerei brunnmatt       | offerte  |         | U_OFFERTE                |                        |
      | Gondelrevision | Zahnarztpraxis Nordlicht |          |         |                          | Partner nicht gefunden |
    Und antwortet dokumente_suchen für «inhaber-urinet» mit der Art «PIZZA» mit dem Fehler «unbekannte Art» und den aktiven Codes, darunter «AUSGANGSRECHNUNG»

  Szenario: Fremde, unsichtbare, unbekannte und ungültige IDs ergeben gleich «nicht gefunden» und werden verweigert protokolliert
    Angenommen der Stand des Chat-Protokolls von «urinet» ist gemerkt
    Dann antwortet dokument_zeigen für «mitarbeiter-urinet» bei diesen IDs gleich mit «{"fehler":"nicht gefunden"}»:
      | id           | grund                    |
      | V_NORMAL     | anderer Mandant          |
      | U_INTERN     | INTERN ohne A3           |
      | U_OFFEN_FREMD | fremd erfasst, unbestätigt |
      | {unbekannt}  | keine solche ID          |
      | {ungültig}   | keine UUID               |
    Und das Chat-Protokoll von «urinet» hat diese neuen Einträge:
      | benutzer           | rolle       | aktion     | dokument      | grund | fehlercode     | kanal |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT | V_NORMAL      | A2    | NICHT_GEFUNDEN | CHAT  |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT | U_INTERN      | A2    | NICHT_GEFUNDEN | CHAT  |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT | U_OFFEN_FREMD | A2    | NICHT_GEFUNDEN | CHAT  |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT | {unbekannt}   | A2    | NICHT_GEFUNDEN | CHAT  |
      | mitarbeiter-urinet | MITARBEITER | VERWEIGERT |               | A2    | NICHT_GEFUNDEN | CHAT  |

  Szenario: dokument_zeigen liefert Metadaten, Inhalt höchstens 4'000 Zeichen und schreibt ANZEIGEN mit Kanal Chat
    Angenommen im Mandanten «urinet» liegt das Chat-Dokument «U_LANG» mit 5000 Zeichen Text
    Und der Stand des Chat-Protokolls von «urinet» ist gemerkt
    Und «mitarbeiter-urinet» fragt im Chat über die IP «203.0.113.9»
    Wenn «mitarbeiter-urinet» im Chat das Dokument «U_NORMAL» zeigen lässt
    Dann zeigt die Antwort diese Werte:
      | feld        | wert                  |
      | titel       | Revision Gondel Nord  |
      | art         | AUSGANGSRECHNUNG      |
      | datum       | 10.03.2071            |
      | partner     | Bergbahn Quellwald AG |
      | belegnummer | 2071-001              |
      | betrag      | CHF 1'234.50          |
      | faelligkeit | 09.04.2071            |
      | bestaetigt  | true                  |
      | inhalt      | Gondelrevision der Nordbahn |
      | link        | /dokument/{U_NORMAL}  |
    Wenn «mitarbeiter-urinet» im Chat das Dokument «U_VERTRAG» zeigen lässt
    Dann zeigt die Antwort diese Werte:
      | feld                    | wert                   |
      | vertrag.gueltigBis      | 31.12.2071             |
      | vertrag.naechsteFrist   | 30.06.2071             |
      | vertrag.kuendigungsfrist | 3 Monate auf Ende Jahr |
    Wenn «mitarbeiter-urinet» im Chat das Dokument «U_LANG» zeigen lässt
    Dann ist der Inhalt der Antwort 4000 Zeichen lang
    Und das Chat-Protokoll von «urinet» hat diese neuen Einträge:
      | benutzer           | rolle       | aktion   | dokument  | ip          | kanal |
      | mitarbeiter-urinet | MITARBEITER | ANZEIGEN | U_NORMAL  | 203.0.113.9 | CHAT  |
      | mitarbeiter-urinet | MITARBEITER | ANZEIGEN | U_VERTRAG | 203.0.113.9 | CHAT  |
      | mitarbeiter-urinet | MITARBEITER | ANZEIGEN | U_LANG    | 203.0.113.9 | CHAT  |

  Szenario: Scheitert der Protokolleintrag, liefert dokument_zeigen keinen Inhalt
    Angenommen im Schema von «urinet» scheitert jeder neue Eintrag ins Zugriffsprotokoll beim Festschreiben
    Wenn «inhaber-urinet» im Chat das Dokument «U_NORMAL» zeigen lässt
    Dann ist die Antwort ein Fehler ohne Inhalt
