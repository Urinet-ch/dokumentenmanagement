package ch.urinet.archiv.gemeinsam.adapter.in.start;

import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Startprüfung der Port-Auswahl (Req 16.13; Review PR #25, Punkt 11): In {@code prod} (Profil {@code prod} oder
 * gepacktes Artefakt) bricht der Start ab, wenn ein Dev-Stub aktiv ist oder ein Adapter fehlt, der nicht erst mit
 * einer späteren Aufgabe kommt. Gilt für jede Rolle und jeden {@link PortAuswahl}-Produzenten.
 */
@ApplicationScoped
public class PortAuswahlPruefung {

    private static final Logger LOG = Logger.getLogger(PortAuswahlPruefung.class);

    void beimStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ereignis,
                   Instance<PortAuswahl> auswahl) {
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        if (!prod(config.getProfiles(), LaunchMode.current())) {
            return;
        }
        PortAuswahl.Befund befund = PortAuswahl.pruefe(auswahl.stream().toList());
        befund.warnungen().forEach(LOG::warn);
        if (!befund.abbrueche().isEmpty()) {
            throw new IllegalStateException("Port-Auswahl in prod abgelehnt: " + String.join("; ", befund.abbrueche())
                    + ". Stub-Schalter archiv.<port>.stub=false setzen (Req 16.13).");
        }
    }

    /** Ein gepacktes Artefakt (LaunchMode NORMAL) gilt immer als {@code prod} (wie {@code KiBasisUrlPruefung}). */
    static boolean prod(List<String> profile, LaunchMode modus) {
        return profile.contains("prod") || modus == LaunchMode.NORMAL;
    }
}
