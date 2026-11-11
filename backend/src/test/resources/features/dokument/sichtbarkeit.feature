# language: de
@spec-01
Funktionalität: Sichtbarkeit der Dokumente nach Rolle und Vertraulichkeit (Aufgabe 2.2)
  Der VertraulichkeitFilter setzt die Zeilen A2–A5 und A7 der Rechtematrix in jeder Abfrage um: Direktzugriff
  («GET /api/v1/dokumente/{id}»), Eingangskorb («GET /api/v1/eingangskorb») samt Gesamtzahl und Paging. Testdokumente
  entstehen per JDBC, bis Erfassung und Bestätigung (Welle 3) sie anlegen; Erfasser und Person sind Testbenutzer.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und im Mandanten «urinet» liegen diese Dokumente:
      | name              | art | vertraulichkeit | bestaetigt | erfasser           | person             | status              |
      | U_NORMAL          |     | NORMAL          | ja         | inhaber-urinet     |                    | ARCHIVIERT          |
      | U_INTERN          |     | INTERN          | ja         | inhaber-urinet     |                    | ARCHIVIERT          |
      | U_PERSONAL_EIGEN  |     | PERSONAL        | ja         | inhaber-urinet     | mitarbeiter-urinet | ARCHIVIERT          |
      | U_PERSONAL_FREMD  |     | PERSONAL        | ja         | inhaber-urinet     | inhaber-urinet     | ARCHIVIERT          |
      | U_OFFEN_EIGEN     |     | NORMAL          | nein       | mitarbeiter-urinet |                    | EINGANGSKORB        |
      | U_OFFEN_FREMD     |     | NORMAL          | nein       | inhaber-urinet     | mitarbeiter-urinet | EINGANGSKORB        |
      | U_OFFEN_KEINER    |     | NORMAL          | nein       | inhaber-urinet     |                    | EINGANGSKORB        |
      | U_NEU_FUER_ANDERE |     | NORMAL          | nein       | mitarbeiter-urinet | inhaber-urinet     | VERARBEITUNG        |
      | U_FEHLER_INTERN   |     | INTERN          | ja         | mitarbeiter-urinet |                    | ARCHIVIERUNG_FEHLER |
      | U_FEHLER_FREMD    |     | NORMAL          | ja         | inhaber-urinet     |                    | FEHLER              |
    Und im Mandanten «vhf-gsk» liegen diese Dokumente:
      | name        | art | vertraulichkeit | bestaetigt | erfasser                 | person | status       |
      | V_NORMAL    |     | NORMAL          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT   |
      | V_INTERN    |     | INTERN          | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT   |
      | V_PERSONAL  |     | PERSONAL        | ja         | geschaeftsstelle-vhf-gsk |        | ARCHIVIERT   |
      | V_OFFEN     |     | NORMAL          | nein       | geschaeftsstelle-vhf-gsk |        | EINGANGSKORB |
      | V_NEU_FREMD |     | NORMAL          | nein       | inhaber-vhf-gsk          |        | VERARBEITUNG |

  @req-2.3 @req-2.8 @req-2.9 @req-2.10 @req-2.21 @req-1.4
  Szenario: Jede Rolle sieht im Direktzugriff nur, was ihr die Rechtematrix erlaubt; sonst 404
    Dann sehen die Benutzer die Dokumente so:
      | benutzer                 | dokument         | status | grund                                                                             |
      | inhaber-urinet           | U_NORMAL         | 200    | A2                                                                                |
      | inhaber-urinet           | U_INTERN         | 200    | A3                                                                                |
      | inhaber-urinet           | U_PERSONAL_EIGEN | 200    | A4                                                                                |
      | inhaber-urinet           | U_OFFEN_EIGEN    | 200    | unbestätigt, Recht A7                                                             |
      | inhaber-urinet           | V_NORMAL         | 404    | fremder Mandant (1.4)                                                             |
      | mitarbeiter-urinet       | U_NORMAL         | 200    | NORMAL unabhängig von der Rolle (2.8)                                             |
      | mitarbeiter-urinet       | U_INTERN         | 404    | INTERN nicht für mitarbeiter (2.9)                                                |
      | mitarbeiter-urinet       | U_PERSONAL_EIGEN | 200    | person_benutzer ist er selbst (Fussnote ²)                                        |
      | mitarbeiter-urinet       | U_PERSONAL_FREMD | 404    | PERSONAL, nicht seines (2.10)                                                     |
      | mitarbeiter-urinet       | U_OFFEN_EIGEN    | 200    | unbestätigt, selbst erfasst (2.21)                                                |
      | mitarbeiter-urinet       | U_OFFEN_FREMD    | 404    | unbestätigt, fremd erfasst, auch mit person_benutzer er selbst (2.21, Fussnote ²) |
      | mitarbeiter-urinet       | U_OFFEN_KEINER   | 404    | unbestätigt, fremd erfasst, ohne person_benutzer (2.21)                           |
      | mitarbeiter-urinet       | V_NORMAL         | 404    | fremder Mandant (1.4)                                                             |
      | treuhaender-urinet       | U_NORMAL         | 200    | A2                                                                                |
      | treuhaender-urinet       | U_INTERN         | 200    | A3 nur lesend                                                                     |
      | treuhaender-urinet       | U_PERSONAL_FREMD | 200    | A4 nur lesend                                                                     |
      | treuhaender-urinet       | U_OFFEN_EIGEN    | 404    | unbestätigt, ohne Recht A7 (2.21)                                                 |
      | treuhaender-urinet       | U_OFFEN_FREMD    | 404    | unbestätigt, ohne Recht A7 (2.21)                                                 |
      | treuhaender-urinet       | U_FEHLER_FREMD   | 200    | A2                                                                                |
      | geschaeftsstelle-vhf-gsk | V_NORMAL         | 200    | A2                                                                                |
      | geschaeftsstelle-vhf-gsk | V_INTERN         | 200    | A3                                                                                |
      | geschaeftsstelle-vhf-gsk | V_PERSONAL       | 200    | A4                                                                                |
      | geschaeftsstelle-vhf-gsk | V_OFFEN          | 200    | unbestätigt, Recht A7                                                             |
      | geschaeftsstelle-vhf-gsk | U_NORMAL         | 404    | fremder Mandant (1.4)                                                             |
      | inhaber-vhf-gsk          | V_OFFEN          | 200    | unbestätigt, Recht A7                                                             |
      | inhaber-vhf-gsk          | U_PERSONAL_FREMD | 404    | fremder Mandant (1.4)                                                             |

  # Entschieden von Fabian am 11.11.2026 (P45 = nein): Ein unbestätigtes Dokument sehen nur der Erfasser und die Rollen
  # mit Recht A7; person_benutzer wirkt erst nach der Bestätigung. Je Rolle mit Gegenprobe.
  @req-2.21
  Szenario: inhaber sieht jedes unbestätigte Dokument seines Mandanten (A7), keines eines fremden Mandanten
    Dann sehen die Benutzer die Dokumente so:
      | benutzer       | dokument          | status | grund                                    |
      | inhaber-urinet | U_OFFEN_EIGEN     | 200    | unbestätigt, vom mitarbeiter erfasst, A7 |
      | inhaber-urinet | U_OFFEN_KEINER    | 200    | unbestätigt, selbst erfasst              |
      | inhaber-urinet | U_NEU_FUER_ANDERE | 200    | unbestätigt, in Verarbeitung, A7         |
      | inhaber-urinet | V_OFFEN           | 404    | Gegenprobe: unbestätigt, fremder Mandant |

  @req-2.21 @req-2.10
  Szenario: mitarbeiter sieht ein unbestätigtes Dokument nur, wenn er es erfasst hat, nicht über person_benutzer
    Dann sehen die Benutzer die Dokumente so:
      | benutzer           | dokument          | status | grund                                                        |
      | mitarbeiter-urinet | U_OFFEN_EIGEN     | 200    | unbestätigt, selbst erfasst                                  |
      | mitarbeiter-urinet | U_NEU_FUER_ANDERE | 200    | unbestätigt, selbst erfasst, person_benutzer ein anderer     |
      | mitarbeiter-urinet | U_OFFEN_FREMD     | 404    | Gegenprobe: unbestätigt, fremd erfasst, person_benutzer er   |
      | mitarbeiter-urinet | U_OFFEN_KEINER    | 404    | Gegenprobe: unbestätigt, fremd erfasst, ohne person_benutzer |
      | mitarbeiter-urinet | U_PERSONAL_EIGEN  | 200    | Gegenprobe: bestätigt, fremd erfasst, person_benutzer er     |

  @req-2.21
  Szenario: treuhaender sieht kein unbestätigtes Dokument (ohne A7, erfasst nie), wohl aber bestätigte PERSONAL
    Dann sehen die Benutzer die Dokumente so:
      | benutzer           | dokument          | status | grund                                     |
      | treuhaender-urinet | U_OFFEN_KEINER    | 404    | unbestätigt, ohne Recht A7                |
      | treuhaender-urinet | U_NEU_FUER_ANDERE | 404    | unbestätigt, ohne Recht A7                |
      | treuhaender-urinet | U_PERSONAL_EIGEN  | 200    | Gegenprobe: bestätigt PERSONAL, A4 lesend |

  @req-2.21
  Szenario: geschaeftsstelle sieht jedes unbestätigte Dokument ihres Mandanten (A7), keines eines fremden Mandanten
    Dann sehen die Benutzer die Dokumente so:
      | benutzer                 | dokument      | status | grund                                    |
      | geschaeftsstelle-vhf-gsk | V_OFFEN       | 200    | unbestätigt, selbst erfasst              |
      | geschaeftsstelle-vhf-gsk | V_NEU_FREMD   | 200    | unbestätigt, fremd erfasst, A7           |
      | geschaeftsstelle-vhf-gsk | U_OFFEN_EIGEN | 404    | Gegenprobe: unbestätigt, fremder Mandant |

  @req-1.4
  Szenario: Eine Dokument-ID eines anderen Mandanten antwortet wie eine unbekannte mit 404
    Wenn «inhaber-vhf-gsk» das Dokument «U_NORMAL» liest
    Dann antwortet der Dokument-Endpunkt mit Status 404 und Fehlercode «NICHT_GEFUNDEN»
    Wenn «inhaber-vhf-gsk» das Dokument «UNBEKANNT» liest
    Dann antwortet der Dokument-Endpunkt mit Status 404 und Fehlercode «NICHT_GEFUNDEN»

  @req-2.21
  Szenario: Ein Dokument ohne bestätigte Art erscheint als PERSONAL, auch wenn eine andere Stufe gespeichert ist
    Wenn «mitarbeiter-urinet» das Dokument «U_OFFEN_EIGEN» liest
    Dann antwortet der Dokument-Endpunkt mit Status 200
    Und hat das Dokument in der Antwort diese Werte:
      | vertraulichkeit | PERSONAL     |
      | status          | EINGANGSKORB |
      | bestaetigt      | null         |
    Und trägt die Antwort das ETag der Version des Dokuments

  # U_FEHLER_FREMD (NORMAL) sieht er im Direktzugriff, im Eingangskorb nicht (A5).
  @req-2.24 @req-2.3
  Szenario: mitarbeiter sieht im Eingangskorb nur selbst erfasste und für ihn sichtbare Dokumente
    Wenn «mitarbeiter-urinet» das Dokument «U_FEHLER_FREMD» liest
    Dann antwortet der Dokument-Endpunkt mit Status 200
    Wenn «mitarbeiter-urinet» den Eingangskorb abruft
    Dann liefert der Eingangskorb die Gesamtzahl 1 und diese Dokumente:
      | U_OFFEN_EIGEN |

  @req-2.3
  Szenario: inhaber sieht den ganzen Eingangskorb; Gesamtzahl und Paging zählen nur sichtbare Dokumente
    Wenn «inhaber-urinet» den Eingangskorb abruft
    Dann liefert der Eingangskorb die Gesamtzahl 5 und diese Dokumente:
      | U_OFFEN_EIGEN   |
      | U_OFFEN_FREMD   |
      | U_OFFEN_KEINER  |
      | U_FEHLER_INTERN |
      | U_FEHLER_FREMD  |
    Wenn «inhaber-urinet» den Eingangskorb mit Seite 2 und Grösse 2 abruft
    Dann liefert der Eingangskorb die Gesamtzahl 5 und 1 Dokument
    Wenn «geschaeftsstelle-vhf-gsk» den Eingangskorb abruft
    Dann liefert der Eingangskorb die Gesamtzahl 1 und diese Dokumente:
      | V_OFFEN |

  @req-2.3
  Szenario: treuhaender hat keinen Eingangskorb (A5 verboten)
    Wenn «treuhaender-urinet» den Eingangskorb abruft
    Dann antwortet der Dokument-Endpunkt mit Status 403 und Fehlercode «NICHT_ERLAUBT»

  Szenario: Ungültige Seite oder Grösse im Eingangskorb
    Wenn «inhaber-urinet» den Eingangskorb mit Seite 0 und Grösse 101 abruft
    Dann antwortet der Dokument-Endpunkt mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Wenn «inhaber-urinet» den Eingangskorb mit Seite -1 und Grösse 10 abruft
    Dann antwortet der Dokument-Endpunkt mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
