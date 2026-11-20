# language: de
@spec-04 @wissen-import
Funktionalität: Wissenspakete synchron und ohne KI als Entwurf importieren

  Grundlage:
    Angenommen synthetische Wissensquellen in zwei Mandanten sind für den Paketimport vorhanden

  @req-19.1 @req-19.11 @req-19.19 @req-19.20 @req-19.22
  Szenario: Wissenspaket erst importieren und danach ausdrücklich freigeben
    Wenn der Inhaber ein gültiges lokales Wissenspaket importiert
    Dann liegt ausschliesslich ein offener Importentwurf mit SHA-Protokoll vor
    Wenn der Inhaber die importierte Änderung mit ihrem ETag annimmt
    Dann ist eine belegte Seite mit externer Provenienz und Import-Abschlusslog freigegeben

  @req-19.9 @req-19.10 @req-19.17
  Szenario: Wissenspaket gegen die aktuellen Seiten abgleichen
    Dann unterscheidet der Import neue geänderte und unveränderte Seiten ohne Seitenbereichsfehler

  @req-19.7 @req-19.9 @req-19.18
  Szenariogrundriss: Neue Wissensseiten lassen keine fremde Basisversion zu
    Dann eine neue Seite mit Basisversion <Version> verwirft das gesamte Wissenspaket
    Beispiele:
      | Version |
      | -1      |
      | 1       |

  @req-19.9
  Szenario: Neue Wissensseite mit Basisversion 0 bleibt zulässig
    Wenn der Inhaber eine neue Wissensseite mit Basisversion 0 importiert
    Dann liegt ausschliesslich ein offener Importentwurf mit SHA-Protokoll vor

  @req-19.12 @req-19.13 @req-19.14 @req-19.15
  Szenariogrundriss: Wissenspaket mit unzulässiger oder unbelegter Quelle
    Wenn das Wissenspaket die Quellenabweichung "<Fall>" enthält
    Dann blockiert der Befund "<Code>" die Freigabe des Importentwurfs
    Beispiele:
      | Fall              | Code                    |
      | unbekannt         | QUELLE_UNBEKANNT        |
      | fremd             | QUELLE_UNBEKANNT        |
      | PERSONAL          | QUELLE_PERSONAL         |
      | falsche Art       | QUELLE_UNBEKANNT        |
      | Auszug fehlt      | AUSZUG_FEHLT            |
      | Auszug falsch     | AUSZUG_NICHT_GEFUNDEN   |
      | Seitentext fehlt  | AUSZUG_NICHT_PRUEFBAR    |

  @req-19.16 @req-19.26
  Szenario: Wissenspaket aus lokalem und Cloud-Kontext mit internen Quellen
    Dann erzwingt der Import die Quellenstufe und verwirft unzulässige Cloud-Kontexte

  @req-19.14
  Szenario: Wissenspaket nach einer späteren Quellenaufnahme erneut prüfen
    Wenn das Wissenspaket die Quellenabweichung "unbekannt" enthält
    Dann löst eine spätere Quellenaufnahme die Neuprüfung aus und entwertet alte ETags

  @req-19.17
  Szenario: Wissenspaket wiederholen und die offene Importquote einhalten
    Dann werden gleiche Pakete auch gleichzeitig nur einmal angelegt und die Importquote bleibt getrennt

  @req-19.14
  Szenario: Wissenspaketquelle ändert sich vor dem Annehmen
    Dann wird eine inzwischen geänderte Quelle beim Annehmen erneut geprüft

  @req-19.5
  Szenario: Wissenspaketimport ist A17 und OIDC vorbehalten
    Dann rollenfremde Benutzer und API-Tokens können kein Wissenspaket importieren

  @req-19.18
  Szenario: Wissenspaketfehler nach dem ersten Speicherschritt
    Dann ein Fehler nach dem ersten Speicherschritt rollt den gesamten Wissensimport zurück

  @req-19.18
  Szenario: Wissenspaket überschreitet den Zeitrahmen
    Dann ein überschrittener Zeitrahmen beendet den Wissensimport ohne Entwurf

  @req-19.18
  Szenario: Wissenspaketspeicherung bleibt trotz interner SQL-Zeitlimitwechsel begrenzt
    Dann eine blockierte Speicherung überschreitet auch nach SQL-Zeitlimitwechseln nicht den Importrahmen

  @req-19.6 @req-19.7 @req-19.20
  Szenario: Wissenspaketeingang prüft Multipart und die exakte ZIP-Grenze ohne Inhaltslogs
    Dann Multipart und ZIP-Grenze werden ohne Paketinhalt in Antwort oder Log geprüft

  @req-19.23 @req-17.2
  Szenario: Lokaler OKF-Export durchläuft denselben Paketleser und Import mit SHA-Quellen
    Dann ein OKF-Export mit lokaler Herkunft ist ohne ID-Zuordnung als Wissenspaket importierbar

  @req-19.2 @req-19.3 @req-19.4 @req-19.6 @req-19.7 @req-19.8 @req-19.21
  Szenario: Wissenspaketangriffe und Markdown werden durch gemeinsame Regeln geprüft
    Dann der Paketleser und der REST-Import beurteilen die gemeinsamen Beispielpakete gleich
