package ch.urinet.archiv.gemeinsam.domain;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Ursachen-Kette eines Fehlers fürs Log (Req 16.7): nur Klassennamen, äusserste zuerst, bei einer {@link SQLException}
 * mit SQLState; nie Meldungen, denn die enthalten Werte wie «Key (code)=(…)», Token oder Belegdaten. Dazu Abfragen
 * über die Kette (Unterbrechung, enthaltener Typ).
 */
public final class Ursachen {

    private static final int MAX_URSACHEN = 10;

    private Ursachen() {
    }

    public static String kette(Throwable fehler) {
        List<String> kette = new ArrayList<>();
        Set<Throwable> gesehen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = fehler; t != null && gesehen.add(t) && kette.size() < MAX_URSACHEN; t = t.getCause()) {
            String name = t.getClass().getName();
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                name += "[sqlstate=" + sql.getSQLState() + "]";
            }
            kette.add(name);
        }
        return String.join(" <- ", kette);
    }

    /**
     * {@code true}, wenn die Kette eine Unterbrechung des Threads anzeigt ({@link InterruptedException},
     * {@link InterruptedIOException} ausser Zeitüberschreitungen, {@link ClosedByInterruptException}). So erkennt ein
     * Aufrufer einen Abbruch auch dann, wenn eine Bibliothek das Interrupt-Flag schon gelöscht hat.
     */
    public static boolean unterbrechung(Throwable fehler) {
        for (Throwable t : liste(fehler)) {
            if (t instanceof InterruptedException || t instanceof ClosedByInterruptException
                    || (t instanceof InterruptedIOException && !zeitueberschreitung(t))) {
                return true;
            }
        }
        return false;
    }

    /** {@code true}, wenn {@code fehler} oder eine seiner Ursachen vom Typ {@code typ} ist. */
    public static boolean enthaelt(Throwable fehler, Class<? extends Throwable> typ) {
        return liste(fehler).stream().anyMatch(typ::isInstance);
    }

    // SocketTimeoutException und z. B. ConnectTimeoutException von Apache erben von InterruptedIOException
    private static boolean zeitueberschreitung(Throwable t) {
        return t instanceof SocketTimeoutException || t.getClass().getSimpleName().endsWith("TimeoutException");
    }

    private static List<Throwable> liste(Throwable fehler) {
        List<Throwable> liste = new ArrayList<>();
        Set<Throwable> gesehen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = fehler; t != null && gesehen.add(t); t = t.getCause()) {
            liste.add(t);
        }
        return liste;
    }
}
