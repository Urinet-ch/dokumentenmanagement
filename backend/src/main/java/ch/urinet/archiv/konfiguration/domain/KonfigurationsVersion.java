package ch.urinet.archiv.konfiguration.domain;

import java.time.Instant;
import java.util.Objects;

public record KonfigurationsVersion(int version, Instant zeit, String wer, String grund, KonfigurationQuelle quelle,
                                    Konfiguration inhalt, String sha256) {
    public KonfigurationsVersion {
        if (version < 1) {
            throw new IllegalArgumentException("Konfigurationsversion muss positiv sein");
        }
        Objects.requireNonNull(zeit, "zeit");
        Objects.requireNonNull(wer, "wer");
        Objects.requireNonNull(quelle, "quelle");
        Objects.requireNonNull(inhalt, "inhalt");
        Objects.requireNonNull(sha256, "sha256");
    }
}
