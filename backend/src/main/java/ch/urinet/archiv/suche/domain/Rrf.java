package ch.urinet.archiv.suche.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion der Hybridsuche (Spec 02, Req 2.2; design.md «Embeddings und Hybridsuche», Schritt 3):
 * {@code wert = Σ 1 / (60 + rang)} über die Listen, in denen ein Dokument steht, Rang ab 1. Bei Gleichstand entscheidet
 * der Rang im Volltext (ohne Volltext zuletzt), dann die ID; so ist die Reihenfolge deterministisch.
 */
public final class Rrf {

    public static final int K = 60;

    private Rrf() {
    }

    /** Wert eines Dokuments aus seinen Rängen (ab 1); {@code null} heisst: nicht in dieser Liste. */
    public static double wert(Integer rangVolltext, Integer rangVektor) {
        double wert = 0;
        if (rangVolltext != null) {
            wert += 1.0 / (K + rangVolltext);
        }
        if (rangVektor != null) {
            wert += 1.0 / (K + rangVektor);
        }
        return wert;
    }

    /** Vereinigung beider Listen (je in Rangfolge, ohne Doppel), absteigend nach {@link #wert}. */
    public static List<UUID> ordnen(List<UUID> volltext, List<UUID> vektor) {
        Map<UUID, Integer> rangVolltext = raenge(volltext);
        Map<UUID, Integer> rangVektor = raenge(vektor);
        Set<UUID> alle = new LinkedHashSet<>(volltext);
        alle.addAll(vektor);
        Map<UUID, Double> werte = new HashMap<>();
        alle.forEach(id -> werte.put(id, wert(rangVolltext.get(id), rangVektor.get(id))));
        List<UUID> geordnet = new ArrayList<>(alle);
        geordnet.sort(Comparator.<UUID>comparingDouble(werte::get).reversed()
                .thenComparingInt(id -> rangVolltext.getOrDefault(id, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(geordnet);
    }

    /** Die ersten {@code anzahl} aus {@code geordnet} ohne die in {@code ohne} (z. B. exakte Belegnummern). */
    public static List<UUID> auswahl(List<UUID> geordnet, Set<UUID> ohne, int anzahl) {
        return geordnet.stream().filter(id -> !ohne.contains(id)).limit(Math.max(0, anzahl)).toList();
    }

    private static Map<UUID, Integer> raenge(List<UUID> liste) {
        Map<UUID, Integer> raenge = new HashMap<>();
        for (UUID id : liste) {
            raenge.putIfAbsent(id, raenge.size() + 1);
        }
        return raenge;
    }
}
