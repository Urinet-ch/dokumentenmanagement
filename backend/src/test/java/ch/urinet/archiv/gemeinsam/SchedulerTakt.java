package ch.urinet.archiv.gemeinsam;

import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import io.quarkus.arc.Arc;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Löst einen Takt wie im Betrieb aus: Ein Job des Quarkus-Schedulers ({@code Scheduler.newJob}, gleicher
 * {@code DefaultInvoker} wie {@code @Scheduled}) ruft ihn einmal auf, im Thread des Schedulers mit dem Request-Kontext,
 * den Quarkus für jeden Takt aktiviert, und ohne Anmeldung (Befund prod 13.11.2026). Geprüft wird diese Bedingung im
 * Thread des Schedulers; ein gewöhnlicher Executor hätte keinen Request-Kontext und verdeckte den Befund.
 */
public final class SchedulerTakt {

    private SchedulerTakt() {
    }

    public static void ausloesen(Scheduler scheduler, MandantAusAnmeldung anmeldung, String name, Runnable takt)
            throws Exception {
        String job = name + "-szenario-" + UUID.randomUUID();
        AtomicBoolean gestartet = new AtomicBoolean();
        CompletableFuture<String> lauf = new CompletableFuture<>();
        scheduler.newJob(job).setInterval("1s").setConcurrentExecution(Scheduled.ConcurrentExecution.SKIP)
                .setTask(ausfuehrung -> {
                    if (!gestartet.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        String bedingung = "requestKontext=" + Arc.container().requestContext().isActive()
                                + " anmeldung=" + anmeldung.ermitteln().getClass().getSimpleName();
                        takt.run();
                        lauf.complete(bedingung);
                    } catch (Throwable t) {
                        lauf.completeExceptionally(t);
                    }
                }).schedule();
        try {
            assertThat(lauf.get(2, TimeUnit.MINUTES)).as("Bedingungen im Thread des Schedulers")
                    .isEqualTo("requestKontext=true anmeldung=Anonym");
        } finally {
            scheduler.unscheduleJob(job);
        }
    }
}
