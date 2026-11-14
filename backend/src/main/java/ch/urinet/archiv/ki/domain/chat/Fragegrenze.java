package ch.urinet.archiv.ki.domain.chat;

import java.time.Duration;
import java.time.Instant;

/**
 * Rate-Limit des Chats (Requirement 5.5; design.md «Vertrag Welle 7», Anfrage): rollend über {@link #FENSTER}. Ist die
 * Grenze erreicht, gilt die Frage, deren Ablauf eine Frage wieder zulässt (die {@code grenze}-neueste im Fenster), als
 * massgebend: {@code Retry-After} sind die Sekunden, bis sie eine Stunde alt ist, aufgerundet und mindestens 1.
 */
public final class Fragegrenze {

    public static final Duration FENSTER = Duration.ofHours(1);

    private Fragegrenze() {
    }

    public static long wartezeitSekunden(Instant massgebendeFrage, Instant jetzt) {
        Duration rest = Duration.between(jetzt, massgebendeFrage.plus(FENSTER));
        long sekunden = rest.getSeconds() + (rest.getNano() > 0 ? 1 : 0);
        return Math.max(1, sekunden);
    }
}
