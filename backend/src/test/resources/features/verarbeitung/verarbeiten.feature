# language: de
@spec-01
Funktionalität: Verarbeitung eines Dokuments (VERARBEITEN)
  Der Worker macht aus jedem neuen Dokument ein durchsuchbares PDF/A-2b mit Vorschaubildern und Volltext und reiht
  danach den KI-Vorschlag ein (Aufgabe 2.6). Die Werkzeuge laufen im Image der Stufe «werkzeuge», die Dateien liegen
  in MinIO. Bis Aufgabe 3.2 steht für «KI_VORSCHLAG» ein Test-Handler unter src/test, der nur die Dokument-ID merkt.
  Nur synthetische Testdateien (Req 16.15).

  Hintergrund:
    Angenommen die Verarbeitung ist bereit

  @req-5.1 @req-5.3
  Szenario: Ein schief fotografierter Beleg wird zu einem durchsuchbaren PDF/A-2b mit Vorschau
    Angenommen ein Dokument mit einem schiefen Foto «Rechnungen Nummer 4711»
    Wenn der Worker die Aufträge bearbeitet
    Dann ist das Dokument «VERARBEITUNG» mit 1 Seite
    Und die PDF/A-Fassung ist gültiges PDF/A-2b und stimmt mit der gespeicherten Prüfsumme überein
    Und es liegt je Seite ein Vorschaubild
    Und der Volltext enthält «4711»
    Und die deutsche Volltextsuche nach «Rechnung» findet das Dokument
    Und für das Dokument lief genau ein Auftrag «KI_VORSCHLAG»
    Und die Dateien aus dem Eingang sind unverändert

  @req-5.1
  Szenario: Ein HEIC-Foto wird zu einem PDF/A-2b
    Angenommen ein Dokument mit einem HEIC-Foto
    Wenn der Worker die Aufträge bearbeitet
    Dann ist das Dokument «VERARBEITUNG» mit 1 Seite
    Und die PDF/A-Fassung ist gültiges PDF/A-2b und stimmt mit der gespeicherten Prüfsumme überein
    Und der Volltext enthält «Quittung»

  @req-5.2
  # Dass OCRmyPDF dabei «--skip-text» erhält, zeigt OcrMyPdfAdapterTest (Teil a); hier zählt das Ergebnis am Dokument.
  Szenario: Ein PDF mit Textebene erhält eine PDF/A-Fassung mit dem vorhandenen Text
    Angenommen ein Dokument mit einem digitalen PDF «Digitale Rechnung Textebene vorhanden»
    Wenn der Worker die Aufträge bearbeitet
    Dann ist das Dokument «VERARBEITUNG» mit 1 Seite
    Und die PDF/A-Fassung ist gültiges PDF/A-2b und stimmt mit der gespeicherten Prüfsumme überein
    Und der Volltext enthält «Textebene vorhanden»

  @req-5.4
  Szenario: Nach drei Fehlversuchen ist das Dokument FEHLER mit Fehlertext, das Original bleibt
    Angenommen ein Dokument mit einem schiefen Foto «Beleg mit falscher Prüfsumme»
    Und die gespeicherte Prüfsumme des Originals passt nicht zur Datei im Object Storage
    Wenn der Worker die Aufträge bearbeitet
    Dann ist der Auftrag «VERARBEITEN» nach 1 Versuch wieder offen und das Dokument «VERARBEITUNG»
    Wenn der Auftrag «VERARBEITEN» zweimal wieder fällig wird und der Worker ihn bearbeitet
    Dann ist der Auftrag «VERARBEITEN» «FEHLER» nach 3 Versuchen
    Und ist das Dokument «FEHLER» mit einem Fehlertext, der die Fehler-ID des Auftrags nennt
    Und für das Dokument lief kein Auftrag «KI_VORSCHLAG»
    Und die Dateien aus dem Eingang sind unverändert

  @req-5.8
  Szenario: Ein signiertes PDF erhält eine PDF/A-Fassung, das Original bleibt unverändert
    Angenommen ein Dokument mit einem signierten PDF
    Wenn der Worker die Aufträge bearbeitet
    Dann ist das Dokument «VERARBEITUNG» mit 1 Seite
    Und die PDF/A-Fassung ist gültiges PDF/A-2b und stimmt mit der gespeicherten Prüfsumme überein
    Und die Dateien aus dem Eingang sind unverändert

  @req-5.9
  Szenario: Ein verschlüsseltes PDF landet mit «pdfa_fehlt» und Fehlertext im Eingangskorb
    Angenommen ein Dokument mit einem verschlüsselten PDF
    Wenn der Worker die Aufträge bearbeitet
    Dann ist das Dokument «EINGANGSKORB» ohne PDF/A mit Vermerk «pdfa_fehlt» und Fehlertext «verschlüsselt»
    Und für das Dokument lief genau ein Auftrag «KI_VORSCHLAG»
    Und die Dateien aus dem Eingang sind unverändert

  @req-5.10
  Szenario: Aus der Handy-Erfassung entsteht die PDF/A-Fassung aus den Arbeitsfassungen
    Angenommen ein Dokument aus der Handy-Erfassung mit 2 Seiten
    Wenn der Worker die Aufträge bearbeitet
    Dann ist das Dokument «VERARBEITUNG» mit 2 Seiten
    Und die PDF/A-Fassung ist gültiges PDF/A-2b und stimmt mit der gespeicherten Prüfsumme überein
    Und es liegt je Seite ein Vorschaubild
    Und der Volltext enthält «Arbeitsfassung» und nicht «Rohbild»
    Und die Dateien aus dem Eingang sind unverändert
