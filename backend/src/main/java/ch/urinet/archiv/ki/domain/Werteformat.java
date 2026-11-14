package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Werte, wie sie vorformatiert an das Sprachmodell gehen (Spec 02, Req 3.2, 3.7; design.md «Vertrag Welle 7»):
 * Beträge {@code CHF 1'234.50} mit Hochkomma als Tausendertrennzeichen, negativ {@code CHF -1'234.50}, Datum
 * {@code TT.MM.JJJJ}, Zeitpunkt {@code TT.MM.JJJJ HH:mm} in {@code Europe/Zurich}. So rechnet und formatiert das Modell
 * nichts selbst. Gemeinsam für alle Chat-Werkzeuge (7.1a–7.1c) und den Agenten (7.2).
 */
public final class Werteformat {

    public static final String CHF = "CHF";

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter ZEITPUNKT = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm")
            .withZone(ZoneId.of("Europe/Zurich"));

    private Werteformat() {
    }

    /** {@code CHF 1'234.50}; {@code null} bleibt {@code null}. */
    public static String chf(BigDecimal betrag) {
        return betrag(betrag, CHF);
    }

    /** Betrag mit Währungscode davor (ohne Umrechnung), zwei Nachkommastellen; {@code null} bleibt {@code null}. */
    public static String betrag(BigDecimal betrag, String waehrung) {
        if (betrag == null) {
            return null;
        }
        String ziffern = betrag.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
        int punkt = ziffern.indexOf('.');
        StringBuilder ganz = new StringBuilder(ziffern.substring(0, punkt));
        for (int i = ganz.length() - 3; i > 0; i -= 3) {
            ganz.insert(i, '\'');
        }
        String vorzeichen = betrag.signum() < 0 ? "-" : "";
        return (waehrung == null || waehrung.isBlank() ? CHF : waehrung.strip()) + " " + vorzeichen + ganz
                + ziffern.substring(punkt);
    }

    /** {@code TT.MM.JJJJ}; {@code null} bleibt {@code null}. */
    public static String datum(LocalDate datum) {
        return datum == null ? null : DATUM.format(datum);
    }

    /** {@code TT.MM.JJJJ HH:mm} in {@code Europe/Zurich}. */
    public static String zeitpunkt(Instant zeitpunkt) {
        return zeitpunkt == null ? null : ZEITPUNKT.format(zeitpunkt);
    }
}
