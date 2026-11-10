package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.verarbeitung.application.AuftragAufraeumen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Gibt alle {@code archiv.auftrag.aufraeumen-intervall} verwaiste Aufträge frei (Req 5.7), nur mit der Rolle {@code worker}. */
@ApplicationScoped
public class AuftragAufraeumer {

    private static final Logger LOG = Logger.getLogger(AuftragAufraeumer.class);

    private final AuftragAufraeumen aufraeumen;
    private final boolean worker;

    public AuftragAufraeumer(AuftragAufraeumen aufraeumen, @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.aufraeumen = aufraeumen;
        this.worker = WorkerRolle.aktiv(rolle);
    }

    @Scheduled(identity = "auftrag-aufraeumer", every = "${archiv.auftrag.aufraeumen-intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void aufraeumen() {
        if (worker) {
            try {
                aufraeumen.aufraeumen();
            } catch (RuntimeException e) {
                // nächster Takt versucht es wieder; nur Klassennamen, keine Meldungen (Req 16.7)
                LOG.warnf("Aufräumen fehlgeschlagen, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
