package ch.urinet.archiv.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 03, Req 11.3: Selbsttest der Prüfung «Vertrag nur additiv» ({@code .github/scripts/pruefe-vertrag-test.sh}) im
 * Wegwerf-Repository, damit er ohne eigenen CI-Schritt in den Backend-Tests läuft (Muster {@code CiNachweisTest}).
 * Braucht {@code bash}, {@code git} und {@code ruby} (auf ubuntu-24.04 und macOS vorhanden).
 */
class VertragPruefungTest {

    private static final Path SELBSTTEST = Path.of("..").toAbsolutePath().normalize()
            .resolve(".github/scripts/pruefe-vertrag-test.sh");

    @Test
    void selbsttestIstGruen() throws IOException, InterruptedException {
        Process lauf = new ProcessBuilder("bash", SELBSTTEST.toString()).redirectErrorStream(true).start();
        String ausgabe = new String(lauf.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(lauf.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(lauf.exitValue()).as(ausgabe).isZero();
        assertThat(ausgabe).as(ausgabe).doesNotContain("FEHL").contains("ok   Pfad umbenannt", "ok   neuer Pflichtparameter",
                "ok   Statuscode entfernt", "ok   neuer Wert ohne x-erweiterbar", "ok   Datei gelöscht");
    }
}
