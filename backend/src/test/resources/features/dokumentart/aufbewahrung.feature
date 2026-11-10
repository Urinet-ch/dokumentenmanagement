# language: de
@spec-01
Funktionalität: Aufbewahrungsfrist der Dokumente
  Die Frist berechnet die Domain-Regel «Aufbewahrung» (Aufgabe 2.1). Die Szenarien rufen sie direkt auf; das
  Bestätigen, das Kennzeichnen im Eingangskorb, das Protokollieren des Konflikts und die Sperre folgen mit den
  Aufgaben zu Dokument, Eingangskorb und Ablage. Deshalb sind alle Szenarien hier Teilnachweise.

  @req-9.3
  Szenariogrundriss: Die Frist ergibt sich aus Geschäftsjahr bzw. gültig bis und den Jahren der Regel (Teilnachweis)
    Wenn die Frist für die Regel «<regel>» mit Belegdatum «<beleg>», Eingang «<eingang>», Geschäftsjahr «<gj>», gültig bis «<gueltig>» und Geschäftsjahresende «<ende>» am «01.01.2027» berechnet wird
    Dann ist aufbewahren_bis «<bis>» und die Frist offen

    Beispiele:
      | regel                     | beleg      | eingang              | gj   | gueltig    | ende  | bis         |
      | 10 Jahre ab GESCHAEFTSJAHR | 15.03.2026 | –                    | –    | –          | 12-31 | 31.12.2036  |
      | 10 Jahre ab GESCHAEFTSJAHR | 15.08.2026 | –                    | –    | –          | 06-30 | 30.06.2037  |
      | 10 Jahre ab GESCHAEFTSJAHR | –          | 2026-12-31T23:30:00Z | –    | –          | 12-31 | 31.12.2037  |
      | 10 Jahre ab GESCHAEFTSJAHR | 15.03.2026 | –                    | 2025 | –          | 12-31 | 31.12.2035  |
      | 10 Jahre ab GUELTIG_BIS   | 01.02.2026 | –                    | –    | 31.01.2028 | 12-31 | 31.01.2038  |
      | 10 Jahre ab GUELTIG_BIS   | 01.02.2026 | –                    | –    | –          | 12-31 | unbefristet |
      | unbefristet               | 01.02.2026 | –                    | –    | –          | 12-31 | unbefristet |

  @req-9.7
  Szenariogrundriss: Eine Neuberechnung verlängert nur, ein früheres Datum ist ein Konflikt (Teilnachweis)
    Wenn eine bestehende Frist «<bisher>» neu als «<neu>» berechnet wird
    Dann lautet der Entscheid «<entscheid>»

    Beispiele:
      | bisher      | neu         | entscheid   |
      | 31.12.2036  | 31.12.2036  | GLEICH      |
      | 31.12.2036  | 31.12.2037  | VERLAENGERN |
      | 31.12.2036  | unbefristet | VERLAENGERN |
      | 31.12.2036  | 31.12.2035  | KONFLIKT    |
      | unbefristet | 31.12.2036  | KONFLIKT    |

  @req-9.8
  Szenario: Eine Frist in der Vergangenheit wird als abgelaufen erkannt (Teilnachweis)
    Wenn die Frist für die Regel «10 Jahre ab GESCHAEFTSJAHR» mit Belegdatum «15.03.2010», Eingang «–», Geschäftsjahr «–», gültig bis «–» und Geschäftsjahresende «12-31» am «10.11.2026» berechnet wird
    Dann ist aufbewahren_bis «31.12.2020» und die Frist abgelaufen

  # Teilnachweis: Dokumente und Dateien gibt es erst ab Aufgabe 2.2. Belegt ist hier nur, dass die Frist-Regel nichts
  # löscht und die API ausser den freigegebenen Pfaden keinen DELETE-Endpunkt hat; ein neuer DELETE-Pfad macht das
  # Szenario rot, bis er hier ausdrücklich zugelassen ist. Den vollen Nachweis (abgelaufenes Dokument bleibt samt Datei
  # erhalten) bringen die Aufgaben zu Dokument und Ablage.
  @req-9.9
  Szenario: Ein Fristablauf löscht nichts (Teilnachweis bis Dokument und Ablage)
    Wenn die Frist für die Regel «10 Jahre ab GESCHAEFTSJAHR» mit Belegdatum «15.03.2010», Eingang «–», Geschäftsjahr «–», gültig bis «–» und Geschäftsjahresende «12-31» am «10.11.2026» berechnet wird
    Dann ist aufbewahren_bis «31.12.2020» und die Frist abgelaufen
    Und bietet die API DELETE nur für diese Pfade an:
      | /api/v1/api-tokens/{tokenId} |
      | /api/v1/dokumentarten/{code} |
