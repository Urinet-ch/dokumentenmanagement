# language: de
@spec-01
Funktionalität: Einmaliger Import des Google-Drive-Altbestands
  Das Werkzeug «tools/drive_import» (Java, Picocli) liest einen lokalen Ordner rekursiv und lädt jede Datei über
  «POST /api/v1/dokumente» mit ihrem Ordnerpfad als Hinweis hoch; es meldet sich mit einem API-Token vom Scope
  «import» an, das es nur aus einer Datei mit Rechten 600 liest. Die Szenarien rufen das Werkzeug direkt gegen das
  laufende Archiv auf. Ordner und Dateien sind synthetisch (Testdateien), keine echten Belege.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-12.1 @req-12.2
  Szenario: Jede Datei kommt mit ihrem Ordnerpfad und den erkannten Mustern als Hinweis ins Archiv
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                                    | typ  |
      | Kunden/FC Muster/Rechnungen/2026/r1.pdf | pdf  |
      | Kunden/FC Muster/Offerten/o1.jpg        | jpeg |
      | Buchhaltung 2026/Bank/auszug.png        | png  |
      | Büro/Allgemein/scan.pdf                 | pdf  |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und hat das Archiv im Mandanten «urinet» diese Dateien mit Quelle «DRIVE» vom Import-Token angelegt:
      | pfad                                    | hinweis                                                                                                  |
      | Kunden/FC Muster/Rechnungen/2026/r1.pdf | Drive-Import; Ordner: UriNet/Kunden/FC Muster/Rechnungen/2026; Partner: FC Muster; Art: AUSGANGSRECHNUNG |
      | Kunden/FC Muster/Offerten/o1.jpg        | Drive-Import; Ordner: UriNet/Kunden/FC Muster/Offerten; Partner: FC Muster; Art: OFFERTE                 |
      | Buchhaltung 2026/Bank/auszug.png        | Drive-Import; Ordner: UriNet/Buchhaltung 2026/Bank; Bereich: Buchhaltung; Jahr: 2026                     |
      | Büro/Allgemein/scan.pdf                 | Drive-Import; Ordner: UriNet/Büro/Allgemein                                                              |
    Und enthält die Ausgabe des Drive-Imports das Token nicht

  @req-12.9 @req-3.9
  Szenario: Das Archiv speichert nur den Dateinamen ohne Ordner, der Ordnerpfad steht nur im Hinweis
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                                          | typ |
      | Kunden/FC Muster/Rechnungen/Rechnung_2026.pdf | pdf |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und hat das Archiv im Mandanten «urinet» diese Dateien mit Quelle «DRIVE» vom Import-Token angelegt:
      | pfad                                          | dateiname         | hinweis                                                                                            |
      | Kunden/FC Muster/Rechnungen/Rechnung_2026.pdf | Rechnung_2026.pdf | Drive-Import; Ordner: UriNet/Kunden/FC Muster/Rechnungen; Partner: FC Muster; Art: AUSGANGSRECHNUNG |

  @req-12.1
  Szenario: Ohne Token-Datei mit Rechten 600 oder mit falschem Scope überträgt das Werkzeug nichts
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad       | typ |
      | a/eins.pdf | pdf |
      | a/zwei.pdf | pdf |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 644
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 2
    Und meldet der Drive-Import «chmod 600»
    Und ist im Mandanten «urinet» keine Datei des Ordners gespeichert
    Angenommen ein Token vom Scope «generator» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 3
    Und nennt der Bericht des Drive-Imports diese Dateien:
      | pfad              | ergebnis     | grund                          |
      | UriNet/a/eins.pdf | abgelehnt    | Token darf nicht erfassen (403 |
      | UriNet/a/zwei.pdf | übersprungen | Lauf abgebrochen               |
    Und ist im Mandanten «urinet» keine Datei des Ordners gespeichert
    Und enthält die Ausgabe des Drive-Imports das Token nicht

  @req-12.3
  Szenario: Der Trockenlauf zählt und listet auf, ohne Token und ohne etwas zu übertragen
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                              | typ  |
      | Kunden/FC Muster/Rechnungen/r.pdf | pdf  |
      | Ablage/Notiz.gdoc                 | gdoc |
      | Ablage/Brief.docx                 | docx |
    Wenn der Drive-Import den Ordner «UriNet» im Trockenlauf durchgeht
    Dann endet der Drive-Import mit Exit-Code 0
    Und nennt der Bericht des Drive-Imports diese Dateien:
      | pfad                                     | ergebnis         | grund                                                                                 |
      | UriNet/Kunden/FC Muster/Rechnungen/r.pdf | würde übertragen | Ordner: UriNet/Kunden/FC Muster/Rechnungen; Partner: FC Muster; Art: AUSGANGSRECHNUNG |
      | UriNet/Ablage/Notiz.gdoc                 | übersprungen     | bitte in Google Drive als PDF exportieren                                             |
      | UriNet/Ablage/Brief.docx                 | übersprungen     | Office-Datei                                                                          |
    Und geht der Bericht des Trockenlaufs auf: 3 gefunden = 1 würde übertragen + 2 übersprungen + 0 abgelehnt
    Und ist im Mandanten «urinet» keine Datei des Ordners gespeichert

  @req-12.4
  Szenario: Ein zweiter Lauf zählt die Dubletten als schon vorhanden
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad         | typ |
      | Belege/a.pdf | pdf |
      | Belege/b.png | png |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Und der Drive-Import hat den Ordner «UriNet» schon einmal ins Archiv geladen
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und nennt der Bericht des Drive-Imports diese Dateien:
      | pfad                | ergebnis        | grund |
      | UriNet/Belege/a.pdf | schon vorhanden |       |
      | UriNet/Belege/b.png | schon vorhanden |       |
    Und geht der Schlussbericht auf: 2 gefunden = 0 übertragen + 2 schon vorhanden + 0 übersprungen + 0 abgelehnt
    Und hat das Archiv im Mandanten «urinet» je Datei des Ordners genau ein Dokument

  @req-12.5 @req-12.6
  Szenario: Google-, Office-, E-Mail- und ZIP-Dateien sowie abgelehnte Dateien werden mit Pfad und Grund aufgeführt
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                | typ          |
      | Ablage/Notiz.gdoc   | gdoc         |
      | Ablage/Liste.gsheet | gsheet       |
      | Ablage/Brief.docx   | docx         |
      | Ablage/Tabelle.xlsx | xlsx         |
      | Ablage/Antwort.eml  | eml          |
      | Ablage/Paket.zip    | zip          |
      | Ablage/notiz.txt    | text         |
      | Ablage/leer.pdf     | leer         |
      | Ablage/gross.pdf    | pdf-zu-gross |
      | Ablage/gut.pdf      | pdf          |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 1
    Und steht unter «Bitte als PDF exportieren» im Bericht des Drive-Imports genau:
      | eintrag                    |
      | UriNet/Ablage/Liste.gsheet |
      | UriNet/Ablage/Notiz.gdoc   |
    Und steht unter «Nicht übernommen» im Bericht des Drive-Imports genau:
      | eintrag                    | grund                                     |
      | UriNet/Ablage/Antwort.eml  | E-Mail-Datei, in Phase 1 nicht übernommen |
      | UriNet/Ablage/Brief.docx   | Office-Datei, in Phase 1 nicht übernommen |
      | UriNet/Ablage/Paket.zip    | Archivdatei (ZIP o. ä.)                   |
      | UriNet/Ablage/Tabelle.xlsx | Office-Datei, in Phase 1 nicht übernommen |
      | UriNet/Ablage/gross.pdf    | (413 DATEI_ZU_GROSS)                      |
      | UriNet/Ablage/leer.pdf     | (422 DATEI_UNGUELTIG)                     |
      | UriNet/Ablage/notiz.txt    | (415 DATEITYP_UNBEKANNT)                  |
    Und hat das Archiv im Mandanten «urinet» nur diese Dateien des Ordners gespeichert:
      | pfad           |
      | Ablage/gut.pdf |

  @req-12.1 @req-12.6
  Szenario: Symbolische Links auf Dateien oder Ordner ausserhalb des Startordners folgt das Werkzeug nie
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad      | typ |
      | a/gut.pdf | pdf |
    Und ausserhalb des Ordners liegen diese synthetischen Dateien:
      | pfad                     | typ |
      | privat/geheim.pdf        | pdf |
      | privat/ordner/privat.pdf | pdf |
    Und im Ordner «UriNet» liegen diese symbolischen Links nach ausserhalb:
      | link        | ziel              |
      | a/link.pdf  | privat/geheim.pdf |
      | link-ordner | privat/ordner     |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und steht unter «Nicht übernommen» im Bericht des Drive-Imports genau:
      | eintrag            | grund             |
      | UriNet/a/link.pdf  | symbolischer Link |
      | UriNet/link-ordner | symbolischer Link |
    Und hat das Archiv im Mandanten «urinet» nur diese Dateien des Ordners gespeichert:
      | pfad      |
      | a/gut.pdf |
    Und geht der Schlussbericht auf: 3 gefunden = 1 übertragen + 0 schon vorhanden + 2 übersprungen + 0 abgelehnt

  @req-12.6
  Szenario: Dateien in versteckten Ordnern wie den Zwischenablagen von Google Drive werden nicht übertragen
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                            | typ  |
      | .tmp.drivedownload/teil.pdf     | pdf  |
      | Kunden/.tmp.driveupload/neu.jpg | jpeg |
      | a/gut.pdf                       | pdf  |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und steht unter «Nicht übernommen» im Bericht des Drive-Imports genau:
      | eintrag                                | grund                 |
      | UriNet/.tmp.drivedownload/teil.pdf     | in verstecktem Ordner |
      | UriNet/Kunden/.tmp.driveupload/neu.jpg | in verstecktem Ordner |
    Und hat das Archiv im Mandanten «urinet» nur diese Dateien des Ordners gespeichert:
      | pfad      |
      | a/gut.pdf |
    Und geht der Schlussbericht auf: 3 gefunden = 1 übertragen + 0 schon vorhanden + 2 übersprungen + 0 abgelehnt

  @req-12.7
  Szenario: Der Schlussbericht geht auf
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                              | typ  |
      | Kunden/FC Muster/Rechnungen/a.pdf | pdf  |
      | Kunden/FC Muster/Rechnungen/b.pdf | pdf  |
      | Ablage/neu.jpg                    | jpeg |
      | Ablage/Notiz.gdoc                 | gdoc |
      | Ablage/Brief.docx                 | docx |
      | Ablage/.DS_Store                  | text |
      | Ablage/notiz.txt                  | text |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Und der Drive-Import hat den Ordner «UriNet/Kunden» schon einmal ins Archiv geladen
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 1
    Und geht der Schlussbericht auf: 7 gefunden = 1 übertragen + 2 schon vorhanden + 3 übersprungen + 1 abgelehnt

  @req-12.8
  Szenario: Importierte Dateien landen wie ein Upload im Eingangskorb-Ablauf, nie direkt im Archiv
    Angenommen ein Ordner «UriNet» mit diesen synthetischen Dateien:
      | pfad                              | typ  |
      | Kunden/FC Muster/Rechnungen/r.pdf | pdf  |
      | Buchhaltung 2026/q.jpg            | jpeg |
    Und ein Token vom Scope «import» für «urinet» liegt in einer Datei mit Rechten 600
    Wenn der Drive-Import den Ordner «UriNet» ins Archiv lädt
    Dann endet der Drive-Import mit Exit-Code 0
    Und steht jedes importierte Dokument im Status «NEU» als «PERSONAL», unbestätigt und nicht archiviert
    Und ist für jedes importierte Dokument genau ein Auftrag «VERARBEITEN» eingereiht
    Und liegt jede importierte Datei unter «eingang/» und keine unter «archiv/» im Bucket von «urinet»
