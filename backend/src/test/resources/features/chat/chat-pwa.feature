# language: de
# Seite «Chat» der PWA (Spec 02, Aufgabe 7.4) gegen eine Attrappe der Chat-Schnittstelle
# (frontend/e2e/hilfen/chat-attrappe.mjs): Das Backend (7.2, 7.3) entsteht parallel. Die Attrappe folgt dem Vertrag aus
# design.md «Vertrag Welle 7» und liefert den Strom Stück für Stück, absichtlich mitten in Ereignissen zerteilt. Die
# Szenarien belegen nur die Oberfläche; Rechte, Sichtbarkeit, Protokoll (Req 4.3) und Rate-Limit belegt das Backend.
# Den Menüpunkt schaltet Aufgabe 7.4b ein, darum öffnen die Szenarien die Seite über die Adresse. Alle Daten sind
# synthetisch.
@spec-02 @browser @attrappe
Funktionalität: Chat in der PWA

  Grundlage:
    Angenommen die Chat-Schnittstelle ist eine Attrappe

  @req-6.1 @req-6.2 @req-6.3
  Szenariogrundriss: Frage stellen, Antwort fortlaufend mit Hinweis und Quellen (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und Keycloak kennt mich als «<rolle>» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/chat»
    Und sehe ich unter den letzten Gesprächen «Fristen im Dezember» vom «02.11.2026»
    Wenn ich die Frage «Welche Rechnungen sind offen?» sende
    Dann sehe ich den Hinweis «prüfe offene Rechnungen …»
    Und ist das Eingabefeld leer
    Wenn ich «Noch etwas» in das Eingabefeld schreibe
    Dann ist der Knopf «Senden» gesperrt
    Wenn das Archiv weiterantwortet
    Dann sehe ich den Anfang der Antwort «Offen sind zwei Rechnungen», während sie noch läuft
    Und ist der Hinweis verschwunden
    Wenn das Archiv weiterantwortet
    Dann sehe ich die Antwort:
      """
      Offen sind zwei Rechnungen:
      - 2026-003 Testverein Seedorf, CHF 1'234.50
      - 2026-007 Testverein Bauen, CHF 980.00
      """
    Und sehe ich die Quellen «Rechnung Vereinsfest Seedorf | Datenstand: 14.11.2026 09:00, 2 Belege»
    Und ist die Quelle «Rechnung Vereinsfest Seedorf» ein Link
    Und ist die Quelle «Datenstand» nicht klickbar
    Und sehe ich unter den letzten Gesprächen zuoberst «Welche Rechnungen sind offen?»
    Und ragt nichts über den Bildschirm hinaus
    Wenn ich die Frage «Und die ältere?» sende
    Dann sehe ich die Antwort «Antwort auf «Und die ältere?».»
    Und hat die PWA die Frage im selben Gespräch gestellt

    Beispiele:
      | breite | rolle            |
      | 375    | mitarbeiter      |
      | 1280   | inhaber          |

  @req-6.1
  Szenariogrundriss: Gespräch aus der Liste öffnen, fortsetzen und ein neues beginnen (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und Keycloak kennt mich als «<rolle>» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich das Gespräch «Fristen im Dezember» öffne
    Dann sehe ich die Antwort «Offerte Testdruckerei AG, gültig bis 31.12.2026»
    Und sehe ich die Quellen «Rechnung Druckerei | Datenstand: 02.11.2026 10:14, 1 Beleg»
    Wenn ich die Frage «Und im Januar?» sende
    Dann sehe ich die Antwort «Antwort auf «Und im Januar?».»
    Und hat die PWA die Frage im Gespräch «Fristen im Dezember» gestellt
    Und ragt nichts über den Bildschirm hinaus
    Wenn ich auf «Neues Gespräch» klicke
    Und ich die Frage «Etwas Neues» sende
    Dann sehe ich die Antwort «Antwort auf «Etwas Neues».»
    Und hat die PWA die Frage ohne Gespräch gestellt

    Beispiele:
      | breite | rolle            |
      | 375    | treuhaender      |
      | 1280   | geschaeftsstelle |

  @req-6.3
  Szenario: Lange Antwort mit vielen Quellen bleibt bei 375 px ohne horizontales Scrollen
    Angenommen ein Bildschirm mit 375 Pixel Breite
    Und Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Zeig mir alles» sende
    Dann sehe ich 13 Quellen
    Und ragt nichts über den Bildschirm hinaus

  Szenario: Eine Quelle öffnet das Dokument, Links ausserhalb von /dokument/ sind nicht klickbar
    Angenommen Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Wo ist die Rechnung aus Seedorf?» sende
    Dann sehe ich die Quellen «Rechnung Vereinsfest Seedorf | Fremde Seite | Protokoll»
    Und ist die Quelle «Fremde Seite» nicht klickbar
    Und ist die Quelle «Protokoll» nicht klickbar
    Wenn ich auf die Quelle «Rechnung Vereinsfest Seedorf» tippe
    Dann bin ich auf der Seite des Dokuments «Rechnung Vereinsfest Seedorf»

  Szenario: Mehr als 100 Fragen in der Stunde
    Angenommen Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Frage 101» sende
    Dann sehe ich die Meldung, dass es in 20 Minuten weitergeht
    Und steht «Frage 101» wieder im Eingabefeld

  Szenario: Es läuft schon eine andere Frage
    Angenommen Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Noch eine Frage» sende
    Dann sehe ich die Meldung «Es läuft noch eine andere Frage. Bitte warten Sie, bis sie beantwortet ist, und senden Sie dann erneut.»

  Szenario: Fehler während der Antwort
    Angenommen Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Gibt es Fristen?» sende
    Dann sehe ich die Meldung «Der Chat ist im Moment nicht verfügbar. Die Suche funktioniert weiterhin.»
    Wenn ich die Frage «Nochmals» sende
    Dann sehe ich die Antwort «Antwort auf «Nochmals».»
    Und hat die PWA die Frage im Gespräch «Gibt es Fristen?» gestellt

  Szenario: Eine laufende Antwort abbrechen
    Angenommen Keycloak kennt mich als «inhaber» im Mandanten «urinet»
    Wenn ich die Seite «/chat» öffne
    Und ich mich anmelde
    Und ich die Frage «Lange Frage» sende
    Dann sehe ich den Anfang der Antwort «Ich suche noch», während sie noch läuft
    Wenn ich auf «Abbrechen» klicke
    Dann hat die PWA die Anfrage abgebrochen
