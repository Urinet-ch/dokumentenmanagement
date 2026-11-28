package ch.urinet.archiv.sftp;

import com.sun.security.auth.module.UnixSystem;
import org.apache.sshd.server.SshServer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * Startpunkt des Dienstes {@code sftp} (Scan-Eingang, Spec 09, ADR-016). Ohne Quarkus; Konfiguration aus der Umgebung
 * ({@link Konfiguration}), Log als JSON auf stdout.
 */
public final class ScanEingang {

    private ScanEingang() {
    }

    public static void main(String[] args) throws Exception {
        JsonLog log = JsonLog.auf(System.out);
        if (new UnixSystem().getUid() == 0) {
            log.ereignis("start", "ergebnis", "abgebrochen", "grund", "Der Dienst läuft nie als root");
            System.exit(1);
        }
        Konfiguration konfiguration;
        ScanServer.Eingang eingang;
        try {
            konfiguration = Konfiguration.aus(System.getenv());
            eingang = ScanServer.bauen(konfiguration, log);
        } catch (IllegalArgumentException | IOException e) {
            log.ereignis("start", "ergebnis", "abgebrochen", "grund", e.getMessage());
            System.exit(1);
            return;
        }
        SshServer server = eingang.server();
        server.start();
        log.ereignis("start", "ergebnis", "bereit", "port", konfiguration.port(), "benutzer", eingang.benutzer(),
                "aeltereVerfahren", konfiguration.aeltereVerfahren(), "verfahren", Verfahren.namen(server));

        CountDownLatch ende = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Exception e) {
                // beim Beenden ohne Folgen
            }
            log.ereignis("stopp");
            ende.countDown();
        }));
        ende.await();
    }
}
