# language: de
@spec-03
Funktionalität: Erfassung durch die Geschäftsstelle
  Die Rolle «geschaeftsstelle» erfasst nur im eigenen Mandanten. Veröffentlichungen sind erst nach der Archivierung
  möglich; Zugriffsstufe und Zusatzfelder werden von einer Person gewählt.

  Hintergrund:
    Angenommen die Archivierung ist bereit
    Und die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-3.1 @req-3.5
  Szenario: Die Geschäftsstelle erhält A1 bis A10, aber keine Verwaltungsrechte A11 bis A14
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

  @req-3.2 @req-3.3
  Szenario: Die KI erhält nur gerahmte Publikationshinweise und setzt keine Zugriffsstufe
    Wenn der Erfassungsprompt für den Mandanten "vhf-gsk" gebaut wird
    Dann enthält er gerahmte, neutrale Hinweisfelder und verbietet KI-Zugriffsstufen

  @req-3.4
  Szenario: Ein unveröffentlichtes Dokument lässt sich vor der Archivierung nicht veröffentlichen
    Angenommen im Mandanten «vhf-gsk» liegt das Dokument «B» im Status «EINGANGSKORB», erfasst von «geschaeftsstelle-vhf-gsk»
    Wenn «geschaeftsstelle-vhf-gsk» das Dokument «B» mit dem Körper '{"zusatz":{"veroeffentlicht":true}}' ändert
    Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»
    Und ist das Dokument «B» im Status «EINGANGSKORB»
