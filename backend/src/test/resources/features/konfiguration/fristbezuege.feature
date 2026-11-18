# language: de
@spec-05
Funktionalität: Taggenaue Aufbewahrung ab Belegdatum oder Zusatzfeld

  Hintergrund:
    Angenommen die Archivierung ist bereit

  @req-4.1 @req-4.3
  Szenariogrundriss: Belegdatum gilt taggenau und fehlt es, zählt der Eingang in Zürich
    Wenn die Frist für die Regel «10 Jahre ab BELEGDATUM» mit Belegdatum «<beleg>», Eingang «<eingang>», Geschäftsjahr «2025», gültig bis «–» und Geschäftsjahresende «12-31» am «17.11.2026» berechnet wird
    Dann ist aufbewahren_bis «<bis>» und die Frist laufend

    Beispiele:
      | beleg      | eingang              | bis        |
      | 29.02.2028 | 2026-03-10T12:00:00Z | 28.02.2038 |
      | –          | 2026-12-31T23:30:00Z | 01.01.2037 |
      | –          | 2026-06-30T22:30:00Z | 01.07.2036 |

  @req-4.2
  Szenariogrundriss: Nur ein pflichtiges Datumsfeld darf Fristbezug sein
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_FRIST","name":"Probe Frist","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "gruppen":["frei"],"pflichtfelder":<pflicht>,"aufbewahrungAb":"FELD","aufbewahrungFeld":"sitzung",
       "zusatzSchema":{"type":"object","properties":<felder>}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

    Beispiele:
      | pflicht            | felder                                                |
      | ["zusatz.sitzung"]  | {}                                                    |
      | ["zusatz.sitzung"]  | {"sitzung":{"type":"integer"}}                        |
      | ["zusatz.sitzung"]  | {"sitzung":{"type":"string","format":"date-time"}}    |
      | []                 | {"sitzung":{"type":"string","format":"date"}}         |

  @req-4.1 @req-4.5 @req-4.6
  Szenariogrundriss: Das Zusatzdatum steuert Frist und Sperre und eine frühere Frist gilt nur ohne Schutz
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_FRIST","name":"Probe Frist","vertraulichkeit":"NORMAL","gesetzlichePflicht":<pflicht>,
       "gruppen":["frei"],"pflichtfelder":["titel","zusatz.sitzung"],
       "aufbewahrungJahre":10,"aufbewahrungAb":"FELD","aufbewahrungFeld":"sitzung",
       "zusatzSchema":{"type":"object","properties":{"sitzung":{"type":"string","format":"date"}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und hat die Dokumentart in der Antwort diese Werte:
      | aufbewahrungAb   | FELD    |
      | aufbewahrungFeld | sitzung |
    Angenommen im Mandanten «urinet» liegt das Dokument «F» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «F» mit dem Körper '{"art":"PROBE_DA_FRIST","zusatz":{"sitzung":"2028-02-29"}}' ändert
    Dann meldet das Archiv Status 200
    Wenn «inhaber-urinet» das Dokument «F» so bestätigt:
      | art   | PROBE_DA_FRIST |
      | titel | Sitzung Probe |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «F» diese Werte:
      | aufbewahren_bis | 2038-02-28 |
    Wenn der Worker die Archivierung ausführt
    Und «inhaber-urinet» das Dokument «F» mit dem Körper '{"zusatz":{"sitzung":"2027-01-15"},"grund":"Datum korrigiert"}' ändert
    Dann meldet das Archiv Status 200
    Und hat das Dokument «F» diese Werte:
      | aufbewahren_bis | <bis> |
    Wenn «inhaber-urinet» das Dokument «F» mit dem Körper '{"zusatz":{"sitzung":"2029-03-01"},"grund":"Datum berichtigt"}' ändert
    Dann meldet das Archiv Status 200
    Und hat das Dokument «F» diese Werte:
      | aufbewahren_bis | 2039-03-01 |

    Beispiele:
      | pflicht | bis        |
      | true    | 2038-02-28 |
      | false   | 2037-01-15 |

  @req-4.4 @req-4.6
  Szenariogrundriss: Ein fehlendes Fristfeld im Bestand bleibt unbefristet mit Warnung
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_FRIST","name":"Probe Frist","vertraulichkeit":"NORMAL","gesetzlichePflicht":<pflicht>,
       "gruppen":["frei"],"pflichtfelder":["titel","zusatz.sitzung"],"aufbewahrungAb":"FELD",
       "aufbewahrungFeld":"sitzung","zusatzSchema":{"type":"object","properties":
       {"sitzung":{"type":"string","format":"date"}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» liegt das Dokument «F» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «F» mit dem Körper '{"art":"PROBE_DA_FRIST","titel":"Bestand Probe"}' ändert
    Dann meldet das Archiv Status 200
    Und zeigt die Antwort die Warnung «FRISTBEZUG_FEHLT» ohne Dokumentverweis
    Wenn «inhaber-urinet» das Dokument «F» so bestätigt:
      | art   | PROBE_DA_FRIST |
      | titel | Bestand Probe |
    Dann meldet das Archiv Status 202
    Und zeigt die Antwort die Warnung «FRISTBEZUG_FEHLT» ohne Dokumentverweis
    Und hat das Dokument «F» diese Werte:
      | aufbewahren_bis | null |
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «F» im Status «ARCHIVIERT»
    Und haben die Archivdateien von «F» <sperre> Sperre

    Beispiele:
      | pflicht | sperre |
      | true    | eine   |
      | false   | keine  |

  @req-4.5 @req-4.6
  Szenario: Eine bestehende Sperre bleibt auch nach Wechsel auf eine Art ohne Pflicht geschützt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_FRIST","name":"Probe Frist","vertraulichkeit":"NORMAL","gesetzlichePflicht":true,
       "aufbewahrungAb":"BELEGDATUM","pflichtfelder":["titel"]}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» liegt das Dokument «F» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «F» so bestätigt:
      | art   | PROBE_DA_FRIST |
      | titel | Sperre Probe  |
      | datum | 2028-02-29    |
    Dann meldet das Archiv Status 202
    Wenn der Worker die Archivierung ausführt
    Und die Sperren der Dateien von «F» sind gemerkt
    Und «inhaber-urinet» die Dokumentart «PROBE_DA_FRIST» ändert:
      """
      {"gesetzlichePflicht":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 200
    Wenn «inhaber-urinet» das Dokument «F» so ändert:
      | datum | 2027-01-15          |
      | grund | Bezugsdatum geändert |
    Dann meldet das Archiv Status 200
    Und hat das Dokument «F» diese Werte:
      | aufbewahren_bis | 2038-02-28 |
    Und sind die Sperren der Dateien von «F» unverändert
    Und hält das Zugriffsprotokoll für «F» «SPERRE_KONFLIKT» von «inhaber-urinet» mit der Frist bisher «2038-02-28» und neu «2037-01-15» fest

  @req-4.6
  Szenario: Abgelaufene taggenaue Frist verlangt weiterhin den Entscheid des Inhabers
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_FRIST","name":"Probe Frist","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "aufbewahrungAb":"BELEGDATUM","pflichtfelder":["titel"]}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Angenommen im Mandanten «urinet» liegt das Dokument «F» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «F» so bestätigt:
      | art   | PROBE_DA_FRIST |
      | titel | Altbestand    |
      | datum | 2000-01-01    |
    Dann meldet das Archiv Status 409 mit Fehlercode «FRIST_ABGELAUFEN»
    Und ist das Dokument «F» im Status «EINGANGSKORB»
