package ch.urinet.archiv.sicherheit.adapter.in.start;

import ch.urinet.archiv.sicherheit.application.QuellAdresse;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class QuellAdresseBeimStart {

    private static final Logger LOG = Logger.getLogger(QuellAdresseBeimStart.class);

    void beimStart(@Observes StartupEvent start, QuellAdresse quelle) {
        if (quelle.ohneNetze() && LaunchMode.current() == LaunchMode.NORMAL) {
            LOG.warn("Keine vertrauenswürdigen Proxy-Netze gesetzt: Clients hinter dem Proxy teilen eine Quelladresse");
        }
    }
}
