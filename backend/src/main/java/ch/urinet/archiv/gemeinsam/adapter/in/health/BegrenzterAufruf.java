package ch.urinet.archiv.gemeinsam.adapter.in.health;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Führt einen Aufruf auf einem eigenen Thread mit Zeitlimit aus (Review PR #42, Runde 2, K1): Antwortet Keycloak
 * nicht, hängt sonst der Wächter. Läuft der vorige Aufruf noch, startet kein zweiter; gewartet wird wieder höchstens
 * das Zeitlimit auf denselben. So bindet ein hängender Aufruf genau einen Thread.
 */
final class BegrenzterAufruf<T> implements AutoCloseable {

    private final Duration zeitlimit;
    private final ExecutorService ausfuehrer;
    private Future<T> laufend;

    BegrenzterAufruf(String threadName, Duration zeitlimit) {
        if (zeitlimit.isNegative() || zeitlimit.isZero()) {
            throw new IllegalArgumentException("Zeitlimit muss positiv sein");
        }
        this.zeitlimit = zeitlimit;
        this.ausfuehrer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /** Ergebnis des Aufrufs oder leer, wenn er nicht innerhalb des Zeitlimits fertig wird. */
    synchronized Optional<T> ausfuehren(Callable<T> aufruf) throws ExecutionException, InterruptedException {
        if (laufend == null || laufend.isDone()) {
            laufend = ausfuehrer.submit(aufruf);
        }
        try {
            return Optional.ofNullable(laufend.get(zeitlimit.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        ausfuehrer.shutdownNow();
    }
}
