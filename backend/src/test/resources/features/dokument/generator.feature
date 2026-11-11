# language: de
@spec-01
Funktionalität: Generator-Schnittstelle für Skripte
  Ein Skript (rechnung_gen.py, urinet_pdf.py) legt über «POST /api/v1/dokumente/generator» eine PDF mit fertigen
  Metadaten ab, angemeldet mit einem API-Token vom Scope generator. Das Archiv prüft synchron, antwortet 202 und
  verarbeitet und archiviert ohne KI-Vorschlag (Aufgabe 4.7, design.md «Generator-Pfad»). Nur synthetische
  Testdaten (Req 16.15).

  Hintergrund:
    Angenommen der Generator ist bereit
    Und der Generator von «urinet» hat ein API-Token
    Und im Mandanten «urinet» gibt es den CRM-Kontakt «Probe AG» mit der crmId «K-PROBE-4711»

  @req-11.1 @req-11.2
  Szenario: Eine Ausgangsrechnung des Generators wird ohne KI archiviert
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnung", "datum": "2026-03-10",
        "partner": {"crmId": "K-PROBE-4711", "name": "Probe AG"},
        "finanz": {"belegnummer": "2026-901", "richtung": "EINNAHME", "betrag": "408.00", "waehrung": "CHF"},
        "tags": ["Probe"] }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1»
    Und hat das Generator-Dokument «R1» diese Werte:
      | status          | NEU              |
      | quelle          | GENERATOR        |
      | art             | AUSGANGSRECHNUNG |
      | vertraulichkeit | INTERN           |
      | partner_name    | Probe AG         |
      | geschaeftsjahr  | 2026             |
      | aufbewahren_bis | 2036-12-31       |
      | erstellt_von    | TOKEN            |
      | bestaetigt_von  | TOKEN            |
    Und ist für das Generator-Dokument «R1» ein Auftrag «VERARBEITEN» ohne KI eingereiht
    Wenn der Worker die Generator-Aufträge abarbeitet
    Dann ist das Generator-Dokument «R1» archiviert, ohne KI-Vorschlag

  @req-11.1
  Szenario: Ein angemeldeter Benutzer erreicht den Generator nicht
    Wenn «inhaber-urinet» die PDF «B1» mit diesen Metadaten an den Generator sendet:
      """
      { "art": "PROJEKTDOKUMENT", "titel": "Probe", "projekt": "Probe" }
      """
    Dann antwortet der Generator mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «B1»

  @req-11.3
  Szenario: Eine eigene Belegnummer mit anderer PDF ergibt 409 und überschreibt nichts
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnung", "datum": "2026-03-10",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-902", "richtung": "EINNAHME", "betrag": "100.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1»
    Wenn der Generator die PDF «R2» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Andere Probe-Rechnung", "datum": "2026-03-11",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-902", "richtung": "EINNAHME", "betrag": "200.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit Status 409 und Fehlercode «BELEGNUMMER_VERGEBEN»
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «R2»
    Und hat das Generator-Dokument «R1» diese Werte:
      | titel | Probe-Rechnung |

  @req-11.8
  Szenario: Dieselbe PDF erneut gesendet ergibt 200 mit der vorhandenen ID
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "PROJEKTDOKUMENT", "titel": "Probe", "projekt": "Probe" }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1»
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "PROJEKTDOKUMENT", "titel": "Probe, zweiter Versuch", "projekt": "Probe" }
      """
    Dann antwortet der Generator mit 200 und dem vorhandenen Dokument «R1» im Status «NEU»
    Und gibt es im Mandanten «urinet» genau ein Dokument zur PDF «R1»
    Und hat das Generator-Dokument «R1» diese Werte:
      | titel | Probe |

  @req-11.6
  Szenariogrundriss: Fehlende Pflichtfelder ergeben 422 mit Liste, nichts wird angelegt
    Wenn der Generator die PDF «P1» mit diesen Metadaten sendet:
      """
      <metadaten>
      """
    Dann antwortet der Generator mit Status 422 und Fehlercode «PFLICHTFELD_FEHLT»
    Und nennt die Generator-Antwort die fehlenden Felder "<felder>"
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «P1»

    Beispiele:
      | metadaten                                                             | felder                           |
      | { "titel": "Ohne Art" }                                               | art                              |
      | { "art": "PROJEKTDOKUMENT", "titel": "Probe" }                        | projekt                          |
      | { "art": "AUSGANGSRECHNUNG", "titel": "Probe", "datum": "2026-03-10" } | partner, belegnummer, richtung, betrag, waehrung |

  @req-11.7
  Szenario: Eine unbekannte crmId ergibt 422, nichts wird angelegt
    Wenn der Generator die PDF «P1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnung", "datum": "2026-03-10",
        "partner": {"crmId": "K-UNBEKANNT-0000"},
        "finanz": {"belegnummer": "2026-903", "richtung": "EINNAHME", "betrag": "50.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit Status 422 und Fehlercode «PARTNER_UNBEKANNT»
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «P1»

  @req-11.10
  Szenario: Bei Standard PERSONAL sind Vertraulichkeit und Person Pflicht, sonst gilt der Standard der Art
    Wenn der Generator die PDF «L1» mit diesen Metadaten sendet:
      """
      { "art": "LOHNABRECHNUNG", "titel": "Probe-Lohnabrechnung", "datum": "2026-03-25",
        "finanz": {"richtung": "AUSGABE", "betrag": "1000.00", "waehrung": "CHF"},
        "zusatz": {"periode": "2026-03", "person": "Probe Person"} }
      """
    Dann antwortet der Generator mit Status 422 und Fehlercode «PFLICHTFELD_FEHLT»
    Und nennt die Generator-Antwort die fehlenden Felder "vertraulichkeit, person_benutzer"
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «L1»
    Wenn der Generator die PDF «L1» mit diesen Metadaten sendet:
      """
      { "art": "LOHNABRECHNUNG", "titel": "Probe-Lohnabrechnung", "datum": "2026-03-25",
        "vertraulichkeit": "PERSONAL", "personBenutzer": "6f1d1c52-0000-4000-8000-000000004711",
        "finanz": {"richtung": "AUSGABE", "betrag": "1000.00", "waehrung": "CHF"},
        "zusatz": {"periode": "2026-03", "person": "Probe Person"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «L1»
    Und hat das Generator-Dokument «L1» diese Werte:
      | vertraulichkeit | PERSONAL                             |
      | person_benutzer | 6f1d1c52-0000-4000-8000-000000004711 |
    Wenn der Generator die PDF «D1» mit diesen Metadaten sendet:
      """
      { "art": "PROJEKTDOKUMENT", "titel": "Probe", "projekt": "Probe" }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «D1»
    Und hat das Generator-Dokument «D1» diese Werte:
      | vertraulichkeit | NORMAL |
      | person_benutzer | null   |

  @req-11.11
  Szenario: Nach dem Verwerfen wird dieselbe Belegnummer wieder angenommen
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnung", "datum": "2026-03-10",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-904", "richtung": "EINNAHME", "betrag": "100.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1»
    Angenommen die Verarbeitung des Generator-Dokuments «R1» ist gescheitert
    Wenn «inhaber-urinet» das Generator-Dokument «R1» verwirft
    Dann ist das Generator-Dokument «R1» im Status «VERWORFEN»
    Wenn der Generator die PDF «R1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Probe-Rechnung korrigiert", "datum": "2026-03-10",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2026-904", "richtung": "EINNAHME", "betrag": "110.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit 202 und dem neuen Dokument «R1-neu»

  @req-11.1
  Szenariogrundriss: Ungültige Metadaten ergeben 400, nichts wird angelegt
    Wenn der Generator die PDF «U1» mit diesen Metadaten sendet:
      """
      <metadaten>
      """
    Dann antwortet der Generator mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «U1»

    Beispiele:
      | metadaten                                                                  | fall                |
      | { "art": "PROJEKTDOKUMENT", "titel": "Probe"                               | kein JSON           |
      | { "art": "PROJEKTDOKUMENT", "titel": "Probe", "projekt": "P", "tittel": "x" } | unbekanntes Feld    |
      | { "art": "GIBT_ES_NICHT", "titel": "Probe" }                               | unbekannte Art      |
      | { "art": "PROJEKTDOKUMENT", "titel": "Probe", "projekt": "P", "zusatz": [] } | zusatz kein Objekt  |

  @req-11.2
  Szenario: Eine abgelaufene Aufbewahrungsfrist ergibt 409, nichts wird angelegt
    Wenn der Generator die PDF «A1» mit diesen Metadaten sendet:
      """
      { "art": "AUSGANGSRECHNUNG", "titel": "Alte Probe-Rechnung", "datum": "2010-05-01",
        "partner": {"crmId": "K-PROBE-4711"},
        "finanz": {"belegnummer": "2010-901", "richtung": "EINNAHME", "betrag": "10.00", "waehrung": "CHF"} }
      """
    Dann antwortet der Generator mit Status 409 und Fehlercode «FRIST_ABGELAUFEN»
    Und gibt es im Mandanten «urinet» kein Dokument zur PDF «A1»
