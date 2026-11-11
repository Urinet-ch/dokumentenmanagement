package ch.urinet.archiv.verarbeitung.domain;

import java.time.Duration;
import java.util.Optional;

/**
 * Wiederholung des Hooks nach dem letzten Fehlversuch (Req 5.4, design.md «Verarbeitung»): höchstens fünf Versuche;
 * nach dem n-ten Fehlschlag wartet die Nachbearbeitung 2^n Minuten, nach dem fünften gibt der Worker sie auf.
 */
public final class Nachbearbeitung {

    public static final int MAX_VERSUCHE = 5;

    private Nachbearbeitung() {
    }

    /** Wartezeit nach dem {@code n}-ten Fehlschlag; leer, wenn kein Versuch mehr bleibt (aufgegeben). */
    public static Optional<Duration> nachFehlschlag(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Fehlschläge beginnen bei 1");
        }
        return n >= MAX_VERSUCHE ? Optional.empty() : Optional.of(Duration.ofMinutes(1L << n));
    }
}
