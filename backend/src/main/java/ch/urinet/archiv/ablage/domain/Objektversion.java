package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.Objects;

/** Eine Version oder Löschmarke im Object Storage (Schlüssel und Versions-ID, Req 15.10). */
public record Objektversion(String schluessel, String versionId, boolean loeschmarke, Instant lastModified,
                            long groesse) {

    public Objektversion {
        Objects.requireNonNull(schluessel, "schluessel");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(lastModified, "lastModified");
        if (groesse < 0) {
            throw new IllegalArgumentException("negative Objektgrösse");
        }
    }

    public Objektversion(String schluessel, String versionId, boolean loeschmarke) {
        this(schluessel, versionId, loeschmarke, Instant.EPOCH, 0);
    }
}
