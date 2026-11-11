# language: de
@spec-01
Funktionalität: Volltextsuche mit Filtern (Aufgabe 4.5)
  «GET /api/v1/dokumente» sucht über Titel, Partner, Tags und Volltext (deutsche Stammformen) und filtert nach Art,
  Partner, Zeitraum, Betrag, Richtung, Tags, Status, Geschäftsjahr und Belegnummer. Treffer, Gesamtzahl und Paging
  laufen in derselben Abfrage unter dem VertraulichkeitFilter (Req 2.3) und im Schema des Mandanten aus dem Token.
  Alle Daten sind synthetisch. Jedes Szenario versieht seine Dokumente mit einem eigenen Lauf-Tag und jede Suche
  filtert zusätzlich auf diesen Tag, damit Dokumente anderer Szenarien die Zähler nicht verfälschen. Ohne
  Suchbegriff sortiert die Suche nach Belegdatum (neueste zuerst).

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Suchdokumente:
      | name            | titel                | art              | partner           | tags        | datum      | betrag  | richtung | belegnummer | vertraulichkeit | bestaetigt | erfasser           | person             | status       | text                                                           |
      | U_RECHNUNG_A    | Rechnung Trikots     | AUSGANGSRECHNUNG | FC Muster         | sport       | 2026-03-10 | 450.00  | EINNAHME | 2026-001    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Lieferung von Trikots für die erste Mannschaft                 |
      | U_RECHNUNG_B    | Rechnung Webseite    | AUSGANGSRECHNUNG | SC Beispiel       | web,wartung | 2025-11-20 | 1800.00 | EINNAHME | 2025-014    | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Pflege der Webseite im vierten Quartal                         |
      | U_LIEFERANT     | Hosting Jahresgebühr | LIEFERANTENBELEG | Muster Hosting AG | web         | 2026-01-05 | 240.00  | AUSGABE  | F0003       | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Server für ein Jahr. Diese Rechnung ist innert 30 Tagen fällig |
      | U_OFFERTE       | Offerte Clubhaus     | OFFERTE          | FC Muster         | sport       | 2026-03-20 | 3200.00 | EINNAHME | O-2026-005  | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Beschallung des Clubhauses, die Trikots folgen separat         |
      | U_INTERN        | Rechnung Beratung    | AUSGANGSRECHNUNG | Muster Treuhand   |             | 2026-04-01 | 900.00  | EINNAHME | 2026-002    | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT   | Beratung zum Jahresabschluss                                   |
      | U_PERSONAL_JOEL | Lohn März            | LOHNABRECHNUNG   |                   |             | 2026-03-31 | 5000.00 | AUSGABE  |             | PERSONAL        | ja         | inhaber-urinet     | mitarbeiter-urinet | ARCHIVIERT   | Rechnung der Sozialabzüge                                      |
      | U_PERSONAL_FAB  | Rechnung Zahnarzt    | KORRESPONDENZ    |                   |             | 2026-02-15 | 180.00  | AUSGABE  |             | PERSONAL        | ja         | inhaber-urinet     | inhaber-urinet     | ARCHIVIERT   | Kontrolle                                                      |
      | U_OFFEN_EIGEN   | Rechnung Tankstelle  |                  |                   |             |            |         |          |             | NORMAL          | nein       | mitarbeiter-urinet |                    | EINGANGSKORB | Benzin                                                         |
      | U_OFFEN_FREMD   | Rechnung Bürobedarf  |                  |                   |             |            |         |          |             | NORMAL          | nein       | inhaber-urinet     |                    | EINGANGSKORB | Papier                                                         |
      | U_VERWORFEN     | Rechnung Trikots     | AUSGANGSRECHNUNG | FC Muster         | sport       | 2026-03-11 | 450.00  | EINNAHME |             | NORMAL          | ja         | inhaber-urinet     |                    | VERWORFEN    | Doppelt erfasst                                                |
    Und im Mandanten «vhf-gsk» liegen diese Suchdokumente:
      | name       | titel                   | art | partner | tags | datum      | betrag | richtung | belegnummer | vertraulichkeit | bestaetigt | erfasser                 | person | status     | text      |
      | V_RECHNUNG | Rechnung Bulletin Druck |     |         |      | 2026-02-02 |        |          | 2026-001    | NORMAL          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT | Druckerei |
      | V_INTERN   | Rechnung Vorstand       |     |         |      | 2026-02-03 |        |          |             | INTERN          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT | Sitzung   |

  @req-14.1 @req-14.5
  Szenario: Jede Rolle findet nur die Dokumente, die sie sehen darf, im eigenen Mandanten – auch in der Gesamtzahl
    Dann finden die Benutzer mit dem Suchbegriff «Rechnungen» diese Dokumente:
      | benutzer                 | treffer                                                                                                          | gesamt | grund                                                 |
      | inhaber-urinet           | U_RECHNUNG_A, U_RECHNUNG_B, U_LIEFERANT, U_INTERN, U_PERSONAL_JOEL, U_PERSONAL_FAB, U_OFFEN_EIGEN, U_OFFEN_FREMD | 8      | alles im Mandanten ausser Verworfenem; Stammform      |
      | mitarbeiter-urinet       | U_RECHNUNG_A, U_RECHNUNG_B, U_LIEFERANT, U_PERSONAL_JOEL, U_OFFEN_EIGEN                                          | 5      | NORMAL, eigene PERSONAL, selbst erfasste unbestätigte |
      | treuhaender-urinet       | U_RECHNUNG_A, U_RECHNUNG_B, U_LIEFERANT, U_INTERN, U_PERSONAL_JOEL, U_PERSONAL_FAB                               | 6      | alle bestätigten, keine unbestätigten                 |
      | inhaber-vhf-gsk          | V_RECHNUNG, V_INTERN                                                                                             | 2      | nur der eigene Mandant (1.2)                          |
      | geschaeftsstelle-vhf-gsk | V_RECHNUNG, V_INTERN                                                                                             | 2      | nur der eigene Mandant (1.2)                          |

  @req-14.5
  Szenario: Paging zählt nur sichtbare Treffer
    Wenn «mitarbeiter-urinet» sucht mit:
      | q       | Rechnung |
      | groesse | 2        |
      | seite   | 2        |
    Dann ist die Gesamtzahl 5
    Und die Seite enthält 1 Treffer
    Wenn «inhaber-urinet» sucht mit:
      | q       | Rechnung |
      | groesse | 3        |
      | seite   | 2        |
    Dann ist die Gesamtzahl 8
    Und die Seite enthält 2 Treffer
    Wenn «inhaber-urinet» sucht mit:
      | q       | Rechnung |
      | groesse | 3        |
      | seite   | 9        |
    Dann ist die Gesamtzahl 8
    Und die Seite enthält 0 Treffer

  @req-14.1
  Szenario: Die Suche findet über Titel, Partner, Tags und Volltext
    Dann findet «inhaber-urinet» mit diesen Suchbegriffen diese Dokumente:
      | suchbegriff          | treffer                                                                                          | grund               |
      | Clubhaus             | U_OFFERTE                                                                                        | Titel und Volltext  |
      | Muster               | U_RECHNUNG_A, U_LIEFERANT, U_OFFERTE, U_INTERN                                                   | Partner             |
      | sport                | U_RECHNUNG_A, U_OFFERTE                                                                          | Tag                 |
      | Sozialabzug          | U_PERSONAL_JOEL                                                                                  | Volltext, Stammform |
      | "erste Mannschaft"   | U_RECHNUNG_A                                                                                     | Wortfolge           |
      | Rechnung -Trikots    | U_RECHNUNG_B, U_LIEFERANT, U_INTERN, U_PERSONAL_JOEL, U_PERSONAL_FAB, U_OFFEN_EIGEN, U_OFFEN_FREMD | Ausschluss          |
      | Webseite or Clubhaus | U_RECHNUNG_B, U_OFFERTE                                                                          | oder                |
      | Quittung             |                                                                                                  | kein Treffer        |

  @req-14.3
  Szenario: Mit Suchbegriff sortiert die Suche nach Relevanz, auf Wunsch nach Datum
    Wenn «inhaber-urinet» sucht mit:
      | q | Trikots |
    Dann lauten die Treffer der Reihe nach «U_RECHNUNG_A, U_OFFERTE»
    Wenn «inhaber-urinet» sucht mit:
      | q          | Trikots |
      | sortierung | DATUM   |
    Dann lauten die Treffer der Reihe nach «U_OFFERTE, U_RECHNUNG_A»

  @req-14.2
  Szenario: Jeder Filter wirkt einzeln, ohne Suchbegriff nach Datum sortiert
    Dann findet «inhaber-urinet» mit diesen Filtern diese Dokumente in dieser Reihenfolge:
      | filter                                  | treffer                                      |
      | art=AUSGANGSRECHNUNG                    | U_INTERN, U_RECHNUNG_A, U_RECHNUNG_B         |
      | partner=FC Muster                       | U_OFFERTE, U_RECHNUNG_A                      |
      | datumVon=2026-01-01&datumBis=2026-03-15 | U_RECHNUNG_A, U_PERSONAL_FAB, U_LIEFERANT    |
      | datumVon=2026-03-10&datumBis=2026-03-10 | U_RECHNUNG_A                                 |
      | betragVon=400&betragBis=1000            | U_INTERN, U_RECHNUNG_A                       |
      | betragVon=450.00&betragBis=450          | U_RECHNUNG_A                                 |
      | richtung=AUSGABE                        | U_PERSONAL_JOEL, U_PERSONAL_FAB, U_LIEFERANT |
      | tag=web                                 | U_LIEFERANT, U_RECHNUNG_B                    |
      | tag=web&tag=wartung                     | U_RECHNUNG_B                                 |
      | status=EINGANGSKORB                     | U_OFFEN_FREMD, U_OFFEN_EIGEN                 |
      | status=VERWORFEN                        | U_VERWORFEN                                  |
      | geschaeftsjahr=2025                     | U_RECHNUNG_B                                 |
      | belegnummer=O-2026-005                  | U_OFFERTE                                    |
      | belegnummer=o-2026-005                  |                                              |

  @req-14.2 @req-14.5
  Szenario: Filter lassen sich kombinieren und gelten nur über sichtbare Dokumente
    Dann findet «inhaber-urinet» mit diesen Filtern diese Dokumente in dieser Reihenfolge:
      | filter                                                                    | treffer                |
      | art=AUSGANGSRECHNUNG&geschaeftsjahr=2026&richtung=EINNAHME&betragBis=1000 | U_INTERN, U_RECHNUNG_A |
      | partner=FC Muster&tag=sport&datumBis=2026-03-15&status=ARCHIVIERT         | U_RECHNUNG_A           |
      | q=Rechnung&art=AUSGANGSRECHNUNG&datumVon=2026-01-01&sortierung=DATUM      | U_INTERN, U_RECHNUNG_A |
    Und findet «mitarbeiter-urinet» mit diesen Filtern diese Dokumente in dieser Reihenfolge:
      | filter               | treffer                      |
      | art=AUSGANGSRECHNUNG | U_RECHNUNG_A, U_RECHNUNG_B   |
      | richtung=AUSGABE     | U_PERSONAL_JOEL, U_LIEFERANT |
      | status=EINGANGSKORB  | U_OFFEN_EIGEN                |

  @req-14.7
  Szenario: Eine Belegnummer als Suchbegriff liefert genau dieses Dokument zuerst, nur im eigenen Mandanten
    Wenn «inhaber-urinet» sucht mit:
      | q | 2026-001 |
    Dann ist der erste Treffer «U_RECHNUNG_A»
    Und die Treffer enthalten «V_RECHNUNG» nicht
    Wenn «inhaber-urinet» sucht mit:
      | q | F0003 |
    Dann lauten die Treffer der Reihe nach «U_LIEFERANT»
    Wenn «geschaeftsstelle-vhf-gsk» sucht mit:
      | q | 2026-001 |
    Dann lauten die Treffer der Reihe nach «V_RECHNUNG»

  @req-14.4
  Szenario: Treffer zeigen eine Textstelle mit hervorgehobenem Suchbegriff
    Wenn «inhaber-urinet» sucht mit:
      | q | Trikots |
    Dann zeigt der Auszug von «U_RECHNUNG_A» «Trikots» hervorgehoben
    Und zeigt der Auszug von «U_OFFERTE» «Trikots» hervorgehoben
    Wenn «inhaber-urinet» sucht mit:
      | art | OFFERTE |
    Dann ist der Auszug von «U_OFFERTE» leer

  @req-14.1 @req-14.5
  Szenario: Bösartige und ungültige Eingaben richten keinen Schaden an
    Dann antwortet die Suche für «inhaber-urinet» so:
      | parameter         | wert                        | status | grund                               |
      | q                 | '; drop table dokument; --  | 200    | gebunden, nie Teil des SQL          |
      | q                 | Rechnung' or '1'='1         | 200    | gebunden                            |
      | q                 | a:* & !b \| (c <-> d)       | 200    | websearch_to_tsquery, keine tsquery |
      | q                 | ))) ((( "" -- or and not    | 200    | Websuche-Syntax ohne Fehler         |
      | q                 | der die das                 | 200    | nur Stoppwörter                     |
      | tag               | x'] or true; --             | 200    | gebunden                            |
      | belegnummer       | 1' or '1'='1                | 200    | gebunden                            |
      | q                 | {201 Zeichen}               | 400    | Suchbegriff zu lang                 |
      | q                 | {Steuerzeichen}             | 400    | Steuerzeichen                       |
      | art               | AUSGANGSRECHNUNG' or 1=1 -- | 400    | kein Code einer Dokumentart         |
      | partner           | 1 or 1=1                    | 400    | keine UUID                          |
      | datumVon          | 2026-13-01                  | 400    | kein Datum                          |
      | datumVon,datumBis | 2026-03-02,2026-03-01       | 400    | von nach bis                        |
      | betragVon         | 12.345                      | 400    | mehr als zwei Nachkommastellen      |
      | betragVon         | 99999999999                 | 400    | grösser als NUMERIC(12,2)           |
      | betragVon         | abc                         | 400    | keine Zahl                          |
      | richtung          | SEITWAERTS                  | 400    | unbekannte Richtung                 |
      | status            | ARCHIVIERT' or '1'='1       | 400    | unbekannter Status                  |
      | geschaeftsjahr    | 2026 or 1=1                 | 400    | keine Zahl                          |
      | sortierung        | id; drop table dokument     | 400    | unbekannte Sortierung               |
      | seite             | -1                          | 400    | negative Seite                      |
      | groesse           | 101                         | 400    | zu gross                            |
      | groesse           | 0                           | 400    | zu klein                            |
      | tag               | {11 Tags}                   | 400    | zu viele Tags                       |
    Und finden die Benutzer mit dem Suchbegriff «Rechnung» diese Dokumente:
      | benutzer       | treffer                                                                                                          | gesamt | grund               |
      | inhaber-urinet | U_RECHNUNG_A, U_RECHNUNG_B, U_LIEFERANT, U_INTERN, U_PERSONAL_JOEL, U_PERSONAL_FAB, U_OFFEN_EIGEN, U_OFFEN_FREMD | 8      | Tabelle unverändert |

  @req-14.5
  Szenario: Der Suchbegriff erscheint nicht im Log, auch nicht bei einer ungültigen Anfrage
    Angenommen das Log der Anwendung wird mitgeschnitten
    Wenn «inhaber-urinet» sucht mit:
      | q | Geheimwortxyz Trikots |
    Und «inhaber-urinet» sucht mit:
      | q        | Geheimwortxyz |
      | datumVon | kein-datum    |
    Dann enthält das Log «Geheimwortxyz» nicht

  @req-14.5
  Szenario: Ohne Anmeldung gibt es keine Suche
    Dann antwortet die Suche ohne Anmeldung mit 401
