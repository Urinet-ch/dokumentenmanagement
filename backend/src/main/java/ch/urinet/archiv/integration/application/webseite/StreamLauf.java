package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.ablage.domain.DateiStrom;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ein laufender Stream einer Datei an den Webseitenserver (Spec 03, Aufgabe 11.8, Req 13.3, 13.7): Die Datei wird in
 * Blöcken von höchstens {@value #BLOCK_GROESSE} Bytes mit einem einzigen wiederverwendeten Puffer gelesen und
 * weitergegeben; erst wenn {@code write} den Block angenommen hat (ein langsamer Client hält es auf), wird der nächste
 * gelesen. So liegt nie mehr als ein Block im Speicher, und der Speicher wächst nicht mit der Dateigrösse.
 * <p>
 * Zwei Zeitlimits halten Plätze nicht ewig fest: {@code leerlauf} (kein Block weitergegeben) und {@code gesamt}
 * (Gesamtdauer ab Erstellung). Läuft eines ab, verwirft der Zeitgeber die Verbindung zum Speicher und zum Client
 * ({@code verbindungSchliessen}), damit ein blockiertes {@code read} oder {@code write} mit einer Ausnahme endet, und
 * gibt den Platz frei. Dasselbe tut {@link #senden(OutputStream)} (verwirft die Speicherverbindung per {@code abbrechen}, gibt dann in
 * {@code finally} den Platz frei), wenn der Client abbricht oder der
 * Speicher ausfällt; ein nie gestartetes {@code senden} erledigt der Zeitgeber nach dem Leerlauf. Der Lauf hält keine
 * Transaktion und keine Datenbankverbindung.
 */
public final class StreamLauf {

    /** Blockgrösse beim Weitergeben (64 KiB, design.md «Dateiauslieferung und Stream»). */
    public static final int BLOCK_GROESSE = 64 * 1024;

    private static final Logger LOG = Logger.getLogger(StreamLauf.class);

    /** Wie der Stream endete. */
    public enum Ende { VOLLSTAENDIG, ABBRUCH, LEERLAUF, ZEITLIMIT }

    private final DateiStrom strom;
    private final DateiPlaetze.Platz platz;
    private final long leerlaufNanos;
    private final Runnable verbindungSchliessen;
    private final ScheduledExecutorService zeitgeber;
    private final AtomicBoolean frei = new AtomicBoolean();
    private final long start = System.nanoTime();
    private volatile long letzterFortschritt = start;
    private volatile Ende ende;
    private volatile long gesendet;
    private volatile ScheduledFuture<?> leerlaufTimer;
    private final ScheduledFuture<?> gesamtTimer;

    public StreamLauf(DateiStrom strom, DateiPlaetze.Platz platz, Duration leerlauf, Duration gesamt,
                      ScheduledExecutorService zeitgeber, Runnable verbindungSchliessen) {
        this.strom = Objects.requireNonNull(strom, "strom");
        this.platz = Objects.requireNonNull(platz, "platz");
        this.zeitgeber = Objects.requireNonNull(zeitgeber, "zeitgeber");
        this.verbindungSchliessen = Objects.requireNonNull(verbindungSchliessen, "verbindungSchliessen");
        if (leerlauf.isZero() || leerlauf.isNegative() || gesamt.isZero() || gesamt.isNegative()) {
            throw new IllegalArgumentException("Zeitlimits müssen positiv sein");
        }
        this.leerlaufNanos = leerlauf.toNanos();
        this.gesamtTimer = zeitgeber.schedule(() -> abbrechen(Ende.ZEITLIMIT), gesamt.toNanos(), TimeUnit.NANOSECONDS);
        this.leerlaufTimer = zeitgeber.schedule(this::leerlaufPruefen, leerlaufNanos, TimeUnit.NANOSECONDS);
    }

    /** Länge der Datei in Bytes für {@code Content-Length}. */
    public long laenge() {
        return strom.laenge();
    }

    /** Wie der Lauf endete; {@code null}, solange er läuft. */
    public Ende ende() {
        return ende;
    }

    /** Bytes, die bisher an den Client weitergegeben wurden. */
    public long gesendet() {
        return gesendet;
    }

    /**
     * Gibt die Datei in Blöcken an {@code ziel} weiter und gibt danach in jedem Fall Strom und Platz frei.
     *
     * @throws IOException wenn der Client abbricht, ein Zeitlimit abläuft, der Speicher ausfällt oder weniger Bytes
     *                     kommen als angekündigt
     */
    public void senden(OutputStream ziel) throws IOException {
        byte[] puffer = new byte[BLOCK_GROESSE];
        try {
            InputStream quelle = strom.inhalt();
            int n;
            while ((n = quelle.readNBytes(puffer, 0, BLOCK_GROESSE)) > 0) {
                ziel.write(puffer, 0, n);
                gesendet += n;
                letzterFortschritt = System.nanoTime();
            }
            ziel.flush();
            if (gesendet != strom.laenge()) {
                throw new IOException("Datei kürzer als angekündigt");
            }
            if (ende == null) {
                ende = Ende.VOLLSTAENDIG;
            }
        } catch (IOException | RuntimeException e) {
            if (ende == null) {
                ende = Ende.ABBRUCH;
            }
            // Client weg oder Speicher ausgefallen: Verbindung zum Speicher verwerfen, nicht den Rest lesen (Req 13.7)
            strom.abbrechen();
            throw e;
        } finally {
            freigeben();
            LOG.debugf("Stream beendet: %s, %d von %d Bytes", ende, gesendet, strom.laenge());
        }
    }

    /** Für einen Lauf, dessen {@link #senden} nie beginnt (Fehler beim Bauen der Antwort): Speicher verwerfen, freigeben. */
    public void abbrechen() {
        if (ende == null) {
            ende = Ende.ABBRUCH;
        }
        try {
            strom.abbrechen();
        } finally {
            freigeben();
        }
    }

    /** Gibt Strom und Platz frei (einmal); ohne {@link #senden} etwa nach Abbruch vor dem ersten Block. */
    public void freigeben() {
        if (frei.compareAndSet(false, true)) {
            gesamtTimer.cancel(false);
            ScheduledFuture<?> timer = leerlaufTimer;
            if (timer != null) {
                timer.cancel(false);
            }
            try {
                strom.close();
            } finally {
                platz.close();
            }
        }
    }

    private void leerlaufPruefen() {
        if (frei.get()) {
            return;
        }
        long seitFortschritt = System.nanoTime() - letzterFortschritt;
        if (seitFortschritt >= leerlaufNanos) {
            abbrechen(Ende.LEERLAUF);
        } else {
            leerlaufTimer = zeitgeber.schedule(this::leerlaufPruefen, leerlaufNanos - seitFortschritt,
                    TimeUnit.NANOSECONDS);
        }
    }

    /** Zeitlimit: Speicher und Client abhängen, damit ein blockiertes read/write endet, und den Platz freigeben. */
    private void abbrechen(Ende grund) {
        if (frei.get()) {
            return;
        }
        if (ende == null) {
            ende = grund;
        }
        LOG.debugf("Stream abgebrochen: %s nach %d Bytes", grund, gesendet);
        try {
            strom.abbrechen();
            verbindungSchliessen.run();
        } finally {
            freigeben();
        }
    }
}
