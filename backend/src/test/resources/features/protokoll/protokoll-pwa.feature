# language: de
# Zeitstempel, Sortierung und verzögerte Antworten müssen reproduzierbar sein; darum verwenden alle Szenarien eine
# Attrappe, die festhält, was die PWA sendet. Die echten Serverregeln belegen die Java-Szenarien in protokoll-einsicht.feature.
# Die Attrappe liefert auch synthetische Zusatzfelder für die Prüfung, dass die PWA keine Dateinamen oder Inhalte zeigt.
@spec-01 @browser @attrappe
Funktionalität: Zugriffsprotokoll in der PWA

  @req-10.5 @req-16.7
  Szenariogrundriss: Der Inhaber sieht neueste Einträge mit den erlaubten Angaben (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und die Protokoll-Schnittstelle ist eine Attrappe
    Und das Zugriffsprotokoll enthält zwei synthetische Einträge mit Zusatzfeldern
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Zugriffsprotokoll» wähle
    Dann sehe ich die Protokolleinträge neueste zuerst mit Zeit, Benutzer, Aktion und Dokument-ID
    Und sehe ich weder Dateinamen noch Dokumentinhalte im Protokoll
    Und ragt nichts über den Bildschirm hinaus

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-10.5
  Szenario: Filter werden erst nach «Anzeigen» gesendet und schliessen den «Bis»-Tag ein
    Angenommen die Protokoll-Schnittstelle ist eine Attrappe
    Und das Zugriffsprotokoll enthält einen Eintrag am Ende des «Bis»-Tages und einen am Folgetag
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Zugriffsprotokoll» wähle
    Wenn ich die vorbereiteten Protokollfilter eintrage
    Dann hat die PWA noch keinen Filter gesendet
    Wenn ich auf «Anzeigen» klicke
    Dann hat die PWA den Protokollfilter in Europe/Zurich gesendet
    Und ist der Eintrag am «Bis»-Tag sichtbar, aber der am Folgetag nicht
    Wenn ich die Protokollfilter zurücksetze
    Dann sind die Protokollfilter leer und die PWA hat sie zurückgesetzt

  @req-10.5
  Szenario: Blättern verwendet den übernommenen Filter und zeigt die Gesamtzahl
    Angenommen die Protokoll-Schnittstelle ist eine Attrappe
    Und das Zugriffsprotokoll enthält 101 Einträge für eine synthetische Kennung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Zugriffsprotokoll» wähle
    Wenn ich die vorbereitete Protokoll-Kennung und Aktion filtere
    Dann sehe ich «1–50 von 101»
    Wenn ich den nicht übernommenen Protokollfilter ändere
    Und ich auf «Weiter» klicke
    Dann sehe ich «51–100 von 101»
    Wenn ich auf «Weiter» klicke
    Dann sehe ich «101–101 von 101»
    Wenn ich auf «Zurück» klicke
    Dann sehe ich «51–100 von 101»
    Und hat die PWA beim Blättern nur den übernommenen Protokollfilter gesendet

  @req-10.5
  Szenario: Eine neue Protokollabfrage wird nicht von einer verspäteten Antwort überschrieben
    Angenommen die Protokoll-Schnittstelle ist eine Attrappe
    Und die erste Protokollantwort ist absichtlich verzögert
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Zugriffsprotokoll» wähle
    Wenn ich die neue Protokollabfrage starte
    Dann sehe ich die Antwort der neuen Abfrage
    Wenn die verzögerte erste Protokollantwort eintrifft
    Dann bleibt die Antwort der neuen Abfrage sichtbar

  @req-10.10
  Szenario: Austritt wird erst nach Bestätigung gesendet
    Angenommen die Protokoll-Schnittstelle ist eine Attrappe
    Und das Benutzerverzeichnis enthält eine synthetische Person
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Zugriffsprotokoll» wähle
    Dann zeigt das Benutzerverzeichnis Name und Rolle zur Zeit des Eintrags
    Wenn ich beim Austritt auf «Austritt erfassen» klicke
    Dann sehe ich die zweite Bestätigung und es wurde kein Austritt gesendet
    Wenn ich den Austritt abbreche
    Dann wurde kein Austritt gesendet
    Wenn ich den Austritt erneut bestätige
    Dann hat die PWA genau einen Austritt für diese Kennung gesendet

  @req-2.19
  Szenariogrundriss: Ohne A13 lässt sich die Protokollseite nicht öffnen
    Angenommen die Protokoll-Schnittstelle ist eine Attrappe
    Und ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Wenn ich die Seite «/verwaltung/protokoll» öffne
    Dann bin ich auf der Seite «/»
    Und hat die PWA die Protokoll-Schnittstelle nicht aufgerufen

    Beispiele:
      | rolle       |
      | mitarbeiter |
      | treuhaender |
