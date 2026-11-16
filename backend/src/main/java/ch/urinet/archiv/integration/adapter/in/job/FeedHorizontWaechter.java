package ch.urinet.archiv.integration.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.application.webseite.FeedHorizontUeberwachung;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;

/**
 * Warnt, wenn der Horizont des Änderungs-Feeds stillsteht (Spec 03, Aufgabe 11.9, design.md «Änderungs-Feed», Grenze
 * des Cursors): Eine lange offene Transaktion hält {@code pg_snapshot_xmin} für den ganzen Cluster an, bis sie endet.
 * Nur als Worker, Takt {@code archiv.webseite.feed-ueberwachung}; die Grenze steht in
 * {@code archiv.webseite.feed-stillstand}. Im Log nur die Dauer, nie Inhalte oder Transaktions-IDs.
 */
@ApplicationScoped
public class FeedHorizontWaechter {

    private static final Logger LOG = Logger.getLogger(FeedHorizontWaechter.class);

    private final FeedHorizontUeberwachung ueberwachung;
    private final boolean worker;

    public FeedHorizontWaechter(FeedHorizontUeberwachung ueberwachung,
                                @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.ueberwachung = ueberwachung;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "feed-horizont", every = "${archiv.webseite.feed-ueberwachung}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP, skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker) {
            try {
                TaktThread.ausfuehren("feed-horizont", this::pruefen);
            } catch (RuntimeException e) {
                LOG.warnf("Prüfung des Feed-Horizonts fehlgeschlagen, ursache=%s", Ursachen.ketteMitMeldung(e));
            }
        }
    }

    void pruefen() {
        ueberwachung.pruefen(Instant.now()).ifPresent(dauer -> LOG.warnf(
                "Der Horizont des Änderungs-Feeds steht seit %d Minuten still: eine lange offene Transaktion hält ihn an; "
                        + "die Webseiten sehen bis zu ihrem Ende nichts Neues (Cache-Dauer gilt)", dauer.toMinutes()));
    }
}
