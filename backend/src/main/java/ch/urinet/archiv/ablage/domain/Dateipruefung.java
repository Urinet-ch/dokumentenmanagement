package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Ergebnis des Lesens einer Version im Prüflauf (Req 15.5): SHA-256 des gelesenen Inhalts, Grösse und das
 * {@code retainUntil} der Version, auch wenn es schon abgelaufen ist.
 */
public record Dateipruefung(String sha256, long groesse, Optional<Instant> sperreBis) {

    public Dateipruefung {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(sperreBis, "sperreBis");
    }
}
