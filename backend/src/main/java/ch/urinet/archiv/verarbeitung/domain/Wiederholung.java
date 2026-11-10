package ch.urinet.archiv.verarbeitung.domain;

import java.time.Duration;
import java.util.Optional;

/**
 * Wiederholung fehlgeschlagener Aufträge (Req 5.4, design.md «Verarbeitung»): insgesamt drei Versuche; nach dem n-ten
 * Fehlversuch wartet der Auftrag 2^n Minuten, nach dem dritten ist er endgültig {@code FEHLER}. Zeitüberschreitung
 * (Req 5.6) und fehlendes Lebenszeichen (Req 5.7) zählen wie ein Fehler.
 */
public final class Wiederholung {

    public static final int MAX_VERSUCHE = 3;

    private Wiederholung() {
    }

    /** Wartezeit nach dem {@code n}-ten Fehlversuch; leer, wenn kein Versuch mehr bleibt (Auftrag {@code FEHLER}). */
    public static Optional<Duration> nachFehlversuch(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Fehlversuche beginnen bei 1");
        }
        return n >= MAX_VERSUCHE ? Optional.empty() : Optional.of(Duration.ofMinutes(1L << n));
    }
}
