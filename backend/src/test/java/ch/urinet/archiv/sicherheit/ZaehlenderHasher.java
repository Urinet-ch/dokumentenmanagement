package ch.urinet.archiv.sicherheit;

import ch.urinet.archiv.sicherheit.application.GeheimnisHasher;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nur Tests: zählt die gleichzeitig laufenden Argon2-Vergleiche (Höchststand) und kann sie anhalten. Ohne
 * {@link #anhalten()} reicht er alles unverändert durch.
 */
@Decorator
@Priority(10)
public class ZaehlenderHasher implements GeheimnisHasher {

    static final AtomicInteger LAUFEND = new AtomicInteger();
    static final AtomicInteger HOECHSTENS = new AtomicInteger();
    private static volatile CountDownLatch bremse = new CountDownLatch(0);

    @Inject
    @Delegate
    GeheimnisHasher hasher;

    static void zuruecksetzen() {
        HOECHSTENS.set(0);
    }

    static void anhalten() {
        bremse = new CountDownLatch(1);
    }

    static void freigeben() {
        bremse.countDown();
    }

    @Override
    public String hashen(String geheimnis) {
        return hasher.hashen(geheimnis);
    }

    @Override
    public boolean pruefen(String geheimnis, String hash) {
        HOECHSTENS.accumulateAndGet(LAUFEND.incrementAndGet(), Math::max);
        try {
            bremse.await(10, TimeUnit.SECONDS);
            return hasher.pruefen(geheimnis, hash);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            LAUFEND.decrementAndGet();
        }
    }
}
