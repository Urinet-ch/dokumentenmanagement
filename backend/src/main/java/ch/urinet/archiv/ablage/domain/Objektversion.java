package ch.urinet.archiv.ablage.domain;

import java.util.Objects;

/** Eine Version oder Löschmarke im Object Storage (Schlüssel und Versions-ID, Req 15.10). */
public record Objektversion(String schluessel, String versionId, boolean loeschmarke) {

    public Objektversion {
        Objects.requireNonNull(schluessel, "schluessel");
        Objects.requireNonNull(versionId, "versionId");
    }
}
