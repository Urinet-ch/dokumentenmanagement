package ch.urinet.archiv.sicherheit.adapter.in.start;

import ch.urinet.archiv.sicherheit.application.QuellAdresse;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class QuellAdresseWarnungBeimStart {

    private static final Logger LOG = Logger.getLogger(QuellAdresseWarnungBeimStart.class);

    void beimStart(@Observes StartupEvent ereignis, QuellAdresse quellAdresse) {
        if (quellAdresse.ohneNetze() && LaunchMode.current() == LaunchMode.NORMAL) {
            LOG.warn("Keine vertrauenswürdigen Proxy-Netze gesetzt: Clients hinter dem Proxy teilen eine Quelladresse");
        }
    }
}
