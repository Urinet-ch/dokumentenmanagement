# language: de
@spec-01
Funktionalität: Mandanten-Kontext und Rechtematrix
  Bis es fachliche Endpunkte gibt (2.1, 2.2), prüfen Probe-Endpunkte unter src/test die Regeln:
  «/api/v1/probe/dokumentarten/{code}» liest eine Dokumentart im Schema des Mandanten (alle Archiv-Rollen),
  «/api/v1/probe/erfassen» steht für Aktion A1, «/api/v1/probe/dokumente/{id}» für A2 und
  «/api/v1/probe/dokumente/{id}/bestaetigen» für A7. Die Probe-Dokumente «NORMAL» und «INTERN» liegen im Mandanten
  «urinet» (ProbeSichtbarkeit unter src/test, bis 2.2 den VertraulichkeitFilter bringt).

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-1.3
  Szenariogrundriss: Ohne gültigen Mandanten antwortet das Archiv mit 403 und liefert keine Daten
    Wenn «<benutzer>» die Dokumentart «PROBE_VHF_BULLETIN» abfragt
    Dann antwortet das Archiv mit Status 403 und liefert keine Daten

    Beispiele:
      | benutzer          | fall                             |
      | ohne-mandant      | ohne Claim mandant               |
      | unbekannt-mandant | Mandant gibt es nicht            |

  @req-1.3
  Szenario: Ein inaktiver Mandant erhält 403 und keine Daten
    Angenommen im Register steht der inaktive Mandant «probe-inaktiv»
    Wenn «inaktiv-mandant» die Dokumentart «PROBE_VHF_BULLETIN» abfragt
    Dann antwortet das Archiv mit Status 403 und liefert keine Daten

  @req-1.4
  # Teilnachweis: Die Tabelle dokument entsteht mit 2.2; dort folgt «fremde Dokument-ID → 404».
  Szenario: Ein Datensatz eines anderen Mandanten gilt als nicht vorhanden (Teilnachweis)
    Wenn «geschaeftsstelle-vhf-gsk» die Dokumentart «PROBE_VHF_BULLETIN» abfragt
    Dann antwortet das Archiv mit Status 200 und liefert die Dokumentart «PROBE_VHF_BULLETIN»
    Wenn «inhaber-urinet» die Dokumentart «PROBE_VHF_BULLETIN» abfragt
    Dann antwortet das Archiv mit Status 404 und liefert keine Daten

  @req-1.2
  Szenario: Header und Query-Parameter setzen den Mandanten nicht
    Wenn «inhaber-urinet» die Dokumentart «PROBE_VHF_BULLETIN» abfragt und dabei «vhf-gsk» als Mandant in Header und Query angibt
    Dann antwortet das Archiv mit Status 404 und liefert keine Daten

  @req-1.6
  Szenario: Der Worker arbeitet im Kontext des Mandanten aus dem Auftrag
    Wenn der Worker im Mandanten-Kontext «vhf-gsk» die Dokumentart «PROBE_VHF_BULLETIN» liest
    Dann findet der Worker die Dokumentart
    Wenn der Worker im Mandanten-Kontext «urinet» die Dokumentart «PROBE_VHF_BULLETIN» liest
    Dann findet der Worker die Dokumentart nicht

  @req-2.2
  Szenario: Ohne Archiv-Rolle antwortet das Archiv mit 403
    Wenn «ohne-rolle-urinet» die Dokumentart «PROBE_VHF_BULLETIN» abfragt
    Dann antwortet das Archiv mit Status 403 und liefert keine Daten

  @req-2.2
  Szenario: Bei mehreren Archiv-Rollen gilt die stärkste (inhaber vor treuhaender)
    Wenn «treuhaender-urinet» ein Dokument erfasst
    Dann antwortet das Archiv mit Status 403 und liefert keine Daten
    Wenn «zwei-rollen-urinet» ein Dokument erfasst
    Dann antwortet das Archiv mit Status 204 und liefert keine Daten

  @req-2.2
  Szenario: Bei mehreren Archiv-Rollen werden die Rechte nicht vereinigt (mitarbeiter vor treuhaender)
    Wenn «treuhaender-urinet» das Probe-Dokument «INTERN» liest
    Dann antwortet das Archiv mit Status 200 und liefert das Probe-Dokument «INTERN»
    Wenn «mitarbeiter-treuhaender-urinet» das Probe-Dokument «INTERN» liest
    Dann antwortet das Archiv mit Status 404 und liefert keine Daten
    Wenn «mitarbeiter-treuhaender-urinet» ein Dokument erfasst
    Dann antwortet das Archiv mit Status 204 und liefert keine Daten

  @req-2.2 @req-2.4
  Szenariogrundriss: Erfassen (A1) nach Rolle
    Wenn «<benutzer>» ein Dokument erfasst
    Dann antwortet das Archiv mit Status <status> und liefert keine Daten

    Beispiele:
      | benutzer                 | status |
      | inhaber-urinet           | 204    |
      | mitarbeiter-urinet       | 204    |
      | treuhaender-urinet       | 403    |
      | geschaeftsstelle-vhf-gsk | 204    |

  @req-2.3 @req-2.6
  Szenariogrundriss: Auf einem Dokument gilt 404 vor 403 (Bestätigen, A7)
    Wenn «<benutzer>» das Probe-Dokument «<dokument>» bestätigt
    Dann antwortet das Archiv mit Status <status> und liefert keine Daten

    Beispiele:
      | benutzer           | dokument  | status | fall                                 |
      | mitarbeiter-urinet | INTERN    | 404    | nicht sichtbar und ohne Recht        |
      | mitarbeiter-urinet | NORMAL    | 403    | sichtbar, aber ohne Recht            |
      | treuhaender-urinet | UNBEKANNT | 404    | Dokument gibt es nicht               |
      | treuhaender-urinet | NORMAL    | 403    | sichtbar, aber ohne Recht            |
      | inhaber-vhf-gsk    | NORMAL    | 404    | Dokument eines anderen Mandanten     |
      | inhaber-urinet     | kein-uuid | 404    | keine gültige Dokument-ID            |
      | inhaber-urinet     | NORMAL    | 204    | sichtbar und berechtigt              |

  @req-2.6
  Szenario: Die Rechtematrix der Domain entspricht der Tabelle aus Requirement 2.6
    Dann gilt in der Rechtematrix:
      | Nr. | inhaber | mitarbeiter         | treuhaender          | geschaeftsstelle |
      | A1  | erlaubt | erlaubt             | 403                  | erlaubt          |
      | A2  | erlaubt | erlaubt             | erlaubt              | erlaubt          |
      | A3  | erlaubt | 404                 | erlaubt              | erlaubt          |
      | A4  | erlaubt | nur eigene ²        | erlaubt (nur lesend) | erlaubt          |
      | A5  | erlaubt | nur selbst erfasste | 403                  | erlaubt          |
      | A6  | erlaubt | 403                 | 403                  | erlaubt          |
      | A7  | erlaubt | 403                 | 403                  | erlaubt          |
      | A8  | erlaubt | 403                 | 403                  | erlaubt          |
      | A9  | erlaubt | 403                 | 403                  | erlaubt          |
      | A10 | erlaubt | 403                 | 403                  | erlaubt          |
      | A11 | erlaubt | 403                 | 403                  | 403              |
      | A12 | erlaubt | 403                 | 403                  | 403              |
      | A13 | erlaubt | 403                 | 403                  | 403              |
      | A14 | erlaubt | 403                 | 403                  | 403              |
