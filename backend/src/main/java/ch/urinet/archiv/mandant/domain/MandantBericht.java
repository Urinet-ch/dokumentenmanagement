package ch.urinet.archiv.mandant.domain;

import java.time.YearMonth;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Zahlen des mandantenbezogenen Monatsberichts; keine Dokument- oder Frageinhalte. */
public record MandantBericht(MandantCode mandant, YearMonth monat, long speicherBytes,
                             Map<String, Long> dokumente, Map<String, Tokens> kiTokens, long chatFragen) {

    public MandantBericht {
        Objects.requireNonNull(mandant, "mandant");
        Objects.requireNonNull(monat, "monat");
        if (speicherBytes < 0 || chatFragen < 0) {
            throw new IllegalArgumentException("Berichtszahlen dürfen nicht negativ sein");
        }
        dokumente = unveraenderlich(dokumente);
        kiTokens = unveraenderlich(kiTokens);
    }

    private static <T> Map<String, T> unveraenderlich(Map<String, T> werte) {
        Objects.requireNonNull(werte, "werte");
        TreeMap<String, T> sortiert = new TreeMap<>();
        werte.forEach((schluessel, wert) -> {
            if (schluessel == null || schluessel.isBlank() || wert == null) {
                throw new IllegalArgumentException("Berichtswerte sind unvollständig");
            }
            sortiert.put(schluessel, wert);
        });
        return Collections.unmodifiableMap(sortiert);
    }

    public record Tokens(long eingabe, long ausgabe) {
        public Tokens {
            if (eingabe < 0 || ausgabe < 0) {
                throw new IllegalArgumentException("Token-Zahlen dürfen nicht negativ sein");
            }
        }
    }
}
