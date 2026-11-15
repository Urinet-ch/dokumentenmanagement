package ch.urinet.archiv.ablage.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Reine Berechnung der zu setzenden Object-Lock-Sperre bei einer Wiederherstellung. */
public record Sperrplan(Optional<Sperrmodus> modus, Optional<Instant> bis, boolean gekappt) {

    public Sperrplan {
        Objects.requireNonNull(modus, "modus");
        Objects.requireNonNull(bis, "bis");
        if (modus.isPresent() != bis.isPresent()) {
            throw new IllegalArgumentException("Sperrmodus und Frist müssen zusammen angegeben werden");
        }
    }

    public static Sperrplan berechnen(Optional<SicherungsSperre> ist, boolean kappen, Instant jetzt) {
        Objects.requireNonNull(ist, "ist");
        Objects.requireNonNull(jetzt, "jetzt");
        if (ist.isEmpty() || !ist.get().retainUntil().isAfter(jetzt)) {
            return new Sperrplan(Optional.empty(), Optional.empty(), false);
        }
        SicherungsSperre sperre = ist.get();
        Sperrmodus modus = Sperrmodus.valueOf(sperre.modus());
        if (!kappen) {
            return new Sperrplan(Optional.of(modus), Optional.of(sperre.retainUntil()), false);
        }
        Instant morgen = jetzt.plus(Duration.ofDays(1));
        boolean gekappt = morgen.isBefore(sperre.retainUntil());
        return new Sperrplan(Optional.of(modus), Optional.of(gekappt ? morgen : sperre.retainUntil()), gekappt);
    }
}
