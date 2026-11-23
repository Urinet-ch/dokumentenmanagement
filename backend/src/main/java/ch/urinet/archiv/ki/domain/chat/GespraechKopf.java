package ch.urinet.archiv.ki.domain.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Eintrag der Gesprächsliste mit dem festen Modus des Gesprächs (Spec 04, Requirement 21.1). */
public record GespraechKopf(UUID id, String titel, Instant geaendert, Chatmodus modus) {

    public GespraechKopf {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(titel, "titel");
        Objects.requireNonNull(geaendert, "geaendert");
        Objects.requireNonNull(modus, "modus");
    }

    /** Gespräch im Modus {@link Chatmodus#ARCHIV}. */
    public GespraechKopf(UUID id, String titel, Instant geaendert) {
        this(id, titel, geaendert, Chatmodus.ARCHIV);
    }
}
