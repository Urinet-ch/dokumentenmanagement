package ch.urinet.archiv.ablage.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entscheid des Prüflaufs über die Sperre einer unbefristeten Datei oder eines Manifests (Req 15.9, 16.24): Eine aktive
 * Sperre mit weniger als 12 Monaten Restlaufzeit wird um 10 Jahre verlängert, gekappt auf die Höchstfrist
 * ({@link Sperrfrist#verlaengert}); in Stufe P nur gezählt, nie verlängert. {@code bis} ist nur bei
 * {@link Art#VERLAENGERN} gesetzt.
 */
public record Sperrpflege(Art art, Optional<Instant> bis) {

    public enum Art {
        /** Keine aktive Sperre; der Prüflauf setzt keine neue. */
        OHNE_SPERRE,
        /** Restlaufzeit 12 Monate oder mehr. */
        AUSREICHEND,
        /** Stufe P: wäre zu verlängern, wird nur gezählt. */
        NUR_ZAEHLEN,
        VERLAENGERN,
        /** Steht schon an der Höchstfrist (Stufe A); länger geht nicht. */
        GEKAPPT
    }

    public Sperrpflege {
        Objects.requireNonNull(art, "art");
        Objects.requireNonNull(bis, "bis");
        if ((art == Art.VERLAENGERN) != bis.isPresent()) {
            throw new IllegalArgumentException("bis nur bei VERLAENGERN");
        }
    }

    public static Sperrpflege entscheiden(Stufe stufe, Optional<Instant> bisher, Instant jetzt,
                                          Optional<Duration> hoechstfrist) {
        Objects.requireNonNull(stufe, "stufe");
        if (bisher.isEmpty() || !bisher.get().isAfter(jetzt)) {
            return new Sperrpflege(Art.OHNE_SPERRE, Optional.empty());
        }
        Instant grenze = jetzt.atZone(Sperrfrist.ZUERICH).plusMonths(Sperrfrist.VERLAENGERN_UNTER_MONATEN).toInstant();
        if (!bisher.get().isBefore(grenze)) {
            return new Sperrpflege(Art.AUSREICHEND, Optional.empty());
        }
        if (stufe == Stufe.P) {
            return new Sperrpflege(Art.NUR_ZAEHLEN, Optional.empty());
        }
        return Sperrfrist.verlaengert(bisher.get(), jetzt, hoechstfrist)
                .map(bis -> new Sperrpflege(Art.VERLAENGERN, Optional.of(bis)))
                .orElseGet(() -> new Sperrpflege(Art.GEKAPPT, Optional.empty()));
    }
}
