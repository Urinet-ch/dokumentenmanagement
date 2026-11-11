package ch.urinet.archiv.ablage.adapter.in.start;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Stufenregel;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Startprüfung der Ablage nach Stufe (Req 6.11; tasks.md 2.3; design.md «Protokoll und Manifest»): Ab Stufe B brechen
 * API, Worker und Admin-Kommando den Start ab, wenn {@code manifest/} nicht COMPLIANCE ohne Höchstfrist sperrt, eine
 * Höchstfrist für {@code archiv/} oder der Bucket-Präfix der Stufe A konfiguriert ist. Die Stufe kommt aus
 * {@code archiv.stufe}; fehlt der Wert, gilt B. Die Regel hängt nicht am Profil {@code prod}.
 */
@ApplicationScoped
public class AblageStufenPruefung {

    private static final Logger LOG = Logger.getLogger(AblageStufenPruefung.class);

    void beimStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ereignis,
                   @ConfigProperty(name = "archiv.stufe") Optional<String> stufe,
                   AblageEinstellungen einstellungen) {
        Stufe wirksam = Stufe.aus(stufe);
        Stufenregel.pruefe(wirksam, einstellungen.fuerStufenregel());
        var warnungen = Stufenregel.warnungen(wirksam, einstellungen.fuerStufenregel());
        if (!warnungen.isEmpty()) {
            LOG.warnf("Ablage Stufe A: %s weichen ab (Höchstfrist höchstens %s, Präfix %s, Req 6.11)",
                    String.join(", ", warnungen), Stufenregel.HOECHSTFRIST_STUFE_A, Stufenregel.PRAEFIX_STUFE_A);
        }
        LOG.infof("Ablage: Stufe %s, Manifeste %s", wirksam, einstellungen.manifestSperrmodus());
    }
}
