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

 @req-3.1 @req-3.4
 Szenario: Die Geschäftsstelle veröffentlicht nach der Archivierung und zieht zurück, beides protokolliert
 Wenn «inhaber-vhf-gsk» eine Dokumentart anlegt:
 """
 {"code": "PROBE_DA_HEFT", "name": "Heft Probe", "gruppen": [], "pflichtfelder": ["datum"],
 "zusatzSchema": {"type": "object", "properties": {"reihe": {"type": "string"}, "jahr": {"type": "integer"},
 "zugriff": {"type": "string"}, "veroeffentlicht": {"type": "boolean"}}, "additionalProperties": false},
 "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10, "aufbewahrungAb": "GESCHAEFTSJAHR",
 "gesetzlichePflicht": false}
 """
 Dann antwortet die Dokumentart-Verwaltung mit Status 201
 Und im Mandanten «vhf-gsk» liegt das Dokument «H» im Status «EINGANGSKORB», erfasst von «geschaeftsstelle-vhf-gsk»
 Und «geschaeftsstelle-vhf-gsk» das Dokument «H» so bestätigt:
 | art | PROBE_DA_HEFT |
 | titel | Heft Probe |
 | datum | 2026-03-10 |
 Und der Worker die Archivierung ausführt
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «H» mit dem Körper '{"zusatz":{"zugriff":"mitglieder","veroeffentlicht":true},"grund":"Freigabe"}' ändert
 Dann meldet das Archiv Status 200
 Und hat das Dokument «H» diese Werte:
 | zusatz | {"zugriff": "mitglieder", "veroeffentlicht": true} |
 Und steht im Änderungs-Feed für «H» zuletzt die Stufe vorher «-» und nachher «mitglieder»
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «H» mit dem Körper '{"zusatz":{"zugriff":"mitglieder","veroeffentlicht":false},"grund":"Rückzug"}' ändert
 Dann meldet das Archiv Status 200
 Und hat das Dokument «H» diese Werte:
 | zusatz | {"zugriff": "mitglieder", "veroeffentlicht": false} |
 Und steht im Änderungs-Feed für «H» zuletzt die Stufe vorher «mitglieder» und nachher «-»
 Und steht im Zugriffsprotokoll für «H» 2 Mal «AENDERN» von «geschaeftsstelle-vhf-gsk»

 @req-3.4
 Szenario: Ein Dokument ohne Archivierung lässt sich nicht veröffentlichen, auch nicht in einem anderen Mandanten
 Angenommen im Mandanten «vhf-gsk» liegt das Dokument «B» im Status «ARCHIVIERUNG», erfasst von «geschaeftsstelle-vhf-gsk»
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «B» mit dem Körper '{"zusatz":{"veroeffentlicht":true}}' ändert
 Dann meldet das Archiv Status 409 mit Fehlercode «STATUS_KONFLIKT»
 Angenommen im Mandanten «urinet» liegt das Dokument «U» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «U» mit dem Körper '{"titel":"Fremd"}' ändert
 Dann meldet das Archiv Status 404 mit Fehlercode «NICHT_GEFUNDEN»

 @req-3.1 @req-3.5
 Szenario: Der Mitarbeiter darf auch sein eigenes Dokument nicht ändern, die Geschäftsstelle keine Dokumentarten verwalten
 Angenommen im Mandanten «urinet» liegt das Dokument «M» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
 Wenn «mitarbeiter-urinet» das Dokument «M» mit dem Körper '{"titel":"X"}' ändert
 Dann meldet das Archiv Status 403 mit Fehlercode «NICHT_ERLAUBT»
 Wenn «geschaeftsstelle-vhf-gsk» eine Dokumentart anlegt:
 """
 {"code": "PROBE_DA_X", "name": "X", "gruppen": [], "pflichtfelder": [], "zusatzSchema": {},
 "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 1, "aufbewahrungAb": "GESCHAEFTSJAHR", "gesetzlichePflicht": false}
 """
 Dann antwortet die Dokumentart-Verwaltung mit Status 403 und Fehlercode «NICHT_ERLAUBT»

 @req-3.2 @req-3.3 @req-10.1
 Szenario: Die Zusatzfelder werden gegen die mit der Änderung gewählte Art geprüft
 Wenn «inhaber-vhf-gsk» eine Dokumentart anlegt:
 """
 {"code": "PROBE_DA_HEFT", "name": "Heft Probe", "gruppen": [], "pflichtfelder": [],
 "zusatzSchema": {"type": "object", "properties": {"reihe": {"type": "string"}, "jahr": {"type": "integer"}},
 "additionalProperties": false},
 "vertraulichkeit": "NORMAL", "aufbewahrungJahre": 10, "aufbewahrungAb": "GESCHAEFTSJAHR",
 "gesetzlichePflicht": false}
 """
 Dann antwortet die Dokumentart-Verwaltung mit Status 201
 Und im Mandanten «vhf-gsk» liegt das Dokument «Z» im Status «EINGANGSKORB», erfasst von «geschaeftsstelle-vhf-gsk»
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «Z» mit dem Körper '{"zusatz":{"reihe":"R","jahr":2024}}' ändert
 Dann meldet das Archiv Status 422 mit Fehlercode «ZUSATZ_UNGUELTIG»
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «Z» mit dem Körper '{"art":"PROBE_DA_HEFT","zusatz":{"reihe":"R","jahr":2024}}' ändert
 Dann meldet das Archiv Status 200
 Und hat das Dokument «Z» diese Werte:
 | art | PROBE_DA_HEFT |
 | zusatz | {"jahr": 2024, "reihe": "R"} |
 Wenn «geschaeftsstelle-vhf-gsk» das Dokument «Z» mit dem Körper '{"art":"PROBE_DA_HEFT","zusatz":{"unbekannt":"R"}}' ändert
 Dann meldet das Archiv Status 422 mit Fehlercode «ZUSATZ_UNGUELTIG»
