package ch.urinet.archiv.gemeinsam.domain;

import java.time.Duration;

/**
 * Zählt aufeinanderfolgende Prüfungen «nicht bereit» und meldet {@link Lage#BEENDEN}, sobald seit der ersten davon die
 * Frist vergangen ist (Selbstheilung der Readiness, entschieden von Fabian am 11.11.2026). Eine bereite Prüfung setzt
 * Zähler und Beginn zurück. Gemessen wird mit einer monotonen Zeit in Nanosekunden (z. B. {@link System#nanoTime()}),
 * damit ein Sprung der Systemuhr nach einem Neustart der VPS die Frist nicht verkürzt.
 * <p>
 * Die Frist ist mindestens {@link #MINDESTFRIST}: Weil jeder Neustart die Frist neu beginnt, liegt zwischen zwei
 * Neustarts mindestens so viel Zeit; eine schnelle Schleife ist so ausgeschlossen.
 */
public final class Bereitschaftsfrist {

    public static final Duration MINDESTFRIST = Duration.ofMinutes(1);

    public enum Lage { BEREIT, WARTEN, BEENDEN }

    private final Duration frist;
    private long nichtBereitSeit;
    private int zaehler;

    public Bereitschaftsfrist(Duration frist) {
        if (frist == null || frist.compareTo(MINDESTFRIST) < 0) {
            throw new IllegalArgumentException("Frist kürzer als " + MINDESTFRIST.toSeconds() + " s");
        }
        this.frist = frist;
    }

    public synchronized Lage melden(boolean bereit, long jetztNanos) {
        if (bereit) {
            zaehler = 0;
            return Lage.BEREIT;
        }
        if (zaehler++ == 0) {
            nichtBereitSeit = jetztNanos;
        }
        return dauer(jetztNanos).compareTo(frist) >= 0 ? Lage.BEENDEN : Lage.WARTEN;
    }

    /** Aufeinanderfolgende Prüfungen «nicht bereit». */
    public synchronized int zaehler() {
        return zaehler;
    }

    /** Seit der ersten Prüfung «nicht bereit», 0 wenn bereit. */
    public synchronized Duration dauer(long jetztNanos) {
        return zaehler == 0 ? Duration.ZERO : Duration.ofNanos(jetztNanos - nichtBereitSeit);
    }

    public Duration frist() {
        return frist;
    }
}
