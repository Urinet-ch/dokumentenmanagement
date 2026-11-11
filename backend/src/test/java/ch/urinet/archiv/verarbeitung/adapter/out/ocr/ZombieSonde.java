package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import ch.urinet.archiv.verarbeitung.domain.OcrFehlgeschlagen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Läuft im Werkzeug-Image (siehe {@link WerkzeugImageInitTest}): startet ein Werkzeug mit zwei Nachfahren, lässt die
 * Frist ablaufen und zählt danach die Zombies im Container. Ausgabe: {@code pid1=<name> zombies=<anzahl>}.
 */
final class ZombieSonde {

    private ZombieSonde() {
    }

    public static void main(String[] args) throws Exception {
        Path lauf = Files.createTempDirectory("lauf-");
        var einstellungen = new OcrEinstellungen(Duration.ofMinutes(1), 1, lauf.getParent(), Optional.empty(),
                "docker", "/usr/bin:/bin");
        try {
            new Werkzeugstarter(einstellungen, Clock.systemUTC()).ausfuehren(
                    Befehl.von("sh", "-c", "sleep 60 & sleep 60 & wait"), lauf, Instant.now().plusSeconds(2));
            throw new IllegalStateException("Frist nicht abgelaufen");
        } catch (OcrFehlgeschlagen erwartet) {
            // Zeitüberschreitung: Werkzeugstarter hat Prozess und Nachfahren beendet
        }
        Instant ende = Instant.now().plusSeconds(3);
        long zombies = zombies();
        while (zombies > 0 && Instant.now().isBefore(ende)) {
            Thread.sleep(100);
            zombies = zombies();
        }
        System.out.println("pid1=" + Files.readString(Path.of("/proc/1/comm")).trim() + " zombies=" + zombies);
    }

    private static long zombies() throws IOException {
        try (var prozesse = Files.list(Path.of("/proc"))) {
            return prozesse.filter(p -> p.getFileName().toString().matches("\\d+"))
                    .map(p -> p.resolve("stat")).filter(ZombieSonde::istZombie).count();
        }
    }

    private static boolean istZombie(Path stat) {
        try {
            String zeile = Files.readString(stat);
            return zeile.substring(zeile.lastIndexOf(')') + 2).startsWith("Z");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
