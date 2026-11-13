# language: de
# Seite «Export» der PWA (Spec 02, Aufgabe 6.5) gegen eine Attrappe der Export-Schnittstelle
# (frontend/e2e/schritte/export.mjs): Im Testmodus läuft kein Worker, der Export würde nie fertig. Die Attrappe
# antwortet wie ExportResource (OFFEN, nach einer Abfrage FERTIG, Adresse nur bei GET /exporte/{id}) und hält fest,
# was die PWA sendet. Das Verhalten des Servers belegen die Java-Szenarien in jahresexport.feature. Alle Daten sind
# synthetisch.
@spec-02 @browser @attrappe
Funktionalität: Jahresexport in der PWA

  @req-7.1 @req-7.2
  Szenariogrundriss: Jahr wählen, Export starten, Status verfolgen und herunterladen (<breite> px)
    Angenommen ein Bildschirm mit <breite> Pixel Breite
    Und die Export-Schnittstelle ist eine Attrappe
    Und ich bin als «<rolle>» im Mandanten «urinet» angemeldet
    Und ich im Menü «Jahresexport» wähle
    Wenn ich das Geschäftsjahr «2025» wähle und auf «Export starten» klicke
    Dann hat das Archiv die Anforderung für das Geschäftsjahr 2025 erhalten
    Und sehe ich den Export 2025 mit Status «In Arbeit»
    Und sehe ich den Export 2025 mit Status «Bereit»
    Und ragt nichts über den Bildschirm hinaus
    Wenn ich auf «Export 2025 herunterladen» klicke
    Dann lädt der Browser «jahresexport-urinet-2025.zip» herunter

    Beispiele:
      | breite | rolle       |
      | 375    | inhaber     |
      | 1280   | treuhaender |

  @req-7.5
  Szenario: Ohne Recht auf den Export zeigt die PWA weder Menüpunkt noch Seite
    Angenommen die Export-Schnittstelle ist eine Attrappe
    Und Keycloak kennt mich als «mitarbeiter» im Mandanten «urinet»
    Wenn ich die Seite «/export» öffne
    Und ich mich anmelde
    Dann bin ich auf der Seite «/»
    Und sehe ich im Menü genau «Erfassen, Eingangskorb, Suche»
    Und hat die PWA die Export-Schnittstelle nicht aufgerufen
