package ch.urinet.archiv.ki.domain.oeffentlich;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ereignis an die Webseite (SSE von {@code POST /public/v1/chat}, Spec 03, design.md «Webseiten-Schnittstelle: Lesen»),
 * benannt wie im Stream: {@code werkzeug}, {@code text}, genau ein {@code quellen} unmittelbar vor {@code fertig} oder
 * {@code fehler}. Anders als beim internen Chat nennt {@code fertig} keine Tokens (kein Verbrauch nach aussen).
 */
public sealed interface OeffentlicheAusgabe {

    /** Name des Ereignisses im Stream ({@code event:}-Zeile). */
    String name();

    record Werkzeug(String werkzeug) implements OeffentlicheAusgabe {
        public Werkzeug {
            Objects.requireNonNull(werkzeug, "werkzeug");
        }

        @Override
        public String name() {
            return "werkzeug";
        }
    }

    record Text(String delta) implements OeffentlicheAusgabe {
        public Text {
            Objects.requireNonNull(delta, "delta");
        }

        @Override
        public String name() {
            return "text";
        }
    }

    record Quellen(List<OeffentlicheQuelle> quellen) implements OeffentlicheAusgabe {
        public Quellen {
            quellen = List.copyOf(quellen);
        }

        @Override
        public String name() {
            return "quellen";
        }
    }

    record Fertig(UUID gespraechId) implements OeffentlicheAusgabe {
        public Fertig {
            Objects.requireNonNull(gespraechId, "gespraechId");
        }

        @Override
        public String name() {
            return "fertig";
        }
    }

    /** Feste Meldung ohne interne Angaben; beendet den Stream ohne {@code fertig}. */
    record Fehler(String meldung, UUID gespraechId) implements OeffentlicheAusgabe {
        public Fehler {
            Objects.requireNonNull(meldung, "meldung");
            Objects.requireNonNull(gespraechId, "gespraechId");
        }

        @Override
        public String name() {
            return "fehler";
        }
    }

    /** Beendet diese Ausgabe den Stream? */
    default boolean letzte() {
        return this instanceof Fertig || this instanceof Fehler;
    }
}
