package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Rolle;

import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: meldet jede Verweigerung, bevor die Anfrage mit 403 bzw. 404 endet. Der Adapter schreibt sie mit Aktion
 * {@code VERWEIGERT} ins Zugriffsprotokoll, in einer eigenen Transaktion (Req 10.7); ein Fehler dabei ändert die
 * Antwort nicht.
 */
public interface VerweigerungMelder {

    /** {@code benutzerId}, {@code rolle} und {@code aktion} fehlen, wenn die Anfrage schon an Mandant oder Rolle scheitert. */
    void verweigert(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion,
                    Optional<UUID> dokumentId, Verweigerung.Grund grund);
}
