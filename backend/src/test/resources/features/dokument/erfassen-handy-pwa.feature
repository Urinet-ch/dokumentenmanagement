# language: de
# Handy-Erfassung der PWA (Aufgabe 3.7): Chromium als Handy (390 × 844, Touch) mit Fake-Kamera; sie filmt ein
# synthetisches Testbild (frontend/e2e/hilfen/testbild.mjs). Der Scanner (OpenCV.js und jscanify im Web Worker) läuft
# echt. Angenommene Uploads leitet Playwright an das Archiv im Testmodus weiter (wie upload-pwa.feature); Dublette, 5xx,
# 4xx und 401 beantwortet es selbst. Req 4.1 (Installation und Test auf echten Geräten) weist Fabian manuell nach.
@spec-01 @browser
Funktionalität: Handy-Erfassung

  @req-4.2 @req-4.3
  Szenario: Mehrere Seiten mit der Rückkamera aufnehmen, Kanten werden erkannt
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Dann hat die App die Rückkamera geöffnet
    Wenn ich 2 Seiten aufnehme
    Dann zeigt Seite 1 «Kanten erkannt, zugeschnitten und entzerrt.»
    Und zeigt Seite 2 «Kanten erkannt, zugeschnitten und entzerrt.»

  @req-4.3
  Szenario: Ohne erkannte Kanten verwendet die App das ganze Bild
    Angenommen ein Handy, dessen Rückkamera «eine leere Fläche» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Dann zeigt Seite 1 «Keine Kanten erkannt, das ganze Bild wird verwendet.»

  @req-4.3
  Szenario: Den vorgeschlagenen Zuschnitt anpassen und eine Seite verwerfen
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Und ich 2 Seiten aufnehme
    Und zeigt Seite 1 «Kanten erkannt, zugeschnitten und entzerrt.»
    Und ich die Ecke oben links von Seite 1 nach rechts schiebe
    Dann zeigt Seite 1 «Zuschnitt angepasst.»
    Wenn ich Seite 2 verwerfe
    Dann sehe ich 1 Seite

  @req-4.3
  Szenario: Ohne Kamera ein Foto wählen; die App schneidet es ebenfalls zu
    Angenommen ein Handy ohne Kamera
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Dann sehe ich die Meldung «Die Kamera ist nicht verfügbar. Sie können stattdessen ein Foto aufnehmen oder auswählen.»
    Wenn ich das Foto «blatt.png» wähle
    Und ich das Foto «leer.png» wähle
    Dann zeigt Seite 1 «Kanten erkannt, zugeschnitten und entzerrt.»
    Und zeigt Seite 2 «Keine Kanten erkannt, das ganze Bild wird verwendet.»

  @req-4.4
  Szenario: Offline und ohne Anmeldung erfassen; die Aufnahmen bleiben auf dem Gerät
    Angenommen ein Handy mit installierter PWA, dessen Rückkamera «ein Blatt» filmt
    Und das Handy offline ist
    Wenn ich die Startseite öffne
    Und ich auf den Link «Ohne Verbindung erfassen» tippe
    Dann bin ich auf der Seite «/erfassen»
    Und sehe ich die Meldung «Sie sind nicht angemeldet. Aufnahmen bleiben auf diesem Gerät und werden nach der Anmeldung hochgeladen.»
    Wenn ich 2 Seiten aufnehme
    Und zeigt Seite 2 «Kanten erkannt, zugeschnitten und entzerrt.»
    Und ich die Erfassung abschliesse
    Dann zeigt der Zähler «Wartet auf Upload: 1»
    Wenn ich die Seite neu lade
    Dann zeigt der Zähler «Wartet auf Upload: 1»

  @req-4.4 @req-4.5 @req-4.7 @req-4.9
  Szenario: Offline erfasste Seiten lädt die App hoch, sobald die Verbindung besteht
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Dann hat die App die Rückkamera geöffnet
    Wenn das Handy offline ist
    Und ich 2 Seiten aufnehme
    Und zeigt Seite 2 «Kanten erkannt, zugeschnitten und entzerrt.»
    Und ich die Erfassung abschliesse
    Dann zeigt der Zähler «Wartet auf Upload: 1»
    Und hat das Archiv keinen Upload-Versuch erhalten
    Wenn das Handy wieder online ist
    Dann hat das Archiv eine Erfassung mit 2 Seiten als ein Dokument angelegt
    Und zeigt der Zähler «Wartet auf Upload: 0»
    Und tragen die Originale den Namen «Aufnahme TT.MM.JJJJ HH:MM» der ersten Aufnahme
    Und sind die Originale die unbearbeiteten Aufnahmen
    Und sind die Arbeitsfassungen zugeschnittene JPEG mit 2480 Pixeln an der langen Kante

  @req-4.9
  Szenario: Der Dateiname nennt die erste Aufnahme in Ortszeit Zürich, auch beim Upload am Folgetag
    Angenommen ein Handy in der Zeitzone «Asia/Tokyo», dessen Rückkamera «ein Blatt» filmt
    Und die Uhr des Handys steht auf dem 13.11.2026 um 23:59 Uhr in Zürich
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich im Menü «Erfassen» wähle
    Dann hat die App die Rückkamera geöffnet
    Wenn das Handy offline ist
    Und ich 1 Seite aufnehme
    Und ich die Erfassung abschliesse
    Und die Uhr des Handys 2 Minuten vorrückt
    Und das Handy wieder online ist
    Dann hat das Archiv eine Erfassung mit 1 Seite als ein Dokument angelegt
    Und speichert das Archiv den Dateinamen «Aufnahme 13.11.2026 23:59»

  @req-4.5
  Szenario: Meldet das Archiv eine Dublette, gilt die Erfassung als übertragen
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und das Archiv meldet den nächsten Upload als Dublette
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Und ich die Erfassung abschliesse
    Dann hat das Archiv einen Upload-Versuch erhalten
    Und zeigt der Zähler «Wartet auf Upload: 0»
    Und sehe ich nichts unter «Nicht hochgeladen»

  @req-4.6
  Szenario: Nach drei Fehlversuchen mit HTTP 5xx ist die Erfassung fehlerhaft und lässt sich wiederholen
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und die Uhr des Handys lässt sich vorstellen
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und das Archiv antwortet auf die nächsten 3 Uploads mit Status 503
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Und ich die Erfassung abschliesse
    Dann hat das Archiv einen Upload-Versuch erhalten
    Und zeigt der Zähler «Wartet auf Upload: 1»
    Wenn die Wartezeit bis zum nächsten Versuch verstrichen ist
    Und die Wartezeit bis zum nächsten Versuch verstrichen ist
    Dann sehe ich die Erfassung unter «Nicht hochgeladen» mit der Meldung «Der Upload ist dreimal fehlgeschlagen (Verbindung oder Archiv). Bitte später erneut versuchen.»
    Und hat das Archiv 3 Upload-Versuche erhalten
    Wenn ich auf «Erneut versuchen» klicke
    Dann hat das Archiv eine Erfassung mit 1 Seite als ein Dokument angelegt
    Und sehe ich nichts unter «Nicht hochgeladen»

  @req-4.8
  Szenario: Lehnt das Archiv mit HTTP 4xx ab, ist die Erfassung sofort fehlerhaft mit der Meldung des Archivs
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und das Archiv lehnt den nächsten Upload mit Status 400 und der Meldung «Die Anfrage ist ungültig.» ab
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Und ich die Erfassung abschliesse
    Dann sehe ich die Erfassung unter «Nicht hochgeladen» mit der Meldung «Die Anfrage ist ungültig.»
    Und hat das Archiv einen Upload-Versuch erhalten
    Und zeigt der Zähler «Wartet auf Upload: 0»

  @req-4.8
  Szenario: Bei HTTP 401 meldet man sich neu an; danach lädt die App die Erfassung hoch
    Angenommen ein Handy, dessen Rückkamera «ein Blatt» filmt
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Und das Archiv antwortet auf den nächsten Upload mit Status 401
    Wenn ich im Menü «Erfassen» wähle
    Und ich 1 Seite aufnehme
    Und ich die Erfassung abschliesse
    Dann sehe ich die Meldung «Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an.»
    Und zeigt der Zähler «Wartet auf Upload: 1»
    Wenn ich mich anmelde
    Dann hat das Archiv eine Erfassung mit 1 Seite als ein Dokument angelegt
    Und zeigt der Zähler «Wartet auf Upload: 0»
