# language: de
@spec-01
Funktionalität: Upload von Dateien
  Der Upload «POST /api/v1/dokumente» (Recht A1) nimmt PDF, JPEG, PNG, HEIC/HEIF und TIFF bis 50 MB je Datei an,
  auch mehrere in einem Vorgang. Je Datei hält das Archiv zuerst die SHA-256 fest, prüft den Typ am Inhalt, sucht
  Dubletten im Mandanten und legt die Datei ohne Sperre unter «eingang/» ab; je neuem Dokument reiht es einen Auftrag
  «VERARBEITEN» ein. Die Dateien sind synthetisch (Testdateien), keine echten Belege.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt

  @req-3.1 @req-3.2 @req-6.1 @req-10.2
  Szenario: Alle unterstützten Typen werden in einem Vorgang angenommen und eingereiht
    Angenommen der Stand des Zugriffsprotokolls von «urinet» ist gemerkt
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei        | typ  |
      | beleg.pdf    | pdf  |
      | foto.jpg     | jpeg |
      | scan.png     | png  |
      | handy.heic   | heic |
      | bild.heif    | heif |
      | fax.tif      | tiff |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei      | status |
      | beleg.pdf  | NEU    |
      | foto.jpg   | NEU    |
      | scan.png   | NEU    |
      | handy.heic | NEU    |
      | bild.heif  | NEU    |
      | fax.tif    | NEU    |
    Und das Zugriffsprotokoll von «urinet» hat genau einen neuen Eintrag:
      | benutzer       | rolle   | aktion   | dokument | ip | grund | fehlercode |
      | inhaber-urinet | INHABER | ERFASSEN |          |    |       |            |
    Und steht jedes neue Dokument im Status «NEU» als «PERSONAL» mit Quelle «UPLOAD» und Erfasser «inhaber-urinet»
    Und hält das Archiv zu jeder neuen Datei die SHA-256 der gesendeten Bytes mit Empfangszeitpunkt fest
    Und liegt jede neue Datei unverändert unter «eingang/» im Bucket von «urinet»
    Und ist für jedes neue Dokument genau ein Auftrag «VERARBEITEN» eingereiht
    Und bleiben keine temporären Dateien des Uploads zurück

  @req-3.2 @req-5.1
  Szenario: Nach dem Upload verarbeitet der Worker das Dokument und reiht den KI-Vorschlag ein
    Angenommen die Verarbeitung ist bereit
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei        | typ      |
      | rechnung.pdf | pdf-text |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei        | status |
      | rechnung.pdf | NEU    |
    Und ist für jedes neue Dokument genau ein Auftrag «VERARBEITEN» eingereiht
    Wenn der Worker die Aufträge bearbeitet
    Dann ist der Auftrag «VERARBEITEN» jedes neuen Dokuments «FERTIG»
    Und steht jedes neue Dokument im Status «EINGANGSKORB» mit PDF/A und Volltext
    Und ist für jedes neue Dokument genau ein Auftrag «KI_VORSCHLAG» eingereiht

  @req-3.1 @req-3.6
  Szenario: Eine Datei von genau 50 MB wird angenommen
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei      | typ    |
      | grenze.pdf | pdf:50 |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei      | status |
      | grenze.pdf | NEU    |
    Und liegt jede neue Datei unverändert unter «eingang/» im Bucket von «urinet»

  @req-3.1 @req-3.6
  Szenario: Drei gleichzeitige Uploads von je 50 MB laufen über das Arbeitsverzeichnis und hinterlassen nichts
    Wenn «inhaber-urinet» gleichzeitig in 3 Anfragen je eine Datei von 50 MB hochlädt
    Dann antworten alle Anfragen mit Status 202 und je einem neuen Dokument im Status «NEU»
    Und lagen die Teile der Uploads im Arbeitsverzeichnis, nicht im temporären Verzeichnis der JVM
    Und bleibt im Arbeitsverzeichnis keine Datei zurück

  @req-3.3
  Szenario: Der Typ wird am Inhalt erkannt, nicht an der Endung
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei       | typ  |
      | foto.pdf    | jpeg |
      | rechnung    | pdf  |
      | notiz.pdf   | text |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei     | status    | fehlercode         | http |
      | foto.pdf  | NEU       |                    |      |
      | rechnung  | NEU       |                    |      |
      | notiz.pdf | ABGELEHNT | DATEITYP_UNBEKANNT | 415  |

  @req-3.3
  Szenario: Eine einzelne Datei mit unbekanntem Inhalt wird mit 415 abgelehnt und nicht gespeichert
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei     | typ  |
      | notiz.pdf | text |
    Dann antwortet der Upload mit Status 415, Fehlercode «DATEITYP_UNBEKANNT» und diesen Ergebnissen:
      | datei     | status    | fehlercode         | http |
      | notiz.pdf | ABGELEHNT | DATEITYP_UNBEKANNT | 415  |
    Und ist im Mandanten «urinet» nichts gespeichert
    Und bleiben keine temporären Dateien des Uploads zurück

  @req-3.4
  Szenario: Eine vorhandene Datei meldet das Archiv als Dublette mit der ID des Dokuments
    Angenommen «inhaber-urinet» hat die Datei «beleg.pdf» (pdf) hochgeladen
    Wenn «inhaber-urinet» die Datei «beleg.pdf» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei     | status   |
      | beleg.pdf | DUBLETTE |
    Und nennt das Ergebnis von «beleg.pdf» das vorhandene Dokument
    Und ist im Mandanten «urinet» nichts gespeichert

  @req-3.4 @req-2.3
  Szenario: Ohne Leserecht auf das vorhandene Dokument meldet das Archiv nur «bereits vorhanden»
    Angenommen «inhaber-urinet» hat die Datei «lohn.pdf» (pdf) hochgeladen
    Wenn «mitarbeiter-urinet» die Datei «lohn.pdf» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei    | status   |
      | lohn.pdf | DUBLETTE |
    Und meldet das Ergebnis von «lohn.pdf» nur «bereits vorhanden» ohne ID
    Und ist im Mandanten «urinet» nichts gespeichert

  @req-3.4
  Szenario: Die eigene Datei erkennt der Mitarbeiter mit ID wieder
    Angenommen «mitarbeiter-urinet» hat die Datei «quittung.jpg» (jpeg) hochgeladen
    Wenn «mitarbeiter-urinet» die Datei «quittung.jpg» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei        | status   |
      | quittung.jpg | DUBLETTE |
    Und nennt das Ergebnis von «quittung.jpg» das vorhandene Dokument

  @req-3.4 @req-2.5
  Szenario: Ein Import-Token erfährt die ID eines vorhandenen Dokuments nicht
    Angenommen «inhaber-urinet» hat die Datei «alt.pdf» (pdf) hochgeladen
    Wenn ein Import-Token von «urinet» die Datei «alt.pdf» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei   | status   |
      | alt.pdf | DUBLETTE |
    Und meldet das Ergebnis von «alt.pdf» nur «bereits vorhanden» ohne ID
    Und ist im Mandanten «urinet» nichts gespeichert

  @req-3.4
  Szenario: Nach dem Verwerfen gilt die Datei nicht mehr als Dublette
    Angenommen «inhaber-urinet» hat die Datei «fehlerfassung.pdf» (pdf) hochgeladen
    Und das Dokument zur Datei «fehlerfassung.pdf» ist verworfen
    Wenn «inhaber-urinet» die Datei «fehlerfassung.pdf» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei             | status |
      | fehlerfassung.pdf | NEU    |
    Und ist das Ergebnis von «fehlerfassung.pdf» ein neues Dokument

  @req-3.4 @req-1.4
  Szenario: Dubletten gelten nur im eigenen Mandanten
    Angenommen «inhaber-urinet» hat die Datei «merkblatt.pdf» (pdf) hochgeladen
    Wenn «geschaeftsstelle-vhf-gsk» die Datei «merkblatt.pdf» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei         | status |
      | merkblatt.pdf | NEU    |
    Und steht jedes neue Dokument im Status «NEU» als «PERSONAL» mit Quelle «UPLOAD» und Erfasser «geschaeftsstelle-vhf-gsk»

  @req-3.4 @req-2.3
  Szenario: Die Geschäftsstelle erkennt eine Datei ihres Mandanten mit ID wieder
    Angenommen «geschaeftsstelle-vhf-gsk» hat die Datei «bulletin.pdf» (pdf) hochgeladen
    Wenn «geschaeftsstelle-vhf-gsk» die Datei «bulletin.pdf» erneut hochlädt
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei        | status   |
      | bulletin.pdf | DUBLETTE |
    Und nennt das Ergebnis von «bulletin.pdf» das vorhandene Dokument
    Und ist im Mandanten «vhf-gsk» nichts gespeichert

  @req-6.1
  Szenario: Zu jeder empfangenen Datei hält das Log Prüfsumme und Ergebnis fest, auch bei Ablehnung und Dublette
    Angenommen «inhaber-urinet» hat die Datei «spur-alt.pdf» (pdf) hochgeladen
    Und das Log des Uploads wird mitgeschnitten
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei            | typ              |
      | spur-neu.pdf     | pdf              |
      | spur-wieder.pdf  | wie:spur-alt.pdf |
      | spur-notiz.pdf   | text             |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei           | status    | fehlercode         | http |
      | spur-neu.pdf    | NEU       |                    |      |
      | spur-wieder.pdf | DUBLETTE  |                    |      |
      | spur-notiz.pdf  | ABGELEHNT | DATEITYP_UNBEKANNT | 415  |
    Und hält das Log zu jeder gesendeten Datei Mandant, Prüfsumme, Grösse und Ergebnis fest, ohne Dateinamen:
      | datei           | ergebnis           |
      | spur-neu.pdf    | ANGENOMMEN         |
      | spur-wieder.pdf | DUBLETTE           |
      | spur-notiz.pdf  | DATEITYP_UNBEKANNT |

  @req-3.5
  Szenario: Mehrere Bilder als ein Dokument in der gewählten Reihenfolge
    Wenn «inhaber-urinet» diese Dateien als ein Dokument mit dem Hinweis "Quittungen Oktober – Reise «Zürich»" hochlädt:
      | datei      | typ  |
      | seite3.jpg | jpeg |
      | seite1.png | png  |
      | seite2.jpg | jpeg |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei      | status |
      | seite3.jpg | NEU    |
      | seite1.png | NEU    |
      | seite2.jpg | NEU    |
    Und nennen alle Ergebnisse dasselbe Dokument mit dem Hinweis "Quittungen Oktober – Reise «Zürich»"
    Und hat das Dokument die Originale in der gesendeten Reihenfolge
    Und ist für jedes neue Dokument genau ein Auftrag «VERARBEITEN» eingereiht

  @req-3.5
  Szenario: Ein Hinweis aus 1'000 Zeichen wird angenommen, auch wenn er mehr als 2'048 Bytes umfasst
    Wenn «inhaber-urinet» diese Dateien als ein Dokument mit einem Hinweis aus 1000 × «€» hochlädt:
      | datei      | typ  |
      | seite1.jpg | jpeg |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei      | status |
      | seite1.jpg | NEU    |
    Und nennen alle Ergebnisse dasselbe Dokument mit einem Hinweis aus 1000 × «€»

  @req-3.5
  Szenario: Ein Hinweis über 1'000 Zeichen wird mit 400 abgelehnt und nichts gespeichert
    Wenn «inhaber-urinet» diese Dateien als ein Dokument mit einem Hinweis aus 1001 × «€» hochlädt:
      | datei      | typ  |
      | seite1.jpg | jpeg |
    Dann antwortet der Upload mit Status 400 und Fehlercode «ANFRAGE_UNGUELTIG»
    Und ist im Mandanten «urinet» nichts gespeichert

  @req-3.6 @req-3.8
  Szenario: Eine Datei über 50 MB wird abgelehnt, die Nachbarin angenommen
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei     | typ    |
      | gross.pdf | pdf:51 |
      | klein.pdf | pdf    |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei     | status    | fehlercode     | http |
      | gross.pdf | ABGELEHNT | DATEI_ZU_GROSS | 413  |
      | klein.pdf | NEU       |                |      |
    Und bleiben keine temporären Dateien des Uploads zurück

  @req-3.6
  Szenario: Eine einzelne Datei über 50 MB wird mit 413 abgelehnt und nicht gespeichert
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei     | typ    |
      | gross.pdf | pdf:51 |
    Dann antwortet der Upload mit Status 413, Fehlercode «DATEI_ZU_GROSS» und diesen Ergebnissen:
      | datei     | status    | fehlercode     | http |
      | gross.pdf | ABGELEHNT | DATEI_ZU_GROSS | 413  |
    Und ist im Mandanten «urinet» nichts gespeichert

  @req-3.6
  Szenario: Eine Anfrage über der Gesamtgrenze lehnt das Archiv vor dem Empfang ab
    Wenn «inhaber-urinet» eine Anfrage über 61 MB ankündigt
    Dann antwortet der Upload mit Status 413 und Fehlercode «DATEI_ZU_GROSS»

  @req-3.7
  Szenariogrundriss: Eine leere oder beschädigte Datei wird mit 422 abgelehnt
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei   | typ   |
      | <datei> | <typ> |
    Dann antwortet der Upload mit Status 422, Fehlercode «DATEI_UNGUELTIG» und diesen Ergebnissen:
      | datei   | status    | fehlercode      | http |
      | <datei> | ABGELEHNT | DATEI_UNGUELTIG | 422  |
    Und ist im Mandanten «urinet» nichts gespeichert

    Beispiele:
      | datei          | typ           | fall                         |
      | leer.pdf       | leer          | leer                         |
      | abbruch.pdf    | abgeschnitten | PDF ohne Endmarker           |

  @req-3.8 @req-3.4 @req-3.3 @req-3.7
  Szenario: Je Datei ein eigenes Ergebnis, gültige Dateien trotz abgelehnter Nachbarn
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei       | typ           |
      | gut.pdf     | pdf           |
      | kopie.pdf   | wie:gut.pdf   |
      | notiz.txt   | text          |
      | leer.png    | leer          |
      | foto.jpg    | jpeg          |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei     | status    | fehlercode         | http |
      | gut.pdf   | NEU       |                    |      |
      | kopie.pdf | DUBLETTE  |                    |      |
      | notiz.txt | ABGELEHNT | DATEITYP_UNBEKANNT | 415  |
      | leer.png  | ABGELEHNT | DATEI_UNGUELTIG    | 422  |
      | foto.jpg  | NEU       |                    |      |
    Und hält das Archiv zu jeder neuen Datei die SHA-256 der gesendeten Bytes mit Empfangszeitpunkt fest
    Und ist für jedes neue Dokument genau ein Auftrag «VERARBEITEN» eingereiht

  @req-3.8
  Szenario: Ein Name mit unsichtbaren Steuerzeichen wird abgelehnt, die Nachbarin angenommen
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei                  | typ |
      | rechnung{U+202E}fdp.exe | pdf |
      | beleg.pdf              | pdf |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei                  | status    | fehlercode        | http |
      | rechnung{U+202E}fdp.exe | ABGELEHNT | ANFRAGE_UNGUELTIG | 400  |
      | beleg.pdf              | NEU       |                   |      |

  @req-4.7
  Szenario: Die Handy-App überträgt je Seite die Rohaufnahme und die Arbeitsfassung
    Wenn «mitarbeiter-urinet» diese Seiten mit Arbeitsfassung als ein Dokument hochlädt:
      | datei       | typ  | arbeitsfassung |
      | seite1.heic | heic | jpeg           |
      | seite2.jpg  | jpeg | jpeg           |
    Dann antwortet der Upload mit Status 202 und diesen Ergebnissen:
      | datei       | status |
      | seite1.heic | NEU    |
      | seite2.jpg  | NEU    |
    Und steht jedes neue Dokument im Status «NEU» als «PERSONAL» mit Quelle «SCAN» und Erfasser «mitarbeiter-urinet»
    Und hat das Dokument je Seite das Original und die Arbeitsfassung unter «eingang/»

  @req-4.7
  Szenario: Eine Arbeitsfassung, die kein JPEG ist, wird abgelehnt
    Wenn «mitarbeiter-urinet» diese Seiten mit Arbeitsfassung als ein Dokument hochlädt:
      | datei      | typ  | arbeitsfassung |
      | seite1.jpg | jpeg | png            |
    Dann antwortet der Upload mit Status 415, Fehlercode «DATEITYP_UNBEKANNT» und diesen Ergebnissen:
      | datei      | status    | fehlercode         | http |
      | seite1.jpg | ABGELEHNT | DATEITYP_UNBEKANNT | 415  |
    Und ist im Mandanten «urinet» nichts gespeichert

  @req-2.7
  Szenario: Der Treuhänder darf nicht erfassen und das Archiv speichert nichts
    Wenn «treuhaender-urinet» diese Dateien hochlädt:
      | datei     | typ |
      | beleg.pdf | pdf |
    Dann antwortet der Upload mit Status 403 und Fehlercode «NICHT_ERLAUBT»
    Und ist im Mandanten «urinet» nichts gespeichert
    Und bleiben keine temporären Dateien des Uploads zurück

  @req-3.2 @req-6.1
  Szenariogrundriss: Fällt der Object Storage aus, antwortet das Archiv mit 503 und hinterlässt nichts
    Angenommen der Object Storage fällt nach 1 abgelegten Datei aus (<art>)
    Wenn «inhaber-urinet» diese Dateien hochlädt:
      | datei   | typ |
      | a.pdf   | pdf |
      | b.pdf   | pdf |
    Dann antwortet der Upload mit Status 503 und Fehlercode «SPEICHER_NICHT_ERREICHBAR»
    Und verlangt die Antwort einen neuen Versuch nach 30 Sekunden
    Und ist im Mandanten «urinet» nichts gespeichert

    Beispiele:
      | art         |
      | NETZ        |
      | ANTWORT_503 |
