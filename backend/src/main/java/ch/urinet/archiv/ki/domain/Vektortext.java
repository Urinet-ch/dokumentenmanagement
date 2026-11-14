package ch.urinet.archiv.ki.domain;

/**
 * Text eines Vektors für PostgreSQL ({@code [x,y,…]}, Typ {@code public.halfvec}): Schreiben der Abschnitte (Spec 02,
 * Aufgabe 6.3) und Abfrage der Hybridsuche (Aufgabe 6.4) nutzen dieselbe Form.
 */
public final class Vektortext {

    private Vektortext() {
    }

    public static String text(float[] vektor) {
        StringBuilder text = new StringBuilder(vektor.length * 12).append('[');
        for (int i = 0; i < vektor.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vektor[i]);
        }
        return text.append(']').toString();
    }
}
