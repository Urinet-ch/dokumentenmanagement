package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.verarbeitung.application.Ueberwachung;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/** Prüft alle {@code archiv.ueberwachung.intervall} auf Befunde und alarmiert den Inhaber (Req 16.5), nur als Worker. */
@ApplicationScoped
public class UeberwachungsTakt {

    private static final Logger LOG = Logger.getLogger(UeberwachungsTakt.class);

    private final Ueberwachung ueberwachung;
    private final boolean worker;
    private final Stufe stufe;

    public UeberwachungsTakt(Ueberwachung ueberwachung, @ConfigProperty(name = "archiv.rolle") String rolle,
                             @ConfigProperty(name = "archiv.stufe", defaultValue = "B") String stufe) {
        this.ueberwachung = ueberwachung;
        this.worker = WorkerRolle.aktiv(rolle);
        this.stufe = Stufe.aus(Optional.ofNullable(stufe));
    }

    @Scheduled(identity = "ueberwachung", every = "${archiv.ueberwachung.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void pruefen() {
        if (worker && stufe != Stufe.P) {
            try {
                ueberwachung.pruefen();
            } catch (RuntimeException e) {
                // nächster Takt versucht es wieder; nur Klassennamen, keine Meldungen (Req 16.7)
                LOG.warnf("Überwachung fehlgeschlagen, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
