package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.AuftragHandlerVerzeichnis;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Holt alle {@code archiv.worker.intervall} fällige Aufträge (design.md «Verarbeitung»), nur mit der Rolle
 * {@code worker}. Quarkus aktiviert in {@code @Scheduled}-Methoden einen Request-Kontext; der {@link AuftragArbeiter}
 * führt die Aufträge darum in eigenen Threads aus und setzt den Mandanten erst dort (design.md «Mandant»).
 */
@ApplicationScoped
public class AuftragPoller {

    private static final Logger LOG = Logger.getLogger(AuftragPoller.class);

    private final AuftragArbeiter arbeiter;
    private final AuftragHandlerVerzeichnis verzeichnis;
    private final boolean worker;

    public AuftragPoller(AuftragArbeiter arbeiter, AuftragHandlerVerzeichnis verzeichnis,
                         @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.arbeiter = arbeiter;
        this.verzeichnis = verzeichnis;
        this.worker = WorkerRolle.aktiv(rolle);
    }

    /** Handler und Zeitgrenzen beim Start prüfen, auch für die API, die Aufträge einreiht. */
    void beimStart(@Observes StartupEvent start) {
        verzeichnis.typen();
    }

    /**
     * Geordneter Stopp (z. B. Deploy): Laufende Aufträge gehen ohne Erhöhung von {@code versuche} zurück auf
     * {@code OFFEN}, sofern ihr Handler innerhalb von {@code archiv.worker.stopp-frist} endet.
     */
    void beimStopp(@Observes ShutdownEvent stopp) {
        arbeiter.herunterfahren();
    }

    @Scheduled(identity = "auftrag-poller", every = "${archiv.worker.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void abholen() {
        if (worker) {
            try {
                arbeiter.abholen();
            } catch (RuntimeException e) {
                // nächster Takt versucht es wieder; nur Klassennamen, keine Meldungen (Req 16.7)
                LOG.warnf("Aufträge nicht geholt, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
