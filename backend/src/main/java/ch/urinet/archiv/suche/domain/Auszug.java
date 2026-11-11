package ch.urinet.archiv.suche.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Zerlegt das Ergebnis von {@code ts_headline} in {@link AuszugTeil}e (Req 14.4). Die Abfrage markiert Treffer mit den
 * Steuerzeichen {@link #START} und {@link #STOP} und trennt Fragmente mit {@link #TRENNER}; diese Zeichen entfernt sie
 * vorher aus dem Text, darum sind sie eindeutig. So entsteht kein HTML, und Text aus Dokumenten kann nichts einschleusen.
 */
public final class Auszug {

    public static final char START = '\u0002';
    public static final char STOP = '\u0003';
    public static final char TRENNER = '\u001f';
    /** Zwischen zwei Fragmenten der Textstelle. */
    public static final String AUSLASSUNG = " … ";

    private Auszug() {
    }

    /** {@code null} oder leer ergibt eine leere Liste; benachbarte Stücke gleicher Art werden zusammengefasst. */
    public static List<AuszugTeil> zerlegen(String markiert) {
        List<AuszugTeil> teile = new ArrayList<>();
        if (markiert == null || markiert.isEmpty()) {
            return teile;
        }
        StringBuilder stueck = new StringBuilder();
        boolean treffer = false;
        for (int i = 0; i < markiert.length(); i++) {
            char zeichen = markiert.charAt(i);
            if (zeichen == START || zeichen == STOP) {
                anhaengen(teile, stueck, treffer);
                treffer = zeichen == START;
            } else if (zeichen == TRENNER) {
                anhaengen(teile, stueck, treffer);
                treffer = false;
                stueck.append(AUSLASSUNG);
            } else {
                stueck.append(zeichen);
            }
        }
        anhaengen(teile, stueck, treffer);
        return List.copyOf(teile);
    }

    private static void anhaengen(List<AuszugTeil> teile, StringBuilder stueck, boolean treffer) {
        if (stueck.isEmpty()) {
            return;
        }
        if (!teile.isEmpty() && teile.getLast().treffer() == treffer) {
            teile.set(teile.size() - 1, new AuszugTeil(teile.getLast().text() + stueck, treffer));
        } else {
            teile.add(new AuszugTeil(stueck.toString(), treffer));
        }
        stueck.setLength(0);
    }
}
