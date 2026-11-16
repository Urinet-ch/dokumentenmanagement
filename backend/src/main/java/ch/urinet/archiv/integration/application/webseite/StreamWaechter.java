package ch.urinet.archiv.integration.application.webseite;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Der Zeitgeber der Zeitlimits eines Streams (Spec 03, Aufgabe 11.8, Req 13.7): ein einziger Daemon-Thread für alle
 * Streams. Er führt nur kurze Aufgaben aus (Zustand prüfen, Verbindung verwerfen), nie Lesen oder Schreiben der Datei.
 */
@ApplicationScoped
public class StreamWaechter {

    private final ScheduledExecutorService zeitgeber = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-waechter");
        t.setDaemon(true);
        return t;
    });

    public ScheduledExecutorService zeitgeber() {
        return zeitgeber;
    }

    @PreDestroy
    void beenden() {
        zeitgeber.shutdownNow();
    }
}
