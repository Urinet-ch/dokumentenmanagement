package ch.urinet.archiv.protokoll.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kanonische JSON-Form nach RFC 8785 (JCS) für Manifeste und Protokollzeilen (Req 15.1, design.md «Manifest»). Eingabe
 * ist ein Baum aus {@link Map} (Schlüssel {@link String}), {@link List}, {@link String}, {@link Number},
 * {@link Boolean} und {@code null}. Schlüssel werden nach UTF-16-Codeeinheiten sortiert, Zahlen wie in ECMAScript
 * ({@code Number.prototype.toString}) als IEEE-754-double geschrieben; ganze Zahlen über 2^53 verlieren darum wie in
 * jedem JCS-Werkzeug ihre letzten Stellen (Auslegung: Beträge {@code NUMERIC(12,2)} und IDs bleiben exakt).
 */
public final class Jcs {

    private Jcs() {
    }

    public static String kanonisch(Object wert) {
        StringBuilder aus = new StringBuilder();
        schreiben(wert, aus);
        return aus.toString();
    }

    public static byte[] utf8(Object wert) {
        return kanonisch(wert).getBytes(StandardCharsets.UTF_8);
    }

    private static void schreiben(Object wert, StringBuilder aus) {
        switch (wert) {
            case null -> aus.append("null");
            case Boolean b -> aus.append(b);
            case String s -> text(s, aus);
            case Number n -> aus.append(zahl(n));
            case Map<?, ?> m -> objekt(m, aus);
            case List<?> l -> {
                aus.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        aus.append(',');
                    }
                    schreiben(l.get(i), aus);
                }
                aus.append(']');
            }
            default -> throw new IllegalArgumentException("Kein JSON-Wert: " + wert.getClass().getName());
        }
    }

    private static void objekt(Map<?, ?> m, StringBuilder aus) {
        List<String> schluessel = new ArrayList<>(m.size());
        for (Object k : m.keySet()) {
            if (!(k instanceof String s)) {
                throw new IllegalArgumentException("Schlüssel ist kein Text");
            }
            schluessel.add(s);
        }
        // String.compareTo vergleicht UTF-16-Codeeinheiten, wie RFC 8785, 3.2.3 verlangt
        schluessel.sort(String::compareTo);
        aus.append('{');
        for (int i = 0; i < schluessel.size(); i++) {
            if (i > 0) {
                aus.append(',');
            }
            text(schluessel.get(i), aus);
            aus.append(':');
            schreiben(m.get(schluessel.get(i)), aus);
        }
        aus.append('}');
    }

    private static void text(String s, StringBuilder aus) {
        aus.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> aus.append("\\\"");
                case '\\' -> aus.append("\\\\");
                case '\b' -> aus.append("\\b");
                case '\f' -> aus.append("\\f");
                case '\n' -> aus.append("\\n");
                case '\r' -> aus.append("\\r");
                case '\t' -> aus.append("\\t");
                default -> {
                    if (c < 0x20) {
                        aus.append(String.format("\\u%04x", (int) c));
                    } else {
                        aus.append(c);
                    }
                }
            }
        }
        aus.append('"');
    }

    /** ECMAScript-Darstellung eines double (ECMA-262, Number::toString), wie RFC 8785, 3.2.2.3 verlangt. */
    static String zahl(Number n) {
        double d = switch (n) {
            case BigDecimal b -> b.doubleValue();
            case BigInteger b -> b.doubleValue();
            default -> n.doubleValue();
        };
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("NaN und Unendlich sind in JSON nicht zulässig");
        }
        if (d == 0) {
            return "0";
        }
        // Double.toString liefert seit JDK 19 die kürzeste eindeutige Ziffernfolge, wie ECMAScript
        BigDecimal kurz = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String ziffern = kurz.unscaledValue().toString();
        int k = ziffern.length();
        int exp = k - kurz.scale();
        StringBuilder aus = new StringBuilder(d < 0 ? "-" : "");
        if (k <= exp && exp <= 21) {
            aus.append(ziffern).append("0".repeat(exp - k));
        } else if (0 < exp && exp <= 21) {
            aus.append(ziffern, 0, exp).append('.').append(ziffern, exp, k);
        } else if (-6 < exp && exp <= 0) {
            aus.append("0.").append("0".repeat(-exp)).append(ziffern);
        } else {
            aus.append(ziffern.charAt(0));
            if (k > 1) {
                aus.append('.').append(ziffern, 1, k);
            }
            int e = exp - 1;
            aus.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return aus.toString();
    }
}
