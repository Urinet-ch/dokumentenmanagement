package ch.urinet.archiv.ki.adapter.out.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.service.output.OutputParsingException;

/** Req 7.3: nur Parse- und Schemafehler lösen eine Nachfrage aus; andere Fehler (Timeout, HTTP) sind ein Ausfall. */
public final class KiFehler {

    private KiFehler() {
    }

    static boolean istSchemafehler(Throwable t) {
        for (Throwable u = t; u != null; u = u.getCause() == u ? null : u.getCause()) {
            if (u instanceof OutputParsingException || u instanceof JsonProcessingException) {
                return true;
            }
        }
        return false;
    }

    /** Nur Klassennamen, keine Meldung (sie kann Antwort- oder Belegtext enthalten, Req 16.7). */
    public static String grund(Throwable t) {
        Throwable wurzel = t;
        while (wurzel.getCause() != null && wurzel.getCause() != wurzel) {
            wurzel = wurzel.getCause();
        }
        return wurzel == t ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + " (" + wurzel.getClass().getSimpleName() + ")";
    }
}
