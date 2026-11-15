# language: de
# Vorschau und Download in der Dokumentansicht (Req 14.6) gegen das echte Archiv im Testmodus MIT MinIO statt Stub
# (BrowserBackendTest, Schritte in frontend/e2e/schritte/vorschau-echt.mjs): Die Adresse der Vorschau liefert beim
# echten GET Content-Type application/pdf und Content-Disposition inline, damit der Browser die Datei anzeigt statt
# herunterzuladen; erst der Klick auf «PDF/A herunterladen» holt eine eigene Adresse mit attachment und löst einen
# Download aus. Alle Daten sind synthetisch; Adressen der Ablage werden nie ausgegeben.
@spec-01 @browser @pdf-viewer
Funktionalität: Vorschau in der Seite, Download nur auf Klick

  Hintergrund:
    Angenommen die PWA spricht mit dem Archiv im Testmodus

  @req-14.6
  Szenario: Die Vorschau zeigt das PDF in der Seite, ohne dass der Browser die Datei herunterlädt
    Angenommen «inhaber» im Mandanten «urinet» hat das PDF «Vorschau-Test.pdf» im Eingangskorb mit dem Titel «Vorschau Test inline»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Dokumentansicht von «Vorschau-Test.pdf» öffne
    Dann sehe ich die Vorschau des PDF
    Und hat der Browser die Vorschau mit Content-Type «application/pdf» und Content-Disposition «inline» erhalten
    Und hat der Browser keine Datei heruntergeladen

  @req-14.6
  Szenario: Erst der Klick auf «PDF/A herunterladen» löst einen Download aus
    Angenommen «inhaber» im Mandanten «urinet» hat das PDF «Download-Test.pdf» im Eingangskorb mit dem Titel «Vorschau Test download»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Dokumentansicht von «Download-Test.pdf» öffne
    Und ich auf «PDF/A herunterladen» klicke
    Dann hat der Browser eine Datei heruntergeladen
    Und hat der Browser nur die Vorschau mit Content-Disposition «inline» erhalten und die Datei als «pdfa.pdf» mit der ID von «Download-Test.pdf» heruntergeladen
