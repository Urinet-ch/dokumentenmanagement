package ch.urinet.archiv.kontakt.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Verkaufschance aus 21.CRM (design.md Data Models, {@code verkaufschance}; Req 13.9): gehört über {@code kontaktId}
 * zu einem Kontakt; {@code importLauf} ist der Lauf, der sie zuletzt geschrieben hat. Betrag als {@code NUMERIC(12,2)}
 * mit Währung (tech.md Regel 9).
 */
public record Verkaufschance(UUID id, String crmId, UUID kontaktId, String titel, String phase, BigDecimal betrag,
                             String waehrung, LocalDate datum, UUID importLauf) {

    public static final String CHF = "CHF";

    public Verkaufschance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kontaktId, "kontaktId");
        waehrung = waehrung == null ? CHF : waehrung;
    }
}
