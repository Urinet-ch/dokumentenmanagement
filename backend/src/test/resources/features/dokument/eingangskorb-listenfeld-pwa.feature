# language: de
# Eingangskorb in der PWA: Zusatzfelder vom Typ Liste (JSON-Schema `array`), z. B. das Pflichtfeld `verband` der Arten
# von vhf-gsk (Seed vhf-gsk/dokumentarten.json). Die Geschäftsstelle muss es erfassen können, sonst bleibt das Bestätigen
# unmöglich. Die API ist eine Attrappe im Browser-Test (frontend/e2e/hilfen/archiv-attrappe.mjs); das Prüfen der Werte
# im Archiv belegen die Java-Szenarien (dokument/bestaetigen.feature, mandant/geschaeftsstelle.feature). Darum
# Teilnachweis (@attrappe). Alle Daten sind synthetisch. Die Kontrollkästchen zeigen die Anzeigetexte aus `x-labels`
# («VHF», «GSK»); gespeichert und gesendet wird der Rohwert («vhf», «gsk»).
@spec-03 @browser @attrappe
Funktionalität: Eingangskorb in der PWA: Listenfelder der Dokumentart

  Grundlage:
    Angenommen das Archiv kennt die Dokumentart «Bulletin» mit dem Pflichtfeld «verband» als Mehrfachauswahl «vhf, gsk» mit den Beschriftungen «VHF, GSK»
    Und der Eingangskorb enthält:
      | titel | art | datum |
      | Bulletin 12 | BULLETIN | 2026-11-02 |

  @req-3.1
  Szenario: Die Geschäftsstelle wählt den Verband mit der Tastatur und bestätigt
    Wenn ich als «geschaeftsstelle» im Mandanten «vhf-gsk» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Bulletin 12» auf «Angaben ändern» tippe
    Und ich beim Eintrag «Bulletin 12» im Feld «Verband» «VHF» mit der Tastatur ankreuze
    Und ich beim Eintrag «Bulletin 12» im Feld «Verband» «GSK» mit der Tastatur ankreuze
    Dann ist beim Eintrag «Bulletin 12» im Feld «Verband» «VHF» angekreuzt
    Wenn ich beim Eintrag «Bulletin 12» auf «Bestätigen» tippe
    Dann hat das Archiv für «Bulletin 12» die Zusatzangabe «verband» als Liste «vhf, gsk» gespeichert und bestätigt
    Und sehe ich die Meldung ««Bulletin 12» bestätigt, wird archiviert.»

  @req-3.1
  Szenario: Ohne Auswahl meldet das Formular das Pflichtfeld und bestätigt nichts
    Wenn ich als «geschaeftsstelle» im Mandanten «vhf-gsk» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Bulletin 12» auf «Angaben ändern» tippe
    Und ich beim Eintrag «Bulletin 12» auf «Bestätigen» tippe
    Dann sehe ich die Meldung «Bitte ergänzen: Verband.»
    Und ist beim Eintrag «Bulletin 12» die Auswahl «Verband» als ungültig markiert
    Und hat das Archiv nichts bestätigt
