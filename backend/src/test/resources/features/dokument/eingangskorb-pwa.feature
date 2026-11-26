# language: de
# Eingangskorb in der PWA (Aufgabe 3.6), im Handy-Viewport von 375 px. Die API ist eine Attrappe im Browser-Test
# (frontend/e2e/hilfen/archiv-attrappe.mjs); Rechte, Sichtbarkeit, Pflichtfelder und Status prüft das Backend selbst
# (dokument/eingangskorb.feature, dokument/bestaetigen.feature, sicherheit/rechtematrix). Darum Teilnachweis (@attrappe);
# den Platzhalter (Req 8.11) weist dokument/dateiname-pwa.feature zusätzlich gegen das echte Archiv im Testmodus nach.
# Alle Daten sind synthetisch.
@spec-01 @browser @attrappe
Funktionalität: Eingangskorb in der PWA

  Grundlage:
    Angenommen der Eingangskorb enthält:
      | titel             | art                 | datum      | betrag | partner            | unsicher |
      | Rechnung Bürobedarf | LIEFERANTENRECHNUNG | 2026-11-02 | 1234.5 | Papeterie Muster AG | betrag   |
      | Quittung Parkhaus | QUITTUNG            | 2026-11-03 |        |                    |          |

  @req-8.1
  Szenario: Der Eingangskorb zeigt Vorschaubild, Vorschläge und unsichere Felder
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann zeigt der Eingangskorb 2 Einträge
    Und zeigt der Eintrag «Rechnung Bürobedarf» ein Vorschaubild
    Und zeigt der Eintrag «Rechnung Bürobedarf» bei «Art» den Wert «Lieferantenrechnung» ohne «bitte prüfen»
    Und zeigt der Eintrag «Rechnung Bürobedarf» bei «Belegdatum» den Wert «02.11.2026» ohne «bitte prüfen»
    Und zeigt der Eintrag «Rechnung Bürobedarf» bei «Betrag» den Wert «CHF 1'234.50» mit «bitte prüfen»
    Und zeigt der Eintrag «Rechnung Bürobedarf» den erkannten Partner «Papeterie Muster AG»

  @req-8.4
  Szenario: Ein korrekt vorgeschlagenes Dokument ist auf dem Handy mit einem Tipp bestätigt
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann scrollt die Seite nicht horizontal
    Wenn ich beim Eintrag «Rechnung Bürobedarf» auf «Bestätigen» tippe
    Dann hat das Archiv «Rechnung Bürobedarf» bestätigt mit:
      | art    | LIEFERANTENRECHNUNG |
      | datum  | 2026-11-02          |
      | betrag | 1234.5              |
    Und habe ich dafür höchstens 2 Mal getippt
    Und sehe ich die Meldung ««Rechnung Bürobedarf» bestätigt, wird archiviert.»
    Und zeigt der Eingangskorb 1 Eintrag
    Und steht der Fokus auf «Eingangskorb»
    Und zeigt die Navigation den Eingangskorb-Zähler «1»

  @req-8.4
  Szenario: Auch das geöffnete Formular passt ohne horizontales Scrollen auf das Handy
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Rechnung Bürobedarf» auf «Angaben ändern» tippe
    Dann scrollt die Seite nicht horizontal

  @req-8.1
  Szenario: Den Partner wählt der Inhaber über die Suche
    Angenommen das Archiv kennt den Kontakt «Papeterie Muster AG» in «Altdorf»
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Rechnung Bürobedarf» auf «Angaben ändern» tippe
    Und ich beim Eintrag «Rechnung Bürobedarf» den Partner «Papeterie» suche und «Papeterie Muster AG, Altdorf» wähle
    Und ich beim Eintrag «Rechnung Bürobedarf» auf «Bestätigen» tippe
    Dann hat das Archiv «Rechnung Bürobedarf» bestätigt mit:
      | partnerId | k-1 |

  @req-8.3
  Szenario: Fehlt ein Pflichtfeld, markiert das Formular das Feld
    Angenommen das Archiv verweigert das Bestätigen, weil «betrag» fehlt
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Quittung Parkhaus» auf «Bestätigen» tippe
    Dann sehe ich die Meldung «Bitte ergänzen: Betrag.»
    Und ist beim Eintrag «Quittung Parkhaus» das Feld «Betrag» als ungültig markiert
    Und hat das Archiv nichts bestätigt

  @req-8.5
  Szenario: Verwerfen braucht eine Rückfrage und einen Grund
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Quittung Parkhaus» auf «Verwerfen» tippe
    Und ich beim Eintrag «Quittung Parkhaus» auf «Endgültig verwerfen» tippe
    Dann sehe ich die Meldung «Bitte einen Grund angeben.»
    Und hat das Archiv nichts verworfen
    Wenn ich beim Eintrag «Quittung Parkhaus» in «Grund» «Doppelt erfasst» eingebe
    Und ich beim Eintrag «Quittung Parkhaus» auf «Endgültig verwerfen» tippe
    Dann hat das Archiv «Quittung Parkhaus» verworfen mit:
      | grund | Doppelt erfasst |
    Und sehe ich die Meldung ««Quittung Parkhaus» verworfen.»

  @req-8.7
  Szenario: Fehler bei Verarbeitung und Archivierung mit Fehlertext und passender Aktion
    Angenommen der Eingangskorb enthält:
      | titel           | status              | fehler                         |
      | Scan unlesbar   | FEHLER              | Die Datei ist beschädigt.      |
      | Vertrag Telefon | ARCHIVIERUNG_FEHLER | Ablage nicht erreichbar.       |
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann zeigt der Eintrag «Scan unlesbar» den Fehlertext «Die Datei ist beschädigt.»
    Und zeigt der Eintrag «Scan unlesbar» nur die Aktionen «Erneut verarbeiten, Verwerfen»
    Und zeigt der Eintrag «Vertrag Telefon» den Fehlertext «Ablage nicht erreichbar.»
    Und zeigt der Eintrag «Vertrag Telefon» nur die Aktionen «Archivierung wiederholen»
    Wenn ich beim Eintrag «Scan unlesbar» auf «Erneut verarbeiten» tippe
    Dann hat das Archiv «Scan unlesbar» wiederholt
    Und sehe ich die Meldung ««Scan unlesbar» wird erneut verarbeitet.»
    Wenn ich beim Eintrag «Vertrag Telefon» auf «Archivierung wiederholen» tippe
    Dann hat das Archiv «Vertrag Telefon» wiederholt

  @req-8.10
  Szenario: Bei bekannter Belegnummer warnt der Eingangskorb und lässt das Bestätigen zu
    Angenommen der Eingangskorb enthält:
      | titel             | art                 | belegnummer | warnung             |
      | Rechnung Druckerei | LIEFERANTENRECHNUNG | F0003       | BELEGNUMMER_BEKANNT |
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann zeigt der Eintrag «Rechnung Druckerei» die Warnung zur Belegnummer mit Verweis auf «/dokument/vorhanden-1»

  @req-2.6
  Szenario: Der Mitarbeiter sieht seine Dokumente, aber keine Aktionen
    Wenn ich als «mitarbeiter» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann zeigt der Eingangskorb 2 Einträge
    Und zeigt der Eintrag «Quittung Parkhaus» nur die Aktionen «»
    Und zeigt der Eintrag «Quittung Parkhaus» den Hinweis «Für dieses Dokument haben Sie hier keine Aktion.»

  @req-8.4
  Szenario: Der Eingangskorb ist mit der Tastatur bedienbar
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Und ich beim Eintrag «Rechnung Bürobedarf» «Angaben ändern» mit der Tastatur wähle
    Dann steht der Fokus auf «Art»
    Wenn ich beim Eintrag «Rechnung Bürobedarf» «Bestätigen» mit der Tastatur wähle
    Dann hat das Archiv «Rechnung Bürobedarf» bestätigt
    Und steht der Fokus auf «Eingangskorb»

  @req-8.11
  Szenario: Ohne Titel zeigt der Eingangskorb den Dateinamen als Platzhalter und sendet ihn nie als Titel
    Angenommen der Eingangskorb enthält:
      | titel | dateiname                                                      | art      | datum      | status | fehler                    |
      |       | Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg | QUITTUNG | 2026-11-04 |        |                           |
      |       |                                                                |          |            | FEHLER | Die Datei ist beschädigt. |
    Wenn ich als «inhaber» im Mandanten «urinet» den Eingangskorb auf dem Handy öffne
    Dann zeigt der Eintrag «Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg» den Dateinamen als Platzhalter statt eines Titels
    Und zeigt der Eintrag mit dem Fehlertext «Die Datei ist beschädigt.» den Titel «Ohne Titel» ohne Platzhalter
    Und scrollt die Seite nicht horizontal
    Wenn ich beim Eintrag «Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg» auf «Angaben ändern» tippe
    Dann ist beim Eintrag «Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg» das Feld «Titel» leer
    Wenn ich beim Eintrag «Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg» auf «Bestätigen» tippe
    Dann hat das Archiv den Eintrag mit dem Dateinamen «Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg» ohne Titel bestätigt
    Und sehe ich die Meldung ««Ohne Titel · Scan_Quittung_Testgarage_2026-11-04_Seite_01_unbearbeitet.jpg» bestätigt, wird archiviert.» mit isoliertem Dateinamen
