# language: de
@spec-05
Funktionalität: Datenschutz und reservierte Standards der Konfiguration

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-1.3
  Szenariogrundriss: Kennungsänderungen hinterlassen keine Werte im Änderungsprotokoll
    Angenommen der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Und der Stand des Zugriffsprotokolls von «<anderer>» ist gemerkt
    Wenn eine synthetische UID- und IBAN-Änderung für «<mandant>» protokolliert wird
    Dann enthält der neue Konfigurationseintrag von «<mandant>» nur Kennungsfeldnamen und «geändert»
    Und das Zugriffsprotokoll von «<anderer>» hat keinen neuen Eintrag für die Aktion «KONFIGURATION_AENDERN»

    Beispiele:
      | mandant | anderer |
      | urinet  | vhf-gsk |
      | vhf-gsk | urinet  |

  @req-2.7
  Szenariogrundriss: Eigene Artcodes verwenden nicht öffentliche Standards ohne Sonderfall im Code
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_STANDARD","name":"Probe Standard","vertraulichkeit":"INTERN","gesetzlichePflicht":false,
       "zusatzSchema":{"type":"object","required":["zugriff"],"properties":
       {"zugriff":{"type":"string","enum":["oeffentlich","mitglieder","vorstand"],"default":"<standard>"}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201

    Beispiele:
      | standard   |
      | mitglieder |
      | vorstand   |

  @req-2.7
  Szenario: Eine eigene Art darf nicht standardmässig öffentlich zugänglich sein
    Wenn «inhaber-urinet» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_STANDARD","name":"Probe Standard","vertraulichkeit":"INTERN","gesetzlichePflicht":false,
       "zusatzSchema":{"type":"object","required":["zugriff"],"properties":
       {"zugriff":{"type":"string","enum":["oeffentlich","mitglieder","vorstand"],"default":"oeffentlich"}}}}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 422 und Fehlercode «KONFIGURATION_UNGUELTIG»

  @req-1.3
  Szenariogrundriss: Geänderte Kennungen erscheinen bei der nächsten Änderung über die Verwaltung ohne Werte im Protokoll
    Angenommen der Mandant «<mandant>» hat synthetische Kennungen, die seit der letzten Konfigurationsversion geändert sind
    Und der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Und der Stand des Zugriffsprotokolls von «<anderer>» ist gemerkt
    Wenn «inhaber-<mandant>» eine Dokumentart anlegt:
      """
      {"code":"PROBE_DA_KENNUNG","name":"Probe Kennung","vertraulichkeit":"INTERN","gesetzlichePflicht":false}
      """
    Dann antwortet die Dokumentart-Verwaltung mit Status 201
    Und der neue Konfigurationseintrag von «<mandant>» nennt die Kennungen nur mit Feldnamen und «geändert»
    Und das Zugriffsprotokoll von «<anderer>» hat keinen neuen Eintrag für die Aktion «KONFIGURATION_AENDERN»
    Wenn die Kennungen von «<mandant>» wieder wie vorher sind
    Und der Stand des Zugriffsprotokolls von «<mandant>» ist gemerkt
    Und «inhaber-<mandant>» die Dokumentart «PROBE_DA_KENNUNG» löscht
    Dann antwortet die Dokumentart-Verwaltung mit Status 204
    Und der neue Konfigurationseintrag von «<mandant>» nennt die Kennungen nur mit Feldnamen und «geändert»

    Beispiele:
      | mandant | anderer |
      | urinet  | vhf-gsk |
      | vhf-gsk | urinet  |
