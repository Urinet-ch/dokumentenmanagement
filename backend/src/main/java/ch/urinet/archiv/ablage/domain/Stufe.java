package ch.urinet.archiv.ablage.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Stufe der Plattform aus {@code archiv.stufe} (design.md «Protokoll und Manifest»): Ohne Wert gilt {@code B}, damit
 * COMPLIANCE im Zweifel erzwungen wird; ein unbekannter Wert bricht ab.
 */
public enum Stufe {
    A,
    B,
    /**
     * Restore-Probe (Req 16.24): nur in {@code deploy/probe/compose.yml}, Sperren höchstens 1 Tag. Als Startwert erst ab
     * Aufgabe 5.5 zulässig, wenn alle Schutzregeln der Stufe P stehen; bis dahin bricht {@link #aus} ab.
     */
    P;

    public static Stufe aus(Optional<String> wert) {
        String text = wert.map(String::strip).filter(w -> !w.isEmpty()).orElse("B");
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "A" -> A;
            case "B" -> B;
            case "P" -> throw new IllegalStateException("archiv.stufe ist «P»: Stufe P ist erst mit Aufgabe 5.5 "
                    + "zulässig (Req 16.24), zulässig sind A und B");
            default -> throw new IllegalStateException("archiv.stufe ist «" + text + "», zulässig sind A und B");
        };
    }
}
