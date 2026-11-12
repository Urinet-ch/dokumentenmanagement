package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Werte eines Swiss QR Codes (Req 7.11, design.md «SwissQrLeser»); {@code betrag} und {@code referenz} fehlen, wenn
 * der Code keine trägt. {@link #parse} übernimmt die Regeln des Golden-Laufs ({@code SwissQrLeser.parse}).
 */
public record QrDaten(String iban, BigDecimal betrag, String waehrung, String referenztyp, String referenz,
                      String zahlungsempfaenger) {

    /**
     * Swiss Payments Code 2.0: Zeile 3 IBAN, 5 Name, 18 Betrag, 19 Währung, 27 Referenztyp, 28 Referenz. Nur Payloads
     * mit Kopf {@code SPC} und mindestens 31 Zeilen; ein unlesbarer Betrag macht den ganzen Code ungültig.
     */
    public static Optional<QrDaten> parse(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String[] z = payload.split("\\r?\\n", -1);
        if (z.length < 31 || !"SPC".equals(z[0].strip())) {
            return Optional.empty();
        }
        String betrag = z[18].strip();
        try {
            return Optional.of(new QrDaten(z[3].strip(), betrag.isEmpty() ? null : new BigDecimal(betrag),
                    z[19].strip(), z[27].strip(), z[28].strip().isEmpty() ? null : z[28].strip(), z[5].strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
