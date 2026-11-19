package ch.urinet.archiv.wissen.application;

import java.util.Optional;
import java.util.UUID;

public interface QuellenPruefungPort {
    Optional<SeitenStand> seiteSperren(UUID seiteId);

    void bestaetigen(UUID seiteId, SeitenStand stand, String benutzer);

    record SeitenStand(int version, long quellenRevision, boolean quellePruefen) {
    }
}
