# language: de
@spec-07 @browser @attrappe
Funktionalität: Einführung auf der Startseite

  @req-1.1 @req-2.1 @req-2.2 @req-3.1
  Szenariogrundriss: Ein neuer Benutzer sieht die Einführung über den Kacheln
    Angenommen ich bin als «<rolle>» im Mandanten «<mandant>» angemeldet
    Dann sehe ich die Einführung mit meiner Rolle «<bezeichnung>» über den Kacheln
    Und enthält die Schrittfolge für «<rolle>» die Schritte «<schritte>» und Bereiche «<bereiche>»

    Beispiele:
      | rolle            | mandant | bezeichnung      | schritte                                                                               | bereiche                                                                  |
      | inhaber          | urinet  | Inhaber          | Willkommen im Archiv; Dokumente erfassen; Prüfen und bestätigen; Finden; Bestand und Abschluss; Hilfe finden | Kontakte; Importe; Jahresexport; Dokumentarten; Zugriffsprotokoll; API-Tokens |
      | mitarbeiter      | urinet  | Mitarbeiter      | Willkommen im Archiv; Dokumente erfassen; Ihre erfassten Dokumente; Finden; Hilfe finden |                                                                           |
      | treuhaender      | urinet  | Treuhänder       | Willkommen im Archiv; Finden; Bestand und Abschluss; Hilfe finden                       | Jahresexport                                                              |
      | geschaeftsstelle | vhf-gsk | Geschäftsstelle  | Willkommen im Archiv; Dokumente erfassen; Prüfen und bestätigen; Finden; Bestand und Abschluss; Hilfe finden | Kontakte                                                                  |

  @req-1.2
  Szenario: Ein bekannter Benutzer sieht keine Einführung
    Angenommen ein bekannter Benutzer «inhaber» im Mandanten «urinet» ist angemeldet
    Dann sehe ich die Startseite ohne Einführung

  @req-1.7
  Szenario: Ein Benutzer ohne Rolle sieht keine Einführung
    Angenommen ich bin als «ohne-rolle» im Mandanten «urinet» angemeldet
    Dann sehe ich die Startseite ohne Einführung
    Und sehe ich die Meldung für fehlenden Zugang

  @req-2.3 @req-2.4
  Szenario: Die Einführung übernimmt das Branding des Mandanten
    Angenommen das Branding heisst «Musterarchiv» und hat ein Logo
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Dann zeigt die Einführung den Namen «Musterarchiv» und das Logo aus dem Branding

  @req-3.8
  Szenario: Die Einführung erzeugt keine zusätzlichen API-Anfragen
    Dann sendet die Startseite mit Einführung dieselben API-Anfragen wie ohne Einführung

  @req-3.2 @req-3.3 @req-3.7
  Szenario: Einführung mit Tastatur durchgehen und abschliessen
    Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Dann zeigt die Einführung «Schritt 1 von 5» ohne Zurück
    Wenn ich in der Einführung mit der Tastatur «Weiter» wähle
    Dann zeigt die Einführung «Schritt 2 von 5» mit Fokus auf der Schrittüberschrift
    Wenn ich in der Einführung mit der Tastatur «Zurück» wähle
    Dann zeigt die Einführung «Schritt 1 von 5» mit Fokus auf der Schrittüberschrift
    Wenn ich die Einführung bis zum letzten Schritt durchgehe
    Und ich in der Einführung mit der Tastatur «Fertig» wähle
    Dann ist die Einführung mit «ABGESCHLOSSEN» und der passenden Statusmeldung geschlossen

  @req-3.4 @req-3.5 @req-5.5
  Szenario: Einführung überspringen und über die Anleitung erneut zeigen
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich in der Einführung mit der Tastatur «Überspringen» wähle
    Dann ist die Einführung mit «UEBERSPRUNGEN» und der passenden Statusmeldung geschlossen
    Wenn ich die Einführung über die Anleitung erneut öffne
    Dann zeigt die Einführung «Schritt 1 von 6» ohne Zurück
    Wenn ich die Einführung bis zum letzten Schritt durchgehe
    Und ich in der Einführung mit der Tastatur «Fertig» wähle
    Dann ist die Einführung mit «ABGESCHLOSSEN» und der passenden Statusmeldung geschlossen

  @req-3.6
  Szenario: Aktueller Schritt bleibt beim Seitenwechsel, aber nicht beim Neuladen
    Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Wenn ich in der Einführung mit der Tastatur «Weiter» wähle
    Und ich die Startseite vorübergehend verlasse und zurückkehre
    Dann zeigt die Einführung «Schritt 2 von 5»
    Und ist noch kein Einführungsvermerk gespeichert
    Wenn ich die Seite neu lade und mich erneut anmelde
    Dann zeigt die Einführung «Schritt 1 von 5» ohne Zurück

  @req-3.5 @req-3.6
  Szenario: Wiederaufgerufene Einführung behält ihren Schritt beim Zurückkehren
    Angenommen ein bekannter Benutzer «mitarbeiter» im Mandanten «urinet» ist angemeldet
    Wenn ich die Einführung über die Anleitung erneut öffne
    Und ich in der Einführung mit der Tastatur «Weiter» wähle
    Und ich die Startseite vorübergehend verlasse und zurückkehre
    Dann zeigt die Einführung «Schritt 2 von 5»
    Wenn ich die Seite neu lade und mich erneut anmelde
    Dann zeigt die Einführung «Schritt 1 von 5» ohne Zurück

  @req-3.2
  Szenario: Anleitung zum Schritt öffnet sich ohne Seitenwechsel
    Angenommen ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Wenn ich in der Einführung mit der Tastatur «Weiter» wähle
    Und ich in der Einführung mit der Tastatur «In der Anleitung lesen» wähle
    Dann zeigt die Hilfe-Leiste den Abschnitt «Erfassen»
    Und bleibt die Startseite mit dem aktuellen Einführungsschritt geöffnet
