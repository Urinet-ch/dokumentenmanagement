package ch.urinet.archiv.spike.golden;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/** Vergleichsregeln Req 0.7. Fehlt ein Wert in der Erwartung, ist nur ein leerer Vorschlag richtig. */
public final class Vergleich {

    public enum Feld { ART, DATUM, PARTNER, BETRAG, BELEGNUMMER }

    /** Für das Gate (Req 0.3) zählen diese vier Felder je Beleg. */
    public static final Feld[] GATE_FELDER = {Feld.ART, Feld.DATUM, Feld.PARTNER, Feld.BETRAG};

    private Vergleich() {
    }

    public static boolean art(String erwartet, String vorschlag) {
        return text(erwartet, vorschlag, s -> s.strip());
    }

    public static boolean datum(LocalDate erwartet, LocalDate vorschlag) {
        return Objects.equals(erwartet, vorschlag);
    }

    public static boolean betrag(BigDecimal erwartet, BigDecimal vorschlag) {
        if (erwartet == null || vorschlag == null) {
            return erwartet == null && vorschlag == null;
        }
        return erwartet.setScale(2, RoundingMode.HALF_UP).compareTo(vorschlag.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    public static boolean partner(String erwartet, String vorschlag) {
        return text(erwartet, vorschlag, Normalisierung::name);
    }

    public static boolean belegnummer(String erwartet, String vorschlag) {
        return text(erwartet, vorschlag, s -> s.replaceAll("\\s+", ""));
    }

    private static boolean text(String erwartet, String vorschlag, java.util.function.UnaryOperator<String> norm) {
        boolean leer = vorschlag == null || vorschlag.isBlank();
        if (erwartet == null) {
            return leer;
        }
        return !leer && norm.apply(erwartet).equals(norm.apply(vorschlag));
    }
}
