package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Im Image holt ein Init-Prozess die Nachfahren abgebrochener Werkzeuge ab (Review PR #46, Muss 1): Nach einer
 * Zeitüberschreitung bleibt im Container kein Zombie. Die JVM selbst holt nur ihre eigenen Kinder ab; als PID 1 ohne
 * Init blieben die Nachfahren als Zombies stehen. Das prüft der Test als Kontrolle mit.
 */
class WerkzeugImageInitTest {

    private static final Path DOCKERFILE = Path.of("src/main/docker/Dockerfile.jvm");

    @Test
    void nachZeitueberschreitungBleibtImImageKeinZombie() throws Exception {
        String image = WerkzeugImage.sicherstellen();

        assertThat(sonde(image, false)).as("mit dem ENTRYPOINT des Images").isEqualTo("pid1=tini zombies=0");
        assertThat(sonde(image, true)).as("Kontrolle: java als PID 1 ohne Init").startsWith("pid1=java")
                .doesNotEndWith("zombies=0");
    }

    /** Die Stufe des Archivs setzt nur CMD und erbt damit den ENTRYPOINT mit tini aus der Stufe «werkzeuge». */
    @Test
    void archivStufeErbtDenInitDerWerkzeugStufe() throws Exception {
        String datei = Files.readString(DOCKERFILE);
        String werkzeuge = datei.substring(0, datei.indexOf("FROM werkzeuge"));
        String archiv = datei.substring(datei.indexOf("FROM werkzeuge"));

        assertThat(werkzeuge).contains("ENTRYPOINT [\"tini\", \"-s\", \"--\"]");
        assertThat(archiv).doesNotContain("ENTRYPOINT").contains("CMD [\"sh\", \"-c\", \"exec java ");
    }

    private static String sonde(String image, boolean ohneInit) throws Exception {
        String klassen = Path.of("target/classes").toAbsolutePath().toString();
        String testklassen = Path.of("target/test-classes").toAbsolutePath().toString();
        String logging = Path.of(Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> befehl = new ArrayList<>(List.of("run", "--rm", "--pull", "never", "--network", "none",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "-v", klassen + ":" + klassen + ":ro", "-v", testklassen + ":" + testklassen + ":ro",
                "-v", logging + ":" + logging + ":ro"));
        if (ohneInit) {
            befehl.addAll(List.of("--entrypoint", "java"));
        }
        befehl.add(image);
        if (!ohneInit) {
            befehl.add("java");
        }
        befehl.addAll(List.of("-cp", String.join(":", klassen, testklassen, logging),
                ZombieSonde.class.getName()));
        String ausgabe = WerkzeugImage.dockerAusgabe(befehl.toArray(String[]::new));
        return ausgabe.lines().filter(z -> z.startsWith("pid1=")).findFirst().orElse(ausgabe);
    }
}
