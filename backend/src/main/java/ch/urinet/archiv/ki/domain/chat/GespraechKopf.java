package ch.urinet.archiv.ki.domain.chat;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Eintrag der Gesprächsliste. */
public record GespraechKopf(UUID id, String titel, Instant geaendert) {

    public GespraechKopf {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(titel, "titel");
        Objects.requireNonNull(geaendert, "geaendert");
    }
}
