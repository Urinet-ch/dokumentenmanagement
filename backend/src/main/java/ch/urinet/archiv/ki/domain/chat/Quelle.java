package ch.urinet.archiv.ki.domain.chat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Quelle einer Antwort (Requirement 4.1): ein Dokument mit Link oder der Datenstand eines Zahlenwerkzeugs. */
public sealed interface Quelle {

    /** Dokument; {@link #link()} ist der relative PWA-Pfad, auf dem Server aus der ID gebaut. */
    record Dokument(UUID id, String titel, String seite) implements Quelle {
        public Dokument {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(titel, "titel");
        }

        public Dokument(UUID id, String titel) {
            this(id, titel, null);
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

    record Wissen(String pfad, String titel, Instant stand, List<Dokument> dokumente) implements Quelle {
        public Wissen {
            Objects.requireNonNull(pfad, "pfad");
            Objects.requireNonNull(titel, "titel");
            Objects.requireNonNull(stand, "stand");
            dokumente = List.copyOf(dokumente);
        }

        public String link() {
            return "/wissen/" + pfad;
        }
    }
}
