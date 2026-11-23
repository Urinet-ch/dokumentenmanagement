package ch.urinet.archiv.benutzer.adapter.in.job;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.benutzer.application.Abgleich;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.application.SchemasBereit;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class AbgleichTakt {
    private static final Logger LOG = Logger.getLogger(AbgleichTakt.class);

    private final Abgleich abgleich;
    private final boolean worker;
    private final boolean beimStart;
    private final Stufe stufe;
    private final AtomicBoolean laeuft = new AtomicBoolean();

    public AbgleichTakt(Abgleich abgleich, @ConfigProperty(name = "archiv.rolle") String rolle,
                        @ConfigProperty(name = "archiv.verwaltung.abgleich.beim-start") boolean beimStart,
                        @ConfigProperty(name = "archiv.stufe", defaultValue = "B") String stufe) {
        this.abgleich = abgleich;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
        this.beimStart = beimStart;
        this.stufe = Stufe.aus(Optional.ofNullable(stufe));
    }

    void start(@Observes SchemasBereit ereignis) {
        if (beimStart && worker && stufe != Stufe.P) {
            Thread.ofPlatform().name("verwaltungsabgleich-start").daemon().start(this::geplant);
        }
    }

    @Scheduled(identity = "verwaltungsabgleich", cron = "${archiv.verwaltung.abgleich.zeitplan}",
            timeZone = "Europe/Zurich", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void geplant() {
        if (!worker || stufe == Stufe.P || !laeuft.compareAndSet(false, true)) {
            return;
        }
        try {
            TaktThread.ausfuehren("verwaltungsabgleich", abgleich::ausfuehren);
        } catch (RuntimeException e) {
            LOG.errorf("Verwaltungsabgleich fehlgeschlagen, ursache=%s", Ursachen.kette(e));
        } finally {
            laeuft.set(false);
        }
    }
}
