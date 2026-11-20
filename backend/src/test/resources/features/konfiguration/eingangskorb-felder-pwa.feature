# language: de
# Browser-Teilnachweis für das Schema-gesteuerte Erfassen im Eingangskorb. Die API-Attrappe liefert synthetische Daten.
@spec-05 @browser @attrappe
Funktionalität: Felder je Dokumentart im Eingangskorb

  Grundlage:
    Angenommen das Archiv kennt die Dokumentart «Bulletin» mit dem Pflichtfeld «verband» als Mehrfachauswahl «vhf, gsk» mit den Beschriftungen «VHF, GSK»
    Und der Eingangskorb enthält:
      | titel      | art      | datum      |
      | Merkblatt  | QUITTUNG | 2026-11-02 |

  @req-7.5
  Szenario: Beim Artwechsel erscheinen die Zusatzfelder der neuen Dokumentart
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Merkblatt» auf «Angaben ändern» tippe
    Und ich beim Eintrag «Merkblatt» die Art «Bulletin» wähle
    Und ich beim Eintrag «Merkblatt» im Feld «Verband» «VHF» ankreuze
    Dann ist beim Eintrag «Merkblatt» im Feld «Verband» «VHF» angekreuzt
    Wenn ich beim Eintrag «Merkblatt» auf «Bestätigen» tippe
    Dann hat das Archiv für «Merkblatt» die Zusatzangabe «verband» als Liste «vhf» gespeichert und bestätigt
