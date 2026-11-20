# language: de
@spec-04
Funktionalität: Wissensseiten mit sichtbaren gewichteten Treffern suchen

  @req-9.1 @req-9.2 @req-9.3
  Szenariogrundriss: Volltext und Stub-Rückfall beachten dieselbe Sichtbarkeit und Gewichtung
    Angenommen synthetische Wissensleseproben sind angelegt
    Und Titel, Beschreibung und Inhalt enthalten denselben synthetischen Wissenssuchbegriff
    Wenn der Mitarbeiter Wissensseiten im Modus "<modus>" mit höchstens drei Treffern sucht
    Dann liefert die Wissenssuche Titel vor Beschreibung vor Inhalt ohne die interne Seite
    Und meldet die Wissenssuche den Rückfall "<degradiert>"

    Beispiele:
      | modus    | degradiert |
      | volltext | false      |
      | hybrid   | true       |

  @req-9.6 @wissensnachberechnung
  Szenario: Nachberechnung bleibt ein Auftrag mit Schreibrecht
    Wenn der Inhaber die Nachberechnung der Wissensembeddings zweimal startet
    Dann ist die Nachberechnung ein Auftrag und für Mitarbeiter und Treuhänder gesperrt
