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
