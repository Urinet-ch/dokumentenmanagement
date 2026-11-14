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
 * mit SQLState; keine Meldungen, denn die enthalten Werte wie «Key (code)=(…)», Token oder Belegdaten. Nur
 * {@link #ketteMitMeldung} nennt feste Meldungen eigener Ausnahmen. Dazu Abfragen über die Kette (Unterbrechung,
 * enthaltener Typ).
 */
public final class Ursachen {

    private static final int MAX_URSACHEN = 10;
    private static final String EIGENER_CODE = "ch.urinet.archiv.";

    private Ursachen() {
    }

    public static String kette(Throwable fehler) {
        return kette(fehler, false);
    }

    /**
     * Wie {@link #kette}, dazu bei eigenen Ausnahmen die Meldung, wenn sie sicher keine Werte enthält (Spec 02,
     * design.md «Umsetzung (Aufgabe 6.1)»): bei einem {@link FachFehler} Fehlercode und Meldung (nach seinem Vertrag ein
     * fester Text), bei {@link IllegalStateException} und {@link IllegalArgumentException} (genau diese Klassen) aus
     * Code unter {@value #EIGENER_CODE} nur, wenn die Meldung als String-Konstante in der Klasse steht, die die Ausnahme
     * erzeugt hat. Eine zusammengesetzte Meldung («…: » + Wert) steht dort nie und bleibt draussen, ebenso jede Meldung
     * fremder Ausnahmen und ihrer Unterklassen. So gelangen keine Mail-Inhalte, Adressen, Betreffe oder Dateinamen ins
     * Log (Req 16.7).
     */
    public static String ketteMitMeldung(Throwable fehler) {
        return kette(fehler, true);
    }

    private static String kette(Throwable fehler, boolean mitMeldung) {
        List<String> kette = new ArrayList<>();
        Set<Throwable> gesehen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = fehler; t != null && gesehen.add(t) && kette.size() < MAX_URSACHEN; t = t.getCause()) {
            String name = t.getClass().getName();
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                name += "[sqlstate=" + sql.getSQLState() + "]";
            }
            if (mitMeldung) {
                name += zusatz(t);
            }
            kette.add(name);
        }
        return String.join(" <- ", kette);
    }

    private static String zusatz(Throwable t) {
        String meldung = t.getMessage();
        if (t instanceof FachFehler fach) {
            return meldung == null || meldung.isEmpty() || meldung.equals(fach.code().name())
                    ? "[code=" + fach.code().name() + "]"
                    : "[code=" + fach.code().name() + ", meldung=\"" + meldung + "\"]";
        }
        boolean eigeneKlasse = t.getClass() == IllegalStateException.class
                || t.getClass() == IllegalArgumentException.class;
        StackTraceElement[] stapel = t.getStackTrace();
        if (!eigeneKlasse || meldung == null || meldung.isEmpty() || stapel.length == 0
                || !stapel[0].getClassName().startsWith(EIGENER_CODE)
                || !Konstanten.enthaelt(stapel[0].getClassName(), meldung)) {
            return "";
        }
        return "[meldung=\"" + meldung + "\"]";
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
