package ch.urinet.archiv.ki.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.ki.application.oeffentlich.OeffentlicheFragenAufraeumen;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;

/**
 * Löscht täglich je aktivem Mandanten die gespeicherten Fragen des öffentlichen Chatbots nach der Aufbewahrung (Spec 03,
 * Req 6.5: 90 Tage), nur als Worker. Takt {@code archiv.webseite.chat.aufraeumen} (Cron in Europe/Zurich, Standard 03:40).
 * Läuft in einem eigenen Thread ohne den Request-Kontext des Schedulers ({@link TaktThread}). Im Log nur Mandant und
 * Anzahl.
 */
@ApplicationScoped
public class OeffentlicheFragenAufraeumer {

    private static final Logger LOG = Logger.getLogger(OeffentlicheFragenAufraeumer.class);

    private final MandantRegister mandanten;
    private final OeffentlicheFragenAufraeumen aufraeumen;
    private final boolean worker;

    public OeffentlicheFragenAufraeumer(MandantRegister mandanten, OeffentlicheFragenAufraeumen aufraeumen,
                                        @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.mandanten = mandanten;
        this.aufraeumen = aufraeumen;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "oeffentliche-fragen-aufraeumen", cron = "${archiv.webseite.chat.aufraeumen}",
            timeZone = "Europe/Zurich", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker) {
            TaktThread.ausfuehren("oeffentliche-fragen-aufraeumen", this::alleAufraeumen);
        }
    }

    /** Alle aktiven Mandanten; ein Fehler bei einem hält die übrigen nicht auf. Nicht im Request-Kontext aufrufen. */
    public void alleAufraeumen() {
        Instant jetzt = Instant.now();
        for (Mandant mandant : mandanten.aktive()) {
            try {
                int geloescht = aufraeumen.aufraeumen(mandant.code(), jetzt);
                LOG.infof("Öffentliche Fragen aufgeräumt für Mandant %s: %d gelöscht", mandant.code().wert(), geloescht);
            } catch (RuntimeException e) {
                // der nächste tägliche Takt versucht es wieder; Klassennamen, feste Meldungen (Req 16.7)
                LOG.warnf("Aufräumen der öffentlichen Fragen für Mandant %s fehlgeschlagen, ursache=%s",
                        mandant.code().wert(), Ursachen.ketteMitMeldung(e));
            }
        }
    }
}
