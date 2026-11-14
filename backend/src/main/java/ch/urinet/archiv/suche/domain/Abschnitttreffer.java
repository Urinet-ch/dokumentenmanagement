package ch.urinet.archiv.suche.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Bester Abschnitt eines Dokuments im Vektor-Teil der Hybridsuche (Spec 02, design.md «Embeddings und Hybridsuche»,
 * Schritt 2): Abstand nach Kosinus (kleiner ist näher), Text mit Kopfzeile für den Reranker und Inhalt ohne Kopfzeile
 * für den Auszug.
 */
public record Abschnitttreffer(UUID dokumentId, int nr, Integer seiteVon, Integer seiteBis, double abstand, String text,
                               String inhalt) {

    public Abschnitttreffer {
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(inhalt, "inhalt");
    }

    public Fundstelle fundstelle() {
        return new Fundstelle(nr, seiteVon, seiteBis);
    }
}
