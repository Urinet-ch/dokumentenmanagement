package ch.urinet.archiv.suche.domain;

import java.util.List;

/**
 * Ergebnis der Hybridsuche (Spec 02, Req 2.2, 2.3, 2.7): höchstens {@code k} Treffer in der Reihenfolge des Rerankers,
 * exakte Belegnummern zuerst; mit {@code degradiert} die besten Volltexttreffer ohne Fundstelle (Rückfall).
 */
public record Hybridergebnis(List<Hybridtreffer> treffer, boolean degradiert) {

    public Hybridergebnis {
        treffer = List.copyOf(treffer);
    }
}
