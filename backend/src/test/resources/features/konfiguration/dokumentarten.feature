# language: de
@spec-05
Funktionalität: Mandantenspezifische Dokumentart-Attribute
  Die zusätzlichen Attribute steuern Dokumentnummern, Umstufungen und KI-Vorschläge je Mandant.

  @req-2.1
  Szenario: Die Pflege speichert die neuen Attribute einer Dokumentart
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_ZIEL","name":"Probe Ziel","vertraulichkeit":"NORMAL","gesetzlichePflicht":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_ATTR","name":"Probe Attribute","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "selbstAusgestellt":true,"belegnummerMuster":["^X-(?<nr>\\d{3})$"],
       "fremdbelegArt":"PROBE_DA_ZIEL","ohneZahlungsdatenArt":null,"kiVorschlag":false,
       "kiFelder":["referenz"],"kiHinweise":"Nur synthetische Probe","reihenfolge":17,
       "gruppen":["frei"],"pflichtfelder":[],"zusatzSchema":{"type":"object","properties":
       {"referenz":{"type":"string"}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und hat die Dokumentart in der Antwort diese Werte:
      | selbstAusgestellt | true                         |
      | belegnummerMuster | [^X-(?<nr>\d{3})$]            |
      | fremdbelegArt     | PROBE_DA_ZIEL                 |
      | kiVorschlag       | false                         |
      | kiFelder          | [referenz]                    |
      | kiHinweise        | Nur synthetische Probe        |
      | reihenfolge       | 17                            |

  @req-2.3
  Szenario: Die Belegnummer-Muster des Mandanten stammen aus seinen Dokumentarten
    Wenn «inhaber-urinet» die Dokumentarten auflistet
    Dann enthält die Liste der Dokumentarten «AUSGANGSRECHNUNG» mit diesen Werten:
      | selbstAusgestellt | true                                        |
      | belegnummerMuster | [^\d{4}-\d{3}$, ^R\d{4}$]                  |
      | fremdbelegArt     | LIEFERANTENBELEG                            |
    Und enthält die Liste der Dokumentarten «OFFERTE» mit diesen Werten:
      | selbstAusgestellt | true                         |
      | belegnummerMuster | [^O-\d{4}-\d{3}$]             |

  @req-2.6
  Szenario: Die Art WISSENSQUELLE behält ihre festen Eigenschaften
    Wenn eine Test-Konfiguration die Art «WISSENSQUELLE» mit geänderten festen Eigenschaften prüft
    Dann enthält die Test-Konfiguration den Befund «WISSENSQUELLE_UNGUELTIG»

  @req-2.2
  Szenario: Nur selbst ausgestellte Arten verhindern doppelte Belegnummern
    Angenommen der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_EIGEN","name":"Probe Eigen","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "gruppen":["finanz"],"selbstAusgestellt":true}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_FREMD","name":"Probe Fremd","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "gruppen":["finanz"],"selbstAusgestellt":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      {"art":"PROBE_DA_FREMD","titel":"Probe","datum":"2026-03-10",
       "finanz":{"belegnummer":"PROBE-17","richtung":"EINNAHME","betrag":"100.00","waehrung":"CHF"}}
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1»
    Wenn der Generator die PDF «R2» mit diesen Metadaten sendet:
      """
      {"art":"PROBE_DA_FREMD","titel":"Probe","datum":"2026-03-10",
       "finanz":{"belegnummer":"PROBE-17","richtung":"EINNAHME","betrag":"100.00","waehrung":"CHF"}}
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R2»
    Wenn der Generator die PDF «R3» mit diesen Metadaten sendet:
      """
      {"art":"PROBE_DA_EIGEN","titel":"Probe","datum":"2026-03-10",
       "finanz":{"belegnummer":"PROBE-18","richtung":"EINNAHME","betrag":"100.00","waehrung":"CHF"}}
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R3»
    Wenn der Generator die PDF «R4» mit diesen Metadaten sendet:
      """
      {"art":"PROBE_DA_EIGEN","titel":"Probe","datum":"2026-03-10",
       "finanz":{"belegnummer":"PROBE-18","richtung":"EINNAHME","betrag":"100.00","waehrung":"CHF"}}
      """
    Dann antwortet der Generator mit Status 409 und Fehlercode «BELEGNUMMER_VERGEBEN»

  @req-2.4
  Szenario: Ungültige Belegnummer-Muster werden abgelehnt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_MUSTER","name":"Probe Muster","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "belegnummerMuster":["("]}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

  @req-2.7
  Szenario: Ein reserviertes Zusatzfeld darf nicht als KI-Feld vorgeschlagen werden
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_KI","name":"Probe KI","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "kiFelder":["zugriff"],"zusatzSchema":{"type":"object","properties":
       {"zugriff":{"type":"string","enum":["oeffentlich","mitglieder","vorstand"],"default":"mitglieder"}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

  @req-2.8
  Szenario: Eine Umstufung darf nicht auf eine weniger vertrauliche Art verweisen
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_ZIEL","name":"Probe Ziel","vertraulichkeit":"NORMAL","gesetzlichePflicht":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_QUELLE","name":"Probe Quelle","vertraulichkeit":"INTERN","gesetzlichePflicht":false,
       "fremdbelegArt":"PROBE_DA_ZIEL"}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

  @req-2.8
  Szenario: Ein Verweis auf eine nicht vorhandene Art wird fachlich abgelehnt
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_QUELLE","name":"Probe Quelle","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "fremdbelegArt":"PROBE_DA_GIBT_ES_NICHT"}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

  @req-2.8
  Szenario: Eine Art, auf die eine andere verweist, lässt sich nicht löschen
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_ZIEL","name":"Probe Ziel","vertraulichkeit":"NORMAL","gesetzlichePflicht":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_QUELLE","name":"Probe Quelle","vertraulichkeit":"NORMAL","gesetzlichePflicht":false,
       "ohneZahlungsdatenArt":"PROBE_DA_ZIEL"}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Wenn «inhaber-urinet» die Dokumentart «PROBE_DA_ZIEL» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 409 und Fehlercode «DOKUMENTART_IN_GEBRAUCH»
