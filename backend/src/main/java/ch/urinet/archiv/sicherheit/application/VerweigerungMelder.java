package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: meldet jede Verweigerung. Anknüpfungspunkt für das Zugriffsprotokoll mit Aktion {@code VERWEIGERT}
 * (Aufgabe 1.6, Req 10.7); bis dahin nur ein Log mit IDs.
 */
public interface VerweigerungMelder {

    /** {@code benutzerId} und {@code aktion} fehlen, wenn die Anfrage schon an Mandant oder Rolle scheitert. */
    void verweigert(Optional<String> benutzerId, Optional<Aktion> aktion, Optional<UUID> dokumentId,
                    Verweigerung.Grund grund);
}
