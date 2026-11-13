package ch.urinet.archiv.mandant;

import ch.urinet.archiv.gemeinsam.LogMitschnitt;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.SkippedExecution;
import io.quarkus.scheduler.SuccessfulExecution;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Testhilfe für {@link JobsVorMigrationTest}: hält den Start nach {@link StartMandantVorbereitung}, aber vor
 * {@code MigrationBeimStart} für {@code archiv.test.vor-migration-warten} an. In dieser Zeit läuft der Scheduler schon
 * (er startet mit {@code @Priority(0)}); mitgeschnitten werden die übersprungenen und gelaufenen Takte, die Warnungen
 * und Fehler im Log und die Antwort von {@code /q/health/ready}. Ohne den Schlüssel tut die Bean nichts.
 */
@ApplicationScoped
public class VorMigrationBeobachter {

    /** Readiness-Status vor der Migration; -1, wenn der HTTP-Server noch nicht antwortet. */
    private volatile int readinessVorMigration = Integer.MIN_VALUE;
    private volatile boolean vorMigration;
    private final List<String> uebersprungenVor = new CopyOnWriteArrayList<>();
    private final List<String> gelaufenVor = new CopyOnWriteArrayList<>();
    private final List<String> gelaufenNach = new CopyOnWriteArrayList<>();
    private final List<String> warnungenVor = new CopyOnWriteArrayList<>();

    void warten(@Observes @Priority(2) StartupEvent start,
                @ConfigProperty(name = "archiv.test.vor-migration-warten") Optional<Duration> dauer)
            throws InterruptedException {
        if (dauer.isEmpty()) {
            return;
        }
        vorMigration = true;
        try (LogMitschnitt log = LogMitschnitt.starten()) {
            Thread.sleep(dauer.get().toMillis());
            // bei test-port=0 trägt Quarkus den gebundenen Port nach dem Öffnen des Sockets ein
            readinessVorMigration = readiness(ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.http.test-port", Integer.class).orElse(8081));
            for (LogRecord eintrag : log.eintraege()) {
                if (eintrag.getLevel().intValue() >= Level.WARNING.intValue()
                        && eintrag.getLoggerName().startsWith("ch.urinet.archiv")) {
                    warnungenVor.add(eintrag.getLoggerName() + " " + log.nachricht(eintrag));
                }
            }
        } finally {
            vorMigration = false;
        }
    }

    void uebersprungen(@Observes SkippedExecution ausfuehrung) {
        if (vorMigration) {
            uebersprungenVor.add(ausfuehrung.getExecution().getTrigger().getId());
        }
    }

    void gelaufen(@Observes SuccessfulExecution ausfuehrung) {
        (vorMigration ? gelaufenVor : gelaufenNach).add(ausfuehrung.getExecution().getTrigger().getId());
    }

    private static int readiness(int port) throws InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/q/health/ready"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        }
    }

    public int readinessVorMigration() {
        return readinessVorMigration;
    }

    public List<String> uebersprungenVor() {
        return List.copyOf(uebersprungenVor);
    }

    public List<String> gelaufenVor() {
        return List.copyOf(gelaufenVor);
    }

    public List<String> gelaufenNach() {
        return List.copyOf(gelaufenNach);
    }

    public List<String> warnungenVor() {
        return List.copyOf(warnungenVor);
    }
}
