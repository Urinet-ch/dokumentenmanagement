package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Werte eines Swiss QR Codes (Req 7.11, design.md «SwissQrLeser»); {@code betrag} und {@code referenz} fehlen, wenn
 * der Code keine trägt. {@link #parse} übernimmt die Regeln des Golden-Laufs ({@code SwissQrLeser.parse}) und prüft
 * zusätzlich den Betrag: {@code betragUngueltig} heisst, der Code trägt einen Betrag ausserhalb des Formats; der Betrag
 * bleibt dann leer und gilt als unsicher.
 */
public record QrDaten(String iban, BigDecimal betrag, String waehrung, String referenztyp, String referenz,
                      String zahlungsempfaenger, boolean betragUngueltig) {

    /**
     * Swiss Payments Code 2.0, Betrag: höchstens 12 Zeichen, nur Ziffern mit Punkt, bis 999'999'999.99. Strenger als das
     * Format wäre «genau zwei Nachkommastellen»; angenommen wird wie im Golden-Lauf auch ein Betrag ohne oder mit einer.
     * So kommt nie ein Exponent ({@code 1E300000000}) an {@code BigDecimal} vorbei.
     */
    private static final Pattern BETRAG = Pattern.compile("\\d{1,9}(\\.\\d{1,2})?");

    public QrDaten(String iban, BigDecimal betrag, String waehrung, String referenztyp, String referenz,
                   String zahlungsempfaenger) {
        this(iban, betrag, waehrung, referenztyp, referenz, zahlungsempfaenger, false);
    }

    /**
     * Swiss Payments Code 2.0: Zeile 3 IBAN, 5 Name, 18 Betrag, 19 Währung, 27 Referenztyp, 28 Referenz. Nur Payloads
     * mit Kopf {@code SPC} und mindestens 31 Zeilen; ein Betrag ausserhalb des Formats bleibt leer
     * ({@code betragUngueltig}), die übrigen Werte gelten.
     */
    public static Optional<QrDaten> parse(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String[] z = payload.split("\\r?\\n", -1);
        if (z.length < 31 || !"SPC".equals(z[0].strip())) {
            return Optional.empty();
        }
        String text = z[18].strip();
        boolean gueltig = text.isEmpty() || BETRAG.matcher(text).matches();
        BigDecimal betrag = text.isEmpty() || !gueltig ? null : new BigDecimal(text);
        return Optional.of(new QrDaten(z[3].strip(), betrag, z[19].strip(), z[27].strip(),
                z[28].strip().isEmpty() ? null : z[28].strip(), z[5].strip(), !gueltig));
    }
}
