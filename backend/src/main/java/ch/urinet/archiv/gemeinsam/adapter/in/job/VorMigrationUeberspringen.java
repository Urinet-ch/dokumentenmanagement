package ch.urinet.archiv.gemeinsam.adapter.in.job;

import ch.urinet.archiv.gemeinsam.application.SchemaBereitschaft;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.inject.Singleton;

/**
 * Überspringt einen Takt, solange die Mandanten-Schemas nicht migriert bzw. geprüft sind ({@link SchemaBereitschaft}).
 * Der Scheduler startet vor allen Start-Beobachtern ({@code @Priority(0)}) und löst {@code every}-Takte schon nach
 * einer Sekunde aus, also womöglich vor {@code MigrationBeimStart}. Jeder geplante Job mit Mandanten-Daten trägt
 * darum {@code skipExecutionIf = VorMigrationUeberspringen.class} (Nachweis {@code GeplanteJobsTest}). Der Scheduler
 * meldet das Überspringen nur als Ereignis {@code SkippedExecution}, ohne Warnung im Log.
 */
@Singleton
public class VorMigrationUeberspringen implements Scheduled.SkipPredicate {

    private final SchemaBereitschaft bereitschaft;

    public VorMigrationUeberspringen(SchemaBereitschaft bereitschaft) {
        this.bereitschaft = bereitschaft;
    }

    @Override
    public boolean test(ScheduledExecution ausfuehrung) {
        return !bereitschaft.bereit();
    }
}
