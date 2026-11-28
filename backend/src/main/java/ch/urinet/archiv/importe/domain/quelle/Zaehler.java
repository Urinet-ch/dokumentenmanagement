package ch.urinet.archiv.importe.domain.quelle;

/**
 * Zähler eines Quelllaufs (Spec 09, 10.3). Sie gehen auf, wenn {@code gefunden = neu + dublettePruefsumme +
 * uebersprungen + fehler}; fachliche Dubletten zählen zusätzlich bei den neuen Dokumenten und gibt es im Trockenlauf
 * nicht (10.5).
 */
public record Zaehler(int gefunden, int neu, int dublettePruefsumme, int dubletteFachlich, int uebersprungen,
                      int fehler) {

    public static final Zaehler NULL = new Zaehler(0, 0, 0, 0, 0, 0);

    public Zaehler {
        if (gefunden < 0 || neu < 0 || dublettePruefsumme < 0 || dubletteFachlich < 0 || uebersprungen < 0
                || fehler < 0) {
            throw new IllegalArgumentException("Zähler dürfen nicht negativ sein");
        }
    }

    public boolean gehtAuf() {
        return gefunden == neu + dublettePruefsumme + uebersprungen + fehler && dubletteFachlich <= neu;
    }

    /** Gültig als Meldung eines Werkzeugs für einen Lauf der Art {@code art}. */
    public boolean gueltigFuer(LaufArt art) {
        return gehtAuf() && (art != LaufArt.TROCKEN || dubletteFachlich == 0);
    }
}
