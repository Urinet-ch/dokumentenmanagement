package ch.urinet.archiv.dokument.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Gespeicherte Finanzangaben eines Dokuments ({@code dokument_finanz}); Betrag als {@link BigDecimal} mit Währung
 * (Regel 9). Zahlstatus und AbaNinja-Felder gehören nicht dazu (Regel 8).
 */
public record DokumentFinanz(String belegnummer, String richtung, BigDecimal betrag, String waehrung,
                             LocalDate faelligkeit, String qrReferenz, String konto) {
}
