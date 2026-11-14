package ch.urinet.archiv.suche.domain;

import java.util.Objects;

/**
 * Treffer der Hybridsuche (Spec 02, Req 2.2): das Dokument wie in der Volltextsuche und die {@link Fundstelle} aus dem
 * Vektor-Teil; {@code fundstelle} ist {@code null} bei Treffern nur aus dem Volltext und im Rückfall.
 */
public record Hybridtreffer(Suchtreffer treffer, Fundstelle fundstelle) {

    public Hybridtreffer {
        Objects.requireNonNull(treffer, "treffer");
    }
}
