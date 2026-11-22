# language: de
@spec-04
Funktionalität: Wissensquelle als Markierung

  @req-1.1 @req-1.3
  Szenario: Ein neuer Mandant erhält keine Wissensquellenart und das Manifest enthält markierte Dateien
    Angenommen der Mandant «mf-wissensquelle» ist am «10.01.2026» angelegt
    Dann hat der Mandant «mf-wissensquelle» keine Dokumentart WISSENSQUELLE
    Angenommen im Mandanten «mf-wissensquelle» wurde die Wissensquelle «WQ» am «10.01.2026» um «09:15» archiviert
    Wenn der Manifest-Lauf für «mf-wissensquelle» bis «10.01.2026» läuft
    Dann das Manifest «10.01.2026» von «mf-wissensquelle» enthält das Dokument «WQ» mit den Varianten «original,arbeitsfassung» samt SHA-256 und Versions-ID

  @req-1.1
  Szenario: Auch der Verbandsmandant hat keine eigene Wissensquellenart
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Dann hat der Mandant «vhf-gsk» keine Dokumentart WISSENSQUELLE

  @req-1.2 @req-1.5
  Szenario: Der Inhaber archiviert eine Fachunterlage nach ihrer eigentlichen Art
    Angenommen die Archivierung ist bereit
    Und im Mandanten «urinet» liegt das Dokument «WQ_HAND» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «WQ_HAND» so bestätigt:
      | art   | KORRESPONDENZ       |
      | titel | Probe Wissensquelle |
      | datum | 2026-03-10          |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «WQ_HAND» diese Werte:
      | art             | KORRESPONDENZ       |
      | titel           | Probe Wissensquelle |
      | aufbewahren_bis | null                |
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «WQ_HAND» im Status «ARCHIVIERT»
    Und liegen die Dateien von «WQ_HAND» unter archiv/ ohne Sperre
    Und ist der Eingang von «WQ_HAND» leer

  @req-1.6 @req-20.6
  Szenario: Eine markierte Quittung ist Teil des Jahresexports
    Angenommen im Mandanten «urinet» gibt es keinen offenen Export
    Und im Mandanten «urinet» liegen diese Belege:
      | name      | art           | datum      | jahr | status     | partner           | belegnummer | richtung | betrag | vertraulichkeit | pdfa_fehlt |
      | WQ_EXPORT | QUITTUNG      | 2025-03-14 | 2025 | ARCHIVIERT |                   |             |          |        | NORMAL          | nein       |
      | B_NORMAL  | QUITTUNG      | 2025-04-02 | 2025 | ARCHIVIERT | Bäckerei Beispiel |             | AUSGABE  | 12.00  | NORMAL          | nein       |
    Und der Beleg «WQ_EXPORT» ist als Wissensquelle markiert
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Dann ist der Export «FERTIG» mit 2 Belegen
    Und das ZIP enthält genau diese Dateien:
      | datei                                                    |
      | belege/2025-03-14_QUITTUNG_{WQ_EXPORT}_ohne-partner.pdf  |
      | belege/2025-04-02_QUITTUNG_{B_NORMAL}_Baeckerei-Beispiel.pdf |
      | belegliste.csv                                           |
      | pruefsummen.sha256                                       |
