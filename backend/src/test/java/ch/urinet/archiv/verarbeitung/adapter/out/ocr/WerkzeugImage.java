package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Baut das Image der Stufe {@code werkzeuge} aus {@code Dockerfile.jvm} einmal je JVM, falls es fehlt (ohne
 * Build-Kontext, die Stufe kopiert nichts). Die Tests rufen die Werkzeuge darin per {@code docker run} auf
 * (Container-Modus), damit sie auf jedem Rechner mit Docker dieselben Versionen nutzen wie das Image der VPS.
 */
final class WerkzeugImage {

    static final String NAME = "urinet-archiv-werkzeuge:test";
    private static boolean bereit;

    private WerkzeugImage() {
    }

    static synchronized String sicherstellen() throws IOException, InterruptedException {
        if (bereit) {
            return NAME;
        }
        if (docker("image", "inspect", NAME) != 0) {
            Process bau = new ProcessBuilder("docker", "build", "-q", "--target", "werkzeuge", "-t", NAME, "-")
                    .redirectInput(Path.of("src/main/docker/Dockerfile.jvm").toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start();
            if (!bau.waitFor(20, TimeUnit.MINUTES) || bau.exitValue() != 0) {
                throw new IllegalStateException("Werkzeug-Image nicht gebaut (Docker nötig, siehe backend/README.md)");
            }
        }
        bereit = true;
        return NAME;
    }

    static int docker(String... argumente) throws IOException, InterruptedException {
        var befehl = new java.util.ArrayList<String>();
        befehl.add("docker");
        befehl.addAll(java.util.List.of(argumente));
        Process p = new ProcessBuilder(befehl).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    static String dockerAusgabe(String... argumente) throws IOException, InterruptedException {
        var befehl = new java.util.ArrayList<String>();
        befehl.add("docker");
        befehl.addAll(java.util.List.of(argumente));
        Process p = new ProcessBuilder(befehl).redirectErrorStream(true).start();
        String ausgabe = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor(2, TimeUnit.MINUTES);
        return ausgabe.trim();
    }
}
