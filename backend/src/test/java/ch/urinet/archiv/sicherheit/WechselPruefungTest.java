package ch.urinet.archiv.sicherheit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechselPruefungTest {
    @TempDir
    Path temporaer;

    @Test
    void synthetischePruefungenLaufenInDerCi() throws IOException, InterruptedException {
        Path tools = Path.of("..", "tools").toAbsolutePath().normalize();
        Path ausgabe = temporaer.resolve("ausgabe.txt");
        ProcessBuilder builder = new ProcessBuilder("python3", "-m", "unittest", "discover", "-s", tools.toString(),
                "-p", "test_wechsel_pruefen.py").redirectErrorStream(true).redirectOutput(ausgabe.toFile());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        pruefeProzess(builder.start(), ausgabe, Duration.ofSeconds(120));
    }

    @Test
    void haengenderProzessWirdBeendet() throws IOException {
        Path ausgabe = temporaer.resolve("haengend.txt");
        Process lauf = new ProcessBuilder("python3", "-c", "import time; time.sleep(60)")
                .redirectErrorStream(true).redirectOutput(ausgabe.toFile()).start();
        assertThatThrownBy(() -> pruefeProzess(lauf, ausgabe, Duration.ofSeconds(1)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Zeitgrenze");
        assertThat(lauf.isAlive()).isFalse();
    }

    private static void pruefeProzess(Process lauf, Path ausgabe, Duration zeitgrenze)
            throws IOException, InterruptedException {
        try {
            if (!lauf.waitFor(zeitgrenze.toMillis(), TimeUnit.MILLISECONDS)) {
                lauf.destroyForcibly();
                lauf.waitFor();
                throw new AssertionError("Zeitgrenze für die Wechselprüfung überschritten: " + zeitgrenze);
            }
            assertThat(lauf.exitValue()).as(Files.readString(ausgabe, StandardCharsets.UTF_8)).isZero();
        } finally {
            if (lauf.isAlive()) {
                lauf.destroyForcibly();
            }
        }
    }
}
