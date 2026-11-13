package ch.urinet.archiv.protokoll.domain;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Eine archivierte Datei im Manifest (Req 15.1): Dokument, Variante ({@code original}, {@code arbeitsfassung},
 * {@code pdfa}), Nummer, SHA-256 und als Zusatz die S3-Versions-ID.
 */
public record ManifestEintrag(UUID dokument, String variante, int nr, String sha256, String s3Version) {

    public static final Set<String> VARIANTEN = Set.of("original", "arbeitsfassung", "pdfa");

    /** Feste Reihenfolge im Manifest: Dokument, Variante, Nummer. */
    public static final Comparator<ManifestEintrag> REIHENFOLGE = Comparator
            .comparing((ManifestEintrag e) -> e.dokument().toString())
            .thenComparing(ManifestEintrag::variante)
            .thenComparingInt(ManifestEintrag::nr);

    public ManifestEintrag {
        Objects.requireNonNull(dokument, "dokument");
        if (!VARIANTEN.contains(variante)) {
            throw new IllegalArgumentException("Unbekannte Variante im Manifest: " + variante);
        }
        if (nr < 1) {
            throw new IllegalArgumentException("nr ist kleiner als 1");
        }
        Objects.requireNonNull(sha256, "sha256");
        Sha256.gueltig(sha256, "sha256");
    }
}
