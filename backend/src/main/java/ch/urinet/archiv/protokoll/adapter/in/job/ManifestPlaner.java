package ch.urinet.archiv.protokoll.adapter.in.job;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.protokoll.application.ManifestPlanen;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.gemeinsam.adapter.in.job.VorMigrationUeberspringen;
import ch.urinet.archiv.gemeinsam.application.SchemasBereit;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;

/**
 * Täglicher Lauf des Tages-Manifests um 03:30 Europe/Zurich (Req 15.1, 15.7; {@code archiv.manifest.zeitplan}) und
 * einmal beim Start ({@code archiv.manifest.beim-start}), damit verpasste Läufe nachgeholt werden (Req 15.6). Nur mit
 * der Rolle {@code worker}; die Aufträge selbst laufen über die Auftragsschlange.
 * <p>
 * Der Lauf beim Start folgt auf {@link SchemasBereit}, also erst nach der Migration bzw. Prüfung der Mandanten-Schemas;
 * ein Takt vor der Migration wird übersprungen ({@link VorMigrationUeberspringen}). Fällt der Zeitplan in die Migration,
 * holt der Lauf beim Start ihn nach (design.md «Mandant», «Start und Migration»).
 * <p>
 * Beide Läufe planen in einem eigenen Thread ohne den Request-Kontext des Schedulers ({@link TaktThread}), sonst
 * verweigert {@code MandantKontext.mit} im Takt den Mandanten.
 */
@ApplicationScoped
public class ManifestPlaner {

    private static final Logger LOG = Logger.getLogger(ManifestPlaner.class);

    private final ManifestPlanen planen;
    private final boolean worker;
    private final boolean beimStart;

    public ManifestPlaner(ManifestPlanen planen, @ConfigProperty(name = "archiv.rolle") String rolle,
                          @ConfigProperty(name = "archiv.manifest.beim-start") boolean beimStart) {
        this.planen = planen;
        this.worker = rolle != null && Arrays.stream(rolle.split(",")).map(String::trim).anyMatch("worker"::equals);
        this.beimStart = beimStart;
    }

    void start(@Observes SchemasBereit ereignis) {
        if (beimStart) {
            planen();
        }
    }

    @Scheduled(identity = "manifest-planer", cron = "${archiv.manifest.zeitplan}", timeZone = "Europe/Zurich",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = VorMigrationUeberspringen.class)
    void planen() {
        if (worker) {
            try {
                TaktThread.ausfuehren("manifest-planer", () -> planen.planen(Instant.now()));
            } catch (RuntimeException e) {
                // nächster Lauf (spätestens morgen, sonst beim Start) holt nach; Klassennamen, feste Meldungen (Req 16.7)
                LOG.errorf("Manifest-Planung fehlgeschlagen, ursache=%s", Ursachen.ketteMitMeldung(e));
            }
        }
    }
}
