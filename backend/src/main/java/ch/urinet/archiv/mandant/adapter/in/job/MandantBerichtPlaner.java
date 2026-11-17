package ch.urinet.archiv.mandant.adapter.in.job;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantBerichtPlanen;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/** Plant den Monatsbericht täglich, damit verpasste Läufe und fehlgeschlagene Mails nachgeholt werden. */
@ApplicationScoped
public class MandantBerichtPlaner {

    private static final Logger LOG = Logger.getLogger(MandantBerichtPlaner.class);

    private final MandantBerichtPlanen planen;
    private final boolean worker;
    private final Stufe stufe;

    public MandantBerichtPlaner(MandantBerichtPlanen planen, @ConfigProperty(name = "archiv.rolle") String rolle,
                                @ConfigProperty(name = "archiv.stufe", defaultValue = "B") String stufe) {
        this.planen = planen;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
        this.stufe = Stufe.aus(Optional.ofNullable(stufe));
    }

    @Scheduled(identity = "mandant-bericht", cron = "${archiv.mandant.bericht.zeitplan}",
            timeZone = "Europe/Zurich", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void planen() {
        if (worker && stufe != Stufe.P) {
            try {
                TaktThread.ausfuehren("mandant-bericht", () -> planen.planen(Instant.now()));
            } catch (RuntimeException e) {
                LOG.errorf("Planung der Monatsberichte fehlgeschlagen, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
