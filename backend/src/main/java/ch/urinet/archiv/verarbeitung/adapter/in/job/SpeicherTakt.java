package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Prüft alle {@code archiv.ueberwachung.speicher.intervall} den Speicher der eigenen JVM (Req 16.5, Frühwarnung vor
 * {@code OutOfMemoryError}). Anders als die übrigen Takte läuft er in jeder Rolle, weil jede JVM nur ihren eigenen
 * Speicher sieht; die Stufe P sendet keine Mails und überspringt ihn. Die Arbeit läuft wie bei den anderen Takten im
 * eigenen Thread ohne Request-Kontext ({@link TaktThread}).
 */
@ApplicationScoped
public class SpeicherTakt {

    private static final Logger LOG = Logger.getLogger(SpeicherTakt.class);

    private final SpeicherUeberwachung ueberwachung;
    private final Stufe stufe;

    public SpeicherTakt(SpeicherUeberwachung ueberwachung,
                        @ConfigProperty(name = "archiv.stufe", defaultValue = "B") String stufe) {
        this.ueberwachung = ueberwachung;
        this.stufe = Stufe.aus(Optional.ofNullable(stufe));
    }

    @Scheduled(identity = "speicher-ueberwachung", every = "${archiv.ueberwachung.speicher.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void pruefen() {
        if (stufe != Stufe.P) {
            try {
                TaktThread.ausfuehren("speicher-ueberwachung", ueberwachung::pruefen);
            } catch (RuntimeException e) {
                LOG.warnf("Speicherüberwachung fehlgeschlagen, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
