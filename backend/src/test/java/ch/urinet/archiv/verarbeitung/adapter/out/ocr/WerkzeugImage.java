package ch.urinet.archiv.verarbeitung.adapter.out.ocr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Baut das Image der Stufe {@code werkzeuge} aus {@code Dockerfile.jvm} einmal je JVM (ohne Build-Kontext, die Stufe
 * kopiert nichts). Gebaut wird immer, auch wenn das Image schon besteht: Mit dem Cache von Docker kostet das wenig, und
 * eine geänderte Stufe gelangt sicher in die Tests. Die Tests rufen die Werkzeuge darin per {@code docker run} auf
 * (Container-Modus), damit sie auf jedem Rechner mit Docker dieselben Versionen nutzen wie das Image der VPS.
 */
public final class WerkzeugImage {

    static final String NAME = "urinet-archiv-werkzeuge:test";
    private static boolean bereit;

    private WerkzeugImage() {
    }

    public static synchronized String sicherstellen() throws IOException, InterruptedException {
        if (bereit) {
            return NAME;
        }
        Process bau = new ProcessBuilder("docker", "build", "-q", "--target", "werkzeuge", "-t", NAME, "-")
                .redirectInput(Path.of("src/main/docker/Dockerfile.jvm").toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        if (!bau.waitFor(20, TimeUnit.MINUTES) || bau.exitValue() != 0) {
            throw new IllegalStateException("Werkzeug-Image nicht gebaut (Docker nötig, siehe backend/README.md)");
        }
        bereit = true;
        return NAME;
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
