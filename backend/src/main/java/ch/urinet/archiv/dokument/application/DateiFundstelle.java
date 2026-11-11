package ch.urinet.archiv.dokument.application;

import java.util.Objects;

/** Gespeicherter Pfad (mit Präfix {@code eingang/} oder {@code archiv/}) und S3-Versions-ID einer Dokumentdatei. */
public record DateiFundstelle(String pfad, String versionId) {

    public DateiFundstelle {
        Objects.requireNonNull(pfad, "pfad");
        Objects.requireNonNull(versionId, "versionId");
    }
}
