package ch.urinet.archiv.ki.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheGespraeche;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OeffentlicheGespraecheAufraeumer {

    private final OeffentlicheGespraeche gespraeche;

    public OeffentlicheGespraecheAufraeumer(OeffentlicheGespraeche gespraeche) {
        this.gespraeche = gespraeche;
    }

    @Scheduled(every = "${archiv.webseite.chat.gespraech-aufraeumtakt}", identity = "oeffentliche-gespraeche-aufraeumen",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        gespraeche.abgelaufeneAufraeumen();
    }
}
