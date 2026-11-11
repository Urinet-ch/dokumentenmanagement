package ch.urinet.archiv.driveimport;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Schlussbericht eines Laufs (Req 12.3, 12.5, 12.6, 12.7). Jede gefundene Datei landet in genau einem Zähler:
 * gefunden = übertragen + schon vorhanden + übersprungen + abgelehnt; im Trockenlauf
 * gefunden = würde übertragen + übersprungen.
 */
final class Bericht {

    record Eintrag(String pfad, String grund) {
    }

    private final boolean trockenlauf;
    private int gefunden;
    private int uebertragen;
    private int schonVorhanden;
    private int uebersprungen;
    private int abgelehnt;
    private final List<String> pdfExport = new ArrayList<>();
    private final List<Eintrag> nichtUebernommen = new ArrayList<>();

    Bericht(boolean trockenlauf) {
        this.trockenlauf = trockenlauf;
    }

    void uebertragen() {
        gefunden++;
        uebertragen++;
    }

    void schonVorhanden() {
        gefunden++;
        schonVorhanden++;
    }

    void googleFormat(String pfad) {
        gefunden++;
        uebersprungen++;
        pdfExport.add(pfad);
    }

    void uebersprungen(String pfad, String grund) {
        gefunden++;
        uebersprungen++;
        nichtUebernommen.add(new Eintrag(pfad, grund));
    }

    void abgelehnt(String pfad, String grund) {
        gefunden++;
        abgelehnt++;
        nichtUebernommen.add(new Eintrag(pfad, grund));
    }

    int gefunden() {
        return gefunden;
    }

    int abgelehnt() {
        return abgelehnt;
    }

    boolean gehtAuf() {
        return gefunden == uebertragen + schonVorhanden + uebersprungen + abgelehnt;
    }

    void ausgeben(PrintWriter aus) {
        aus.println();
        aus.println(trockenlauf ? "Schlussbericht (Trockenlauf: nichts übertragen)" : "Schlussbericht");
        aus.println();
        aus.println("Bitte als PDF exportieren (" + pdfExport.size() + "):");
        pdfExport.forEach(pfad -> aus.println("  " + pfad));
        aus.println();
        aus.println("Nicht übernommen (" + nichtUebernommen.size() + "):");
        nichtUebernommen.forEach(e -> aus.println("  " + e.pfad() + " – " + e.grund()));
        aus.println();
        aus.println("Gefunden:        " + gefunden);
        if (trockenlauf) {
            aus.println("Würde übertragen: " + uebertragen);
            aus.println("Übersprungen:    " + uebersprungen);
            aus.println("Kontrolle: " + gefunden + " = " + uebertragen + " + " + uebersprungen
                    + (gehtAuf() ? " (geht auf)" : " (GEHT NICHT AUF)"));
        } else {
            aus.println("Übertragen:      " + uebertragen);
            aus.println("Schon vorhanden: " + schonVorhanden);
            aus.println("Übersprungen:    " + uebersprungen);
            aus.println("Abgelehnt:       " + abgelehnt);
            aus.println("Kontrolle: " + gefunden + " = " + uebertragen + " + " + schonVorhanden + " + "
                    + uebersprungen + " + " + abgelehnt + (gehtAuf() ? " (geht auf)" : " (GEHT NICHT AUF)"));
        }
        aus.flush();
    }
}
