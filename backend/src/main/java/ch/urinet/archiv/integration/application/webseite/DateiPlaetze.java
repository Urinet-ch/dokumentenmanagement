package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plätze für gleichzeitige Streams der Webseiten-Schnittstelle (Spec 03, Aufgabe 11.8, Req 13.4): höchstens
 * {@code archiv.webseite.streams-gesamt} gleichzeitig und {@code archiv.webseite.streams-je-schluessel} je Schlüssel.
 * Ohne Warten: Ist kein Platz frei, kommt sofort 429 {@code ZU_VIELE_ANFRAGEN} mit {@code Retry-After: 5}, damit eine
 * Webseite mit vielen Besuchern die API nicht mit wartenden Verbindungen belegt. Ein Platz wird mit
 * {@link Platz#close()} frei, auch nach Abbruch des Clients oder einem Zeitlimit; mehrfaches Schliessen gibt ihn nur
 * einmal frei. Die Zähler liegen im Speicher der API (eine Instanz, design.md «Grenzen»).
 */
@ApplicationScoped
public class DateiPlaetze {

    /** Wartezeit der Antwort 429 in Sekunden (design.md «Dateiauslieferung und Stream»). */
    public static final long WARTEZEIT_SEKUNDEN = 5;

    private final int jeSchluessel;
    private final Semaphore gesamt;
    private final int gesamtMax;
    private final ConcurrentHashMap<String, Integer> belegt = new ConcurrentHashMap<>();

    public DateiPlaetze(@ConfigProperty(name = "archiv.webseite.streams-gesamt") int gesamt,
                        @ConfigProperty(name = "archiv.webseite.streams-je-schluessel") int jeSchluessel) {
        if (gesamt < 1 || jeSchluessel < 1) {
            throw new IllegalArgumentException("archiv.webseite.streams-gesamt und streams-je-schluessel brauchen "
                    + "mindestens 1");
        }
        this.gesamt = new Semaphore(gesamt);
        this.gesamtMax = gesamt;
        this.jeSchluessel = jeSchluessel;
    }

    /**
     * Reserviert einen Platz für den Schlüssel; zuerst die Grenze des Schlüssels, dann die gesamte.
     *
     * @throws ZuVieleAnfragen wenn der Schlüssel oder die API keinen Platz mehr hat
     */
    public Platz reservieren(String schluessel) {
        boolean[] frei = {false};
        belegt.compute(schluessel, (k, n) -> {
            int aktuell = n == null ? 0 : n;
            if (aktuell >= jeSchluessel) {
                return n;
            }
            frei[0] = true;
            return aktuell + 1;
        });
        if (!frei[0]) {
            throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.LAEUFT, WARTEZEIT_SEKUNDEN);
        }
        if (!gesamt.tryAcquire()) {
            freigeben(schluessel);
            throw new ZuVieleAnfragen(ZuVieleAnfragen.Grund.LAEUFT, WARTEZEIT_SEKUNDEN);
        }
        return new Platz(schluessel);
    }

    /** Zahl der belegten Plätze insgesamt. */
    public int belegt() {
        return gesamtMax - gesamt.availablePermits();
    }

    /** Zahl der belegten Plätze dieses Schlüssels. */
    public int belegt(String schluessel) {
        return belegt.getOrDefault(schluessel, 0);
    }

    private void freigeben(String schluessel) {
        belegt.compute(schluessel, (k, n) -> n == null || n <= 1 ? null : n - 1);
    }

    /** Ein reservierter Platz; {@link #close()} gibt ihn frei (einmal, auch bei mehrfachem Aufruf). */
    public final class Platz implements AutoCloseable {

        private final String schluessel;
        private final AtomicBoolean offen = new AtomicBoolean(true);

        private Platz(String schluessel) {
            this.schluessel = schluessel;
        }

        @Override
        public void close() {
            if (offen.compareAndSet(true, false)) {
                gesamt.release();
                freigeben(schluessel);
            }
        }
    }
}
