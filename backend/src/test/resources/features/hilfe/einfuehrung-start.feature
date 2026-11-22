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
