package ch.urinet.archiv.dokument.domain;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Ergebnis einer Datei im Upload (Req 3.2, 3.4, 3.8; design.md «Upload»); {@code datei} ist der bereinigte Name. */
public sealed interface UploadErgebnis {

    String datei();

    /** Angenommen: neues Dokument im Status {@code NEU}; beim Zusammenfassen tragen alle dieselbe ID. */
    record Angenommen(String datei, UUID id) implements UploadErgebnis {
        public Angenommen {
            Objects.requireNonNull(datei, "datei");
            Objects.requireNonNull(id, "id");
        }
    }

    /** Gleiche SHA-256 wie ein nicht verworfenes Dokument; ohne Leserecht ohne ID (Req 3.4, 2.3). */
    record Dublette(String datei, Optional<UUID> vorhandenId) implements UploadErgebnis {
        public Dublette {
            Objects.requireNonNull(datei, "datei");
            Objects.requireNonNull(vorhandenId, "vorhandenId");
        }
    }

    record Abgelehnt(String datei, Fehlercode fehler) implements UploadErgebnis {
        public Abgelehnt {
            Objects.requireNonNull(datei, "datei");
            Objects.requireNonNull(fehler, "fehler");
        }
    }
}
