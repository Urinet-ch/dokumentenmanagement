# language: de
# Vorschau und Download in der Dokumentansicht (Req 14.6) gegen das echte Archiv im Testmodus MIT MinIO statt Stub
# (BrowserBackendTest, Schritte in frontend/e2e/schritte/vorschau-echt.mjs): Die vorsignierte Adresse wird nicht als
# eingebettete Ressource verwendet. Die Vorschau öffnet sie erst nach einem Klick in einem neuen Tab; der Download
# holt beim Klick auf «PDF/A herunterladen» eine eigene Adresse. Alle Daten sind synthetisch; Ablage-Adressen werden
# nie ausgegeben.
@spec-01 @browser @pdf-viewer
Funktionalität: PDF-Vorschau als geprüfter Link, Download nur auf Klick

  Hintergrund:
    Angenommen die PWA spricht mit dem Archiv im Testmodus

  @req-14.6
  Szenario: Der PDF-Link ist sicher und löst ohne Klick keinen Abruf aus
    Angenommen «inhaber» im Mandanten «urinet» hat das PDF «Vorschau-Test.pdf» im Eingangskorb mit dem Titel «Vorschau Test Link»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Dokumentansicht von «Vorschau-Test.pdf» öffne
    Dann sehe ich einen sicheren Link zur PDF-Vorschau
    Und hat die PWA das PDF nicht automatisch abgerufen
    Und hat der Browser keine Datei heruntergeladen

  @req-14.6
  Szenario: Erst der Klick auf «PDF/A herunterladen» löst einen Download aus
    Angenommen «inhaber» im Mandanten «urinet» hat das PDF «Download-Test.pdf» im Eingangskorb mit dem Titel «Vorschau Test download»
    Und ich bin als «inhaber» im Mandanten «urinet» angemeldet
    Wenn ich die Dokumentansicht von «Download-Test.pdf» öffne
    Und ich auf «PDF/A herunterladen» klicke
    Dann hat der Browser eine Datei heruntergeladen
    Und hat die PWA das PDF nicht automatisch abgerufen und die Datei als «pdfa.pdf» mit der ID von «Download-Test.pdf» heruntergeladen
