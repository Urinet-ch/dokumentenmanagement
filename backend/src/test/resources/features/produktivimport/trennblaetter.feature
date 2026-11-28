# language: de
@spec-09
Funktionalität: Trennblatt-Generator
  Benutzer mit Recht A1 erzeugen Trennblätter ohne Art, Trennblätter je aktiver Dokumentart ihres Mandanten und
  Ordner-Deckblätter mit freiem Titel als druckfertiges PDF (Spec 09, Requirement 6; Recht A1 nach Requirement 15.1).
  Das PDF wird gelesen wie später die Stapelanalyse: jede Seite mit 150 dpi grau gerastert, QR-Code mit ZXing. Der
  Rundlauf mit einem synthetischen Stapel (6.4) folgt mit Aufgabe 28.8. Steuer- und Formatzeichen stehen in den Titeln
  als «U+XXXX». Eine vorübergehend inaktive Art wird nach dem Szenario wieder aktiv.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-6.1 @req-6.3
  Szenario: Der Inhaber erzeugt Trennblätter ohne Art, je Art und ein Ordner-Deckblatt als ein PDF
    Wenn «inhaber-urinet» diese Trennblätter erzeugt:
      | typ    | art      | titel               | anzahl |
      | TRENN  |          |                     | 1      |
      | TRENN  | QUITTUNG |                     | 2      |
      | ORDNER |          | Versicherungen 2026 | 1      |
    Dann liefert der Trennblatt-Generator ein PDF zum Herunterladen mit 8 Seiten im Format A4 hochkant
    Und tragen die Seiten diese QR-Codes und Aufschriften:
      | seiten | qr                                            | aufschrift          |
      | 1-2    | URINET-TRENN;v=1                              | Ohne Art            |
      | 3-6    | URINET-TRENN;v=1;art=QUITTUNG                 | Quittung / Spesen   |
      | 7-8    | URINET-ORDNER;v=1;titel=Versicherungen%202026 | Versicherungen 2026 |
    Und trägt jede Seite den Hinweis «Beidseitig auf farbiges Papier drucken. Wiederverwendbar.»

  @req-6.2
  Szenario: Beide Seiten tragen denselben lesbaren QR-Code von mindestens 40 mm und die Art in grosser Schrift
    Wenn «mitarbeiter-urinet» diese Trennblätter erzeugt:
      | typ    | art      | titel     | anzahl |
      | TRENN  | QUITTUNG |           | 1      |
      | ORDNER |          | Bank 2026 | 1      |
    Dann liefert der Trennblatt-Generator ein PDF zum Herunterladen mit 4 Seiten im Format A4 hochkant
    Und tragen die Seiten diese QR-Codes und Aufschriften:
      | seiten | qr                                  | aufschrift        |
      | 1-2    | URINET-TRENN;v=1;art=QUITTUNG       | Quittung / Spesen |
      | 3-4    | URINET-ORDNER;v=1;titel=Bank%202026 | Bank 2026         |
    Und ist jeder QR-Code mindestens 40 mm breit, mit Fehlerkorrektur M oder höher
    Und steht die Aufschrift jeder Seite in mindestens 24 pt

  @req-6.2 @req-6.5
  Szenario: Auch ein Titel aus 60 Zeichen mit Umlauten bleibt als QR-Code lesbar
    Wenn «inhaber-urinet» ein Ordner-Deckblatt mit dem Titel aus 60 Zeichen «Ä» erzeugt
    Dann liefert der Trennblatt-Generator ein PDF zum Herunterladen mit 2 Seiten im Format A4 hochkant
    Und ist jeder QR-Code mindestens 40 mm breit, mit Fehlerkorrektur M oder höher
    Und enthält jeder QR-Code den Titel URL-kodiert in UTF-8

  @req-6.5
  Szenariogrundriss: Der Titel steht URL-kodiert im QR-Code: <fall>
    Wenn «inhaber-urinet» ein Ordner-Deckblatt mit dem Titel «<titel>» erzeugt
    Dann liefert der Trennblatt-Generator ein PDF zum Herunterladen mit 2 Seiten im Format A4 hochkant
    Und tragen die Seiten diese QR-Codes und Aufschriften:
      | seiten | qr   | aufschrift |
      | 1-2    | <qr> | <titel>    |

    Beispiele:
      | fall                   | titel                  | qr                                                                  |
      | Umlaute und Guillemets | Bürokosten «Ä» 2026    | URINET-ORDNER;v=1;titel=B%C3%BCrokosten%20%C2%AB%C3%84%C2%BB%202026 |
      | Trennzeichen           | Miete; Nebenkosten=A+B | URINET-ORDNER;v=1;titel=Miete%3B%20Nebenkosten%3DA%2BB              |

  @req-6.5
  Szenariogrundriss: Ein ungültiger Titel ergibt 400 TRENNBLATT_UNGUELTIG: <fall>
    Wenn «inhaber-urinet» ein Ordner-Deckblatt mit dem Titel «<titel>» erzeugt
    Dann antwortet der Trennblatt-Generator mit «400 TRENNBLATT_UNGUELTIG»

    Beispiele:
      | fall               | titel               |
      | Richtungsumkehr    | U+202EOrdner        |
      | Steuerzeichen      | OrdnerU+0007        |
      | Zeilentrenner      | AU+2028B            |
      | Nullbreitenzeichen | AU+200BB            |
      | leer               | U+0020U+0020        |
      | Schrift ohne CJK   | 档案 2026            |
      | Schrift ohne Emoji | Ordner 😀           |

  @req-6.5
  Szenario: Ein Titel über 60 Zeichen ergibt 400 TRENNBLATT_UNGUELTIG
    Wenn «inhaber-urinet» ein Ordner-Deckblatt mit dem Titel aus 61 Zeichen «Ä» erzeugt
    Dann antwortet der Trennblatt-Generator mit «400 TRENNBLATT_UNGUELTIG»

  @req-6.1
  Szenariogrundriss: Trennblätter erzeugen folgt der Zeile A1 der Rechtematrix
    Wenn «<benutzer>» diese Trennblätter erzeugt:
      | typ   | art | titel | anzahl |
      | TRENN |     |       | 1      |
    Dann antwortet der Trennblatt-Generator mit «<ergebnis>»

    Beispiele:
      | benutzer                 | ergebnis          |
      | inhaber-urinet           | 200 PDF           |
      | mitarbeiter-urinet       | 200 PDF           |
      | geschaeftsstelle-vhf-gsk | 200 PDF           |
      | treuhaender-urinet       | 403 NICHT_ERLAUBT |

  @req-6.1
  Szenario: Der Treuhänder erhält 403 NICHT_ERLAUBT, auch mit ungültigem Titel
    Wenn «treuhaender-urinet» ein Ordner-Deckblatt mit dem Titel «U+202EOrdner» erzeugt
    Dann antwortet der Trennblatt-Generator mit «403 NICHT_ERLAUBT»

  @req-6.1
  Szenariogrundriss: Nur aktive Arten des eigenen Mandanten: <fall>
    Angenommen die Art «VERSICHERUNG» ist im Mandanten «urinet» inaktiv
    Wenn «<benutzer>» diese Trennblätter erzeugt:
      | typ   | art   | titel | anzahl |
      | TRENN | <art> |       | 1      |
    Dann antwortet der Trennblatt-Generator mit «<ergebnis>»

    Beispiele:
      | fall                        | benutzer                 | art          | ergebnis              |
      | eigene aktive Art           | geschaeftsstelle-vhf-gsk | BULLETIN     | 200 PDF               |
      | Art eines anderen Mandanten | geschaeftsstelle-vhf-gsk | QUITTUNG     | 400 ANFRAGE_UNGUELTIG |
      | inaktive Art                | inhaber-urinet           | VERSICHERUNG | 400 ANFRAGE_UNGUELTIG |
      | unbekannte Art              | inhaber-urinet           | PROBE_FEHLT  | 400 ANFRAGE_UNGUELTIG |

  @req-6.1
  Szenariogrundriss: Höchstens 100 Blätter je Anfrage und nur gültige Angaben: <fall>
    Wenn «inhaber-urinet» diese Trennblätter erzeugt:
      | typ   | art   | titel   | anzahl   |
      | <typ> | <art> | <titel> | <anzahl> |
    Dann antwortet der Trennblatt-Generator mit «<ergebnis>»

    Beispiele:
      | fall                | typ    | art      | titel  | anzahl | ergebnis              |
      | 100 Blätter         | TRENN  | QUITTUNG |        | 100    | 200 PDF               |
      | 101 Blätter         | TRENN  | QUITTUNG |        | 101    | 400 ANFRAGE_UNGUELTIG |
      | keine Blätter       | TRENN  |          |        | 0      | 400 ANFRAGE_UNGUELTIG |
      | unbekannter Typ     | LEER   |          |        | 1      | 400 ANFRAGE_UNGUELTIG |
      | Titel am Trennblatt | TRENN  |          | Ordner | 1      | 400 ANFRAGE_UNGUELTIG |
      | Art am Deckblatt    | ORDNER | QUITTUNG | Ordner | 1      | 400 ANFRAGE_UNGUELTIG |

  @req-6.1
  Szenariogrundriss: Nur streng gültiges JSON: <fall>
    Wenn «inhaber-urinet» den Trennblatt-Generator mit dem Körper «<koerper>» aufruft
    Dann antwortet der Trennblatt-Generator mit «400 ANFRAGE_UNGUELTIG»

    Beispiele:
      | fall                       | koerper                                                     |
      | doppelter Schlüssel        | {"blaetter":[{"typ":"TRENN","anzahl":1,"anzahl":2}]}        |
      | doppelte Liste             | {"blaetter":[{"typ":"TRENN","anzahl":1}],"blaetter":[]}     |
      | weiterer Wert nach dem JSON | {"blaetter":[{"typ":"TRENN","anzahl":1}]} {"blaetter":[]} |

  @req-6.1
  Szenario: Ein Körper über 64 KiB ergibt 413 DATEI_ZU_GROSS, bevor die Ressource ihn liest
    Wenn «inhaber-urinet» den Trennblatt-Generator mit einem Körper von 65537 Bytes aufruft
    Dann antwortet der Trennblatt-Generator mit «413 DATEI_ZU_GROSS»

  @req-6.1
  Szenario: 100 Deckblätter mit je 60 Zeichen als JSON-Escape bleiben unter der Grenze
    Wenn «inhaber-urinet» 100 Ordner-Deckblätter mit je 60 Zeichen «Ä» als JSON-Escape anfordert
    Dann antwortet der Trennblatt-Generator mit «200 PDF»
