package ch.urinet.archiv.export.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Fristen eines Jahresexports (Spec 02, Req 7.2; design.md «Jahresexport»): Der Download-Link gilt 24 Stunden ab
 * Fertigstellung; danach ist der Export abgelaufen, und es braucht einen neuen. Das ZIP selbst entfernt der
 * Lebenszyklus des Buckets nach 7 Tagen unter {@code exporte/} (Ablage, {@code Exportdatei}).
 */
public final class Exportfrist {

    public static final Duration DOWNLOAD = Duration.ofHours(24);

    private Exportfrist() {
    }

    public static Instant gueltigBis(Instant fertig) {
        return Objects.requireNonNull(fertig, "fertig").plus(DOWNLOAD);
    }

    public static boolean abgelaufen(Instant fertig, Instant jetzt) {
        return !jetzt.isBefore(gueltigBis(fertig));
    }
}
