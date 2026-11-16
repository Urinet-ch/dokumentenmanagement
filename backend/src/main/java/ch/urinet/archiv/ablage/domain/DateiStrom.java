package ch.urinet.archiv.ablage.domain;

import java.io.InputStream;
import java.util.Objects;

/**
 * Eine geöffnete Version einer Datei zum Streamen (Spec 03, Aufgabe 11.8): der Datenstrom und die Länge in Bytes aus
 * den Objektdaten. Der Aufrufer liest in Blöcken und schliesst den Strom; {@link #abbrechen()} verwirft die Verbindung
 * zum Speicher sofort, ohne den Rest zu lesen (Abbruch des Clients, Zeitlimit; Req 13.7).
 */
public final class DateiStrom implements AutoCloseable {

    private final InputStream inhalt;
    private final long laenge;
    private final Runnable abbruch;

    /** @param abbruch verwirft die Verbindung, ohne den Rest zu lesen; darf mehrfach und nach {@code close} laufen */
    public DateiStrom(InputStream inhalt, long laenge, Runnable abbruch) {
        this.inhalt = Objects.requireNonNull(inhalt, "inhalt");
        this.abbruch = Objects.requireNonNull(abbruch, "abbruch");
        if (laenge < 0) {
            throw new IllegalArgumentException("Länge unter 0");
        }
        this.laenge = laenge;
    }

    /** Strom ohne eigenen Abbruch: {@link #abbrechen()} schliesst ihn. */
    public DateiStrom(InputStream inhalt, long laenge) {
        this(inhalt, laenge, () -> {
        });
    }

    public InputStream inhalt() {
        return inhalt;
    }

    public long laenge() {
        return laenge;
    }

    /** Verwirft die Verbindung zum Speicher; ein blockiertes {@code read} endet mit einer Ausnahme. */
    public void abbrechen() {
        try {
            abbruch.run();
        } finally {
            schliessenLeise();
        }
    }

    @Override
    public void close() {
        schliessenLeise();
    }

    private void schliessenLeise() {
        try {
            inhalt.close();
        } catch (Exception | Error e) {
            // schon geschlossen oder abgebrochen: nichts mehr zu tun
        }
    }
}
