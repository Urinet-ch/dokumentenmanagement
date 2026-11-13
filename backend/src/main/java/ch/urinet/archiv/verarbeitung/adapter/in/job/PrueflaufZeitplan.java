package ch.urinet.archiv.verarbeitung.adapter.in.job;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.verarbeitung.application.PrueflaufStarten;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Startet den monatlichen Prüflauf jedes aktiven Mandanten (Req 15.5), nur mit der Rolle {@code worker}. Der Takt
 * ({@code archiv.prueflauf.zeitplan}, Cron in Europe/Zurich, Standard täglich um 04:00) löst täglich aus; weil
 * {@code prueflauf.monat} eindeutig ist, legt nur der erste Start eines Monats einen Lauf an. So holt der nächste Tag
 * einen verpassten (Worker zum Zeitpunkt nicht aktiv) oder gescheiterten Start nach.
 */
@ApplicationScoped
public class PrueflaufZeitplan {

    private static final Logger LOG = Logger.getLogger(PrueflaufZeitplan.class);
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final MandantRegister mandanten;
    private final PrueflaufStarten starten;
    private final Clock uhr;
    private final boolean worker;

    @Inject
    public PrueflaufZeitplan(MandantRegister mandanten, PrueflaufStarten starten,
                             @ConfigProperty(name = "archiv.rolle") String rolle) {
        this(mandanten, starten, rolle, Clock.systemUTC());
    }

    public PrueflaufZeitplan(MandantRegister mandanten, PrueflaufStarten starten, String rolle, Clock uhr) {
        this.mandanten = mandanten;
        this.starten = starten;
        this.uhr = uhr;
        this.worker = WorkerRolle.aktiv(rolle);
    }

    @Scheduled(identity = "prueflauf", cron = "${archiv.prueflauf.zeitplan}", timeZone = "Europe/Zurich",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    public void starten() {
        if (!worker) {
            return;
        }
        YearMonth monat = YearMonth.now(uhr.withZone(ZUERICH));
        for (Mandant mandant : mandanten.aktive()) {
            try {
                starten.starten(mandant.code(), Optional.of(monat))
                        .ifPresent(id -> LOG.infof("Prüflauf %s für Mandant %s gestartet", id, mandant.code().wert()));
            } catch (RuntimeException e) {
                // der nächste tägliche Takt versucht es wieder; nur Klassennamen (Req 16.7)
                LOG.warnf("Prüflauf für Mandant %s nicht gestartet, ursache=%s", mandant.code().wert(),
                        Ursachen.kette(e));
            }
        }
    }
}
