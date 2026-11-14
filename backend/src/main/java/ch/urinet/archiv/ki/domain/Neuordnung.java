package ch.urinet.archiv.ki.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Prüft die Antwort des Rerankers (Spec 02, Aufgabe 6.4; Spike 0.3, Abschnitt 6): Sie ist nur gültig, wenn sie genau
 * {@code topN} Indizes nennt, jeder auf einen gesendeten Text zeigt und keiner doppelt vorkommt.
 */
public final class Neuordnung {

    private Neuordnung() {
    }

    /**
     * @param indizes Indizes der Antwort in der Reihenfolge des Modells
     * @param texte   Zahl der gesendeten Texte
     * @param topN    verlangte Zahl ({@code top_n})
     */
    public static boolean gueltig(List<Integer> indizes, int texte, int topN) {
        if (indizes == null || indizes.size() != topN) {
            return false;
        }
        Set<Integer> gesehen = new HashSet<>();
        for (Integer index : indizes) {
            if (index == null || index < 0 || index >= texte || !gesehen.add(index)) {
                return false;
            }
        }
        return true;
    }
}
