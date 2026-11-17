# language: de
@spec-05
Funktionalität: Konfigurationsversionen
  Änderungen an der Konfiguration werden je Mandant versioniert. Frühere Abbilder bleiben lesbar, und Dokumente
  halten fest, welche Konfigurationsversion angewandt wurde.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-1.1
  Szenario: Die Konfigurationsversionen und ein vollständiges Abbild sind lesbar
    Wenn «inhaber-urinet» die Konfigurationsversionen auflistet
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und enthält die Antwort mindestens eine Konfigurationsversion
    Wenn «inhaber-urinet» die neueste Konfigurationsversion abruft
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und enthält die Antwort ein vollständiges Konfigurationsabbild

  @req-1.2
  Szenario: Konfigurationsversionen eines Mandanten bleiben von Änderungen anderer Mandanten getrennt
    Wenn «inhaber-urinet» eine Dokumentart mit der aktuellen Version anlegt:
      """
      {"code":"PROBE_KONF_TRENNUNG","name":"Probe Urinet","vertraulichkeit":"NORMAL","aufbewahrungJahre":null,"gesetzlichePflicht":false}
      """
    Dann antwortet die Konfigurationsverwaltung mit Status 201
    Wenn «inhaber-vhf-gsk» die aktuelle Konfigurationsversion abruft
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und enthält die aktuelle Konfiguration die Art «PROBE_KONF_TRENNUNG» nicht

  @req-1.3 @req-1.5
  Szenario: Änderungen verlangen die aktuelle Version und bewahren frühere Abbilder
    Wenn «inhaber-urinet» eine Dokumentart ohne If-Match anlegt:
      """
      {"code":"PROBE_KONF_VERSION","name":"Probe vorher","vertraulichkeit":"NORMAL","aufbewahrungJahre":null,"gesetzlichePflicht":false}
      """
    Dann antwortet die Konfigurationsverwaltung mit Status 428 und Fehlercode «VERSION_FEHLT»
    Wenn «inhaber-urinet» eine Dokumentart mit einer veralteten Version anlegt:
      """
      {"code":"PROBE_KONF_VERSION","name":"Probe vorher","vertraulichkeit":"NORMAL","aufbewahrungJahre":null,"gesetzlichePflicht":false}
      """
    Dann antwortet die Konfigurationsverwaltung mit Status 412 und Fehlercode «VERSION_KONFLIKT»
    Wenn «inhaber-urinet» eine Dokumentart mit der aktuellen Version anlegt:
      """
      {"code":"PROBE_KONF_VERSION","name":"Probe vorher","vertraulichkeit":"NORMAL","aufbewahrungJahre":null,"gesetzlichePflicht":false}
      """
    Dann antwortet die Konfigurationsverwaltung mit Status 201
    Und ist die Konfigurationsversion um eins gestiegen
    Wenn «inhaber-urinet» die Dokumentart «PROBE_KONF_VERSION» mit der aktuellen Version ändert:
      """
      {"name":"Probe nachher"}
      """
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Konfigurationsversion um eins gestiegen
    Wenn «inhaber-urinet» die zuvor aktuelle Konfigurationsversion abruft
    Dann antwortet die Konfigurationsverwaltung mit Status 200
    Und ist die Art «PROBE_KONF_VERSION» in dieser Version unter dem Namen "Probe vorher" enthalten

  @req-1.4
  Szenario: Bestätigen und Ändern übernehmen die angewandte Konfigurationsversion
    Angenommen die Archivierung ist bereit
    Und im Mandanten «urinet» liegt das Dokument «KV» im Status «EINGANGSKORB», erfasst von «mitarbeiter-urinet»
    Wenn «inhaber-urinet» das Dokument «KV» so bestätigt:
      | art      | QUITTUNG   |
      | titel    | Quittung   |
      | datum    | 2026-03-10 |
      | richtung | AUSGABE    |
      | betrag   | 12.00      |
      | waehrung | CHF        |
    Dann meldet das Archiv Status 202
    Und der Worker die Archivierung ausführt
    Wenn «inhaber-urinet» das Dokument «KV» so ändert:
      | titel | Quittung aktualisiert |
      | grund | Konfiguration prüfen  |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «KV» die angewandte Konfigurationsversion im Dokument und in der letzten Fassung
