package ch.urinet.archiv.kontakt.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Kontakt mit der Namensähnlichkeit aus der Datenbank ({@code pg_trgm}, 0 bis 1). */
public record PartnerKandidat(UUID kontaktId, BigDecimal aehnlichkeit) {

    public PartnerKandidat {
        Objects.requireNonNull(kontaktId, "kontaktId");
        Objects.requireNonNull(aehnlichkeit, "aehnlichkeit");
    }
}
