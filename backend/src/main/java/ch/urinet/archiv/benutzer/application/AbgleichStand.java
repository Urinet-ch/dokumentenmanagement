package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Verwaltungsstand eines Mandanten für den lesenden Abgleich. */
public interface AbgleichStand {
    record OffenerVorgang(UUID id, Optional<UUID> ziel) {
        public OffenerVorgang {
            Objects.requireNonNull(id);
            Objects.requireNonNull(ziel);
        }
    }

    record Stand(Set<UUID> verwaltet, Set<UUID> dokumentierteInhaber, Set<OffenerVorgang> offeneVorgaenge) {
        public Stand {
            verwaltet = Set.copyOf(verwaltet);
            dokumentierteInhaber = Set.copyOf(dokumentierteInhaber);
            offeneVorgaenge = Set.copyOf(offeneVorgaenge);
        }
    }

    Stand lesen(MandantCode mandant);

    /**
     * Markiert einen weiterhin offenen Vorgang atomar als ungeklärt und hält seine Sperre. Eine bereits ungeklärte
     * Sperre gilt ebenfalls als offen. Bei {@code false} muss der Aufrufer den Stand erneut prüfen.
     */
    boolean unklar(MandantCode mandant, UUID vorgang);
}
