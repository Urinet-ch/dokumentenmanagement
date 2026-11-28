package ch.urinet.archiv.wissen.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.wissen.application.WissenIngestSpeichern;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reaktiviert einmalig Ingest-Entwürfe, die vor dem Handler mit dem Hinweis {@code HANDLER_FEHLT} endeten. */
@ApplicationScoped
public class WissenIngestNachholen {

    private static final Logger LOG = Logger.getLogger(WissenIngestNachholen.class);

    private final MandantRegister mandanten;
    private final MandantKontext kontext;
    private final WissenIngestSpeichern speichern;
    private final Wissensfreischaltung freischaltung;
    private final boolean worker;
    private final AtomicBoolean abgeschlossen = new AtomicBoolean();

    public WissenIngestNachholen(MandantRegister mandanten, MandantKontext kontext, WissenIngestSpeichern speichern,
                                 Wissensfreischaltung freischaltung, @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.freischaltung = freischaltung;
        this.mandanten = mandanten;
        this.kontext = kontext;
        this.speichern = speichern;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "wissen-ingest-nachholen", every = "${archiv.worker.intervall}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker && !abgeschlossen.get()) {
            TaktThread.ausfuehren("wissen-ingest-nachholen", this::alleNachholen);
        }
    }

    void alleNachholen() {
        boolean erfolgreich = true;
        for (Mandant mandant : mandanten.aktive()) {
            if (!freischaltung.freigeschaltet(mandant.code())) {
                // Spec 04, Req 22.9: kein Nachholen ohne Freischaltung, auch nicht später von selbst
                continue;
            }
            try {
                int anzahl = kontext.mit(mandant.code(), speichern::handlerFehltNachholen);
                if (anzahl > 0) {
                    LOG.infof("Wissen-Ingest-Entwürfe nachgeholt: mandant=%s anzahl=%d",
                            mandant.code().wert(), anzahl);
                }
            } catch (RuntimeException e) {
                erfolgreich = false;
                LOG.warnf("Wissen-Ingest-Wiederherstellung fehlgeschlagen: mandant=%s ursache=%s",
                        mandant.code().wert(), Ursachen.kette(e));
            }
        }
        if (erfolgreich) {
            abgeschlossen.set(true);
        }
    }
}
