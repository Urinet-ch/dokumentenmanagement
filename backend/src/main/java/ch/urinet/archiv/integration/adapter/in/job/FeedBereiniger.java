package ch.urinet.archiv.integration.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.application.webseite.AenderungFeedAufraeumen;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;

/**
 * Auftrag {@code FEED_BEREINIGEN} (Spec 03, Aufgabe 11.9, Req 14.4): löscht täglich je aktivem Mandanten die Einträge
 * des Änderungs-Feeds nach der Aufbewahrung (90 Tage), nur als Worker. Takt {@code archiv.webseite.feed-bereinigen}
 * (Cron in Europe/Zurich, Standard 03:50). Läuft in einem eigenen Thread ohne den Request-Kontext des Schedulers
 * ({@link TaktThread}). Im Log nur Mandant und Anzahl.
 */
@ApplicationScoped
public class FeedBereiniger {

    private static final Logger LOG = Logger.getLogger(FeedBereiniger.class);

    private final MandantRegister mandanten;
    private final AenderungFeedAufraeumen aufraeumen;
    private final boolean worker;

    public FeedBereiniger(MandantRegister mandanten, AenderungFeedAufraeumen aufraeumen,
                          @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.mandanten = mandanten;
        this.aufraeumen = aufraeumen;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "feed-bereinigen", cron = "${archiv.webseite.feed-bereinigen}", timeZone = "Europe/Zurich",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP, skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker) {
            TaktThread.ausfuehren("feed-bereinigen", this::alleBereinigen);
        }
    }

    /** Alle aktiven Mandanten; ein Fehler bei einem hält die übrigen nicht auf. Nicht im Request-Kontext aufrufen. */
    public void alleBereinigen() {
        Instant jetzt = Instant.now();
        for (Mandant mandant : mandanten.aktive()) {
            try {
                int geloescht = aufraeumen.aufraeumen(mandant.code(), jetzt);
                LOG.infof("Änderungs-Feed bereinigt für Mandant %s: %d gelöscht", mandant.code().wert(), geloescht);
            } catch (RuntimeException e) {
                // der nächste tägliche Takt versucht es wieder; Klassennamen, feste Meldungen (Req 16.7)
                LOG.warnf("Bereinigen des Änderungs-Feeds für Mandant %s fehlgeschlagen, ursache=%s",
                        mandant.code().wert(), Ursachen.ketteMitMeldung(e));
            }
        }
    }
}
