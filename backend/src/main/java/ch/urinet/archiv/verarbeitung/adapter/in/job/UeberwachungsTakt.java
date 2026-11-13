package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Prüft alle {@code archiv.ueberwachung.intervall} auf Befunde und alarmiert den Inhaber (Req 16.5), nur als Worker. */
@ApplicationScoped
public class UeberwachungsTakt {

    private static final Logger LOG = Logger.getLogger(UeberwachungsTakt.class);

    private final Ueberwachung ueberwachung;
    private final boolean worker;

    public UeberwachungsTakt(Ueberwachung ueberwachung, @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.ueberwachung = ueberwachung;
        this.worker = WorkerRolle.aktiv(rolle);
    }

    @Scheduled(identity = "ueberwachung", every = "${archiv.ueberwachung.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pruefen() {
        if (worker) {
            try {
                ueberwachung.pruefen();
            } catch (RuntimeException e) {
                // nächster Takt versucht es wieder; nur Klassennamen, keine Meldungen (Req 16.7)
                LOG.warnf("Überwachung fehlgeschlagen, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
