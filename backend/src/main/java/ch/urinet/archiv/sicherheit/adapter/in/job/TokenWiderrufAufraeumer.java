package ch.urinet.archiv.sicherheit.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

@ApplicationScoped
public class TokenWiderrufAufraeumer {

    private static final Logger LOG = Logger.getLogger(TokenWiderrufAufraeumer.class);

    private final TokenWiderruf tokenWiderruf;
    private final boolean worker;

    public TokenWiderrufAufraeumer(TokenWiderruf tokenWiderruf,
                                   @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.tokenWiderruf = tokenWiderruf;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(every = "24h", identity = "token-widerruf-aufraeumen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker) {
            TaktThread.ausfuehren("token-widerruf-aufraeumen", this::aufraeumen);
        }
    }

    public void aufraeumen() {
        int anzahl = tokenWiderruf.aufraeumen(Instant.now().minus(1, ChronoUnit.DAYS));
        LOG.infof("Token-Widerrufe aufgeräumt: %d Einträge", anzahl);
    }
}
