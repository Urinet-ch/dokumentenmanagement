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
        String stufe = config.getOptionalValue("archiv.stufe", String.class).orElse("B");
        PortAuswahl.Befund befund = pruefe(auswahl.stream().toList(), stufe);
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

    /** Ports, die {@code archiv.ki.stub} folgen; in Stufe P ohne KI-Token dürfen sie alle auf den Stub fallen. */
    private static final java.util.Set<String> KI_PORTS =
            java.util.Set.of("KiPort", "EmbeddingPort", "RerankerPort", "ChatAgentPort");

    static PortAuswahl.Befund pruefe(List<PortAuswahl> auswahl, String stufe) {
        if (!"P".equalsIgnoreCase(stufe.strip())) {
            return PortAuswahl.pruefe(auswahl);
        }
        List<PortAuswahl> ohneKiStub = auswahl.stream()
                .map(port -> KI_PORTS.contains(port.port()) && port.stub()
                        ? new PortAuswahl(port.port(), false, port.adapterVorhanden(), port.folgtMit())
                        : port)
                .toList();
        return PortAuswahl.pruefe(ohneKiStub);
    }
}
