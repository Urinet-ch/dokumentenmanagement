package ch.urinet.archiv.ki.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.ki.application.chat.ChatAufraeumen;
import ch.urinet.archiv.ki.domain.chat.Aufgeraeumt;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;

/**
 * Löscht täglich je aktivem Mandanten Chat-Nachrichten nach der Aufbewahrung und danach leere Gespräche (Spec 02,
 * Req 5.4), nur als Worker. Takt {@code archiv.chat.aufraeumen} (Cron in Europe/Zurich, Standard 03:30). Läuft in einem
 * eigenen Thread ohne den Request-Kontext des Schedulers ({@link TaktThread}), sonst verweigert {@code MandantKontext.mit}
 * den Mandanten. Im Log nur Mandant und Anzahlen.
 */
@ApplicationScoped
public class ChatAufraeumer {

    private static final Logger LOG = Logger.getLogger(ChatAufraeumer.class);

    private final MandantRegister mandanten;
    private final ChatAufraeumen aufraeumen;
    private final boolean worker;

    public ChatAufraeumer(MandantRegister mandanten, ChatAufraeumen aufraeumen,
                          @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.mandanten = mandanten;
        this.aufraeumen = aufraeumen;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "chat-aufraeumen", cron = "${archiv.chat.aufraeumen}", timeZone = "Europe/Zurich",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker) {
            TaktThread.ausfuehren("chat-aufraeumen", this::alleAufraeumen);
        }
    }

    /** Alle aktiven Mandanten; ein Fehler bei einem hält die übrigen nicht auf. Nicht im Request-Kontext aufrufen. */
    public void alleAufraeumen() {
        Instant jetzt = Instant.now();
        for (Mandant mandant : mandanten.aktive()) {
            try {
                Aufgeraeumt ergebnis = aufraeumen.aufraeumen(mandant.code(), jetzt);
                LOG.infof("Chat aufgeräumt für Mandant %s: %d Nachrichten und %d Gespräche gelöscht",
                        mandant.code().wert(), ergebnis.nachrichten(), ergebnis.gespraeche());
            } catch (RuntimeException e) {
                // der nächste tägliche Takt versucht es wieder; Klassennamen, feste Meldungen (Req 16.7)
                LOG.warnf("Chat-Aufräumen für Mandant %s fehlgeschlagen, ursache=%s", mandant.code().wert(),
                        Ursachen.ketteMitMeldung(e));
            }
        }
    }
}
