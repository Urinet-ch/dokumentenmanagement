# language: de
# Teilnachweis bis Aufgabe 2.4: Die Szenarien prüfen die Upload-Seite der PWA gegen eine Attrappe von
# POST /api/v1/dokumente (frontend/e2e/schritte/upload.mjs), die wie in design.md «Upload» antwortet. Die Prüfungen am
# Inhalt (Typ, Grösse, Dubletten) belegen die Java-Szenarien von 2.4 gegen das echte Backend.
@spec-01 @browser
Funktionalität: Upload im Browser

  Hintergrund:
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Erfassen» wähle

  @req-3.1
  Szenario: Mehrere Dateien aller unterstützten Typen in einem Vorgang hochladen
    Wenn ich die Dateien «rechnung.pdf, quittung.jpg, scan.png, foto.heic, fax.tif» auswähle
    Und ich hochlade
    Dann hat das Archiv einen Upload mit «rechnung.pdf, quittung.jpg, scan.png, foto.heic, fax.tif» ohne «zusammenfassen» erhalten
    Und sehe ich je Datei das Ergebnis:
      | datei        | ausgang    | meldung                      |
      | rechnung.pdf | angenommen | Angenommen, wird verarbeitet |
      | quittung.jpg | angenommen | Angenommen, wird verarbeitet |
      | scan.png     | angenommen | Angenommen, wird verarbeitet |
      | foto.heic    | angenommen | Angenommen, wird verarbeitet |
      | fax.tif      | angenommen | Angenommen, wird verarbeitet |

  @req-3.1
  Szenario: Dateien per Drag & Drop ablegen
    Wenn ich die Dateien «brief.pdf, beleg.jpg» in die Ablage ziehe
    Dann sehe ich die Dateien «brief.pdf, beleg.jpg» in dieser Reihenfolge
    Wenn ich hochlade
    Dann hat das Archiv einen Upload mit «brief.pdf, beleg.jpg» ohne «zusammenfassen» erhalten

  @req-3.3
  Szenario: Ein nicht unterstützter Dateityp wird mit verständlicher Meldung abgelehnt
    Angenommen das Archiv antwortet auf Uploads so:
      | datei     | antwort                |
      | notiz.pdf | 415 DATEITYP_UNBEKANNT |
    Wenn ich die Dateien «notiz.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei     | ausgang   | meldung                                    |
      | notiz.pdf | abgelehnt | Dieser Dateityp wird nicht unterstützt.    |
      | notiz.pdf | abgelehnt | DATEITYP_UNBEKANNT                         |

  @req-3.4
  Szenario: Eine Dublette meldet «bereits vorhanden», mit Link nur auf ein sichtbares Dokument
    Angenommen das Archiv antwortet auf Uploads so:
      | datei        | antwort                 |
      | sichtbar.pdf | DUBLETTE d-vorhanden-1  |
      | geheim.pdf   | DUBLETTE                |
    Wenn ich die Dateien «sichtbar.pdf, geheim.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei        | ausgang   | meldung           |
      | sichtbar.pdf | vorhanden | bereits vorhanden |
      | geheim.pdf   | vorhanden | bereits vorhanden |
    Und führt «sichtbar.pdf» zum vorhandenen Dokument «d-vorhanden-1»
    Und bietet «geheim.pdf» keinen Link

  @req-3.5
  Szenario: Mehrere Bilder in der gewählten Reihenfolge als ein Dokument hochladen
    Wenn ich die Dateien «seite-2.jpg, seite-1.jpg, seite-3.jpg» auswähle
    Und ich «seite-1.jpg» nach oben verschiebe
    Dann sehe ich die Dateien «seite-1.jpg, seite-2.jpg, seite-3.jpg» in dieser Reihenfolge
    Wenn ich «als ein Dokument» wähle
    Und ich hochlade
    Dann hat das Archiv einen Upload mit «seite-1.jpg, seite-2.jpg, seite-3.jpg» mit «zusammenfassen» erhalten

  @req-3.6
  Szenario: Eine Datei über 50 MB wird mit DATEI_ZU_GROSS abgelehnt
    Angenommen das Archiv antwortet auf Uploads so:
      | datei      | antwort            |
      | riesig.pdf | 413 DATEI_ZU_GROSS |
    Wenn ich die Dateien «riesig.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei      | ausgang   | meldung                             |
      | riesig.pdf | abgelehnt | Die Datei ist grösser als 50 MB.    |
      | riesig.pdf | abgelehnt | DATEI_ZU_GROSS                      |

  @req-3.7
  Szenario: Eine leere oder beschädigte Datei wird mit DATEI_UNGUELTIG abgelehnt
    Angenommen das Archiv antwortet auf Uploads so:
      | datei    | antwort             |
      | leer.pdf | 422 DATEI_UNGUELTIG |
    Wenn ich die Dateien «leer.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei    | ausgang   | meldung                              |
      | leer.pdf | abgelehnt | Die Datei ist leer oder beschädigt.  |
      | leer.pdf | abgelehnt | DATEI_UNGUELTIG                      |

  @req-3.8
  Szenario: Je Datei ein eigenes Ergebnis, gültige Dateien trotz abgelehnter Nachbarn
    Angenommen das Archiv antwortet auf Uploads so:
      | datei       | antwort                 |
      | gut.pdf     | NEU                     |
      | doppelt.pdf | DUBLETTE d-vorhanden-2  |
      | falsch.pdf  | 415 DATEITYP_UNBEKANNT  |
      | gross.pdf   | 413 DATEI_ZU_GROSS      |
      | kaputt.pdf  | 422 DATEI_UNGUELTIG     |
    Wenn ich die Dateien «gut.pdf, doppelt.pdf, falsch.pdf, gross.pdf, kaputt.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei       | ausgang    | meldung                      |
      | gut.pdf     | angenommen | Angenommen, wird verarbeitet |
      | doppelt.pdf | vorhanden  | bereits vorhanden            |
      | falsch.pdf  | abgelehnt  | DATEITYP_UNBEKANNT           |
      | gross.pdf   | abgelehnt  | DATEI_ZU_GROSS               |
      | kaputt.pdf  | abgelehnt  | DATEI_UNGUELTIG              |
