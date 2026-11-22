package ch.urinet.archiv.driveimport;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Schlussbericht eines Laufs (Req 12.3, 12.5, 12.6, 12.7). Jede gefundene Datei landet in genau einem Zähler:
 * gefunden = übertragen + schon vorhanden + übersprungen + abgelehnt; im Trockenlauf
 * gefunden = würde übertragen + übersprungen + abgelehnt (nicht lesbar). «Gefunden» ist die Zahl der Dateien aus dem
 * Durchsuchen, nicht die Summe der Zähler; die Kontrolle deckt so eine Datei auf, die keinen Zähler erreicht hat.
 */
final class Bericht {

    record Eintrag(String pfad, String grund) {
    }

    private record ReiheJahr(String art, int jahr) {
    }

    private static final class AltbestandGruppe {
        private int dateien;
        private int ohneAusgabe;
        private final TreeSet<Integer> ausgaben = new TreeSet<>();
    }

    private final boolean trockenlauf;
    private final boolean altbestandModus;
    private final int gefunden;
    private int uebertragen;
    private int schonVorhanden;
    private int uebersprungen;
    private int abgelehnt;
    private final List<String> pdfExport = new ArrayList<>();
    private final List<Eintrag> nichtUebernommen = new ArrayList<>();
    private final Map<ReiheJahr, AltbestandGruppe> altbestand = new TreeMap<>(Comparator
            .comparing(ReiheJahr::art).thenComparingInt(ReiheJahr::jahr));

    Bericht(boolean trockenlauf, boolean altbestandModus, int gefunden) {
        this.trockenlauf = trockenlauf;
        this.altbestandModus = altbestandModus;
        this.gefunden = gefunden;
    }

    void altbestand(AltbestandPfad pfad) {
        AltbestandGruppe gruppe = altbestand.computeIfAbsent(new ReiheJahr(pfad.art(), pfad.jahr()),
                ignored -> new AltbestandGruppe());
        gruppe.dateien++;
        if (pfad.ausgabe() == null) {
            gruppe.ohneAusgabe++;
        } else {
            gruppe.ausgaben.add(pfad.ausgabe());
        }
    }

    void uebertragen() {
        uebertragen++;
    }

    void schonVorhanden() {
        schonVorhanden++;
    }

    void googleFormat(String pfad) {
        uebersprungen++;
        pdfExport.add(pfad);
    }

    void uebersprungen(String pfad, String grund) {
        uebersprungen++;
        nichtUebernommen.add(new Eintrag(pfad, grund));
    }

    void abgelehnt(String pfad, String grund) {
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
        if (altbestandModus) {
            aus.println("Altbestand nach Reihe und Jahr (Dateien im Quellordner):");
            altbestand.forEach((schluessel, gruppe) -> aus.println("  " + schluessel.art() + " / "
                    + schluessel.jahr() + ": " + gruppe.dateien + " Dateien; Ausgabe-Nr. erkannt: "
                    + gruppe.ausgaben + "; nicht erkannt: " + gruppe.ohneAusgabe));
            aus.println("Lücken: nicht geprüft; massgeblich ist der serverseitige Lückenbericht.");
            aus.println();
        }
        aus.println("Gefunden:        " + gefunden);
        if (trockenlauf) {
            aus.println("Würde übertragen: " + uebertragen);
            aus.println("Übersprungen:    " + uebersprungen);
            aus.println("Abgelehnt:       " + abgelehnt);
            aus.println("Kontrolle: " + gefunden + " = " + uebertragen + " + " + uebersprungen + " + " + abgelehnt
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
