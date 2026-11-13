package ch.urinet.archiv.ki.domain;

import java.util.Objects;

/**
 * Abschnitt eines Dokuments für die semantische Suche (Spec 02, Req 2.1; design.md «Embeddings und Hybridsuche»).
 *
 * @param nr       Nummer im Dokument, ab 1
 * @param text     Kopfzeile (Titel, Art, Partner) und Inhalt, so wie er eingebettet wird
 * @param seiteVon erste PDF-Seite (1-basiert) oder {@code null} ohne Seitenbereich (Spec 03, Req 5.7)
 * @param seiteBis letzte PDF-Seite oder {@code null}
 */
public record Chunk(int nr, String text, Integer seiteVon, Integer seiteBis) {

    public Chunk {
        Objects.requireNonNull(text, "text");
        if (nr < 1) {
            throw new IllegalArgumentException("Chunk-Nummer ab 1");
        }
        if ((seiteVon == null) != (seiteBis == null)
                || seiteVon != null && (seiteVon < 1 || seiteBis < seiteVon)) {
            throw new IllegalArgumentException("Ungültiger Seitenbereich");
        }
    }
}
