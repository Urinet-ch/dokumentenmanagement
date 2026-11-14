package ch.urinet.archiv.ki.domain.chat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ereignis an den Client (SSE, design.md «Vertrag Welle 7», Stream), benannt wie im Stream. Nach {@link Fertig} oder
 * {@link Fehler} ist der Stream zu Ende; {@link Quellen} kommt genau einmal unmittelbar vor {@link Fertig}.
 */
public sealed interface ChatAusgabe {

    /** Name des Ereignisses im Stream ({@code event:}-Zeile). */
    String name();

    record Werkzeug(String werkzeug) implements ChatAusgabe {
        public Werkzeug {
            Objects.requireNonNull(werkzeug, "werkzeug");
        }

        @Override
        public String name() {
            return "werkzeug";
        }
    }

    record Text(String delta) implements ChatAusgabe {
        public Text {
            Objects.requireNonNull(delta, "delta");
        }

        @Override
        public String name() {
            return "text";
        }
    }

    record Quellen(List<Quelle> quellen) implements ChatAusgabe {
        public Quellen {
            quellen = List.copyOf(quellen);
        }

        @Override
        public String name() {
            return "quellen";
        }
    }

    record Fertig(UUID gespraechId, ChatEreignis.Ende tokens) implements ChatAusgabe {
        public Fertig {
            Objects.requireNonNull(gespraechId, "gespraechId");
            Objects.requireNonNull(tokens, "tokens");
        }

        @Override
        public String name() {
            return "fertig";
        }
    }

    record Fehler(String meldung, UUID gespraechId) implements ChatAusgabe {
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
