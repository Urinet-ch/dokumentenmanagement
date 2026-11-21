package ch.urinet.archiv.wissen.adapter.in.job;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.wissen.application.WissenLintStarten;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

@ApplicationScoped
public class WissenLintZeitplan {
    private static final Logger LOG = Logger.getLogger(WissenLintZeitplan.class);
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");
    private final MandantRegister mandanten;
    private final MandantKontext kontext;
    private final WissenLintStarten lint;
    private final boolean worker;

    public WissenLintZeitplan(MandantRegister mandanten, MandantKontext kontext, WissenLintStarten lint,
                              @ConfigProperty(name = "archiv.rolle") String rolle) {
        this.mandanten = mandanten;
        this.kontext = kontext;
        this.lint = lint;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
    }

    @Scheduled(identity = "wissen-lint-zeitplan", every = "30s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void takt() {
        if (worker) {
            TaktThread.ausfuehren("wissen-lint-zeitplan", this::alleStarten);
        }
    }

    private void alleStarten() {
        Instant jetzt = Instant.now();
        for (var mandant : mandanten.aktive()) {
            try {
                kontext.mit(mandant.code(), () -> {
                    faellig(lint.zeitplan(), jetzt).ifPresent(lint::geplant);
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warnf("Wissens-Lint-Zeitplan fehlgeschlagen: mandant=%s ursache=%s",
                        mandant.code().wert(), Ursachen.kette(e));
            }
        }
    }

    static Optional<Instant> faellig(String ausdruck, Instant jetzt) {
        var cron = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)).parse(ausdruck);
        cron.validate();
        return ExecutionTime.forCron(cron).lastExecution(jetzt.atZone(ZUERICH))
                .map(zeit -> zeit.toInstant())
                .filter(termin -> !termin.isAfter(jetzt) && Duration.between(termin, jetzt).compareTo(
                        Duration.ofSeconds(60)) < 0);
    }
}
