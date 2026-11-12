# language: de
# Import-Oberfläche der PWA (Aufgabe 4.4) gegen eine Attrappe der Import-Schnittstelle (frontend/e2e/schritte/importe.mjs):
# Im Testmodus läuft kein Worker, Vorschau und Übernahme kämen darum nie an. Die Attrappe antwortet wie
# ImportResource (Statusfolge, Zähler, Filter, Kandidaten) und hält fest, was die PWA sendet. Das Verhalten des Servers
# belegen die Java-Szenarien unter features/importe/. Alle Daten sind synthetisch.
@spec-01 @browser @attrappe
Funktionalität: CSV-Import in der PWA

  @req-13.2 @req-13.7
  Szenariogrundriss: Erstmals hochgeladen: Zuordnung mit Profil «21.CRM» bestätigen (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und die Import-Schnittstelle ist eine Attrappe ohne bekannte Zuordnung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Importe» wähle
    Wenn ich die Kontakt-Datei «kontakte.csv» mit der Kopfzeile «Organisation;Vorname;Name;PLZ;Ort;E-Mail» als Vollexport hochlade
    Dann hat das Archiv die Datei «kontakte.csv» als Multipart-Feld «datei» mit «art=kontakt» und «vollexport=true» erhalten
    Und sehe ich den Zuordnungsdialog mit «Profil «21.CRM» verwenden» ausgewählt
    Und ist die Spalte «PLZ» dem Feld «PLZ» zugeordnet
    Wenn ich die Spalte «E-Mail» dem Feld «– nicht zuordnen –» zuordne
    Dann ist «Profil «21.CRM» verwenden» nicht mehr ausgewählt
    Wenn ich auf «Zuordnung bestätigen» klicke
    Dann hat das Archiv die Zuordnung ohne Profil mit den Feldern «organisation, vorname, name, plz, ort» erhalten
    Und sehe ich die Vorschau mit «4 Alle, 1 neu, 1 geändert, 1 unverändert, 1 mögliche Dublette, 0 fehlerhaft, 0 übersprungen»
    Und ragt nichts über den Bildschirm hinaus

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-13.3 @req-13.5
  Szenariogrundriss: Bekannte Zuordnung: Vorschau mit Zählern und Filter, ohne etwas zu übernehmen (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und die Import-Schnittstelle ist eine Attrappe mit bekannter Zuordnung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Importe» wähle
    Wenn ich die Kontakt-Datei «kontakte.csv» mit der Kopfzeile «Organisation;Vorname;Name;PLZ;Ort;E-Mail» hochlade
    Dann sehe ich die Meldung «Das Archiv gleicht die Zeilen ab. Die Vorschau erscheint hier automatisch.»
    Und sehe ich die Vorschau mit «4 Alle, 1 neu, 1 geändert, 1 unverändert, 1 mögliche Dublette, 0 fehlerhaft, 0 übersprungen»
    Wenn ich den Filter «mögliche Dublette» wähle
    Dann hat das Archiv die Zeilen mit dem Ergebnis «DUBLETTE» abgefragt
    Und sehe ich nur die Zeile 3 mit den möglichen Kontakten «Muster Garage AG», «Muster Garage GmbH»
    Und hat das Archiv keine Übernahme erhalten
    Und ragt nichts über den Bildschirm hinaus

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-13.5 @req-13.6 @req-13.7
  Szenariogrundriss: Dublette entscheiden und übernehmen (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und die Import-Schnittstelle ist eine Attrappe mit bekannter Zuordnung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Importe» wähle
    Wenn ich die Kontakt-Datei «kontakte.csv» mit der Kopfzeile «Organisation;Vorname;Name;PLZ;Ort;E-Mail» als Vollexport hochlade
    Und ich für Zeile 3 «Zusammenführen mit «Muster Garage GmbH»» wähle
    Und ich auf «Übernehmen …» klicke
    Dann sehe ich die Meldung «Vollexport: Kontakte mit Kontakt-ID aus 21.CRM, die in dieser Datei fehlen oder dort gelöscht sind, werden als «nicht mehr im CRM-Export» gekennzeichnet. Gelöscht wird nichts.»
    Und hat das Archiv keine Übernahme erhalten
    Wenn ich auf «Jetzt übernehmen» klicke
    Dann hat das Archiv die Übernahme mit dem Entscheid «3» → «Muster Garage GmbH» erhalten
    Und sehe ich die Bilanz «1 angelegt, 2 aktualisiert, 0 ausgelassen, 3 als «nicht mehr im CRM-Export» gekennzeichnet»
    Wenn ich im Menü «Importe» wähle
    Dann zeigt die Liste der Importe «kontakte.csv» mit «Übernommen»
    Und ragt nichts über den Bildschirm hinaus

    Beispiele:
      | breite |
      | 375    |
      | 1280   |

  @req-13.2
  Szenario: Ohne Name oder Organisation lässt sich die Zuordnung nicht bestätigen
    Angenommen die Import-Schnittstelle ist eine Attrappe ohne bekannte Zuordnung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Importe» wähle
    Wenn ich die Kontakt-Datei «kontakte.csv» mit der Kopfzeile «Organisation;Vorname;Name;PLZ;Ort;E-Mail» hochlade
    Und ich die Spalte «Organisation» dem Feld «– nicht zuordnen –» zuordne
    Und ich die Spalte «Name» dem Feld «– nicht zuordnen –» zuordne
    Dann sehe ich die Meldung «Ordnen Sie «Name» oder «Organisation» einer Spalte zu.»
    Und ist der Knopf «Zuordnung bestätigen» gesperrt

  Szenario: Eine Datei über 5 MiB lehnt die PWA ab, ohne sie zu senden
    Angenommen die Import-Schnittstelle ist eine Attrappe ohne bekannte Zuordnung
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Importe» wähle
    Wenn ich eine Kontakt-Datei «gross.csv» über 5 MiB wähle
    Dann sehe ich einen Fehler mit «grösser als 5 MiB»
    Und ist der Knopf «Hochladen» gesperrt
    Und hat das Archiv keinen Upload erhalten

  @req-2.6
  Szenario: Ohne Recht A12 führt die Adresse «/importe» zur Startseite
    Angenommen die Import-Schnittstelle ist eine Attrappe ohne bekannte Zuordnung
    Und Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
    Wenn ich die Seite «/importe» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/»
    Und hat die PWA die Import-Schnittstelle nicht aufgerufen
