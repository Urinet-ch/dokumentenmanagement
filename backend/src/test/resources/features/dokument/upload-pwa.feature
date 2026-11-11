# language: de
# Upload-Seite der PWA gegen das echte Backend (Aufgabe 2.4): Playwright leitet POST /api/v1/dokumente an das Archiv im
# Testmodus weiter (BrowserBackendTest mit PostgreSQL, Keycloak und MinIO) und tauscht das Token der Keycloak-Attrappe
# gegen ein echtes Token desselben Testbenutzers (frontend/e2e/hilfen/echtes-backend.mjs). Die Dateien sind synthetisch.
@spec-01 @browser
Funktionalität: Upload im Browser

  @req-3.1
  Szenario: Mehrere Dateien aller unterstützten Typen in einem Vorgang hochladen
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «rechnung.pdf, quittung.jpg, scan.png, foto.heic, fax.tif» auswähle
    Und ich hochlade
    Dann hat das Archiv einen Upload mit «rechnung.pdf, quittung.jpg, scan.png, foto.heic, fax.tif» ohne «zusammenfassen» erhalten
    Und hat das Archiv daraus je Datei ein Dokument angelegt
    Und sehe ich je Datei das Ergebnis:
      | datei        | ausgang    | meldung                      |
      | rechnung.pdf | angenommen | Angenommen, wird verarbeitet |
      | quittung.jpg | angenommen | Angenommen, wird verarbeitet |
      | scan.png     | angenommen | Angenommen, wird verarbeitet |
      | foto.heic    | angenommen | Angenommen, wird verarbeitet |
      | fax.tif      | angenommen | Angenommen, wird verarbeitet |

  @req-3.1
  Szenario: Dateien per Drag & Drop ablegen
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «brief.pdf, beleg.jpg» in die Ablage ziehe
    Dann sehe ich die Dateien «brief.pdf, beleg.jpg» in dieser Reihenfolge
    Wenn ich hochlade
    Dann hat das Archiv einen Upload mit «brief.pdf, beleg.jpg» ohne «zusammenfassen» erhalten
    Und hat das Archiv daraus je Datei ein Dokument angelegt

  @req-3.3
  Szenario: Ein nicht unterstützter Dateityp wird mit verständlicher Meldung abgelehnt
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und die Datei «notiz.pdf» ist ein Text
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «notiz.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei     | ausgang   | meldung                                    |
      | notiz.pdf | abgelehnt | Dieser Dateityp wird nicht unterstützt.    |
      | notiz.pdf | abgelehnt | DATEITYP_UNBEKANNT                         |

  @req-3.4
  Szenario: Eine Dublette meldet «bereits vorhanden» mit Link auf das sichtbare Dokument
    Angenommen «inhaber» im Mandanten «urinet» hat die Datei «sichtbar.pdf» schon hochgeladen
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «sichtbar.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei        | ausgang   | meldung           |
      | sichtbar.pdf | vorhanden | bereits vorhanden |
    Und führt «sichtbar.pdf» zum vorhandenen Dokument

  @req-3.4
  Szenario: Eine Dublette eines unsichtbaren Dokuments meldet «bereits vorhanden» ohne Link
    Angenommen «inhaber» im Mandanten «urinet» hat die Datei «geheim.pdf» schon hochgeladen
    Und ich bin als «mitarbeiter» im Mandanten «urinet» angemeldet
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «geheim.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei      | ausgang   | meldung           |
      | geheim.pdf | vorhanden | bereits vorhanden |
    Und bietet «geheim.pdf» keinen Link

  @req-3.5
  Szenario: Mehrere Bilder in der gewählten Reihenfolge als ein Dokument hochladen
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «seite-2.jpg, seite-1.jpg, seite-3.jpg» auswähle
    Und ich «seite-1.jpg» nach oben verschiebe
    Dann sehe ich die Dateien «seite-1.jpg, seite-2.jpg, seite-3.jpg» in dieser Reihenfolge
    Wenn ich «als ein Dokument» wähle
    Und ich hochlade
    Dann hat das Archiv einen Upload mit «seite-1.jpg, seite-2.jpg, seite-3.jpg» mit «zusammenfassen» erhalten
    Und hat das Archiv daraus ein Dokument angelegt

  @req-3.6
  Szenario: Eine Datei über 50 MB wird mit DATEI_ZU_GROSS abgelehnt
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und die Datei «riesig.pdf» ist grösser als 50 MB
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «riesig.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei      | ausgang   | meldung                                  |
      | riesig.pdf | abgelehnt | Die Datei oder die Anfrage ist zu gross. |
      | riesig.pdf | abgelehnt | DATEI_ZU_GROSS                           |

  @req-3.7
  Szenario: Eine leere oder beschädigte Datei wird mit DATEI_UNGUELTIG abgelehnt
    Angenommen ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und die Datei «leer.pdf» ist leer
    Und die Datei «kaputt.pdf» ist abgeschnitten
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «leer.pdf, kaputt.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei      | ausgang   | meldung                              |
      | leer.pdf   | abgelehnt | Die Datei ist leer oder beschädigt.  |
      | leer.pdf   | abgelehnt | DATEI_UNGUELTIG                      |
      | kaputt.pdf | abgelehnt | Die Datei ist leer oder beschädigt.  |
      | kaputt.pdf | abgelehnt | DATEI_UNGUELTIG                      |

  @req-3.8
  Szenario: Je Datei ein eigenes Ergebnis, gültige Dateien trotz abgelehnter Nachbarn
    Angenommen «inhaber» im Mandanten «urinet» hat die Datei «doppelt.pdf» schon hochgeladen
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und die Datei «falsch.pdf» ist ein Text
    Und die Datei «gross.pdf» ist grösser als 50 MB
    Und die Datei «kaputt.pdf» ist abgeschnitten
    Und ich im Menü «Erfassen» wähle
    Wenn ich die Dateien «gut.pdf, doppelt.pdf, falsch.pdf, gross.pdf, kaputt.pdf» auswähle
    Und ich hochlade
    Dann sehe ich je Datei das Ergebnis:
      | datei       | ausgang    | meldung                      |
      | gut.pdf     | angenommen | Angenommen, wird verarbeitet |
      | doppelt.pdf | vorhanden  | bereits vorhanden            |
      | falsch.pdf  | abgelehnt  | DATEITYP_UNBEKANNT           |
      | gross.pdf   | abgelehnt  | DATEI_ZU_GROSS               |
      | kaputt.pdf  | abgelehnt  | DATEI_UNGUELTIG              |
    Und führt «doppelt.pdf» zum vorhandenen Dokument
