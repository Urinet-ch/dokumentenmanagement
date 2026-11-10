package ch.urinet.archiv.mandant;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin-Kommando {@code archiv mandant anlegen <code>} (ADR-002) über denselben Einstieg wie der Dienst. */
@QuarkusMainTest
class MandantKommandoTest {

    @Test
    @Launch(value = {"mandant", "anlegen", "probe-kommando", "--geschaeftsjahr-ende", "03-31"})
    void anlegen(LaunchResult ergebnis) {
        assertThat(ergebnis.exitCode()).isZero();
        assertThat(ergebnis.getOutput()).contains("Mandant probe-kommando angelegt", "Schema m_probe_kommando",
                "Bucket urinet-archiv-probe-kommando", "Geschäftsjahresende 03-31");
    }

    @Test
    @Launch(value = {"mandant", "anlegen"}, exitCode = 2)
    void falscherAufruf(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Aufruf: archiv mandant anlegen");
    }

    @Test
    @Launch(value = {"unbekannt"}, exitCode = 2)
    void unbekanntesKommando(LaunchResult ergebnis) {
        assertThat(ergebnis.getErrorOutput()).contains("Unbekanntes Kommando");
    }
}
