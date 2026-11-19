package ch.urinet.archiv.suche.domain;

/** Optionale Aktualitätskorrektur für die Hybridsuche. */
public record Aktualitaetsregel(boolean aktiviert, double faktor, int halbwertzeitTage) {

    public static final Aktualitaetsregel AUS = new Aktualitaetsregel(false, 0.05, 365);

    public Aktualitaetsregel {
        if (!Double.isFinite(faktor) || faktor < 0 || faktor > 1) {
            throw new IllegalArgumentException("faktor muss zwischen 0 und 1 liegen");
        }
        if (halbwertzeitTage < 1) {
            throw new IllegalArgumentException("halbwertzeitTage muss positiv sein");
        }
    }
}
