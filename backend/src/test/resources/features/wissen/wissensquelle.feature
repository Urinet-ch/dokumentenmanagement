# language: de
@spec-04
Funktionalität: Dokumentart Wissensquelle

  @req-1.1 @req-1.3
  Szenario: Der neue Mandant erhält die Wissensquelle im Seed und das Manifest enthält ihre Dateien
    Angenommen der Mandant «mf-wissensquelle» ist am «10.01.2026» angelegt
    Dann hat der Mandant «mf-wissensquelle» die Dokumentart WISSENSQUELLE mit den gemeinsamen Vorgaben
    Angenommen im Mandanten «mf-wissensquelle» wurde die Wissensquelle «WQ» am «10.01.2026» um «09:15» archiviert
    Dann hat der Mandant «mf-wissensquelle» die Wissensquelle mit dem Titel «Probe Wissensquelle» und Standdatum «10.01.2026» unverändert archiviert
    Wenn der Manifest-Lauf für «mf-wissensquelle» bis «10.01.2026» läuft
    Dann das Manifest «10.01.2026» von «mf-wissensquelle» enthält das Dokument «WQ» mit den Varianten «original,arbeitsfassung» samt SHA-256 und Versions-ID

  @req-1.2 @req-1.4 @req-1.5
  Szenario: Der Inhaber wählt die Wissensquelle von Hand und archiviert sie ohne Sperre
    Angenommen die Archivierung ist bereit
    Und im Mandanten «urinet» liegt das Dokument «WQ_HAND» im Status «EINGANGSKORB», erfasst von «inhaber-urinet»
    Wenn «inhaber-urinet» das Dokument «WQ_HAND» so bestätigt:
      | art   | WISSENSQUELLE       |
      | titel | Probe Wissensquelle |
      | datum | 2026-03-10          |
    Dann meldet das Archiv Status 202
    Und hat das Dokument «WQ_HAND» diese Werte:
      | art             | WISSENSQUELLE       |
      | titel           | Probe Wissensquelle |
      | aufbewahren_bis | null                |
    Wenn der Worker die Archivierung ausführt
    Dann ist das Dokument «WQ_HAND» im Status «ARCHIVIERT»
    Und liegen die Dateien von «WQ_HAND» unter archiv/ ohne Sperre
    Und ist der Eingang von «WQ_HAND» leer

  @req-1.6
  Szenario: Wissensquellen sind nicht Teil des Jahresexports
    Angenommen im Mandanten «urinet» gibt es keinen offenen Export
    Und im Mandanten «urinet» liegen diese Belege:
      | name     | art           | datum      | jahr | status     | partner | belegnummer | richtung | betrag | vertraulichkeit | pdfa_fehlt |
      | WQ_EXPORT | WISSENSQUELLE | 2025-03-14 | 2025 | ARCHIVIERT |         |             | AUSGABE  | 0.00   | NORMAL          | nein       |
    Wenn «inhaber-urinet» den Jahresexport 2025 anfordert
    Und der Worker die Aufträge abarbeitet
    Dann ist der Export «FERTIG» mit 0 Belegen
    Und das ZIP enthält genau diese Dateien:
      | datei             |
      | belegliste.csv    |
      | pruefsummen.sha256 |
