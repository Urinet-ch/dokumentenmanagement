package ch.urinet.archiv.sicherheit.adapter.out.protokoll;

import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.application.VerweigerungMelder;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/** Bis zum Zugriffsprotokoll (Aufgabe 1.6) nur ein Log-Eintrag mit IDs, ohne Inhalte. */
@ApplicationScoped
public class LogVerweigerungMelder implements VerweigerungMelder {

    private static final Logger LOG = Logger.getLogger(LogVerweigerungMelder.class);

    @Override
    public void verweigert(Optional<String> benutzerId, Optional<Aktion> aktion, Optional<UUID> dokumentId,
                           Verweigerung.Grund grund) {
        LOG.infof("Zugriff verweigert: grund=%s benutzer=%s aktion=%s dokument=%s", grund, benutzerId.orElse("-"),
                aktion.map(Enum::name).orElse("-"), dokumentId.map(UUID::toString).orElse("-"));
    }
}
