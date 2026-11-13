package ch.urinet.archiv.integration.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.integration.application.mail.MailEingang;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;

/** Ruft alle {@code archiv.mail.intervall} (Standard 10 Minuten) die Postfächer ab (Spec 02, Req 1.1), nur als Worker. */
@ApplicationScoped
public class MailTakt {

    private static final Logger LOG = Logger.getLogger(MailTakt.class);

    private final MailEingang eingang;
    private final boolean worker;

    public MailTakt(MailEingang eingang, @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.eingang = eingang;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "mail-eingang", every = "${archiv.mail.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void abrufen() {
        if (worker) {
            try {
                eingang.abrufen();
            } catch (RuntimeException e) {
                // nächster Takt versucht es wieder; nur Klassennamen (Req 16.7)
                LOG.warnf("Mail-Eingang fehlgeschlagen, ursache=%s", Ursachen.kette(e));
            }
        }
    }
}
