package ch.urinet.archiv.ki.domain.chat;

import java.util.Objects;
import java.util.UUID;

/** Quelle einer Antwort (Requirement 4.1): ein Dokument mit Link oder der Datenstand eines Zahlenwerkzeugs. */
public sealed interface Quelle {

    /** Dokument; {@link #link()} ist der relative PWA-Pfad, auf dem Server aus der ID gebaut. */
    record Dokument(UUID id, String titel) implements Quelle {
        public Dokument {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(titel, "titel");
        }

        public String link() {
            return "/dokument/" + id;
        }
    }

    /** Datenstand eines Zahlenwerkzeugs. */
    record Stand(String werkzeug, ChatEreignis.Datenstand datenstand) implements Quelle {
        public Stand {
            Objects.requireNonNull(werkzeug, "werkzeug");
            Objects.requireNonNull(datenstand, "datenstand");
        }
    }
}
