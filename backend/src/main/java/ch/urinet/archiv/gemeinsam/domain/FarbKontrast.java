package ch.urinet.archiv.gemeinsam.domain;

/** Kontrastverhältnis nach WCAG für zwei RGB-Farben im Format {@code #rrggbb}. */
public final class FarbKontrast {
    private FarbKontrast() {
    }

    public static double zwischen(String farbeA, String farbeB) {
        double a = luminanz(farbeA);
        double b = luminanz(farbeB);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminanz(String farbe) {
        int rot = Integer.parseInt(farbe.substring(1, 3), 16);
        int gruen = Integer.parseInt(farbe.substring(3, 5), 16);
        int blau = Integer.parseInt(farbe.substring(5, 7), 16);
        return 0.2126 * linear(rot) + 0.7152 * linear(gruen) + 0.0722 * linear(blau);
    }

    private static double linear(int wert) {
        double kanal = wert / 255.0;
        return kanal <= 0.04045 ? kanal / 12.92 : Math.pow((kanal + 0.055) / 1.055, 2.4);
    }
}
