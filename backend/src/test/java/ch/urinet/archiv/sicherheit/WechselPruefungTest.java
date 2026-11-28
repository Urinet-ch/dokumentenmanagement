package ch.urinet.archiv.sicherheit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WechselPruefungTest {
    @Test
    void synthetischePruefungenLaufenInDerCi() throws IOException, InterruptedException {
        Path tools = Path.of("..", "tools").toAbsolutePath().normalize();
        ProcessBuilder builder = new ProcessBuilder("python3", "-m", "unittest", "discover", "-s", tools.toString(),
                "-p", "test_wechsel_pruefen.py").redirectErrorStream(true);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process lauf = builder.start();
        String ausgabe = new String(lauf.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(lauf.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(lauf.exitValue()).as(ausgabe).isZero();
    }
}
