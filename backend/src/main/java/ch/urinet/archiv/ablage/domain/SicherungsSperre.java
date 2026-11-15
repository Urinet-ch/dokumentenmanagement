package ch.urinet.archiv.ablage.domain;

import java.time.Instant;
import java.util.Objects;

/** Object-Lock-Angaben einer gesicherten Version. */
public record SicherungsSperre(String modus, Instant retainUntil) {

    public SicherungsSperre {
        Objects.requireNonNull(modus, "modus");
        Objects.requireNonNull(retainUntil, "retainUntil");
        if (!modus.equals("GOVERNANCE") && !modus.equals("COMPLIANCE")) {
            throw new IllegalArgumentException("Ungültiger Sperrmodus");
        }
    }
}
