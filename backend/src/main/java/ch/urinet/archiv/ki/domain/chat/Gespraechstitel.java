package ch.urinet.archiv.ki.domain.chat;

/**
 * Titel eines neuen Gesprächs (design.md «Vertrag Welle 7», Verlauf): die erste Frage ohne KI-Aufruf, Leerraum
 * zusammengefasst, höchstens {@value #MAX} Zeichen (Unicode-Codepunkte). Eine längere Frage wird an der letzten
 * Wortgrenze vor der Grenze gekürzt und endet mit «…»; ein einzelnes Wort ohne Grenze wird hart gekürzt.
 */
public final class Gespraechstitel {

    public static final int MAX = 80;
    private static final String AUSLASSUNG = "…";

    private Gespraechstitel() {
    }

    public static String aus(String frage) {
        String text = frage.strip().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) <= MAX) {
            return text;
        }
        int ende = text.offsetByCodePoints(0, MAX - 1);
        String kopf = text.substring(0, ende);
        int leer = kopf.lastIndexOf(' ');
        if (text.charAt(ende) != ' ' && leer > 0) {
            kopf = kopf.substring(0, leer);
        }
        return kopf.stripTrailing() + AUSLASSUNG;
    }
}
