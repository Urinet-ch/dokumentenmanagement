package ch.urinet.archiv.ablage.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Stufe der Plattform aus {@code archiv.stufe} (design.md «Protokoll und Manifest»): Ohne Wert gilt {@code B}, damit
 * COMPLIANCE im Zweifel erzwungen wird; ein unbekannter Wert bricht ab.
 */
public enum Stufe {
    A,
    B;

    public static Stufe aus(Optional<String> wert) {
        String text = wert.map(String::strip).filter(w -> !w.isEmpty()).orElse("B");
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "A" -> A;
            case "B" -> B;
            default -> throw new IllegalStateException("archiv.stufe ist «" + text + "», zulässig sind A und B");
        };
    }
}
