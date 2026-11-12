package ch.urinet.archiv.protokoll.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Ein Eintrag des Zugriffsprotokolls zur Einsicht (Req 10.5), mit Name und Rolle aus dem Benutzerverzeichnis zur Zeit
 * des Eintrags (Req 10.10). {@code details} ist das gespeicherte JSON-Objekt als Text.
 */
public record ProtokollZeile(long id, Instant zeit, Optional<String> benutzer, Optional<String> name,
                             Optional<String> rolle, String aktion, Optional<UUID> dokumentId, Optional<String> ip,
                             Optional<String> details) {

    public ProtokollZeile {
        Objects.requireNonNull(zeit, "zeit");
        Objects.requireNonNull(benutzer, "benutzer");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rolle, "rolle");
        Objects.requireNonNull(aktion, "aktion");
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(details, "details");
    }

    /** Eine Seite der Einsicht mit der Zahl aller Treffer des Filters. */
    public record Seite(List<ProtokollZeile> eintraege, long gesamt) {

        public Seite {
            eintraege = List.copyOf(eintraege);
        }
    }
}
